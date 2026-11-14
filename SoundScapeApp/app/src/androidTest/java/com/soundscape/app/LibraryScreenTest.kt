package com.soundscape.app

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.soundscape.app.core.designsystem.ui.theme.SoundScapeTheme
import com.soundscape.app.core.domain.model.CachedPage
import com.soundscape.app.core.domain.model.CachedPageState
import com.soundscape.app.core.domain.model.DomainError
import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.GeoBounds
import com.soundscape.app.core.domain.model.GeoPoint
import com.soundscape.app.core.domain.model.MapPlace
import com.soundscape.app.core.domain.model.MapPlaceKind
import com.soundscape.app.core.domain.model.MusicSearch
import com.soundscape.app.core.domain.model.Page
import com.soundscape.app.core.domain.model.PageRequest
import com.soundscape.app.core.domain.model.Track
import com.soundscape.app.core.domain.model.TrackAvailability
import com.soundscape.app.core.domain.repository.TrackSearchCache
import com.soundscape.app.core.domain.repository.VisiblePlacesCache
import com.soundscape.app.core.domain.usecase.ObserveTrackSearchUseCase
import com.soundscape.app.core.domain.usecase.ObserveVisiblePlacesUseCase
import com.soundscape.app.core.domain.usecase.RefreshTrackSearchUseCase
import com.soundscape.app.core.domain.usecase.RefreshVisiblePlacesUseCase
import com.soundscape.app.presentation.library.LibraryScreen
import com.soundscape.app.presentation.library.LibraryViewModel
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Rule
import org.junit.Test

/** UI fixtures exist only in androidTest. Real Retrofit/Room is tested in core:data. */
class LibraryScreenTest {
    @get:Rule val compose = createComposeRule()
    private val store = ViewModelStore()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun text(id: Int) = context.getString(id)
    private val point = (GeoPoint.create(55.0, 37.0) as DomainResult.Success).value
    private val mapState = MutableStateFlow(CachedPageState(CachedPage(
        Page(listOf(MapPlace(UUID.randomUUID(), MapPlaceKind.CITY, "Cached City", point)), false), Instant.EPOCH)))
    private val searches = HashMap<MusicSearch, MutableStateFlow<CachedPageState<Track>>>()
    private val places = object : VisiblePlacesCache {
        override fun observe(bounds: GeoBounds, page: PageRequest) = mapState
        override suspend fun refresh(bounds: GeoBounds, page: PageRequest): DomainResult<Unit> {
            mapState.value = mapState.value.copy(refreshError = DomainError.Network)
            return DomainResult.Failure(DomainError.Network)
        }
    }
    private val tracks = object : TrackSearchCache {
        override fun observe(parameters: MusicSearch) = searches.getOrPut(parameters) {
            MutableStateFlow(CachedPageState(refreshError = DomainError.Network))
        }
        override suspend fun refresh(parameters: MusicSearch): DomainResult<Unit> =
            if (observe(parameters).value.cached == null) DomainResult.Failure(DomainError.Network)
            else DomainResult.Success(Unit)
    }
    private fun render() {
        val factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T = modelClass.cast(
                LibraryViewModel(ObserveVisiblePlacesUseCase(places), RefreshVisiblePlacesUseCase(places),
                    ObserveTrackSearchUseCase(tracks), RefreshTrackSearchUseCase(tracks)))!!
        }
        val model = ViewModelProvider(store, factory)[LibraryViewModel::class.java]
        compose.setContent { SoundScapeTheme { LibraryScreen(model) { Text("settings-content") } } }
    }
    @After fun close() { compose.runOnIdle { store.clear() } }

    @Test fun destinationsKeepSettingsAndShowCachedPlacesWithRefreshError() {
        render()
        compose.onNodeWithText("settings-content").assertExists()
        compose.onNodeWithText(text(com.soundscape.app.presentation.R.string.library_places)).performClick()
        compose.onNodeWithText("Cached City", substring = true).assertExists()
        compose.onNodeWithText(text(com.soundscape.app.presentation.R.string.library_offline)).assertExists()
        compose.onNodeWithText(text(com.soundscape.app.presentation.R.string.library_refresh)).performClick()
        compose.onNodeWithText("Cached City", substring = true).assertExists()
        compose.onNodeWithText(text(com.soundscape.app.presentation.R.string.library_settings)).performClick()
        compose.onNodeWithText("settings-content").assertExists()
    }
    @Test fun differentSearchDoesNotDisplayOldQueryResults() {
        val track = Track(UUID.randomUUID(), "First Track", "Artist", 1000.milliseconds, TrackAvailability.IN_APP)
        searches[MusicSearch("first")] = MutableStateFlow(
            CachedPageState(CachedPage(Page(listOf(track), false), Instant.EPOCH)))
        render()
        compose.onNodeWithText(text(com.soundscape.app.presentation.R.string.library_search)).performClick()
        val input = compose.onNodeWithText(text(com.soundscape.app.presentation.R.string.library_query))
        input.performTextInput("first")
        compose.onNodeWithText(text(com.soundscape.app.presentation.R.string.library_submit)).performClick()
        compose.onNodeWithText("First Track", substring = true).assertExists()
        input.performTextReplacement("second")
        compose.onNodeWithText(text(com.soundscape.app.presentation.R.string.library_submit)).performClick()
        compose.onNodeWithText("First Track", substring = true).assertDoesNotExist()
        compose.onNodeWithText(text(com.soundscape.app.presentation.R.string.library_no_cache)).assertExists()
    }
    @Test fun successfulEmptyPageIsNotShownAsMissingCache() {
        searches[MusicSearch("empty")] = MutableStateFlow(
            CachedPageState(CachedPage(Page(emptyList(), false), Instant.EPOCH)))
        render()
        compose.onNodeWithText(text(com.soundscape.app.presentation.R.string.library_search)).performClick()
        compose.onNodeWithText(text(com.soundscape.app.presentation.R.string.library_query)).performTextInput("empty")
        compose.onNodeWithText(text(com.soundscape.app.presentation.R.string.library_submit)).performClick()
        compose.onNodeWithText(text(com.soundscape.app.presentation.R.string.library_empty)).assertExists()
        compose.onNodeWithText(text(com.soundscape.app.presentation.R.string.library_no_cache)).assertDoesNotExist()
        compose.onNodeWithText(text(com.soundscape.app.presentation.R.string.library_settings)).performClick()
        compose.onNodeWithText(text(com.soundscape.app.presentation.R.string.library_search)).performClick()
        compose.onNodeWithText(text(com.soundscape.app.presentation.R.string.library_empty)).assertExists()
    }
}
