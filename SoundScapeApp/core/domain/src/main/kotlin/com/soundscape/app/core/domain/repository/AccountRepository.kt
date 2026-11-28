package com.soundscape.app.core.domain.repository

import com.soundscape.app.core.domain.model.AccountDeletionStatus
import com.soundscape.app.core.domain.model.AccountSession
import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.EmailVerificationProof
import com.soundscape.app.core.domain.model.EmailVerificationPurpose
import com.soundscape.app.core.domain.model.MediaReference
import com.soundscape.app.core.domain.model.ProfileEdit
import com.soundscape.app.core.domain.model.RegistrationStatus
import com.soundscape.app.core.domain.model.UserProfile
import kotlinx.coroutines.flow.StateFlow

/** Full email account lifecycle. The adapter owns credential storage and transport. */
interface AccountRepository : AuthRepository {
    val session: StateFlow<AccountSession>

    /** Registers with email and password. */
    suspend fun register(email: String, password: String): DomainResult<RegistrationStatus>

    /** Confirms a signup or recovery challenge. */
    suspend fun verifyEmail(
        purpose: EmailVerificationPurpose,
        proof: EmailVerificationProof,
    ): DomainResult<AccountSession>

    /** Requests account recovery. */
    suspend fun recover(email: String): DomainResult<Unit>

    /** Sets a password in the authorized account or recovery session. */
    suspend fun changePassword(password: String): DomainResult<Unit>

    /** Renews a session or returns its typed failure. */
    suspend fun refresh(): DomainResult<AccountSession>

    /** Signs out and clears the adapter's local session. */
    suspend fun logout(): DomainResult<Unit>

    /** Requests irreversible account deletion after explicit confirmation. */
    suspend fun deleteAccount(): DomainResult<AccountDeletionStatus>
}

/** Authenticated profile edits, including an avatar previously uploaded for this account. */
interface ProfileEditor {
    /** Updates the current profile. */
    suspend fun update(edit: ProfileEdit): DomainResult<UserProfile>

    /** Uploads a selected image to the current account's avatar storage. */
    suspend fun uploadAvatar(image: MediaReference): DomainResult<String>
}
