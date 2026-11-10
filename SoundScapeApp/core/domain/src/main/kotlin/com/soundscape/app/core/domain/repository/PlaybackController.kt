package com.soundscape.app.core.domain.repository

import com.soundscape.app.core.domain.model.*

interface PlaybackController {
    suspend fun play(source: PlaybackSource.InApp): DomainResult<Unit>
}
