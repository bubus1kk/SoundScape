package com.soundscape.app.presentation.map

import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.GeoBounds
import com.soundscape.app.core.domain.model.GeoPoint
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

private const val WORLD_SPAN = 360.0
private const val HALF_WORLD = WORLD_SPAN / 2.0
private const val POLE = 90.0
private const val MAX_REQUEST_LONGITUDE = 120.0
private const val MAX_REQUEST_LATITUDE = 100.0

internal data class MapViewport(val south: Double, val west: Double, val north: Double, val east: Double) {
    fun queryBounds(): GeoBounds? {
        val longitudeDelta = east - west
        val longitudeSpan = if (longitudeDelta < 0) longitudeDelta + WORLD_SPAN else longitudeDelta
        if (!validLatitude() || abs(longitudeDelta) >= WORLD_SPAN || longitudeSpan !in 0.0..MAX_REQUEST_LONGITUDE) {
            return null
        }

        val step = when {
            longitudeSpan >= 10.0 -> 0.25
            longitudeSpan >= 1.0 -> 0.05
            else -> 0.005
        }
        val normalizedWest = normalizeLongitude(west)
        val westEdge = (floor(normalizedWest / step) * step).coerceAtLeast(-HALF_WORLD)
        val eastEdge = ceil((normalizedWest + longitudeSpan) / step) * step
        val southWest = GeoPoint.create((floor(south / step) * step).coerceAtLeast(-POLE),
            westEdge) as DomainResult.Success
        val northEast = GeoPoint.create((ceil(north / step) * step).coerceAtMost(POLE),
            if (eastEdge > HALF_WORLD) eastEdge - WORLD_SPAN else eastEdge) as DomainResult.Success
        return (GeoBounds.create(southWest.value, northEast.value) as DomainResult.Success).value
    }

    private fun validLatitude(): Boolean = south >= -POLE && north <= POLE && north - south in 0.0..MAX_REQUEST_LATITUDE
}

private fun normalizeLongitude(longitude: Double): Double =
    ((longitude + HALF_WORLD) % WORLD_SPAN + WORLD_SPAN) % WORLD_SPAN - HALF_WORLD
