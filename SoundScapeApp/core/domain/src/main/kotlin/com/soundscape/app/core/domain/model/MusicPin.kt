package com.soundscape.app.core.domain.model

import java.time.LocalDate
import java.util.UUID

/** Opaque reference to an attached media item. */
data class MediaReference(val value: String)

/** An ordered collection of tracks pinned to a point on the map. */
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
    /** Checks the stored pin revision and attachment constraints. */
    fun validate(): DomainResult<Unit> = validateTitle(title).flatMap {
        if (revision < 1) invalid("revision", "positive")
        else validateAttachments(trackIds, photos, MAX_PIN_PHOTOS)
    }
}

internal fun validateAttachments(
    tracks: List<UUID>,
    photos: List<MediaReference>,
    maxPhotos: Int,
): DomainResult<Unit> = when {
    tracks.size !in 1..MAX_CONTENT_TRACKS -> invalid("trackIds", "count_1_to_100")
    tracks.distinct().size != tracks.size -> invalid("trackIds", "duplicates")
    photos.size > maxPhotos -> invalid("photos", "too_many")
    photos.any { it.value.isBlank() } -> invalid("photos", "blank_reference")
    photos.distinct().size != photos.size -> invalid("photos", "duplicates")
    else -> DomainResult.Success(Unit)
}
