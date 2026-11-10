package com.soundscape.app.core.domain.usecase

import com.soundscape.app.core.domain.model.*
import com.soundscape.app.core.domain.repository.*

class DiscoverRandomPlaceTrackUseCase(private val places: MusicPlaceRepository, private val random: RandomSource) {
    suspend operator fun invoke(): DomainResult<Discovery?> {
        val candidates = places.getDiscoveryCandidates(PageRequest(limit = 100))
        if (candidates is DomainResult.Failure) return candidates
        val items = (candidates as DomainResult.Success).value.items
        if (items.isEmpty()) return DomainResult.Success(null)
        val placeIndex = random.nextIndex(items.size)
        if (placeIndex is DomainResult.Failure) return placeIndex
        val index = (placeIndex as DomainResult.Success).value
        if (index !in items.indices) return DomainResult.Failure(DomainError.Unexpected)
        val place = items[index]
        val linked = places.getDiscoveryTracks(place.id, place.kind)
        if (linked is DomainResult.Failure) return linked
        val tracks = (linked as DomainResult.Success).value.filter { it.validate() is DomainResult.Success }
        if (tracks.isEmpty()) return DomainResult.Success(null)
        val trackIndex = random.nextIndex(tracks.size)
        if (trackIndex is DomainResult.Failure) return trackIndex
        val selected = (trackIndex as DomainResult.Success).value
        if (selected !in tracks.indices) return DomainResult.Failure(DomainError.Unexpected)
        return DomainResult.Success(Discovery(place, tracks[selected]))
    }
}
