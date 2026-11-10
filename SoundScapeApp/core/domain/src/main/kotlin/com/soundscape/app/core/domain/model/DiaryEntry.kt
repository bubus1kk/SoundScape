package com.soundscape.app.core.domain.model

import java.time.LocalDate
import java.util.UUID
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
    fun validate(): DomainResult<Unit> {
        val titleResult = validateTitle(title)
        if (titleResult is DomainResult.Failure) return titleResult
        if (revision < 1) return invalid("revision", "positive")
        if (locationName != null && locationName.isBlank()) return invalid("locationName", "blank")
        if (locationName.isNullOrBlank() && coordinates == null && musicPinId == null) {
            return invalid("location", "required")
        }
        return validateAttachments(trackIds, photos, 10)
    }
}
