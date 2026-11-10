package com.soundscape.app.core.domain.usecase

import com.soundscape.app.core.domain.model.*

internal fun validatePage(page: PageRequest, maxLimit: Int): DomainResult<Unit> {
    if (page.limit !in 1..maxLimit) return invalid("limit", "outside_range")
    if (page.offset < 0) return invalid("offset", "nonnegative")
    return DomainResult.Success(Unit)
}

internal fun validateIds(ids: List<Int>, field: String, maxCount: Int): DomainResult<Unit> {
    if (ids.size > maxCount) return invalid(field, "too_many")
    if (ids.any { it <= 0 }) return invalid(field, "positive_ids")
    if (ids.distinct().size != ids.size) return invalid(field, "duplicates")
    return DomainResult.Success(Unit)
}
