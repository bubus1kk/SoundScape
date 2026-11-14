package com.soundscape.app.presentation.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.soundscape.app.core.designsystem.ui.components.SoundScapeFilterChip
import com.soundscape.app.core.designsystem.ui.theme.SoundScapeSpacing
import com.soundscape.app.core.domain.model.CachedPageState
import com.soundscape.app.core.domain.model.DomainError
import com.soundscape.app.core.domain.model.MapPlaceKind
import com.soundscape.app.presentation.R
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Main-source-set cache demonstration; production results always come from domain repositories. */
@Composable
fun LibraryScreen(model: LibraryViewModel, settings: @Composable () -> Unit) {
    val tab by model.tab.collectAsState()
    Column(Modifier.fillMaxSize()) {
        Row(horizontalArrangement = Arrangement.spacedBy(SoundScapeSpacing.space8)) {
            LibraryTab.entries.forEach { destination ->
                val label = when (destination) {
                    LibraryTab.PLACES -> R.string.library_places
                    LibraryTab.SEARCH -> R.string.library_search
                    LibraryTab.SETTINGS -> R.string.library_settings
                }
                SoundScapeFilterChip(stringResource(label), tab == destination, { model.open(destination) })
            }
        }
        when (tab) {
            LibraryTab.PLACES -> PlacesContent(model)
            LibraryTab.SEARCH -> SearchContent(model)
            LibraryTab.SETTINGS -> settings()
        }
    }
}

@Composable
private fun PlacesContent(model: LibraryViewModel) {
    val area by model.area.collectAsState()
    val state by model.places.collectAsState()
    val page by model.page.collectAsState()
    Column(Modifier.verticalScroll(rememberScrollState()).padding(SoundScapeSpacing.space16)) {
        Text(stringResource(R.string.library_bounds, area.south, area.west, area.north, area.east))
        Button(onClick = { model.selectArea(LibraryAreas.regional) }) {
            Text(stringResource(R.string.library_region))
        }
        Button(onClick = { model.selectArea(LibraryAreas.antimeridian) }) {
            Text(stringResource(R.string.library_antimeridian))
        }
        CacheStatus(state, model::refreshActive)
        state.cached?.page?.items?.forEach { place ->
            val kind = if (place.kind == MapPlaceKind.CITY) R.string.library_city else R.string.library_pin
            Text(stringResource(R.string.library_place_title, place.title, stringResource(kind)))
            Text(stringResource(R.string.library_coordinates, place.coordinates.latitude, place.coordinates.longitude))
            place.description?.let { Text(it) }
            place.playlistId?.let { Text(stringResource(R.string.library_playlist, it.toString())) }
            place.coverPath?.let { Text(stringResource(R.string.library_cover, it)) }
        }
        Paging(page.offset, state.cached?.page?.hasMore == true, state.refreshing, model::movePage)
    }
}

@Composable
private fun SearchContent(model: LibraryViewModel) {
    var text by rememberSaveable { mutableStateOf("") }
    var onlyInApp by rememberSaveable { mutableStateOf(false) }
    val state by model.tracks.collectAsState()
    val submitted by model.search.collectAsState()
    Column(Modifier.verticalScroll(rememberScrollState()).padding(SoundScapeSpacing.space16)) {
        OutlinedTextField(text, { text = it }, label = { Text(stringResource(R.string.library_query)) })
        Row {
            Checkbox(onlyInApp, { onlyInApp = it })
            Text(stringResource(R.string.library_only_in_app))
        }
        Button(onClick = { model.submit(text, onlyInApp) }) { Text(stringResource(R.string.library_submit)) }
        submitted?.let { Text(stringResource(R.string.library_submitted, it.query)) }
        if (submitted != null) CacheStatus(state, model::refreshActive)
        else Text(stringResource(R.string.library_not_loaded))
        state.cached?.page?.items?.forEach { track ->
            Text(stringResource(R.string.library_track_title, track.title, track.artist))
            Text(stringResource(if (track.availability.name == "IN_APP") R.string.library_in_app
                else R.string.library_external))
            Text(stringResource(R.string.library_duration, track.duration.inWholeMilliseconds))
        }
        submitted?.let {
            Paging(it.page.offset, state.cached?.page?.hasMore == true, state.refreshing, model::movePage)
        }
    }
}

/** Empty persisted pages differ from missing pages, and content remains visible during failed refresh. */
@Composable
private fun <T> CacheStatus(state: CachedPageState<T>, refresh: () -> Unit) {
    if (state.refreshing) CircularProgressIndicator()
    val cached = state.cached
    if (cached == null) Text(stringResource(R.string.library_no_cache))
    else {
        if (cached.page.items.isEmpty()) Text(stringResource(R.string.library_empty))
        val formatted = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")
            .format(cached.fetchedAt.atZone(ZoneId.systemDefault()))
        Text(stringResource(R.string.library_fetched, formatted))
    }
    state.refreshError?.let { error ->
        val message = when (error) {
            DomainError.Network -> R.string.library_offline
            DomainError.Timeout -> R.string.library_timeout
            is DomainError.Validation ->
                if (error.field == "cache") R.string.library_cache_full else R.string.library_invalid
            DomainError.Forbidden -> R.string.library_guest_only
            else -> R.string.library_error
        }
        Text(stringResource(message))
    }
    Button(onClick = refresh, enabled = !state.refreshing) { Text(stringResource(R.string.library_refresh)) }
}

@Composable
private fun Paging(offset: Int, hasMore: Boolean, loading: Boolean, move: (Boolean) -> Unit) {
    Text(stringResource(R.string.library_offset, offset))
    Row {
        Button(onClick = { move(false) }, enabled = offset > 0 && !loading) {
            Text(stringResource(R.string.library_previous))
        }
        Button(onClick = { move(true) }, enabled = hasMore && !loading) { Text(stringResource(R.string.library_next)) }
    }
}
