package com.soundscape.app.presentation.diary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.soundscape.app.core.domain.model.DiaryDraft
import com.soundscape.app.core.domain.model.DiaryDraftInput
import com.soundscape.app.core.domain.model.DiaryDraftPhoto
import com.soundscape.app.core.domain.model.DiaryDraftScope
import com.soundscape.app.core.domain.model.DomainError
import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.GeoPoint
import com.soundscape.app.core.domain.repository.DiaryDraftRepository
import com.soundscape.app.core.domain.usecase.DeleteDiaryDraftUseCase
import com.soundscape.app.core.domain.usecase.SaveDiaryDraftUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@Suppress("TooManyFunctions")
@HiltViewModel
class DiaryViewModel @Inject constructor(
    private val repository: DiaryDraftRepository,
    private val saveDraft: SaveDiaryDraftUseCase,
    private val deleteDraft: DeleteDiaryDraftUseCase,
) : ViewModel() {
    private val mutable = MutableStateFlow(DiaryUiState())
    val state = mutable.asStateFlow()
    private var watch: Job? = null
    private var editorSession = UUID.randomUUID()
    val photoImports = DiaryPhotoImports(viewModelScope, editor = {
        val current = mutable.value
        current.id?.takeIf { current.editing && !current.busy && current.form.photos.size < MAX_DRAFT_PHOTOS }
            ?.let { DiaryEditorIdentity(it, editorSession) }
    }, attach = { id ->
        if (mutable.value.form.photos.size < MAX_DRAFT_PHOTOS) { photo(id); true } else false
    }, error = ::error)
    private var pendingExit: (() -> Unit)? = null
    init { retry() }

    fun retry() {
        watch?.cancel()
        mutable.value = mutable.value.copy(loading = true, error = null)
        watch = viewModelScope.launch {
            repository.observe(DiaryDraftScope.DeviceLocal).collect { result ->
                mutable.value = when (result) {
                    is DomainResult.Success -> mutable.value.copy(drafts = result.value, loading = false)
                    is DomainResult.Failure -> mutable.value.copy(loading = false, error =
                        "Не удалось прочитать черновики. Повторите попытку.")
                }
            }
        }
    }

    fun create() {
        photoImports.invalidate()
        editorSession = UUID.randomUUID()
        mutable.value = mutable.value.copy(id = UUID.randomUUID(), revision = null,
            form = DiaryForm(), editing = true, error = null, dirty = true)
    }

    fun open(draft: DiaryDraft, editing: Boolean = false) {
        photoImports.invalidate()
        editorSession = UUID.randomUUID()
        mutable.value = mutable.value.copy(id = draft.id, revision = draft.revision,
            form = DiaryForm.from(draft.input), editing = editing, dirty = false, error = null)
    }

    fun edit() { mutable.value = mutable.value.copy(editing = true) }
    fun change(form: DiaryForm) {
        if (photoImports.isBusy || mutable.value.busy) return
        mutable.value = mutable.value.copy(form = form, dirty = true, error = null)
    }
    private fun photo(id: UUID) {
        val current = mutable.value
        mutable.value = current.copy(form = current.form.copy(photos = current.form.photos + DiaryDraftPhoto(id)),
            dirty = true, error = null)
    }
    fun error(message: String) { mutable.value = mutable.value.copy(error = message) }

    fun leave(action: () -> Unit) {
        if (mutable.value.busy || photoImports.isBusy) {
            if (photoImports.isBusy) error("Дождитесь завершения импорта или отмените его.")
            return
        }
        if (mutable.value.dirty) {
            pendingExit = action
            mutable.value = mutable.value.copy(confirmExit = true)
        } else { close(); action() }
    }
    fun stay() { pendingExit = null; mutable.value = mutable.value.copy(confirmExit = false) }
    fun discard() {
        if (photoImports.isBusy) return
        val action = pendingExit
        pendingExit = null
        close()
        action?.invoke()
    }
    private fun close() {
        photoImports.invalidate()
        editorSession = UUID.randomUUID()
        mutable.value = mutable.value.copy(id = null, editing = false, dirty = false, confirmExit = false)
    }

    fun save(exit: Boolean = false) {
        val before = mutable.value
        val id = before.id
        if (id == null || before.busy || photoImports.isBusy) return
        val input = try { before.form.input() } catch (_: RuntimeException) {
            error("Проверьте дату, координаты и идентификатор музыкальной отметки."); return
        }
        mutable.value = before.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
            val result = saveDraft(DiaryDraftScope.DeviceLocal, id, input, before.revision)
            when (result) {
                is DomainResult.Success -> {
                    mutable.value = mutable.value.copy(revision = result.value.revision, dirty = false,
                        busy = false, editing = false)
                    if (exit) discard()
                }
                is DomainResult.Failure -> mutable.value = mutable.value.copy(busy = false,
                    error = if (result.error == DomainError.VersionConflict)
                        "Черновик изменён другим редактором. Ваш текст сохранён в форме." +
                            " Откройте актуальную запись перед повторным сохранением."
                    else "Не удалось сохранить. Введённые данные остались в форме.")
            }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    val restored = repository.get(DiaryDraftScope.DeviceLocal, id)
                    if (restored is DomainResult.Success && restored.value?.input == input) {
                        mutable.value = mutable.value.copy(revision = restored.value!!.revision, dirty = false)
                    }
                }
                throw cancelled
            } finally { mutable.value = mutable.value.copy(busy = false) }
        }
    }

    fun delete() {
        if (mutable.value.busy || photoImports.isBusy) return
        val current = mutable.value
        val id = current.id
        val revision = current.revision
        if (id == null || revision == null) return
        mutable.value = current.copy(busy = true)
        viewModelScope.launch {
            when (deleteDraft(DiaryDraftScope.DeviceLocal, id, revision)) {
                is DomainResult.Success -> { mutable.value = mutable.value.copy(busy = false); close() }
                is DomainResult.Failure -> mutable.value = mutable.value.copy(busy = false, error =
                    "Не удалось удалить черновик. Обновите список и повторите.")
            }
        }
    }
}

