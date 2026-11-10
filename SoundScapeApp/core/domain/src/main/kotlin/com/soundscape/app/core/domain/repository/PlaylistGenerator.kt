package com.soundscape.app.core.domain.repository

import com.soundscape.app.core.domain.model.*
interface PlaylistGenerator {
    suspend fun generate(request: GenerationRequest): DomainResult<GeneratedPlaylistDraft>
}
