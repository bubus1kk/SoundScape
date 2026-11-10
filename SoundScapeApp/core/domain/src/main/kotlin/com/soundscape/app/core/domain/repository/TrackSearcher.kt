package com.soundscape.app.core.domain.repository

import com.soundscape.app.core.domain.model.*

interface TrackSearcher {
    suspend fun search(parameters: MusicSearch): DomainResult<Page<Track>>
}
