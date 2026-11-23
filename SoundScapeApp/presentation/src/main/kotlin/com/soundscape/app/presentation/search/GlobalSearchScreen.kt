package com.soundscape.app.presentation.search

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.soundscape.app.core.designsystem.ui.components.SoundScapeButton
import com.soundscape.app.core.designsystem.ui.components.SoundScapeButtonVariant
import com.soundscape.app.core.designsystem.ui.theme.SoundScapeTheme
import com.soundscape.app.core.domain.model.DomainError
import com.soundscape.app.core.domain.model.TrackAvailability
import com.soundscape.app.core.domain.repository.SearchCategory
import com.soundscape.app.core.domain.repository.SearchFacet
import com.soundscape.app.core.domain.repository.SearchFilters
import com.soundscape.app.core.domain.repository.SearchHit
import com.soundscape.app.presentation.R
import com.soundscape.app.presentation.profile.ProfileHeader

@Composable
fun GlobalSearchScreen(
    state: GlobalSearchState,
    model: GlobalSearchViewModel,
    offline: Boolean,
    back: () -> Unit,
    openProfile: (SearchHit) -> Unit,
    image: @Composable (SearchHit, Modifier) -> Unit,
) {
    var filtersOpen by rememberSaveable { mutableStateOf(false) }
    val spacing = SoundScapeTheme.spacing
    val colors = SoundScapeTheme.colors
    val results = state.results?.items.orEmpty()
    val filtered = state.filters != SearchFilters()
    val list = rememberLazyListState()
    LaunchedEffect(state.query.trim(), state.category, state.filters) { list.scrollToItem(0) }
    Column(Modifier.fillMaxSize().imePadding().background(colors.background)) {
        ProfileHeader(R.string.search_title, back, compact = true)
        SearchInput(state, model::query, model::category)
        LazyColumn(state = list, contentPadding = PaddingValues(spacing.space16),
            verticalArrangement = Arrangement.spacedBy(spacing.space8)) {
            if (state.query.isBlank()) {
                item {
                    SearchHeading(stringResource(R.string.search_recent), stringResource(R.string.search_clear).takeIf { state.history.isNotEmpty() }, model::clearHistory)
                }
                if (state.historyError) item { Text(stringResource(R.string.search_history_error)) }
                items(state.history, key = { it }) { query ->
                    Row(Modifier.fillMaxWidth().clickable { model.query(query) }.heightIn(min = 52.dp),
                        horizontalArrangement = Arrangement.spacedBy(spacing.space16),
                        verticalAlignment = Alignment.CenterVertically) {
                        SearchIcon(R.drawable.ic_history)
                        Text(query, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        SearchIcon(R.drawable.ic_chevron_right)
                    }
                    HorizontalDivider(color = colors.surfaceAlt)
                }
                if (state.history.isEmpty()) item {
                    SearchMessage(stringResource(R.string.search_history_empty), stringResource(R.string.search_history_hint))
                }
            } else {
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.search_query_results, state.query.trim()), Modifier.weight(1f),
                            color = colors.textMuted, style = MaterialTheme.typography.bodySmall,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        SoundScapeButton(stringResource(R.string.search_filters), {
                            filtersOpen = !filtersOpen
                            if (filtersOpen) model.loadOptions()
                        }, compact = true, variant = SoundScapeButtonVariant.Outlined,
                            outlineColor = colors.outline, leadingIcon = { SearchIcon(R.drawable.ic_sliders_horizontal) })
                    }
                }
                if (filtersOpen) item { SearchFiltersCard(state, model) }
                if (filtered) item {
                    Text(stringResource(R.string.search_filters_active), color = colors.primary, style = MaterialTheme.typography.labelMedium)
                }
                when {
                    results.isEmpty() && state.loading -> item { SearchSkeleton() }
                    results.isEmpty() && state.error != null -> item {
                        val offline = state.error == DomainError.Network
                        SearchMessage(if (offline) stringResource(R.string.search_offline) else stringResource(R.string.search_failed),
                            when {
                                state.waitingForRetry -> stringResource(R.string.search_rate_limited)
                                offline -> stringResource(R.string.search_offline_hint)
                                else -> stringResource(R.string.search_failed_hint)
                            },
                            stringResource(R.string.search_retry), model::retry, !state.waitingForRetry,
                            if (offline) R.drawable.ic_offline else R.drawable.ic_alert)
                    }
                    results.isEmpty() && state.results != null -> item {
                        SearchMessage(stringResource(R.string.search_empty), if (filtered) stringResource(R.string.search_empty_filtered)
                            else stringResource(R.string.search_empty_hint),
                            stringResource(R.string.search_reset_filters).takeIf { filtered }, { model.filters(SearchFilters()) })
                    }
                    results.isNotEmpty() -> {
                        if (state.loading || state.error != null || offline) item { SearchStatus(state, offline, model::retry) }
                        SearchCategory.entries.forEach { category ->
                            val group = results.filter { it.category == category }
                            if (group.isNotEmpty()) {
                                item(key = category.name) {
                                    SearchHeading(category.heading(),
                                        (if (category == SearchCategory.TRACK) stringResource(R.string.search_all_tracks) else stringResource(R.string.search_all))
                                            .takeIf { state.category == null }) { model.category(category) }
                                }
                                items(group, key = { "${it.category}:${it.id}" }) { SearchResult(it, openProfile, image) }
                            }
                        }
                        if (!state.loading && state.error == null && state.results?.hasMore == true) item {
                            SoundScapeButton(stringResource(R.string.search_more), model::loadMore, Modifier.fillMaxWidth(), loading = state.paging)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchInput(state: GlobalSearchState, query: (String) -> Unit, category: (SearchCategory?) -> Unit) {
    val spacing = SoundScapeTheme.spacing
    val colors = SoundScapeTheme.colors
    val focus = LocalFocusManager.current
    Surface(color = colors.surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = spacing.space16, vertical = spacing.space8)) {
            TextField(value = state.query, onValueChange = query, singleLine = true, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.search_hint), maxLines = 1) },
                leadingIcon = { SearchIcon(R.drawable.ic_search) },
                trailingIcon = if (state.query.isNotEmpty()) {{
                    IconButton({ query("") }) { Icon(painterResource(R.drawable.ic_x), stringResource(R.string.search_clear_query)) }
                }} else null,
                shape = MaterialTheme.shapes.medium,
                colors = TextFieldDefaults.colors(focusedContainerColor = colors.surfaceAlt,
                    unfocusedContainerColor = colors.surfaceAlt, focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }))
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = spacing.space4),
                horizontalArrangement = Arrangement.spacedBy(spacing.space8)) {
                (listOf(null) + SearchCategory.entries).forEach { tab ->
                    FilterChip(selected = tab == state.category, onClick = { category(tab); focus.clearFocus() },
                        label = { Text(tab.label(), maxLines = 1) }, shape = CircleShape,
                        colors = FilterChipDefaults.filterChipColors(containerColor = colors.surface,
                            labelColor = colors.textMuted, selectedContainerColor = colors.primary,
                            selectedLabelColor = colors.onPrimary),
                        border = BorderStroke(1.dp, if (tab == state.category) colors.primary else colors.outline))
                }
            }
        }
    }
}

