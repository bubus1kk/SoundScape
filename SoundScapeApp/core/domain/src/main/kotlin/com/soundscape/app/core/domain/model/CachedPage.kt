package com.soundscape.app.core.domain.model

import java.time.Instant

data class CachedPage<T>(val page: Page<T>, val fetchedAt: Instant)

data class CachedPageState<T>(
    val cached: CachedPage<T>? = null,
    val refreshing: Boolean = false,
    val refreshError: DomainError? = null,
)
