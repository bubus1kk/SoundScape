@file:Suppress("MagicNumber")

package com.soundscape.app.core.data

import com.soundscape.app.core.domain.model.TrackAvailability
import com.soundscape.app.core.domain.model.Visibility
import com.soundscape.app.core.network.PlaylistDetailDto
import com.soundscape.app.core.network.PlaylistItemDto
import com.soundscape.app.core.network.TrackViewDto
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PlaylistMapperTest {
    @Test
    fun `maps playlist metadata and track fields`() {
        val dto = fixture()
        val playlist = dto.toDomain()
        assertEquals(UUID.fromString(dto.id), playlist.id)
        assertEquals(UUID.fromString(dto.ownerId), playlist.ownerId)
        assertEquals(dto.title, playlist.title)
        assertEquals(Visibility.PRIVATE, playlist.visibility)
        assertEquals(dto.revision, playlist.revision)
        assertEquals(dto.description, playlist.description)
        assertEquals(dto.coverPath, playlist.coverPath)
        assertEquals(UUID.fromString(dto.placeId), playlist.placeId)
        assertEquals(dto.genreIds, playlist.genreIds)
        assertEquals(dto.tagIds, playlist.tagIds)
        assertEquals(true, playlist.isEditorial)
        val track = playlist.items.single().track
        assertEquals(UUID.fromString(TRACK_A), track.id)
        assertEquals("Fixture A", track.title)
        assertEquals("Fixture artist", track.artist)
        assertEquals(180000.milliseconds, track.duration)
        assertEquals(TrackAvailability.EXTERNAL_ONLY, track.availability)
    }

    @Test
    fun `sorts by position and preserves repeated track UUIDs`() {
        val a = track(TRACK_A, "Fixture A")
        val b = track(TRACK_B, "Fixture B")
        val dto = fixture().copy(items = listOf(
            PlaylistItemDto(position = 3, track = a),
            PlaylistItemDto(position = 1, track = a),
            PlaylistItemDto(position = 2, track = b),
        ))
        val tracks = dto.toDomain().items.map { it.track }
        assertEquals(listOf(TRACK_A, TRACK_B, TRACK_A).map(UUID::fromString), tracks.map { it.id })
        assertEquals(3, tracks.size)
        assertEquals(tracks[0], tracks[2])
    }

    @Test
    fun `positive distinct positions need not be contiguous`() {
        val dto = fixture().copy(items = listOf(
            PlaylistItemDto(10, track(TRACK_B, "Fixture B")),
            PlaylistItemDto(2, track(TRACK_A, "Fixture A")),
        ))
        assertEquals(listOf(TRACK_A, TRACK_B).map(UUID::fromString), dto.toDomain().items.map { it.track.id })
    }

    @Test
    fun `rejects duplicate and nonpositive positions`() {
        val item = fixture().items.single()
        val invalidItems = listOf(
            listOf(item.copy(position = 0)),
            listOf(item.copy(position = -1)),
            listOf(item, item.copy(track = track(TRACK_B, "Fixture B"))),
        )
        for (items in invalidItems) {
            assertThrows(IllegalArgumentException::class.java) { fixture().copy(items = items).toDomain() }
        }
    }

    @Test
    fun `nullable fields and unknown provider do not break mapping`() {
        val dto = fixture().copy(
            description = null,
            coverPath = null,
            placeId = null,
            items = listOf(PlaylistItemDto(1, track(TRACK_A, "Fixture A").copy(provider = "future-provider"))),
        )
        val playlist = dto.toDomain()
        assertNull(playlist.description)
        assertNull(playlist.coverPath)
        assertNull(playlist.placeId)
        assertEquals(TrackAvailability.EXTERNAL_ONLY, playlist.items.single().track.availability)
    }

    @Test
    fun `preserves both supported availability values`() {
        val dto = fixture().copy(items = listOf(
            PlaylistItemDto(1, track(TRACK_A, "Fixture A").copy(availability = "IN_APP")),
            PlaylistItemDto(2, track(TRACK_B, "Fixture B")),
        ))
        assertEquals(
            listOf(TrackAvailability.IN_APP, TrackAvailability.EXTERNAL_ONLY),
            dto.toDomain().items.map { it.track.availability },
        )
    }

    @Test
    fun `rejects malformed and noncanonical UUIDs`() {
        val dto = fixture()
        val invalid = listOf(
            dto.copy(id = "invalid"),
            dto.copy(id = "1-1-1-1-1"),
            dto.copy(ownerId = "invalid"),
            dto.copy(placeId = "invalid"),
            dto.copy(items = listOf(PlaylistItemDto(1, dto.items.single().track.copy(id = "invalid")))),
        )
        for (value in invalid) {
            assertThrows(IllegalArgumentException::class.java) { value.toDomain() }
        }
    }

    @Test
    fun `rejects invalid domain data and unknown enums`() {
        val dto = fixture()
        val item = dto.items.single()
        val invalid = listOf(
            dto.copy(title = ""),
            dto.copy(revision = 0),
            dto.copy(visibility = "FUTURE_VISIBILITY"),
            dto.copy(genreIds = listOf(1, 1)),
            dto.copy(tagIds = listOf(0)),
            dto.copy(items = listOf(item.copy(track = item.track.copy(durationMs = 0L)))),
            dto.copy(items = listOf(item.copy(track = item.track.copy(artist = "")))),
            dto.copy(items = listOf(item.copy(track = item.track.copy(availability = "FUTURE_AVAILABILITY")))),
        )
        for (value in invalid) {
            assertThrows(IllegalArgumentException::class.java) { value.toDomain() }
        }
    }

    @Test
    fun `maps empty playlist without inventing items`() {
        val dto = fixture().copy(items = emptyList())
        val playlist = dto.toDomain()
        assertEquals(UUID.fromString(dto.id), playlist.id)
        assertEquals(dto.title, playlist.title)
        assertEquals(dto.revision, playlist.revision)
        assertEquals(emptyList<Any>(), playlist.items)
    }

    @Test
    fun `empty playlist still rejects invalid metadata`() {
        val dto = fixture().copy(items = emptyList())
        for (value in listOf(dto.copy(title = ""), dto.copy(revision = 0), dto.copy(genreIds = listOf(1, 1)))) {
            assertThrows(IllegalArgumentException::class.java) { value.toDomain() }
        }
    }

    private fun fixture() = PlaylistDetailDto(
        id = PLAYLIST_ID,
        ownerId = OWNER_ID,
        title = "Fixture playlist",
        visibility = "PRIVATE",
        revision = 4,
        items = listOf(PlaylistItemDto(1, track(TRACK_A, "Fixture A"))),
        description = "Fixture description",
        placeId = PLACE_ID,
        coverPath = "editorial/fixture/cover.png",
        genreIds = listOf(1),
        tagIds = listOf(2),
        isEditorial = true,
    )

    private fun track(id: String, title: String) = TrackViewDto(
        id = id, title = title, artist = "Fixture artist", durationMs = 180000L,
        availability = "EXTERNAL_ONLY", provider = "apple_music",
    )

    private companion object {
        const val PLAYLIST_ID = "11111111-1111-4111-8111-111111111111"
        const val OWNER_ID = "22222222-2222-4222-8222-222222222222"
        const val TRACK_A = "33333333-3333-4333-8333-333333333333"
        const val TRACK_B = "44444444-4444-4444-8444-444444444444"
        const val PLACE_ID = "55555555-5555-4555-8555-555555555555"
    }
}
