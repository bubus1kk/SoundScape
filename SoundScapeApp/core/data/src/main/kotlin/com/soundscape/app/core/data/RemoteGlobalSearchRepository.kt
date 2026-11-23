package com.soundscape.app.core.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.soundscape.app.core.domain.model.DomainError
import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.Page
import com.soundscape.app.core.domain.model.TrackAvailability
import com.soundscape.app.core.domain.repository.GlobalSearch
import com.soundscape.app.core.domain.repository.GlobalSearchRepository
import com.soundscape.app.core.domain.repository.SearchCategory
import com.soundscape.app.core.domain.repository.SearchFacet
import com.soundscape.app.core.domain.repository.SearchHit
import com.soundscape.app.core.domain.repository.SearchOption
import com.soundscape.app.core.network.GlobalSearchApi
import com.soundscape.app.core.network.GlobalSearchRequestDto
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.searchStore by preferencesDataStore(name = "global_search")
private val historyKey = stringPreferencesKey("history")
private const val HISTORY_LIMIT = 20

class RemoteGlobalSearchRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val api: Lazy<GlobalSearchApi>,
    private val remote: RemoteCall,
) : GlobalSearchRepository {
    private val store = context.searchStore
    override val history = store.data.map { decodeHistory(it[historyKey]) }.catch {
        if (it is IOException) emitAll(flowOf(emptyList())) else throw it
    }

    override suspend fun rememberQuery(query: String) {
        store.edit { preferences ->
            val recent = decodeHistory(preferences[historyKey]).filterNot { it.equals(query, ignoreCase = true) }
            preferences[historyKey] = Json.encodeToString((listOf(query) + recent).take(HISTORY_LIMIT))
        }
    }

    override suspend fun clearHistory() {
        store.edit { it.remove(historyKey) }
    }

    override suspend fun search(request: GlobalSearch, limit: Int, offset: Int): DomainResult<Page<SearchHit>> {
        if (request.query.length !in 1..200 || limit !in 1..50 || offset !in 0..1000) {
            return DomainResult.Failure(DomainError.Validation("search", "outside_range"))
        }
        val dto = request.toDto(limit, offset)
        return remote.execute({ api.get().search(dto) }) { response ->
            require(response.limit == limit && response.offset == offset && response.items.size <= limit)
            Page(response.items.map {
                SearchHit(SearchCategory.valueOf(it.category), UUID.fromString(it.id), it.title, it.subtitle,
                    it.coverPath, it.availability?.let(TrackAvailability::valueOf))
            }, response.hasMore && response.items.isNotEmpty() && offset + limit <= 1000)
        }
    }

    override suspend fun options(facet: SearchFacet): DomainResult<List<SearchOption>> =
        remote.execute({ if (facet == SearchFacet.Genre) api.get().genres() else api.get().moods() }) { rows ->
            rows.map { SearchOption(it.id, it.name) }
        }

    private fun GlobalSearch.toDto(limit: Int, offset: Int) = GlobalSearchRequestDto(
        query, categories = category?.let { listOf(it.name) }, genreIds = listOfNotNull(filters.genreId), moodIds = listOfNotNull(filters.moodId),
        onlyInApp = filters.onlyInApp, limit = limit, offset = offset,
    )

    private fun decodeHistory(raw: String?): List<String> =
        raw?.let { runCatching { Json.decodeFromString<List<String>>(it) }.getOrDefault(emptyList()) }.orEmpty()
}
