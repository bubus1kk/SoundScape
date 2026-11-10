package com.soundscape.app.core.domain.usecase

import com.soundscape.app.core.domain.model.*
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CreateMusicPinUseCaseTest {
    private val auth = FakeAuth()
    private val repository = FakePins()
    private val useCase = CreateMusicPinUseCase(auth, repository)

    @Test fun `five photos and optional metadata are preserved`() = runTest {
        val input = pinInput().copy(photos = photos(5), description = "memory", visitDate = java.time.LocalDate.of(2026, 11, 10))
        assertEquals(repository.result, useCase(id, input))
        assertEquals(input, repository.input)
    }
    @Test fun `six photos and invalid track references prevent writes`() = runTest {
        for (input in listOf(pinInput().copy(photos = photos(6)), pinInput().copy(trackIds = emptyList()), pinInput().copy(trackIds = listOf(id, id)), pinInput().copy(trackIds = List(101) { UUID(0, it.toLong()) }), pinInput().copy(title = " "))) assertValidation(useCase(id, input))
        assertEquals(0, repository.calls)
    }
    @Test fun `one hundred unique ids are accepted`() = runTest {
        assertSuccess(useCase(id, pinInput().copy(trackIds = List(100) { UUID(0, it.toLong()) })))
    }
    @Test fun `guest and auth failure prevent save`() = runTest {
        auth.current = DomainResult.Success(null)
        assertError(useCase(id, pinInput()), DomainError.Unauthenticated)
        auth.current = failure(DomainError.Timeout)
        assertError(useCase(id, pinInput()), DomainError.Timeout)
        assertEquals(0, repository.calls)
    }
    @Test fun `errors and duplicate creation do not retry`() = runTest {
        for (error in failures + DomainError.AlreadyExists) { repository.result = failure(error)
        assertError(useCase(id, pinInput()), error) }
        assertEquals(failures.size + 1, repository.calls)
        assertEquals(id, repository.savedId)
    }
    @Test fun `cancellation propagates`() = runTest {
        repository.before = { cancel() }
        assertCancellation { useCase(id, pinInput()) }
        assertEquals(1, repository.calls)
    }
}
