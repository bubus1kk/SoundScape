package com.soundscape.app.core.domain.usecase

import com.soundscape.app.core.domain.model.*
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SavePlaylistUseCaseTest {
    private val auth = FakeAuth()
    private val repository = FakePlaylists()
    private val useCase = SavePlaylistUseCase(auth, repository)

    @Test fun `creation preserves duplicates metadata and order`() = runTest {
        val input = playlistInput()
        assertEquals(repository.result, useCase(id, input))
        assertEquals(input, repository.input)
        assertEquals(id, repository.savedId)
        assertEquals(1, repository.creates)
        assertEquals(0, repository.reads)
    }
    @Test fun `revision conflict returns without retry or creation`() = runTest {
        repository.result = failure(DomainError.VersionConflict)
        assertError(useCase(id, playlistInput(), 2), DomainError.VersionConflict)
        assertEquals(2L, repository.revision)
        assertEquals(1, repository.updates)
        assertEquals(0, repository.creates)
    }
    @Test fun `duplicate creation preserves uuid and already exists`() = runTest {
        repository.result = failure(DomainError.AlreadyExists)
        assertError(useCase(id, playlistInput()), DomainError.AlreadyExists)
        assertEquals(1, repository.creates)
        assertEquals(id, repository.savedId)
    }
    @Test fun `invalid fields prevent writes`() = runTest {
        val input = playlistInput()
        for (bad in listOf(input.copy(title = " "), input.copy(title = "a" + " ".repeat(150)), input.copy(description = "a".repeat(2001)), input.copy(trackIds = emptyList()), input.copy(trackIds = List(101) { id }), input.copy(genreIds = listOf(0)), input.copy(tagIds = listOf(1, 1)), input.copy(genreIds = (1..31).toList()))) assertValidation(useCase(id, bad))
        assertValidation(useCase(id, input, 0))
        assertEquals(0, repository.creates + repository.updates)
    }
    @Test fun `boundary counts are accepted`() = runTest {
        for (count in listOf(1, 100)) assertSuccess(useCase(id, playlistInput().copy(trackIds = List(count) { id }, genreIds = (1..30).toList(), tagIds = (1..30).toList())))
    }
    @Test fun `guest wrong owner and editorial content cannot write`() = runTest {
        auth.current = DomainResult.Success(null)
        assertError(useCase(id, playlistInput()), DomainError.Unauthenticated)
        auth.current = DomainResult.Success(profile)
        repository.existing = DomainResult.Success(playlist().copy(ownerId = otherId))
        assertError(useCase(id, playlistInput(), 2), DomainError.Forbidden)
        repository.existing = DomainResult.Success(playlist().copy(isEditorial = true))
        assertError(useCase(id, playlistInput(), 2), DomainError.Forbidden)
        assertEquals(0, repository.creates + repository.updates)
    }
    @Test fun `auth read and write failures retain types`() = runTest {
        auth.current = failure(DomainError.Unauthenticated)
        assertError(useCase(id, playlistInput()), DomainError.Unauthenticated)
        auth.current = DomainResult.Success(profile)
        repository.existing = failure(DomainError.NotFound)
        assertError(useCase(id, playlistInput(), 2), DomainError.NotFound)
        for (error in failures) { repository.result = failure(error)
        assertError(useCase(id, playlistInput()), error) }
    }
    @Test fun `cancellation propagates without another write`() = runTest {
        repository.beforeWrite = { cancel() }
        assertCancellation { useCase(id, playlistInput()) }
        assertEquals(1, repository.creates)
    }
    @Test fun `successful update preserves user metadata and supplied revision`() = runTest {
        val input = playlistInput().copy(visibility = Visibility.UNLISTED)
        assertEquals(repository.result, useCase(id, input, 2))
        assertEquals(input, repository.input)
        assertEquals(2L, repository.revision)
        assertEquals(1, repository.updates)
        assertEquals(0, repository.creates)
    }
}
