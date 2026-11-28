package com.soundscape.app.core.domain.usecase

import com.soundscape.app.core.domain.model.DomainError
import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.MapPlace
import com.soundscape.app.core.domain.model.MapPlaceKind
import com.soundscape.app.core.domain.model.MusicPin
import com.soundscape.app.core.domain.model.Playlist
import com.soundscape.app.core.domain.repository.MusicPins
import com.soundscape.app.core.domain.repository.PlaylistReader

/** A place map result can resolve to either a city card or an accessible pin. */
sealed interface PlaceCard {
    /** A city card with its published playlist when available. */
    data class City(val summary: MapPlace, val officialPlaylist: Playlist?) : PlaceCard
    /** A card for a music pin accessible to the caller. */
    data class Pin(val pin: MusicPin) : PlaceCard
}

/** Resolves an existing map result without treating pin and place UUIDs as interchangeable. */
class LoadPlaceCardUseCase(
    private val playlists: PlaylistReader,
    private val pins: MusicPins,
) {
    /** Loads only the server-visible content referenced by the selected map item. */
    suspend operator fun invoke(place: MapPlace): DomainResult<PlaceCard> = when (place.kind) {
        MapPlaceKind.CITY -> city(place)
        MapPlaceKind.PIN -> when (val result = pins.get(place.id)) {
            is DomainResult.Failure -> result
            is DomainResult.Success -> DomainResult.Success(PlaceCard.Pin(result.value))
        }
    }

    private suspend fun city(place: MapPlace): DomainResult<PlaceCard> {
        val playlistId = place.playlistId ?: return DomainResult.Success(PlaceCard.City(place, null))
        return when (val result = playlists.get(playlistId)) {
            is DomainResult.Failure -> if (result.error == DomainError.NotFound) {
                DomainResult.Success(PlaceCard.City(place, null))
            } else result
            is DomainResult.Success -> DomainResult.Success(PlaceCard.City(place, result.value))
        }
    }
}
