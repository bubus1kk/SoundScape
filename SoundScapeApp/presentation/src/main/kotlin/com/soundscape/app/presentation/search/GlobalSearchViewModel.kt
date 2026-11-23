package com.soundscape.app.presentation.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.soundscape.app.core.domain.model.DomainError
import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.repository.GlobalSearch
import com.soundscape.app.core.domain.repository.GlobalSearchRepository
import com.soundscape.app.core.domain.repository.SearchCategory
import com.soundscape.app.core.domain.repository.SearchFacet
import com.soundscape.app.core.domain.repository.SearchFilters
import com.soundscape.app.core.domain.repository.SearchHit
import com.soundscape.app.core.domain.repository.SearchOption
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val PAGE_SIZE = 20
private const val DEBOUNCE_MS = 450L
private const val DEFAULT_RETRY_MS = 30_000L
private const val CACHE_TTL_MS = 60_000L
private const val CACHE_SIZE = 8

data class SearchResults(val items: List<SearchHit>, val nextOffset: Int, val hasMore: Boolean)

data class GlobalSearchState(
    val query: String = "",
    val category: SearchCategory? = null,
    val filters: SearchFilters = SearchFilters(),
    val history: List<String> = emptyList(),
    val historyError: Boolean = false,
    val results: SearchResults? = null,
    val loading: Boolean = false,
    val paging: Boolean = false,
    val error: DomainError? = null,
    val waitingForRetry: Boolean = false,
    val options: Map<SearchFacet, List<SearchOption>> = emptyMap(),
    val failedOptions: Set<SearchFacet> = emptySet(),
)

