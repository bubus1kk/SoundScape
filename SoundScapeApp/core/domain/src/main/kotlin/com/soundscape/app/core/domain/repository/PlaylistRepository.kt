package com.soundscape.app.core.domain.repository

import com.soundscape.app.core.domain.model.*
import java.util.UUID

interface PlaylistRepository {
    suspend fun create(id: UUID, input: PlaylistInput): DomainResult<Playlist>

    suspend fun get(id: UUID): DomainResult<Playlist>

    suspend fun update(id: UUID, expectedRevision: Long, input: PlaylistInput): DomainResult<Playlist>
}
