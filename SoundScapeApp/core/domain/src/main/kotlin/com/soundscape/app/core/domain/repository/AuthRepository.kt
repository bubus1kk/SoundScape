package com.soundscape.app.core.domain.repository

import com.soundscape.app.core.domain.model.*

interface AuthRepository {
    suspend fun currentUser(): DomainResult<UserProfile?>
    suspend fun signIn(email: String, password: String): DomainResult<UserProfile>
}
