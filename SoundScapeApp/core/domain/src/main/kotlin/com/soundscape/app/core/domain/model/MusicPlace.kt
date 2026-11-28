package com.soundscape.app.core.domain.model

import java.util.UUID


/** Published music place with an optional official playlist. */
data class MusicPlace(
    val id: UUID,
    val title: String,
    val coordinates: GeoPoint,
    val description: String? = null,
    val playlistId: UUID? = null,
    val coverPath: String? = null,
)
