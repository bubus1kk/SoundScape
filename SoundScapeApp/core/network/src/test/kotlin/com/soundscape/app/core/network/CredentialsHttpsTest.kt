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
                    .header("Authorization", "stale").header("apikey", "stale").build()).execute().close()
                val request = server.takeRequest(1, TimeUnit.SECONDS)!!
                assertEquals("sb_publishable_test", request.getHeader("apikey"))
                assertEquals(value?.let { "Bearer $it" }, request.getHeader("Authorization"))
            }
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
    @Test fun `redirect is returned without forwarding credentials`() {
        server().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", url(server, "/target")))
            val client = client(server, AccessTokenProvider { "synthetic-first" })
            client.newCall(Request.Builder().url(url(server)).build()).execute().use { assertEquals(302, it.code) }
            server.takeRequest()
            assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
        }
    }
    @Test fun `disconnect is not retried`() {
        server().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            val client = client(server, AccessTokenProvider { null })
            assertThrows(IOException::class.java) {
                client.newCall(Request.Builder().url(url(server)).build()).execute().close()
            }
            assertEquals(1, server.requestCount)
        }
    }
}