@Composable
private fun SearchHeading(title: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        if (action != null) TextButton(onAction) { Text(action) }
    }
}

@Composable
private fun SearchFiltersCard(state: GlobalSearchState, model: GlobalSearchViewModel) {
    Surface(shape = MaterialTheme.shapes.medium, color = SoundScapeTheme.colors.surface) {
        Column(Modifier.fillMaxWidth().padding(SoundScapeTheme.spacing.space16)) {
            SearchHeading(stringResource(R.string.search_filters_title), stringResource(R.string.search_reset)) { model.filters(SearchFilters()) }
            SearchFacet.entries.forEach { facet ->
                var expanded by remember { mutableStateOf(false) }
                val genre = facet == SearchFacet.Genre
                val options = state.options[facet].orEmpty()
                val selected = if (genre) state.filters.genreId else state.filters.moodId
                val any = if (genre) stringResource(R.string.search_any_genre) else stringResource(R.string.search_any_mood)
                fun select(id: Int?) {
                    model.filters(if (genre) state.filters.copy(genreId = id) else state.filters.copy(moodId = id))
                    expanded = false
                }
                Text(if (genre) stringResource(R.string.search_genre) else stringResource(R.string.search_mood), style = MaterialTheme.typography.labelMedium)
                Box {
                    OutlinedButton({ model.loadOptions(); expanded = true }, Modifier.fillMaxWidth()) {
                        Text(options.firstOrNull { it.id == selected }?.name ?: any, Modifier.weight(1f))
                        SearchIcon(R.drawable.ic_chevron_down)
                    }
                    DropdownMenu(expanded, { expanded = false }) {
                        DropdownMenuItem(text = { Text(any) }, onClick = { select(null) })
                        options.forEach { option ->
                            DropdownMenuItem(text = { Text(option.name) }, onClick = { select(option.id) })
                        }
                        if (options.isEmpty()) {
                            val message = when {
                                facet in state.failedOptions -> stringResource(R.string.search_options_failed)
                                facet in state.options -> stringResource(R.string.search_options_empty)
                                else -> stringResource(R.string.search_options_loading)
                            }
                            DropdownMenuItem(text = { Text(message) }, onClick = {}, enabled = false)
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(state.filters.onlyInApp, { model.filters(state.filters.copy(onlyInApp = it)) })
                Text(stringResource(R.string.search_only_in_app), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SearchResult(hit: SearchHit, openProfile: (SearchHit) -> Unit, image: @Composable (SearchHit, Modifier) -> Unit) {
    val spacing = SoundScapeTheme.spacing
    val colors = SoundScapeTheme.colors
    val opens = hit.category == SearchCategory.USER
    val shape = if (opens || hit.category == SearchCategory.ARTIST) CircleShape else MaterialTheme.shapes.extraSmall
    Surface(shape = MaterialTheme.shapes.small, color = colors.surface) {
        Row(Modifier.fillMaxWidth().then(if (opens) Modifier.clickable { openProfile(hit) } else Modifier)
            .heightIn(min = 64.dp).padding(spacing.space8), horizontalArrangement = Arrangement.spacedBy(spacing.space12),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(spacing.space48).clip(shape).background(colors.primarySoft),
                contentAlignment = Alignment.Center) {
                SearchIcon(when (hit.category) {
                    SearchCategory.TRACK -> R.drawable.ic_music
                    SearchCategory.PLAYLIST -> R.drawable.ic_library
                    SearchCategory.PLACE -> R.drawable.ic_map_pin
                    else -> R.drawable.ic_user
                })
                if (!hit.coverPath.isNullOrBlank()) image(hit, Modifier.matchParentSize())
            }
            Column(Modifier.weight(1f)) {
                Text(hit.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                hit.subtitle?.takeIf(String::isNotBlank)?.let {
                    Text(it, color = colors.textMuted, style = MaterialTheme.typography.bodySmall,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                when (hit.availability) {
                    TrackAvailability.IN_APP -> Text(stringResource(R.string.search_in_app), color = colors.primary,
                        style = MaterialTheme.typography.labelSmall)
                    TrackAvailability.EXTERNAL_ONLY -> Text(stringResource(R.string.search_external), color = colors.textMuted,
                        style = MaterialTheme.typography.labelSmall)
                    else -> Unit
                }
            }
            if (opens) SearchIcon(R.drawable.ic_chevron_right)
        }
    }
}

@Composable
private fun SearchMessage(title: String, body: String, action: String? = null, onAction: () -> Unit = {},
    enabled: Boolean = true, icon: Int = R.drawable.ic_search) {
    val spacing = SoundScapeTheme.spacing
    val colors = SoundScapeTheme.colors
    Surface(Modifier.padding(top = spacing.space32), shape = MaterialTheme.shapes.medium, color = colors.surface) {
        Column(Modifier.fillMaxWidth().heightIn(min = 240.dp).padding(spacing.space24),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.space20, Alignment.CenterVertically)) {
            Box(Modifier.size(spacing.space48).background(colors.primarySoft, MaterialTheme.shapes.medium),
                contentAlignment = Alignment.Center) { SearchIcon(icon) }
            Text(title, textAlign = TextAlign.Center, style = MaterialTheme.typography.titleSmall)
            Text(body, textAlign = TextAlign.Center, color = colors.textMuted, style = MaterialTheme.typography.bodySmall)
            if (action != null) SoundScapeButton(action, onAction, Modifier.fillMaxWidth(), enabled = enabled)
        }
    }
}

@Composable
private fun SearchSkeleton() {
    val colors = SoundScapeTheme.colors
    val spacing = SoundScapeTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.space16)) {
        Text(stringResource(R.string.search_loading), style = MaterialTheme.typography.titleSmall)
        repeat(5) {
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.space12), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.size(spacing.space48).background(colors.primarySoft, MaterialTheme.shapes.extraSmall))
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
            if (state.error != null) TextButton(retry, enabled = !state.waitingForRetry) { Text(stringResource(R.string.search_retry)) }
        }
    }
}

@Composable
private fun SearchIcon(icon: Int) =
    Icon(painterResource(icon), null, Modifier.size(20.dp), tint = SoundScapeTheme.colors.primary)

@Composable
private fun SearchCategory?.label() = when (this) {
    null -> stringResource(R.string.search_all)
    SearchCategory.TRACK -> stringResource(R.string.search_tracks)
    SearchCategory.ARTIST -> stringResource(R.string.search_artists)
    SearchCategory.PLAYLIST -> stringResource(R.string.search_playlists)
    SearchCategory.PLACE -> stringResource(R.string.search_places)
    SearchCategory.USER -> stringResource(R.string.search_users)
}

@Composable
private fun SearchCategory.heading() = when (this) {
    SearchCategory.TRACK -> stringResource(R.string.search_compositions)
    SearchCategory.PLACE -> stringResource(R.string.search_cities)
    else -> label()
}
