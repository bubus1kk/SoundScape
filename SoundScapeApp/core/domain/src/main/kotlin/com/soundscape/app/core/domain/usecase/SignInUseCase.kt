package com.soundscape.app.core.domain.usecase

import com.soundscape.app.core.domain.model.*
import com.soundscape.app.core.domain.repository.*

class SignInUseCase(private val auth: AuthRepository) {
    suspend operator fun invoke(email: String, password: String): DomainResult<UserProfile> {
        val parts = email.split('@')
        if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank() || email.any { it.isWhitespace() } ||
            !parts[1].contains('.') || parts[1].startsWith('.') || parts[1].endsWith('.')) {
            return invalid("email", "invalid_email")
        }
        if (password.isEmpty()) return invalid("password", "required")
        return auth.signIn(email, password)
    }
}
