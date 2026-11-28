package com.soundscape.app.core.domain.repository

import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.GeoPoint
import com.soundscape.app.core.domain.model.MapPlace
import com.soundscape.app.core.domain.model.MapPlaceKind
import com.soundscape.app.core.domain.model.Page
import com.soundscape.app.core.domain.model.PageRequest
import com.soundscape.app.core.domain.model.Track
import java.util.UUID

/** Reads public map places and their linked discovery tracks. */
interface MusicPlaceRepository : VisiblePlacesReader {

    /** Returns a page near a point within the requested radius in meters. */
    suspend fun getNearby(
        center: GeoPoint,
        radiusMeters: Int,
        page: PageRequest = PageRequest(),
    ): DomainResult<Page<MapPlace>>

    /** Returns public music places eligible for random discovery. */
    suspend fun getDiscoveryCandidates(page: PageRequest = PageRequest()): DomainResult<Page<MapPlace>>

    /** Returns tracks linked to the selected place or pin. */
    suspend fun getDiscoveryTracks(placeId: UUID, kind: MapPlaceKind): DomainResult<List<Track>>
}
