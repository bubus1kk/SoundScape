package com.soundscape.app.core.domain.model

import java.util.UUID
enum class JourneyStopKind { PLACE, PIN }
data class JourneyStop(val id: UUID, val kind: JourneyStopKind)
data class MusicJourney(
    val id: UUID,
    val ownerId: UUID,
    val title: String,
    val visibility: Visibility,
    val stops: List<JourneyStop>,
    val revision: Long,
    val playlistId: UUID? = null,
) {
    fun validate(): DomainResult<Unit> {
        val titleResult = validateTitle(title)
        if (titleResult is DomainResult.Failure) return titleResult
        if (revision < 1) return invalid("revision", "positive")
        if (stops.size !in 2..50) return invalid("stops", "count_2_to_50")
        return DomainResult.Success(Unit)
    }
}
