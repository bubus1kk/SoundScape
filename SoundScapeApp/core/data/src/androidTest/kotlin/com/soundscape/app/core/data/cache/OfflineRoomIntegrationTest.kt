package com.soundscape.app.core.data.cache

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.soundscape.app.core.data.NetworkErrorMapper
import com.soundscape.app.core.data.RemoteCall
import com.soundscape.app.core.data.RemoteTrackSearcher
import com.soundscape.app.core.data.RemoteVisiblePlacesReader
import com.soundscape.app.core.database.CacheKeys
import com.soundscape.app.core.database.SoundScapeDatabase
import com.soundscape.app.core.domain.model.CachedPageState
import com.soundscape.app.core.domain.model.DomainError
import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.GeoBounds
import com.soundscape.app.core.domain.model.GeoPoint
import com.soundscape.app.core.domain.model.MusicSearch
import com.soundscape.app.core.domain.model.PageRequest
import com.soundscape.app.core.domain.model.Track
import com.soundscape.app.core.network.AccessTokenProvider
import com.soundscape.app.core.network.ConnectivityChecker
import com.soundscape.app.core.network.NetworkConfig
import com.soundscape.app.core.network.PlacesApi
import com.soundscape.app.core.network.TracksApi
import dagger.Lazy
import java.util.UUID
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** File-backed Room + actual Retrofit DTOs against localhost only, never a hosted backend. */
class OfflineRoomIntegrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "offline-${UUID.randomUUID()}.db"
    private lateinit var db: SoundScapeDatabase
    private lateinit var server: MockWebServer
    private lateinit var api: Retrofit
    private var offline = false
    private var configured = true
    private val id = UUID.randomUUID().toString()
    private val query = MusicSearch("ambient")
    private val connectivity = ConnectivityChecker { offline }
    private fun openDb() { db = Room.databaseBuilder(context, SoundScapeDatabase::class.java, name).build() }
    @Before fun setup() {
        openDb()
        server = MockWebServer().apply { start() }
        api = Retrofit.Builder().baseUrl(server.url("/"))
            .addConverterFactory(Json.asConverterFactory("application/json".toMediaType())).build()
    }
    @After fun close() { db.close(); context.deleteDatabase(name); server.shutdown() }
    private fun call() = RemoteCall(
        NetworkConfig(if (configured) "https://example.invalid/" else "", "sb_publishable_test"),
        connectivity, NetworkErrorMapper(Json))
    private fun tracks() = OfflineFirstTrackSearcher(
        RemoteTrackSearcher(Lazy { api.create(TracksApi::class.java) }, call()), RoomTrackStorage(db.cacheDao()),
        connectivity, AccessTokenProvider { null })
    private fun response(duration: Long = 1000) =
        """{"items":[{"id":"$id","title":"Track","artist":"Artist","durationMs":$duration,
        "availability":"IN_APP"}],"limit":20,"offset":0,"hasMore":false}"""

    @Test fun actualHttpResultPersistsAcrossFileReopenAndOfflineWithoutConfig() = runBlocking {
        server.enqueue(MockResponse().setBody(response()))
        val initial = tracks().search(query) as DomainResult.Success
        assertEquals(id, initial.value.items.single().id.toString())
        assertEquals("/functions/v1/v1-search-tracks", server.takeRequest().path)
        db.close(); openDb(); offline = true; configured = false
        assertEquals(initial, tracks().search(query))
        assertEquals(1, server.requestCount)
        val observed = tracks().observe(query).first { it.cached != null }
        assertNotNull(observed.cached!!.fetchedAt)
        assertFalse(observed.cached!!.page.hasMore)
    }
    @Test fun emptyNetworkPageAndAbsentQueryRemainDifferent() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"items":[],"limit":20,"offset":0,"hasMore":false}"""))
        assertTrue((tracks().search(query) as DomainResult.Success).value.items.isEmpty())
        offline = true
        assertTrue((tracks().search(query) as DomainResult.Success).value.items.isEmpty())
        assertEquals(DomainResult.Failure(DomainError.Network), tracks().search(MusicSearch("other")))
        assertEquals(1, server.requestCount)
    }
    @Test fun serverErrorKeepsRoomAndPublishesRefreshFailure() = runBlocking {
        val repository = tracks()
        server.enqueue(MockResponse().setBody(response()))
        val initial = repository.search(query)
        val failure = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(15000) { repository.observe(query).first { it.refreshError == DomainError.Server } }
        }
        server.enqueue(MockResponse().setResponseCode(503))
        assertEquals(DomainResult.Failure(DomainError.Server), repository.refresh(query))
        val state: CachedPageState<Track> = failure.await()
        assertEquals((initial as DomainResult.Success).value, state.cached!!.page)
        assertEquals(initial, repository.search(query))
        assertEquals(2, server.requestCount)
    }
    @Test fun malformedRemotePageNeverPartiallyReplacesCache() = runBlocking {
        val repository = tracks()
        server.enqueue(MockResponse().setBody(response()))
        val initial = repository.search(query)
        server.enqueue(MockResponse().setBody(response(0)))
        assertEquals(DomainResult.Failure(DomainError.Unexpected), repository.refresh(query))
        assertEquals(initial, repository.search(query))
    }
    @Test fun roomObservationUpdatesAfterSuccessfulTransaction() = runBlocking {
        val repository = tracks()
        assertNull(repository.observe(query).first().cached)
        val updated = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(15000) { repository.observe(query).first { it.cached != null } }
        }
        server.enqueue(MockResponse().setBody(response()))
        assertEquals(DomainResult.Success(Unit), repository.refresh(query))
        assertEquals(id, updated.await().cached!!.page.items.single().id.toString())
    }
    @Test fun mapBoundsAntimeridianAndMetadataRoundTripThroughHttpAndRoom() = runBlocking {
        val sw = (GeoPoint.create(-10.0, 170.0) as DomainResult.Success).value
        val ne = (GeoPoint.create(10.0, -170.0) as DomainResult.Success).value
        val bounds = (GeoBounds.create(sw, ne) as DomainResult.Success).value
        val repository = OfflineFirstVisiblePlaces(RemoteVisiblePlacesReader(
            Lazy { api.create(PlacesApi::class.java) }, call()), RoomMapStorage(db.cacheDao()),
            connectivity, AccessTokenProvider { null })
        server.enqueue(MockResponse().setBody("""{"items":[{"id":"$id","kind":"PIN","title":"Pin",
            "latitude":1.0,"longitude":179.0,"description":"Details","playlistId":"$id","coverPath":"cover"}],
            "limit":20,"offset":0,"hasMore":true}"""))
        val initial = repository.getVisible(bounds, PageRequest()) as DomainResult.Success
        assertEquals("Details", initial.value.items.single().description)
        assertEquals(UUID.fromString(id), initial.value.items.single().playlistId)
        assertEquals("cover", initial.value.items.single().coverPath)
        val request = server.takeRequest()
        assertEquals("/rest/v1/rpc/get_visible_places", request.path)
        assertTrue(request.body.readUtf8().contains("170.0"))
        offline = true
        assertEquals(initial, repository.getVisible(bounds, PageRequest()))
        val key = CacheKeys.map(-10.0, 170.0, 10.0, -170.0)
        assertNotNull(db.cacheDao().mapSnapshot("guest", key, 0, 20))
    }
    @Test fun mismatchedServerPageCannotBeStoredUnderRequestedOffset() = runBlocking {
        server.enqueue(MockResponse().setBody(response().replace("\"offset\":0", "\"offset\":20")))
        assertEquals(DomainResult.Failure(DomainError.Unexpected), tracks().search(query))
        val key = CacheKeys.search(query.query, query.onlyInApp, query.genreIds, query.moodIds)
        assertNull(db.cacheDao().trackSnapshot("guest", key, 0, 20))
    }
    @Test fun invalidSearchNeverReachesHttpEvenIfOtherPageIsCached() = runBlocking {
        server.enqueue(MockResponse().setBody(response()))
        tracks().search(query)
        assertTrue(tracks().search(MusicSearch("")) is DomainResult.Failure)
        assertTrue(tracks().search(query.copy(page = PageRequest(limit = 51))) is DomainResult.Failure)
        assertEquals(1, server.requestCount)
    }
}
