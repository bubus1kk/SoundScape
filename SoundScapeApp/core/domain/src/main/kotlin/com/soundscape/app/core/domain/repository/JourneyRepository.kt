package com.soundscape.app.core.domain.repository

import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.JourneyInput
import com.soundscape.app.core.domain.model.MusicJourney
import java.util.UUID

/** Stores ordered musical journeys. */
interface JourneyRepository {
    /** Creates a journey from the supplied stops and stable ID. */
    suspend fun create(id: UUID, input: JourneyInput): DomainResult<MusicJourney>
}
