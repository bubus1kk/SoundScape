package com.soundscape.app.core.database

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class Migration1To2Test {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val name = "migration-${UUID.randomUUID()}.db"
    @get:Rule val helper = MigrationTestHelper(instrumentation,
        SoundScapeDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory())
    @After fun cleanup() { context.deleteDatabase(name) }

    @Test fun filledVersionOneMigratesAndSupportsCacheAndDraftCrud() = runBlocking {
        val id = UUID.randomUUID().toString()
        val old = helper.createDatabase(name, 1).apply {
            execSQL("INSERT INTO cache_queries VALUES ('guest','maps',7)")
            execSQL("INSERT INTO cache_queries VALUES ('guest','tracks',11)")
            execSQL("INSERT INTO cache_pages VALUES ('guest','maps','map',0,20,7,1234,0)")
            execSQL("INSERT INTO cache_pages VALUES ('guest','tracks','track',0,20,11,5678,1)")
            execSQL("INSERT INTO cached_places VALUES ('guest','CITY','$id',55.0,37.0,'Город','Описание',NULL,NULL)")
            execSQL("INSERT INTO cached_tracks VALUES ('guest','$id','Трек','Автор',120000,'IN_APP')")
            execSQL("INSERT INTO map_page_items VALUES ('guest','maps',0,20,0,'CITY','$id')")
            execSQL("INSERT INTO track_page_items VALUES ('guest','tracks',0,20,0,'$id')")
        }
        val before = old.cacheRows()
        old.close()
        val migrated = helper.runMigrationsAndValidate(name, 2, true, MIGRATION_1_2)
        assertEquals(before, migrated.cacheRows())
        migrated.close()
        val db = Room.databaseBuilder(context, SoundScapeDatabase::class.java, name)
            .addMigrations(MIGRATION_1_2).build()
        try {
            val cache = db.cacheDao()
            assertEquals(7L, cache.generation("guest", "maps"))
            assertEquals(11L, cache.generation("guest", "tracks"))
            val map = cache.mapSnapshot("guest", "maps", 0, 20)!!
            assertEquals(1234L, map.header.fetchedAt)
            assertEquals("Город", map.items.single().title)
            val track = cache.trackSnapshot("guest", "tracks", 0, 20)!!
            assertEquals("Трек", track.items.single().title)
            assertEquals(120000L, track.items.single().durationMs)
            val dao = db.diaryDraftDao()
            val draft = draftEntity(id)
            dao.save(draft, emptyList(), emptyList(), null)
            assertEquals("", dao.snapshot("device_local", id)!!.draft.title)
            dao.save(draft.copy(title = "Сохранено", revision = 2), emptyList(), emptyList(), 1)
            assertEquals("Сохранено", dao.snapshot("device_local", id)!!.draft.title)
            dao.delete("device_local", id, 2)
            assertNull(dao.snapshot("device_local", id))
        } finally { db.close() }
    }
}

internal fun draftEntity(id: String, scope: String = "device_local") = DiaryDraftEntity(
    scope, id, "", null, null, null, null, "", null, "PRIVATE", 1000, 1000, 1)

private fun androidx.sqlite.db.SupportSQLiteDatabase.cacheRows(): Map<String, List<List<String?>>> =
    listOf("cache_queries", "cache_pages", "cached_places", "cached_tracks", "map_page_items",
        "track_page_items").associateWith { table ->
        query("SELECT * FROM $table ORDER BY 1, 2").use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(List(cursor.columnCount) { column ->
                    if (cursor.isNull(column)) null else cursor.getString(column)
                })
            }
        }
    }
