package com.soundscape.app.presentation.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.graphics.PathParser
import com.soundscape.app.core.domain.model.MapPlace
import java.util.UUID
import kotlin.math.roundToInt
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

internal class MapMarkers(style: Style, context: Context, primary: Int, selectedColor: Int) {
    private val source = GeoJsonSource(SOURCE, GeoJsonOptions().withCluster(true)
        .withClusterRadius(48).withClusterMaxZoom(16))
    private val focusSource = GeoJsonSource(FOCUS_SOURCE)
    private var drawn: List<MapPlace>? = null
    private var focused: MapPlace? = null
    private val cluster = Expression.has("point_count")
    private val pinLayer = SymbolLayer(PLACES, SOURCE)

    init {
        style.addSource(source)
        style.addSource(focusSource)
        style.addLayer(CircleLayer(CLUSTERS, SOURCE).withFilter(cluster).withProperties(
            PropertyFactory.circleRadius(CLUSTER_RADIUS_DP),
            PropertyFactory.circleColor(primary),
            PropertyFactory.circleStrokeColor(Color.WHITE),
            PropertyFactory.circleStrokeWidth(CLUSTER_STROKE_DP),
        ))
        style.addLayer(SymbolLayer(COUNTS, SOURCE).withFilter(cluster).withProperties(
            PropertyFactory.textField(Expression.toString(Expression.get("point_count_abbreviated"))),
            PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
            PropertyFactory.textSize(COUNT_TEXT_DP),
            PropertyFactory.textColor(Color.WHITE),
            PropertyFactory.textAllowOverlap(true),
        ))
        val density = context.resources.displayMetrics.density
        style.addImage(PIN_ICON, mapPin(density, primary))
        style.addImage(SELECTED_PIN_ICON, mapPin(density, selectedColor))
        style.addLayer(pinLayer.withFilter(Expression.not(cluster)).withProperties(
            PropertyFactory.iconImage(PIN_ICON),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
        ))
        style.addLayer(SymbolLayer(FOCUS, FOCUS_SOURCE).withProperties(
            PropertyFactory.iconImage(SELECTED_PIN_ICON),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
        ))
    }

    fun update(places: List<MapPlace>) {
        if (drawn == places) return
        drawn = places
        source.setGeoJson(FeatureCollection.fromFeatures(places.map(MapPlace::asFeature)))
    }

    fun select(place: MapPlace?) {
        if (focused == place) return
        focused = place
        focusSource.setGeoJson(FeatureCollection.fromFeatures(listOfNotNull(place?.asFeature())))
    }

    fun click(map: MapLibreMap, at: LatLng, touchSlop: Float, select: (UUID?) -> Unit): Boolean {
        val screen = map.projection.toScreenLocation(at)
        val box = RectF(screen.x - touchSlop, screen.y - touchSlop, screen.x + touchSlop, screen.y + touchSlop)
        val feature = map.queryRenderedFeatures(box, PLACES, CLUSTERS).firstOrNull()
        if (feature?.hasProperty("cluster_id") == true) {
            val point = feature.geometry() as? Point ?: return false
            val zoom = source.getClusterExpansionZoom(feature)
            if (zoom >= 0) map.animateCamera(CameraUpdateFactory.newLatLngZoom(
                LatLng(point.latitude(), point.longitude()), zoom.toDouble()))
        } else {
            val id = feature?.id()?.let(UUID::fromString)
            select(id)
        }
        return feature != null
    }

    private companion object {
        const val SOURCE = "soundscape-places"
        const val CLUSTERS = "soundscape-clusters"
        const val COUNTS = "soundscape-cluster-counts"
        const val PLACES = "soundscape-places"
        const val FOCUS_SOURCE = "soundscape-focused-place"
        const val FOCUS = "soundscape-focused-place-pin"
        const val PIN_ICON = "soundscape-music-pin"
        const val SELECTED_PIN_ICON = "soundscape-selected-music-pin"
        const val CLUSTER_RADIUS_DP = 21f
        const val CLUSTER_STROKE_DP = 1.5f
        const val COUNT_TEXT_DP = 13f
    }
}

private fun MapPlace.asFeature(): Feature =
    Feature.fromGeometry(Point.fromLngLat(coordinates.longitude, coordinates.latitude), null, id.toString())

private fun mapPin(density: Float, primary: Int): Bitmap {
    val bitmap = Bitmap.createBitmap(
        (PIN_WIDTH_DP * density).roundToInt(), (PIN_HEIGHT_DP * density).roundToInt(), Bitmap.Config.ARGB_8888,
    )
    val canvas = Canvas(bitmap)
    canvas.scale(density, density)
    val outline = PathParser.createPathFromPathData(PIN_OUTLINE)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = primary }
    canvas.drawPath(outline, paint)
    paint.apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = PIN_STROKE_DP
        strokeJoin = Paint.Join.ROUND
    }
    canvas.drawPath(outline, paint)
    paint.strokeWidth = NOTE_STROKE_DP
    paint.strokeCap = Paint.Cap.ROUND
    canvas.drawPath(PathParser.createPathFromPathData(PIN_NOTE), paint)
    return bitmap
}

private const val PIN_WIDTH_DP = 45
private const val PIN_HEIGHT_DP = 51
private const val PIN_STROKE_DP = 2.76923f
private const val NOTE_STROKE_DP = 2.07692f
private const val PIN_OUTLINE = "M22.5 3C33.75 3 42.4038 11.6538 42.4038 22.9038" +
    "C42.4038 34.1538 30.2885 39.3462 22.5 48C14.7115 39.3462 2.59615 34.1538 2.59615 22.9038" +
    "C2.59615 11.6538 11.25 3 22.5 3Z"
private const val PIN_NOTE = "M22.5 27.2308C22.5 29.1425 20.9502 30.6923 19.0385 30.6923" +
    "C17.1267 30.6923 15.5769 29.1425 15.5769 27.2308C15.5769 25.319 17.1267 23.7692 19.0385 23.7692" +
    "C20.9502 23.7692 22.5 25.319 22.5 27.2308ZM22.5 27.2308V13.3846L28.5577 16.8462"