data class DiaryUiState(
    val drafts: List<DiaryDraft> = emptyList(), val loading: Boolean = true,
    val id: UUID? = null, val revision: Long? = null, val form: DiaryForm = DiaryForm(),
    val editing: Boolean = false, val dirty: Boolean = false, val busy: Boolean = false,
    val confirmExit: Boolean = false, val error: String? = null,
)

data class DiaryForm(
    val title: String = "", val date: String = "", val location: String = "",
    val latitude: String = "", val longitude: String = "", val body: String = "",
    val musicPin: String = "", val tracks: List<UUID> = emptyList(),
    val photos: List<DiaryDraftPhoto> = emptyList(),
) {
    fun input(): DiaryDraftInput {
        require(latitude.isBlank() == longitude.isBlank())
        val point = if (latitude.isBlank()) null else {
            val result = GeoPoint.create(latitude.toDouble(), longitude.toDouble())
            require(result is DomainResult.Success)
            result.value
        }
        return DiaryDraftInput(title, date.takeIf { it.isNotBlank() }?.let(LocalDate::parse),
            location.takeIf { it.isNotBlank() }, point, body, tracks, photos,
            musicPin.takeIf { it.isNotBlank() }?.let(UUID::fromString))
    }
    companion object {
        fun from(input: DiaryDraftInput) = DiaryForm(input.title, input.eventDate?.toString() ?: "",
            input.locationName ?: "", input.coordinates?.latitude?.toString() ?: "",
            input.coordinates?.longitude?.toString() ?: "", input.body, input.musicPinId?.toString() ?: "",
            input.trackIds, input.photos)
    }
}

private const val MAX_DRAFT_PHOTOS = 10
