package com.soundscape.app.core.network

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import okhttp3.MediaType.Companion.toMediaType

class CredentialsHttpsTest {
    private val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
    private val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
    private val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()

    private fun server() = MockWebServer().apply { useHttps(serverTls.sslSocketFactory(), false); start() }
    private fun url(server: MockWebServer, path: String = "/") =
        server.url(path).newBuilder().host("localhost").build()
    private fun client(server: MockWebServer, tokens: AccessTokenProvider): OkHttpClient = NetworkModule.client(
        NetworkConfig(url(server).toString(), "sb_publishable_test"), tokens,
    ).newBuilder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).build()

    @Test fun `production settings and credentials survive guest and two JWT changes`() {
        server().use { server ->
            var token: String? = null
            val client = client(server, AccessTokenProvider { token })
            assertFalse(client.retryOnConnectionFailure)
            assertFalse(client.followRedirects)
            assertFalse(client.followSslRedirects)
            for (value in listOf(null, "synthetic-first", "synthetic-second")) {
                token = value
                server.enqueue(MockResponse().setBody("{}"))
                client.newCall(Request.Builder().url(url(server))
                    .header("X-Soundscape-Auth", "OptionalUser")
                    .header("Authorization", "stale").header("apikey", "stale").build()).execute().close()
                val request = server.takeRequest(1, TimeUnit.SECONDS)!!
                assertEquals("sb_publishable_test", request.getHeader("apikey"))
                assertEquals(value?.let { "Bearer $it" }, request.getHeader("Authorization"))
            }
        }
    }
    @Test fun `guest cache request never reads or sends tokens and strips internal marker`() {
        server().use { server ->
            server.enqueue(MockResponse().setBody("{}"))
            val client = client(server, AccessTokenProvider { error("guest must not read a token") })
            client.newCall(Request.Builder().url(url(server))
                .header("X-Soundscape-Auth", "Public").header("Authorization", "stale").build()).execute().close()
            val request = server.takeRequest()
            assertNull(request.getHeader("Authorization"))
            assertNull(request.getHeader("X-Soundscape-Guest"))
            assertNull(request.getHeader("X-Soundscape-Auth"))
            assertEquals("sb_publishable_test", request.getHeader("apikey"))
        }
    }
    @Test fun `different origin is rejected before transport and token lookup`() {
        server().use { configured ->
            server().use { other ->
                val client = client(configured, AccessTokenProvider { error("token must not be read") })
                val urls = listOf(url(other), url(configured).newBuilder().host("127.0.0.1").build(),
                    url(configured).newBuilder().scheme("http").build())
                for (url in urls) {
                    assertThrows(IllegalStateException::class.java) {
                        client.newCall(Request.Builder().url(url).build()).execute().close()
                    }
                }
                assertEquals(0, configured.requestCount)
                assertEquals(0, other.requestCount)
            }
        }
    }
    @Test fun `missing invalid and unauthenticated policies fail before transport`() {
        server().use { server ->
            val client = client(server, AccessTokenProvider { null })
            for (policy in listOf(null, "Unknown", "Authenticated")) {
                val request = Request.Builder().url(url(server)).apply {
                    if (policy != null) header("X-Soundscape-Auth", policy)
                }.build()
                assertThrows(RuntimeException::class.java) { client.newCall(request).execute().close() }
            }
            assertEquals(0, server.requestCount)
        }
    }
    @Test fun `authenticated request sends current JWT`() {
        server().use { server ->
            server.enqueue(MockResponse().setBody("{}"))
            client(server, AccessTokenProvider { "synthetic-jwt" }).newCall(Request.Builder()
                .url(url(server)).header("X-Soundscape-Auth", "Authenticated").build()).execute().close()
            val request = server.takeRequest()
            assertEquals("Bearer synthetic-jwt", request.getHeader("Authorization"))
            assertNull(request.getHeader("X-Soundscape-Auth"))
        }
    }
    @Test fun `both Retrofit APIs remain public across JWT changes`() = kotlinx.coroutines.test.runTest {
        server().use { server ->
            var token: String? = null
            val retrofit = retrofit2.Retrofit.Builder().baseUrl(url(server))
                .client(client(server, AccessTokenProvider { token }))
                .addConverterFactory(kotlinx.serialization.json.Json.asConverterFactory(
                    "application/json".toMediaType())).build()
            for (value in listOf(null, "synthetic-first", "synthetic-second")) {
                token = value
                for (places in listOf(true, false)) {
                    server.enqueue(MockResponse().setBody("""{"items":[],"limit":20,"offset":0,"hasMore":false}"""))
                    if (places) retrofit.create(PlacesApi::class.java)
                        .visible(VisiblePlacesRequestDto(-90.0, -180.0, 90.0, 180.0, 20, 0))
                    else retrofit.create(TracksApi::class.java)
                        .search(SearchTracksRequestDto("ambient", false, 20, 0, emptyList(), emptyList()))
                    val request = server.takeRequest()
                    assertEquals("POST", request.method)
                    assertEquals(if (places) "/rest/v1/rpc/get_visible_places"
                        else "/functions/v1/v1-search-tracks", request.path)
                    assertNull(request.getHeader("Authorization"))
                    assertNull(request.getHeader("X-Soundscape-Auth"))
                    assertEquals("sb_publishable_test", request.getHeader("apikey"))
                }
            }
        }
    }
    @Test fun `redirect is returned without forwarding credentials`() {
        server().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", url(server, "/target")))
            val client = client(server, AccessTokenProvider { "synthetic-first" })
            client.newCall(Request.Builder().url(url(server))
                .header("X-Soundscape-Auth", "OptionalUser").build()).execute().use { assertEquals(302, it.code) }
            server.takeRequest()
            assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
        }
    }
    @Test fun `disconnect is not retried`() {
        server().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            val client = client(server, AccessTokenProvider { null })
            assertThrows(IOException::class.java) {
                client.newCall(Request.Builder().url(url(server))
                .header("X-Soundscape-Auth", "OptionalUser").build()).execute().close()
            }
            assertEquals(1, server.requestCount)
        }
    }
}
