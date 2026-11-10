package com.soundscape.app.core.domain.model

import java.net.URI
import java.time.Instant
import java.util.UUID

sealed interface PlaybackSource {
    val trackId: UUID

    data class InApp(
        override val trackId: UUID,
        val streamUri: URI,
        val expiresAt: Instant? = null,
        val cacheAllowed: Boolean = false,
        val downloadAllowed: Boolean = false,
    ) : PlaybackSource

    data class External(override val trackId: UUID, val uri: URI) : PlaybackSource
}
