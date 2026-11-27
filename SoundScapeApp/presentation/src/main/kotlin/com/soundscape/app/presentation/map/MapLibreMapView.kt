package com.soundscape.app.presentation.map

import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.soundscape.app.core.domain.model.GeoBounds
import com.soundscape.app.core.designsystem.ui.theme.SoundScapeTheme
import com.soundscape.app.core.domain.model.MapPlace
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.math.pow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdate
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.log.Logger
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.module.http.HttpRequestUtil

@Composable
internal fun MapLibreMapView(
    apiKey: String,
    initialState: Bundle?,
    callbacks: NativeMapCallbacks,
    places: List<MapPlace>,
    selected: MapPlace?,
    markerColor: Int,
    zoom: MapZoomControls,
    modifier: Modifier = Modifier,
) {
    val owner = LocalLifecycleOwner.current
    val currentCallbacks = rememberUpdatedState(callbacks)
    val selectedColor = SoundScapeTheme.colors.onPrimarySoft.toArgb()
    AndroidView(
        modifier = modifier,
        factory = { context ->
            MapTilerRequests.initialize(context)
            val options = MapLibreMapOptions.createFromAttributes(context)
                .textureMode(true)
                .logoEnabled(false)
                .rotateGesturesEnabled(false)
                .tiltGesturesEnabled(false)
                .camera(initialMapCamera)
            val view = MapView(context, options)
            val binding = MapViewLifecycle(view, owner, markerColor, selectedColor, zoom) { currentCallbacks.value }
            view.tag = binding
            binding.attach(initialState, mapTilerStyle(apiKey))
            view
        },
        update = { view -> (view.tag as MapViewLifecycle).setPlaces(places, selected) },
        onRelease = { view ->
            (view.tag as MapViewLifecycle).release()
            view.tag = null
        },
    )
}

internal class MapZoomControls {
    private var map: MapLibreMap? = null
    private val cameraMoved = MapLibreMap.OnCameraMoveListener { update() }
    private val cameraIdle = MapLibreMap.OnCameraIdleListener { update() }
    var canZoomIn by mutableStateOf(false)
        private set
    var canZoomOut by mutableStateOf(false)
        private set
    var isFlightOverview = false
        private set

    fun attach(current: MapLibreMap) {
        map?.let(::detach)
        map = current
        current.addOnCameraMoveListener(cameraMoved)
        current.addOnCameraIdleListener(cameraIdle)
        update()
    }

    fun detach(current: MapLibreMap) {
        if (map !== current) return
        current.removeOnCameraMoveListener(cameraMoved)
        current.removeOnCameraIdleListener(cameraIdle)
        map = null
        update()
    }

    fun changeBy(step: Double) {
        val current = map ?: return
        val start = current.cameraPosition.zoom
        val target = MapZoomRange(current.minZoomLevel, current.maxZoomLevel).step(start, step)
        if (target != start) current.animateCamera(CameraUpdateFactory.zoomTo(target), ZOOM_DURATION_MS)
    }

    suspend fun flyTo(place: MapPlace): Boolean {
        val current = map ?: return false
        val start = current.cameraPosition
        val center = LatLng(place.coordinates.latitude, place.coordinates.longitude)
        val zoom = MapZoomRange(current.minZoomLevel, current.maxZoomLevel).destination(start.zoom)
        val overview = current.flightOverviewZoom(start, center)
        return try {
            isFlightOverview = true
            val pulledBack = overview >= start.zoom ||
                current.animateFlightStep(CameraUpdateFactory.zoomTo(overview), FLIGHT_ZOOM_OUT_MS)
            val relocated = pulledBack && map === current && (start.target == center ||
                current.animateFlightStep(CameraUpdateFactory.newLatLngZoom(center, overview), FLIGHT_PAN_MS))
            isFlightOverview = false
            relocated && map === current &&
                current.animateFlightStep(CameraUpdateFactory.newLatLngZoom(center, zoom), FLIGHT_ZOOM_IN_MS)
        } finally {
            isFlightOverview = false
            if (!currentCoroutineContext().isActive && map === current) current.cancelTransitions()
        }
    }

    private fun update() {
        val current = map
        val range = current?.let { MapZoomRange(it.minZoomLevel, it.maxZoomLevel) }
        canZoomIn = current != null && range?.canZoomIn(current.cameraPosition.zoom) == true
        canZoomOut = current != null && range?.canZoomOut(current.cameraPosition.zoom) == true
    }

    private companion object {
        const val ZOOM_DURATION_MS = 250
        const val FLIGHT_ZOOM_OUT_MS = 400
        const val FLIGHT_PAN_MS = 600
        const val FLIGHT_ZOOM_IN_MS = 550
    }
}

private fun MapLibreMap.flightOverviewZoom(start: CameraPosition, destination: LatLng): Double {
    val origin = start.target
    val fitZoom = if (origin != null && origin != destination) {
        val bounds = LatLngBounds.Builder().include(origin).include(destination).build()
        getCameraForLatLngBounds(bounds)?.zoom?.minus(1.0)
    } else null
    return MapZoomRange(minZoomLevel, maxZoomLevel).overview(start.zoom, fitZoom)
}

private suspend fun MapLibreMap.animateFlightStep(update: CameraUpdate, duration: Int): Boolean =
    suspendCancellableCoroutine { continuation ->
        animateCamera(update, duration, object : MapLibreMap.CancelableCallback {
            override fun onFinish() {
                if (continuation.isActive) continuation.resume(true)
            }

            override fun onCancel() {
                if (continuation.isActive) continuation.resume(false)
            }
        })
    }

