package com.soundscape.app.core.domain.model

import java.util.UUID
import kotlin.time.Duration
enum class TrackAvailability { IN_APP, EXTERNAL_ONLY }

data class Track(
    val id: UUID,
    val title: String,
    val artist: String,
    val duration: Duration,
    val availability: TrackAvailability,
) {
    fun validate(): DomainResult<Unit> {
        if (title.isBlank()) return invalid("title", "required")
        if (artist.isBlank()) return invalid("artist", "required")
        if (!duration.isFinite() || duration <= Duration.ZERO) return invalid("duration", "positive_finite")
        return DomainResult.Success(Unit)
    }
}
