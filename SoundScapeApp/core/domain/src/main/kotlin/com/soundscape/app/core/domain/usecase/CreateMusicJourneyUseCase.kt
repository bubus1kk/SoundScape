package com.soundscape.app.core.domain.usecase

import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.JourneyInput
import com.soundscape.app.core.domain.model.MusicJourney
import com.soundscape.app.core.domain.model.flatMap
import com.soundscape.app.core.domain.repository.AuthRepository
import com.soundscape.app.core.domain.repository.JourneyRepository
import java.util.UUID

/** Creates a musical journey from ordered route stops. */
class CreateMusicJourneyUseCase(private val auth: AuthRepository, private val journeys: JourneyRepository) {
    /** Submits an authenticated route after validating the local fields. */
    suspend operator fun invoke(id: UUID, input: JourneyInput): DomainResult<MusicJourney> =
        validateJourney(input)
            .flatMap { auth.requireCurrentUser() }
            .flatMap { journeys.create(id, input) }
}
