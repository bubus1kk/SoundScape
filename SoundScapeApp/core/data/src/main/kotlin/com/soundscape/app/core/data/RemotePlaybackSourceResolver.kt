package com.soundscape.app.core.data

import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.PlaybackSource
import com.soundscape.app.core.domain.model.TrackAvailability
import com.soundscape.app.core.domain.repository.PlaybackSourceResolver
import com.soundscape.app.core.network.ResolveTrackApi
import com.soundscape.app.core.network.ResolveTrackRequestDto
import com.soundscape.app.core.network.ResolvedSourceDto
import dagger.Lazy
import java.net.URI
import java.net.URISyntaxException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.UUID
import javax.inject.Inject

private fun sourceTrackId(value: String): UUID =
    UUID.fromString(value).also {
        require(it.toString().equals(value, ignoreCase = true))
    }

@Suppress("MagicNumber")
private fun sourceUri(value: String?): URI {
    requireNotNull(value)
    val uri = try {
        URI(value).parseServerAuthority()
    } catch (_: URISyntaxException) {
        throw IllegalArgumentException("Invalid source URL")
    }
    require(uri.isAbsolute && !uri.isOpaque)
    require(uri.scheme.equals("https", ignoreCase = true))
    require(!uri.host.isNullOrBlank())
    require(uri.rawUserInfo == null)
    require(uri.port == -1 || uri.port in 1..65535)
    return uri
}

private fun sourceExpiry(value: String?): Instant? {
    if (value == null) return null
    return try {
        OffsetDateTime.parse(value).toInstant()
    } catch (_: DateTimeParseException) {
        throw IllegalArgumentException("Invalid stream expiration")
    }
}

internal fun ResolvedSourceDto.toDomain(): PlaybackSource {
    val id = sourceTrackId(trackId)
    require(provider.isNotBlank())
    return when (TrackAvailability.valueOf(availability)) {
        TrackAvailability.EXTERNAL_ONLY -> {
            require(!cacheAllowed && !downloadAllowed)
            PlaybackSource.External(trackId = id, uri = sourceUri(externalUrl))
        }
        TrackAvailability.IN_APP -> PlaybackSource.InApp(
            trackId = id,
            streamUri = sourceUri(streamUrl),
            expiresAt = sourceExpiry(streamExpiresAt),
            cacheAllowed = cacheAllowed,
            downloadAllowed = downloadAllowed,
        )
    }
}

class RemotePlaybackSourceResolver @Inject constructor(
    private val api: Lazy<ResolveTrackApi>,
    private val call: RemoteCall,
) : PlaybackSourceResolver {
    override suspend fun resolve(trackId: UUID): DomainResult<PlaybackSource> =
        call.execute(
            request = {
                api.get().resolve(ResolveTrackRequestDto(trackId = trackId.toString()))
            },
            map = {
                it.toDomain().also { source -> require(source.trackId == trackId) }
            },
        )
}
