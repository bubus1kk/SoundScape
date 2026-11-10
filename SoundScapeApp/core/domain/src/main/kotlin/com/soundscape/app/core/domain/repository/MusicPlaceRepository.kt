package com.soundscape.app.core.domain.repository

import com.soundscape.app.core.domain.model.*
import java.util.UUID

interface MusicPlaceRepository : VisiblePlacesReader {

    suspend fun getNearby(center: GeoPoint, radiusMeters: Int, page: PageRequest = PageRequest()): DomainResult<Page<MapPlace>>

    suspend fun getDiscoveryCandidates(page: PageRequest = PageRequest()): DomainResult<Page<MapPlace>>

    suspend fun getDiscoveryTracks(placeId: UUID, kind: MapPlaceKind): DomainResult<List<Track>>
}
