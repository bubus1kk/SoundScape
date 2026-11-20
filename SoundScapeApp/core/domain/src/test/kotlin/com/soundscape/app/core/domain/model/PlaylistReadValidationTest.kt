package com.soundscape.app.core.domain.model

import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistReadValidationTest {
    @Test
    fun `empty playlist is valid for reading but strict validation is unchanged`() {
        val playlist = fixture()

        assertEquals(DomainResult.Success(Unit), playlist.validateForRead())
        assertTrue(playlist.validate() is DomainResult.Failure)
        assertEquals(Duration.ZERO, playlist.totalDuration())
        assertEquals(Duration.ZERO, playlist.inAppDuration())
    }

    @Test
    fun `both validation modes accept one and one hundred items`() {
        val item = PlaylistItem(
            Track(UUID(0, 3), "Fixture track", "Fixture artist", 30.seconds, TrackAvailability.EXTERNAL_ONLY),
        )
        for (count in listOf(1, 100)) {
            val playlist = fixture().copy(items = List(count) { item })
            assertEquals(DomainResult.Success(Unit), playlist.validate())
            assertEquals(DomainResult.Success(Unit), playlist.validateForRead())
        }
    }

    @Test
    fun `read validation still rejects more than one hundred items`() {
        val item = PlaylistItem(
            Track(UUID(0, 3), "Fixture track", "Fixture artist", 30.seconds, TrackAvailability.EXTERNAL_ONLY),
        )
        val playlist = fixture().copy(items = List(101) { item })
        assertTrue(playlist.validate() is DomainResult.Failure)
        assertTrue(playlist.validateForRead() is DomainResult.Failure)
    }

    @Test
    fun `empty items do not bypass metadata validation`() {
        val playlist = fixture()
        val invalid = listOf(
            playlist.copy(title = " "),
            playlist.copy(revision = 0),
            playlist.copy(description = "a".repeat(2001)),
            playlist.copy(genreIds = listOf(1, 1)),
            playlist.copy(tagIds = listOf(0)),
        )
        for (value in invalid) {
            assertTrue(value.validateForRead() is DomainResult.Failure)
        }
    }

    private fun fixture() = Playlist(
        id = UUID(0, 1),
        ownerId = UUID(0, 2),
        title = "Fixture playlist",
        visibility = Visibility.PRIVATE,
        items = emptyList(),
        revision = 1,
    )
}
