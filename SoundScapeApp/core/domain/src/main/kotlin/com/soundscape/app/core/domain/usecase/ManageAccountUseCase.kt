package com.soundscape.app.core.domain.usecase

import com.soundscape.app.core.domain.model.AccountDeletionStatus
import com.soundscape.app.core.domain.model.AccountSession
import com.soundscape.app.core.domain.model.DomainError
import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.EmailVerificationProof
import com.soundscape.app.core.domain.model.EmailVerificationPurpose
import com.soundscape.app.core.domain.model.invalid
import com.soundscape.app.core.domain.model.ProfileEdit
import com.soundscape.app.core.domain.model.profileNameError
import com.soundscape.app.core.domain.model.RegistrationStatus
import com.soundscape.app.core.domain.model.UserProfile
import com.soundscape.app.core.domain.repository.AccountRepository
import com.soundscape.app.core.domain.repository.ProfileEditor

/** Registration, restoration and account deletion through the email account service. */
class ManageAccountUseCase(private val account: AccountRepository) {
    /** Registers a new account using the server's password policy. */
    suspend fun register(email: String, password: String): DomainResult<RegistrationStatus> {
        val error = emailError(email)
        return when {
            error != null -> DomainResult.Failure(error)
            password.isEmpty() -> invalid("password", "required")
            else -> account.register(email, password)
        }
    }

    /** Requests password recovery at the given address. */
    suspend fun recover(email: String): DomainResult<Unit> =
        emailError(email)?.let { DomainResult.Failure(it) } ?: account.recover(email)

    /** Verifies a signup or recovery challenge. */
    suspend fun verify(
        purpose: EmailVerificationPurpose,
        proof: EmailVerificationProof,
    ): DomainResult<AccountSession> {
        val validation = when (proof) {
            is EmailVerificationProof.LinkHash -> validateProof(proof.tokenHash)
            is EmailVerificationProof.Code -> emailError(proof.email)?.let { DomainResult.Failure(it) }
                ?: validateProof(proof.code)
        }
        if (validation is DomainResult.Failure) return validation
        return account.verifyEmail(purpose, proof)
    }

    private fun validateProof(proof: String): DomainResult<Unit> =
        if (proof.isBlank()) invalid("proof", "required") else DomainResult.Success(Unit)

    /** Applies a password within the authorized session. */
    suspend fun changePassword(password: String): DomainResult<Unit> =
        if (password.isEmpty()) invalid("password", "required") else account.changePassword(password)

    /** Requests deletion only after explicit confirmation. */
    suspend fun delete(confirmed: Boolean): DomainResult<AccountDeletionStatus> =
        if (!confirmed) invalid("confirmation", "required") else account.deleteAccount()
}

/** Validates profile input before applying authorized changes. */
class EditProfileUseCase(private val editor: ProfileEditor) {
    /** Validates and normalizes the profile name before saving. */
    suspend operator fun invoke(edit: ProfileEdit): DomainResult<UserProfile> {
        val nameError = profileNameError(edit.displayName)
        if (nameError != null) return DomainResult.Failure(nameError)
        return editor.update(edit.copy(displayName = edit.displayName.trim()))
    }
}

/** Matches the existing email input validation for both sign-in and recovery. */
internal fun emailError(email: String): DomainError.Validation? {
    val parts = email.split('@')
    val valid = parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank() &&
        email.none { it.isWhitespace() } && '.' in parts[1] && !parts[1].startsWith('.') && !parts[1].endsWith('.')
    return if (valid) null else DomainError.Validation("email", "invalid_email")
}
