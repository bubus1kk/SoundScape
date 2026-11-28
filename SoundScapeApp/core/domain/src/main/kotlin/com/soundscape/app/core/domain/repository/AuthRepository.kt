package com.soundscape.app.core.domain.repository

import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.UserProfile

/** Reads the account behind the current session and supports credential sign-in. */
interface AuthRepository {
    /** Returns the authenticated profile, or null for a guest with no session. */
    suspend fun currentUser(): DomainResult<UserProfile?>
    /** Signs in with email and password and returns the resulting profile. */
    suspend fun signIn(email: String, password: String): DomainResult<UserProfile>
}
