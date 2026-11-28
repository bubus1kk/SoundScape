@file:Suppress("MagicNumber")

package com.soundscape.app.core.data

import com.soundscape.app.core.domain.model.DomainError
import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.PlaybackSource
import com.soundscape.app.core.network.ConnectivityChecker
import com.soundscape.app.core.network.NetworkConfig
import com.soundscape.app.core.network.ResolveTrackApi
import com.soundscape.app.core.network.ResolveTrackRequestDto
import com.soundscape.app.core.network.ResolvedSourceDto
import dagger.Lazy
import java.io.IOException
import java.io.InterruptedIOException
import java.net.URI
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
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
import org.junit.Test
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class RemotePlaybackSourceResolverTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val id = UUID.fromString(TRACK_ID)
    private val config = NetworkConfig("https://example.invalid/", "sb_publishable_test")

    @Test
    fun `posts exact contract and returns external source`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(jsonResponse(json.encodeToString(fixture())))
            val resolver = httpResolver(server)
            assertEquals(
                DomainResult.Success(PlaybackSource.External(id, URI(EXTERNAL_URL))),
                resolver.resolve(id),
            )
            val request = server.takeRequest(1, TimeUnit.SECONDS)
            assertNotNull(request)
            requireNotNull(request)
            assertEquals("POST", request.method)
            assertEquals("/functions/v1/v1-resolve-track", request.path)
            assertEquals(
                json.parseToJsonElement("""{"trackId":"$TRACK_ID"}"""),
                json.parseToJsonElement(request.body.readUtf8()),
            )
            assertEquals("OptionalUser", request.getHeader("X-Soundscape-Auth"))
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `maps Edge errors and retry after`() = runTest {
        MockWebServer().use { server ->
            val resolver = httpResolver(server)
            val cases = listOf(
                Triple(400, edgeError("VALIDATION_ERROR"), DomainError.Validation("request", "invalid_request")),
                Triple(401, edgeError("INVALID_JWT"), DomainError.Unauthenticated),
                Triple(401, edgeError("AUTH_REQUIRED"), DomainError.Unauthenticated),
                Triple(401, edgeError("INVALID_API_KEY"), DomainError.Unexpected),
                Triple(403, edgeError("FORBIDDEN"), DomainError.Forbidden),
                Triple(404, edgeError("NOT_FOUND"), DomainError.NotFound),
                Triple(404, edgeError("TRACK_UNAVAILABLE"), DomainError.NotFound),
                Triple(429, edgeError("RATE_LIMITED"), DomainError.RateLimited()),
                Triple(500, edgeError("INTERNAL_ERROR"), DomainError.Server),
                Triple(502, edgeError("PROVIDER_ERROR"), DomainError.Server),
                Triple(503, edgeError("PROVIDER_UNAVAILABLE"), DomainError.SourceUnavailable()),
            )
            for ((status, body, expected) in cases) {
                server.enqueue(jsonResponse(body, status))
                assertEquals("status=$status", DomainResult.Failure(expected), resolver.resolve(id))
            }
            server.enqueue(jsonResponse(edgeError("RATE_LIMITED"), 429).setHeader("Retry-After", "30"))
            assertEquals(
                DomainResult.Failure(DomainError.RateLimited(30.seconds)),
                resolver.resolve(id),
            )
        }
    }

    @Test
    fun `rejects malformed missing and null response bodies`() = runTest {
        MockWebServer().use { server ->
            val resolver = httpResolver(server)
            for (body in listOf("{", "{}", "null")) {
                server.enqueue(jsonResponse(body))
                assertEquals(DomainResult.Failure(DomainError.Unexpected), resolver.resolve(id))
            }
            server.enqueue(MockResponse().setResponseCode(204))
            assertEquals(DomainResult.Failure(DomainError.Unexpected), resolver.resolve(id))
        }
    }

    @Test
    fun `invalid response fields become domain failures`() = runTest {
        MockWebServer().use { server ->
            val resolver = httpResolver(server)
            val invalid = listOf(
                fixture().copy(trackId = "22222222-2222-4222-8222-222222222222"),
                fixture().copy(externalUrl = null),
                fixture().copy(externalUrl = "javascript:alert(1)"),
                fixture().copy(externalUrl = "https://user:password@music.example.test/track"),
                fixture().copy(
                    availability = "IN_APP",
                    streamUrl = "https://cdn.example.test/audio",
                    streamExpiresAt = "invalid-date",
                ),
            )
            for (dto in invalid) {
                server.enqueue(jsonResponse(json.encodeToString(dto)))
                assertEquals(DomainResult.Failure(DomainError.Unexpected), resolver.resolve(id))
            }
        }
    }

    @Test
    fun `returns in app source without invoking playback`() = runTest {
        val dto = fixture().copy(
            availability = "IN_APP",
            provider = "AUDIUS",
            streamUrl = "https://cdn.example.test/audio",
            externalUrl = null,
        )
        val resolver = resolver { Response.success(dto) }
        assertEquals(
            DomainResult.Success(PlaybackSource.InApp(id, URI("https://cdn.example.test/audio"))),
            resolver.resolve(id),
        )
    }

    @Test
    fun `offline and missing configuration do not initialize API`() = runTest {
        val api = Lazy<ResolveTrackApi> { error("API must not be initialized") }
        val offline = RemotePlaybackSourceResolver(api, remoteCall(offline = true))
        val unconfigured = RemotePlaybackSourceResolver(api, remoteCall(configuration = NetworkConfig("", "")))
        assertEquals(DomainResult.Failure(DomainError.Network), offline.resolve(id))
        assertEquals(DomainResult.Failure(DomainError.Unexpected), unconfigured.resolve(id))
    }

    @Test
    fun `maps transport failure and timeout`() = runTest {
        val network = resolver { throw IOException("fixture") }
        val timeout = resolver { throw InterruptedIOException("fixture") }
        assertEquals(DomainResult.Failure(DomainError.Network), network.resolve(id))
        assertEquals(DomainResult.Failure(DomainError.Timeout), timeout.resolve(id))
    }

    @Test
    fun `propagates cancellation unchanged`() = runTest {
        val expected = CancellationException("fixture cancellation")
        val resolver = resolver { throw expected }
        val actual = try {
            resolver.resolve(id)
            null
        } catch (error: CancellationException) {
            error
        }
        assertSame(expected, actual)
    }

    private fun httpResolver(server: MockWebServer): RemotePlaybackSourceResolver {
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build().create(ResolveTrackApi::class.java)
        return RemotePlaybackSourceResolver(Lazy { api }, remoteCall())
    }

    private fun resolver(
        response: suspend (ResolveTrackRequestDto) -> Response<ResolvedSourceDto>,
    ): RemotePlaybackSourceResolver {
        val api = object : ResolveTrackApi {
            override suspend fun resolve(
                request: ResolveTrackRequestDto,
            ): Response<ResolvedSourceDto> = response(request)
        }
        return RemotePlaybackSourceResolver(Lazy { api }, remoteCall())
    }

    private fun remoteCall(offline: Boolean = false, configuration: NetworkConfig = config) = RemoteCall(
        configuration, ConnectivityChecker { offline }, NetworkErrorMapper(json),
    )

    private fun jsonResponse(body: String, status: Int = 200): MockResponse = MockResponse()
        .setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)

    private fun edgeError(code: String): String =
        """{"error":{"code":"$code","message":"Fixture error","requestId":"fixture"}}"""

    private fun fixture() = ResolvedSourceDto(
        trackId = TRACK_ID, availability = "EXTERNAL_ONLY", provider = "APPLE_MUSIC",
        cacheAllowed = false, downloadAllowed = false, externalUrl = EXTERNAL_URL,
    )

    private companion object {
        const val TRACK_ID = "11111111-1111-4111-8111-111111111111"
        const val EXTERNAL_URL = "https://music.apple.com/us/song/fixture/1234567890"
    }
}
