package com.soundscape.app.core.data

import com.soundscape.app.core.domain.model.DomainError
import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.MusicSearch
import com.soundscape.app.core.network.ConnectivityChecker
import com.soundscape.app.core.network.NetworkConfig
import com.soundscape.app.core.network.TracksApi
import dagger.Lazy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class RemoteHttpTest {
    @Test fun `search maps actual HTTP responses and safe errors`() = runTest {
        MockWebServer().use { server ->
            val json = Json { ignoreUnknownKeys = true }
            val api = Retrofit.Builder().baseUrl(server.url("/"))
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build().create(TracksApi::class.java)
            val call = RemoteCall(NetworkConfig("https://example.invalid/", "sb_publishable_test"),
                ConnectivityChecker { false }, NetworkErrorMapper(json))
            val searcher = RemoteTrackSearcher(Lazy { api }, call)
            server.enqueue(MockResponse().setBody("""{"items":[],"limit":20,"offset":0,"hasMore":false}"""))
            val result = searcher.search(MusicSearch("test")) as DomainResult.Success
            assertEquals(emptyList<Any>(), result.value.items)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/functions/v1/v1-search-tracks", request.path)
            val cases = listOf(
                Triple(401, """{"error":{"code":"INVALID_API_KEY"}}""", DomainError.Unexpected),
                Triple(401, "{}", DomainError.Unexpected),
                Triple(401, """{"error":{"code":"INVALID_JWT"}}""", DomainError.Unauthenticated),
                Triple(403, "{}", DomainError.Forbidden),
                Triple(404, "{}", DomainError.NotFound),
                Triple(422, """{"code":"PT422","message":"INVALID_BOUNDS"}""",
                    DomainError.Validation("request", "invalid_request")),
                Triple(429, "{}", DomainError.RateLimited()),
                Triple(500, "not JSON", DomainError.Server),
                Triple(502, "{}", DomainError.Server),
                Triple(503, "{}", DomainError.Server),
                Triple(200, "{", DomainError.Unexpected),
                Triple(200, "{}", DomainError.Unexpected),
            )
            for ((status, body, expected) in cases) {
                server.enqueue(MockResponse().setResponseCode(status).setBody(body))
                assertEquals("status=$status", DomainResult.Failure(expected), searcher.search(MusicSearch("test")))
            }
        }
    }
}
