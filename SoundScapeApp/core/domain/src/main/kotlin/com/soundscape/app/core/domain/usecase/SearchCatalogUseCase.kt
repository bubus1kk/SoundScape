package com.soundscape.app.core.domain.usecase

import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.flatMap
import com.soundscape.app.core.domain.model.invalid
import com.soundscape.app.core.domain.model.Page
import com.soundscape.app.core.domain.model.PageRequest
import com.soundscape.app.core.domain.repository.GlobalSearch
import com.soundscape.app.core.domain.repository.GlobalSearchRepository
import com.soundscape.app.core.domain.repository.SearchHit

private const val MAX_SEARCH_OFFSET = 1000

/** Unified search over the five catalog categories. */
class SearchCatalogUseCase(private val repository: GlobalSearchRepository) {
    /** Validates and runs a page. History is managed by the explicit history methods on the port. */
    suspend operator fun invoke(
        request: GlobalSearch,
        page: PageRequest = PageRequest(),
    ): DomainResult<Page<SearchHit>> {
        val normalized = request.copy(query = request.query.trim())
        val validation = validate(normalized, page)
        if (validation is DomainResult.Failure) return validation
        return repository.search(normalized, page.limit, page.offset)
    }

    private fun validate(request: GlobalSearch, page: PageRequest): DomainResult<Unit> = when {
        request.query.length !in 1..MAX_SEARCH_QUERY_LENGTH -> invalid("query", "length_1_to_200")
        page.offset > MAX_SEARCH_OFFSET -> invalid("offset", "max_1000")
        else -> validatePage(page, MAX_SEARCH_PAGE_SIZE)
            .flatMap { validateIds(request.filters.selectedGenreIds, "genreIds", MAX_SEARCH_FACETS) }
            .flatMap { validateIds(request.filters.selectedMoodIds, "moodIds", MAX_SEARCH_FACETS) }
    }
}
