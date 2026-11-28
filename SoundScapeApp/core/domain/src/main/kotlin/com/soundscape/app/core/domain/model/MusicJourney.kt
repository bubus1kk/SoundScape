package com.soundscape.app.core.domain.model

import java.util.UUID
/** Place and pin identifiers are distinct in an ordered route. */
enum class JourneyStopKind { PLACE, PIN }
/** One reference in an ordered journey. */
data class JourneyStop(val id: UUID, val kind: JourneyStopKind)
/** A stored journey, including recoverable incomplete routes. */
data class MusicJourney(
    val id: UUID,
    val ownerId: UUID,
    val title: String,
    val visibility: Visibility,
    val stops: List<JourneyStop>,
    val revision: Long,
    val playlistId: UUID? = null,
    val route: JourneyRoute? = null,
) {
    /** Checks the journey snapshot without discarding editable incomplete routes. */
    fun validate(): DomainResult<Unit> = validateTitle(title).flatMap {
        val incomplete = stops.size < MIN_JOURNEY_STOPS && route?.status != JourneyRouteStatus.NEEDS_EDIT
        when {
            revision < 1 -> invalid("revision", "positive")
            stops.size > MAX_JOURNEY_STOPS || incomplete -> invalid("stops", "count_2_to_50")
            else -> DomainResult.Success(Unit)
        }
    }
}
