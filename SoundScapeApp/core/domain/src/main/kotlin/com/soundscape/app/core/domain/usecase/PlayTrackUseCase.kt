package com.soundscape.app.core.domain.usecase

import com.soundscape.app.core.domain.model.*
import com.soundscape.app.core.domain.repository.*
import java.util.UUID

class PlayTrackUseCase(private val resolver: PlaybackSourceResolver, private val player: PlaybackController) {
    suspend operator fun invoke(trackId: UUID): DomainResult<PlayTrackResult> {
        val resolved = resolver.resolve(trackId)
        if (resolved is DomainResult.Failure) return resolved
        val source = (resolved as DomainResult.Success).value
        if (source.trackId != trackId) return DomainResult.Failure(DomainError.PlaybackUnavailable)
        return when (source) {
            is PlaybackSource.External -> DomainResult.Success(PlayTrackResult.ExternalActionRequired(source))
            is PlaybackSource.InApp -> {
                val started = player.play(source)
                if (started is DomainResult.Failure) started else DomainResult.Success(PlayTrackResult.Started)
            }
        }
    }
}
