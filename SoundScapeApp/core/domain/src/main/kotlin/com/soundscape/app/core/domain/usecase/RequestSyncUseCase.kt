package com.soundscape.app.core.domain.usecase

import com.soundscape.app.core.domain.model.*
import com.soundscape.app.core.domain.repository.*

class RequestSyncUseCase(private val auth: AuthRepository, private val sync: SyncRequester) {
    suspend operator fun invoke(): DomainResult<SyncRequestStatus> {
        val user = auth.currentUser()
        if (user is DomainResult.Failure) return user
        if ((user as DomainResult.Success).value == null) return DomainResult.Failure(DomainError.Unauthenticated)
        return sync.requestSync()
    }
}
