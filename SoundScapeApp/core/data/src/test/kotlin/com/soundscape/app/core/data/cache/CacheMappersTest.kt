package com.soundscape.app.core.data.cache

import com.soundscape.app.core.database.CacheKeys
import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.GeoPoint
import com.soundscape.app.core.domain.model.MapPlace
import com.soundscape.app.core.domain.model.MapPlaceKind
import com.soundscape.app.core.domain.model.MusicSearch
import com.soundscape.app.core.domain.model.PageRequest
import com.soundscape.app.core.domain.model.Track
import com.soundscape.app.core.domain.model.TrackAvailability
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class CacheMappersTest {
    @Test fun mapSummaryRoundTripsEverySupportedFieldAndUuid() {
        val point = (GeoPoint.create(1.5, -179.5) as DomainResult.Success).value
        for (kind in MapPlaceKind.entries) {
            val place = MapPlace(UUID.randomUUID(), kind, "Place", point, "description", UUID.randomUUID(), "cover")
            assertEquals(place, place.toEntity("guest").toDomain())
            assertEquals("guest", place.toEntity("guest").scope)
        }
    }
    @Test fun trackDurationAndAvailabilityRoundTripExactly() {
        for (availability in TrackAvailability.entries) {
            val track = Track(UUID.randomUUID(), "Track", "Artist", 12345.milliseconds, availability)
            assertEquals(track, track.toEntity("guest").toDomain())
        }
    }
    @Test fun fractionalDurationIsRejectedRatherThanTruncated() {
        val track = Track(UUID.randomUUID(), "Track", "Artist", 1500001.nanoseconds, TrackAvailability.IN_APP)
        assertThrows(IllegalArgumentException::class.java) { track.toEntity("guest") }
    }
    @Test fun corruptMetadataIsRejectedBeforeStorage() {
        val track = Track(UUID.randomUUID(), "", "Artist", 1000.milliseconds, TrackAvailability.IN_APP)
        assertThrows(IllegalArgumentException::class.java) { track.toEntity("guest") }
        val point = (GeoPoint.create(0.0, 0.0) as DomainResult.Success).value
        assertThrows(IllegalArgumentException::class.java) {
            MapPlace(UUID.randomUUID(), MapPlaceKind.PIN, "", point).toEntity("guest")
        }
    }
    @Test fun validationRejectsInvalidInputBeforeCacheLookup() {
        assertNotNull(validateMap(PageRequest(limit = 101)))
        assertNotNull(validateSearch(MusicSearch("")))
        assertNotNull(validateSearch(MusicSearch("valid", page = PageRequest(offset = 1001))))
        assertNotNull(validateSearch(MusicSearch("valid", genreIds = listOf(1, 1))))
        assertNotNull(validateSearch(MusicSearch("valid", moodIds = listOf(32768))))
        assertNull(validateSearch(MusicSearch(" valid ", genreIds = listOf(1), moodIds = listOf(2))))
    }
    @Test fun exactTextFiltersBoundsAndAntimeridianHaveDistinctKeys() {
        assertNotEquals(CacheKeys.search("rock", false, listOf(1), listOf(2)),
            CacheKeys.search(" rock", false, listOf(1), listOf(2)))
        assertNotEquals(CacheKeys.search("rock", false, listOf(1), listOf(2)),
            CacheKeys.search("rock", true, listOf(2), listOf(1)))
        assertNotEquals(CacheKeys.map(-10.0, 170.0, 10.0, -170.0), CacheKeys.map(-10.0, -170.0, 10.0, 170.0))
        assertNotEquals(CacheKeys.map(0.0, 0.0, 10.0, 10.0), CacheKeys.map(1.0, 0.0, 10.0, 10.0))
    }
}
