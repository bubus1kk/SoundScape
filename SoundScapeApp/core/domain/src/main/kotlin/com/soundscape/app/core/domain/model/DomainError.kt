package com.soundscape.app.core.domain.model

import java.util.UUID
import kotlin.time.Duration

/** Failure semantics shared by use cases and adapters. */
sealed interface DomainError {
    /** An authenticated session is unavailable for the stated reason. */
    data class SessionUnavailable(val reason: SessionFailure) : DomainError
    /** A write uses a stale revision, with versions included when known. */
    data class RevisionMismatch(val expected: Long? = null, val actual: Long? = null) : DomainError
    /** A key was reused for a different request. */
    data class IdempotencyConflict(val key: UUID? = null) : DomainError
    /** A music source is temporarily unavailable and may supply a retry interval. */
    data class SourceUnavailable(val retryAfter: Duration? = null) : DomainError
    /** The timed playback source for the track has expired. */
    data class PlaybackSourceExpired(val trackId: UUID) : DomainError
    /** No eligible candidates were found. */
    data object NoCandidates : DomainError
    /** Shake discovery is unavailable for the given device access state. */
    data class ShakeUnavailable(val access: DeviceAccess) : DomainError
    /** The generated draft violates a generation rule. */
    data class InvalidGeneration(val reason: GenerationResultIssue) : DomainError
    /** The supplied share reference or proof is invalid. */
    data object InvalidShare : DomainError
    /** The share has passed its expiry time. */
    data object ExpiredShare : DomainError
    /** A field failed validation for the stated reason. */
    data class Validation(val field: String, val reason: String) : DomainError
    /** The action requires an authenticated account. */
    data object Unauthenticated : DomainError
    /** The credentials did not authenticate the user. */
    data object InvalidCredentials : DomainError
    /** The user is authenticated but lacks permission for the requested action. */
    data object Forbidden : DomainError
    /** The requested resource could not be found in the caller's access context. */
    data object NotFound : DomainError
    /** A resource already occupies the requested identity. */
    data object AlreadyExists : DomainError
    /** The resource revision changed before the write. */
    data object VersionConflict : DomainError
    /** The request failed at the network boundary. */
    data object Network : DomainError
    /** The operation exceeded its available time. */
    data object Timeout : DomainError
    /** The service is throttling requests and may specify when to retry. */
    data class RateLimited(val retryAfter: Duration? = null) : DomainError
    /** The remote service failed to complete the operation. */
    data object Server : DomainError
    /** A compatible playback source could not be used. */
    data object PlaybackUnavailable : DomainError
    /** The platform denied permission to read location. */
    data object LocationPermissionDenied : DomainError
    /** The platform could not provide a location fix. */
    data object LocationUnavailable : DomainError
    /** The operation failed outside the known business cases. */
    data object Unexpected : DomainError
}

internal fun invalid(field: String, reason: String) =
    DomainResult.Failure(DomainError.Validation(field, reason))

internal fun validateTitle(title: String): DomainResult<Unit> =
    if (title.isNotBlank() && title.length <= MAX_TITLE_LENGTH) DomainResult.Success(Unit)
    else invalid("title", "length_1_to_150")
