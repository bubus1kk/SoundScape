package com.soundscape.app.core.domain.model

/** Describes how playback can proceed for the resolved track. */
sealed interface PlayTrackResult {
    /** In-app playback started. */
    data object Started : PlayTrackResult
    /** The caller must explicitly open the external recording link. */
    data class ExternalActionRequired(val source: PlaybackSource.External) : PlayTrackResult
}

/** A randomly selected music place and one eligible linked track. */
data class Discovery(val place: MapPlace, val track: Track)
