package com.soundscape.app.core.domain.model

private const val NEAR_RADIUS_METERS = 100
private const val MEDIUM_RADIUS_METERS = 250
private const val FAR_RADIUS_METERS = 500

/** Supported geofence radii. */
enum class GeofenceRadius(val meters: Int) {
    M100(NEAR_RADIUS_METERS), M250(MEDIUM_RADIUS_METERS), M500(FAR_RADIUS_METERS),
}

/** Opt-in geofence preferences and the Shake Discovery switch. */
data class DiscoveryPreferences(
    val geofencingEnabled: Boolean = false,
    val geofenceRadius: GeofenceRadius = GeofenceRadius.M250,
    val shakeEnabled: Boolean = true,
)

/** Device capability and runtime permission outcomes. */
enum class DeviceAccess { AVAILABLE, UNAVAILABLE, PERMISSION_REQUIRED, DENIED, SETTINGS_REQUIRED }

/** A desired notification region for a published place. */
data class MusicGeofence(val place: MusicPlace, val radius: GeofenceRadius)
