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
import com.soundscape.app.core.domain.repository.SearchHit
import java.util.UUID
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

    private fun model(repository: Fake) =
        GlobalSearchViewModel(SavedStateHandle(), repository).also { store.put("search", it) }

    private class Fake : GlobalSearchRepository {
        private val id = UUID.randomUUID()
        override val history = MutableStateFlow(emptyList<String>())
        val calls = mutableListOf<Pair<GlobalSearch, Int>>()
        var waitFor: CompletableDeferred<Unit>? = null
        var error: DomainError? = null
        var hasMore = false

        override suspend fun rememberQuery(query: String) {
            history.value = listOf(query) + history.value.filterNot { it == query }
        }

        override suspend fun clearHistory() {
            history.value = emptyList()
        }

        override suspend fun search(request: GlobalSearch, limit: Int, offset: Int): DomainResult<Page<SearchHit>> {
            calls += request to offset
            waitFor?.await()
            error?.let { return DomainResult.Failure(it) }
            val hit = SearchHit(SearchCategory.PLACE, id, request.query, null, null, null)
            return DomainResult.Success(Page(listOf(hit), hasMore && offset == 0))
        }

        override suspend fun options(facet: SearchFacet) = DomainResult.Success(emptyList<Nothing>())
    }
}
