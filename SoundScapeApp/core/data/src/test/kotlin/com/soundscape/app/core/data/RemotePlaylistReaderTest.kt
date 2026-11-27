@file:Suppress("MagicNumber")

package com.soundscape.app.core.data

import com.soundscape.app.core.domain.model.DomainError
import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.TrackAvailability
import com.soundscape.app.core.network.ConnectivityChecker
import com.soundscape.app.core.network.GetPlaylistRequestDto
import com.soundscape.app.core.network.NetworkConfig
import com.soundscape.app.core.network.PlaylistDetailDto
import com.soundscape.app.core.network.PlaylistItemDto
import com.soundscape.app.core.network.PlaylistsApi
import com.soundscape.app.core.network.TrackViewDto
import dagger.Lazy
import java.io.IOException
import java.io.InterruptedIOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class RemotePlaylistReaderTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val id = UUID.fromString(PLAYLIST_ID)
    private val config = NetworkConfig("https://example.invalid/", "sb_publishable_test")

    @Test
    fun `posts exact request and reads thirteen distinct tracks in position order`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(jsonResponse(json.encodeToString(fixture())))
            val reader = RemotePlaylistReader(Lazy { httpApi(server) }, remoteCall())
            val result = reader.get(id)
            assertTrue(result is DomainResult.Success)
            val playlist = (result as DomainResult.Success).value
            assertEquals(id, playlist.id)
            assertEquals(13, playlist.items.size)
            assertEquals((1..13).map { UUID(0, it.toLong()) }, playlist.items.map { it.track.id })
            assertEquals((1..13).map { "Fixture $it" }, playlist.items.map { it.track.title })
            assertEquals((1..13).map { "Fixture artist $it" }, playlist.items.map { it.track.artist })
            assertEquals(
                (1..13).map { (180000L + it * 1000L).milliseconds },
                playlist.items.map { it.track.duration },
            )
            assertEquals(13, playlist.items.map { it.track.id }.distinct().size)
            assertTrue(playlist.items.all { it.track.availability == TrackAvailability.EXTERNAL_ONLY })
            val request = server.takeRequest(1, TimeUnit.SECONDS)
            assertNotNull(request)
            requireNotNull(request)
            assertEquals("POST", request.method)
            assertEquals("/rest/v1/rpc/get_playlist", request.path)
            assertEquals(
                json.parseToJsonElement("""{"playlistId":"$PLAYLIST_ID"}"""),
                json.parseToJsonElement(request.body.readUtf8()),
            )
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `preserves repeated track with identical metadata and sorts positions`() = runTest {
        MockWebServer().use { server ->
            val repeated = fixtureTrack(1)
            val other = fixtureTrack(2)
            val dto = fixture().copy(items = listOf(
                PlaylistItemDto(position = 3, track = repeated),
                PlaylistItemDto(position = 1, track = repeated),
                PlaylistItemDto(position = 2, track = other),
            ))
            server.enqueue(jsonResponse(json.encodeToString(dto)))
            val reader = RemotePlaylistReader(Lazy { httpApi(server) }, remoteCall())
            val result = reader.get(id)
            assertTrue(result is DomainResult.Success)
            val tracks = (result as DomainResult.Success).value.items.map { it.track }
            assertEquals(3, tracks.size)
            assertEquals(
                listOf(repeated.id, other.id, repeated.id).map(UUID::fromString),
                tracks.map { it.id },
            )
            assertEquals(listOf(repeated.title, other.title, repeated.title), tracks.map { it.title })
            assertEquals(listOf(repeated.artist, other.artist, repeated.artist), tracks.map { it.artist })
            assertEquals(
                listOf(repeated.durationMs, other.durationMs, repeated.durationMs).map { it.milliseconds },
                tracks.map { it.duration },
            )
            assertEquals(tracks[0], tracks[2])
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `successful empty playlist returns success`() = runTest {
        MockWebServer().use { server ->
            val dto = fixture().copy(items = emptyList())
            server.enqueue(jsonResponse(json.encodeToString(dto)))
            val reader = RemotePlaylistReader(Lazy { httpApi(server) }, remoteCall())
            val result = reader.get(id)
            assertTrue(result is DomainResult.Success)
            val playlist = (result as DomainResult.Success).value
            assertEquals(id, playlist.id)
            assertEquals(dto.title, playlist.title)
            assertTrue(playlist.items.isEmpty())
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `maps RPC HTTP errors without exposing server response`() = runTest {
        MockWebServer().use { server ->
            val reader = RemotePlaylistReader(Lazy { httpApi(server) }, remoteCall())
            val cases = listOf(
                Triple(401, """{"code":"PT401","message":"AUTH_REQUIRED"}""", DomainError.Unauthenticated),
                Triple(403, "{}", DomainError.Forbidden),
                Triple(404, """{"code":"PT404","message":"PLAYLIST_NOT_FOUND"}""", DomainError.NotFound),
                Triple(429, "{}", DomainError.RateLimited()),
                Triple(503, "not JSON", DomainError.Server),
            )
            for ((status, body, expected) in cases) {
                server.enqueue(jsonResponse(body, status))
                assertEquals("status=$status", DomainResult.Failure(expected), reader.get(id))
            }
        }
    }

    @Test
    fun `rejects malformed missing and null response data`() = runTest {
        MockWebServer().use { server ->
            val reader = RemotePlaylistReader(Lazy { httpApi(server) }, remoteCall())
            for (body in listOf("{", "{}", "null")) {
                server.enqueue(jsonResponse(body))
                assertEquals(DomainResult.Failure(DomainError.Unexpected), reader.get(id))
            }
            server.enqueue(MockResponse().setResponseCode(204))
            assertEquals(DomainResult.Failure(DomainError.Unexpected), reader.get(id))
        }
    }

    @Test
    fun `rejects response for another playlist`() = runTest {
        val reader = reader {
            Response.success(fixture().copy(id = "66666666-6666-4666-8666-666666666666"))
        }
        assertEquals(DomainResult.Failure(DomainError.Unexpected), reader.get(id))
    }

    @Test
    fun `mapper failure becomes unexpected domain error`() = runTest {
        val dto = fixture()
        val invalid = dto.copy(items = listOf(dto.items.first().copy(position = 0)))
        val reader = reader { Response.success(invalid) }
        assertEquals(DomainResult.Failure(DomainError.Unexpected), reader.get(id))
    }

    @Test
    fun `offline does not initialize API`() = runTest {
        val reader = RemotePlaylistReader(
            Lazy<PlaylistsApi> { error("API must not be initialized") },
            remoteCall(offline = true),
        )
        assertEquals(DomainResult.Failure(DomainError.Network), reader.get(id))
    }

    @Test
    fun `missing configuration does not initialize API`() = runTest {
        val reader = RemotePlaylistReader(
            Lazy<PlaylistsApi> { error("API must not be initialized") },
            remoteCall(configuration = NetworkConfig("", "")),
        )
        assertEquals(DomainResult.Failure(DomainError.Unexpected), reader.get(id))
    }

    @Test
    fun `maps transport failure and timeout`() = runTest {
        val networkReader = reader { throw IOException("fixture") }
        val timeoutReader = reader { throw InterruptedIOException("fixture") }
        assertEquals(DomainResult.Failure(DomainError.Network), networkReader.get(id))
        assertEquals(DomainResult.Failure(DomainError.Timeout), timeoutReader.get(id))
    }

    @Test
    fun `propagates cancellation unchanged`() = runTest {
        val expected = CancellationException("fixture cancellation")
        val reader = reader { throw expected }
        val actual = try {
            reader.get(id)
            null
        } catch (error: CancellationException) {
            error
        }
        assertSame(expected, actual)
    }

    private fun httpApi(server: MockWebServer): PlaylistsApi = Retrofit.Builder()
        .baseUrl(server.url("/"))
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build().create(PlaylistsApi::class.java)

    private fun remoteCall(offline: Boolean = false, configuration: NetworkConfig = config) = RemoteCall(
        configuration, ConnectivityChecker { offline }, NetworkErrorMapper(json),
    )

    private fun reader(
        response: suspend (GetPlaylistRequestDto) -> Response<PlaylistDetailDto>,
    ): RemotePlaylistReader {
        val api = object : PlaylistsApi {
            override suspend fun get(request: GetPlaylistRequestDto): Response<PlaylistDetailDto> = response(request)
        }
        return RemotePlaylistReader(Lazy { api }, remoteCall())
    }

    private fun jsonResponse(body: String, status: Int = 200): MockResponse = MockResponse()
        .setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)

    private fun fixture() = PlaylistDetailDto(
        id = PLAYLIST_ID, ownerId = OWNER_ID, title = "Fixture playlist", visibility = "PRIVATE", revision = 1,
        items = (1..13).reversed().map { PlaylistItemDto(position = it, track = fixtureTrack(it)) },
        isEditorial = true,
    )

    private fun fixtureTrack(number: Int) = TrackViewDto(
        id = UUID(0, number.toLong()).toString(),
        title = "Fixture $number",
        artist = "Fixture artist $number",
        durationMs = 180000L + number * 1000L,
        availability = "EXTERNAL_ONLY",
        provider = if (number % 2 == 0) "apple_music" else "bandcamp",
    )

    private companion object {
        const val PLAYLIST_ID = "11111111-1111-4111-8111-111111111111"
        const val OWNER_ID = "22222222-2222-4222-8222-222222222222"
    }
}
