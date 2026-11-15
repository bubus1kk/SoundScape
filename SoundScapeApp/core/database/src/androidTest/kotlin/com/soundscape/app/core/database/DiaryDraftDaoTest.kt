package com.soundscape.app.core.database

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class DiaryDraftDaoTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "draft-${UUID.randomUUID()}.db"
    private lateinit var db: SoundScapeDatabase
    @Before fun open() { db = Room.databaseBuilder(context, SoundScapeDatabase::class.java, name).build() }
    @After fun close() { db.close(); context.deleteDatabase(name) }
    @Test fun fileReopenPreservesPartialDraftAndOrder() = runBlocking {
        val id = UUID.randomUUID().toString()
        val tracks = List(3) { DiaryDraftTrackEntity("device_local", id, it, UUID.randomUUID().toString()) }
        db.diaryDraftDao().save(draftEntity(id).copy(body = "Личный текст"), tracks, emptyList(), null)
        db.close(); open()
        val restored = db.diaryDraftDao().snapshot("device_local", id)!!
        assertEquals("Личный текст", restored.draft.body)
        assertEquals(tracks, restored.tracks)
        val swapped = tracks.reversed().mapIndexed { index, item -> item.copy(position = index) }
        db.diaryDraftDao().save(restored.draft.copy(revision = 2), swapped, emptyList(), 1)
        assertEquals(swapped, db.diaryDraftDao().tracks("device_local", id))
    }
    @Test fun conflictAndOtherScopeCannotChangeDraft() = runBlocking {
        val id = UUID.randomUUID().toString()
        val dao = db.diaryDraftDao()
        dao.save(draftEntity(id), emptyList(), emptyList(), null)
        try { dao.save(draftEntity(id).copy(title = "bad", revision = 2), emptyList(), emptyList(), null); fail() }
        catch (_: DraftRevisionConflict) { Unit }
        assertNull(dao.snapshot("account:other", id))
        try { dao.delete("account:other", id, 1); fail() } catch (_: DraftRevisionConflict) { Unit }
        assertEquals("", dao.snapshot("device_local", id)!!.draft.title)
    }
    @Test fun childConstraintFailureRollsBackParentAndChildren() = runBlocking {
        val id = UUID.randomUUID().toString()
        val dao = db.diaryDraftDao()
        dao.save(draftEntity(id), emptyList(), emptyList(), null)
        val track = UUID.randomUUID().toString()
        val duplicate = List(2) { DiaryDraftTrackEntity("device_local", id, it, track) }
        try { dao.save(draftEntity(id).copy(title = "bad", revision = 2), duplicate, emptyList(), 1); fail() }
        catch (_: android.database.sqlite.SQLiteConstraintException) { Unit }
        assertEquals(1L, dao.header("device_local", id)!!.revision)
        assertTrue(dao.tracks("device_local", id).isEmpty())
    }
    @Test fun photosKeepOrderCascadeAndLimit() = runBlocking {
        val id = UUID.randomUUID().toString()
        val dao = db.diaryDraftDao()
        val photos = List(10) { DiaryDraftPhotoEntity("device_local", id, it, UUID.randomUUID().toString(),
            "$it.image", "image/png", 42) }
        dao.save(draftEntity(id), emptyList(), photos, null)
        assertEquals(photos, dao.photos("device_local", id))
        try { dao.save(draftEntity(id).copy(revision = 2), emptyList(), photos + photos[0].copy(position = 10),
            1); fail() }
        catch (_: IllegalArgumentException) { Unit }
        assertEquals(1L, dao.header("device_local", id)!!.revision)
        dao.delete("device_local", id, 1)
        assertTrue(dao.allPhotos().isEmpty())
    }
    @Test fun simultaneousUpdatesHaveOneWinnerAndCacheCleanupKeepsDraft() = runBlocking {
        val id = UUID.randomUUID().toString()
        val dao = db.diaryDraftDao()
        val tracks = listOf(DiaryDraftTrackEntity("device_local", id, 0, UUID.randomUUID().toString()))
        dao.save(draftEntity(id), tracks, emptyList(), null)
        val results = List(2) { number -> async {
            try {
                dao.save(draftEntity(id).copy(title = "$number", revision = 2), tracks, emptyList(), 1)
                true
            } catch (_: DraftRevisionConflict) { false }
        } }.awaitAll()
        assertEquals(1, results.count { it })
        db.cacheDao().deleteBefore(Long.MAX_VALUE)
        assertEquals(tracks, dao.snapshot("device_local", id)!!.tracks)
        assertEquals(2L, dao.header("device_local", id)!!.revision)
    }

}
