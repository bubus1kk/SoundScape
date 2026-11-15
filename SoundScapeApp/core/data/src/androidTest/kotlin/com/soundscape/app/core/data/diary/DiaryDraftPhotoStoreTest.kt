package com.soundscape.app.core.data.diary

import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.soundscape.app.core.database.SoundScapeDatabase
import com.soundscape.app.core.domain.model.DiaryDraftInput
import com.soundscape.app.core.domain.model.DiaryDraftPhoto
import com.soundscape.app.core.domain.model.DiaryDraftScope
import com.soundscape.app.core.domain.model.DomainResult
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DiaryDraftPhotoStoreTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val folder = File(target.noBackupFilesDir, "photo-test-${UUID.randomUUID()}")
    private val context = object : ContextWrapper(target) { override fun getNoBackupFilesDir(): File = folder }
    private val name = "photos-${UUID.randomUUID()}.db"
    private val db = Room.databaseBuilder(context, SoundScapeDatabase::class.java, name).build()
    private var store = DiaryDraftPhotoStore(context, db.diaryDraftDao())
    private fun png(): ByteArray {
        val bitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            bitmap.recycle(); output.toByteArray()
        }
    }
    @After fun close() { db.close(); target.deleteDatabase(name); folder.deleteRecursively() }
    @Test fun importedPhotoSurvivesNewStoreAndThumbnailIsSampled() = runBlocking {
        val id = store.importSource("image/png") { ByteArrayInputStream(png()) }
        val repository = RoomDiaryDraftRepository(db.diaryDraftDao(), store, Clock.systemUTC())
        val draft = UUID.randomUUID()
        assertTrue(repository.save(DiaryDraftScope.DeviceLocal, draft,
            DiaryDraftInput(body = "Фото", photos = listOf(DiaryDraftPhoto(id))), null) is DomainResult.Success)
        store = DiaryDraftPhotoStore(context, db.diaryDraftDao())
        val image = store.thumbnail(id)!!
        assertTrue(image.width <= 384 && image.height <= 384)
        image.recycle()
        assertEquals(DomainResult.Success(Unit), repository.delete(DiaryDraftScope.DeviceLocal, draft, 1))
        assertTrue(File(folder, "diary-photos").listFiles()!!.isEmpty())
    }
    @Test fun restartRemovesInterruptedAndUnreferencedFiles() = runBlocking {
        store.importSource("image/png") { ByteArrayInputStream(png()) }
        File(folder, "diary-photos/interrupted.part").writeText("partial")
        store = DiaryDraftPhotoStore(context, db.diaryDraftDao())
        store.release(emptyList())
        assertTrue(File(folder, "diary-photos").listFiles()!!.isEmpty())
    }
    @Test fun failedDatabaseReadNeverDeletesFiles() = runBlocking {
        store.importSource("image/png") { ByteArrayInputStream(png()) }
        val files = File(folder, "diary-photos").listFiles()!!.map { it.name }
        db.close()
        try { store.release(emptyList()); fail() } catch (_: Exception) { Unit }
        assertEquals(files, File(folder, "diary-photos").listFiles()!!.map { it.name })
    }
    @Test fun wrongTypeCorruptAndOversizedStreamsAreRejected() = runBlocking {
        for ((mime, bytes) in listOf("text/plain" to byteArrayOf(1), "image/png" to byteArrayOf(1),
            "image/png" to ByteArray((DiaryDraftPhotoStore.MAX_BYTES + 1).toInt()))) {
            try { store.importSource(mime) { ByteArrayInputStream(bytes) }; fail() }
            catch (_: IllegalArgumentException) { Unit }
        }
        assertTrue(File(folder, "diary-photos").listFiles()!!.isEmpty())
    }
    @Test fun brokenStreamLeavesNoPartialFile() = runBlocking {
        try { store.importSource("image/png") { object : java.io.InputStream() {
            override fun read(): Int = throw IOException("broken")
        } }; fail() } catch (_: IOException) { Unit }
        assertTrue(File(folder, "diary-photos").listFiles()!!.isEmpty())
    }
    @Test fun cancellationAfterPrivateFileCreationReleasesPendingFileImmediately() = runBlocking {
        val created = CompletableDeferred<UUID>()
        val gate = CompletableDeferred<Unit>()
        val job = launch {
            store.importSource("image/png", onCreated = { created.complete(it); gate.await() }) {
                ByteArrayInputStream(png())
            }
        }
        val id = created.await()
        job.cancelAndJoin()
        assertTrue(File(folder, "diary-photos").listFiles()!!.isEmpty())
        assertEquals(null, store.thumbnail(id))
    }

    @Test fun cancelledImportCleanupNeverDeletesAnotherDraftSavedPhoto() = runBlocking {
        val saved = store.importSource("image/png") { ByteArrayInputStream(png()) }
        val repository = RoomDiaryDraftRepository(db.diaryDraftDao(), store, Clock.systemUTC())
        val draft = UUID.randomUUID()
        val input = DiaryDraftInput(body = "Saved text", photos = listOf(DiaryDraftPhoto(saved)))
        assertTrue(repository.save(DiaryDraftScope.DeviceLocal, draft, input, null) is DomainResult.Success)
        val created = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val job = launch {
            store.importSource("image/png", onCreated = { created.complete(Unit); gate.await() }) {
                ByteArrayInputStream(png())
            }
        }
        created.await()
        job.cancelAndJoin()
        store.release(listOf(saved))
        val bitmap = requireNotNull(store.thumbnail(saved))
        bitmap.recycle()
        assertEquals(1, File(folder, "diary-photos").listFiles()!!.size)
        assertEquals(input, (repository.get(DiaryDraftScope.DeviceLocal, draft) as DomainResult.Success).value!!.input)
    }

}
