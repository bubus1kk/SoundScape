package com.soundscape.app.core.domain.model

import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class PlaybackQueueTest {
    private val first = entry(1)
    private val second = entry(2)
    private val repeatedTrack = entry(3).copy(trackId = first.trackId)
    private val queue = PlaybackQueue(listOf(first, second), first.id)

    @Test fun `insert next append reorder and removal operate on occurrences`() {
        val inserted = queue.insertNext(repeatedTrack).append(entry(4))
        assertEquals(listOf(first, repeatedTrack, second, entry(4)), inserted.entries)
        assertEquals(first, inserted.current)
        val moved = (inserted.move(second.id, 0) as DomainResult.Success).value
        assertEquals(second, moved.entries.first())
        assertEquals(repeatedTrack.id, moved.remove(first.id).currentId)
        assertEquals(DomainResult.Failure(DomainError.NotFound), queue.move(entry(9).id, 0))
        assertTrue(queue.move(second.id, -1) is DomainResult.Failure)
    }

    @Test fun `shuffle restore maintains the active playback order and repeat boundaries`() {
        val shuffled = queue.copy(shuffleOrder = listOf(second.id, first.id))
        assertEquals(second.id, shuffled.previous())
        assertEquals(null, shuffled.advance())
        assertEquals(first.id, shuffled.copy(repeat = RepeatMode.ONE).advance(onCompletion = true))
        assertEquals(second.id, shuffled.copy(repeat = RepeatMode.ALL).advance())
        assertEquals(first.id, shuffled.copy(currentId = second.id, repeat = RepeatMode.ALL).previous())
        val updated = shuffled.insertNext(repeatedTrack)
        assertEquals(listOf(second.id, first.id, repeatedTrack.id), updated.shuffleOrder)
        val moved = (updated.move(repeatedTrack.id, 0) as DomainResult.Success).value
        assertEquals(listOf(repeatedTrack.id, second.id, first.id), moved.shuffleOrder)
        assertEquals(updated.entries, moved.entries)
        assertEquals(repeatedTrack.id, updated.remove(first.id).currentId)
        assertEquals(listOf(second.id), updated.remove(first.id).remove(repeatedTrack.id).shuffleOrder)
        assertThrows(IllegalArgumentException::class.java) {
            queue.copy(shuffleOrder = listOf(first.id, first.id))
        }
    }

    private fun entry(n: Long) = QueueEntry(UUID(0, n), UUID(1, n))
}
