package com.soundscape.app.core.data.cache

import com.soundscape.app.core.database.CacheCapacityExceededException
import com.soundscape.app.core.domain.model.CachedPage
import com.soundscape.app.core.domain.model.CachedPageState
import com.soundscape.app.core.domain.model.DomainError
import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.Page
import com.soundscape.app.core.domain.model.PageRequest
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fake transaction boundary tests coordinator policy; separate Android tests exercise real Room. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PageCoordinatorTest {
    private class Store : PageStorage<Int> {
        val pages = MutableStateFlow<Map<PageAddress, CachedPage<Int>>>(emptyMap())
        val generations = HashMap<Pair<String, String>, Long>()
        val pageGenerations = HashMap<PageAddress, Long>()
        var readFailure = false
        var writeFailure: Exception? = null
        override suspend fun read(address: PageAddress): CachedPage<Int>? {
            if (readFailure) throw IOException("disk read")
            return pages.value[address]
        }
        override fun observe(address: PageAddress) = pages.map {
            if (readFailure) throw IOException("disk read")
            it[address]
        }.distinctUntilChanged()
        override suspend fun generation(address: PageAddress): Long {
            val key = address.scope to address.key
            val value = if (address.page.offset == 0) (generations[key] ?: 0) + 1 else generations[key] ?: 1
            generations[key] = value
            return value
        }
        override suspend fun save(address: PageAddress, generation: Long, page: Page<Int>): Boolean {
            writeFailure?.let { throw it }
            if (generations[address.scope to address.key] != generation) return false
            val current = pages.value.toMutableMap()
            if (address.page.offset == 0) current.keys.removeAll {
                it.scope == address.scope && it.key == address.key && pageGenerations[it] != generation
            }
            pageGenerations[address] = generation
            current[address] = CachedPage(page, Instant.ofEpochMilli(generation))
            pages.value = current
            return true
        }
    }
    private val address = PageAddress("guest", "query", PageRequest())
    private val store = Store()
    private var offline = false
    private var guest = true
    private val coordinator = PageCoordinator(store, { offline }, { guest })
    private val content = Page(listOf(2, 1), true)
    private suspend fun seed(address: PageAddress = this.address, page: Page<Int> = content) {
        store.save(address, store.generation(address), page)
    }

    @Test fun observationRecoversAfterDiskReadFailureOnExplicitRetry() = runTest {
        store.readFailure = true
        var state: CachedPageState<Int>? = null
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            coordinator.observe(address).collect { state = it }
        }
        runCurrent(); assertEquals(DomainError.Unexpected, state!!.refreshError)
        store.readFailure = false
        coordinator.refresh(address) { DomainResult.Success(content) }
        runCurrent()
        assertEquals(content, state!!.cached!!.page)
        assertNull(state!!.refreshError)
    }
    @Test fun cancelledCallerCannotSaveEvenIfRemoteIgnoresCancellation() = runTest {
        val response = CompletableDeferred<DomainResult<Page<Int>>>()
        val pending = async {
            coordinator.refresh(address) { withContext(NonCancellable) { response.await() } }
        }
        runCurrent(); pending.cancel()
        response.complete(DomainResult.Success(content))
        pending.join()
        assertTrue(pending.isCancelled)
        assertNull(store.read(address))
    }
    @Test fun cacheHitNeverCallsRemote() = runTest {
        seed()
        assertEquals(DomainResult.Success(content), coordinator.get(address) { error("HTTP forbidden") })
    }
    @Test fun cacheMissPersistsBeforeSuccess() = runTest {
        assertEquals(DomainResult.Success(content), coordinator.get(address) { DomainResult.Success(content) })
        assertEquals(content, store.read(address)!!.page)
    }
    @Test fun firstLaunchOfflineIsFailureNotEmptySuccess() = runTest {
        offline = true
        assertEquals(DomainResult.Failure(DomainError.Network), coordinator.get(address) { error("HTTP forbidden") })
        assertNull(store.read(address))
    }
    @Test fun offlineCacheHitSurvivesMissingNetwork() = runTest {
        seed(); offline = true
        assertEquals(DomainResult.Success(content), coordinator.get(address) { error("HTTP forbidden") })
    }
    @Test fun successfulEmptyPageIsPersistedAndDistinctFromAbsent() = runTest {
        val empty = Page<Int>(emptyList(), false)
        assertNull(store.read(address))
        assertEquals(DomainResult.Success(empty), coordinator.get(address) { DomainResult.Success(empty) })
        offline = true
        assertEquals(DomainResult.Success(empty), coordinator.get(address) { error("HTTP forbidden") })
    }
    @Test fun networkFailureRetainsCachedContentAndPublishesError() = runTest {
        seed()
        var state: CachedPageState<Int>? = null
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            coordinator.observe(address).collect { state = it }
        }
        runCurrent()
        coordinator.refresh(address) { DomainResult.Failure(DomainError.Network) }
        runCurrent()
        assertEquals(content, state!!.cached!!.page)
        assertEquals(DomainError.Network, state!!.refreshError)
        assertFalse(state!!.refreshing)
    }
    @Test fun serverErrorDoesNotRemovePreviouslyPersistedPage() = runTest {
        seed()
        assertEquals(DomainResult.Failure(DomainError.Server), coordinator.refresh(address) {
            DomainResult.Failure(DomainError.Server)
        })
        assertEquals(content, store.read(address)!!.page)
    }
    @Test fun diskWriteFailureIsNeverAcknowledgedAsSuccess() = runTest {
        store.writeFailure = IOException("disk full")
        assertEquals(DomainResult.Failure(DomainError.Unexpected), coordinator.get(address) {
            DomainResult.Success(content)
        })
        assertNull(store.read(address))
    }
    @Test fun diskReadFailureDoesNotFallThroughToHttpOrFakeSuccess() = runTest {
        store.readFailure = true
        assertEquals(DomainResult.Failure(DomainError.Unexpected), coordinator.get(address) { error("HTTP forbidden") })
    }
    @Test fun concurrentIdenticalRefreshIsCoalesced() = runTest {
        val response = CompletableDeferred<DomainResult<Page<Int>>>()
        var calls = 0
        val one = async { coordinator.refresh(address) { calls++; response.await() } }
        runCurrent()
        val two = async { coordinator.refresh(address) { calls++; response.await() } }
        runCurrent(); response.complete(DomainResult.Success(content))
        assertEquals(DomainResult.Success(Unit), one.await())
        assertEquals(DomainResult.Success(Unit), two.await())
        assertEquals(1, calls)
    }
    @Test fun differentPagesShareGenerationAndBothRemainAvailable() = runTest {
        val later = address.copy(page = PageRequest(offset = 20))
        val response = CompletableDeferred<DomainResult<Page<Int>>>()
        val one = async { coordinator.refresh(address) { response.await() } }
        runCurrent()
        val two = async { coordinator.refresh(later) { DomainResult.Success(Page(listOf(3), false)) } }
        runCurrent(); response.complete(DomainResult.Success(content))
        one.await(); two.await()
        assertEquals(store.pageGenerations[address], store.pageGenerations[later])
        assertEquals(content, store.read(address)!!.page)
        assertEquals(listOf(3), store.read(later)!!.page.items)
    }
    @Test fun firstPageRefreshInvalidatesOlderDependentPages() = runTest {
        seed()
        val later = address.copy(page = PageRequest(offset = 20))
        seed(later)
        coordinator.refresh(address) { DomainResult.Success(Page(listOf(4), false)) }
        assertNull(store.read(later))
        assertEquals(listOf(4), store.read(address)!!.page.items)
    }
    @Test fun externalNewGenerationRejectsDelayedResponse() = runTest {
        seed()
        val response = CompletableDeferred<DomainResult<Page<Int>>>()
        val pending = async { coordinator.refresh(address) { response.await() } }
        runCurrent()
        store.generation(address)
        response.complete(DomainResult.Success(Page(listOf(9), false)))
        assertEquals(DomainResult.Failure(DomainError.Unexpected), pending.await())
        assertEquals(content, store.read(address)!!.page)
    }
    @Test fun cancellationClearsRefreshingAndAllowsRetry() = runTest {
        var state: CachedPageState<Int>? = null
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            coordinator.observe(address).collect { state = it }
        }
        val response = CompletableDeferred<DomainResult<Page<Int>>>()
        val pending = launch { coordinator.refresh(address) { response.await() } }
        runCurrent(); assertTrue(state!!.refreshing)
        pending.cancelAndJoin(); runCurrent()
        assertFalse(state!!.refreshing)
        assertNull(store.read(address))
        assertEquals(DomainResult.Success(Unit), coordinator.refresh(address) { DomainResult.Success(content) })
    }
    @Test fun cancellingWaiterDoesNotCancelRunningOwner() = runTest {
        val response = CompletableDeferred<DomainResult<Page<Int>>>()
        val owner = async { coordinator.refresh(address) { response.await() } }
        runCurrent()
        val waiter = launch { coordinator.refresh(address) { error("duplicate") } }
        runCurrent(); waiter.cancelAndJoin()
        response.complete(DomainResult.Success(content))
        assertEquals(DomainResult.Success(Unit), owner.await())
    }
    @Test fun scopeQueryOffsetAndLimitAreSeparatePages() = runTest {
        seed()
        for (other in listOf(address.copy(scope = "user:other"), address.copy(key = "other"),
            address.copy(page = PageRequest(offset = 1)), address.copy(page = PageRequest(limit = 1)))) {
            assertNull(store.read(other))
        }
    }
    @Test fun guestGuardBlocksExistingTokenAndMidRequestSwitch() = runTest {
        guest = false
        assertEquals(DomainResult.Failure(DomainError.Forbidden), coordinator.get(address) { error("HTTP forbidden") })
        guest = true
        val response = CompletableDeferred<DomainResult<Page<Int>>>()
        val pending = async { coordinator.refresh(address) { response.await() } }
        runCurrent(); guest = false; response.complete(DomainResult.Success(content))
        assertEquals(DomainResult.Failure(DomainError.Forbidden), pending.await())
        assertNull(store.read(address))
    }
    @Test fun hardCapacityFailureIsExplicitAndPreservesContent() = runTest {
        seed(); store.writeFailure = CacheCapacityExceededException()
        val result = coordinator.refresh(address) { DomainResult.Success(Page(listOf(5), false)) }
        assertEquals(DomainResult.Failure(DomainError.Validation("cache", "capacity")), result)
        assertEquals(content, store.read(address)!!.page)
    }
    @Test fun oversizedResponseCannotBePartiallyPersisted() = runTest {
        val tiny = address.copy(page = PageRequest(limit = 1))
        assertEquals(DomainResult.Failure(DomainError.Unexpected),
            coordinator.get(tiny) { DomainResult.Success(content) })
        assertNull(store.read(tiny))
    }
}
