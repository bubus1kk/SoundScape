package com.soundscape.app.core.domain.model

class GeoPoint private constructor(val latitude: Double, val longitude: Double) {
    companion object {
        fun create(latitude: Double, longitude: Double): DomainResult<GeoPoint> {
            if (!latitude.isFinite() || latitude !in -90.0..90.0) {
                return invalid("latitude", "outside_range")
            }
            if (!longitude.isFinite() || longitude !in -180.0..180.0) {
                return invalid("longitude", "outside_range")
            }
            return DomainResult.Success(GeoPoint(latitude, longitude))
        }
    }

    override fun equals(other: Any?): Boolean =
        other is GeoPoint && latitude == other.latitude && longitude == other.longitude

    override fun hashCode(): Int = 31 * normalizedHash(latitude) + normalizedHash(longitude)

    private fun normalizedHash(value: Double): Int = if (value == 0.0) 0 else value.hashCode()
}

class GeoBounds private constructor(val southWest: GeoPoint, val northEast: GeoPoint) {
    val crossesAntimeridian: Boolean get() = southWest.longitude > northEast.longitude

    override fun equals(other: Any?): Boolean =
        other is GeoBounds && southWest == other.southWest && northEast == other.northEast

    override fun hashCode(): Int = 31 * southWest.hashCode() + northEast.hashCode()

    companion object {
        fun create(southWest: GeoPoint, northEast: GeoPoint): DomainResult<GeoBounds> {
            if (southWest.latitude > northEast.latitude) return invalid("bounds", "latitude_order")
            return DomainResult.Success(GeoBounds(southWest, northEast))
        }
    }
}
