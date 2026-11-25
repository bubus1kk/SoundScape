package com.soundscape.app.presentation.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.soundscape.app.core.designsystem.ui.components.SoundScapeButton
import com.soundscape.app.core.designsystem.ui.components.SoundScapeButtonVariant
import com.soundscape.app.core.designsystem.ui.theme.SoundScapeTheme
import com.soundscape.app.core.domain.model.DomainError
import com.soundscape.app.core.domain.model.TrackAvailability
import com.soundscape.app.core.domain.repository.SearchCategory
import com.soundscape.app.core.domain.repository.SearchFilters
import com.soundscape.app.core.domain.repository.SearchHit
import com.soundscape.app.presentation.R
import java.util.UUID

private const val SEARCH_SKELETON_ROWS = 5

internal fun LazyListScope.searchHistory(state: GlobalSearchState, model: GlobalSearchViewModel) {
    item {
        SearchHeading(
            stringResource(R.string.search_recent),
            stringResource(R.string.search_clear).takeIf { state.history.isNotEmpty() },
            model::clearHistory,
        )
    }
    if (state.historyError) item { Text(stringResource(R.string.search_history_error)) }
    items(state.history, key = { it }) { query ->
        val spacing = SoundScapeTheme.spacing
        Row(Modifier.fillMaxWidth().clickable { model.query(query) }.heightIn(min = 52.dp),
            horizontalArrangement = Arrangement.spacedBy(spacing.space16),
            verticalAlignment = Alignment.CenterVertically) {
            SearchIcon(R.drawable.ic_history)
            Text(query, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            SearchIcon(R.drawable.ic_chevron_right)
        }
        HorizontalDivider(color = SoundScapeTheme.colors.surfaceAlt)
    }
    if (state.history.isEmpty())
        item {
            SearchMessage(
                stringResource(R.string.search_history_empty),
                stringResource(R.string.search_history_hint),
            )
        }
}

internal fun LazyListScope.searchFilters(
    state: GlobalSearchState, model: GlobalSearchViewModel, filtersOpen: Boolean, toggle: () -> Unit,
) {
    val filtered = state.filters != SearchFilters()
    item {
        val colors = SoundScapeTheme.colors
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.search_query_results, state.query.trim()),
                Modifier.weight(1f),
                color = colors.textMuted,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            SoundScapeButton(
                stringResource(R.string.search_filters),
                toggle,
                compact = true,
                variant = SoundScapeButtonVariant.Outlined,
                outlineColor = colors.outline,
                leadingIcon = { SearchIcon(R.drawable.ic_sliders_horizontal) },
            )
        }
    }
    if (filtersOpen) item { SearchFiltersCard(state, model) }
    if (filtered)
        item {
            Text(
                stringResource(R.string.search_filters_active),
                color = SoundScapeTheme.colors.primary,
                style = MaterialTheme.typography.labelMedium,
            )
        }
}

internal fun LazyListScope.searchResults(
    state: GlobalSearchState,
    model: GlobalSearchViewModel,
    offline: Boolean,
    openProfile: (SearchHit) -> Unit,
    playback: Pair<UUID, SearchPlayback>?,
    onTrack: (SearchHit) -> Unit,
    image: @Composable (SearchHit, Modifier) -> Unit,
) {
    val results = state.results?.items.orEmpty()
    val filtered = state.filters != SearchFilters()
    when {
        results.isEmpty() && state.loading -> item { SearchSkeleton() }
        results.isEmpty() && state.error != null -> item { SearchFailure(state, model::retry) }
        results.isEmpty() && state.results != null ->
            item {
                SearchMessage(
                    stringResource(R.string.search_empty),
                    if (filtered) stringResource(R.string.search_empty_filtered)
                    else stringResource(R.string.search_empty_hint),
                    stringResource(R.string.search_reset_filters).takeIf { filtered },
                    { model.filters(SearchFilters()) },
                )
            }
        results.isNotEmpty() -> searchPopulatedResults(state, model, offline, openProfile, playback, onTrack, image)
    }
    if (!state.loading && state.error == null && state.results?.hasMore == true)
        item {
            SoundScapeButton(
                stringResource(R.string.search_more),
                model::loadMore,
                Modifier.fillMaxWidth(),
                loading = state.paging,
            )
        }
}

private fun LazyListScope.searchPopulatedResults(
    state: GlobalSearchState,
    model: GlobalSearchViewModel,
    offline: Boolean,
    openProfile: (SearchHit) -> Unit,
    playback: Pair<UUID, SearchPlayback>?,
    onTrack: (SearchHit) -> Unit,
    image: @Composable (SearchHit, Modifier) -> Unit,
) {
    val results = state.results?.items.orEmpty()
    if (state.loading || state.error != null || offline)
        item { SearchStatus(state, offline, model::retry) }
    searchGroups(results, state.category, model::category, openProfile, playback, onTrack, image)
}

