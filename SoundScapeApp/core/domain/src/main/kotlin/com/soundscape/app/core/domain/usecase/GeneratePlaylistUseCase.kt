package com.soundscape.app.core.domain.usecase

import com.soundscape.app.core.domain.model.*
import com.soundscape.app.core.domain.repository.*

class GeneratePlaylistUseCase(private val auth: AuthRepository, private val generator: PlaylistGenerator) {
    suspend operator fun invoke(request: GenerationRequest): DomainResult<GeneratedPlaylistDraft> {
        val options = request.options
        if (options.count !in 1..50) return invalid("count", "count_1_to_50")
        val genres = validateIds(options.genreIds, "genreIds", 20)
        if (genres is DomainResult.Failure) return genres
        val moods = validateIds(options.moodIds, "moodIds", 20)
        if (moods is DomainResult.Failure) return moods
        when (request) {
            is GenerationRequest.Quick -> if (request.prompt.isBlank() || request.prompt.length > 1000) return invalid("prompt", "length_1_to_1000")
            is GenerationRequest.Advanced -> {
                if (request.prompt != null && request.prompt.length > 1000) return invalid("prompt", "max_1000")
                if (request.prompt.isNullOrBlank() && options.genre.isNullOrBlank() && options.mood.isNullOrBlank() &&
                    options.genreIds.isEmpty() && options.moodIds.isEmpty()) return invalid("theme", "required")
            }
            is GenerationRequest.Journey -> if (request.stops.size < 2) return invalid("stops", "min_2")
            is GenerationRequest.Place -> Unit
        }
        val user = auth.currentUser()
        if (user is DomainResult.Failure) return user
        if ((user as DomainResult.Success).value == null) return DomainResult.Failure(DomainError.Unauthenticated)
        return generator.generate(request)
    }
}
