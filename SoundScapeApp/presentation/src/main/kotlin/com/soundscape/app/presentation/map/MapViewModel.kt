package com.soundscape.app.presentation.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.soundscape.app.core.domain.model.DomainError
import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.GeoBounds
import com.soundscape.app.core.domain.model.GeoPoint
import com.soundscape.app.core.domain.model.MapPlace
import com.soundscape.app.core.domain.model.MapPlaceKind
import com.soundscape.app.core.domain.model.PageRequest
import com.soundscape.app.core.domain.usecase.GetVisiblePlacesUseCase
import com.soundscape.app.core.domain.usecase.ObserveVisiblePlacesUseCase
import com.soundscape.app.core.domain.usecase.RefreshVisiblePlacesUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal enum class MapPlacesLoad { Idle, Initial, Background, Retry }

internal sealed interface RandomPlace {
    val busy: Boolean get() = this == Loading || this is Flight

    data object Idle : RandomPlace
    data object Loading : RandomPlace
    data object Empty : RandomPlace
    data object Failed : RandomPlace
    data class Flight(val place: MapPlace) : RandomPlace
}

internal data class MapPlacesState(
    val places: List<MapPlace> = emptyList(),
    val selected: MapPlace? = null,
    val load: MapPlacesLoad = MapPlacesLoad.Idle,
    val needsZoom: Boolean = false,
    val partial: Boolean = false,
    val error: DomainError? = null,
    val loaded: Boolean = false,
) {
    val loading: Boolean get() = load != MapPlacesLoad.Idle
}