private fun LazyListScope.searchGroups(
    results: List<SearchHit>,
    currentCategory: SearchCategory?,
    select: (SearchCategory) -> Unit,
    openProfile: (SearchHit) -> Unit,
    playback: Pair<UUID, SearchPlayback>?,
    onTrack: (SearchHit) -> Unit,
    image: @Composable (SearchHit, Modifier) -> Unit,
) {
    SearchCategory.entries.forEach { category ->
        val group = results.filter { it.category == category }
        if (group.isNotEmpty()) {
            item(key = category.name) {
                SearchHeading(
                    category.heading(),
                    (if (category == SearchCategory.TRACK)
                            stringResource(R.string.search_all_tracks)
                        else stringResource(R.string.search_all))
                        .takeIf { currentCategory == null },
                ) {
                    select(category)
                }
            }
            items(group, key = { "${it.category}:${it.id}" }) { hit ->
                SearchResult(hit, openProfile, playback?.takeIf { it.first == hit.id }?.second,
                    onTrack, image)
            }
        }
    }
}

@Composable
internal fun SearchHeading(title: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        if (action != null) TextButton(onAction) { Text(action) }
    }
}

@Composable
private fun SearchResult(
    hit: SearchHit,
    openProfile: (SearchHit) -> Unit,
    playback: SearchPlayback?,
    onTrack: (SearchHit) -> Unit,
    image: @Composable (SearchHit, Modifier) -> Unit,
) {
    val spacing = SoundScapeTheme.spacing
    val colors = SoundScapeTheme.colors
    val opens = hit.category == SearchCategory.USER
    val shape = if (opens || hit.category == SearchCategory.ARTIST) CircleShape else MaterialTheme.shapes.extraSmall
    Surface(shape = MaterialTheme.shapes.small, color = colors.surface) {
        Row(
            Modifier.fillMaxWidth()
                .then(if (opens) Modifier.clickable { openProfile(hit) } else Modifier)
                .heightIn(min = 64.dp)
                .padding(spacing.space8),
            horizontalArrangement = Arrangement.spacedBy(spacing.space12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(spacing.space48).clip(shape).background(colors.primarySoft),
                contentAlignment = Alignment.Center,
            ) {
                SearchIcon(
                    when (hit.category) {
                        SearchCategory.TRACK -> R.drawable.ic_music
                        SearchCategory.PLAYLIST -> R.drawable.ic_library
                        SearchCategory.PLACE -> R.drawable.ic_map_pin
                        else -> R.drawable.ic_user
                    }
                )
                if (!hit.coverPath.isNullOrBlank()) image(hit, Modifier.matchParentSize())
            }
            Column(Modifier.weight(1f)) {
                Text(
                    hit.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                hit.subtitle?.takeIf(String::isNotBlank)?.let {
                    Text(it, color = colors.textMuted, style = MaterialTheme.typography.bodySmall,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                when (hit.availability) {
                    TrackAvailability.IN_APP ->
                        Text(
                            stringResource(R.string.search_in_app),
                            color = colors.primary,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    TrackAvailability.EXTERNAL_ONLY ->
                        Text(
                            stringResource(R.string.search_external),
                            color = colors.textMuted,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    else -> Unit
                }
            }
            SearchPlaybackAction(hit, playback, onTrack)
            if (opens) SearchIcon(R.drawable.ic_chevron_right)
        }
    }
}

@Composable
private fun SearchSkeleton() {
    val colors = SoundScapeTheme.colors
    val spacing = SoundScapeTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.space16)) {
        Text(stringResource(R.string.search_loading), style = MaterialTheme.typography.titleSmall)
        repeat(SEARCH_SKELETON_ROWS) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(spacing.space12),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(
                    Modifier.size(spacing.space48)
                        .background(colors.primarySoft, MaterialTheme.shapes.extraSmall)
                )
                Column(verticalArrangement = Arrangement.spacedBy(spacing.space8)) {
                    Spacer(Modifier.width(192.dp).height(spacing.space12).background(colors.primarySoft, CircleShape))
                    Spacer(Modifier.width(128.dp).height(spacing.space8).background(colors.surfaceAlt, CircleShape))
                }
            }
        }
    }
}

@Composable
private fun SearchStatus(state: GlobalSearchState, offline: Boolean, retry: () -> Unit) {
    val colors = SoundScapeTheme.colors
    Surface(shape = MaterialTheme.shapes.small, color = colors.primarySoft) {
        Column(Modifier.fillMaxWidth().padding(SoundScapeTheme.spacing.space12)) {
            val title = when {
                state.loading -> stringResource(R.string.search_refreshing)
                state.waitingForRetry -> stringResource(R.string.search_rate_short)
                offline || state.error == DomainError.Network -> stringResource(R.string.search_offline)
                else -> stringResource(R.string.search_refresh_failed)
            }
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.search_cached), style = MaterialTheme.typography.bodySmall)
            if (state.error != null)
                TextButton(retry, enabled = !state.waitingForRetry) {
                    Text(stringResource(R.string.search_retry))
                }
        }
    }
}

@Composable
private fun SearchCategory.heading() = when (this) {
    SearchCategory.TRACK -> stringResource(R.string.search_compositions)
    SearchCategory.PLACE -> stringResource(R.string.search_cities)
    else -> label()
}
