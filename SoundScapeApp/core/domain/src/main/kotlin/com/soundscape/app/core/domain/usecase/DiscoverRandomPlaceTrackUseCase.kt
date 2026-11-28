package com.soundscape.app.core.domain.usecase

import com.soundscape.app.core.domain.model.Discovery
import com.soundscape.app.core.domain.model.DomainError
import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.MapPlace
import com.soundscape.app.core.domain.model.PageRequest
import com.soundscape.app.core.domain.model.flatMap
import com.soundscape.app.core.domain.repository.MusicPlaceRepository
import com.soundscape.app.core.domain.repository.RandomSource

/** Selects a random public place and one eligible track from that place. */
class DiscoverRandomPlaceTrackUseCase(private val places: MusicPlaceRepository, private val random: RandomSource) {
    /** Returns a discovery, an empty selection, or the first failure from the discovery ports. */
    suspend operator fun invoke(): DomainResult<Discovery?> =
        places.getDiscoveryCandidates(PageRequest(limit = MAX_PLACE_PAGE_SIZE))
            .flatMap { choose(it.items) }
            .flatMap { place -> if (place == null) DomainResult.Success(null) else discoverTrack(place) }

    private suspend fun discoverTrack(place: MapPlace): DomainResult<Discovery?> =
        places.getDiscoveryTracks(place.id, place.kind)
            .flatMap { tracks -> choose(tracks.filter { it.validate() is DomainResult.Success }) }
            .flatMap { track -> DomainResult.Success(track?.let { Discovery(place, it) }) }

    private suspend fun <T : Any> choose(items: List<T>): DomainResult<T?> {
        if (items.isEmpty()) return DomainResult.Success(null)
        return random.nextIndex(items.size).flatMap { index ->
            val item = items.getOrNull(index)
            if (item == null) DomainResult.Failure(DomainError.Unexpected) else DomainResult.Success(item)
        }
    }
}
