package com.soundscape.app.core.domain.repository

import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.PlaybackSource
import java.util.UUID
/** Resolves a track to its current authorized playback source. */
interface PlaybackSourceResolver {
    /** Returns the current source and its playback information for the identified track. */
    suspend fun resolve(trackId: UUID): DomainResult<PlaybackSource>
}
