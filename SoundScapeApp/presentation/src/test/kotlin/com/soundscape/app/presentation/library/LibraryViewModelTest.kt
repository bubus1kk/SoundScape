package com.soundscape.app.presentation.library

import com.soundscape.app.core.domain.model.CachedPage
import com.soundscape.app.core.domain.model.CachedPageState
import com.soundscape.app.core.domain.model.DomainError
import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.GeoBounds
import com.soundscape.app.core.domain.model.MapPlace
import com.soundscape.app.core.domain.model.MusicSearch
import com.soundscape.app.core.domain.model.Page
import com.soundscape.app.core.domain.model.PageRequest
import com.soundscape.app.core.domain.model.Track
import com.soundscape.app.core.domain.repository.TrackSearchCache
import com.soundscape.app.core.domain.repository.VisiblePlacesCache
import com.soundscape.app.core.domain.usecase.ObserveTrackSearchUseCase
import com.soundscape.app.core.domain.usecase.ObserveVisiblePlacesUseCase
import com.soundscape.app.core.domain.usecase.RefreshTrackSearchUseCase
import com.soundscape.app.core.domain.usecase.RefreshVisiblePlacesUseCase
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private class Places : VisiblePlacesCache {
        val states = HashMap<Pair<GeoBounds, PageRequest>, MutableStateFlow<CachedPageState<MapPlace>>>()
        var calls = 0
        var hold: CompletableDeferred<Unit>? = null
        override fun observe(bounds: GeoBounds, page: PageRequest) =
            states.getOrPut(bounds to page) { MutableStateFlow(CachedPageState()) }
        override suspend fun refresh(bounds: GeoBounds, page: PageRequest): DomainResult<Unit> {
            calls++
            hold?.await()
            observe(bounds, page).value = observe(bounds, page).value.copy(refreshError = DomainError.Network)
            return DomainResult.Failure(DomainError.Network)
        }
    }
    private class Search : TrackSearchCache {
        val calls = mutableListOf<MusicSearch>()
        val states = HashMap<MusicSearch, MutableStateFlow<CachedPageState<Track>>>()
        override fun observe(parameters: MusicSearch) =
            states.getOrPut(parameters) { MutableStateFlow(CachedPageState()) }
        override suspend fun refresh(parameters: MusicSearch): DomainResult<Unit> {
            calls.add(parameters)
            observe(parameters).value = observe(parameters).value.copy(refreshError = DomainError.Network)
            return DomainResult.Failure(DomainError.Network)
        }
    }
    private val places = Places()
    private val search = Search()
    private lateinit var model: LibraryViewModel
    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        model = LibraryViewModel(ObserveVisiblePlacesUseCase(places), RefreshVisiblePlacesUseCase(places),
            ObserveTrackSearchUseCase(search), RefreshTrackSearchUseCase(search))
    }
    @After fun close() { model.open(LibraryTab.SETTINGS); Dispatchers.resetMain() }

    @Test fun settingsAreDefaultAndDoNotRequestNetwork() = runTest(dispatcher) {
        runCurrent()
        assertEquals(LibraryTab.SETTINGS, model.tab.value)
        assertEquals(0, places.calls)
        assertTrue(search.calls.isEmpty())
    }
    @Test fun openObservesEmptySavedPageAndKeepsItOnRefreshError() = runTest(dispatcher) {
        val cached = CachedPage(Page<MapPlace>(emptyList(), false), Instant.EPOCH)
        places.observe(LibraryAreas.regional.bounds(), PageRequest()).value = CachedPageState(cached)
        model.open(LibraryTab.PLACES); runCurrent()
        assertEquals(cached, model.places.value.cached)
        assertEquals(DomainError.Network, model.places.value.refreshError)
        assertEquals(1, places.calls)
    }
    @Test fun changingSearchNeverShowsPreviousQueryAsNewContent() = runTest(dispatcher) {
        val first = MusicSearch(" exact ", true)
        search.observe(first).value = CachedPageState(CachedPage(Page(emptyList(), false), Instant.EPOCH))
        model.submit(" exact ", true); runCurrent()
        assertEquals(first, search.calls.single())
        model.submit("other", false); runCurrent()
        assertEquals(MusicSearch("other"), model.search.value)
        assertNull(model.tracks.value.cached)
        assertEquals(DomainError.Network, model.tracks.value.refreshError)
    }
    @Test fun overlapsAndReconnectSignalsDoNotCreateRefreshStorm() = runTest(dispatcher) {
        val pending = CompletableDeferred<Unit>()
        places.hold = pending
        model.open(LibraryTab.PLACES); runCurrent()
        repeat(10) { model.refreshActive() }
        runCurrent(); assertEquals(1, places.calls)
        pending.complete(Unit); runCurrent()
        model.refreshActive(); runCurrent()
        assertEquals(2, places.calls)
    }
    @Test fun areaAndPageSwitchDoNotRetainOldSnapshot() = runTest(dispatcher) {
        places.observe(LibraryAreas.regional.bounds(), PageRequest()).value =
            CachedPageState(CachedPage(Page(emptyList(), true), Instant.EPOCH))
        model.open(LibraryTab.PLACES); runCurrent()
        model.movePage(true); runCurrent()
        assertEquals(20, model.page.value.offset)
        assertNull(model.places.value.cached)
        model.selectArea(LibraryAreas.antimeridian); runCurrent()
        assertEquals(0, model.page.value.offset)
        assertTrue(model.area.value.bounds().crossesAntimeridian)
        assertNull(model.places.value.cached)
    }
}
