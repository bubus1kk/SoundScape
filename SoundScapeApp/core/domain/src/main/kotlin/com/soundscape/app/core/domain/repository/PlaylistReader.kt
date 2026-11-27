package com.soundscape.app.core.domain.repository

import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.Playlist
import java.util.UUID

interface PlaylistReader {
    suspend fun get(id: UUID): DomainResult<Playlist>
}
