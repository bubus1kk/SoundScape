package com.soundscape.app.core.domain.usecase

import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.GeoBounds
import com.soundscape.app.core.domain.model.MapPlace
import com.soundscape.app.core.domain.model.Page
import com.soundscape.app.core.domain.model.PageRequest
import com.soundscape.app.core.domain.repository.VisiblePlacesReader

/** Loads visible music places for the map viewport. */
class GetVisiblePlacesUseCase(private val places: VisiblePlacesReader) {
    /** Validates the page before requesting places within the bounds. */
    suspend operator fun invoke(bounds: GeoBounds, page: PageRequest = PageRequest()): DomainResult<Page<MapPlace>> {
        val validation = validatePage(page, MAX_PLACE_PAGE_SIZE)
        if (validation is DomainResult.Failure) return validation
        return places.getVisible(bounds, page)
    }
}
