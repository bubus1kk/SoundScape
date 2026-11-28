package com.soundscape.app.core.domain.repository

import com.soundscape.app.core.domain.model.DeviceAccess
import com.soundscape.app.core.domain.model.DiscoveryPreferences
import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.MusicGeofence
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/** Optional capabilities exposed alongside the existing sensor and location sources. */
interface DiscoveryDeviceAccess {
    val locationAccess: Flow<DeviceAccess>
    val shakeAccess: Flow<DeviceAccess>
}

/** Device-local preferences used by discovery and geofencing. */
interface DiscoverySettings {
    val preferences: Flow<DiscoveryPreferences>

    /** Persists a user preference change. */
    suspend fun update(preferences: DiscoveryPreferences): DomainResult<Unit>
}

/** Applies the selected regions when the user opts in and the device grants access. */
fun interface GeofenceMonitor {
    /** Replaces monitored regions. An empty list disables monitoring. */
    suspend fun replace(regions: List<MusicGeofence>): DomainResult<Unit>
}

/** Device-local entry events, deduplicated before notification delivery. */
interface GeofenceEntries {
    /** Each entry identifies a place to open on explicit action. */
    val enteredPlaceIds: Flow<UUID>
}
