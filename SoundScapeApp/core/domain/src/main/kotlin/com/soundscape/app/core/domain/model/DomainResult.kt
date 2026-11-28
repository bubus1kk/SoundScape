package com.soundscape.app.core.domain.model

/** The outcome of a domain operation, carrying a value or a typed failure. */
sealed interface DomainResult<out T> {
    /** A completed operation and its value. */
    data class Success<T>(val value: T) : DomainResult<T>

    /** A failed operation and its business error. */
    data class Failure(val error: DomainError) : DomainResult<Nothing>
}

internal inline fun <T, R> DomainResult<T>.flatMap(transform: (T) -> DomainResult<R>): DomainResult<R> = when (this) {
    is DomainResult.Failure -> this
    is DomainResult.Success -> transform(value)
}
