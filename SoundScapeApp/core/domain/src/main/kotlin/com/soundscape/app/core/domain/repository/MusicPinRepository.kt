package com.soundscape.app.core.domain.repository

import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.MusicPin
import com.soundscape.app.core.domain.model.MusicPinInput
import java.util.UUID

/** Stores a music pin at the user's selected point. */
interface MusicPinRepository {
    /** Creates a pin using the supplied stable ID. */
    suspend fun create(id: UUID, input: MusicPinInput): DomainResult<MusicPin>
}
