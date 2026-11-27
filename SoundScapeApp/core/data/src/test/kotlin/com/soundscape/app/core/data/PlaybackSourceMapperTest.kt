package com.soundscape.app.core.data

import com.soundscape.app.core.domain.model.PlaybackSource
import com.soundscape.app.core.network.ResolvedSourceDto
import java.net.URI
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PlaybackSourceMapperTest {
    @Test
    fun `maps Apple Music Bandcamp and future providers`() {
        val cases = listOf(
            "APPLE_MUSIC" to "https://music.apple.com/us/song/fixture/1234567890",
            "BANDCAMP" to "https://fixture-artist.bandcamp.com/track/fixture",
            "FUTURE_PROVIDER" to "https://music.example.test/track/fixture",
        )
        for ((provider, url) in cases) {
            assertEquals(
                PlaybackSource.External(UUID.fromString(TRACK_ID), URI(url)),
                external(provider, url).toDomain(),
            )
        }
    }

    @Test
    fun `maps stream permissions and expiration without changing them`() {
        for (cache in listOf(false, true)) {
            for (download in listOf(false, true)) {
                val dto = inApp().copy(
                    cacheAllowed = cache,
                    downloadAllowed = download,
                    streamExpiresAt = "2030-01-01T03:00:00+03:00",
                )
                val source = dto.toDomain() as PlaybackSource.InApp
                assertEquals(UUID.fromString(TRACK_ID), source.trackId)
                assertEquals(URI(STREAM_URL), source.streamUri)
                assertEquals(Instant.parse("2030-01-01T00:00:00Z"), source.expiresAt)
                assertEquals(cache, source.cacheAllowed)
                assertEquals(download, source.downloadAllowed)
            }
        }
    }

    @Test
    fun `stream expiration may be absent`() {
        assertNull((inApp().toDomain() as PlaybackSource.InApp).expiresAt)
    }

    @Test
    fun `rejects missing required source URL`() {
        val invalid = listOf(
            external().copy(externalUrl = null),
            external().copy(externalUrl = ""),
            inApp().copy(streamUrl = null),
            inApp().copy(streamUrl = ""),
        )
        for (dto in invalid) {
            assertThrows(IllegalArgumentException::class.java) { dto.toDomain() }
        }
    }

    @Test
    fun `rejects unsafe or malformed URLs for both source kinds`() {
        val urls = listOf(
            "http://music.example.test/track",
            "javascript:alert(1)",
            "file:///tmp/audio",
            "content://music/track",
            "//music.example.test/track",
            "/track/fixture",
            "https:///track",
            "https://user:password@music.example.test/track",
            "https://user@music.example.test/track",
            "https://bad_host.example.test/track",
            "https://music.example.test:70000/track",
            "https://music.example.test:0/track",
            "https://music.example.test/a b",
        )
        for (url in urls) {
            for (dto in listOf(external().copy(externalUrl = url), inApp().copy(streamUrl = url))) {
                assertThrows(IllegalArgumentException::class.java) { dto.toDomain() }
            }
        }
    }

    @Test
    fun `rejects invalid UUID provider availability and expiration`() {
        val invalid = listOf(
            external().copy(trackId = "invalid"),
            external().copy(trackId = "1-1-1-1-1"),
            external().copy(provider = " "),
            external().copy(availability = "FUTURE_AVAILABILITY"),
            inApp().copy(streamExpiresAt = "not-a-date"),
        )
        for (dto in invalid) {
            assertThrows(IllegalArgumentException::class.java) { dto.toDomain() }
        }
    }

    @Test
    fun `external source cannot silently discard download or cache grants`() {
        for (dto in listOf(external().copy(cacheAllowed = true), external().copy(downloadAllowed = true))) {
            assertThrows(IllegalArgumentException::class.java) { dto.toDomain() }
        }
    }

    @Test
    fun `preserves signed URL query without reconstructing it`() {
        val url = "https://cdn.example.test/audio?token=a%2Fb&expires=123"
        val source = inApp().copy(streamUrl = url).toDomain() as PlaybackSource.InApp
        assertEquals(url, source.streamUri.toString())
    }

    private fun external(
        provider: String = "APPLE_MUSIC",
        url: String = "https://music.apple.com/us/song/fixture/1234567890",
    ) = ResolvedSourceDto(
        trackId = TRACK_ID,
        availability = "EXTERNAL_ONLY",
        provider = provider,
        cacheAllowed = false,
        downloadAllowed = false,
        externalUrl = url,
    )

    private fun inApp() = ResolvedSourceDto(
        trackId = TRACK_ID,
        availability = "IN_APP",
        provider = "AUDIUS",
        cacheAllowed = false,
        downloadAllowed = false,
        streamUrl = STREAM_URL,
    )

    private companion object {
        const val TRACK_ID = "11111111-1111-4111-8111-111111111111"
        const val STREAM_URL = "https://cdn.example.test/audio?token=fixture"
    }
}
