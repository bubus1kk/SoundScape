package com.soundscape.app.core.database

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.UUID

class CacheDaoTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "cache-test-${UUID.randomUUID()}.db"
    private lateinit var db: SoundScapeDatabase
    private val dao get() = db.cacheDao()
    private val key = CacheKeys.search("ambient", false, emptyList(), emptyList())
    private val first = UUID.randomUUID().toString()
    private val second = UUID.randomUUID().toString()

    @Before fun open() { db = Room.databaseBuilder(context, SoundScapeDatabase::class.java, name).build() }
    @After fun close() { db.close(); context.deleteDatabase(name) }
    private fun track(id: String, scope: String = "guest") =
        CachedTrackEntity(scope, id, "Title", "Artist", 1000, "IN_APP")
    private fun page(key: String, generation: Long, scope: String = "guest", offset: Int = 0, limit: Int = 20,
        type: String = "search") = CachedQueryPageEntity(scope, key, type, offset, limit, generation, 100, true)

    @Test fun tracksPersistInOrderAndReplace() = runTest {
        val p = page(key, dao.beginRefresh("guest", key))
        assertTrue(dao.saveTracks(p, listOf(track(second), track(first))))
        assertEquals(listOf(second, first), dao.tracks("guest", key, 0, 20).map { it.id })
        assertTrue(dao.saveTracks(p, listOf(track(first))))
        db.close(); open()
        assertEquals(listOf(first), dao.tracks("guest", key, 0, 20).map { it.id })
        assertEquals(p, dao.page("guest", key, 0, 20))
    }

    @Test fun emptyAndAbsentPagesDiffer() = runTest {
        assertNull(dao.page("guest", key, 0, 20))
        val p = page(key, dao.beginRefresh("guest", key))
        dao.saveTracks(p, emptyList())
        assertNotNull(dao.page("guest", key, 0, 20))
        assertTrue(dao.tracks("guest", key, 0, 20).isEmpty())
        assertTrue(dao.page("guest", key, 0, 20)!!.hasMore)
    }

    @Test fun scopedQueriesAndPaginationStaySeparate() = runTest {
        val keys = listOf(key, CacheKeys.search("ambient", true, listOf(1), listOf(2)),
            CacheKeys.search("other", false, emptyList(), emptyList()))
        for (scope in listOf("guest", "user:A")) for (k in keys) {
            val g = dao.beginRefresh(scope, k)
            for ((offset, limit) in listOf(0 to 20, 20 to 20, 0 to 10)) {
                val p = page(k, g, scope, offset, limit)
                dao.saveTracks(p, listOf(track(first, scope)))
                assertEquals(p, dao.page(scope, k, offset, limit))
            }
        }
        assertNull(dao.page("user:B", key, 0, 20))
    }

    @Test fun mapBoundsAndAntimeridianPersist() = runTest {
        val keys = listOf(CacheKeys.map(-10.0, 170.0, 10.0, -170.0),
            CacheKeys.map(-10.0, -170.0, 10.0, 170.0))
        assertNotEquals(keys[0], keys[1])
        for (k in keys) {
            val p = page(k, dao.beginRefresh("guest", k), type = "map")
            val items = listOf(
                CachedMapPlaceEntity("guest", "CITY", first, 0.0, 179.0, "City"),
                CachedMapPlaceEntity("guest", "PIN", second, 1.0, -179.0, "Pin"))
            dao.savePlaces(p, items)
            assertEquals(items, dao.places("guest", k, 0, 20))
        }
    }

    @Test fun staleGenerationCannotMutateContent() = runTest {
        val old = page(key, dao.beginRefresh("guest", key))
        dao.saveTracks(old, listOf(track(first)))
        dao.saveTracks(old.copy(offset = 20), listOf(track(second)))
        val fresh = page(key, dao.beginRefresh("guest", key))
        assertEquals(old, dao.page("guest", key, 0, 20))
        dao.saveTracks(fresh, listOf(track(second)))
        assertFalse(dao.saveTracks(old, listOf(track(first))))
        assertEquals(listOf(second), dao.tracks("guest", key, 0, 20).map { it.id })
        assertNull(dao.page("guest", key, 20, 20))
    }

    @Test fun constraintFailureRollsBackWholeReplacement() = runTest {
        val p = page(key, dao.beginRefresh("guest", key))
        dao.saveTracks(p, listOf(track(first)))
        try {
            dao.saveTracks(p.copy(fetchedAt = 200), listOf(track(second), track(second)))
            fail("Duplicate membership must fail")
        } catch (_: android.database.sqlite.SQLiteConstraintException) {
            assertEquals(p, dao.page("guest", key, 0, 20))
            assertEquals(listOf(track(first)), dao.tracks("guest", key, 0, 20))
        }
    }

    @Test fun foreignKeysRejectOrphanMembership() = runTest {
        val sql = db.openHelper.writableDatabase
        try {
            sql.execSQL("INSERT INTO track_page_items VALUES ('guest', 'missing', 0, 20, 0, '$first')")
            fail("Foreign key must fail")
        } catch (_: android.database.sqlite.SQLiteConstraintException) {   }
    }

    @Test fun cleanupRemovesPagesAndOrphanMetadata() = runTest {
        val p = page(key, dao.beginRefresh("guest", key))
        dao.saveTracks(p, listOf(track(first)))
        dao.deleteBefore(101)
        assertNull(dao.page("guest", key, 0, 20))
        db.openHelper.readableDatabase.query("SELECT count(*) FROM cached_tracks").use {
            assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
        }
    }
}
