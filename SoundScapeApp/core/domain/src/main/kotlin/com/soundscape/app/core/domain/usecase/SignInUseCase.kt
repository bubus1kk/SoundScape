package com.soundscape.app.core.domain.usecase

import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.invalid
import com.soundscape.app.core.domain.model.UserProfile
import com.soundscape.app.core.domain.repository.AuthRepository

/** Signs in with an email/password account. */
class SignInUseCase(private val auth: AuthRepository) {
    /** Checks the email and delegates authentication to the account port. */
    suspend operator fun invoke(email: String, password: String): DomainResult<UserProfile> {
        val error = emailError(email)
        return when {
            error != null -> DomainResult.Failure(error)
            password.isEmpty() -> invalid("password", "required")
            else -> auth.signIn(email, password)
        }
    }
}
