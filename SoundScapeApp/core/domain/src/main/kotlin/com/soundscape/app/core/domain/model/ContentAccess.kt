package com.soundscape.app.core.domain.model

import java.time.Instant
import java.util.UUID

/** Object types supported by server-side link resolution. */
enum class ShareKind { PLAYLIST, MUSIC_PIN, TRAVEL_EVENT, JOURNEY, PROFILE }

/** Stable reference to a shareable object. */
data class ShareTarget(val kind: ShareKind, val id: UUID)

/** Metadata safe to display without disclosing the link credential. */
data class ShareMetadata(val id: UUID, val target: ShareTarget, val expiresAt: Instant?) {
    /** Whether the link has reached its known expiry. */
    fun isExpired(now: Instant): Boolean = expiresAt?.let { !now.isBefore(it) } ?: false
}

/** Create response containing a route that may embed a secret in its fragment. */
class CreatedShare(val metadata: ShareMetadata, val routePath: String)

/** Inputs to server-side share resolution. A token is transient and sensitive. */
sealed interface ShareLookup {
    /** Looks up the target represented by a sharing token. */
    class Token(val value: String) : ShareLookup
    /** Looks up a known target with optional proof of share access. */
    class Target(val target: ShareTarget, val token: String? = null) : ShareLookup
}

/** Object resolved and authorized by the server. */
data class OpenedShare(val target: ShareTarget, val title: String, val routePath: String)

/** Effective access for local presentation using server-verified link authorization. */
fun Visibility.allowsRead(isOwner: Boolean = false, hasAuthorizedLink: Boolean = false): Boolean =
    isOwner || this == Visibility.PUBLIC || (this == Visibility.UNLISTED && hasAuthorizedLink)
