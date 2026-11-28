package com.soundscape.app.core.domain.model

import java.util.UUID

/** Account session visible to Domain without transport credentials. */
sealed interface AccountSession {
    /** No authenticated user is associated with the session. */
    data object Guest : AccountSession
    /** The session exceeded its validity period. */
    data object Expired : AccountSession
    /** The server has invalidated the session. */
    data object Revoked : AccountSession
    /** A valid session associated with an account and its role. */
    data class Active(val userId: UUID, val role: AccountRole = AccountRole.USER) : AccountSession
}

/** Role displayed from the server's account profile. */
enum class AccountRole { USER, EDITOR, ADMIN }

/** Reasons a session-dependent operation may fail. */
enum class SessionFailure { MISSING, EXPIRED, REVOKED }

/** Email verification intent. Recovery verification can establish a password reset session. */
enum class EmailVerificationPurpose { SIGN_UP, RECOVERY }

/** Proof from an email link or an email plus one-time code. */
sealed interface EmailVerificationProof {
    /** Proof extracted from an email verification link. */
    class LinkHash(val tokenHash: String) : EmailVerificationProof
    /** An email address and its one-time verification code. */
    class Code(val email: String, val code: String) : EmailVerificationProof
}

/** Registration result without exposing tokens. */
enum class RegistrationStatus { CONFIRMATION_REQUIRED, SIGNED_IN }

/** Account deletion lifecycle reported by the server. */
enum class AccountDeletionStatus { PENDING, DELETED }

/** Explicit avatar intent so editing the name keeps the existing avatar by default. */
sealed interface AvatarChange {
    /** Retains the currently selected avatar. */
    data object Keep : AvatarChange
    /** Clears the current avatar. */
    data object Remove : AvatarChange
    /** Selects a newly uploaded avatar reference. */
    data class Replace(val path: String) : AvatarChange
}

/** Profile edit fields supported by the account service. */
data class ProfileEdit(val displayName: String, val avatar: AvatarChange = AvatarChange.Keep)
