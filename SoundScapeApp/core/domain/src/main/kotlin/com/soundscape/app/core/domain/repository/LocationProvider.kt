package com.soundscape.app.core.domain.repository

import com.soundscape.app.core.domain.model.*

interface LocationProvider {
    suspend fun currentLocation(): DomainResult<GeoPoint>
}
