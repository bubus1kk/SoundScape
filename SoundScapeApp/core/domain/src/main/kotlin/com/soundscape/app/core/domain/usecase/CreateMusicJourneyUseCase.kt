package com.soundscape.app.core.domain.usecase

import com.soundscape.app.core.domain.model.*
import com.soundscape.app.core.domain.repository.*
import java.util.UUID

class CreateMusicJourneyUseCase(private val auth: AuthRepository, private val journeys: JourneyRepository) {
    suspend operator fun invoke(id: UUID, input: JourneyInput): DomainResult<MusicJourney> {
        val title = validateTitle(input.title)
        if (title is DomainResult.Failure) return title
        if (input.stops.size !in 2..50) return invalid("stops", "count_2_to_50")
        val user = auth.currentUser()
        if (user is DomainResult.Failure) return user
        if ((user as DomainResult.Success).value == null) return DomainResult.Failure(DomainError.Unauthenticated)
        return journeys.create(id, input)
    }
}
