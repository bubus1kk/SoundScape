package com.soundscape.app.core.domain.repository

import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.GeneratedPlaylistDraft
import com.soundscape.app.core.domain.model.GenerationRequest
/** Produces an unsaved, ordered playlist draft. */
interface PlaylistGenerator {
    /** Runs the selected generation mode with the supplied filters. */
    suspend fun generate(request: GenerationRequest): DomainResult<GeneratedPlaylistDraft>
}
