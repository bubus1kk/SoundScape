package com.soundscape.app.core.domain.model

import java.time.LocalDate
import java.util.UUID

data class PlaylistInput(
    val title: String,
    val visibility: Visibility,
    val trackIds: List<UUID>,
    val description: String? = null,
    val coverPath: String? = null,
    val genreIds: List<Int> = emptyList(),
    val tagIds: List<Int> = emptyList(),
)

data class MusicPinInput(
    val title: String,
    val coordinates: GeoPoint,
    val trackIds: List<UUID>,
    val visibility: Visibility,
    val photos: List<MediaReference> = emptyList(),
    val description: String? = null,
    val visitDate: LocalDate? = null,
)

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

data class JourneyInput(
    val title: String,
    val visibility: Visibility,
    val stops: List<JourneyStop>,
    val playlistId: UUID? = null,
)
