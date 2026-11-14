package com.soundscape.app.core.database

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AtomicPageTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val db = Room.inMemoryDatabaseBuilder(context, SoundScapeDatabase::class.java).build()
    private val dao = db.cacheDao()
    private val key = CacheKeys.search("query", false, emptyList(), emptyList())
    private val id = UUID.randomUUID().toString()
    @After fun close() { db.close() }
    private fun page(generation: Long, stamp: Long, offset: Int = 0) =
        CachedQueryPageEntity("guest", key, "search", offset, 20, generation, stamp, true)
    private fun item(stamp: Long) = CachedTrackEntity("guest", id, stamp.toString(), "Artist", 1000, "IN_APP")

    @Test fun concurrentSnapshotNeverMixesHeaderAndItems() = runBlocking {
        val generation = dao.refreshGeneration("guest", key, 0)
        dao.saveTracks(page(generation, 1), listOf(item(1)))
        coroutineScope {
            val writer = launch(Dispatchers.IO) {
                repeat(50) { index ->
                    val stamp = index.toLong() + 2
                    dao.saveTracks(page(generation, stamp), listOf(item(stamp)))
                }
            }
            val reader = launch(Dispatchers.IO) {
                repeat(50) {
                    val snapshot = dao.trackSnapshot("guest", key, 0, 20)!!
                    assertEquals(snapshot.header.fetchedAt.toString(), snapshot.items.single().title)
                }
            }
            writer.join(); reader.join()
        }
    }
    @Test fun observationSeesCommittedEmptyHeaderDespiteSameZeroCount() = runBlocking {
        assertNull(dao.trackSnapshot("guest", key, 0, 20))
        val visible = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(15000) {
                dao.trackChanges("guest", key, 0, 20).map { dao.trackSnapshot("guest", key, 0, 20) }
                    .first { it != null }
            }
        }
        val generation = dao.refreshGeneration("guest", key, 0)
        dao.saveTracks(page(generation, 1), emptyList())
        val snapshot = visible.await()!!
        assertTrue(snapshot.items.isEmpty())
        assertTrue(snapshot.header.hasMore)
    }
    @Test fun laterPagesReuseGenerationAndFirstRefreshInvalidatesOnlyOnSuccess() = runBlocking {
        val first = dao.refreshGeneration("guest", key, 0)
        dao.saveTracks(page(first, 1), listOf(item(1)))
        val second = dao.refreshGeneration("guest", key, 20)
        assertEquals(first, second)
        dao.saveTracks(page(second, 1, 20), listOf(item(1)))
        val fresh = dao.refreshGeneration("guest", key, 0)
        assertNotNull(dao.trackSnapshot("guest", key, 20, 20))
        assertFalse(dao.saveTracks(page(first, 2), listOf(item(2))))
        dao.saveTracks(page(fresh, 3), listOf(item(3)))
        assertNull(dao.trackSnapshot("guest", key, 20, 20))
        assertEquals("3", dao.trackSnapshot("guest", key, 0, 20)!!.items.single().title)
    }
    @Test fun capacityRejectsNewPagesWithoutEvictingExistingOnes() = runBlocking {
        val generation = dao.refreshGeneration("guest", key, 0)
        repeat(200) { offset -> dao.saveTracks(page(generation, 1, offset), emptyList()) }
        try {
            dao.saveTracks(page(generation, 2, 200), emptyList())
            fail("New page must be refused at the configured budget")
        } catch (_: CacheCapacityExceededException) {
            assertNotNull(dao.trackSnapshot("guest", key, 0, 20))
            assertNotNull(dao.trackSnapshot("guest", key, 199, 20))
            assertNull(dao.trackSnapshot("guest", key, 200, 20))
        }
        assertTrue(dao.saveTracks(page(generation, 3), emptyList()))
    }
}
