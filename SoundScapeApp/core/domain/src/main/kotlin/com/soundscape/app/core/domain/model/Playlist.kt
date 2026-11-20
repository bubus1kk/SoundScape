package com.soundscape.app.core.domain.model

import java.util.UUID
import kotlin.time.Duration
data class PlaylistItem(val track: Track)

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
    fun validate(): DomainResult<Unit> = validate(minItems = 1)

    fun validateForRead(): DomainResult<Unit> = validate(minItems = 0)

    private fun validate(minItems: Int): DomainResult<Unit> {
        val titleResult = validateTitle(title)
        if (titleResult is DomainResult.Failure) return titleResult
        if (revision < 1) return invalid("revision", "positive")
        if (description != null && description.length > 2000) return invalid("description", "max_2000")
        if (items.size !in minItems..100) {
            val reason = if (minItems == 0) "count_0_to_100" else "count_1_to_100"
            return invalid("items", reason)
        }
        for ((field, ids) in listOf("genreIds" to genreIds, "tagIds" to tagIds)) {
            if (ids.size > 30) return invalid(field, "max_30")
            if (ids.any { it <= 0 }) return invalid(field, "positive_ids")
            if (ids.distinct().size != ids.size) return invalid(field, "duplicates")
        }
        for (item in items) {
            val result = item.track.validate()
            if (result is DomainResult.Failure) return result
        }
        return DomainResult.Success(Unit)
    }
    fun totalDuration(): Duration = items.fold(Duration.ZERO) { sum, item -> sum + item.track.duration }
    fun inAppDuration(): Duration {
        var total = Duration.ZERO
        for (item in items) {
            if (item.track.availability == TrackAvailability.IN_APP) total += item.track.duration
        }
        return total
    }
}
