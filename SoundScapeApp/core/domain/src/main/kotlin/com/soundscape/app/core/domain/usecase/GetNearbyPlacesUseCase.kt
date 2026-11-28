package com.soundscape.app.core.domain.usecase

import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.MapPlace
import com.soundscape.app.core.domain.model.Page
import com.soundscape.app.core.domain.model.PageRequest
import com.soundscape.app.core.domain.model.flatMap
import com.soundscape.app.core.domain.model.invalid
import com.soundscape.app.core.domain.repository.LocationProvider
import com.soundscape.app.core.domain.repository.MusicPlaceRepository

private const val MAX_NEARBY_RADIUS_METERS = 50_000

/** Loads music places near a location supplied by the device port. */
class GetNearbyPlacesUseCase(private val location: LocationProvider, private val places: MusicPlaceRepository) {
    /** Validates the radius and page before requesting the current location. */
    suspend operator fun invoke(radiusMeters: Int, page: PageRequest = PageRequest()): DomainResult<Page<MapPlace>> {
        if (radiusMeters !in 1..MAX_NEARBY_RADIUS_METERS) return invalid("radiusMeters", "outside_range")
        return validatePage(page, MAX_PLACE_PAGE_SIZE)
            .flatMap { location.currentLocation() }
            .flatMap { position -> places.getNearby(position, radiusMeters, page) }
    }
}
