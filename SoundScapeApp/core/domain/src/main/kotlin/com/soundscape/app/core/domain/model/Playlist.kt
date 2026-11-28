package com.soundscape.app.core.domain.model

import java.util.UUID
import kotlin.time.Duration

/** A track occurrence in an ordered playlist. */
data class PlaylistItem(val track: Track)

/** User or editorial playlist with a stable identity and ordered track occurrences. */
data class Playlist(
    val id: UUID,
    val ownerId: UUID,
    val title: String,
    val visibility: Visibility,
    val items: List<PlaylistItem>,
    val revision: Long,
    val description: String? = null,
    val coverPath: String? = null,
    val genreIds: List<Int> = emptyList(),
    val tagIds: List<Int> = emptyList(),
    val placeId: UUID? = null,
    val isEditorial: Boolean = false,
) {
    /** Validates a playlist with a non-empty track list. */
    fun validate(): DomainResult<Unit> = validate(minItems = 1)

    /** Validates a read view, allowing the server to return an empty track list. */
    fun validateForRead(): DomainResult<Unit> = validate(minItems = 0)

    private fun validate(minItems: Int): DomainResult<Unit> = validateTitle(title).flatMap {
        when {
            revision < 1 -> invalid("revision", "positive")
            description != null && description.length > MAX_PLAYLIST_DESCRIPTION -> invalid("description", "max_2000")
            items.size !in minItems..MAX_CONTENT_TRACKS ->
                invalid("items", if (minItems == 0) "count_0_to_100" else "count_1_to_100")
            else -> validateFacets("genreIds", genreIds)
                .flatMap { validateFacets("tagIds", tagIds) }
                .flatMap { validateTracks() }
        }
    }

    private fun validateFacets(field: String, ids: List<Int>): DomainResult<Unit> = when {
        ids.size > MAX_PLAYLIST_FACETS -> invalid(field, "max_30")
        ids.any { it <= 0 } -> invalid(field, "positive_ids")
        ids.distinct().size != ids.size -> invalid(field, "duplicates")
        else -> DomainResult.Success(Unit)
    }

    private fun validateTracks(): DomainResult<Unit> {
        for (item in items) {
            val result = item.track.validate()
            if (result is DomainResult.Failure) return result
        }
        return DomainResult.Success(Unit)
    }

    /** Sums the duration of all track occurrences. */
    fun totalDuration(): Duration = items.fold(Duration.ZERO) { sum, item -> sum + item.track.duration }
    /** Sums the duration of track occurrences playable in SoundScape. */
    fun inAppDuration(): Duration {
        var total = Duration.ZERO
        for (item in items) {
            if (item.track.availability == TrackAvailability.IN_APP) total += item.track.duration
        }
        return total
    }
}
