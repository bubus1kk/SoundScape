package com.soundscape.app.presentation.map

/** Camera limits shared by the zoom controls and the route to a selected place. */
internal class MapZoomRange(private val min: Double, private val max: Double) {
    fun step(current: Double, delta: Double) = (current + delta).coerceIn(min, max)
    fun canZoomIn(current: Double) = current < max - ZOOM_EPSILON
    fun canZoomOut(current: Double) = current > min + ZOOM_EPSILON
    fun destination(current: Double) = current.coerceAtLeast(PLACE_ZOOM).coerceIn(min, max)

    fun overview(start: Double, fitZoom: Double?): Double {
        val pullBack = start - OVERVIEW_ZOOM_DELTA
        return minOf(pullBack, fitZoom ?: pullBack).coerceIn(min, max)
    }

    private companion object {
        const val ZOOM_EPSILON = 0.0001
        const val PLACE_ZOOM = 12.0
        const val OVERVIEW_ZOOM_DELTA = 2.0
    }
}
