package com.soundscape.app.presentation.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.soundscape.app.core.domain.model.CachedPageState
import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.GeoBounds
import com.soundscape.app.core.domain.model.GeoPoint
import com.soundscape.app.core.domain.model.MapPlace
import com.soundscape.app.core.domain.model.MusicSearch
import com.soundscape.app.core.domain.model.PageRequest
import com.soundscape.app.core.domain.model.Track
import com.soundscape.app.core.domain.usecase.ObserveTrackSearchUseCase
import com.soundscape.app.core.domain.usecase.ObserveVisiblePlacesUseCase
import com.soundscape.app.core.domain.usecase.RefreshTrackSearchUseCase
import com.soundscape.app.core.domain.usecase.RefreshVisiblePlacesUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Small demonstration destinations; opening settings never performs HTTP calls. */
enum class LibraryTab { PLACES, SEARCH, SETTINGS }

/** Explicit geographic query, not fixture results. East < west preserves antimeridian semantics. */
data class MapArea(val south: Double, val west: Double, val north: Double, val east: Double) {
    fun bounds(): GeoBounds {
        val sw = (GeoPoint.create(south, west) as DomainResult.Success).value
        val ne = (GeoPoint.create(north, east) as DomainResult.Success).value
        return (GeoBounds.create(sw, ne) as DomainResult.Success).value
    }
}

/** Query presets contain only bounds, never fixture results. */
object LibraryAreas {
    val regional = MapArea(50.0, 30.0, 60.0, 40.0)
    val antimeridian = MapArea(-10.0, 170.0, 10.0, -170.0)
}

/** UI contains domain snapshots only; query switches cancel obsolete observation and refresh jobs. */
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val observePlaces: ObserveVisiblePlacesUseCase,
    private val refreshPlaces: RefreshVisiblePlacesUseCase,
    private val observeSearch: ObserveTrackSearchUseCase,
    private val refreshSearch: RefreshTrackSearchUseCase,
) : ViewModel() {
    private val mutableTab = MutableStateFlow(LibraryTab.SETTINGS)
    val tab = mutableTab.asStateFlow()
    private val mutablePlaces = MutableStateFlow(CachedPageState<MapPlace>())
    val places = mutablePlaces.asStateFlow()
    private val mutableTracks = MutableStateFlow(CachedPageState<Track>())
    val tracks = mutableTracks.asStateFlow()
    private val mutableArea = MutableStateFlow(LibraryAreas.regional)
    val area = mutableArea.asStateFlow()
    private val mutableSearch = MutableStateFlow<MusicSearch?>(null)
    val search = mutableSearch.asStateFlow()
    private val mutablePage = MutableStateFlow(PageRequest())
    val page = mutablePage.asStateFlow()
    private var watch: Job? = null
    private var refresh: Job? = null

    /** Activates a destination, observing cache before making one explicit refresh attempt. */
    fun open(tab: LibraryTab) {
        stop()
        mutableTab.value = tab
        when (tab) {
            LibraryTab.PLACES -> {
                mutablePlaces.value = CachedPageState()
                val bounds = area.value.bounds()
                val page = page.value
                watch = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    observePlaces(bounds, page).collect { mutablePlaces.value = it }
                }
                refreshActive()
            }
            LibraryTab.SEARCH -> search.value?.let { parameters ->
                mutableTracks.value = CachedPageState()
                watch = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    observeSearch(parameters).collect { mutableTracks.value = it }
                }
                refreshActive()
            }
            LibraryTab.SETTINGS -> Unit
        }
    }

    /** Preserves exact submitted text; changing a draft field alone does not relabel existing results. */
    fun submit(text: String, onlyInApp: Boolean) {
        mutableSearch.value = MusicSearch(text, onlyInApp)
        open(LibraryTab.SEARCH)
    }

    /** Selects a real bounds query and returns to its first page. */
    fun selectArea(area: MapArea) {
        mutableArea.value = area
        mutablePage.value = PageRequest()
        open(LibraryTab.PLACES)
    }

    /** Changes the page without changing the query identity or silently concatenating partial pages. */
    fun movePage(forward: Boolean) {
        if (tab.value == LibraryTab.PLACES) {
            val p = page.value
            mutablePage.value = p.copy(
                offset = if (forward) p.offset + p.limit else (p.offset - p.limit).coerceAtLeast(0))
        } else {
            val parameters = search.value ?: return
            val p = parameters.page
            mutableSearch.value = parameters.copy(page = p.copy(
                offset = if (forward) p.offset + p.limit else (p.offset - p.limit).coerceAtLeast(0)))
        }
        open(tab.value)
    }

    /** Also used for a distinct offline-to-online transition; overlaps are coalesced. */
    fun refreshActive() {
        if (refresh?.isActive == true) return
        refresh = viewModelScope.launch {
            when (tab.value) {
                LibraryTab.PLACES -> refreshPlaces(area.value.bounds(), page.value)
                LibraryTab.SEARCH -> search.value?.let { refreshSearch(it) }
                LibraryTab.SETTINGS -> Unit
            }
        }
    }

    private fun stop() {
        refresh?.cancel()
        watch?.cancel()
        refresh = null
        watch = null
    }
}
