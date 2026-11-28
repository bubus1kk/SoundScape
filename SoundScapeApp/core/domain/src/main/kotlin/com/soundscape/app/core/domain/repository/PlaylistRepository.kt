package com.soundscape.app.core.domain.repository

import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.Playlist
import com.soundscape.app.core.domain.model.PlaylistInput
import java.util.UUID

/** Writes user playlists and extends reading with create and update operations. */
interface PlaylistRepository : PlaylistReader {
    /** Creates a playlist using the supplied stable ID and track order. */
    suspend fun create(id: UUID, input: PlaylistInput): DomainResult<Playlist>

    /** Updates a playlist at the expected revision. */
    suspend fun update(id: UUID, expectedRevision: Long, input: PlaylistInput): DomainResult<Playlist>
}
