package com.soundscape.app.core.domain.usecase

import com.soundscape.app.core.domain.model.*
import com.soundscape.app.core.domain.repository.*

class SearchMusicUseCase(private val catalog: TrackCatalog) {
    suspend operator fun invoke(input: MusicSearch): DomainResult<Page<Track>> {
        if (input.query.isBlank() || input.query.length > 200) return invalid("query", "length_1_to_200")
        val page = validatePage(input.page, 50)
        if (page is DomainResult.Failure) return page
        val genres = validateIds(input.genreIds, "genreIds", 20)
        if (genres is DomainResult.Failure) return genres
        val moods = validateIds(input.moodIds, "moodIds", 20)
        if (moods is DomainResult.Failure) return moods
        return catalog.search(input)
    }
}
