package com.soundscape.app.core.domain.repository

import com.soundscape.app.core.domain.model.*
import java.util.UUID

interface MusicPinRepository {
    suspend fun create(id: UUID, input: MusicPinInput): DomainResult<MusicPin>
}
