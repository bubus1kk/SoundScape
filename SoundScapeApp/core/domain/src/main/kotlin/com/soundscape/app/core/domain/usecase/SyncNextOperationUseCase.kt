package com.soundscape.app.core.domain.usecase

import com.soundscape.app.core.domain.model.DomainError
import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.SessionFailure
import com.soundscape.app.core.domain.model.SyncClaim
import com.soundscape.app.core.domain.model.SyncOperation
import com.soundscape.app.core.domain.repository.AuthRepository
import com.soundscape.app.core.domain.repository.SyncOutbox
import java.util.UUID

/** Processes one outbox claim; scheduling and abandoned-claim recovery belong to adapters. */
class SyncNextOperationUseCase(
    private val auth: AuthRepository,
    private val outbox: SyncOutbox,
    private val apply: suspend (SyncOperation) -> DomainResult<Unit>,
) {
    /** Claims under the active account and persists the outcome before returning it. */
    suspend operator fun invoke(): DomainResult<Boolean> = when (val result = auth.currentUser()) {
        is DomainResult.Failure -> result
        is DomainResult.Success -> {
            val user = result.value
            if (user == null) DomainResult.Failure(DomainError.SessionUnavailable(SessionFailure.MISSING))
            else claimAndProcess(user.id)
        }
    }

    private suspend fun claimAndProcess(accountId: UUID): DomainResult<Boolean> {
        return when (val result = outbox.claim(accountId)) {
            is DomainResult.Failure -> result
            is DomainResult.Success -> {
                val claim = result.value
                when {
                    claim == null -> DomainResult.Success(false)
                    claim.operation.accountId != accountId -> DomainResult.Failure(DomainError.Forbidden)
                    else -> process(claim)
                }
            }
        }
    }

    private suspend fun process(claim: SyncClaim): DomainResult<Boolean> {
        val applied = apply(claim.operation)
        val stored = outbox.finish(claim, applied)
        return when {
            stored is DomainResult.Failure -> stored
            applied is DomainResult.Failure -> applied
            else -> DomainResult.Success(true)
        }
    }
}
