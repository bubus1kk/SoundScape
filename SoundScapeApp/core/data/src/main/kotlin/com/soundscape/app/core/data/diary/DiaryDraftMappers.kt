package com.soundscape.app.core.data.diary

import com.soundscape.app.core.database.DiaryDraftSnapshot
import com.soundscape.app.core.domain.model.DiaryDraft
import com.soundscape.app.core.domain.model.DiaryDraftInput
import com.soundscape.app.core.domain.model.DiaryDraftPhoto
import com.soundscape.app.core.domain.model.DiaryDraftScope
import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.GeoPoint
import com.soundscape.app.core.domain.model.Visibility
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

internal fun DiaryDraftSnapshot.toDomain(): DiaryDraft {
    require(draft.scope == "device_local")
    val coordinates = draft.latitude?.let { latitude ->
        (GeoPoint.create(latitude, requireNotNull(draft.longitude)) as DomainResult.Success).value
    }
    return DiaryDraft(UUID.fromString(draft.id), DiaryDraftScope.DeviceLocal,
        DiaryDraftInput(draft.title, draft.eventDate?.let(LocalDate::parse), draft.locationName,
            coordinates, draft.body, tracks.map { UUID.fromString(it.trackId) },
            photos.map { DiaryDraftPhoto(UUID.fromString(it.photoId)) },
            draft.musicPinId?.let(UUID::fromString), Visibility.valueOf(draft.visibility)),
        Instant.ofEpochMilli(draft.createdAt), Instant.ofEpochMilli(draft.updatedAt), draft.revision)
}

internal fun DiaryDraftScope.storageKey(): String = when (this) {
    DiaryDraftScope.DeviceLocal -> "device_local"
}
