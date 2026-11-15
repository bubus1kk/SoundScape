package com.soundscape.app.core.domain.model

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class DiaryDraftScope { DeviceLocal }

data class DiaryDraftPhoto(val id: UUID)

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
    fun validate(): DomainResult<Unit> = when {
        title.length > 150 -> invalid("title", "maximum_150")
        trackIds.size > 100 -> invalid("trackIds", "maximum_100")
        trackIds.distinct().size != trackIds.size -> invalid("trackIds", "duplicates")
        photos.size > 10 -> invalid("photos", "maximum_10")
        photos.distinct().size != photos.size -> invalid("photos", "duplicates")
        visibility != Visibility.PRIVATE -> invalid("visibility", "local_private_only")
        else -> DomainResult.Success(Unit)
    }
}

data class DiaryDraft(
    val id: UUID,
    val scope: DiaryDraftScope,
    val input: DiaryDraftInput,
    val createdAt: Instant,
    val updatedAt: Instant,
    val revision: Long,
)
