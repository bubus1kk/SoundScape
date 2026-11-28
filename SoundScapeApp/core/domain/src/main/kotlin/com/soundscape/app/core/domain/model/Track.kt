package com.soundscape.app.core.domain.model

import java.util.UUID
import kotlin.time.Duration

/** Whether a catalog recording can be played in SoundScape or opened externally. */
enum class TrackAvailability { IN_APP, EXTERNAL_ONLY }

/** A catalog recording with stable identity and musical duration. */
data class Track(
    val id: UUID,
    val title: String,
    val artist: String,
    val duration: Duration,
    val availability: TrackAvailability,
) {
    /** Checks required metadata and positive finite duration. */
    fun validate(): DomainResult<Unit> = when {
        title.isBlank() -> invalid("title", "required")
        artist.isBlank() -> invalid("artist", "required")
        !duration.isFinite() || duration <= Duration.ZERO -> invalid("duration", "positive_finite")
        else -> DomainResult.Success(Unit)
    }
}
