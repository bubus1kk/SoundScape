package com.soundscape.app.core.domain.usecase

import com.soundscape.app.core.domain.model.*
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class GeneratePlaylistUseCaseTest {
    private val auth = FakeAuth()
    private val generator = FakeGenerator()
    private val useCase = GeneratePlaylistUseCase(auth, generator)

    @Test fun `four modes return unsaved ordered result with one request each`() = runTest {
        for (request in listOf(GenerationRequest.Quick("jazz"), GenerationRequest.Advanced(options = GenerationOptions(genreIds = listOf(1))), GenerationRequest.Place(id), GenerationRequest.Journey(stops))) {
            assertEquals(DomainResult.Success(draft), useCase(request))
        assertEquals(request, generator.input)
        }
        assertEquals(4, generator.calls)
    }
    @Test fun `invalid mode parameters prevent generation`() = runTest {
        for (request in listOf(GenerationRequest.Quick(" "), GenerationRequest.Quick("a".repeat(1001)), GenerationRequest.Advanced(), GenerationRequest.Advanced("a".repeat(1001)), GenerationRequest.Journey(stops.take(1)))) assertValidation(useCase(request))
        assertEquals(0, generator.calls)
    }
    @Test fun `count and filter boundaries are checked`() = runTest {
        for (count in listOf(0, 51)) assertValidation(useCase(GenerationRequest.Place(id, GenerationOptions(count = count))))
        for (ids in listOf(listOf(0), listOf(1, 1), (1..21).toList())) {
            assertValidation(useCase(GenerationRequest.Place(id, GenerationOptions(genreIds = ids))))
            assertValidation(useCase(GenerationRequest.Place(id, GenerationOptions(moodIds = ids))))
        }
        assertEquals(0, generator.calls)
        for (count in listOf(1, 50)) assertSuccess(useCase(GenerationRequest.Place(id, GenerationOptions(count = count, genreIds = (1..20).toList()))))
    }
    @Test fun `guest cannot generate`() = runTest {
        auth.current = DomainResult.Success(null)
        assertError(useCase(GenerationRequest.Place(id)), DomainError.Unauthenticated)
        assertEquals(0, generator.calls)
    }
    @Test fun `quota and service errors are returned without retry`() = runTest {
        for (error in failures) { generator.result = failure(error)
        assertError(useCase(GenerationRequest.Quick("jazz")), error) }
        assertEquals(failures.size, generator.calls)
    }
    @Test fun `cancellation propagates`() = runTest {
        generator.before = { cancel() }
        assertCancellation { useCase(GenerationRequest.Place(id)) }
        assertEquals(1, generator.calls)
    }
}
