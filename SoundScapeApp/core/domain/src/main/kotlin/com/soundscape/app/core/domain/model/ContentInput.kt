package com.soundscape.app.core.domain.model

import java.time.LocalDate
import java.util.UUID

/** Editable playlist fields, including the requested order of track occurrences. */
data class PlaylistInput(
    val title: String,
    val visibility: Visibility,
    val trackIds: List<UUID>,
    val description: String? = null,
    val coverPath: String? = null,
    val genreIds: List<Int> = emptyList(),
    val tagIds: List<Int> = emptyList(),
)

/** Editable fields for a musical pin and its attachments. */
data class MusicPinInput(
    val title: String,
    val coordinates: GeoPoint,
    val trackIds: List<UUID>,
    val visibility: Visibility,
    val photos: List<MediaReference> = emptyList(),
    val description: String? = null,
    val visitDate: LocalDate? = null,
)

/** Editable fields for a diary entry and its optional pin or map location. */
data class DiaryInput(
    val title: String,
    val date: LocalDate,
    val trackIds: List<UUID>,
    val visibility: Visibility? = null,
    val photos: List<MediaReference> = emptyList(),
    val locationName: String? = null,
    val coordinates: GeoPoint? = null,
    val musicPinId: UUID? = null,
    val body: String? = null,
)

/** Editable journey metadata and ordered stops. */
data class JourneyInput(
    val title: String,
    val visibility: Visibility,
    val stops: List<JourneyStop>,
    val playlistId: UUID? = null,
)
