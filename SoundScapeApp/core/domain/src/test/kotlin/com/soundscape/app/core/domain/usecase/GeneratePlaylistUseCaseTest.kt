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
        for (count in listOf(1, 50)) {
            generator.result = DomainResult.Success(draft.copy(items = draft.items.take(count)))
            assertSuccess(useCase(GenerationRequest.Place(id, GenerationOptions(count = count, genreIds = (1..20).toList()))))
        }
    }
    @Test fun `guest cannot generate`() = runTest {
        auth.current = DomainResult.Success(null)
        assertError(useCase(GenerationRequest.Place(id)), DomainError.Unauthenticated)
        assertEquals(0, generator.calls)
    }
    @Test fun `place accepts exactly one UUID and journey rejects known ineligible routes`() = runTest {
        val pin = GenerationRequest.Place(musicPinId = id)
        assertValidation(useCase(GenerationRequest.Place()))
        assertValidation(useCase(pin.copy(placeId = otherId)))
        assertValidation(useCase(pin.copy(prompt = "x".repeat(1001))))
        assertValidation(useCase(GenerationRequest.Journey(stops, route = JourneyRoute())))
        assertValidation(useCase(pin.copy(options = GenerationOptions(genreIds = listOf(32768)))))
        assertEquals(0, generator.calls)
        assertSuccess(useCase(pin))
        assertEquals(pin, generator.input)
        val journey = GenerationRequest.Journey(stops, route = JourneyRoute(status = JourneyRouteStatus.READY, canGenerate = true))
        assertSuccess(useCase(journey))
        assertEquals(journey, generator.input)
    }

    @Test fun `malformed selections never leave the preview use case`() = runTest {
        val strict = GenerationOptions(count = 2, onlyInApp = true)
        val first = GeneratedItem(id, "Reason", TrackAvailability.IN_APP)
        val second = GeneratedItem(otherId, availability = TrackAvailability.EXTERNAL_ONLY)
        val variants = listOf(
            draft.copy(title = " ") to GenerationResultIssue.MISSING_TITLE,
            draft.copy(items = emptyList()) to GenerationResultIssue.EMPTY,
            draft.copy(items = listOf(first, second, first)) to GenerationResultIssue.TOO_MANY,
            draft.copy(items = listOf(first, first)) to GenerationResultIssue.DUPLICATE_TRACK,
            draft.copy(items = listOf(first, second)) to GenerationResultIssue.IN_APP_REQUIRED,
            draft.copy(items = listOf(first.copy(availability = null))) to GenerationResultIssue.IN_APP_REQUIRED,
        )
        val request = GenerationRequest.Quick("night", strict)
        for ((malformed, reason) in variants) {
            generator.result = DomainResult.Success(malformed)
            assertError(useCase(request), DomainError.InvalidGeneration(reason))
        }
        val short = draft.copy(items = listOf(first))
        generator.result = DomainResult.Success(short)
        assertEquals(DomainResult.Success(short), useCase(request))
    }

    @Test fun `editing preserves reasons and order until an explicit save request`() {
        val source = track().copy(id = UUID(0, 99))
        val added = (draft.add(source) as DomainResult.Success).value
        assertValidation(added.add(source))
        assertValidation(added.add(source.copy(title = " ")))
        assertValidation(added.copy(items = List(100) { GeneratedItem(UUID(1, it.toLong())) }).add(source))
        assertValidation(added.move(source.id, -1))
        assertError(draft.move(source.id, 0), DomainError.NotFound)
        val reordered = (added.move(source.id, 0) as DomainResult.Success).value.remove(id)
        assertEquals(listOf(source.id, otherId), reordered.toPlaylistInput(Visibility.UNLISTED).trackIds)
        assertEquals(draft.warnings, reordered.warnings)
        assertEquals(listOf(otherId, id), draft.items.map { it.trackId })
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
