package com.soundscape.app.core.domain.repository

import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.PlaybackQueue
import com.soundscape.app.core.domain.model.PlaybackSource
import com.soundscape.app.core.domain.model.PlaybackState
import com.soundscape.app.core.domain.model.QueueEntry
import com.soundscape.app.core.domain.model.RepeatMode
import java.util.UUID
import kotlin.time.Duration
import kotlinx.coroutines.flow.StateFlow

/** Starts playback of a resolved in-app source. */
fun interface PlaybackStarter {
    /** Starts a track. The implementation validates the source lifetime at playback time. */
    suspend fun play(source: PlaybackSource.InApp): DomainResult<Unit>
}

/** Single shared playback controller and queue for all application entry points. */
interface PlaybackController : PlaybackStarter, PlaybackQueueControls {
    val state: StateFlow<PlaybackState>

    /** Resumes the active track, resolving a fresh source as needed. */
    suspend fun resume(): DomainResult<Unit>

    /** Pauses the active track. */
    suspend fun pause(): DomainResult<Unit>

    /** Seeks within the active track. */
    suspend fun seek(position: Duration): DomainResult<Unit>

    /** Advances to the next eligible occurrence. */
    suspend fun next(): DomainResult<Unit>

    /** Moves to the previous eligible occurrence. */
    suspend fun previous(): DomainResult<Unit>

}

/** Queue editing capabilities provided by the global playback controller. */
interface PlaybackQueueControls {
    /** Inserts a track next in playback order. Unplayable tracks cause a typed outcome. */
    suspend fun insertNext(entry: QueueEntry): DomainResult<Unit>

    /** Appends a track to playback order. */
    suspend fun append(entry: QueueEntry): DomainResult<Unit>

    /** Removes a queue occurrence. */
    suspend fun remove(id: UUID): DomainResult<Unit>

    /** Moves an occurrence to a zero-based position in playback order. */
    suspend fun move(id: UUID, index: Int): DomainResult<Unit>

    /** Enables or disables the global shuffle order. */
    suspend fun setShuffle(enabled: Boolean): DomainResult<Unit>

    /** Applies the given repeat mode. */
    suspend fun setRepeat(mode: RepeatMode): DomainResult<Unit>

    /** Restores stable queue IDs and settings without persisting or replaying old source URLs. */
    suspend fun restore(queue: PlaybackQueue, position: Duration): DomainResult<Unit>
}
