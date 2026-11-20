package com.soundscape.app.core.network

import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PlaylistDtoTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `request contains only playlistId`() {
        val encoded = json.parseToJsonElement(json.encodeToString(GetPlaylistRequestDto(PLAYLIST_ID)))
        assertEquals(json.parseToJsonElement("""{"playlistId":"$PLAYLIST_ID"}"""), encoded)
    }

    @Test
    fun `detail decodes nullable fields and unknown provider`() {
        val dto = json.decodeFromString<PlaylistDetailDto>(DETAIL)
        assertEquals(PLAYLIST_ID, dto.id)
        assertEquals("PRIVATE", dto.visibility)
        assertEquals(1L, dto.revision)
        assertNull(dto.description)
        assertNull(dto.placeId)
        assertNull(dto.coverPath)
        assertEquals(listOf(1), dto.genreIds)
        assertEquals(listOf(2), dto.tagIds)
        assertEquals(true, dto.isEditorial)
        assertEquals(1, dto.items.single().position)
        assertEquals("future-provider", dto.items.single().track.provider)
        assertEquals("EXTERNAL_ONLY", dto.items.single().track.availability)
    }

    @Test
    fun `optional fields may be omitted`() {
        val optionalNames = setOf("description", "placeId", "coverPath", "genreIds", "tagIds", "isEditorial")
        val root = json.parseToJsonElement(DETAIL) as JsonObject
        val minimal = JsonObject(root.filterKeys { it !in optionalNames })
        val dto = json.decodeFromString<PlaylistDetailDto>(minimal.toString())
        assertNull(dto.description)
        assertNull(dto.placeId)
        assertNull(dto.coverPath)
        assertEquals(emptyList<Int>(), dto.genreIds)
        assertEquals(emptyList<Int>(), dto.tagIds)
        assertFalse(dto.isEditorial)
    }

    @Test
    fun `track provider may be null or omitted`() {
        val track = """
            {
              "id":"$TRACK_ID",
              "title":"Fixture track",
              "artist":"Fixture artist",
              "durationMs":180000,
              "availability":"EXTERNAL_ONLY"
            }
        """.trimIndent()
        assertNull(json.decodeFromString<TrackViewDto>(track).provider)
        assertNull(json.decodeFromString<TrackViewDto>(track.replace(
            "\"availability\":\"EXTERNAL_ONLY\"",
            "\"availability\":\"EXTERNAL_ONLY\",\"provider\":null",
        )).provider)
    }

    @Test
    fun `missing required fields are rejected`() {
        val root = json.parseToJsonElement(DETAIL) as JsonObject
        for (field in listOf("id", "ownerId", "title", "visibility", "revision", "items")) {
            val body = JsonObject(root.filterKeys { it != field }).toString()
            assertThrows(SerializationException::class.java) {
                json.decodeFromString<PlaylistDetailDto>(body)
            }
        }
    }

    @Test
    fun `item requires both position and track`() {
        val trackJson = """
            {
              "id":"$TRACK_ID",
              "title":"Fixture track",
              "artist":"Fixture artist",
              "durationMs":180000,
              "availability":"EXTERNAL_ONLY"
            }
        """.trimIndent()
        for (body in listOf("""{"track":$trackJson}""", """{"position":1}""")) {
            assertThrows(SerializationException::class.java) {
                json.decodeFromString<PlaylistItemDto>(body)
            }
        }
    }

    @Test
    fun `null items are rejected instead of becoming empty`() {
        val root = json.parseToJsonElement(DETAIL) as JsonObject
        val body = JsonObject(root + ("items" to JsonNull)).toString()
        assertThrows(SerializationException::class.java) {
            json.decodeFromString<PlaylistDetailDto>(body)
        }
    }

    @Test
    fun `explicit empty items are accepted`() {
        val root = json.parseToJsonElement(DETAIL) as JsonObject
        val body = JsonObject(root + ("items" to json.parseToJsonElement("[]"))).toString()
        assertEquals(emptyList<PlaylistItemDto>(), json.decodeFromString<PlaylistDetailDto>(body).items)
    }

    private companion object {
        const val PLAYLIST_ID = "11111111-1111-4111-8111-111111111111"
        const val TRACK_ID = "33333333-3333-4333-8333-333333333333"
        val DETAIL = """
            {
              "id":"$PLAYLIST_ID",
              "ownerId":"22222222-2222-4222-8222-222222222222",
              "title":"Fixture playlist",
              "visibility":"PRIVATE",
              "revision":1,
              "description":null,
              "placeId":null,
              "coverPath":null,
              "genreIds":[1],
              "tagIds":[2],
              "isEditorial":true,
              "futureField":"ignored",
              "items":[{
                "position":1,
                "track":{
                  "id":"$TRACK_ID",
                  "title":"Fixture track",
                  "artist":"Fixture artist",
                  "durationMs":180000,
                  "availability":"EXTERNAL_ONLY",
                  "provider":"future-provider"
                }
              }]
            }
        """.trimIndent()
    }
}
