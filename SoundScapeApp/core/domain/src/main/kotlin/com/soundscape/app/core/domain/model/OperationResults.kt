package com.soundscape.app.core.domain.model

sealed interface PlayTrackResult {
    data object Started : PlayTrackResult
    data class ExternalActionRequired(val source: PlaybackSource.External) : PlayTrackResult
}

data class Discovery(val place: MapPlace, val track: Track)
