package com.soundscape.app.core.domain.model

import java.time.LocalDate
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.*
import org.junit.Test

class DomainModelsTest {
    private val id = UUID(0, 1)
    private val owner = UUID(0, 2)
    private fun point(lat: Double = 0.0, lon: Double = 0.0) =
        (GeoPoint.create(lat, lon) as DomainResult.Success).value
    private fun track(availability: TrackAvailability = TrackAvailability.IN_APP) =
        Track(id, "Track", "Artist", 30.seconds, availability)
    private fun photos(count: Int) = List(count) { MediaReference("photo-$it") }
    private fun pin() = MusicPin(id, owner, "Pin", point(), listOf(id), emptyList(), Visibility.PUBLIC, 1)
    private fun diary() = DiaryEntry(id, owner, "Diary", LocalDate.of(2026, 11, 10), listOf(id), emptyList(), 1, locationName = "City")
    private fun playlist() = Playlist(id, owner, "Playlist", Visibility.PRIVATE, listOf(PlaylistItem(track())), 1)
    private fun journey(count: Int) = MusicJourney(id, owner, "Journey", Visibility.PRIVATE,
        List(count) { JourneyStop(UUID(0, it.toLong()), JourneyStopKind.PLACE) }, 1)
    private fun success(result: DomainResult<*>) = assertTrue(result.toString(), result is DomainResult.Success)
    private fun invalid(result: DomainResult<*>, field: String) {
        assertTrue(result.toString(), result is DomainResult.Failure)
        val error = (result as DomainResult.Failure).error
        assertTrue(error is DomainError.Validation)
        assertEquals(field, (error as DomainError.Validation).field)
    }