@HiltViewModel
class MapViewModel @Inject constructor(
    private val getPlaces: GetVisiblePlacesUseCase,
    private val observePlaces: ObserveVisiblePlacesUseCase,
    private val refreshPlaces: RefreshVisiblePlacesUseCase,
) : ViewModel() {
    private val mutableState = MutableStateFlow(MapPlacesState())
    internal val state = mutableState.asStateFlow()
    private val random = MutableStateFlow<RandomPlace>(RandomPlace.Idle)
    internal val randomPlace = random.asStateFlow()
    private var fallback: List<MapPlace>? = null
    private var recentCandidates: List<MapPlace> = emptyList()
    private var lastRandomId: UUID? = null
    private var randomJob: Job? = null
    private var bounds: GeoBounds? = null
    private var completed: GeoBounds? = null
    private var loadingJob: Job? = null

    fun onCameraMove() {
        if (loadingJob?.isActive == true) mutableState.value = mutableState.value.copy(load = MapPlacesLoad.Idle)
        loadingJob?.cancel()
        loadingJob = null
    }

    fun onCameraIdle(area: GeoBounds?) {
        if (area == null) {
            onCameraMove()
            bounds = null
            completed = null
            mutableState.value = MapPlacesState(needsZoom = true, loaded = mutableState.value.loaded)
        } else if (area != bounds || (completed != area && loadingJob?.isActive != true)) {
            onCameraMove()
            if (area != bounds) completed = null
            bounds = area
            request(area, if (mutableState.value.loaded) MapPlacesLoad.Background else MapPlacesLoad.Initial)
        }
    }

    fun retry() {
        val area = bounds ?: return
        if (loadingJob?.isActive != true) request(area, MapPlacesLoad.Retry)
    }

    fun select(id: UUID?) {
        if (id != null) cancelRandom()
        val current = mutableState.value
        mutableState.value = current.copy(selected = current.places.firstOrNull { it.id == id })
    }

    internal fun chooseRandomPlace(citiesOnly: Boolean) {
        if (random.value.busy) return
        recentCandidates = (mutableState.value.places + recentCandidates)
            .distinctBy(MapPlace::id).take(MAX_PAGES * PAGE_SIZE)
        val available = recentCandidates.forLayer(citiesOnly).filterNot { it.id == lastRandomId }
        if (available.isNotEmpty()) {
            offerRandomPlace(available)
        } else {
            random.value = RandomPlace.Loading
            randomJob = viewModelScope.launch {
                val cached = fallback
                if (cached != null) {
                    offerRandomPlace(cached.forLayer(citiesOnly))
                } else {
                    when (val result = getPlaces(RANDOM_BOUNDS, PageRequest(PAGE_SIZE, 0))) {
                        is DomainResult.Success -> {
                            fallback = result.value.items
                            offerRandomPlace(result.value.items.forLayer(citiesOnly))
                        }
                        is DomainResult.Failure -> random.value = RandomPlace.Failed
                    }
                }
            }
        }
    }

    internal fun cancelRandom() {
        randomJob?.cancel()
        randomJob = null
        random.value = RandomPlace.Idle
    }

    internal fun finishRandomFlight(flight: RandomPlace.Flight, arrived: Boolean) {
        if (random.value !== flight) return
        if (arrived) {
            lastRandomId = flight.place.id
            mutableState.value = mutableState.value.copy(selected = flight.place)
        }
        random.value = RandomPlace.Idle
    }

    internal fun dismissRandomNotice() {
        if (random.value == RandomPlace.Empty || random.value == RandomPlace.Failed) random.value = RandomPlace.Idle
    }

    private fun offerRandomPlace(candidates: List<MapPlace>) {
        val chosen = candidates.filterNot { it.id == lastRandomId }.randomOrNull()
        random.value = if (chosen == null) RandomPlace.Empty else RandomPlace.Flight(chosen)
    }

    private fun request(area: GeoBounds, load: MapPlacesLoad) {
        val previous = mutableState.value
        mutableState.value = previous.copy(load = load, needsZoom = false, error = null)
        loadingJob = viewModelScope.launch {
            if (load != MapPlacesLoad.Retry) delay(DEBOUNCE_MS)
            val places = linkedMapOf<UUID, MapPlace>()
            var more = true
            var offset = 0
            var error: DomainError? = null
            while (more && offset < MAX_PAGES * PAGE_SIZE) {
                val page = PageRequest(PAGE_SIZE, offset)
                val cached = observePlaces(area, page).first().cached
                if (cached != null) {
                    if (previous.places.isEmpty()) {
                        val preview = (places.values + cached.page.items).distinctBy(MapPlace::id)
                        publish(preview, load = load)
                    }
                    error = (refreshPlaces(area, page) as? DomainResult.Failure)?.error ?: error
                }
                val result = getPlaces(area, page)
                ensureActive()
                when (result) {
                    is DomainResult.Success -> {
                        result.value.items.forEach { places[it.id] = it }
                        more = result.value.hasMore
                    }
                    is DomainResult.Failure -> {
                        cached?.page?.items?.forEach { places[it.id] = it }
                        error = result.error
                        more = false
                    }
                }
                offset += PAGE_SIZE
            }
            completed = area
            val visible = if (error != null && places.isEmpty()) previous.places else places.values.toList()
            publish(visible, load = MapPlacesLoad.Idle, error = error, partial = more)
        }
    }

    private fun publish(
        places: List<MapPlace>,
        load: MapPlacesLoad,
        error: DomainError? = null,
        partial: Boolean = false,
    ) {
        val current = mutableState.value
        mutableState.value = current.copy(
            places = places, selected = places.firstOrNull { it.id == current.selected?.id } ?: current.selected,
            load = load, error = error, loaded = current.loaded || load == MapPlacesLoad.Idle, partial = partial,
        )
    }

    private companion object {
        const val PAGE_SIZE = 100
        const val MAX_PAGES = 3
        const val DEBOUNCE_MS = 300L
        val RANDOM_BOUNDS = (GeoBounds.create(
            (GeoPoint.create(-90.0, -180.0) as DomainResult.Success).value,
            (GeoPoint.create(90.0, 180.0) as DomainResult.Success).value,
        ) as DomainResult.Success).value
    }
}

private fun List<MapPlace>.forLayer(citiesOnly: Boolean): List<MapPlace> =
    if (citiesOnly) filter { it.kind == MapPlaceKind.CITY } else this
