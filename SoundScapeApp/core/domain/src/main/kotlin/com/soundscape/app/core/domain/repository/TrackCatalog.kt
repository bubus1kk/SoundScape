package com.soundscape.app.core.domain.repository

import com.soundscape.app.core.domain.model.*
import java.util.UUID

interface TrackCatalog {
    suspend fun search(parameters: MusicSearch): DomainResult<Page<Track>>

    suspend fun getTrack(id: UUID): DomainResult<Track>
}
