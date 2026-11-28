package com.soundscape.app.core.domain.repository

import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.Page
import com.soundscape.app.core.domain.model.TrackAvailability
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/** Five unified search categories. */
enum class SearchCategory { TRACK, ARTIST, PLAYLIST, PLACE, USER }

/** Filters available to a unified search request. */
data class SearchFilters(val genreId: Int? = null, val moodId: Int? = null, val onlyInApp: Boolean = false,
    val genreIds: List<Int> = emptyList(), val moodIds: List<Int> = emptyList(),
) {
    /** Effective genre filter, including existing single-facet callers. */
    val selectedGenreIds: List<Int> get() = genreIds.ifEmpty { listOfNotNull(genreId) }
    /** Effective mood filter, including existing single-facet callers. */
    val selectedMoodIds: List<Int> get() = moodIds.ifEmpty { listOfNotNull(moodId) }
}

/** Search request. An omitted category means all five categories. */
data class GlobalSearch(val query: String, val category: SearchCategory?, val filters: SearchFilters,
    val includePublicMusicPins: Boolean = false,
)

/** The subtype in the PLACE results when public pins are requested. */
enum class SearchPlaceKind { PLACE, PIN }

/** Stable search result with target identity and optional track status. */
data class SearchHit(
    val category: SearchCategory,
    val id: UUID,
    val title: String,
    val subtitle: String?,
    val coverPath: String?,
    val availability: TrackAvailability?,
    val placeKind: SearchPlaceKind? = null,
)

/** Selectable catalog facet value. */
data class SearchOption(val id: Int, val name: String)

/** Supported catalog facet dictionaries. */
enum class SearchFacet { Genre, Mood }

/** Server-backed catalog search plus local search history. */
interface GlobalSearchRepository {
    val history: Flow<List<String>>
    /** Records a submitted query locally. */
    suspend fun rememberQuery(query: String)
    /** Clears local search history. */
    suspend fun clearHistory()
    /** Pages unified results across the selected catalog categories. */
    suspend fun search(request: GlobalSearch, limit: Int, offset: Int): DomainResult<Page<SearchHit>>
    /** Lists the requested facet dictionary. */
    suspend fun options(facet: SearchFacet): DomainResult<List<SearchOption>>
}
