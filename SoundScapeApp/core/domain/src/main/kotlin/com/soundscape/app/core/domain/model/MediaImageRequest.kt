package com.soundscape.app.core.domain.model

import java.util.UUID

enum class MediaObjectType { PIN, EVENT, PLAYLIST, PLACE }

class MediaShareToken(val value: String) {
    init {
        require(value.length in 32..256)
        require(value.all { it in '!'..'~' })
    }

    override fun toString(): String = "MediaShareToken(REDACTED)"
}

data class MediaImageRequest(
    val objectType: MediaObjectType,
    val objectId: UUID,
    val photoId: UUID? = null,
    val shareToken: MediaShareToken? = null,
) {
    init {
        val requiresPhoto = objectType == MediaObjectType.PIN || objectType == MediaObjectType.EVENT
        require(requiresPhoto == (photoId != null))
    }
}
