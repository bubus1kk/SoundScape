package com.soundscape.app.presentation.map

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.soundscape.app.core.designsystem.ui.components.SoundScapeButton
import com.soundscape.app.core.designsystem.ui.theme.SoundScapeRadii
import com.soundscape.app.core.designsystem.ui.theme.SoundScapeTheme
import com.soundscape.app.core.domain.model.MapPlace
import com.soundscape.app.core.domain.model.MapPlaceKind
import com.soundscape.app.presentation.R

@Composable
internal fun BoxScope.MapControls(citiesOnly: Boolean, zoom: MapZoomControls,
                                  onRandom: (() -> Unit)?, open: (MapPanel) -> Unit) {
    val spacing = SoundScapeTheme.spacing
    val colors = SoundScapeTheme.colors
    Surface(
        onClick = { open(MapPanel.Layers) },
        modifier = Modifier.align(Alignment.TopStart).padding(spacing.space12),
        color = colors.surface,
        shape = CircleShape,
    ) {
        Row(
            Modifier.size(width = 193.dp, height = 44.dp).padding(horizontal = spacing.space12),
            horizontalArrangement = Arrangement.spacedBy(spacing.space4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(7.dp).background(colors.primary, CircleShape))
            Text(
                stringResource(if (citiesOnly) R.string.map_layer_cities else R.string.map_layer_public),
                Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
            )
            Icon(painterResource(R.drawable.ic_chevron_down), null, Modifier.size(spacing.space16),
                tint = colors.textMuted)
        }
    }
    Column(
        Modifier.align(Alignment.TopEnd).padding(spacing.space12),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        MapTool(R.drawable.ic_sliders_horizontal, R.string.map_filters) { open(MapPanel.Filters) }
        MapTool(R.drawable.ic_globe, R.string.map_globe_unavailable, enabled = false) {}
        MapTool(R.drawable.ic_locate_fixed, R.string.map_nearby_unavailable, enabled = false) {}
        MapTool(R.drawable.ic_dice_5, R.string.map_random, enabled = onRandom != null) { onRandom?.invoke() }
    }
    Column(
        Modifier.align(Alignment.BottomEnd).padding(spacing.space16),
        verticalArrangement = Arrangement.spacedBy(spacing.space8),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MapTool(R.drawable.ic_minus, R.string.map_zoom_decrease, enabled = zoom.canZoomOut) { zoom.changeBy(-1.0) }
        MapTool(R.drawable.ic_plus, R.string.map_zoom_increase, enabled = zoom.canZoomIn, accent = true) {
            zoom.changeBy(1.0)
        }
    }
}

@Composable
private fun MapTool(@DrawableRes icon: Int, description: Int, enabled: Boolean = true,
                    accent: Boolean = false, action: () -> Unit) {
    val colors = SoundScapeTheme.colors
    Surface(
        shape = if (accent) MaterialTheme.shapes.medium else MaterialTheme.shapes.small,
        color = if (accent && enabled) colors.primary else colors.surface,
    ) {
        IconButton(onClick = action, enabled = enabled, modifier = if (accent) Modifier.size(52.dp) else Modifier) {
            Icon(painterResource(icon), stringResource(description), Modifier.size(SoundScapeTheme.spacing.space24),
                tint = when {
                    !enabled -> colors.textMuted
                    accent -> colors.onPrimary
                    else -> colors.primary
                })
        }
    }
}

internal enum class MapPanel { Layers, Filters }

@Composable
internal fun MapPanels(panel: MapPanel?, citiesOnly: Boolean, close: () -> Unit, select: (Boolean) -> Unit) {
    when (panel) {
        MapPanel.Layers -> MapLayersSheet(citiesOnly, close, select)
        MapPanel.Filters -> MapSheet(R.string.map_filters, close) {
            Text(stringResource(R.string.map_filters_unavailable), color = SoundScapeTheme.colors.textMuted,
                style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            SoundScapeButton(stringResource(R.string.map_panel_done), close, Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small)
        }
        null -> Unit
    }
}

@Composable
private fun MapLayersSheet(citiesOnly: Boolean, close: () -> Unit, select: (Boolean) -> Unit) {
    var draft by remember(citiesOnly) { mutableStateOf(citiesOnly) }
    MapSheet(R.string.map_layers, close) {
        Text(stringResource(R.string.map_layers_description), color = SoundScapeTheme.colors.textMuted,
            style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.size(SoundScapeTheme.spacing.space12))
        LayerOption(R.string.map_layer_public, R.drawable.ic_music, !draft) { draft = false }
        LayerOption(R.string.map_layer_cities, R.drawable.ic_map_pin, draft) { draft = true }
        LayerOption(R.string.map_layer_own, R.drawable.ic_user, false, enabled = false) {}
        Spacer(Modifier.weight(1f))
        SoundScapeButton(stringResource(R.string.map_panel_done), { select(draft) }, Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small)
    }
}

@Composable
private fun LayerOption(label: Int, @DrawableRes icon: Int, selected: Boolean, enabled: Boolean = true,
                        onSelect: () -> Unit) {
    val colors = SoundScapeTheme.colors
    val spacing = SoundScapeTheme.spacing
    Row(
        Modifier.fillMaxWidth().heightIn(min = spacing.space48)
            .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = spacing.space8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.space16),
    ) {
        Icon(painterResource(icon), null, tint = if (selected) colors.primary else colors.textMuted)
        Text(stringResource(label), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) colors.text else colors.textMuted)
        Box(
            Modifier.size(22.dp).background(if (selected) colors.primary else colors.outline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(spacing.space8).background(colors.onPrimary, CircleShape))
        }
    }
}

