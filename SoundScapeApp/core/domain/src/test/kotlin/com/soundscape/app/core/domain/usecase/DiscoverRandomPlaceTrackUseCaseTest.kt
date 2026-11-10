package com.soundscape.app.core.domain.usecase

import com.soundscape.app.core.domain.model.*
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class DiscoverRandomPlaceTrackUseCaseTest {
    private val places = FakePlaces()

    @Test fun `deterministic selection uses track linked to chosen public candidate`() = runTest {
        val chosen = mapPlace.copy(id = otherId, kind = MapPlaceKind.PIN)
        places.result = DomainResult.Success(Page(listOf(mapPlace, chosen), true))
        val external = track(otherId, TrackAvailability.EXTERNAL_ONLY)
        places.linked = DomainResult.Success(listOf(track(), external))
        val random = FakeRandom(1, 1)
        assertEquals(DomainResult.Success(Discovery(chosen, external)), DiscoverRandomPlaceTrackUseCase(places, random)())
        assertEquals(otherId to MapPlaceKind.PIN, places.linkedTarget)
        assertEquals(listOf(2, 2), random.bounds)
        assertEquals(1, places.calls)
        assertEquals(1, places.linkedCalls)
    }
    @Test fun `empty candidates do not call random or linked lookup`() = runTest {
        places.result = DomainResult.Success(Page(emptyList(), false))
        val random = FakeRandom()
        assertEquals(DomainResult.Success<Discovery?>(null), DiscoverRandomPlaceTrackUseCase(places, random)())
        assertTrue(random.bounds.isEmpty())
        assertEquals(0, places.linkedCalls)
    }
    @Test fun `place without tracks returns empty without loop`() = runTest {
        places.linked = DomainResult.Success(emptyList())
        assertEquals(DomainResult.Success<Discovery?>(null), DiscoverRandomPlaceTrackUseCase(places, FakeRandom())())
        assertEquals(1, places.calls)
        assertEquals(1, places.linkedCalls)
    }
    @Test fun `invalid metadata is not selected`() = runTest {
        places.linked = DomainResult.Success(listOf(track().copy(title = "")))
        assertEquals(DomainResult.Success<Discovery?>(null), DiscoverRandomPlaceTrackUseCase(places, FakeRandom())())
    }
    @Test fun `candidate and track errors are preserved`() = runTest {
        val useCase = DiscoverRandomPlaceTrackUseCase(places, FakeRandom())
        for (error in failures) { places.result = failure(error)
        assertError(useCase(), error) }
        places.result = DomainResult.Success(Page(listOf(mapPlace), false))
        places.linked = failure(DomainError.NotFound)
        assertError(useCase(), DomainError.NotFound)
    }
    @Test fun `random failure and invalid index cannot crash`() = runTest {
        val random = FakeRandom(); random.result = failure(DomainError.Unexpected)
        assertError(DiscoverRandomPlaceTrackUseCase(places, random)(), DomainError.Unexpected)
        assertError(DiscoverRandomPlaceTrackUseCase(places, FakeRandom(-1))(), DomainError.Unexpected)
        assertError(DiscoverRandomPlaceTrackUseCase(places, FakeRandom(0, 5))(), DomainError.Unexpected)
    }
    @Test fun `cancellation propagates`() = runTest {
        places.before = { cancel() }
        assertCancellation { DiscoverRandomPlaceTrackUseCase(places, FakeRandom())() }
    }
}
