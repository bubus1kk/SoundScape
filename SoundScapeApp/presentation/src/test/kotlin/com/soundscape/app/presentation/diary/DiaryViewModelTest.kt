package com.soundscape.app.presentation.diary

import com.soundscape.app.core.domain.model.DiaryDraft
import com.soundscape.app.core.domain.model.DiaryDraftInput
import com.soundscape.app.core.domain.model.DiaryDraftScope
import com.soundscape.app.core.domain.model.DomainError
import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.repository.DiaryDraftRepository
import com.soundscape.app.core.domain.usecase.DeleteDiaryDraftUseCase
import com.soundscape.app.core.domain.usecase.SaveDiaryDraftUseCase
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DiaryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun close() { Dispatchers.resetMain() }
    private class Fake : DiaryDraftRepository {
        val rows = MutableStateFlow<DomainResult<List<DiaryDraft>>>(DomainResult.Success(emptyList()))
        var fail = false
        var cancelAfterCommit = false
        var gate: CompletableDeferred<Unit>? = null
        var lastId: UUID? = null
        override fun observe(scope: DiaryDraftScope) = rows
        override fun observe(scope: DiaryDraftScope, id: UUID) =
            rows.map { DomainResult.Success((it as? DomainResult.Success)?.value?.find { row -> row.id == id }) }
        override suspend fun get(scope: DiaryDraftScope, id: UUID) =
            DomainResult.Success((rows.value as? DomainResult.Success)?.value?.find { it.id == id })
        override suspend fun save(scope: DiaryDraftScope, id: UUID, input: DiaryDraftInput,
            expectedRevision: Long?): DomainResult<DiaryDraft> {
            lastId = id
            gate?.await()
            if (fail) return DomainResult.Failure(DomainError.VersionConflict)
            val draft = DiaryDraft(id, scope, input, Instant.EPOCH, Instant.EPOCH, (expectedRevision ?: 0) + 1)
            rows.value = DomainResult.Success(listOf(draft))
            if (cancelAfterCommit) throw kotlinx.coroutines.CancellationException("committed")
            return DomainResult.Success(draft)
        }
        override suspend fun delete(scope: DiaryDraftScope, id: UUID, expectedRevision: Long): DomainResult<Unit> {
            rows.value = DomainResult.Success(emptyList()); return DomainResult.Success(Unit)
        }
    }
    private fun model(fake: Fake) = DiaryViewModel(fake, SaveDiaryDraftUseCase(fake), DeleteDiaryDraftUseCase(fake))
    @Test fun pendingWriteNeverClaimsSaved() = runTest(dispatcher) {
        val fake = Fake().apply { gate = CompletableDeferred() }
        val model = model(fake)
        model.create(); model.change(model.state.value.form.copy(body = "Текст")); model.save(); runCurrent()
        assertTrue(model.state.value.busy); assertTrue(model.state.value.dirty)
        assertNull(model.state.value.revision)
        fake.gate!!.complete(Unit); runCurrent()
        assertFalse(model.state.value.busy); assertFalse(model.state.value.dirty)
        assertEquals(1L, model.state.value.revision)
    }
    @Test fun conflictRetainsTextAndStableId() = runTest(dispatcher) {
        val fake = Fake().apply { fail = true }
        val model = model(fake)
        model.create(); val id = model.state.value.id
        model.change(model.state.value.form.copy(body = "Не потерять")); model.save(); runCurrent()
        assertEquals("Не потерять", model.state.value.form.body)
        assertEquals(id, model.state.value.id); assertEquals(id, fake.lastId)
        assertTrue(model.state.value.dirty); assertNotNull(model.state.value.error)
    }
    @Test fun exitRequiresDecisionAndSaveCompletesBeforeNavigation() = runTest(dispatcher) {
        val fake = Fake().apply { gate = CompletableDeferred() }
        val model = model(fake); var exited = false
        model.create(); model.leave { exited = true }
        assertTrue(model.state.value.confirmExit); assertFalse(exited)
        model.save(exit = true); runCurrent(); assertFalse(exited)
        fake.gate!!.complete(Unit); runCurrent(); assertTrue(exited)
        assertNull(model.state.value.id)
    }
    @Test fun failedObservationIsNotEmptySuccessAndRetryRecovers() = runTest(dispatcher) {
        val fake = Fake()
        val model = model(fake); runCurrent()
        fake.rows.value = DomainResult.Failure(DomainError.Unexpected); runCurrent()
        assertNotNull(model.state.value.error)
        fake.rows.value = DomainResult.Success(emptyList()); model.retry(); runCurrent()
        assertNull(model.state.value.error); assertFalse(model.state.value.loading)
    }
    @Test fun invalidDateKeepsFormAndDoesNotCallRepository() = runTest(dispatcher) {
        val fake = Fake(); val model = model(fake)
        model.create(); model.change(model.state.value.form.copy(date = "invalid")); model.save(); runCurrent()
        assertNull(fake.lastId); assertEquals("invalid", model.state.value.form.date)
        assertNotNull(model.state.value.error)
    }
    @Test fun cancellationAfterCommitRecoversRevisionWithSameId() = runTest(dispatcher) {
        val fake = Fake().apply { cancelAfterCommit = true }
        val model = model(fake)
        model.create()
        val id = model.state.value.id
        model.change(model.state.value.form.copy(body = "Committed text"))
        model.save()
        runCurrent()
        assertEquals(id, model.state.value.id)
        assertEquals(1L, model.state.value.revision)
        assertFalse(model.state.value.dirty)
        assertFalse(model.state.value.busy)
    }

    @Test fun crossingImportsKeepImmutableOwnersAndReleaseLatePhoto() = runTest(dispatcher) {
        val model = model(Fake())
        val firstGate = CompletableDeferred<Unit>()
        val secondGate = CompletableDeferred<Unit>()
        val released = mutableListOf<UUID>()
        val firstPhoto = UUID.randomUUID()
        val secondPhoto = UUID.randomUUID()
        model.create()
        val first = model.photoImports.begin()!!
        val firstJob = model.photoImports.selected(first, import = {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { firstGate.await(); firstPhoto }
        }, release = { released += it })!!
        model.create()
        val second = model.photoImports.begin()!!
        assertTrue(first.owner.draftId != second.owner.draftId)
        val secondJob = model.photoImports.selected(second, import = { secondGate.await(); secondPhoto },
            release = { released += it })!!
        firstGate.complete(Unit)
        runCurrent()
        assertTrue(firstJob.isCancelled)
        assertEquals(listOf(firstPhoto), released)
        assertTrue(model.state.value.form.photos.isEmpty())
        assertEquals(second, model.photoImports.active.value)
        secondGate.complete(Unit)
        runCurrent()
        secondJob.join()
        assertEquals(listOf(secondPhoto), model.state.value.form.photos.map { it.id })
        assertFalse(model.photoImports.isBusy)
    }

    @Test fun savingWaitsForSelectedPhotoInsteadOfSilentlyOmittingIt() = runTest(dispatcher) {
        val fake = Fake()
        val model = model(fake)
        val gate = CompletableDeferred<Unit>()
        val photo = UUID.randomUUID()
        model.create()
        val operation = model.photoImports.begin()!!
        model.photoImports.selected(operation, import = { gate.await(); photo }, release = {})
        model.save()
        runCurrent()
        assertNull(fake.lastId)
        assertTrue(model.photoImports.isBusy)
        gate.complete(Unit)
        runCurrent()
        model.save()
        runCurrent()
        val draft = (fake.rows.value as DomainResult.Success).value.single()
        assertEquals(listOf(photo), draft.input.photos.map { it.id })
    }

    @Test fun exitAndDiscardStayBlockedUntilCancelledImportIsReleased() = runTest(dispatcher) {
        val model = model(Fake())
        val gate = CompletableDeferred<Unit>()
        val cleanup = CompletableDeferred<Unit>()
        val released = mutableListOf<UUID>()
        val photo = UUID.randomUUID()
        var exited = false
        model.create()
        val operation = model.photoImports.begin()!!
        model.photoImports.selected(operation, import = {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { gate.await(); photo }
        }, release = { released += it; cleanup.await() })
        model.leave { exited = true }
        model.discard()
        assertFalse(exited)
        assertNotNull(model.state.value.id)
        model.photoImports.cancel()
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf(photo), released)
        assertTrue(model.photoImports.isBusy)
        cleanup.complete(Unit)
        runCurrent()
        assertFalse(model.photoImports.isBusy)
        model.leave { exited = true }
        model.discard()
        assertTrue(exited)
    }

    @Test fun failedImportPreservesTypedTextAndAllowsRetry() = runTest(dispatcher) {
        val model = model(Fake())
        model.create()
        model.change(model.state.value.form.copy(body = "Keep this text"))
        val operation = model.photoImports.begin()!!
        model.photoImports.selected(operation, import = { throw java.io.IOException("broken URI") }, release = {})
        runCurrent()
        assertEquals("Keep this text", model.state.value.form.body)
        assertNotNull(model.state.value.error)
        assertTrue(model.state.value.form.photos.isEmpty())
        assertFalse(model.photoImports.isBusy)
        assertNotNull(model.photoImports.begin())
        model.photoImports.cancel()
    }

    @Test fun dismissedAndStalePickerResultsDoNotStartImport() = runTest(dispatcher) {
        val model = model(Fake())
        model.create()
        val operation = model.photoImports.begin()!!
        model.photoImports.dismissed(operation)
        var started = false
        assertNull(model.photoImports.selected(operation, import = { started = true; UUID.randomUUID() }, release = {}))
        assertFalse(started)
        assertFalse(model.photoImports.isBusy)
    }

    @Test fun rejectedAttachmentReleasesReturnedPrivateFile() = runTest(dispatcher) {
        val owner = DiaryEditorIdentity(UUID.randomUUID(), UUID.randomUUID())
        val released = mutableListOf<UUID>()
        val imports = DiaryPhotoImports(this, editor = { owner }, attach = { false }, error = {})
        val operation = imports.begin()!!
        val photo = UUID.randomUUID()
        imports.selected(operation, import = { photo }, release = { released += it })!!.join()
        assertEquals(listOf(photo), released)
        assertFalse(imports.isBusy)
    }

    @Test fun immediateCancellationDoesNotLeaveImportBusy() = runTest(dispatcher) {
        val model = model(Fake())
        model.create()
        val operation = model.photoImports.begin()!!
        val job = model.photoImports.selected(operation,
            import = { kotlinx.coroutines.awaitCancellation() }, release = {})!!
        model.photoImports.cancel()
        job.join()
        assertFalse(model.photoImports.isBusy)
    }

}
