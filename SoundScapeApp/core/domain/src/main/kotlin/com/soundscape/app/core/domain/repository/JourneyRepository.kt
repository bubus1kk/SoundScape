package com.soundscape.app.core.domain.repository

import com.soundscape.app.core.domain.model.*
import java.util.UUID

interface JourneyRepository {
    suspend fun create(id: UUID, input: JourneyInput): DomainResult<MusicJourney>
}
