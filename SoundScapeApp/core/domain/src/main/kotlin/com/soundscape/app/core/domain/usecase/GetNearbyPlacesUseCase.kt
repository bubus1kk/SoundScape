package com.soundscape.app.core.domain.usecase

import com.soundscape.app.core.domain.model.*
import com.soundscape.app.core.domain.repository.*

class GetNearbyPlacesUseCase(private val location: LocationProvider, private val places: MusicPlaceRepository) {
    suspend operator fun invoke(radiusMeters: Int, page: PageRequest = PageRequest()): DomainResult<Page<MapPlace>> {
        if (radiusMeters !in 1..50000) return invalid("radiusMeters", "outside_range")
        val validation = validatePage(page, 100)
        if (validation is DomainResult.Failure) return validation
        val position = location.currentLocation()
        if (position is DomainResult.Failure) return position
        return places.getNearby((position as DomainResult.Success).value, radiusMeters, page)
    }
}