@Composable
internal fun BoxScope.MapPlacesStatus(state: MapPlacesState, noMarkers: Boolean, offline: Boolean, retry: () -> Unit) {
    val spacing = SoundScapeTheme.spacing
    if (!offline && state.error != null && noMarkers) {
        MapMessage(stringResource(R.string.map_places_error), retry)
    } else {
        val message = placesNotice(state, noMarkers, offline) ?: return
        val icon = when {
            offline -> R.drawable.ic_offline
            state.error != null -> R.drawable.ic_alert
            state.loading -> R.drawable.ic_refresh_cw
            else -> R.drawable.ic_map_pin
        }
        MapNotice(message, icon, Modifier.align(Alignment.TopStart).padding(
            start = spacing.space12, end = spacing.space48 + spacing.space24, top = spacing.space48 + spacing.space12,
        ))
    }
}

private fun placesNotice(state: MapPlacesState, noMarkers: Boolean, offline: Boolean): Int? = when {
    state.needsZoom -> R.string.map_zoom_in
    offline -> if (noMarkers) R.string.map_offline_no_places else R.string.map_offline_places
    state.error != null -> R.string.map_places_stale
    state.load == MapPlacesLoad.Initial -> R.string.map_places_loading
    state.load == MapPlacesLoad.Retry -> R.string.map_places_retrying
    state.loading -> null
    state.loaded && noMarkers -> R.string.map_places_empty
    state.partial -> R.string.map_results_limited
    else -> null
}

@Composable
private fun MapNotice(message: Int, @DrawableRes icon: Int, modifier: Modifier) {
    val spacing = SoundScapeTheme.spacing
    Surface(
        modifier,
        color = SoundScapeTheme.colors.surface, shape = MaterialTheme.shapes.medium,
    ) {
        Row(Modifier.padding(spacing.space12), horizontalArrangement = Arrangement.spacedBy(spacing.space8),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(icon), null, Modifier.size(spacing.space20), tint = SoundScapeTheme.colors.primary)
            Text(stringResource(message), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun MapSheet(title: Int, close: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    MapSheetContent(stringResource(title), close, content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MapSheetContent(title: String, close: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val spacing = SoundScapeTheme.spacing
    val colors = SoundScapeTheme.colors
    ModalBottomSheet(
        onDismissRequest = close,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surface,
        scrimColor = colors.text.copy(alpha = 0.18f),
        shape = RoundedCornerShape(topStart = SoundScapeRadii.radius28, topEnd = SoundScapeRadii.radius28),
        dragHandle = {
            Box(Modifier.padding(top = spacing.space8, bottom = spacing.space16)
                .size(width = 38.dp, height = spacing.space4).background(colors.outline, CircleShape))
        },
    ) {
        Column(
            Modifier.fillMaxWidth().height(398.dp).padding(
                start = spacing.space20, end = spacing.space20, bottom = spacing.space12,
            ),
            verticalArrangement = Arrangement.spacedBy(spacing.space12),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, color = colors.text)
                IconButton(onClick = close) {
                    Box(
                        Modifier.size(44.dp).background(colors.background, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(painterResource(R.drawable.ic_x), stringResource(R.string.map_card_close),
                            Modifier.size(spacing.space20), tint = colors.textMuted)
                    }
                }
            }
            content()
        }
    }
}

@Composable
internal fun MapPlaceCard(place: MapPlace, close: () -> Unit) {
    val spacing = SoundScapeTheme.spacing
    val colors = SoundScapeTheme.colors
    MapSheetContent(place.title, close) {
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.space8),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_map_pin), null, Modifier.size(spacing.space16), tint = colors.textMuted)
            val kind = if (place.kind == MapPlaceKind.CITY) R.string.map_card_city else R.string.map_card_pin
            Text(stringResource(kind), style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = spacing.space12),
            verticalArrangement = Arrangement.spacedBy(spacing.space20)) {
            place.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.textMuted)
            }
            HorizontalDivider(color = colors.outline)
            Text(stringResource(R.string.map_card_music), style = MaterialTheme.typography.titleSmall)
            val music = if (place.playlistId == null) R.string.map_card_no_playlist else R.string.map_card_playlist
            Text(stringResource(music), style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
        }
        SoundScapeButton(stringResource(R.string.map_card_open), {}, Modifier.fillMaxWidth(), enabled = false,
            shape = MaterialTheme.shapes.small)
    }
}
