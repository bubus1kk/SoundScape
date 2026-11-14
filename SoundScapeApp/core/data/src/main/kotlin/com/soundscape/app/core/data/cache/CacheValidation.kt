package com.soundscape.app.core.data.cache

import com.soundscape.app.core.domain.model.DomainError
import com.soundscape.app.core.domain.model.MusicSearch
import com.soundscape.app.core.domain.model.PageRequest

private const val MAP_LIMIT = 100
private const val SEARCH_LIMIT = 50
private const val MAX_OFFSET = 1000
private const val MAX_QUERY = 200
private const val MAX_FILTERS = 20
private const val MAX_FILTER_ID = 32767

internal fun validateMap(page: PageRequest): DomainError? =
    if (page.limit !in 1..MAP_LIMIT || page.offset < 0) DomainError.Validation("page", "outside_range") else null

internal fun validateSearch(search: MusicSearch): DomainError? = when {
    search.page.offset !in 0..MAX_OFFSET || search.page.limit !in 1..SEARCH_LIMIT ->
        DomainError.Validation("page", "outside_range")
    search.query.isBlank() || search.query.length > MAX_QUERY -> DomainError.Validation("query", "length_1_to_200")
    else -> listOf("genreIds" to search.genreIds, "moodIds" to search.moodIds).firstOrNull { (_, ids) ->
        ids.size > MAX_FILTERS || ids.any { it !in 1..MAX_FILTER_ID } || ids.distinct().size != ids.size
    }?.let { DomainError.Validation(it.first, "invalid_ids") }
}
