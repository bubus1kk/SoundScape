package com.soundscape.app

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.soundscape.app.core.designsystem.ui.theme.SoundScapeTheme
import com.soundscape.app.core.domain.model.DomainError
import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.Page
import com.soundscape.app.core.domain.model.TrackAvailability
import com.soundscape.app.core.domain.repository.GlobalSearch
import com.soundscape.app.core.domain.repository.GlobalSearchRepository
import com.soundscape.app.core.domain.repository.SearchCategory
import com.soundscape.app.core.domain.repository.SearchFacet
import com.soundscape.app.core.domain.repository.SearchHit
import com.soundscape.app.core.domain.repository.SearchOption
import com.soundscape.app.presentation.R as PresentationR
import com.soundscape.app.presentation.search.GlobalSearchScreen
import com.soundscape.app.presentation.search.GlobalSearchViewModel
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class GlobalSearchScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun showsTrackActionsAppendsResultsAndRetriesAfterAFilterError() {
        val catalog = Fixture()
        val opened = mutableListOf<UUID>()
        val store = ViewModelStore()
        lateinit var model: GlobalSearchViewModel
        compose.runOnUiThread {
            model = GlobalSearchViewModel(SavedStateHandle(), catalog)
            store.put("search", model)
        }
        try {
            compose.setContent {
                val state by model.state.collectAsState()
                SoundScapeTheme {
                    GlobalSearchScreen(state, model, offline = false, back = {}, openProfile = {},
                        onTrack = { opened += it.id }) { _, _ -> }
                }
            }
            compose.onNodeWithText(text(PresentationR.string.search_hint)).performTextReplacement("Ambient")
            compose.onNodeWithText("Ambient").performImeAction()
            waitForTrack(model, catalog.first.id)
            scrollTo(text(PresentationR.string.search_play_track)).performClick()
            compose.runOnIdle { assertEquals(listOf(catalog.first.id), opened) }
            scrollTo(text(PresentationR.string.search_more)).performClick()
            waitForTrack(model, catalog.second.id)
            scrollTo(catalog.first.title)
            scrollTo(catalog.second.title)
            scrollTo(text(PresentationR.string.search_open_track))

            compose.runOnIdle { catalog.error = DomainError.Network }
            compose.onNodeWithText(text(PresentationR.string.search_tracks)).performScrollTo().performClick()
            compose.waitUntil(10_000) { model.state.value.error == DomainError.Network }
            scrollTo(text(PresentationR.string.search_offline))
            compose.runOnIdle { catalog.error = null }
            scrollTo(text(PresentationR.string.search_retry)).performClick()
            waitForTrack(model, catalog.first.id)
            scrollTo(catalog.first.title)
            compose.runOnIdle {
                assertEquals(
                    listOf(null to 0, null to 20, SearchCategory.TRACK to 0, SearchCategory.TRACK to 0),
                    catalog.requests,
                )
            }
        } finally {
            compose.runOnUiThread { store.clear() }
        }
    }

    @Test fun emptyCatalogPageCanAdvanceToTheNextSnapshotOffset() {
        val catalog = Fixture(emptyFirstPage = true)
        val store = ViewModelStore()
        lateinit var model: GlobalSearchViewModel
        compose.runOnUiThread {
            model = GlobalSearchViewModel(SavedStateHandle(), catalog)
            store.put("search", model)
        }
        try {
            compose.setContent {
                val state by model.state.collectAsState()
                SoundScapeTheme {
                    GlobalSearchScreen(state, model, offline = false, back = {}, openProfile = {}) { _, _ -> }
                }
            }
            compose.onNodeWithText(text(PresentationR.string.search_hint)).performTextReplacement("Ambient")
            compose.onNodeWithText("Ambient").performImeAction()
            compose.waitUntil(10_000) { model.state.value.results?.hasMore == true }
            scrollTo(text(PresentationR.string.search_more)).performClick()
            waitForTrack(model, catalog.second.id)
            scrollTo(catalog.second.title)
            compose.runOnIdle { assertEquals(listOf(null to 0, null to 20), catalog.requests) }
        } finally {
            compose.runOnUiThread { store.clear() }
        }
    }

    private fun scrollTo(label: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("global-search-list").performScrollToNode(hasText(label))
        return compose.onNodeWithText(label).assertIsDisplayed()
    }

    private fun waitForTrack(model: GlobalSearchViewModel, id: UUID) {
        compose.waitUntil(10_000) { model.state.value.results?.items?.any { it.id == id } == true }
    }

    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private class Fixture(private val emptyFirstPage: Boolean = false) : GlobalSearchRepository {
        val first = SearchHit(
            SearchCategory.TRACK, UUID(0, 1), "First fixture track", "Artist", null, TrackAvailability.IN_APP,
        )
        val second = first.copy(id = UUID(0, 2), title = "Second fixture track",
            availability = TrackAvailability.EXTERNAL_ONLY)
        val requests = mutableListOf<Pair<SearchCategory?, Int>>()
        var error: DomainError? = null
        override val history = MutableStateFlow(emptyList<String>())
        override suspend fun rememberQuery(query: String) = Unit
        override suspend fun clearHistory() = Unit
        override suspend fun options(facet: SearchFacet) = DomainResult.Success(emptyList<SearchOption>())

        override suspend fun search(request: GlobalSearch, limit: Int, offset: Int): DomainResult<Page<SearchHit>> {
            requests += request.category to offset
            error?.let { return DomainResult.Failure(it) }
            if (emptyFirstPage) {
                return DomainResult.Success(if (offset == 0) Page(emptyList(), true) else Page(listOf(second), false))
            }
            return DomainResult.Success(if (offset == 0) Page(listOf(first), true) else Page(listOf(first, second), false))
        }
    }
}
