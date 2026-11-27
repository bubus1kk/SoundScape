package com.soundscape.app.presentation.generation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.soundscape.app.core.designsystem.ui.components.SoundScapeButton
import com.soundscape.app.core.designsystem.ui.components.SoundScapeButtonVariant
import com.soundscape.app.core.designsystem.ui.theme.SoundScapeTheme
import com.soundscape.app.core.domain.repository.SearchCategory
import com.soundscape.app.core.domain.repository.SearchHit
import com.soundscape.app.presentation.R
import com.soundscape.app.presentation.profile.ProfileHeader
import java.util.UUID

private enum class ResultSheet { Catalog, Save, Leave }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistResultScreen(
    state: WorkshopState,
    model: PlaylistWorkshopViewModel,
    offline: Boolean,
    back: () -> Unit,
    signIn: () -> Unit,
) {
    val draft = state.draft
    var sheet by rememberSaveable { mutableStateOf<ResultSheet?>(null) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var reordering by rememberSaveable { mutableStateOf(false) }
    var showConfirmed by rememberSaveable { mutableStateOf(false) }
    val colors = SoundScapeTheme.colors
    val goBack = {
        if (!state.saving) {
            if (draft == null || draft.confirmed || draft.uncertain) back() else sheet = ResultSheet.Leave
        }
    }
    BackHandler(onBack = goBack)
    Column(Modifier.fillMaxSize().imePadding().background(colors.background)) {
        ProfileHeader(R.string.generation_result_title, goBack, compact = true)
        if (draft == null || draft.confirmed && !showConfirmed) {
            WorkshopResultMessage(draft, offline, back) { showConfirmed = true }
        } else {
            WorkshopResultEditor(
                state, draft, model, offline, editing, reordering, signIn,
                toggleEditing = { editing = !editing },
                editConfirmed = {
                    model.editConfirmed()
                    editing = true
                    showConfirmed = false
                },
                toggleReordering = { reordering = !reordering },
                openCatalog = { sheet = ResultSheet.Catalog },
            )
            if (!draft.confirmed) WorkshopSaveFooter(state, draft, offline) {
                if (draft.uncertain || state.account != draft.owner) model.save() else sheet = ResultSheet.Save
            }
        }
    }
    if (draft != null) WorkshopResultSheet(sheet, state, draft, model, offline, back) { sheet = null }
}

@Composable
private fun WorkshopResultSheet(
    sheet: ResultSheet?,
    state: WorkshopState,
    draft: WorkshopDraft,
    model: PlaylistWorkshopViewModel,
    offline: Boolean,
    back: () -> Unit,
    dismiss: () -> Unit,
) {
    when (sheet) {
        ResultSheet.Catalog -> WorkshopLookup("Добавить из каталога", SearchCategory.TRACK, state, model,
            offline, dismiss, draft.tracks.map { it.id }.toSet(), model::add)
        ResultSheet.Save -> ConfirmationSheet("Сохранить подборку?",
            "Плейлист будет сохранён с твоими изменениями.\n${draft.title} · ${draft.tracks.size} треков",
            "Подтвердить сохранение", "Продолжить редактирование", dismiss) {
            dismiss()
            model.save()
        }
        ResultSheet.Leave -> ConfirmationSheet("Выйти без сохранения?",
            "Черновик можно открыть после возврата к параметрам. Его также можно удалить.",
            "Вернуться к запросу", "Продолжить редактирование", dismiss,
            secondary = { dismiss(); model.discard(); back() }) {
            dismiss()
            back()
        }
        null -> Unit
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkshopLookup(
    title: String,
    category: SearchCategory,
    state: WorkshopState,
    model: PlaylistWorkshopViewModel,
    offline: Boolean,
    dismiss: () -> Unit,
    selected: Set<UUID>,
    onPick: (SearchHit) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val spacing = SoundScapeTheme.spacing
    LaunchedEffect(category, query, offline) {
        if (offline) model.catalogActions.closeLookup() else model.catalogActions.lookup(category, query)
    }
    DisposableEffect(Unit) { onDispose { model.catalogActions.closeLookup() } }
    ModalBottomSheet(onDismissRequest = dismiss, containerColor = SoundScapeTheme.colors.surface) {
        Column(Modifier.fillMaxWidth().heightIn(min = 300.dp, max = 520.dp).imePadding()
            .padding(horizontal = spacing.space16), verticalArrangement = Arrangement.spacedBy(spacing.space12)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            WorkshopField(query, { query = it }, if (category == SearchCategory.TRACK)
                "Поиск по названию или исполнителю" else "Название места", MAX_LOOKUP_LENGTH, !offline)
            val lookup = state.lookup
            LazyColumn(Modifier.weight(1f)) {
                when {
                    offline ->
                        item {
                            Text(
                                if (category == SearchCategory.TRACK)
                                    "Каталог недоступен без интернета"
                                else "Нет подключения"
                            )
                        }
                    lookup.loading && lookup.items.isEmpty() -> item { Text("Загрузка…") }
                    lookup.error != null -> item {
                        Text(lookup.error.message())
                        TextButton({ model.catalogActions.lookup(category, query, retry = true) }) { Text("Повторить") }
                    }
                    query.isNotBlank() && lookup.items.isEmpty() -> item { Text("Ничего не найдено") }
                }
                items(lookup.items, key = { it.id.toString() }) { hit ->
                    WorkshopLookupRow(hit, hit.id in selected, offline, onPick)
                }
                if (lookup.hasMore && !offline) item {
                    TextButton(
                        { model.catalogActions.lookup(category, query, more = true) },
                        enabled = !lookup.loading,
                    ) {
                        Text("Показать ещё")
                    }
                }
            }
            SoundScapeButton("Готово", dismiss, Modifier.fillMaxWidth().testTag("workshop-lookup-done"))
            Spacer(Modifier.height(spacing.space12))
        }
    }
}

@Composable
private fun WorkshopLookupRow(hit: SearchHit, selected: Boolean, offline: Boolean, onPick: (SearchHit) -> Unit) {
    TextButton({ onPick(hit) }, Modifier.fillMaxWidth(), enabled = !offline && !selected) {
        Column(Modifier.weight(1f)) {
            Text(hit.title)
            hit.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        WorkshopIcon(if (selected) R.drawable.ic_circle_check else R.drawable.ic_plus)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfirmationSheet(title: String, body: String, confirmLabel: String, cancelLabel: String,
    dismiss: () -> Unit, secondary: (() -> Unit)? = null, confirm: () -> Unit) {
    val spacing = SoundScapeTheme.spacing
    ModalBottomSheet(onDismissRequest = dismiss, containerColor = SoundScapeTheme.colors.surface) {
        Column(Modifier.fillMaxWidth().padding(spacing.space16),
            verticalArrangement = Arrangement.spacedBy(spacing.space12)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body)
            SoundScapeButton(confirmLabel, confirm, Modifier.fillMaxWidth())
            SoundScapeButton(cancelLabel, dismiss, Modifier.fillMaxWidth(), variant = SoundScapeButtonVariant.Outlined)
            if (secondary != null) TextButton(secondary, Modifier.align(Alignment.CenterHorizontally)) {
                Text("Удалить черновик")
            }
        }
    }
}
