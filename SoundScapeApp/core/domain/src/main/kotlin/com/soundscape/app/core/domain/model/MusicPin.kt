package com.soundscape.app.core.domain.model

import java.time.LocalDate
import java.util.UUID

data class MediaReference(val value: String)

data class MusicPin(
    val id: UUID,
    val ownerId: UUID,
    val title: String,
    val coordinates: GeoPoint,
    val trackIds: List<UUID>,
    val photos: List<MediaReference>,
    val visibility: Visibility,
    val revision: Long,
    val description: String? = null,
    val visitDate: LocalDate? = null,
) {
    fun validate(): DomainResult<Unit> {
        val titleResult = validateTitle(title)
        if (titleResult is DomainResult.Failure) return titleResult
        if (revision < 1) return invalid("revision", "positive")
        return validateAttachments(trackIds, photos, 5)
    }
}

internal fun validateAttachments(tracks: List<UUID>, photos: List<MediaReference>, maxPhotos: Int): DomainResult<Unit> {
    if (tracks.size !in 1..100) return invalid("trackIds", "count_1_to_100")
    if (tracks.distinct().size != tracks.size) return invalid("trackIds", "duplicates")
    if (photos.size > maxPhotos) return invalid("photos", "too_many")
    if (photos.any { it.value.isBlank() }) return invalid("photos", "blank_reference")
    if (photos.distinct().size != photos.size) return invalid("photos", "duplicates")
    return DomainResult.Success(Unit)
}