    @Test fun `coordinates accept endpoints and reject invalid input without exceptions`() {
        success(GeoPoint.create(-90.0, -180.0))
        success(GeoPoint.create(90.0, 180.0))
        for (latitude in listOf(-90.1, 90.1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            invalid(GeoPoint.create(latitude, 0.0), "latitude")
        }
        for (longitude in listOf(-180.1, 180.1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            invalid(GeoPoint.create(0.0, longitude), "longitude")
        }
    }

    @Test fun `bounds allow antimeridian and reject reversed latitudes`() {
        val crossing = GeoBounds.create(point(-10.0, 170.0), point(10.0, -170.0))
        assertTrue((crossing as DomainResult.Success).value.crossesAntimeridian)
        val ordinary = GeoBounds.create(point(-90.0, -180.0), point(90.0, 180.0))
        assertFalse((ordinary as DomainResult.Success).value.crossesAntimeridian)
        success(GeoBounds.create(point(), point()))
        invalid(GeoBounds.create(point(10.0), point(-10.0)), "bounds")
    }

    @Test fun `coordinates compare by value including signed zero`() {
        assertEquals(point(), point(-0.0, -0.0))
        assertEquals(point().hashCode(), point(-0.0, -0.0).hashCode())
    }

    @Test fun `track requires finite positive duration and metadata`() {
        success(track().validate())
        for (duration in listOf(Duration.ZERO, (-1).seconds, Duration.INFINITE)) {
            invalid(track().copy(duration = duration).validate(), "duration")
        }
        invalid(track().copy(title = " ").validate(), "title")
        invalid(track().copy(artist = "").validate(), "artist")
        assertEquals(2, TrackAvailability.entries.size)
        assertEquals(3, Visibility.entries.size)
    }

    @Test fun `playlist preserves repeats and calculates music time only`() {
        val internal = PlaylistItem(track())
        val external = PlaylistItem(track(TrackAvailability.EXTERNAL_ONLY).copy(duration = 50.seconds))
        val items = listOf(external, internal, internal)
        val playlist = playlist().copy(items = items)
        success(playlist.validate())
        assertEquals(items, playlist.items)
        assertEquals(110.seconds, playlist.totalDuration())
        assertEquals(60.seconds, playlist.inAppDuration())
        assertEquals(Duration.ZERO, playlist.copy(items = listOf(external)).inAppDuration())
    }

    @Test fun `playlist validates count title revision and tracks`() {
        for (count in listOf(1, 100)) success(playlist().copy(items = List(count) { PlaylistItem(track()) }).validate())
        for (count in listOf(0, 101)) invalid(playlist().copy(items = List(count) { PlaylistItem(track()) }).validate(), "items")
        success(playlist().copy(title = "a".repeat(150), description = "a".repeat(2000)).validate())
        invalid(playlist().copy(title = "a".repeat(151)).validate(), "title")
        invalid(playlist().copy(title = " ").validate(), "title")
        invalid(playlist().copy(revision = 0).validate(), "revision")
        invalid(playlist().copy(description = "a".repeat(2001)).validate(), "description")
        invalid(playlist().copy(items = listOf(PlaylistItem(track().copy(duration = Duration.ZERO)))).validate(), "duration")
    }

    @Test fun `pin accepts five photos and rejects six`() {
        success(pin().copy(photos = photos(5)).validate())
        invalid(pin().copy(photos = photos(6)).validate(), "photos")
        invalid(pin().copy(photos = listOf(MediaReference(" "))).validate(), "photos")
        invalid(pin().copy(photos = listOf(MediaReference("same"), MediaReference("same"))).validate(), "photos")
    }

    @Test fun `pin validates catalog reference counts without looking up ids`() {
        success(pin().validate())
        success(pin().copy(trackIds = List(100) { UUID(0, it.toLong()) }).validate())
        invalid(pin().copy(trackIds = emptyList()).validate(), "trackIds")
        invalid(pin().copy(trackIds = List(101) { UUID(0, it.toLong()) }).validate(), "trackIds")
        invalid(pin().copy(trackIds = listOf(id, id)).validate(), "trackIds")
        invalid(pin().copy(title = "").validate(), "title")
        invalid(pin().copy(revision = -1).validate(), "revision")
    }

    @Test fun `diary defaults to private and accepts null body`() {
        assertEquals(Visibility.PRIVATE, diary().visibility)
        assertNull(diary().body)
        success(diary().validate())
        success(diary().copy(visibility = Visibility.UNLISTED).validate())
    }

    @Test fun `diary requires one of three location representations`() {
        invalid(diary().copy(locationName = " ").validate(), "locationName")
        success(diary().copy(locationName = null, coordinates = point()).validate())
        success(diary().copy(locationName = null, musicPinId = id).validate())
    }

    @Test fun `diary accepts ten photos and rejects eleven and invalid tracks`() {
        success(diary().copy(photos = photos(10)).validate())
        invalid(diary().copy(photos = photos(11)).validate(), "photos")
        invalid(diary().copy(trackIds = emptyList()).validate(), "trackIds")
        invalid(diary().copy(trackIds = listOf(id, id)).validate(), "trackIds")
        success(diary().copy(trackIds = List(100) { UUID(0, it.toLong()) }).validate())
        invalid(diary().copy(trackIds = List(101) { UUID(0, it.toLong()) }).validate(), "trackIds")
        invalid(diary().copy(title = "").validate(), "title")
        invalid(diary().copy(revision = 0).validate(), "revision")
    }

    @Test fun `journey validates stop boundaries without requiring playlist`() {
        invalid(journey(1).validate(), "stops")
        success(journey(2).validate())
        success(journey(50).validate())
        invalid(journey(51).validate(), "stops")
        assertNull(journey(2).playlistId)
        val stops = listOf(JourneyStop(owner, JourneyStopKind.PIN), JourneyStop(id, JourneyStopKind.PLACE))
        assertEquals(stops, journey(2).copy(stops = stops).stops)
        invalid(journey(2).copy(title = " ").validate(), "title")
        invalid(journey(2).copy(revision = 0).validate(), "revision")
    }

    @Test fun `map projection and full place remain independent of pin`() {
        val place = MusicPlace(id, "City", point())
        val mapItem = MapPlace(id, MapPlaceKind.CITY, place.title, place.coordinates)
        assertEquals(place.id, mapItem.id)
        assertEquals(MapPlaceKind.PIN, mapItem.copy(kind = MapPlaceKind.PIN).kind)
        assertEquals("Listener", UserProfile(owner, "Listener").displayName)
    }

    @Test fun `result represents successful empty data and typed failure`() {
        val empty: DomainResult<List<Track>> = DomainResult.Success(emptyList())
        success(empty)
        val failure: DomainResult<Track> = DomainResult.Failure(DomainError.VersionConflict)
        assertEquals(DomainError.VersionConflict, (failure as DomainResult.Failure).error)
        assertNull(DomainError.RateLimited().retryAfter)
    }
    @Test fun `title length includes surrounding spaces without modifying input`() {
        val title = "a" + " ".repeat(150)
        val value = playlist().copy(title = title)
        invalid(value.validate(), "title")
        assertEquals(title, value.title)
    }

    @Test fun `blank location name is rejected even with coordinates or pin`() {
        invalid(diary().copy(locationName = " ", coordinates = point()).validate(), "locationName")
        invalid(diary().copy(locationName = " ", musicPinId = id).validate(), "locationName")
    }

    @Test fun `playlist dictionary ids must be positive unique and limited`() {
        success(playlist().validate())
        success(playlist().copy(genreIds = (1..30).toList(), tagIds = (1..30).toList()).validate())
        for (ids in listOf(listOf(0), listOf(-1), listOf(1, 1), (1..31).toList())) {
            invalid(playlist().copy(genreIds = ids).validate(), "genreIds")
            invalid(playlist().copy(tagIds = ids).validate(), "tagIds")
        }
    }

    @Test fun `server metadata and opaque covers are preserved`() {
        val value = playlist().copy(coverPath = "opaque", placeId = id, isEditorial = true)
        success(value.validate())
        assertEquals("opaque", value.coverPath)
        assertEquals(id, value.placeId)
        assertTrue(value.isEditorial)
        assertFalse(playlist().isEditorial)
        assertEquals("opaque", MusicPlace(id, "City", point(), coverPath = "opaque").coverPath)
        assertEquals("opaque", MapPlace(id, MapPlaceKind.CITY, "City", point(), coverPath = "opaque").coverPath)
    }

    @Test fun `bounds equality and hash code use validated corner values`() {
        val first = (GeoBounds.create(point(-1.0, 170.0), point(1.0, -170.0)) as DomainResult.Success).value
        val same = (GeoBounds.create(point(-1.0, 170.0), point(1.0, -170.0)) as DomainResult.Success).value
        val other = (GeoBounds.create(point(), point()) as DomainResult.Success).value
        assertEquals(first, same)
        assertEquals(first.hashCode(), same.hashCode())
        assertNotEquals(first, other)
    }
    @Test fun `duplicate identity differs from revision conflict`() {
        val duplicate: DomainResult<Playlist> = DomainResult.Failure(DomainError.AlreadyExists)
        val stale: DomainResult<Playlist> = DomainResult.Failure(DomainError.VersionConflict)
        assertNotEquals(duplicate, stale)
    }
}
