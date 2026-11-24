package com.soundscape.app.presentation.generation

import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.repository.GlobalSearch
import com.soundscape.app.core.domain.repository.GlobalSearchRepository
import com.soundscape.app.core.domain.repository.SearchCategory
import com.soundscape.app.core.domain.repository.SearchFacet
import com.soundscape.app.core.domain.repository.SearchFilters
import com.soundscape.app.core.domain.repository.SearchHit
import com.soundscape.app.core.domain.repository.TrackMetadata
import com.soundscape.app.core.domain.repository.TrackMetadataReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val LOOKUP_PAGE_SIZE = 20
internal const val MAX_LOOKUP_LENGTH = 200
private const val LOOKUP_DEBOUNCE_MS = 450L

internal class WorkshopCatalog(
    private val catalog: GlobalSearchRepository,
    private val tracks: TrackMetadataReader,
    private val mutable: MutableStateFlow<WorkshopState>,
    private val scope: CoroutineScope,
) {
    private val state get() = mutable.value
    private var metadataJob: Job? = null
    private var lookupJob: Job? = null
    private val optionJobs = mutableSetOf<SearchFacet>()
    private var lookupKey: Pair<SearchCategory, String>? = null
    private var lookupOffset = 0

    fun cancelMetadata() {
        metadataJob?.cancel()
    }

    fun refreshMetadata() {
        val value = state.draft ?: return
        val ids = value.tracks.map { it.id }.filterNot { it in state.metadata }
        metadataJob?.cancel()
        if (ids.isEmpty()) {
            mutable.update { it.copy(metadataLoading = false, metadataError = false) }
            return
        }
        mutable.update { it.copy(metadataLoading = true, metadataError = false) }
        metadataJob =
            scope.launch {
                when (val result = tracks.read(ids)) {
                    is DomainResult.Success ->
                        mutable.update {
                            it.copy(
                                metadataLoading = false,
                                metadata = it.metadata + result.value.associateBy(TrackMetadata::id),
                            )
                        }
                    is DomainResult.Failure ->
                        mutable.update { it.copy(metadataLoading = false, metadataError = true) }
                }
        }
    }

    fun loadOptions(facet: SearchFacet) {
        if (facet in state.options || !optionJobs.add(facet)) return
        scope.launch {
            try {
                when (val result = catalog.options(facet)) {
                    is DomainResult.Success -> mutable.update {
                        it.copy(options = it.options + (facet to result.value), optionErrors = it.optionErrors - facet)
                    }
                    is DomainResult.Failure -> mutable.update { it.copy(optionErrors = it.optionErrors + facet) }
                }
            } finally {
                optionJobs.remove(facet)
            }
        }
    }

    fun lookup(category: SearchCategory, query: String, more: Boolean = false, retry: Boolean = false) {
        val text = query.trim().take(MAX_LOOKUP_LENGTH)
        val key = category to text
        val previous = state.lookup
        val sameQuery = key == lookupKey
        val canLoadMore = sameQuery && previous.hasMore && lookupJob?.isActive != true
        if (if (more) !canLoadMore else sameQuery && !retry) return
        lookupJob?.cancel()
        lookupKey = key
        if (!more) lookupOffset = 0
        if (text.isEmpty()) {
            mutable.update { it.copy(lookup = LookupState()) }
            return
        }
        val offset = lookupOffset
        mutable.update {
            it.copy(
                lookup =
                    if (more) previous.copy(loading = true, error = null)
                    else LookupState(loading = true)
            )
        }
        lookupJob =
            scope.launch {
                delay(LOOKUP_DEBOUNCE_MS)
                val request = GlobalSearch(text, category, SearchFilters())
                when (val result = catalog.search(request, LOOKUP_PAGE_SIZE, offset)) {
                    is DomainResult.Success -> {
                        lookupOffset = offset + LOOKUP_PAGE_SIZE
                        val prefix = if (more) previous.items else emptyList()
                        mutable.update {
                            it.copy(
                                lookup =
                                    LookupState(
                                        (prefix + result.value.items).distinctBy(SearchHit::id),
                                        hasMore = result.value.hasMore,
                                    )
                            )
                        }
                    }
                    is DomainResult.Failure ->
                        mutable.update {
                            it.copy(lookup = it.lookup.copy(loading = false, error = result.error))
                        }
                }
            }
    }

    fun closeLookup() {
        lookupJob?.cancel()
        lookupKey = null
        mutable.update { it.copy(lookup = LookupState()) }
    }
}
