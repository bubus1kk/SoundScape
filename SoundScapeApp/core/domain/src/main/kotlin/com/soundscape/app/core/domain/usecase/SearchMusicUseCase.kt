package com.soundscape.app.core.domain.usecase

import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.MusicSearch
import com.soundscape.app.core.domain.model.Page
import com.soundscape.app.core.domain.model.Track
import com.soundscape.app.core.domain.model.flatMap
import com.soundscape.app.core.domain.model.invalid
import com.soundscape.app.core.domain.repository.TrackSearcher

/** Searches the track catalog with explicit availability and facet filters. */
class SearchMusicUseCase(private val catalog: TrackSearcher) {
    /** Validates the query, page and facet IDs before issuing the search. */
    suspend operator fun invoke(input: MusicSearch): DomainResult<Page<Track>> {
        if (input.query.isBlank() || input.query.length > MAX_SEARCH_QUERY_LENGTH) {
            return invalid("query", "length_1_to_200")
        }
        return validatePage(input.page, MAX_SEARCH_PAGE_SIZE)
            .flatMap { validateIds(input.genreIds, "genreIds", MAX_SEARCH_FACETS) }
            .flatMap { validateIds(input.moodIds, "moodIds", MAX_SEARCH_FACETS) }
            .flatMap { catalog.search(input) }
    }
}
