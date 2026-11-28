package com.soundscape.app.core.domain.model

import java.net.URI
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.*
import org.junit.Test

class ContentRulesTest {
    private val now = Instant.parse("2026-11-28T12:00:00Z")
    private val tracks = (1L..16).map { UUID(0, it) }

    @Test fun `visibility requires verified link only for unlisted guest access`() {
        for (visibility in Visibility.entries) {
            assertTrue(visibility.allowsRead(isOwner = true))
            assertEquals(visibility == Visibility.PUBLIC, visibility.allowsRead())
            assertEquals(visibility != Visibility.PRIVATE, visibility.allowsRead(hasAuthorizedLink = true))
        }
    }

    @Test fun `share expiry is inclusive and does not apply to perpetual links`() {
        val metadata = ShareMetadata(tracks[0], ShareTarget(ShareKind.PLAYLIST, tracks[1]), now)
        assertFalse(metadata.isExpired(now.minusNanos(1)))
        assertTrue(metadata.isExpired(now))
        assertFalse(metadata.copy(expiresAt = null).isExpired(now.plusSeconds(60)))
    }

    @Test fun `editorial boundaries require unique and verified recordings`() {
        val twelve = tracks.take(12)
        val fifteen = tracks.take(15)
        val sources = tracks.associateWith { EditorialSource.VERIFIED_IN_APP }
            .toMutableMap().apply {
                this[tracks[0]] = EditorialSource.VERIFIED_EXTERNAL_ONLY
                this[tracks[1]] = EditorialSource.VERIFIED_EXTERNAL_ONLY
            }
        assertTrue(checkEditorialComposition(twelve, sources).isEmpty())
        sources[tracks[2]] = EditorialSource.VERIFIED_EXTERNAL_ONLY
        assertTrue(checkEditorialComposition(fifteen, sources).isEmpty())
        sources[tracks[3]] = EditorialSource.VERIFIED_EXTERNAL_ONLY
        assertTrue(EditorialFinding(EditorialIssue.TOO_MANY_EXTERNAL_ONLY) in checkEditorialComposition(fifteen, sources))
        val broken = checkEditorialComposition(twelve.drop(4) + listOf(tracks[5], tracks[15]), sources - tracks[15])
        assertTrue(EditorialFinding(EditorialIssue.NOT_ENOUGH_EXTERNAL_ONLY) in broken)
        assertTrue(EditorialFinding(EditorialIssue.DUPLICATE_TRACK) in broken)
        assertTrue(EditorialFinding(EditorialIssue.TRACK_COUNT_12_TO_15) in broken)
        assertTrue(EditorialFinding(EditorialIssue.UNKNOWN_TRACK, tracks[15]) in broken)
        sources[tracks[9]] = EditorialSource.RECHECK_REQUIRED
        sources[tracks[10]] = EditorialSource.UNVERIFIED
        val stale = checkEditorialComposition(twelve, sources)
        assertTrue(EditorialFinding(EditorialIssue.IN_APP_RECHECK_REQUIRED, tracks[9]) in stale)
        assertTrue(EditorialFinding(EditorialIssue.NO_VERIFIED_SOURCE, tracks[10]) in stale)
    }

    @Test fun `route durations count only linked playlist music and separate in app time`() {
        val place = JourneyStopContext(JourneyStop(tracks[0], JourneyStopKind.PLACE), true)
        val pin = JourneyStopContext(JourneyStop(tracks[0], JourneyStopKind.PIN), true)
        val inApp = Track(tracks[2], "One", "Artist", 9.seconds, TrackAvailability.IN_APP)
        val external = inApp.copy(id = tracks[3], duration = 4.seconds, availability = TrackAvailability.EXTERNAL_ONLY)
        val runs = listOf(place, pin)
        assertEquals(JourneyRoute(13.seconds, 9.seconds, JourneyRouteStatus.READY, true),
            journeyRoutePreview(runs, listOf(inApp, external)))
        assertFalse(journeyRoutePreview(listOf(place, pin.copy(publiclyAccessible = false))).canGenerate)
        assertEquals(JourneyRouteStatus.NEEDS_EDIT, journeyRoutePreview(listOf(place, place)).status)
        val incomplete = MusicJourney(tracks[0], tracks[1], "Trip", Visibility.PRIVATE,
            listOf(place.stop), 2, route = JourneyRoute())
        assertEquals(DomainResult.Success(Unit), incomplete.validate())
        assertEquals(Duration.ZERO, incomplete.route?.totalDuration)
        assertEquals(13.seconds,
            JourneyRoute.fromMilliseconds(13000, 9000, JourneyRouteStatus.READY, true).totalDuration)
        assertThrows(IllegalArgumentException::class.java) {
            JourneyRoute.fromMilliseconds(9000, 13000, JourneyRouteStatus.READY, true)
        }
    }

    @Test fun `cache and download grants are independent from playback and each other`() {
        val source = PlaybackSource.InApp(tracks[0], URI("https://example.invalid/track"))
        val cached = CachedAudio(tracks[0], 1000, true)
        assertFalse(cached.canPlayOffline(source))
        assertTrue(cached.canPlayOffline(source.copy(cacheAllowed = true)))
        assertFalse(cached.copy(complete = false).canPlayOffline(source.copy(cacheAllowed = true)))
        assertFalse(cached.canPlayOffline(source.copy(cacheAllowed = true, trackId = tracks[1])))
        assertEquals(DomainResult.Failure(DomainError.Forbidden), AuthorizedDownload.from(source.copy(cacheAllowed = true), now))
        assertTrue(AuthorizedDownload.from(source.copy(downloadAllowed = true), now) is DomainResult.Success)
        assertEquals(DomainResult.Failure(DomainError.PlaybackSourceExpired(source.trackId)),
            AuthorizedDownload.from(source.copy(downloadAllowed = true, expiresAt = now), now))
    }
}
