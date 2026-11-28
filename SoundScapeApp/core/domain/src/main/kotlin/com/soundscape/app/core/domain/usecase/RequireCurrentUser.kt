package com.soundscape.app.core.domain.usecase

import com.soundscape.app.core.domain.model.DomainError
import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.UserProfile
import com.soundscape.app.core.domain.model.flatMap
import com.soundscape.app.core.domain.repository.AuthRepository

internal suspend fun AuthRepository.requireCurrentUser(): DomainResult<UserProfile> = currentUser().flatMap { profile ->
    if (profile == null) DomainResult.Failure(DomainError.Unauthenticated) else DomainResult.Success(profile)
}
