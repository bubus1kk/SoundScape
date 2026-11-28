package com.soundscape.app.core.domain.model

import java.util.UUID

private const val MIN_MEDIA_TOKEN_LENGTH = 32
private const val MAX_MEDIA_TOKEN_LENGTH = 256

/** Object kinds supported by image resolution. */
enum class MediaObjectType { PIN, EVENT, PLAYLIST, PLACE }

/** An image access token with a redacted diagnostic representation. */
class MediaShareToken(val value: String) {
    init {
        require(value.length in MIN_MEDIA_TOKEN_LENGTH..MAX_MEDIA_TOKEN_LENGTH)
        require(value.all { it in '!'..'~' })
    }

    /** Returns a display-safe representation of the token. */
    override fun toString(): String = "MediaShareToken(REDACTED)"
}

/** Selects an object image or a specific pin or diary photo. */
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
