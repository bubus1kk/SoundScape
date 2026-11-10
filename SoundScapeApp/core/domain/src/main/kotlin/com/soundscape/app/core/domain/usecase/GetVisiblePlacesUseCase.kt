package com.soundscape.app.core.domain.usecase

import com.soundscape.app.core.domain.model.*
import com.soundscape.app.core.domain.repository.*

class GetVisiblePlacesUseCase(private val places: MusicPlaceRepository) {
    suspend operator fun invoke(bounds: GeoBounds, page: PageRequest = PageRequest()): DomainResult<Page<MapPlace>> {
        val validation = validatePage(page, 100)
        if (validation is DomainResult.Failure) return validation
        return places.getVisible(bounds, page)
    }
}
