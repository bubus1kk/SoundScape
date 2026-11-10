package com.soundscape.app.core.domain.model

import kotlin.time.Duration

sealed interface DomainError {
    data class Validation(val field: String, val reason: String) : DomainError
    data object Unauthenticated : DomainError
    data object InvalidCredentials : DomainError
    data object Forbidden : DomainError
    data object NotFound : DomainError
    data object AlreadyExists : DomainError
    data object VersionConflict : DomainError
    data object Network : DomainError
    data object Timeout : DomainError
    data class RateLimited(val retryAfter: Duration? = null) : DomainError
    data object Server : DomainError
    data object PlaybackUnavailable : DomainError
    data object LocationPermissionDenied : DomainError
    data object LocationUnavailable : DomainError
    data object Unexpected : DomainError
}

internal fun invalid(field: String, reason: String) =
    DomainResult.Failure(DomainError.Validation(field, reason))

internal fun validateTitle(title: String): DomainResult<Unit> =
    if (title.isNotBlank() && title.length <= 150) DomainResult.Success(Unit)
    else invalid("title", "length_1_to_150")
