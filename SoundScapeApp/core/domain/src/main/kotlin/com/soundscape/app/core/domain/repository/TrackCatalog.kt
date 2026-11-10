package com.soundscape.app.core.domain.repository

import com.soundscape.app.core.domain.model.*
import java.util.UUID

interface TrackCatalog : TrackSearcher {

    suspend fun getTrack(id: UUID): DomainResult<Track>
}
