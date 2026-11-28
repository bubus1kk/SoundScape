package com.soundscape.app.core.domain.model

private const val MAX_LATITUDE = 90.0
private const val MAX_LONGITUDE = 180.0

/** Validated geographic coordinates in decimal degrees. */
class GeoPoint private constructor(val latitude: Double, val longitude: Double) {
    /** Factory for validated coordinate pairs. */
    companion object {
        /** Creates a point after checking the latitude and longitude ranges. */
        fun create(latitude: Double, longitude: Double): DomainResult<GeoPoint> = when {
            !latitude.isFinite() || latitude !in -MAX_LATITUDE..MAX_LATITUDE ->
                invalid("latitude", "outside_range")
            !longitude.isFinite() || longitude !in -MAX_LONGITUDE..MAX_LONGITUDE ->
                invalid("longitude", "outside_range")
            else -> DomainResult.Success(GeoPoint(latitude, longitude))
        }
    }

    /** Compares coordinate values, treating signed zero as the same coordinate. */
    override fun equals(other: Any?): Boolean =
        other is GeoPoint && latitude == other.latitude && longitude == other.longitude

    /** Returns a hash consistent with coordinate equality. */
    override fun hashCode(): Int = 31 * normalizedHash(latitude) + normalizedHash(longitude)

    private fun normalizedHash(value: Double): Int = if (value == 0.0) 0 else value.hashCode()
}

/** Map bounds that may cross the antimeridian. */
class GeoBounds private constructor(val southWest: GeoPoint, val northEast: GeoPoint) {
    val crossesAntimeridian: Boolean get() = southWest.longitude > northEast.longitude

    /** Compares the two corners. */
    override fun equals(other: Any?): Boolean =
        other is GeoBounds && southWest == other.southWest && northEast == other.northEast

    /** Returns a hash consistent with corner equality. */
    override fun hashCode(): Int = 31 * southWest.hashCode() + northEast.hashCode()

    /** Factory for validated map bounds. */
    companion object {
        /** Creates bounds when the southern latitude is no greater than the northern latitude. */
        fun create(southWest: GeoPoint, northEast: GeoPoint): DomainResult<GeoBounds> {
            if (southWest.latitude > northEast.latitude) return invalid("bounds", "latitude_order")
            return DomainResult.Success(GeoBounds(southWest, northEast))
        }
    }
}
