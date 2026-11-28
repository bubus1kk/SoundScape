package com.soundscape.app.core.domain.usecase

import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.PageRequest
import com.soundscape.app.core.domain.model.invalid

internal const val MAX_PLACE_PAGE_SIZE = 100
internal const val MAX_SEARCH_QUERY_LENGTH = 200
internal const val MAX_SEARCH_PAGE_SIZE = 50
internal const val MAX_SEARCH_FACETS = 20

internal fun validatePage(page: PageRequest, maxLimit: Int): DomainResult<Unit> = when {
    page.limit !in 1..maxLimit -> invalid("limit", "outside_range")
    page.offset < 0 -> invalid("offset", "nonnegative")
    else -> DomainResult.Success(Unit)
}

internal fun validateIds(ids: List<Int>, field: String, maxCount: Int): DomainResult<Unit> = when {
    ids.size > maxCount -> invalid(field, "too_many")
    ids.any { it <= 0 } -> invalid(field, "positive_ids")
    ids.any { it > Short.MAX_VALUE } -> invalid(field, "outside_range")
    ids.distinct().size != ids.size -> invalid(field, "duplicates")
    else -> DomainResult.Success(Unit)
}

internal fun validateExpectedRevision(revision: Long?): DomainResult<Unit> =
    if (revision != null && revision < 1) invalid("expectedRevision", "positive") else DomainResult.Success(Unit)
