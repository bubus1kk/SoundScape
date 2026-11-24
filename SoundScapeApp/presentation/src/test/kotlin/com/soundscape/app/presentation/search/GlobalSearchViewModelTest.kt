package com.soundscape.app.presentation.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.soundscape.app.core.domain.model.DomainError
import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.Page
import com.soundscape.app.core.domain.repository.GlobalSearch
import com.soundscape.app.core.domain.repository.GlobalSearchRepository
import com.soundscape.app.core.domain.repository.SearchCategory
import com.soundscape.app.core.domain.repository.SearchFacet
import com.soundscape.app.core.domain.repository.SearchFilters
import com.soundscape.app.core.domain.repository.SearchHit
import com.soundscape.app.core.domain.repository.SearchOption
import java.io.IOException
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GlobalSearchViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()

    @Before fun setup() = Dispatchers.setMain(dispatcher)

    @After fun close() {
        store.clear()
        Dispatchers.resetMain()
    }

    @Test fun debounceCancelsSearchAndDeduplicatesTrimmedQuery() = runTest(dispatcher) {
        val repository = Fake()
        val stalled = CompletableDeferred<Unit>()
        repository.waitFor = stalled
        val model = model(repository)
        model.query("P")
        advanceTimeBy(200)
        model.query("Paris")
        advanceTimeBy(451)
        assertEquals(listOf("Paris"), repository.calls.map { it.first.query })
        repository.waitFor = null
        model.query("Rome")
        advanceUntilIdle()
        stalled.complete(Unit)
        model.query(" Rome ")
        advanceUntilIdle()
        assertEquals(listOf("Paris", "Rome"), repository.calls.map { it.first.query })
        assertEquals(listOf("Rome"), model.state.value.history)
        assertEquals("Rome", model.state.value.results?.items?.single()?.title)
        model.clearHistory()
        advanceUntilIdle()
        assertEquals(emptyList<String>(), model.state.value.history)
    }

    @Test fun nextPageRunsOnceAndDoesNotDuplicateItems() = runTest(dispatcher) {
        val repository = Fake().apply { hasMore = true }
        val model = model(repository)
        model.query("Rome")
        advanceUntilIdle()
        model.loadMore()
        model.loadMore()
        advanceUntilIdle()
        assertEquals(listOf(0, 20), repository.calls.map { it.second })
        assertEquals(1, model.state.value.results?.items?.size)
        assertFalse(requireNotNull(model.state.value.results).hasMore)
    }

    @Test fun offlineIsDistinctAndRetryRecovers() = runTest(dispatcher) {
        val repository = Fake().apply { error = DomainError.Network }
        val model = model(repository)
        model.query("Rome")
        advanceUntilIdle()
        assertEquals(DomainError.Network, model.state.value.error)
        repository.error = null
        model.retry()
        runCurrent()
        assertNull(model.state.value.error)
        assertEquals("Rome", model.state.value.results?.items?.single()?.title)
    }

    @Test fun filterChangesRestartPaginationAndRestoredFiltersAreIncluded() = runTest(dispatcher) {
        val repository = Fake().apply { hasMore = true }
        val handle = SavedStateHandle()
        val model = model(repository, handle)
        model.query("Rome")
        advanceUntilIdle()
        model.loadMore()
        advanceUntilIdle()
        model.category(SearchCategory.TRACK)
        val filters = SearchFilters(3, 7, true)
        model.filters(filters)
        model.filters(filters)
        advanceUntilIdle()
        assertEquals(listOf(0, 20, 0), repository.calls.map { it.second })
        assertEquals(GlobalSearch("Rome", SearchCategory.TRACK, filters), repository.calls.last().first)
        val restored = model(repository, handle)
        advanceUntilIdle()
        assertEquals(filters, restored.state.value.filters)
        assertEquals(SearchCategory.TRACK, restored.state.value.category)
        assertEquals(0, repository.calls.last().second)
    }

    @Test fun failedNextPageKeepsItemsAndRetryUsesTheSameOffset() = runTest(dispatcher) {
        val repository = Fake().apply { hasMore = true }
        val model = model(repository)
        model.query("Rome")
        advanceUntilIdle()
        val shown = model.state.value.results
        repository.error = DomainError.Timeout
        model.loadMore()
        advanceUntilIdle()
        assertEquals(shown, model.state.value.results)
        assertEquals(DomainError.Timeout, model.state.value.error)
        repository.error = null
        model.retry()
        advanceUntilIdle()
        assertEquals(listOf(0, 20, 20), repository.calls.map { it.second })
        assertEquals(40, model.state.value.results?.nextOffset)
        assertFalse(requireNotNull(model.state.value.results).hasMore)
        assertNull(model.state.value.error)
    }

    @Test fun rateLimitBlocksNewQueriesUntilRetryWindowExpires() = runTest(dispatcher) {
        val repository = Fake().apply { error = DomainError.RateLimited(2.seconds) }
        val model = model(repository, now = { testScheduler.currentTime })
        model.query("Rome")
        advanceTimeBy(451)
        assertTrue(model.state.value.waitingForRetry)
        model.query("Paris")
        model.retry()
        runCurrent()
        assertEquals(listOf("Rome"), repository.calls.map { it.first.query })
        repository.error = null
        advanceTimeBy(2_000)
        runCurrent()
        assertFalse(model.state.value.waitingForRetry)
        model.retry()
        runCurrent()
        assertEquals("Paris", model.state.value.results?.items?.single()?.title)
    }

    @Test fun reusedCacheSkipsFetchAndExpiredCacheStaysVisibleDuringRefresh() = runTest(dispatcher) {
        val repository = Fake()
        var now = 0L
        val model = model(repository, now = { now })
        model.query("Rome")
        advanceUntilIdle()
        model.query("Paris")
        advanceUntilIdle()
        model.query("Rome")
        advanceUntilIdle()
        assertEquals(2, repository.calls.size)
        model.query("")
        now += 60_000
        model.query("Rome")
        assertEquals("Rome", model.state.value.results?.items?.single()?.title)
        assertTrue(model.state.value.loading)
        advanceUntilIdle()
        assertEquals(listOf("Rome", "Paris", "Rome"), repository.calls.map { it.first.query })
    }

    @Test fun facetFailureAndHistoryFailureAreIndependentlyRecoverable() = runTest(dispatcher) {
        val repository = Fake().apply { failedFacet = SearchFacet.Mood; historyFails = true }
        val model = model(repository)
        repeat(2) { model.loadOptions() }
        model.clearHistory()
        advanceUntilIdle()
        assertTrue(model.state.value.historyError)
        assertEquals(setOf(SearchFacet.Mood), model.state.value.failedOptions)
        assertEquals(SearchFacet.entries.size, repository.facetCalls.size)
        repository.historyFails = false
        repository.failedFacet = null
        model.clearHistory()
        model.loadOptions()
        advanceUntilIdle()
        assertFalse(model.state.value.historyError)
        assertTrue(model.state.value.failedOptions.isEmpty())
        assertEquals(2, repository.facetCalls.count { it == SearchFacet.Mood })
        assertEquals(1, repository.facetCalls.count { it == SearchFacet.Genre })
    }

    private fun model(repository: Fake, handle: SavedStateHandle = SavedStateHandle(), now: () -> Long = { 0L }) =
        GlobalSearchViewModel(handle, repository, now).also { store.put("search", it) }

    private class Fake : GlobalSearchRepository {
        private val id = UUID.randomUUID()
        override val history = MutableStateFlow(emptyList<String>())
        val calls = mutableListOf<Pair<GlobalSearch, Int>>()
        var waitFor: CompletableDeferred<Unit>? = null
        var error: DomainError? = null
        var hasMore = false
        var failedFacet: SearchFacet? = null
        var historyFails = false
        val facetCalls = mutableListOf<SearchFacet>()

        override suspend fun rememberQuery(query: String) {
            history.value = listOf(query) + history.value.filterNot { it == query }
        }

        override suspend fun clearHistory() {
            if (historyFails) throw IOException("Fixture storage unavailable")
            history.value = emptyList()
        }

        override suspend fun search(request: GlobalSearch, limit: Int, offset: Int): DomainResult<Page<SearchHit>> {
            calls += request to offset
            waitFor?.await()
            error?.let { return DomainResult.Failure(it) }
            val hit = SearchHit(SearchCategory.PLACE, id, request.query, null, null, null)
            return DomainResult.Success(Page(listOf(hit), hasMore && offset == 0))
        }

        override suspend fun options(facet: SearchFacet): DomainResult<List<SearchOption>> {
            facetCalls += facet
            if (facet == failedFacet) return DomainResult.Failure(DomainError.Network)
            return DomainResult.Success(listOf(SearchOption(facet.ordinal + 1, facet.name)))
        }
    }
}
