package com.soundscape.app.presentation.search

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.soundscape.app.core.designsystem.ui.theme.SoundScapeTheme
import com.soundscape.app.core.domain.repository.SearchCategory
import com.soundscape.app.core.domain.repository.SearchFacet
import com.soundscape.app.core.domain.repository.SearchFilters
import com.soundscape.app.core.domain.repository.SearchHit
import com.soundscape.app.presentation.R
import com.soundscape.app.presentation.profile.ProfileHeader
import java.util.UUID

@Composable
fun GlobalSearchScreen(
    state: GlobalSearchState,
    model: GlobalSearchViewModel,
    offline: Boolean,
    back: () -> Unit,
    openProfile: (SearchHit) -> Unit,
    playback: Pair<UUID, SearchPlayback>? = null,
    onTrack: (SearchHit) -> Unit = {},
    image: @Composable (SearchHit, Modifier) -> Unit,
) {
    var filtersOpen by rememberSaveable { mutableStateOf(false) }
    val spacing = SoundScapeTheme.spacing
    val colors = SoundScapeTheme.colors
    val list = rememberLazyListState()
    LaunchedEffect(state.query.trim(), state.category, state.filters) { list.scrollToItem(0) }
    Column(Modifier.fillMaxSize().imePadding().background(colors.background)) {
        ProfileHeader(R.string.search_title, back, compact = true)
        SearchInput(state, model::query, model::category)
        LazyColumn(state = list, contentPadding = PaddingValues(spacing.space16),
            verticalArrangement = Arrangement.spacedBy(spacing.space8)) {
            if (state.query.isBlank()) {
                searchHistory(state, model)
            } else {
                searchFilters(state, model, filtersOpen) {
                    filtersOpen = !filtersOpen
                    if (filtersOpen) model.loadOptions()
                }
                searchResults(state, model, offline, openProfile, playback, onTrack, image)
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
                trailingIcon =
                    if (state.query.isNotEmpty()) {
                        {
                            IconButton({ query("") }) {
                                Icon(
                                    painterResource(R.drawable.ic_x),
                                    stringResource(R.string.search_clear_query),
                                )
                            }
                        }
                    } else null,
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
internal fun SearchFiltersCard(state: GlobalSearchState, model: GlobalSearchViewModel) {
    Surface(shape = MaterialTheme.shapes.medium, color = SoundScapeTheme.colors.surface) {
        Column(Modifier.fillMaxWidth().padding(SoundScapeTheme.spacing.space16)) {
            SearchHeading(
                stringResource(R.string.search_filters_title),
                stringResource(R.string.search_reset),
            ) {
                model.filters(SearchFilters())
            }
            SearchFacet.entries.forEach { facet ->
                var expanded by remember { mutableStateOf(false) }
                val genre = facet == SearchFacet.Genre
                val options = state.options[facet].orEmpty()
                val selected = if (genre) state.filters.genreId else state.filters.moodId
                val any =
                    if (genre) stringResource(R.string.search_any_genre)
                    else stringResource(R.string.search_any_mood)
                fun select(id: Int?) {
                    model.filters(if (genre) state.filters.copy(genreId = id) else state.filters.copy(moodId = id))
                    expanded = false
                }
                Text(
                    if (genre) stringResource(R.string.search_genre)
                    else stringResource(R.string.search_mood),
                    style = MaterialTheme.typography.labelMedium,
                )
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
internal fun SearchIcon(icon: Int) =
    Icon(painterResource(icon), null, Modifier.size(20.dp), tint = SoundScapeTheme.colors.primary)

@Composable
internal fun SearchCategory?.label() = when (this) {
    null -> stringResource(R.string.search_all)
    SearchCategory.TRACK -> stringResource(R.string.search_tracks)
    SearchCategory.ARTIST -> stringResource(R.string.search_artists)
    SearchCategory.PLAYLIST -> stringResource(R.string.search_playlists)
    SearchCategory.PLACE -> stringResource(R.string.search_places)
    SearchCategory.USER -> stringResource(R.string.search_users)
}
