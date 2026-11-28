package com.soundscape.app.core.domain.model

import java.util.UUID
import kotlin.time.Duration

/** A queue occurrence has its own identity, so a recording can be enqueued several times. */
data class QueueEntry(val id: UUID, val trackId: UUID)

/** Playback progression at the queue boundary. */
enum class RepeatMode { OFF, ONE, ALL }

/** Current state of the single global player. */
enum class PlaybackStatus { IDLE, BUFFERING, PLAYING, PAUSED, ENDED, FAILED }

/** Observable player state using stable queue and track IDs. */
data class PlaybackState(
    val queue: PlaybackQueue = PlaybackQueue(),
    val status: PlaybackStatus = PlaybackStatus.IDLE,
    val position: Duration = Duration.ZERO,
    val duration: Duration? = null,
    val error: DomainError? = null,
)

/** Persistable global queue. Shuffle order is a permutation of its occurrence IDs. */
data class PlaybackQueue(
    val entries: List<QueueEntry> = emptyList(),
    val currentId: UUID? = null,
    val repeat: RepeatMode = RepeatMode.OFF,
    val shuffleOrder: List<UUID>? = null,
) {
    init {
        val ids = entries.map { it.id }.toSet()
        require(ids.size == entries.size && (currentId == null || currentId in ids))
        require(shuffleOrder == null || (shuffleOrder.size == ids.size && shuffleOrder.toSet() == ids))
    }

    /** Active occurrence, when one is selected. */
    val current: QueueEntry? get() = entries.firstOrNull { it.id == currentId }

    /** In playback order, adds a recording after the current one or at the beginning. */
    fun insertNext(entry: QueueEntry): PlaybackQueue = insert(entry, next = true)

    /** In playback order, appends a recording. */
    fun append(entry: QueueEntry): PlaybackQueue = insert(entry, next = false)

    /** Removes one occurrence and selects its playback successor if it was active. */
    fun remove(id: UUID): PlaybackQueue {
        val updatedCurrent = if (currentId == id) advance() else currentId
        return copy(
            entries = entries.filterNot { it.id == id },
            currentId = updatedCurrent?.takeUnless { it == id },
            shuffleOrder = shuffleOrder?.filterNot { it == id },
        )
    }

    /** Moves an occurrence to a zero-based position in the active order. */
    fun move(id: UUID, index: Int): DomainResult<PlaybackQueue> {
        val order = order()
        val error = when {
            id !in order -> DomainResult.Failure(DomainError.NotFound)
            index !in order.indices -> invalid("index", "outside_queue")
            else -> null
        }
        if (error != null) return error
        val moved = order.toMutableList().apply { remove(id); add(index, id) }
        val byId = entries.associateBy { it.id }
        return DomainResult.Success(
            if (shuffleOrder == null) copy(entries = moved.map(byId::getValue)) else copy(shuffleOrder = moved),
        )
    }

    /** Resolves the next occurrence after explicit navigation or natural completion. */
    fun advance(onCompletion: Boolean = false): UUID? {
        if (onCompletion && repeat == RepeatMode.ONE && currentId != null) return currentId
        val order = order()
        val next = order.indexOf(currentId) + 1
        return order.getOrNull(next) ?: order.firstOrNull().takeIf { repeat == RepeatMode.ALL }
    }

    /** Resolves the previous occurrence, wrapping for Repeat All. */
    fun previous(): UUID? {
        val order = order()
        return order.getOrNull(order.indexOf(currentId) - 1)
            ?: order.lastOrNull().takeIf { repeat == RepeatMode.ALL && currentId != null }
    }

    private fun order(): List<UUID> = shuffleOrder ?: entries.map { it.id }

    private fun insert(entry: QueueEntry, next: Boolean): PlaybackQueue {
        val index = if (next) entries.indexOfFirst { it.id == currentId } + 1 else entries.size
        val updated = entries.toMutableList().apply { add(index, entry) }
        val shuffled = shuffleOrder?.toMutableList()?.apply {
            add(if (next) indexOf(currentId) + 1 else size, entry.id)
        }
        return copy(entries = updated, shuffleOrder = shuffled)
    }
}
