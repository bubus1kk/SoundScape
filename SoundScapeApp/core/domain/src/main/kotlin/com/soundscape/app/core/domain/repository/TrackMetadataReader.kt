package com.soundscape.app.core.domain.repository

import com.soundscape.app.core.domain.model.DomainResult
import java.util.UUID

data class TrackMetadata(val id: UUID, val title: String, val artists: String?, val durationMs: Long)

interface TrackMetadataReader {
    suspend fun read(ids: List<UUID>): DomainResult<List<TrackMetadata>>
}
