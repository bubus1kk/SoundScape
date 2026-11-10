package com.soundscape.app.core.data

import com.soundscape.app.core.domain.model.DomainError
import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.TrackAvailability
import com.soundscape.app.core.network.PlacePageDto
import com.soundscape.app.core.network.TrackViewDto
import com.soundscape.app.core.network.PlaceSummaryDto
import com.soundscape.app.core.network.NetworkConfig
import com.soundscape.app.core.network.ConnectivityChecker
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RemoteReadersTest {
    @Test fun `missing config does not initialize API`() = runTest {
        val call = RemoteCall(NetworkConfig("", ""), ConnectivityChecker { false }, NetworkErrorMapper(Json))
        val result = call.execute<PlacePageDto, Int>({ error("must not execute") }, { it.items.size })
        assertEquals(DomainResult.Failure(DomainError.Unexpected), result)
    }
    @Test fun `guest unknown 401 is not login failure`() {
        val errors = NetworkErrorMapper(Json)
        assertEquals(DomainError.Unexpected, errors.http(401, null, null))
        assertEquals(DomainError.Unexpected, errors.http(401, """{"error":{"code":"INVALID_API_KEY"}}""", null))
        assertEquals(DomainError.Unauthenticated, errors.http(401, """{"error":{"code":"INVALID_JWT"}}""", null))
        assertEquals(DomainError.Server, errors.http(503, null, null))
        assertEquals(DomainError.RateLimited(), errors.http(429, null, null))
    }
    @Test fun `place mapper validates UUID enums coordinates and optional fields`() {
        val dto = PlaceSummaryDto("123e4567-e89b-42d3-a456-426614174000", "CITY", "Place", 90.0, -180.0)
        assertEquals(90.0, dto.toDomain().coordinates.latitude, 0.0)
        assertThrows(IllegalArgumentException::class.java) { dto.copy(id = "1-1-1-1-1").toDomain() }
        assertThrows(IllegalArgumentException::class.java) { dto.copy(latitude = Double.NaN).toDomain() }
        assertThrows(IllegalArgumentException::class.java) { dto.copy(kind = "NEW_KIND").toDomain() }
    }
    @Test fun `track mapping rejects corrupted server data`() {
        val dto = TrackViewDto("123e4567-e89b-42d3-a456-426614174000", "Title", "Artist", 1, "IN_APP")
        assertEquals(TrackAvailability.IN_APP, dto.toDomain().availability)
        assertThrows(IllegalArgumentException::class.java) { dto.copy(durationMs = 0).toDomain() }
    }
}
