package com.soundscape.app.core.domain.model

import java.time.Instant

/** A successfully persisted page, including successful empty results. */
data class CachedPage<T>(val page: Page<T>, val fetchedAt: Instant)

/** Null cached means not loaded; refresh errors never replace existing content. */
data class CachedPageState<T>(
    val cached: CachedPage<T>? = null,
    val refreshing: Boolean = false,
    val refreshError: DomainError? = null,
)
