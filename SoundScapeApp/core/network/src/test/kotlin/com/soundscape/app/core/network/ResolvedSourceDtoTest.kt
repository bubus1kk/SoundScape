package com.soundscape.app.core.network

import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ResolvedSourceDtoTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `request contains only trackId`() {
        val encoded = json.encodeToString(ResolveTrackRequestDto(TRACK_ID))
        assertEquals(
            json.parseToJsonElement("""{"trackId":"$TRACK_ID"}"""),
            json.parseToJsonElement(encoded),
        )
    }

    @Test
    fun `decodes required fields and unknown provider`() {
        val dto = json.decodeFromString<ResolvedSourceDto>(BODY)
        assertEquals(TRACK_ID, dto.trackId)
        assertEquals("EXTERNAL_ONLY", dto.availability)
        assertEquals("FUTURE_PROVIDER", dto.provider)
        assertEquals(false, dto.cacheAllowed)
        assertEquals(false, dto.downloadAllowed)
        assertEquals(EXTERNAL_URL, dto.externalUrl)
        assertNull(dto.streamUrl)
        assertNull(dto.streamExpiresAt)
    }

    @Test
    fun `nullable fields may be omitted`() {
        val root = json.parseToJsonElement(BODY) as JsonObject
        val nullable = setOf("externalUrl", "streamUrl", "streamExpiresAt")
        val body = JsonObject(root.filterKeys { it !in nullable }).toString()
        val dto = json.decodeFromString<ResolvedSourceDto>(body)
        assertNull(dto.externalUrl)
        assertNull(dto.streamUrl)
        assertNull(dto.streamExpiresAt)
    }

    @Test
    fun `required fields cannot be missing or null`() {
        val root = json.parseToJsonElement(BODY) as JsonObject
        val required = listOf("trackId", "availability", "provider", "cacheAllowed", "downloadAllowed")
        for (field in required) {
            val missing = JsonObject(root.filterKeys { it != field })
            val explicitNull = JsonObject(root + (field to JsonNull))
            for (body in listOf(missing, explicitNull)) {
                assertThrows(SerializationException::class.java) {
                    json.decodeFromString<ResolvedSourceDto>(body.toString())
                }
            }
        }
    }

    private companion object {
        const val TRACK_ID = "11111111-1111-4111-8111-111111111111"
        const val EXTERNAL_URL = "https://music.example.test/track/fixture"
        val BODY = """
            {
              "trackId":"$TRACK_ID",
              "availability":"EXTERNAL_ONLY",
              "provider":"FUTURE_PROVIDER",
              "cacheAllowed":false,
              "downloadAllowed":false,
              "streamUrl":null,
              "streamExpiresAt":null,
              "externalUrl":"$EXTERNAL_URL",
              "futureField":"ignored"
            }
        """.trimIndent()
    }
}
