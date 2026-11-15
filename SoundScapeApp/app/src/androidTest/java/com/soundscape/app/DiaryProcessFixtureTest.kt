package com.soundscape.app

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.provider.MediaStore
import androidx.room.Room
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.soundscape.app.core.data.diary.DiaryDraftPhotoStore
import com.soundscape.app.core.data.diary.RoomDiaryDraftRepository
import com.soundscape.app.core.database.MIGRATION_1_2
import com.soundscape.app.core.database.SoundScapeDatabase
import com.soundscape.app.core.domain.model.DiaryDraftInput
import com.soundscape.app.core.domain.model.DiaryDraftPhoto
import com.soundscape.app.core.domain.model.DiaryDraftScope
import com.soundscape.app.core.domain.model.DomainResult
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@SdkSuppress(minSdkVersion = 29)
class DiaryProcessFixtureTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = DiaryDraftScope.DeviceLocal
    private fun database() = Room.databaseBuilder(context, SoundScapeDatabase::class.java, "soundscape-cache.db")
        .addMigrations(MIGRATION_1_2).build()

    @Test fun privatePhotoPersistsAfterOriginalRemovalAndDatabaseReopen() = runBlocking {
        val mode = InstrumentationRegistry.getArguments().getString("stage4PhotoMode")
        val id = if (mode == null) UUID.randomUUID() else UUID.fromString("d9ac5a48-99ed-451a-b79a-c33cc853d901")
        when (mode) {
            "setup" -> create(id)
            "verify" -> verify(id)
            "cleanup" -> {
                remove(id)
                val marker = InstrumentationRegistry.getArguments().getString("stage4TextMarker")
                if (marker != null) removeTextFixture(marker)
            }
            else -> try { create(id); verify(id) } finally { remove(id) }
        }
    }

    private suspend fun create(id: UUID) {
        val db = database()
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "SoundScape-process-test.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SoundScapeTests")
        }
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        try {
            val bitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.MAGENTA)
            try {
                requireNotNull(resolver.openOutputStream(uri)).use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                }
            } finally { bitmap.recycle() }
            val store = DiaryDraftPhotoStore(context, db.diaryDraftDao())
            val repository = RoomDiaryDraftRepository(db.diaryDraftDao(), store, Clock.systemUTC())
            val previous = (repository.get(scope, id) as DomainResult.Success).value
            if (previous != null) {
                assertEquals(TITLE, previous.input.title)
                repository.delete(scope, id, previous.revision)
            }
            val photo = store.import(uri)
            val input = DiaryDraftInput(title = TITLE, body = "Persistent private photo",
                photos = listOf(DiaryDraftPhoto(photo)))
            assertTrue(repository.save(scope, id, input, null) is DomainResult.Success)
        } finally { resolver.delete(uri, null, null); db.close() }
    }

    private suspend fun verify(id: UUID) {
        val db = database()
        try {
            val store = DiaryDraftPhotoStore(context, db.diaryDraftDao())
            val repository = RoomDiaryDraftRepository(db.diaryDraftDao(), store, Clock.systemUTC())
            val draft = (repository.get(scope, id) as DomainResult.Success).value!!
            assertEquals(TITLE, draft.input.title)
            assertEquals("Persistent private photo", draft.input.body)
            val bitmap = store.thumbnail(draft.input.photos.single().id)
            assertNotNull(bitmap)
            requireNotNull(bitmap)
            try {
                assertTrue(bitmap.width <= 384 && bitmap.height <= 384)
                assertEquals(Color.MAGENTA, bitmap.getPixel(0, 0))
            } finally { bitmap.recycle() }
        } finally { db.close() }
    }

    private suspend fun remove(id: UUID) {
        val db = database()
        try {
            val repository = RoomDiaryDraftRepository(db.diaryDraftDao(),
                DiaryDraftPhotoStore(context, db.diaryDraftDao()), Clock.systemUTC())
            val draft = (repository.get(scope, id) as DomainResult.Success).value
            if (draft != null) {
                assertEquals(TITLE, draft.input.title)
                assertEquals(DomainResult.Success(Unit), repository.delete(scope, id, draft.revision))
            }
        } finally { db.close() }
    }

    private suspend fun removeTextFixture(marker: String) {
        require(Regex("ProcessDiary[0-9a-f]{8}").matches(marker))
        val db = database()
        try {
            val dao = db.diaryDraftDao()
            dao.snapshots("device_local").filter { it.draft.title == marker }.forEach {
                dao.delete("device_local", it.draft.id, it.draft.revision)
            }
        } finally { db.close() }
    }

    companion object { private const val TITLE = "ProcessPhotoFixture" }
}
