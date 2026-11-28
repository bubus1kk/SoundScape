package com.soundscape.app.core.domain.usecase

import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.GeneratedPlaylistDraft
import com.soundscape.app.core.domain.model.GenerationOptions
import com.soundscape.app.core.domain.model.GenerationRequest
import com.soundscape.app.core.domain.model.MAX_JOURNEY_STOPS
import com.soundscape.app.core.domain.model.MIN_JOURNEY_STOPS
import com.soundscape.app.core.domain.model.flatMap
import com.soundscape.app.core.domain.model.invalid
import com.soundscape.app.core.domain.repository.AuthRepository
import com.soundscape.app.core.domain.repository.PlaylistGenerator

private const val MAX_GENERATION_TRACKS = 50
private const val MAX_GENERATION_FACETS = 20
private const val MAX_GENERATION_PROMPT = 1000

/** Validates input and returns an unsaved generation result from the server. */
class GeneratePlaylistUseCase(private val auth: AuthRepository, private val generator: PlaylistGenerator) {
    /** Requests and checks a preview without invoking playlist storage. */
    suspend operator fun invoke(request: GenerationRequest): DomainResult<GeneratedPlaylistDraft> =
        validateFilters(request.options)
            .flatMap { validateMode(request) }
            .flatMap { auth.requireCurrentUser() }
            .flatMap { generator.generate(request) }
            .flatMap { draft -> draft.validate(request.options).flatMap { DomainResult.Success(draft) } }

    private fun validateFilters(options: GenerationOptions): DomainResult<Unit> {
        if (options.count !in 1..MAX_GENERATION_TRACKS) return invalid("count", "count_1_to_50")
        return validateIds(options.genreIds, "genreIds", MAX_GENERATION_FACETS).flatMap {
            validateIds(options.moodIds, "moodIds", MAX_GENERATION_FACETS)
        }
    }

    private fun validateMode(request: GenerationRequest): DomainResult<Unit> {
        return when (request) {
            is GenerationRequest.Quick -> validateQuick(request.prompt)
            is GenerationRequest.Advanced -> validateAdvanced(request)
            is GenerationRequest.Journey -> when {
                request.stops.size !in MIN_JOURNEY_STOPS..MAX_JOURNEY_STOPS -> invalid("stops", "count_2_to_50")
                request.route?.canGenerate == false -> invalid("stops", "route_needs_edit")
                else -> DomainResult.Success(Unit)
            }
            is GenerationRequest.Place -> when {
                (request.placeId == null) == (request.musicPinId == null) -> invalid("place", "exactly_one_target")
                (request.prompt?.length ?: 0) > MAX_GENERATION_PROMPT -> invalid("prompt", "max_1000")
                else -> DomainResult.Success(Unit)
            }
        }
    }

    private fun validateQuick(prompt: String): DomainResult<Unit> =
        if (prompt.isBlank() || prompt.length > MAX_GENERATION_PROMPT) invalid("prompt", "length_1_to_1000")
        else DomainResult.Success(Unit)

    private fun validateAdvanced(request: GenerationRequest.Advanced): DomainResult<Unit> {
        val options = request.options
        val hasTheme = listOf(request.prompt, options.genre, options.mood).any { !it.isNullOrBlank() }
        return when {
            (request.prompt?.length ?: 0) > MAX_GENERATION_PROMPT -> invalid("prompt", "max_1000")
            !hasTheme && options.genreIds.isEmpty() && options.moodIds.isEmpty() -> invalid("theme", "required")
            else -> DomainResult.Success(Unit)
        }
    }
}
