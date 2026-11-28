package com.soundscape.app.core.domain.usecase

import com.soundscape.app.core.domain.model.DomainError
import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.flatMap
import com.soundscape.app.core.domain.model.PlaybackSource
import com.soundscape.app.core.domain.model.PlayTrackResult
import com.soundscape.app.core.domain.repository.PlaybackSourceResolver
import com.soundscape.app.core.domain.repository.PlaybackStarter
import java.time.Clock
import java.util.UUID

/** Resolves a track and only starts playback for an in-app source. */
class PlayTrackUseCase(
    private val resolver: PlaybackSourceResolver,
    private val player: PlaybackStarter,
    private val clock: Clock = Clock.systemUTC(),
) {
    /** Opens an in-app source or returns the external action to the caller. */
    suspend operator fun invoke(trackId: UUID): DomainResult<PlayTrackResult> =
        resolver.resolve(trackId).flatMap { source ->
            if (source.trackId != trackId) {
                DomainResult.Failure(DomainError.PlaybackUnavailable)
            } else when (source) {
                is PlaybackSource.External -> DomainResult.Success(PlayTrackResult.ExternalActionRequired(source))
                is PlaybackSource.InApp -> start(source)
            }
        }

    private suspend fun start(source: PlaybackSource.InApp): DomainResult<PlayTrackResult> {
        if (source.isExpired(clock.instant())) {
            return DomainResult.Failure(DomainError.PlaybackSourceExpired(source.trackId))
        }
        return player.play(source).flatMap { DomainResult.Success(PlayTrackResult.Started) }
    }
}
