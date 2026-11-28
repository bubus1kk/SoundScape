package com.soundscape.app.core.domain.model

import java.util.UUID

/** Stable identity and concurrency precondition for one mutation. */
data class MutationIdentity(
    val accountId: UUID,
    val objectId: UUID,
    val idempotencyKey: UUID,
    val expectedRevision: Long? = null,
) {
    init { require(expectedRevision == null || expectedRevision > 0) }
}

/** Payload snapshot retained until the user or a successful sync resolves it. */
sealed interface OfflineEdit {
    /** A queued playlist edit. */
    data class Playlist(val input: PlaylistInput) : OfflineEdit
    /** A queued music pin edit. */
    data class Pin(val input: MusicPinInput) : OfflineEdit
    /** A queued diary entry edit. */
    data class Diary(val input: DiaryInput) : OfflineEdit
    /** A queued musical journey edit. */
    data class Journey(val input: JourneyInput) : OfflineEdit
    /** A queued deletion for the selected content kind. */
    data class Delete(val kind: ShareKind) : OfflineEdit
}

/** Pending writes and their typed outcomes. */
sealed interface SyncOperationState {
    /** Ready to be claimed for synchronization. */
    data object Pending : SyncOperationState
    /** Claimed by a synchronization attempt. */
    data object Running : SyncOperationState
    /** Failed with a condition that permits another attempt. */
    data class Retryable(val error: DomainError) : SyncOperationState
    /** Failed permanently for this request and retains its payload. */
    data class Rejected(val error: DomainError) : SyncOperationState
    /** The stored revision changed and requires an explicit resolution. */
    data class Conflict(val conflict: DomainError.RevisionMismatch) : SyncOperationState
}

/** Explicit user decisions. Keeping local changes requires a new operation key. */
sealed interface ConflictChoice {
    /** Resolves the conflict by keeping the remote content. */
    data object KeepRemote : ConflictChoice
    /** Resubmits the local content with a new key and the selected remote revision. */
    data class KeepLocal(val newIdempotencyKey: UUID, val remoteRevision: Long) : ConflictChoice
}

/** Current operation within one account's outbox. */
data class SyncOperation(
    val identity: MutationIdentity,
    val edit: OfflineEdit,
    val state: SyncOperationState = SyncOperationState.Pending,
) {
    init { require(edit !is OfflineEdit.Delete || identity.expectedRevision != null) }

    val accountId: UUID get() = identity.accountId

    /** Moves a ready operation to running while keeping the original request. */
    fun start(): DomainResult<SyncOperation> = when (state) {
        SyncOperationState.Pending, is SyncOperationState.Retryable ->
            DomainResult.Success(copy(state = SyncOperationState.Running))
        else -> invalid("state", "operation_not_ready")
    }

    /** Records an outcome without discarding the local edit. */
    fun fail(error: DomainError): DomainResult<SyncOperation> {
        if (state != SyncOperationState.Running) return invalid("state", "operation_not_running")
        val next = when (error) {
            is DomainError.RevisionMismatch -> SyncOperationState.Conflict(error)
            DomainError.VersionConflict ->
                SyncOperationState.Conflict(DomainError.RevisionMismatch(identity.expectedRevision))
            DomainError.Network, DomainError.Timeout, is DomainError.RateLimited, DomainError.Server,
            is DomainError.SourceUnavailable, is DomainError.SessionUnavailable, DomainError.Unauthenticated ->
                SyncOperationState.Retryable(error)
            else -> SyncOperationState.Rejected(error)
        }
        return DomainResult.Success(copy(state = next))
    }

    /** Returns a replacement operation or an explicit decision to adopt the remote version. */
    fun resolve(choice: ConflictChoice): DomainResult<SyncOperation?> {
        val conflict = state as? SyncOperationState.Conflict ?: return invalid("state", "operation_has_no_conflict")
        return when (choice) {
            ConflictChoice.KeepRemote -> DomainResult.Success(null)
            is ConflictChoice.KeepLocal -> rebase(choice, conflict.conflict)
        }
    }

    private fun rebase(
        choice: ConflictChoice.KeepLocal,
        conflict: DomainError.RevisionMismatch,
    ): DomainResult<SyncOperation> {
        val revision = choice.remoteRevision
        return when {
            choice.newIdempotencyKey == identity.idempotencyKey ->
                DomainResult.Failure(DomainError.IdempotencyConflict(choice.newIdempotencyKey))
            revision <= 0 || (conflict.actual != null && revision < conflict.actual) ->
                DomainResult.Failure(DomainError.RevisionMismatch(revision, conflict.actual))
            else -> DomainResult.Success(copy(
                identity = identity.copy(idempotencyKey = choice.newIdempotencyKey, expectedRevision = revision),
                state = SyncOperationState.Pending,
            ))
        }
    }
}

/** Whether submissions sharing this account and key also share the original mutation. */
fun SyncOperation.checkReplay(incoming: SyncOperation): DomainResult<Unit> {
    if (accountId != incoming.accountId || identity.idempotencyKey != incoming.identity.idempotencyKey) {
        return invalid("idempotencyKey", "different_scope_or_key")
    }
    return if (identity == incoming.identity && edit == incoming.edit) DomainResult.Success(Unit)
    else DomainResult.Failure(DomainError.IdempotencyConflict(identity.idempotencyKey))
}

/** One execution claim. A late worker cannot finish a newer claim after recovery. */
data class SyncClaim(val attemptId: UUID, val operation: SyncOperation)

/** Local synchronization progress independent of Android scheduling. */
data class SyncProgress(val pending: Int, val running: Int, val conflicts: Int, val rejected: Int, val retryable: Int)
