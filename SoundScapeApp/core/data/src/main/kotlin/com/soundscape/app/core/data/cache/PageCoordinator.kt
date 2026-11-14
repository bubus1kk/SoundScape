package com.soundscape.app.core.data.cache

import com.soundscape.app.core.database.CacheCapacityExceededException
import com.soundscape.app.core.domain.model.CachedPage
import com.soundscape.app.core.domain.model.CachedPageState
import com.soundscape.app.core.domain.model.DomainError
import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.Page
import com.soundscape.app.core.domain.model.PageRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private data class RefreshStatus(
    val running: Boolean = false, val error: DomainError? = null, val attempt: Long = 0,
)
private class QueryEntry {
    val mutex = Mutex()
    val status = HashMap<PageRequest, MutableStateFlow<RefreshStatus>>()
    val completed = HashMap<PageRequest, Long>()
    val results = HashMap<PageRequest, DomainResult<Unit>>()
    var references = 0
    fun state(page: PageRequest) = synchronized(this) { status.getOrPut(page) { MutableStateFlow(RefreshStatus()) } }
    fun version(page: PageRequest) = synchronized(this) { completed[page] ?: 0 }
}

/** Serializes each query, coalesces overlapping identical pages, and owns only observed/in-flight state. */
class PageCoordinator<T>(
    private val storage: PageStorage<T>,
    private val offline: () -> Boolean,
    private val guestAllowed: () -> Boolean,
) {
    private val entries = HashMap<Pair<String, String>, QueryEntry>()
    private fun acquire(address: PageAddress): QueryEntry = synchronized(entries) {
        entries.getOrPut(address.scope to address.key) { QueryEntry() }.also { it.references++ }
    }
    private fun release(address: PageAddress, entry: QueryEntry) = synchronized(entries) {
        entry.references--
        if (entry.references == 0) entries.remove(address.scope to address.key)
        Unit
    }

    /** Reads cache first, with no hidden background work. Storage failures cannot become success. */
    suspend fun get(address: PageAddress, remote: suspend () -> DomainResult<Page<T>>): DomainResult<Page<T>> =
        guarded {
            if (!guestAllowed()) return@guarded DomainResult.Failure(DomainError.Forbidden)
            val cached = storage.read(address)
            if (cached != null) return@guarded DomainResult.Success(cached.page)
            when (val result = refresh(address, remote)) {
                is DomainResult.Failure -> result
                is DomainResult.Success -> storage.read(address)?.let { DomainResult.Success(it.page) }
                    ?: DomainResult.Failure(DomainError.Unexpected)
            }
        }

    /** Refresh is explicit and belongs to the caller's coroutine; cancellation never becomes an error. */
    suspend fun refresh(address: PageAddress, remote: suspend () -> DomainResult<Page<T>>): DomainResult<Unit> {
        val entry = acquire(address)
        val version = entry.version(address.page)
        try {
            return entry.mutex.withLock {
                if (entry.version(address.page) != version) return@withLock entry.results.getValue(address.page)
                val state = entry.state(address.page)
                state.value = RefreshStatus(running = true, attempt = state.value.attempt + 1)
                try {
                    val result = guarded {
                        when {
                            !guestAllowed() -> DomainResult.Failure(DomainError.Forbidden)
                            offline() -> DomainResult.Failure(DomainError.Network)
                            else -> load(address, remote)
                        }
                    }
                    state.value = RefreshStatus(error = (result as? DomainResult.Failure)?.error,
                        attempt = state.value.attempt)
                    synchronized(entry) { entry.completed[address.page] = version + 1 }
                    entry.results[address.page] = result
                    result
                } finally {
                    state.value = state.value.copy(running = false)
                }
            }
        } finally {
            release(address, entry)
        }
    }

    private suspend fun load(address: PageAddress, remote: suspend () -> DomainResult<Page<T>>): DomainResult<Unit> {
        val generation = storage.generation(address)
        val response = remote()
        currentCoroutineContext().ensureActive()
        return when (val result = response) {
            is DomainResult.Failure -> result
            is DomainResult.Success -> {
                require(result.value.items.size <= address.page.limit)
                if (!guestAllowed()) DomainResult.Failure(DomainError.Forbidden)
                else if (!storage.save(address, generation, result.value)) DomainResult.Failure(DomainError.Unexpected)
                else DomainResult.Success(Unit)
            }
        }
    }

    /** Combines durable snapshots with transient refresh status, retaining the last valid snapshot on read error. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observe(address: PageAddress): Flow<CachedPageState<T>> = flow {
        if (!guestAllowed()) {
            emit(CachedPageState(refreshError = DomainError.Forbidden))
            return@flow
        }
        val entry = acquire(address)
        var last: CachedPage<T>? = null
        try {
            emitAll(entry.state(address.page).flatMapLatest { status ->
                flow { emitAll(storage.observe(address)) }.map { cached ->
                    last = cached
                    CachedPageState(cached, status.running, status.error)
                }.catch { error ->
                    if (error is CancellationException) throw error
                    emit(CachedPageState(last, status.running, DomainError.Unexpected))
                }
            })
        } finally {
            release(address, entry)
        }
    }

    private suspend fun <R> guarded(action: suspend () -> DomainResult<R>): DomainResult<R> = try {
        currentCoroutineContext().ensureActive()
        val result = action()
        currentCoroutineContext().ensureActive()
        result
    } catch (error: CancellationException) {
        throw error
    } catch (_: CacheCapacityExceededException) {
        DomainResult.Failure(DomainError.Validation("cache", "capacity"))
    } catch (_: Exception) {
        DomainResult.Failure(DomainError.Unexpected)
    }
}
