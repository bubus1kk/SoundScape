package com.soundscape.app.core.domain.model

import java.time.Instant

/** Page contents paired with the time they were fetched. */
data class CachedPage<T>(val page: Page<T>, val fetchedAt: Instant)

/** Observable cache contents and the state of their refresh. */
data class CachedPageState<T>(
    val cached: CachedPage<T>? = null,
    val refreshing: Boolean = false,
    val refreshError: DomainError? = null,
)
