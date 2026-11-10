package com.soundscape.app.core.domain.usecase

import com.soundscape.app.core.domain.model.*
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class GetNearbyPlacesUseCaseTest {
    private val location = FakeLocation()
    private val places = FakePlaces()
    private val useCase = GetNearbyPlacesUseCase(location, places)

    @Test fun `location radius and page reach repository`() = runTest {
        val page = PageRequest(100, 2)
        assertEquals(places.result, useCase(50000, page))
        assertEquals(point, places.center)
        assertEquals(50000, places.radius)
        assertEquals(page, places.page)
    }
    @Test fun `input validation precedes location lookup`() = runTest {
        for (radius in listOf(0, 50001)) assertValidation(useCase(radius))
        for (page in listOf(PageRequest(0), PageRequest(101), PageRequest(offset = -1))) assertValidation(useCase(1, page))
        assertEquals(0, location.calls)
        assertEquals(0, places.calls)
    }
    @Test fun `minimum radius and empty result succeed`() = runTest {
        places.result = DomainResult.Success(Page(emptyList(), false))
        assertEquals(places.result, useCase(1, PageRequest(1)))
    }
    @Test fun `location failure prevents nearby lookup`() = runTest {
        for (error in listOf(DomainError.LocationPermissionDenied, DomainError.LocationUnavailable)) {
            location.result = failure(error)
        assertError(useCase(100), error)
        }
        assertEquals(0, places.calls)
    }
    @Test fun `repository failures retain reason`() = runTest {
        for (error in failures) { places.result = failure(error)
        assertError(useCase(100), error) }
    }
    @Test fun `location and repository cancellation propagate`() = runTest {
        location.before = { cancel() }
        assertCancellation { useCase(100) }
        assertEquals(0, places.calls)
        location.before = {}
        places.before = { cancel() }
        assertCancellation { useCase(100) }
    }
}
