package com.soundscape.app.core.domain.usecase

import com.soundscape.app.core.domain.model.*
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class GetVisiblePlacesUseCaseTest {
    private val places = FakePlaces()
    private val useCase = GetVisiblePlacesUseCase(places as com.soundscape.app.core.domain.repository.VisiblePlacesReader)

    @Test fun `antimeridian and pagination are passed unchanged`() = runTest {
        val page = PageRequest(100, 4)
        assertEquals(places.result, useCase(bounds, page))
        assertEquals(bounds, places.receivedBounds)
        assertEquals(page, places.page)
    }
    @Test fun `invalid pagination prevents lookup`() = runTest {
        for (page in listOf(PageRequest(0), PageRequest(101), PageRequest(offset = -1))) assertValidation(useCase(bounds, page))
        assertEquals(0, places.calls)
    }
    @Test fun `limits one and one hundred are accepted`() = runTest {
        for (limit in listOf(1, 100)) assertSuccess(useCase(bounds, PageRequest(limit)))
    }
    @Test fun `empty results succeed`() = runTest {
        places.result = DomainResult.Success(Page(emptyList(), false))
        assertEquals(places.result, useCase(bounds))
    }
    @Test fun `errors retain their reason`() = runTest {
        for (error in failures) { places.result = failure(error)
        assertError(useCase(bounds), error) }
    }
    @Test fun `cancellation propagates`() = runTest {
        places.before = { cancel() }
        assertCancellation { useCase(bounds) }
    }
}
