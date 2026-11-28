package com.soundscape.app.core.data

import com.soundscape.app.core.domain.model.DomainError
import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.GenerationOptions
import com.soundscape.app.core.domain.model.GenerationRequest
import com.soundscape.app.core.domain.model.JourneyStop
import com.soundscape.app.core.domain.model.JourneyStopKind
import com.soundscape.app.core.domain.model.PlaylistInput
import com.soundscape.app.core.domain.model.Visibility
import com.soundscape.app.core.network.ConnectivityChecker
import com.soundscape.app.core.network.GeneratedPlaylistDto
import com.soundscape.app.core.network.GeneratedTrackDto
import com.soundscape.app.core.network.NetworkConfig
import com.soundscape.app.core.network.PlaylistGenerationApi
import com.soundscape.app.core.network.SessionCredential
import java.util.UUID
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class RemotePlaylistGenerationTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val a = UUID(0, 1)
    private val b = UUID(0, 2)
    private val credential = SessionCredential("local-test-only")

    @Test fun sendsAllModesAndUsesServerPositions() = runTest {
        MockWebServer().use { server ->
            val api = api(server)
            val requests =
                listOf(
                    GenerationRequest.Quick("night"),
                    GenerationRequest.Advanced(
                        "drive",
                        GenerationOptions(20, true, genreIds = listOf(7), moodIds = listOf(2)),
                    ),
                    GenerationRequest.Place(a, prompt = "quiet"),
                    GenerationRequest.Place(musicPinId = b),
                    GenerationRequest.Journey(
                        listOf(
                            JourneyStop(b, JourneyStopKind.PLACE),
                            JourneyStop(a, JourneyStopKind.PIN),
                        )
                    ),
                )
            requests.zip(listOf("QUICK", "ADVANCED", "PLACE", "PLACE", "JOURNEY")).forEach { (mode, expected)
                ->
                server.enqueue(
                    response(
                        200,
                        """{"title":"Mix","description":"","warnings":["warning"],
                    "items":[{"trackId":"$a","position":2},{"trackId":"$b","position":1,"reason":"chosen"}]}""",
                    )
                )
                val result = requireNotNull(api.generate(mode.toDto(), credential).body()).toDraft()
                assertEquals(listOf(b, a), result.items.map { it.trackId })
                assertEquals("chosen", result.items.first().reason)
                val request = server.takeRequest()
                assertEquals("/functions/v1/v1-generate-playlist", request.path)
                val body = json.parseToJsonElement(request.body.readUtf8()).jsonObject
                assertEquals(expected, body["mode"]?.jsonPrimitive?.content)
                when (mode) {
                    is GenerationRequest.Quick -> assertEquals("night", body["prompt"]?.jsonPrimitive?.content)
                    is GenerationRequest.Advanced -> {
                        assertEquals("[7]", body["genreIds"].toString())
                        assertEquals("[2]", body["moodIds"].toString())
                        assertEquals("true", body["onlyInApp"].toString())
                    }
                    is GenerationRequest.Place -> if (mode.placeId != null) {
                        assertEquals(a.toString(), body["placeId"]?.jsonPrimitive?.content)
                        assertFalse("musicPinId" in body)
                    } else {
                        assertEquals(b.toString(), body["musicPinId"]?.jsonPrimitive?.content)
                        assertFalse("placeId" in body)
                    }
                    is GenerationRequest.Journey -> {
                        val stops = requireNotNull(body["stops"]).jsonArray.map { it.jsonObject }
                        assertEquals(listOf(b.toString(), a.toString()), stops.map { it["id"]?.jsonPrimitive?.content })
                        assertEquals(listOf("1", "2"), stops.map { it["position"].toString() })
                    }
                }
            }
        }
    }

    @Test fun createsWithoutRevisionAndUpdatesWithRevisionInSameTrackOrder() = runTest {
        MockWebServer().use { server ->
            val api = api(server)
            val draft = PlaylistInput("Title", Visibility.PRIVATE, listOf(b, a), "Description")
            for (revision in listOf(null, 5L)) {
                server.enqueue(response(409, """{"message":"VERSION_CONFLICT"}"""))
                assertEquals(409, api.save(draft.toSaveDto(a, revision), credential).code())
                val request = server.takeRequest()
                assertEquals("/rest/v1/rpc/save_playlist", request.path)
                val body = json.parseToJsonElement(request.body.readUtf8()).jsonObject
                assertEquals(a.toString(), body["playlistId"]?.jsonPrimitive?.content)
                assertEquals(
                    listOf(b.toString(), a.toString()),
                    body["trackIds"]?.jsonArray?.map { it.jsonPrimitive.content },
                )
                if (revision == null) assertFalse("expectedRevision" in body)
                else assertEquals("5", body["expectedRevision"].toString())
            }
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun mapsHttpFailuresAndSkipsNetworkWhenOffline() = runTest {
        MockWebServer().use { server ->
            val api = api(server)
            val request = GenerationRequest.Quick("night").toDto()
            val cases = listOf(
                Triple(401, "AUTH_REQUIRED", DomainError.Unauthenticated),
                Triple(403, "DENIED", DomainError.Forbidden),
                Triple(409, "VERSION_CONFLICT", DomainError.VersionConflict),
                Triple(422, "NO_CANDIDATES", DomainError.NoCandidates),
                Triple(429, "RATE_LIMIT", DomainError.RateLimited()),
                Triple(502, "PROVIDER_ERROR", DomainError.Server),
                Triple(502, "PROVIDER_UNAVAILABLE", DomainError.SourceUnavailable()),
                Triple(409, "IDEMPOTENCY_KEY_REUSED", DomainError.IdempotencyConflict()),
            )
            val config = NetworkConfig("https://example.invalid/", "sb_publishable_test")
            val online = RemoteCall(config, ConnectivityChecker { false }, NetworkErrorMapper(json))
            cases.forEach { (status, error, expected) ->
                server.enqueue(response(status, """{"code":"$error","message":"$error"}"""))
                assertEquals(DomainResult.Failure(expected),
                    online.execute({ api.generate(request, credential) }, ::profileSessionError) { it.toDraft() })
            }
            val offline = RemoteCall(config, ConnectivityChecker { true }, NetworkErrorMapper(json))
            assertEquals(DomainResult.Failure(DomainError.Network),
                offline.execute({ api.generate(request, credential) }) { it.toDraft() })
            assertEquals(cases.size, server.requestCount)
        }
    }

    @Test fun readsSavedPlaylistFromSuccessfulRpcResponse() = runTest {
        MockWebServer().use { server ->
            val draft = PlaylistInput("Изменённая подборка", Visibility.PRIVATE, listOf(b, a), "Новое описание")
            server.enqueue(response(200, """{"id":"$a","ownerId":"${UUID(0, 3)}",
                "title":"${draft.title}","description":"${draft.description}","visibility":"PRIVATE","revision":1,
                "items":[{"position":1,"track":{"id":"$b","title":"Black Water","artist":"Apparat",
                "durationMs":270000,"availability":"EXTERNAL_ONLY","provider":"APPLE_MUSIC"}},
                {"position":2,"track":{"id":"$a","title":"La lune","artist":"Test artist",
                "durationMs":180000,"availability":"IN_APP"}}]}"""))
            val saved = requireNotNull(api(server).save(draft.toSaveDto(a, null), credential).body()).toDomain()
            assertEquals(a, saved.id)
            assertEquals(1L, saved.revision)
            assertEquals(draft.title, saved.title)
            assertEquals(draft.description, saved.description)
            assertEquals(draft.trackIds, saved.items.map { it.track.id })
            val request = server.takeRequest()
            assertEquals("/rest/v1/rpc/save_playlist", request.path)
            val body = json.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertEquals(draft.title, body["title"]?.jsonPrimitive?.content)
            assertEquals(draft.description, body["description"]?.jsonPrimitive?.content)
            assertEquals(
                draft.trackIds.map(UUID::toString),
                body["trackIds"]?.jsonArray?.map { it.jsonPrimitive.content },
            )
            assertEquals(null, request.getHeader("Authorization"))
        }
    }

    @Test fun rejectsMalformedIdAndInvalidPosition() {
        val malformed = GeneratedPlaylistDto("Valid", "", listOf(GeneratedTrackDto("0-0-0-0-1", 1)), emptyList())
        assertThrows(IllegalArgumentException::class.java) { malformed.toDraft() }
        val unordered = malformed.copy(items = listOf(GeneratedTrackDto(a.toString(), 2)))
        assertThrows(IllegalArgumentException::class.java) { unordered.toDraft() }
    }

    private fun api(server: MockWebServer) = Retrofit.Builder().baseUrl(server.url("/"))
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build().create(PlaylistGenerationApi::class.java)

    private fun response(code: Int, body: String) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
}
