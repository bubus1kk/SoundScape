package com.soundscape.app.core.domain.model

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Route state from the authoritative stop resolution. */
enum class JourneyRouteStatus { READY, NEEDS_EDIT }

/** Music durations and generation eligibility in the server's access context. */
data class JourneyRoute(
    val totalDuration: Duration = Duration.ZERO,
    val inAppDuration: Duration = Duration.ZERO,
    val status: JourneyRouteStatus = JourneyRouteStatus.NEEDS_EDIT,
    val canGenerate: Boolean = false,
) {
    /** Factory for route summaries built from measured durations. */
    companion object {
        /** Maps values measured in musical milliseconds. */
        fun fromMilliseconds(total: Long, inApp: Long, status: JourneyRouteStatus, canGenerate: Boolean): JourneyRoute {
            require(total >= 0 && inApp in 0..total)
            return JourneyRoute(total.milliseconds, inApp.milliseconds, status, canGenerate)
        }
    }
}

/** Resolution of one requested stop under the current access context. */
data class JourneyStopContext(val stop: JourneyStop, val publiclyAccessible: Boolean)

/** Derives an editor preview from visible stops and their musical durations. */
fun journeyRoutePreview(stops: List<JourneyStopContext>, playlistTracks: List<Track> = emptyList()): JourneyRoute {
    val total = playlistTracks.fold(Duration.ZERO) { sum, track -> sum + track.duration }
    val inApp = playlistTracks.filter { it.availability == TrackAvailability.IN_APP }
        .fold(Duration.ZERO) { sum, track -> sum + track.duration }
    val ready = stops.map { it.stop }.distinct().size >= MIN_JOURNEY_STOPS
    val canGenerate = ready && stops.all { it.publiclyAccessible }
    val status = if (ready) JourneyRouteStatus.READY else JourneyRouteStatus.NEEDS_EDIT
    return JourneyRoute(total, inApp, status, canGenerate)
}
