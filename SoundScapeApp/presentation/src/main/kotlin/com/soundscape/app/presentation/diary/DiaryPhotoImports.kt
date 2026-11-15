package com.soundscape.app.presentation.diary

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

data class DiaryEditorIdentity(val draftId: UUID, val sessionId: UUID)
data class DiaryPhotoOperation(val owner: DiaryEditorIdentity, val id: UUID = UUID.randomUUID())

class DiaryPhotoImports(
    private val scope: CoroutineScope,
    private val editor: () -> DiaryEditorIdentity?,
    private val attach: (UUID) -> Boolean,
    private val error: (String) -> Unit,
) {
    private val mutableActive = MutableStateFlow<DiaryPhotoOperation?>(null)
    val active = mutableActive.asStateFlow()
    private val mutableSelection = MutableStateFlow<DiaryPhotoOperation?>(null)
    val selection = mutableSelection.asStateFlow()
    private val jobs = mutableMapOf<UUID, Job>()
    val isBusy: Boolean get() = active.value != null

    fun begin(): DiaryPhotoOperation? {
        val owner = editor()
        if (isBusy || owner == null) return null
        return DiaryPhotoOperation(owner).also { mutableActive.value = it; mutableSelection.value = it }
    }

    fun dismissed(operation: DiaryPhotoOperation) {
        if (mutableSelection.value == operation) {
            mutableSelection.value = null
            if (mutableActive.value == operation) mutableActive.value = null
        }
    }

    fun selected(operation: DiaryPhotoOperation, import: suspend () -> UUID,
        release: suspend (UUID) -> Unit): Job? {
        if (mutableSelection.value != operation || mutableActive.value != operation) return null
        mutableSelection.value = null
        val job = scope.launch(start = CoroutineStart.UNDISPATCHED) { receive(operation, import, release) }
        if (!job.isCompleted) jobs[operation.id] = job
        return job
    }

    fun cancel() {
        val operation = active.value ?: return
        val job = jobs[operation.id]
        if (job == null) dismissed(operation) else job.cancel()
    }

    fun invalidate() {
        val operation = active.value
        mutableActive.value = null
        mutableSelection.value = null
        operation?.let { jobs[it.id]?.cancel() }
    }

    private fun current(operation: DiaryPhotoOperation): Boolean =
        active.value == operation && editor() == operation.owner

    private suspend fun receive(operation: DiaryPhotoOperation, import: suspend () -> UUID,
        release: suspend (UUID) -> Unit) {
        var photo: UUID? = null
        var attached = false
        try {
            photo = import()
            coroutineContext.ensureActive()
            attached = current(operation) && attach(photo)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            if (current(operation)) error("Не удалось добавить фото. Введённый текст остался в форме.")
        } finally {
            withContext(NonCancellable) {
                try {
                    if (!attached) photo?.let { release(it) }
                } catch (_: Exception) {
                    if (current(operation)) error("Не удалось освободить фото. Повторите открытие раздела.")
                } finally {
                    jobs.remove(operation.id)
                    if (mutableActive.value == operation) mutableActive.value = null
                }
            }
        }
    }
}
