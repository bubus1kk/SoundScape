package com.soundscape.app.core.domain.usecase

import com.soundscape.app.core.domain.model.*
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SaveDiaryEntryUseCaseTest {
    private val auth = FakeAuth()
    private val repository = FakeDiary()
    private val useCase = SaveDiaryEntryUseCase(auth, repository)

    @Test fun `new entry defaults private with null body and ten photos`() = runTest {
        assertSuccess(useCase(id, diaryInput().copy(photos = photos(10))))
        assertEquals(Visibility.PRIVATE, repository.input?.visibility)
        assertNull(repository.input?.body)
    }
    @Test fun `public entry stays public when editing omits visibility`() = runTest {
        assertSuccess(useCase(id, diaryInput(), 2))
        assertEquals(Visibility.PUBLIC, repository.input?.visibility)
        assertEquals(2L, repository.revision)
    }
    @Test fun `explicit visibility change is respected`() = runTest {
        assertSuccess(useCase(id, diaryInput().copy(visibility = Visibility.UNLISTED), 2))
        assertEquals(Visibility.UNLISTED, repository.input?.visibility)
    }
    @Test fun `invalid input prevents all writes`() = runTest {
        for (input in listOf(diaryInput().copy(photos = photos(11)), diaryInput().copy(trackIds = emptyList()), diaryInput().copy(trackIds = listOf(id, id)), diaryInput().copy(locationName = null), diaryInput().copy(locationName = " ", coordinates = point), diaryInput().copy(title = ""))) assertValidation(useCase(id, input))
        assertValidation(useCase(id, diaryInput(), 0))
        assertEquals(0, repository.creates + repository.updates)
    }
    @Test fun `coordinates and pin identify location and preserve track order`() = runTest {
        for (input in listOf(diaryInput().copy(locationName = null, coordinates = point), diaryInput().copy(locationName = null, musicPinId = id))) {
            assertSuccess(useCase(id, input))
        assertEquals(input.trackIds, repository.input?.trackIds)
        }
    }
    @Test fun `guest and wrong owner cannot write`() = runTest {
        auth.current = DomainResult.Success(null)
        assertError(useCase(id, diaryInput()), DomainError.Unauthenticated)
        auth.current = DomainResult.Success(profile)
        repository.existing = DomainResult.Success(entry().copy(ownerId = otherId))
        assertError(useCase(id, diaryInput(), 2), DomainError.Forbidden)
        assertEquals(0, repository.creates + repository.updates)
    }
    @Test fun `revision conflict does not retry or create`() = runTest {
        repository.result = failure(DomainError.VersionConflict)
        assertError(useCase(id, diaryInput(), 2), DomainError.VersionConflict)
        assertEquals(1, repository.updates)
        assertEquals(0, repository.creates)
    }
    @Test fun `already exists and transport errors retain reason`() = runTest {
        for (error in failures + DomainError.AlreadyExists) { repository.result = failure(error)
        assertError(useCase(id, diaryInput()), error) }
        assertEquals(id, repository.savedId)
    }
    @Test fun `read and auth failures prevent update`() = runTest {
        repository.existing = failure(DomainError.NotFound)
        assertError(useCase(id, diaryInput(), 2), DomainError.NotFound)
        auth.current = failure(DomainError.Network)
        assertError(useCase(id, diaryInput(), 2), DomainError.Network)
        assertEquals(0, repository.updates)
    }
    @Test fun `cancellation propagates`() = runTest {
        repository.beforeWrite = { cancel() }
        assertCancellation { useCase(id, diaryInput()) }
        assertEquals(1, repository.creates)
    }
}
