package com.soundscape.app.core.domain.model

import java.util.UUID

/** Requested count and strict catalog filters. */
data class GenerationOptions(
    val count: Int = 10,
    val onlyInApp: Boolean = false,
    val genre: String? = null,
    val mood: String? = null,
    val genreIds: List<Int> = emptyList(),
    val moodIds: List<Int> = emptyList(),
)

/** Four supported generation inputs. */
sealed interface GenerationRequest {
    val options: GenerationOptions

    /** Prompt-based generation. */
    data class Quick(val prompt: String, override val options: GenerationOptions = GenerationOptions()) :
        GenerationRequest

    /** Prompt and filter based generation. */
    data class Advanced(val prompt: String? = null, override val options: GenerationOptions = GenerationOptions()) :
        GenerationRequest

    /** Generation from a published place or a public music pin. */
    data class Place(
        val placeId: UUID? = null,
        override val options: GenerationOptions = GenerationOptions(),
        val prompt: String? = null,
        val musicPinId: UUID? = null,
    ) : GenerationRequest

    /** Ordered stops. The server validates live access when a route snapshot is unavailable. */
    data class Journey(
        val stops: List<JourneyStop>,
        override val options: GenerationOptions = GenerationOptions(),
        val route: JourneyRoute? = null,
    ) : GenerationRequest
}

/** A selected catalog track and the optional explanation from the generator. */
data class GeneratedItem(
    val trackId: UUID,
    val reason: String? = null,
    val availability: TrackAvailability? = null,
)

/** Structural or filter failures in a successful generator response. */
enum class GenerationResultIssue { MISSING_TITLE, EMPTY, TOO_MANY, DUPLICATE_TRACK, IN_APP_REQUIRED, SERVER_REJECTED }

/** Unsaved generation result. Editing returns another unsaved draft. */
data class GeneratedPlaylistDraft(
    val title: String,
    val description: String,
    val items: List<GeneratedItem>,
    val warnings: List<String> = emptyList(),
) {
    /** Validates generated content while accepting legitimately short selections. */
    fun validate(options: GenerationOptions): DomainResult<Unit> {
        val issue = when {
            title.isBlank() -> GenerationResultIssue.MISSING_TITLE
            items.isEmpty() -> GenerationResultIssue.EMPTY
            items.size > options.count -> GenerationResultIssue.TOO_MANY
            items.distinctBy { it.trackId }.size != items.size -> GenerationResultIssue.DUPLICATE_TRACK
            options.onlyInApp && items.any { it.availability != TrackAvailability.IN_APP } ->
                GenerationResultIssue.IN_APP_REQUIRED
            else -> return DomainResult.Success(Unit)
        }
        return DomainResult.Failure(DomainError.InvalidGeneration(issue))
    }

    /** Adds one verified catalog selection at the end of the draft. */
    fun add(track: Track): DomainResult<GeneratedPlaylistDraft> {
        val validation = track.validate()
        return when {
            validation is DomainResult.Failure -> validation
            items.any { it.trackId == track.id } -> invalid("trackIds", "duplicates")
            items.size >= MAX_CONTENT_TRACKS -> invalid("trackIds", "count_1_to_100")
            else -> {
                val item = GeneratedItem(track.id, availability = track.availability)
                DomainResult.Success(copy(items = items + item))
            }
        }
    }

    /** Removes a track, allowing an empty intermediate draft. */
    fun remove(trackId: UUID): GeneratedPlaylistDraft = copy(items = items.filterNot { it.trackId == trackId })

    /** Moves a track to a zero-based index in the draft. */
    fun move(trackId: UUID, index: Int): DomainResult<GeneratedPlaylistDraft> {
        val item = items.find { it.trackId == trackId }
        return when {
            item == null -> DomainResult.Failure(DomainError.NotFound)
            index !in items.indices -> invalid("index", "outside_draft")
            else -> DomainResult.Success(copy(items = items.toMutableList().apply { remove(item); add(index, item) }))
        }
    }

    /** Builds the user-selected playlist input for explicit saving. */
    fun toPlaylistInput(visibility: Visibility): PlaylistInput =
        PlaylistInput(title, visibility, items.map { it.trackId }, description)
}
