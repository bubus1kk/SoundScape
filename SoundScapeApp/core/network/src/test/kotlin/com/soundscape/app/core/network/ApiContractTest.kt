package com.soundscape.app.core.network

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class ApiContractTest {
    @Test fun `client disables retries redirects and rejects missing key`() {
        val config = NetworkConfig("https://example.invalid/", "")
        val client = NetworkModule.client(config, AccessTokenProvider { null })
        assertFalse(client.retryOnConnectionFailure)
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            client.newCall(okhttp3.Request.Builder().url(config.url).build()).execute().close()
        }
    }
    @Test fun `RPC sends raw JSON and parses empty page`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"items":[],"limit":20,"offset":0,"hasMore":false}"""))
            val api = Retrofit.Builder().baseUrl(server.url("/"))
                .addConverterFactory(Json.asConverterFactory("application/json".toMediaType()))
                .build().create(PlacesApi::class.java)
            assertTrue(api.visible(VisiblePlacesRequestDto(-1.0, 170.0, 1.0, -170.0, 20, 0)).body()!!.items.isEmpty())
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/rest/v1/rpc/get_visible_places", request.path)
            assertTrue(request.body.readUtf8().contains("\"minLon\":170.0"))
        }
    }
}
