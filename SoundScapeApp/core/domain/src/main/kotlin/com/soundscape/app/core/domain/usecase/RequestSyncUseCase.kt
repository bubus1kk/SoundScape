package com.soundscape.app.core.domain.usecase

import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.SyncRequestStatus
import com.soundscape.app.core.domain.model.flatMap
import com.soundscape.app.core.domain.repository.AuthRepository
import com.soundscape.app.core.domain.repository.SyncRequester

/** Requests synchronization for a signed-in user. */
class RequestSyncUseCase(private val auth: AuthRepository, private val sync: SyncRequester) {
    /** Checks the current session before submitting the request. */
    suspend operator fun invoke(): DomainResult<SyncRequestStatus> =
        auth.requireCurrentUser().flatMap { sync.requestSync() }
}
