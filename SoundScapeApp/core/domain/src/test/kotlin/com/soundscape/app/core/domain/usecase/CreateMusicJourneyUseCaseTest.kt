package com.soundscape.app.core.domain.usecase

import com.soundscape.app.core.domain.model.*
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CreateMusicJourneyUseCaseTest {
    private val auth = FakeAuth()
    private val repository = FakeJourneys()
    private val useCase = CreateMusicJourneyUseCase(auth, repository)

    @Test fun `two stops retain order and playlist can be absent`() = runTest {
        assertSuccess(useCase(id, journeyInput()))
        assertEquals(stops, repository.input?.stops)
        assertNull(repository.input?.playlistId)
    }
    @Test fun `one or fifty one stops and blank title prevent save`() = runTest {
        for (input in listOf(journeyInput().copy(stops = stops.take(1)), journeyInput().copy(stops = List(51) { stops[0] }), journeyInput().copy(title = " "))) assertValidation(useCase(id, input))
        assertEquals(0, repository.calls)
    }
    @Test fun `fifty stops and explicit link reach repository unchanged`() = runTest {
        val input = journeyInput().copy(stops = List(50) { stops[it % 2] }, playlistId = otherId)
        assertSuccess(useCase(id, input))
        assertEquals(input, repository.input)
    }
    @Test fun `guest prevents creation`() = runTest {
        auth.current = DomainResult.Success(null)
        assertError(useCase(id, journeyInput()), DomainError.Unauthenticated)
        assertEquals(0, repository.calls)
    }
    @Test fun `server link errors and duplicate id retain reason`() = runTest {
        for (error in failures + listOf(DomainError.NotFound, DomainError.AlreadyExists)) { repository.result = failure(error)
        assertError(useCase(id, journeyInput()), error) }
        assertEquals(failures.size + 2, repository.calls)
    }
    @Test fun `cancellation propagates`() = runTest {
        repository.before = { cancel() }
        assertCancellation { useCase(id, journeyInput()) }
    }
}
