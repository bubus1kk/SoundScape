@file:Suppress("MagicNumber")

package com.soundscape.app.core.data

import com.soundscape.app.core.domain.model.DomainError
import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.MediaImageRequest
import com.soundscape.app.core.domain.model.MediaObjectType
import com.soundscape.app.core.network.ConnectivityChecker
import com.soundscape.app.core.network.MediaApi
import com.soundscape.app.core.network.MediaRequestDto
import com.soundscape.app.core.network.NetworkConfig
import dagger.Lazy
import java.io.IOException
import java.io.InterruptedIOException
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.ResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RemoteMediaReaderTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val request = MediaImageRequest(MediaObjectType.PLAYLIST, UUID(0, 1))

    @Test
    fun `maps Edge HTTP errors and preserves retry after`() = runTest {
        MockWebServer().use { server ->
            val api = Retrofit.Builder().baseUrl(server.url("/"))
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build().create(MediaApi::class.java)
            val reader = RemoteMediaReader(Lazy { api }, remoteCall())
            val cases = listOf(
                Triple(400, "VALIDATION_ERROR", DomainError.Validation("request", "invalid_request")),
                Triple(401, "INVALID_JWT", DomainError.Unauthenticated),
                Triple(401, "INVALID_API_KEY", DomainError.Unexpected),
                Triple(403, "FORBIDDEN", DomainError.Forbidden),
                Triple(404, "NOT_FOUND", DomainError.NotFound),
                Triple(429, "RATE_LIMITED", DomainError.RateLimited(30.seconds)),
                Triple(500, "INTERNAL_ERROR", DomainError.Server),
                Triple(502, "INVALID_MEDIA", DomainError.Server),
                Triple(503, "STORAGE_UNAVAILABLE", DomainError.Server),
            )
            for ((status, code, expected) in cases) {
                server.enqueue(MockResponse().setResponseCode(status).setHeader("Retry-After", "30")
                    .setBody("""{"error":{"code":"$code","message":"fixture","requestId":"fixture"}}"""))
                assertEquals(DomainResult.Failure(expected), reader.read(request))
            }
            assertEquals(cases.size, server.requestCount)
        }
    }

    @Test
    fun `success closes body and returns exact bytes`() = runTest {
        val bytes = byteArrayOf(1, 2, 3)
        val body = TrackingBody(bytes)
        val result = reader(OneCall(Response.success(body))).read(request)
        assertTrue(result is DomainResult.Success)
        assertArrayEquals(bytes, (result as DomainResult.Success).value.bytes)
        assertEquals("image/png", result.value.mimeType)
        assertTrue(body.closed)
    }

    @Test
    fun `invalid binary responses are closed`() = runTest {
        val bodies = listOf(
            TrackingBody(byteArrayOf()),
            TrackingBody(byteArrayOf(1), "image/gif"),
            TrackingBody("""{"error":{}}""".toByteArray(), "application/json"),
            TrackingBody(byteArrayOf(1), length = 10485761L),
            TrackingBody(ByteArray(10485761), length = -1L),
        )
        for (body in bodies) {
            assertEquals(
                DomainResult.Failure(DomainError.Unexpected),
                reader(OneCall(Response.success(body))).read(request),
            )
            assertTrue(body.closed)
        }
    }

    @Test
    fun `exact ten MiB succeeds and partial response is rejected`() = runTest {
        val body = TrackingBody(ByteArray(10485760), length = -1L)
        val result = reader(OneCall(Response.success(body))).read(request)
        assertEquals(10485760, (result as DomainResult.Success).value.bytes.size)
        assertTrue(body.closed)
        val partial = TrackingBody(byteArrayOf(1))
        assertEquals(
            DomainResult.Failure(DomainError.Unexpected),
            reader(OneCall(Response.success(206, partial))).read(request),
        )
        assertTrue(partial.closed)
    }

    @Test
    fun `HTTP error body is closed`() = runTest {
        val body = TrackingBody(
            """{"error":{"code":"NOT_FOUND","message":"fixture"}}""".toByteArray(), "application/json",
        )
        val result = reader(OneCall(Response.error(404, body))).read(request)
        assertEquals(DomainResult.Failure(DomainError.NotFound), result)
        assertTrue(body.closed)
    }

    @Test
    fun `cancellation cancels call and closes body during reading`() = runTest {
        lateinit var job: Job
        val body = TrackingBody(byteArrayOf(1, 2, 3), onRead = { job.cancel() })
        val call = OneCall(Response.success(body))
        job = launch(start = CoroutineStart.LAZY) { reader(call).read(request) }
        job.start()
        job.join()
        assertTrue(job.isCancelled)
        assertTrue(call.isCanceled())
        assertTrue(body.closed)
    }

    @Test
    fun `late success and error bodies are closed after cancellation`() = runTest {
        for (success in listOf(true, false)) {
            val body = TrackingBody(byteArrayOf(1))
            val response = if (success) Response.success<ResponseBody>(body) else Response.error(404, body)
            val call = OneCall(response, deferred = true)
            val job = launch { reader(call).read(request) }
            runCurrent()
            job.cancel()
            call.deliver()
            job.join()
            assertTrue(job.isCancelled)
            assertTrue(call.isCanceled())
            assertTrue(body.closed)
        }
    }

    @Test
    fun `body is closed when reading fails`() = runTest {
        val body = TrackingBody(byteArrayOf(1), onRead = { throw IOException("fixture") })
        assertEquals(
            DomainResult.Failure(DomainError.Network),
            reader(OneCall(Response.success(body))).read(request),
        )
        assertTrue(body.closed)
    }

    @Test
    fun `offline does not initialize API`() = runTest {
        val reader = RemoteMediaReader(Lazy<MediaApi> { error("Must not initialize API") }, remoteCall(true))
        assertEquals(DomainResult.Failure(DomainError.Network), reader.read(request))
    }

    @Test
    fun `transport failure and timeout keep existing mappings`() = runTest {
        assertEquals(
            DomainResult.Failure(DomainError.Network),
            reader(OneCall(failure = IOException("fixture"))).read(request),
        )
        assertEquals(
            DomainResult.Failure(DomainError.Timeout),
            reader(OneCall(failure = InterruptedIOException("fixture"))).read(request),
        )
    }

    private fun remoteCall(offline: Boolean = false) = RemoteCall(
        NetworkConfig("https://example.invalid/", "sb_publishable_test"),
        ConnectivityChecker { offline }, NetworkErrorMapper(json),
    )

    private fun reader(call: Call<ResponseBody>): RemoteMediaReader {
        val api = object : MediaApi {
            override fun image(request: MediaRequestDto, shareToken: String?): Call<ResponseBody> = call
        }
        return RemoteMediaReader(Lazy { api }, remoteCall())
    }

    private class TrackingBody(
        bytes: ByteArray,
        private val mime: String = "image/png",
        private val length: Long = bytes.size.toLong(),
        onRead: () -> Unit = {},
    ) : ResponseBody() {
        var closed = false
            private set
        private val input = object : ForwardingSource(Buffer().write(bytes)) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                onRead()
                return super.read(sink, byteCount)
            }
            override fun close() {
                closed = true
                super.close()
            }
        }.buffer()
        override fun contentType(): MediaType = mime.toMediaType()
        override fun contentLength(): Long = length
        override fun source(): BufferedSource = input
    }

    private class OneCall(
        private val response: Response<ResponseBody>? = null,
        private val failure: IOException? = null,
        private val deferred: Boolean = false,
    ) : Call<ResponseBody> {
        private var cancelled = false
        private var executed = false
        private lateinit var callback: Callback<ResponseBody>
        override fun enqueue(callback: Callback<ResponseBody>) {
            executed = true
            this.callback = callback
            if (!deferred) deliver()
        }
        fun deliver() {
            if (failure != null) callback.onFailure(this, failure)
            else callback.onResponse(this, requireNotNull(response))
        }
        override fun execute(): Response<ResponseBody> {
            executed = true
            failure?.let { throw it }
            return requireNotNull(response)
        }
        override fun cancel() { cancelled = true }
        override fun isCanceled(): Boolean = cancelled
        override fun isExecuted(): Boolean = executed
        override fun clone(): Call<ResponseBody> = OneCall(response, failure, deferred)
        override fun request(): Request = Request.Builder().url("https://example.invalid/").build()
        override fun timeout(): Timeout = Timeout.NONE
    }
}
