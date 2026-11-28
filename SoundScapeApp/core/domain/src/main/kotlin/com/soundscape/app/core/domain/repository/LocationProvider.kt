package com.soundscape.app.core.domain.repository

import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.GeoPoint

/** Provides a location fix through the platform permission boundary. */
interface LocationProvider {
    /** Returns current coordinates or a typed availability or permission failure. */
    suspend fun currentLocation(): DomainResult<GeoPoint>
}
