package com.soundscape.app.core.domain.repository

import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.Playlist
import java.util.UUID

/** Reads a playlist using the caller's access context. */
interface PlaylistReader {
    /** Loads the playlist and its ordered track occurrences. */
    suspend fun get(id: UUID): DomainResult<Playlist>
}
