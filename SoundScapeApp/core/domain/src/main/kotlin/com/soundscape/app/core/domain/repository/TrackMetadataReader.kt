package com.soundscape.app.core.domain.repository

import com.soundscape.app.core.domain.model.DomainResult
import java.util.UUID

/** Compact recording metadata used to display selected catalog tracks. */
data class TrackMetadata(val id: UUID, val title: String, val artists: String?, val durationMs: Long)

/** Reads display metadata for a batch of recordings. */
interface TrackMetadataReader {
    /** Loads metadata for the supplied catalog IDs. */
    suspend fun read(ids: List<UUID>): DomainResult<List<TrackMetadata>>
}
