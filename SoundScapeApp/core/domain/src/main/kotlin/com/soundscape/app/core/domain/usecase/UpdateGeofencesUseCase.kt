package com.soundscape.app.core.domain.usecase

import com.soundscape.app.core.domain.model.DeviceAccess
import com.soundscape.app.core.domain.model.DiscoveryPreferences
import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.MusicGeofence
import com.soundscape.app.core.domain.model.MusicPlace
import com.soundscape.app.core.domain.repository.GeofenceMonitor

/** Applies geofencing only when opted in, leaving the map independent of device access. */
class UpdateGeofencesUseCase(private val monitor: GeofenceMonitor) {
    /** Replaces monitored regions or clears them when disabled or access is lost. */
    suspend operator fun invoke(
        preferences: DiscoveryPreferences,
        access: DeviceAccess,
        publishedPlaces: List<MusicPlace>,
    ): DomainResult<Unit> {
        val regions = if (preferences.geofencingEnabled && access == DeviceAccess.AVAILABLE) {
            publishedPlaces.distinctBy { it.id }.map { MusicGeofence(it, preferences.geofenceRadius) }
        } else emptyList()
        return monitor.replace(regions)
    }
}
