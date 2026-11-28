package com.soundscape.app.core.domain.model

import java.time.LocalDate
import java.util.UUID

/** A dated musical memory with independent visibility and optional map coordinates. */
data class DiaryEntry(
    val id: UUID,
    val ownerId: UUID,
    val title: String,
    val date: LocalDate,
    val trackIds: List<UUID>,
    val photos: List<MediaReference>,
    val revision: Long,
    val visibility: Visibility = Visibility.PRIVATE,
    val locationName: String? = null,
    val coordinates: GeoPoint? = null,
    val musicPinId: UUID? = null,
    val body: String? = null,
) {
    /** Checks the stored entry, including its location and attachments. */
    fun validate(): DomainResult<Unit> = validateTitle(title).flatMap {
        when {
            revision < 1 -> invalid("revision", "positive")
            locationName != null && locationName.isBlank() -> invalid("locationName", "blank")
            locationName.isNullOrBlank() && coordinates == null && musicPinId == null ->
                invalid("location", "required")
            else -> validateAttachments(trackIds, photos, MAX_DIARY_PHOTOS)
        }
    }
}
