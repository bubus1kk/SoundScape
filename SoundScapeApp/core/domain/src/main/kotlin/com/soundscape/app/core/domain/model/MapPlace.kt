package com.soundscape.app.core.domain.model

import java.util.UUID

/** Distinguishes a city from a user-created pin on the map. */
enum class MapPlaceKind { CITY, PIN }

/** Map result with coordinates, display fields and an optional playlist reference. */
data class MapPlace(
    val id: UUID,
    val kind: MapPlaceKind,
    val title: String,
    val coordinates: GeoPoint,
    val description: String? = null,
    val playlistId: UUID? = null,
    val coverPath: String? = null,
)
