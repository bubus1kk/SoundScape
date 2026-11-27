package com.soundscape.app.presentation.map

import android.os.Bundle
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soundscape.app.core.designsystem.ui.components.SoundScapeButton
import com.soundscape.app.core.designsystem.ui.theme.SoundScapeTheme
import com.soundscape.app.core.domain.model.MapPlaceKind
import com.soundscape.app.presentation.R
import kotlinx.coroutines.delay

@Composable
fun MapScreen(apiKey: String, model: MapViewModel?, offline: Boolean, onSearch: () -> Unit, onProfile: () -> Unit) {
    val colors = SoundScapeTheme.colors
    Column(Modifier.fillMaxSize()) {
        MapHeader(onSearch, onProfile)
        Surface(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            color = colors.surfaceAlt,
        ) {
            if (apiKey.isBlank() || model == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    MapMessage(stringResource(R.string.map_config_missing))
                }
            } else {
                ConfiguredMap(apiKey.trim(), model, offline)
            }
        }
    }
}

@Composable
private fun MapHeader(onSearch: () -> Unit, onProfile: () -> Unit) {
    val spacing = SoundScapeTheme.spacing
    val colors = SoundScapeTheme.colors
    Surface(color = colors.surface) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .padding(start = spacing.space16, end = spacing.space8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.map_title),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                color = colors.text,
            )
            IconButton(onClick = onSearch) {
                Icon(painterResource(R.drawable.ic_search), stringResource(R.string.search_title))
            }
            IconButton(onClick = onProfile) {
                Surface(shape = CircleShape, color = colors.primarySoft) {
                    Box(Modifier.size(spacing.space40), contentAlignment = Alignment.Center) {
                        Icon(
                            painterResource(R.drawable.ic_user),
                            stringResource(R.string.map_open_profile),
                            tint = colors.onPrimarySoft,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfiguredMap(apiKey: String, model: MapViewModel, offline: Boolean) {
    val places by model.state.collectAsStateWithLifecycle()
    val random by model.randomPlace.collectAsStateWithLifecycle()
    val primary = SoundScapeTheme.colors.primary.toArgb()
    var mapState by rememberSaveable { mutableStateOf<Bundle?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var status by remember(attempt) { mutableStateOf(BaseMapStatus.Loading) }
    var citiesOnly by rememberSaveable { mutableStateOf(false) }
    var panel by remember { mutableStateOf<MapPanel?>(null) }
    val zoom = remember { MapZoomControls() }
    val snackbar = remember { SnackbarHostState() }
    val focus = (random as? RandomPlace.Flight)?.place ?: places.selected
    val markers = remember(places.places, citiesOnly, focus?.id) {
        places.places.filter { it.id != focus?.id && (!citiesOnly || it.kind == MapPlaceKind.CITY) }
    }
    RandomPlaceEffects(model, random, zoom, snackbar)
    LaunchedEffect(status, offline) {
        if (status == BaseMapStatus.Loading && offline) {
            delay(OFFLINE_TILE_GRACE_MS)
            status = BaseMapStatus.Failed
        }
    }
    Column {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            key(attempt, primary) {
                MapLibreMapView(
                    apiKey = apiKey,
                    initialState = mapState,
                    callbacks = NativeMapCallbacks(
                        saveState = { mapState = it },
                        baseLoaded = { status = BaseMapStatus.Ready },
                        baseFailed = { status = BaseMapStatus.Failed },
                        cameraMoving = model::onCameraMove,
                        viewportReady = model::onCameraIdle,
                        select = model::select,
                    ),
                    places = markers,
                    selected = focus,
                    markerColor = primary,
                    zoom = zoom,
                    modifier = Modifier.fillMaxSize().testTag("ss09-map"),
                )
            }
            val chooseRandom = { model.chooseRandomPlace(citiesOnly) }
            MapControls(citiesOnly, zoom, chooseRandom.takeIf { status == BaseMapStatus.Ready && !random.busy }) {
                model.cancelRandom()
                panel = it
            }
            MapStatus(status, places, markers.isEmpty() && focus == null, offline, { attempt++ }, model::retry)
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
        MapAttribution()
    }
    MapPanels(panel, citiesOnly, close = { panel = null }) {
        citiesOnly = it
        model.select(null)
        panel = null
    }
    places.selected?.let { place -> MapPlaceCard(place) { model.select(null) } }
}

@Composable
private fun BoxScope.MapStatus(
    status: BaseMapStatus,
    places: MapPlacesState,
    noMarkers: Boolean,
    offline: Boolean,
    retryTiles: () -> Unit,
    retryPlaces: () -> Unit,
) {
    when (status) {
        BaseMapStatus.Loading -> CircularProgressIndicator()
        BaseMapStatus.Failed -> {
            val message = if (offline) R.string.map_tiles_offline else R.string.map_tiles_unavailable
            MapMessage(stringResource(message), retryTiles)
        }
        BaseMapStatus.Ready -> MapPlacesStatus(places, noMarkers, offline, retryPlaces)
    }
}

@Composable
private fun RandomPlaceEffects(
    model: MapViewModel,
    random: RandomPlace,
    zoom: MapZoomControls,
    snackbar: SnackbarHostState,
) {
    val empty = stringResource(R.string.map_random_empty)
    val failed = stringResource(R.string.map_random_failed)
    DisposableEffect(model) { onDispose(model::cancelRandom) }
    LaunchedEffect(random) {
        when (random) {
            is RandomPlace.Flight -> model.finishRandomFlight(random, zoom.flyTo(random.place))
            RandomPlace.Empty, RandomPlace.Failed -> {
                snackbar.showSnackbar(if (random == RandomPlace.Empty) empty else failed)
                model.dismissRandomNotice()
            }
            else -> Unit
        }
    }
}

@Composable
internal fun MapMessage(message: String, onRetry: (() -> Unit)? = null) {
    val spacing = SoundScapeTheme.spacing
    Surface(
        modifier = Modifier.padding(spacing.space24),
        color = SoundScapeTheme.colors.surface,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            Modifier.padding(spacing.space16),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.space12),
        ) {
            Text(message, color = SoundScapeTheme.colors.text, textAlign = TextAlign.Center)
            if (onRetry != null) SoundScapeButton(stringResource(R.string.map_retry), onRetry)
        }
    }
}

@Composable
private fun MapAttribution() {
    val uri = LocalUriHandler.current
    val spacing = SoundScapeTheme.spacing
    Surface(color = Color.White, contentColor = Color.Black) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = spacing.space8),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painterResource(R.drawable.maptiler_logo),
                stringResource(R.string.map_attribution_logo),
                Modifier.clickable { uri.openUri("https://www.maptiler.com/") }
                    .heightIn(min = spacing.space24).width(spacing.space48 + spacing.space16)
                    .padding(vertical = spacing.space4),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.space8)) {
                Text(
                    stringResource(R.string.map_attribution_provider),
                    Modifier.clickable { uri.openUri("https://www.maptiler.com/copyright/") }
                        .padding(vertical = spacing.space4),
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    stringResource(R.string.map_attribution_osm),
                    Modifier.clickable { uri.openUri("https://www.openstreetmap.org/copyright") }
                        .padding(vertical = spacing.space4),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

private enum class BaseMapStatus { Loading, Ready, Failed }

private const val OFFLINE_TILE_GRACE_MS = 4_000L
