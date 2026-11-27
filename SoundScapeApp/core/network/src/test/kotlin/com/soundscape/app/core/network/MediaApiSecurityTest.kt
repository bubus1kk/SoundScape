package com.soundscape.app.core.network

import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class MediaApiSecurityTest {
    private val json = NetworkModule.json()
    private val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
    private val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
    private val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()

    @Test
    fun `media POST strips policy and supports guest or JWT without redirect leaks`() {
        server().use { server ->
            val origin = server.url("/").newBuilder().host("localhost").build()
            var jwt: String? = null
            val client = NetworkModule.client(
                NetworkConfig(origin.toString(), "sb_publishable_test"), AccessTokenProvider { jwt },
            ).newBuilder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).build()
            val api = Retrofit.Builder().baseUrl(origin).client(client)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build().create(MediaApi::class.java)
            val token = "synthetic-share-token-".repeat(2)
            val body = MediaRequestDto("PLAYLIST", ID)
            assertFalse(client.followRedirects)
            assertFalse(client.followSslRedirects)
            assertNull(client.cache)
            for (value in listOf(null, "synthetic-jwt")) {
                jwt = value
                server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody("fixture"))
                api.image(body, token).execute().body()!!.close()
                val request = requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))
                assertEquals("POST", request.method)
                assertEquals("/functions/v1/v1-media", request.path)
                assertEquals(
                    json.parseToJsonElement("""{"objectType":"PLAYLIST","objectId":"$ID"}"""),
                    json.parseToJsonElement(request.body.readUtf8()),
                )
                assertEquals("sb_publishable_test", request.getHeader("apikey"))
                assertEquals(value?.let { "Bearer $it" }, request.getHeader("Authorization"))
                assertEquals(token, request.getHeader("X-SoundScape-Share-Token"))
                assertNull(request.getHeader("X-Soundscape-Auth"))
                assertEquals("no-store", request.getHeader("Cache-Control"))
            }
            MockWebServer().use { other ->
                other.start()
                server.enqueue(MockResponse().setResponseCode(302)
                    .setHeader("Location", other.url("/must-not-receive")))
                val response = api.image(body, token).execute()
                assertEquals(302, response.code())
                response.errorBody()?.close()
                assertNull(other.takeRequest(100, TimeUnit.MILLISECONDS))
                val foreignApi = Retrofit.Builder().baseUrl(other.url("/")).client(client)
                    .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                    .build().create(MediaApi::class.java)
                assertThrows(IllegalStateException::class.java) { foreignApi.image(body, token).execute() }
                assertEquals(0, other.requestCount)
            }
        }
    }

    @Test
    fun `production serializer omits photoId for playlist and place`() {
        serializationCases(listOf("PLAYLIST", "PLACE"), null)
    }

    @Test
    fun `production serializer sends photoId for pin and event`() {
        serializationCases(listOf("PIN", "EVENT"), PHOTO_ID)
    }

    private fun serializationCases(types: List<String>, photoId: String?) {
        MockWebServer().use { server ->
            val api = Retrofit.Builder().baseUrl(server.url("/"))
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build().create(MediaApi::class.java)
            for (type in types) {
                server.enqueue(MockResponse().setBody("fixture"))
                api.image(MediaRequestDto(type, ID, photoId)).execute().body()!!.close()
                val recorded = requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))
                val expected = if (photoId == null) {
                    """{"objectType":"$type","objectId":"$ID"}"""
                } else {
                    """{"objectType":"$type","objectId":"$ID","photoId":"$photoId"}"""
                }
                assertEquals(json.parseToJsonElement(expected), json.parseToJsonElement(recorded.body.readUtf8()))
                assertNull(recorded.getHeader("X-SoundScape-Share-Token"))
            }
        }
    }

    private fun server() = MockWebServer().apply {
        useHttps(serverTls.sslSocketFactory(), false)
        start()
    }

    private companion object {
        const val ID = "11111111-1111-4111-8111-111111111111"
        const val PHOTO_ID = "22222222-2222-4222-8222-222222222222"
    }
}