@HiltViewModel
class GlobalSearchViewModel @Inject constructor(
    private val saved: SavedStateHandle,
    private val repository: GlobalSearchRepository,
) : ViewModel() {
    private val mutable = MutableStateFlow(GlobalSearchState(
        query = saved.get<String>("query").orEmpty(),
        category = saved.get<String>("category")?.let(SearchCategory::valueOf),
        filters = SearchFilters(saved["genre"], saved["mood"], saved["onlyInApp"] ?: false),
    ))
    val state = mutable.asStateFlow()
    private data class CachedResults(val results: SearchResults, val savedAt: Long)

    private val cache = LinkedHashMap<GlobalSearch, CachedResults>()
    private val loadingOptions = mutableSetOf<SearchFacet>()
    private var current: GlobalSearch? = null
    private var requestJob: Job? = null
    private var retryClock: Job? = null
    private var retryAt = Long.MIN_VALUE
    private var paginationFailed = false

    init {
        viewModelScope.launch { repository.history.collect { history -> mutable.update { it.copy(history = history) } } }
        updateSearch()
    }

    fun query(value: String) {
        val text = value.take(200)
        if (text == state.value.query) return
        saved["query"] = text
        mutable.update { it.copy(query = text) }
        updateSearch()
    }

    fun category(value: SearchCategory?) {
        if (value == state.value.category) return
        saved["category"] = value?.name
        mutable.update { it.copy(category = value) }
        updateSearch()
    }

    fun filters(value: SearchFilters) {
        if (value == state.value.filters) return
        saved["genre"] = value.genreId
        saved["mood"] = value.moodId
        saved["onlyInApp"] = value.onlyInApp
        mutable.update { it.copy(filters = value) }
        updateSearch()
    }

    fun clearHistory() {
        viewModelScope.launch { updateHistory { repository.clearHistory() } }
    }

    fun loadOptions() {
        SearchFacet.entries.forEach { facet ->
            if (facet !in state.value.options && loadingOptions.add(facet)) {
                mutable.update { it.copy(failedOptions = it.failedOptions - facet) }
                viewModelScope.launch {
                    try {
                        when (val result = repository.options(facet)) {
                            is DomainResult.Success -> mutable.update { it.copy(options = it.options + (facet to result.value)) }
                            is DomainResult.Failure -> mutable.update { it.copy(failedOptions = it.failedOptions + facet) }
                        }
                    } finally {
                        loadingOptions.remove(facet)
                    }
                }
            }
        }
    }

    fun retry() {
        val request = current ?: return
        val snapshot = state.value
        if (requestJob?.isActive == true || snapshot.waitingForRetry) return
        val offset = snapshot.results?.nextOffset?.takeIf { snapshot.results.hasMore && snapshot.error != null }
        fetch(request, offset = if (paginationFailed) offset ?: 0 else 0)
    }

    fun loadMore() {
        val request = current ?: return
        val results = state.value.results ?: return
        if (results.hasMore && requestJob?.isActive != true && !state.value.waitingForRetry) {
            fetch(request, offset = results.nextOffset)
        }
    }

    private fun updateSearch() {
        val snapshot = state.value
        val request = snapshot.query.trim().takeIf(String::isNotEmpty)?.let {
            GlobalSearch(it, snapshot.category, snapshot.filters)
        }
        if (request == current) return
        val isNewQuery = request?.query != current?.query
        requestJob?.cancel()
        current = request
        paginationFailed = false
        val cached = cache[request]
        mutable.update { it.copy(results = cached?.results, loading = false, paging = false, error = null) }
        if (request != null && (cached == null || now() - cached.savedAt >= CACHE_TTL_MS)) {
            fetch(request, remember = isNewQuery, debounce = true)
        } else if (request != null && isNewQuery) {
            viewModelScope.launch { updateHistory { repository.rememberQuery(request.query) } }
        }
    }

    private fun fetch(request: GlobalSearch, offset: Int = 0, remember: Boolean = false, debounce: Boolean = false) {
        if (now() < retryAt) {
            mutable.update { it.copy(error = DomainError.RateLimited(null)) }
            return
        }
        mutable.update { it.copy(loading = offset == 0, paging = offset > 0, error = null) }
        requestJob = viewModelScope.launch {
            if (debounce) delay(DEBOUNCE_MS)
            val response = repository.search(request, PAGE_SIZE, offset)
            currentCoroutineContext().ensureActive()
            when (response) {
                is DomainResult.Success -> savePage(request, offset, response.value.items, response.value.hasMore)
                is DomainResult.Failure -> {
                    paginationFailed = offset > 0
                    val error = response.error
                    if (error is DomainError.RateLimited) setRetryDelay(error)
                    mutable.update { it.copy(loading = false, paging = false, error = error) }
                }
            }
            if (remember) updateHistory { repository.rememberQuery(request.query) }
        }
    }

    private fun savePage(request: GlobalSearch, offset: Int, items: List<SearchHit>, hasMore: Boolean) {
        val previous = if (offset > 0) state.value.results?.items.orEmpty() else emptyList()
        val result = SearchResults((previous + items).distinctBy { it.category to it.id }, offset + PAGE_SIZE, hasMore)
        cache.remove(request)
        cache[request] = CachedResults(result, now())
        if (cache.size > CACHE_SIZE) cache.remove(cache.keys.first())
        paginationFailed = false
        mutable.update { it.copy(loading = false, paging = false, results = result) }
    }

    private fun setRetryDelay(error: DomainError.RateLimited) {
        val wait = (error.retryAfter?.inWholeMilliseconds ?: DEFAULT_RETRY_MS).coerceAtLeast(DEBOUNCE_MS)
        retryAt = now() + wait
        mutable.update { it.copy(waitingForRetry = true) }
        retryClock?.cancel()
        retryClock = viewModelScope.launch {
            delay(wait)
            mutable.update { it.copy(waitingForRetry = false) }
        }
    }

    private fun now() = System.nanoTime() / 1_000_000

    private suspend fun updateHistory(operation: suspend () -> Unit) {
        try {
            operation()
            mutable.update { it.copy(historyError = false) }
        } catch (_: IOException) {
            mutable.update { it.copy(historyError = true) }
        }
    }
}
