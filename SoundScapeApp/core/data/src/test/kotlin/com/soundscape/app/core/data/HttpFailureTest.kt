package com.soundscape.app.core.data

import com.soundscape.app.core.domain.model.DomainError
import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.MusicSearch
import com.soundscape.app.core.network.ConnectivityChecker
import com.soundscape.app.core.network.NetworkConfig
import com.soundscape.app.core.network.TracksApi
import dagger.Lazy
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlin.time.Duration.Companion.seconds

class HttpFailureTest {
    private fun reader(server: MockWebServer, client: OkHttpClient = OkHttpClient(), configured: Boolean = true):
        RemoteTrackSearcher {
        val api = Lazy {
            Retrofit.Builder().baseUrl(server.url("/")).client(client)
                .addConverterFactory(Json.asConverterFactory("application/json".toMediaType()))
                .build().create(TracksApi::class.java)
        }
        val config = NetworkConfig(if (configured) "https://example.invalid/" else "", "sb_publishable_test")
        return RemoteTrackSearcher(api, RemoteCall(config, ConnectivityChecker { false }, NetworkErrorMapper(Json)))
    }
    @Test fun `retry after seconds date invalid and past traverse HTTP`() = runTest {
        MockWebServer().use { server ->
            val reader = reader(server)
            val future = ZonedDateTime.now(java.time.ZoneOffset.UTC).plusHours(1)
                .format(DateTimeFormatter.RFC_1123_DATE_TIME)
            for ((header, expected) in listOf("30" to 30.seconds, "wrong" to null,
                "Sat, 01 Jan 2000 00:00:00 GMT" to 0.seconds)) {
                server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", header).setBody("{}"))
                assertEquals(DomainResult.Failure(DomainError.RateLimited(expected)),
                    reader.search(MusicSearch("test")))
            }
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", future).setBody("{}"))
            val result = reader.search(MusicSearch("test")) as DomainResult.Failure
            val duration = (result.error as DomainError.RateLimited).retryAfter!!
            assertTrue(duration.inWholeSeconds in 3500..3600)
        }
    }
    @Test fun `known and unknown conflicts traverse HTTP`() = runTest {
        MockWebServer().use { server ->
            val reader = reader(server)
            for ((message, expected) in listOf("OBJECT_ALREADY_EXISTS" to DomainError.AlreadyExists,
                "VERSION_CONFLICT" to DomainError.VersionConflict, "UNKNOWN" to DomainError.Unexpected)) {
                server.enqueue(MockResponse().setResponseCode(409)
                    .setBody("""{"code":"PT409","message":"$message"}"""))
                assertEquals(DomainResult.Failure(expected), reader.search(MusicSearch("test")))
            }
        }
    }
    @Test fun `real HTTP read timeout is Timeout`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val client = OkHttpClient.Builder().readTimeout(100, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(false).build()
            assertEquals(DomainResult.Failure(DomainError.Timeout),
                reader(server, client).search(MusicSearch("test")))
        }
    }
    @Test fun `coroutine cancellation cancels actual Retrofit call`() = runTest {
        MockWebServer().use { server ->
            val cancelled = AtomicBoolean()
            val client = OkHttpClient.Builder().eventListener(object : EventListener() {
                override fun canceled(call: Call) { cancelled.set(true) }
            }).build()
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val pending = async(Dispatchers.Default) { reader(server, client).search(MusicSearch("test")) }
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) })
            pending.cancelAndJoin()
            assertTrue(pending.isCancelled)
            assertTrue(cancelled.get())
        }
    }
    @Test fun `missing config never contacts server`() = runTest {
        MockWebServer().use { server ->
            assertEquals(DomainResult.Failure(DomainError.Unexpected),
                reader(server, configured = false).search(MusicSearch("test")))
            assertEquals(0, server.requestCount)
        }
    }
}
