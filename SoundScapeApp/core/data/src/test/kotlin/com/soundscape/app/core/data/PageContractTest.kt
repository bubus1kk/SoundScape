package com.soundscape.app.core.data

import com.soundscape.app.core.domain.model.DomainError
import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.GeoBounds
import com.soundscape.app.core.domain.model.GeoPoint
import com.soundscape.app.core.domain.model.MapPlaceKind
import com.soundscape.app.core.domain.model.MusicSearch
import com.soundscape.app.core.domain.model.PageRequest
import com.soundscape.app.core.domain.model.TrackAvailability
import com.soundscape.app.core.network.ConnectivityChecker
import com.soundscape.app.core.network.NetworkConfig
import com.soundscape.app.core.network.PlacesApi
import com.soundscape.app.core.network.TracksApi
import dagger.Lazy
import java.util.UUID
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlin.time.Duration.Companion.milliseconds

class PageContractTest {
    private val id = "123e4567-e89b-42d3-a456-426614174000"
    private val place = """{"id":"$id","kind":"CITY","title":"City","latitude":55.5,"longitude":37.5,
        "description":null,"playlistId":"$id","coverPath":null}"""
    private val track = """{"id":"$id","title":"Title","artist":"Artist","durationMs":12345,
        "availability":"EXTERNAL_ONLY","provider":null}"""
    private val bounds = (GeoBounds.create(
        (GeoPoint.create(50.0, 30.0) as DomainResult.Success).value,
        (GeoPoint.create(60.0, 40.0) as DomainResult.Success).value,
    ) as DomainResult.Success).value
    private fun retrofit(server: MockWebServer): Retrofit = Retrofit.Builder().baseUrl(server.url("/"))
        .addConverterFactory(Json.asConverterFactory("application/json".toMediaType())).build()
    private fun call() = RemoteCall(NetworkConfig("https://example.invalid/", "sb_publishable_test"),
        ConnectivityChecker { false }, NetworkErrorMapper(Json))
    private fun page(vararg items: String) =
        """{"items":[${items.joinToString()}],"limit":20,"offset":0,"hasMore":true}"""

    @Test fun `nonempty places map complete JSON to domain`() = runTest {
        MockWebServer().use { server ->
            val reader = RemoteVisiblePlacesReader(Lazy { retrofit(server).create(PlacesApi::class.java) }, call())
            server.enqueue(MockResponse().setBody(page(place)))
            val result = reader.getVisible(bounds, PageRequest()) as DomainResult.Success
            val item = result.value.items.single()
            assertEquals(UUID.fromString(id), item.id)
            assertEquals(MapPlaceKind.CITY, item.kind)
            assertEquals("City", item.title)
            assertEquals(55.5, item.coordinates.latitude, 0.0)
            assertEquals(37.5, item.coordinates.longitude, 0.0)
            assertEquals(UUID.fromString(id), item.playlistId)
            assertNull(item.description)
            assertNull(item.coverPath)
            assertTrue(result.value.hasMore)
        }
    }
    @Test fun `nonempty catalog maps complete JSON to domain`() = runTest {
        MockWebServer().use { server ->
            val searcher = RemoteTrackSearcher(Lazy { retrofit(server).create(TracksApi::class.java) }, call())
            server.enqueue(MockResponse().setBody(page(track)))
            val result = searcher.search(MusicSearch("test")) as DomainResult.Success
            val item = result.value.items.single()
            assertEquals(UUID.fromString(id), item.id)
            assertEquals("Title", item.title)
            assertEquals("Artist", item.artist)
            assertEquals(12345.milliseconds, item.duration)
            assertEquals(TrackAvailability.EXTERNAL_ONLY, item.availability)
            assertTrue(result.value.hasMore)
        }
    }
    @Test fun `any corrupt place rejects whole nonempty page`() = runTest {
        MockWebServer().use { server ->
            val reader = RemoteVisiblePlacesReader(Lazy { retrofit(server).create(PlacesApi::class.java) }, call())
            val corrupt = listOf(place.replace(id, "invalid"), place.replace("CITY", "UNKNOWN"),
                place.replace("55.5", "91.0"), place.replace("\"title\":\"City\",", ""))
            for (bad in corrupt) {
                server.enqueue(MockResponse().setBody(page(place, bad)))
                assertEquals(DomainResult.Failure(DomainError.Unexpected), reader.getVisible(bounds, PageRequest()))
            }
        }
    }
    @Test fun `any corrupt track rejects whole nonempty page`() = runTest {
        MockWebServer().use { server ->
            val searcher = RemoteTrackSearcher(Lazy { retrofit(server).create(TracksApi::class.java) }, call())
            val corrupt = listOf(track.replace(id, "invalid"), track.replace("EXTERNAL_ONLY", "UNKNOWN"),
                track.replace("12345", "0"), track.replace("\"artist\":\"Artist\",", ""))
            for (bad in corrupt) {
                server.enqueue(MockResponse().setBody(page(track, bad)))
                assertEquals(DomainResult.Failure(DomainError.Unexpected), searcher.search(MusicSearch("test")))
            }
        }
    }
}
