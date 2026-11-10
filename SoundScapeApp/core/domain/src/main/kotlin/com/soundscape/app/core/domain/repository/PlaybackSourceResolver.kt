package com.soundscape.app.core.domain.repository

import com.soundscape.app.core.domain.model.*
import java.util.UUID
interface PlaybackSourceResolver {
    suspend fun resolve(trackId: UUID): DomainResult<PlaybackSource>
}
