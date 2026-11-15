package com.soundscape.app.core.domain.usecase

import com.soundscape.app.core.domain.model.GeoBounds
import com.soundscape.app.core.domain.model.MusicSearch
import com.soundscape.app.core.domain.model.PageRequest
import com.soundscape.app.core.domain.repository.TrackSearchCache
import com.soundscape.app.core.domain.repository.VisiblePlacesCache

class ObserveVisiblePlacesUseCase(private val cache: VisiblePlacesCache) {
    operator fun invoke(bounds: GeoBounds, page: PageRequest = PageRequest()) = cache.observe(bounds, page)
}
class RefreshVisiblePlacesUseCase(private val cache: VisiblePlacesCache) {
    suspend operator fun invoke(bounds: GeoBounds, page: PageRequest = PageRequest()) = cache.refresh(bounds, page)
}
class ObserveTrackSearchUseCase(private val cache: TrackSearchCache) {
    operator fun invoke(parameters: MusicSearch) = cache.observe(parameters)
}
class RefreshTrackSearchUseCase(private val cache: TrackSearchCache) {
    suspend operator fun invoke(parameters: MusicSearch) = cache.refresh(parameters)
}
