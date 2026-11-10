package com.soundscape.app.core.domain.usecase

import com.soundscape.app.core.domain.model.*
import java.util.UUID
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test

class PlayTrackUseCaseTest {
    private val resolver = FakeResolver()
    private val player = FakePlayer()
    private val useCase = PlayTrackUseCase(resolver, player)

    @Test fun `resolved internal source is launched once`() = runTest {
        assertEquals(DomainResult.Success(PlayTrackResult.Started), useCase(id))
        assertEquals(internalSource, player.source)
        assertEquals(1, player.calls)
    }
    @Test fun `external source is returned without launch`() = runTest {
        resolver.result = DomainResult.Success(externalSource)
        assertEquals(DomainResult.Success(PlayTrackResult.ExternalActionRequired(externalSource)), useCase(id))
        assertEquals(0, player.calls)
    }
    @Test fun `source for another track is rejected`() = runTest {
        resolver.result = DomainResult.Success(internalSource.copy(trackId = otherId))
        assertError(useCase(id), DomainError.PlaybackUnavailable)
        assertEquals(0, player.calls)
    }
    @Test fun `unavailable stream and transport errors prevent launch`() = runTest {
        for (error in failures + listOf(DomainError.NotFound, DomainError.PlaybackUnavailable)) { resolver.result = failure(error)
        assertError(useCase(id), error) }
        assertEquals(0, player.calls)
    }
    @Test fun `failed player never produces started`() = runTest {
        player.result = failure(DomainError.PlaybackUnavailable)
        assertError(useCase(id), DomainError.PlaybackUnavailable)
        assertEquals(1, player.calls)
    }
    @Test fun `current resolution is used on every invocation`() = runTest {
        assertSuccess(useCase(id))
        resolver.result = DomainResult.Success(externalSource)
        assertSuccess(useCase(id))
        assertEquals(2, resolver.calls)
        assertEquals(1, player.calls)
    }
    @Test fun `resolver and player cancellation propagate`() = runTest {
        resolver.before = { cancel() }
        assertCancellation { useCase(id) }
        assertEquals(0, player.calls)
        resolver.before = {}
        player.before = { cancel() }
        assertCancellation { useCase(id) }
    }
    @Test fun `cancelling a suspended resolution never launches player`() = runTest {
        val entered = CompletableDeferred<Unit>()
        resolver.before = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val job = launch { useCase(id) }
        entered.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(0, player.calls)
    }
}
