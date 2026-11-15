package com.soundscape.app.core.data.diary

import android.net.Uri
import androidx.room.Room
import androidx.room.withTransaction
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import androidx.test.platform.app.InstrumentationRegistry
import com.soundscape.app.core.database.SoundScapeDatabase
import com.soundscape.app.core.domain.model.DiaryDraftInput
import com.soundscape.app.core.domain.model.DiaryDraftPhoto
import com.soundscape.app.core.domain.model.DiaryDraftScope
import com.soundscape.app.core.domain.model.DomainError
import com.soundscape.app.core.domain.model.DomainResult
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DiaryDraftRepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "repo-${UUID.randomUUID()}.db"
    private lateinit var db: SoundScapeDatabase
    private lateinit var photos: DiaryDraftPhotoStore
    private lateinit var repository: RoomDiaryDraftRepository
    private val scope = DiaryDraftScope.DeviceLocal
    private val clock = Clock.fixed(Instant.ofEpochMilli(9876), ZoneOffset.UTC)
    @Before fun open() {
        db = Room.databaseBuilder(context, SoundScapeDatabase::class.java, name).build()
        photos = DiaryDraftPhotoStore(context, db.diaryDraftDao())
        repository = RoomDiaryDraftRepository(db.diaryDraftDao(), photos, clock)
    }
    @After fun close() { db.close(); context.deleteDatabase(name) }

    @Test fun emptyAndPartialCrudSurviveFileReopenWithoutNetwork() = runBlocking {
        val id = UUID.randomUUID()
        val created = repository.save(scope, id, DiaryDraftInput(), null) as DomainResult.Success
        assertEquals(1L, created.value.revision)
        assertEquals(clock.instant(), created.value.createdAt)
        val input = DiaryDraftInput(body = "Дневник без интернета", trackIds = List(2) { UUID.randomUUID() })
        assertTrue(repository.save(scope, id, input, 1) is DomainResult.Success)
        db.close(); open()
        val restored = repository.get(scope, id) as DomainResult.Success
        assertEquals(input, restored.value!!.input)
        assertEquals(2L, restored.value!!.revision)
        assertEquals(1, (repository.observe(scope).first() as DomainResult.Success).value.size)
        assertEquals(DomainResult.Failure(DomainError.VersionConflict), repository.save(scope, id, input, 1))
        assertEquals(DomainResult.Success(Unit), repository.delete(scope, id, 2))
        assertEquals(DomainResult.Success(null), repository.get(scope, id))
    }
    @Test fun unknownPhotoDoesNotOverwriteSavedText() = runBlocking {
        val id = UUID.randomUUID()
        val input = DiaryDraftInput(body = "Сохранённый текст")
        repository.save(scope, id, input, null)
        assertTrue(repository.save(scope, id, input.copy(photos = listOf(DiaryDraftPhoto(UUID.randomUUID()))),
            1) is DomainResult.Failure)
        assertEquals(input, (repository.get(scope, id) as DomainResult.Success).value!!.input)
    }
    @Test fun unreadableUriFailsWithoutChangingSavedDraft() = runBlocking {
        val id = UUID.randomUUID()
        repository.save(scope, id, DiaryDraftInput(body = "Текст"), null)
        try { photos.import(Uri.parse("content://missing.soundscape/photo")); fail() }
        catch (_: Exception) { Unit }
        assertEquals("Текст", (repository.get(scope, id) as DomainResult.Success).value!!.input.body)
    }
    @Test fun closedDatabaseIsAnErrorRatherThanEmptySuccess() = runBlocking {
        db.close()
        assertTrue(repository.get(scope, UUID.randomUUID()) is DomainResult.Failure)
        assertTrue(repository.observe(scope).first() is DomainResult.Failure)
    }
    @Test fun cancellationBeforeWriteKeepsPreviouslySavedVersion() = runBlocking {
        val id = UUID.randomUUID()
        repository.save(scope, id, DiaryDraftInput(body = "Before"), null)
        val held = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = launch(kotlinx.coroutines.Dispatchers.IO) {
            db.withTransaction { held.complete(Unit); release.await() }
        }
        held.await()
        val writer = launch { repository.save(scope, id, DiaryDraftInput(body = "After"), 1) }
        writer.cancelAndJoin()
        release.complete(Unit)
        holder.join()
        val actual = (repository.get(scope, id) as DomainResult.Success).value!!
        assertEquals("Before", actual.input.body)
        assertEquals(1L, actual.revision)
    }

}
