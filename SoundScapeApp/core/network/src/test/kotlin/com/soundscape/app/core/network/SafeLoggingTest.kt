package com.soundscape.app.core.network

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class SafeLoggingTest {
    @Test fun `logs exclude headers paths bodies and user text`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("private-response"))
            val logs = mutableListOf<String>()
            val client = OkHttpClient.Builder().addInterceptor(SafeNetworkLoggingInterceptor { logs.add(it) }).build()
            client.newCall(Request.Builder().url(server.url("/private-path?q=private-query"))
                .header("Authorization", "Bearer synthetic-token").header("apikey", "synthetic-key")
                .header("X-SoundScape-Share-Token", "synthetic-share")
                .post("private-body".toRequestBody()).build()).execute().close()
            val output = logs.joinToString()
            assertTrue(output.contains("200"))
            listOf("private", "synthetic").forEach { assertFalse(output.contains(it)) }
        }
    }
}
