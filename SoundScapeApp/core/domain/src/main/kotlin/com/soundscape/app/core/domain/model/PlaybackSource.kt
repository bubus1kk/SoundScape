package com.soundscape.app.core.domain.model

import java.net.URI
import java.time.Instant
import java.util.UUID

/** Stream or exact recording link returned by source resolution. */
sealed interface PlaybackSource {
    val trackId: UUID

    /** Temporary in-app URI with independent rights for caching and download. */
    data class InApp(
        override val trackId: UUID,
        val streamUri: URI,
        val expiresAt: Instant? = null,
        val cacheAllowed: Boolean = false,
        val downloadAllowed: Boolean = false,
    ) : PlaybackSource {
        /** Resolves expiry in the same clock context as its consumer. */
        fun isExpired(now: Instant): Boolean = expiresAt?.let { !now.isBefore(it) } ?: false
    }

    /** Exact external recording link, opened by an explicit user action. */
    data class External(override val trackId: UUID, val uri: URI) : PlaybackSource
}
