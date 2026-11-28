package com.soundscape.app.core.domain.usecase

import com.soundscape.app.core.domain.model.GeoBounds
import com.soundscape.app.core.domain.model.MusicSearch
import com.soundscape.app.core.domain.model.PageRequest
import com.soundscape.app.core.domain.repository.TrackSearchCache
import com.soundscape.app.core.domain.repository.VisiblePlacesCache

/** Observes cached music places for a viewport. */
class ObserveVisiblePlacesUseCase(private val cache: VisiblePlacesCache) {
    /** Returns cached map data and its load state. */
    operator fun invoke(bounds: GeoBounds, page: PageRequest = PageRequest()) = cache.observe(bounds, page)
}
/** Refreshes the cached places for a viewport. */
class RefreshVisiblePlacesUseCase(private val cache: VisiblePlacesCache) {
    /** Fetches and stores the requested viewport page. */
    suspend operator fun invoke(bounds: GeoBounds, page: PageRequest = PageRequest()) = cache.refresh(bounds, page)
}
/** Observes cached results for a track search. */
class ObserveTrackSearchUseCase(private val cache: TrackSearchCache) {
    /** Returns cached track results and their load state. */
    operator fun invoke(parameters: MusicSearch) = cache.observe(parameters)
}
/** Refreshes a cached track search. */
class RefreshTrackSearchUseCase(private val cache: TrackSearchCache) {
    /** Fetches and stores a track search page. */
    suspend operator fun invoke(parameters: MusicSearch) = cache.refresh(parameters)
}
