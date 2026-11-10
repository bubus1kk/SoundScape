package com.soundscape.app.core.domain.usecase

import com.soundscape.app.core.domain.model.*
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RequestSyncUseCaseTest {
    private val auth = FakeAuth()
    private val sync = FakeSync()
    private val useCase = RequestSyncUseCase(auth, sync)

    @Test fun `accepted means scheduled not completed`() = runTest {
        assertEquals(DomainResult.Success(SyncRequestStatus.ACCEPTED), useCase())
        assertEquals(1, sync.calls)
    }
    @Test fun `already pending is returned without internal repeat`() = runTest {
        sync.result = DomainResult.Success(SyncRequestStatus.ALREADY_PENDING)
        assertEquals(sync.result, useCase())
        assertEquals(1, sync.calls)
    }
    @Test fun `guest and auth errors prevent scheduling`() = runTest {
        auth.current = DomainResult.Success(null)
        assertError(useCase(), DomainError.Unauthenticated)
        auth.current = failure(DomainError.Timeout)
        assertError(useCase(), DomainError.Timeout)
        assertEquals(0, sync.calls)
    }
    @Test fun `port failures are not retried`() = runTest {
        for (error in failures) { sync.result = failure(error)
        assertError(useCase(), error) }
        assertEquals(failures.size, sync.calls)
    }
    @Test fun `each explicit command delegates once allowing port to coalesce`() = runTest {
        assertSuccess(useCase())
        sync.result = DomainResult.Success(SyncRequestStatus.ALREADY_PENDING)
        assertEquals(sync.result, useCase())
        assertEquals(2, sync.calls)
    }
    @Test fun `cancellation propagates`() = runTest {
        sync.before = { cancel() }
        assertCancellation { useCase() }
        assertEquals(1, sync.calls)
    }
}