internal data class NativeMapCallbacks(
    val saveState: (Bundle) -> Unit,
    val baseLoaded: () -> Unit,
    val baseFailed: () -> Unit,
    val cameraMoving: () -> Unit,
    val viewportReady: (GeoBounds?) -> Unit,
    val select: (UUID?) -> Unit,
)

private val initialMapCamera = CameraPosition.Builder().target(LatLng(48.8566, 2.3522)).zoom(4.0).build()

private fun mapTilerStyle(apiKey: String): String =
    Uri.parse("https://api.maptiler.com/maps/streets-v4/style.json").buildUpon()
        .appendQueryParameter("key", apiKey)
        .build().toString()

private object MapTilerRequests {
    private var initialized = false

    fun initialize(context: Context) {
        if (initialized) return
        Logger.setVerbosity(Logger.NONE)
        MapLibre.getInstance(context.applicationContext)
        HttpRequestUtil.setLogEnabled(false)
        HttpRequestUtil.setOkHttpClient(
            OkHttpClient.Builder().addNetworkInterceptor { chain ->
                val request = chain.request()
                val mapTilerRequest = if (request.url.host == "api.maptiler.com") {
                    request.newBuilder().header("User-Agent", "SoundScape-Android").build()
                } else {
                    request
                }
                chain.proceed(mapTilerRequest)
            }.build(),
        )
        initialized = true
    }
}

private class MapViewLifecycle(
    private val view: MapView,
    private val owner: LifecycleOwner,
    private val markerColor: Int,
    private val selectedColor: Int,
    private val zoom: MapZoomControls,
    private val callbacks: () -> NativeMapCallbacks,
) : DefaultLifecycleObserver {
    private val context = view.context.applicationContext
    private val density = context.resources.displayMetrics.density
    private var map: MapLibreMap? = null
    private var markers: MapMarkers? = null
    private var places: List<MapPlace> = emptyList()
    private var selected: MapPlace? = null
    private val lifecycle = MapLifecycleState()
    private val memoryCallbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) = Unit

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onLowMemory() = view.onLowMemory()
    }
    private val cameraIdle = MapLibreMap.OnCameraIdleListener {
        saveState()
        if (!zoom.isFlightOverview) reportViewport()
    }
    private val cameraMoving = MapLibreMap.OnCameraMoveStartedListener { callbacks().cameraMoving() }
    private val mapLoaded = MapView.OnDidFinishLoadingMapListener { callbacks().baseLoaded() }
    private val mapFailed = MapView.OnDidFailLoadingMapListener { callbacks().baseFailed() }
    private val click = MapLibreMap.OnMapClickListener { coordinate ->
        val current = map
        current != null && markers?.click(current, coordinate, 8f * density, callbacks().select) == true
    }

    fun attach(savedState: Bundle?, style: String) {
        view.onCreate(savedState)
        view.addOnDidFinishLoadingMapListener(mapLoaded)
        view.addOnDidFailLoadingMapListener(mapFailed)
        context.registerComponentCallbacks(memoryCallbacks)
        owner.lifecycle.addObserver(this)
        view.getMapAsync { ready ->
            if (!lifecycle.released) {
                map = ready
                zoom.attach(ready)
                ready.addOnCameraIdleListener(cameraIdle)
                ready.addOnCameraMoveStartedListener(cameraMoving)
                ready.addOnMapClickListener(click)
                ready.setStyle(style) { loaded ->
                    if (!lifecycle.released) {
                        markers = MapMarkers(loaded, view.context, markerColor, selectedColor).also {
                            it.update(places)
                            it.select(selected)
                        }
                        reportViewport()
                    }
                }
            }
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        lifecycle.start(view::onStart)
    }

    override fun onResume(owner: LifecycleOwner) {
        lifecycle.resume(view::onResume)
    }

    override fun onPause(owner: LifecycleOwner) {
        lifecycle.pause(::saveState, view::onPause)
    }

    override fun onStop(owner: LifecycleOwner) {
        lifecycle.stop(view::onStop)
    }

    override fun onDestroy(owner: LifecycleOwner) = release()

    fun release() {
        if (!lifecycle.release({ onPause(owner) }, { onStop(owner) })) return
        owner.lifecycle.removeObserver(this)
        context.unregisterComponentCallbacks(memoryCallbacks)
        map?.let {
            zoom.detach(it)
            it.removeOnCameraIdleListener(cameraIdle)
            it.removeOnCameraMoveStartedListener(cameraMoving)
            it.removeOnMapClickListener(click)
        }
        view.removeOnDidFinishLoadingMapListener(mapLoaded)
        view.removeOnDidFailLoadingMapListener(mapFailed)
        map = null
        markers = null
        view.onDestroy()
    }

    private fun saveState() {
        if (map != null) callbacks().saveState(Bundle().also(view::onSaveInstanceState))
    }

    fun setPlaces(places: List<MapPlace>, selected: MapPlace?) {
        this.places = places
        this.selected = selected
        markers?.update(places)
        markers?.select(selected)
    }

    private fun reportViewport() {
        val current = map ?: return
        if (markers == null || view.width == 0 || view.height == 0) return
        val worldWidth = WORLD_TILE_DP * density * 2.0.pow(current.cameraPosition.zoom)
        val visible = if (view.width >= worldWidth) null else current.projection.visibleRegion.latLngBounds
        callbacks().viewportReady(visible?.let {
            MapViewport(it.latitudeSouth, it.longitudeWest, it.latitudeNorth, it.longitudeEast).queryBounds()
        })
    }

    private companion object {
        const val WORLD_TILE_DP = 512.0
    }
}
