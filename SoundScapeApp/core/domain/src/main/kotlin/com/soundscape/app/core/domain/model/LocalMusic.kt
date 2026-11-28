package com.soundscape.app.core.domain.model

import java.time.Instant
import java.util.UUID

private const val BYTES_PER_MEGABYTE = 1_000_000L
private const val BYTES_PER_GIGABYTE = 1_000 * BYTES_PER_MEGABYTE
private const val SMALL_CACHE_MEGABYTES = 500

/** Supported audio cache limits expressed in bytes. */
enum class AudioCacheLimit(val bytes: Long) {
    MB_500(SMALL_CACHE_MEGABYTES * BYTES_PER_MEGABYTE), GB_1(BYTES_PER_GIGABYTE), GB_2(2 * BYTES_PER_GIGABYTE),
}

/** Cached metadata can exist without any audio bytes. */
data class CachedTrackMetadata(val track: Track, val refreshedAt: Instant)

/** Audio stored under an independent cache grant. */
data class CachedAudio(val trackId: UUID, val bytes: Long, val complete: Boolean) {
    /** Offline playback needs both a complete file and current cache authorization. */
    fun canPlayOffline(source: PlaybackSource.InApp): Boolean =
        complete && bytes > 0 && source.trackId == trackId && source.cacheAllowed
}

/** Capability produced only for a resolved source that permits downloads. */
class AuthorizedDownload private constructor(val source: PlaybackSource.InApp) {
    /** Factory that enforces source rights and expiry for downloads. */
    companion object {
        /** Evaluates the independent grant and the lifetime of the download source. */
        fun from(source: PlaybackSource.InApp, now: Instant): DomainResult<AuthorizedDownload> = when {
            !source.downloadAllowed -> DomainResult.Failure(DomainError.Forbidden)
            source.isExpired(now) -> DomainResult.Failure(DomainError.PlaybackSourceExpired(source.trackId))
            else -> DomainResult.Success(AuthorizedDownload(source))
        }
    }
}

/** A guest or account partition within device-local listening history. */
sealed interface LocalAccountScope {
    /** History recorded while the user is signed out. */
    data object Guest : LocalAccountScope
    /** History associated with an authenticated account. */
    data class Account(val userId: UUID) : LocalAccountScope
}

/** Local listening event, scoped by the history port. */
data class ListeningEvent(val id: UUID, val trackId: UUID, val playedAt: Instant)
