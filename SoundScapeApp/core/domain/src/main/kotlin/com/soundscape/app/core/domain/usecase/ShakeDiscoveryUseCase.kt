package com.soundscape.app.core.domain.usecase

import com.soundscape.app.core.domain.model.DeviceAccess
import com.soundscape.app.core.domain.model.Discovery
import com.soundscape.app.core.domain.model.DiscoveryPreferences
import com.soundscape.app.core.domain.model.DomainError
import com.soundscape.app.core.domain.model.DomainResult

/** Handles a Shake Discovery event or an explicit fallback request. */
class ShakeDiscoveryUseCase(private val discover: DiscoverRandomPlaceTrackUseCase) {
    /** Reports unavailable device capabilities and respects the Shake preference. */
    suspend fun onShake(preferences: DiscoveryPreferences, sensorAccess: DeviceAccess): DomainResult<Discovery?> =
        when {
            !preferences.shakeEnabled -> DomainResult.Success(null)
            sensorAccess != DeviceAccess.AVAILABLE ->
                DomainResult.Failure(DomainError.ShakeUnavailable(sensorAccess))
            else -> randomPlace()
        }

    /** Runs the same discovery by explicit action, regardless of sensor or GPS access. */
    suspend fun randomPlace(): DomainResult<Discovery> = when (val result = discover()) {
        is DomainResult.Failure -> result
        is DomainResult.Success -> result.value?.let { DomainResult.Success(it) }
            ?: DomainResult.Failure(DomainError.NoCandidates)
    }
}
