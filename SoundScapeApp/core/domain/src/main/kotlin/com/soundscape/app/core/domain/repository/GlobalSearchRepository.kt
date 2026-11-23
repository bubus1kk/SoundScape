package com.soundscape.app.core.domain.repository

import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.Page
import com.soundscape.app.core.domain.model.TrackAvailability
import java.util.UUID
import kotlinx.coroutines.flow.Flow

enum class SearchCategory { TRACK, ARTIST, PLAYLIST, PLACE, USER }

data class SearchFilters(val genreId: Int? = null, val moodId: Int? = null, val onlyInApp: Boolean = false)

data class GlobalSearch(val query: String, val category: SearchCategory?, val filters: SearchFilters)

data class SearchHit(
    val category: SearchCategory,
    val id: UUID,
    val title: String,
    val subtitle: String?,
    val coverPath: String?,
    val availability: TrackAvailability?,
)

data class SearchOption(val id: Int, val name: String)

enum class SearchFacet { Genre, Mood }

interface GlobalSearchRepository {
    val history: Flow<List<String>>
    suspend fun rememberQuery(query: String)
    suspend fun clearHistory()
    suspend fun search(request: GlobalSearch, limit: Int, offset: Int): DomainResult<Page<SearchHit>>
    suspend fun options(facet: SearchFacet): DomainResult<List<SearchOption>>
}
