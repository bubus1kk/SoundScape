package com.soundscape.app.core.domain.model

import java.util.UUID

enum class MapPlaceKind { CITY, PIN }

data class MapPlace(
    val id: UUID,
    val kind: MapPlaceKind,
    val title: String,
    val coordinates: GeoPoint,
    val description: String? = null,
    val playlistId: UUID? = null,
    val coverPath: String? = null,
)
