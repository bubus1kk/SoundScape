package com.soundscape.app.core.domain.model

import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class OfflineOperationTest {
    private val account = UUID(0, 1)
    private val objectId = UUID(0, 2)
    private val key = UUID(0, 3)
    private val newKey = UUID(0, 4)
    private val request = MutationIdentity(account, objectId, key, 3)
    private val edit = OfflineEdit.Playlist(PlaylistInput("Edited", Visibility.PRIVATE, listOf(objectId)))
    private val operation = SyncOperation(request, edit)

    @Test fun `network failure retries with original payload and key`() {
        val running = operation.start().value()
        assertEquals(SyncOperationState.Running, running.state)
        assertTrue(running.start() is DomainResult.Failure)
        val retry = running.fail(DomainError.Network).value()
        assertEquals(SyncOperationState.Retryable(DomainError.Network), retry.state)
        assertEquals(request, retry.start().value().identity)
        assertEquals(edit, retry.edit)
        assertTrue(operation.fail(DomainError.Network) is DomainResult.Failure)
    }

    @Test fun `revision conflict retains edit until the user explicitly decides`() {
        val error = DomainError.RevisionMismatch(3, 5)
        val conflicted = operation.start().value().fail(error).value()
        assertEquals(SyncOperationState.Conflict(error), conflicted.state)
        assertEquals(edit, conflicted.edit)
        assertTrue(conflicted.start() is DomainResult.Failure)
        assertTrue(operation.resolve(ConflictChoice.KeepRemote) is DomainResult.Failure)
        assertEquals(null, conflicted.resolve(ConflictChoice.KeepRemote).value())
        assertEquals(DomainResult.Failure(DomainError.IdempotencyConflict(key)),
            conflicted.resolve(ConflictChoice.KeepLocal(key, 5)))
        assertEquals(DomainResult.Failure(DomainError.RevisionMismatch(4, 5)),
            conflicted.resolve(ConflictChoice.KeepLocal(newKey, 4)))
        val replacement = conflicted.resolve(ConflictChoice.KeepLocal(newKey, 5)).value()!!
        assertEquals(request.copy(idempotencyKey = newKey, expectedRevision = 5), replacement.identity)
        assertEquals(edit, replacement.edit)
        assertEquals(SyncOperationState.Pending, replacement.state)
    }

    @Test fun `replay matches revision kind and body within the same account`() {
        assertEquals(DomainResult.Success(Unit), operation.checkReplay(operation.start().value()))
        for (changed in listOf(
            operation.copy(edit = OfflineEdit.Delete(ShareKind.PLAYLIST)),
            operation.copy(identity = request.copy(expectedRevision = 4)),
            operation.copy(edit = OfflineEdit.Playlist(edit.input.copy(title = "Different"))),
        )) assertEquals(DomainResult.Failure(DomainError.IdempotencyConflict(key)), operation.checkReplay(changed))
        val foreign = operation.copy(identity = request.copy(accountId = newKey))
        assertTrue(operation.checkReplay(foreign) is DomainResult.Failure)
        val rejected = operation.start().value().fail(DomainError.Forbidden).value()
        assertEquals(SyncOperationState.Rejected(DomainError.Forbidden), rejected.state)
        assertTrue(rejected.start() is DomainResult.Failure)
    }

    private fun <T> DomainResult<T>.value(): T = (this as DomainResult.Success).value
}
