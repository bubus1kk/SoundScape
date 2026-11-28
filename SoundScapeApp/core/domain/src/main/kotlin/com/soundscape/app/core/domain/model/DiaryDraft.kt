package com.soundscape.app.core.domain.model

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Local ownership scope of a diary draft. */
enum class DiaryDraftScope { DeviceLocal }

/** A photo selected while editing a diary draft. */
data class DiaryDraftPhoto(val id: UUID)

/** Editable diary fields that may still be incomplete. */
data class DiaryDraftInput(
    val title: String = "",
    val eventDate: LocalDate? = null,
    val locationName: String? = null,
    val coordinates: GeoPoint? = null,
    val body: String = "",
    val trackIds: List<UUID> = emptyList(),
    val photos: List<DiaryDraftPhoto> = emptyList(),
    val musicPinId: UUID? = null,
    val visibility: Visibility = Visibility.PRIVATE,
) {
    /** Checks local storage constraints while allowing an unfinished draft. */
    fun validate(): DomainResult<Unit> = when {
        title.length > MAX_TITLE_LENGTH -> invalid("title", "maximum_150")
        trackIds.size > MAX_CONTENT_TRACKS -> invalid("trackIds", "maximum_100")
        trackIds.distinct().size != trackIds.size -> invalid("trackIds", "duplicates")
        photos.size > MAX_DIARY_PHOTOS -> invalid("photos", "maximum_10")
        photos.distinct().size != photos.size -> invalid("photos", "duplicates")
        visibility != Visibility.PRIVATE -> invalid("visibility", "local_private_only")
        else -> DomainResult.Success(Unit)
    }
}

/** A persisted diary draft with local identity and revision. */
data class DiaryDraft(
    val id: UUID,
    val scope: DiaryDraftScope,
    val input: DiaryDraftInput,
    val createdAt: Instant,
    val updatedAt: Instant,
    val revision: Long,
)
