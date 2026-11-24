package com.soundscape.app.presentation.generation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.soundscape.app.core.designsystem.ui.components.SoundScapeButton
import com.soundscape.app.core.designsystem.ui.components.SoundScapeButtonVariant
import com.soundscape.app.core.designsystem.ui.theme.SoundScapeTheme
import com.soundscape.app.core.domain.model.DomainError
import com.soundscape.app.core.domain.model.TrackAvailability
import com.soundscape.app.core.domain.model.Visibility
import com.soundscape.app.presentation.R
import java.util.Locale

private const val SECONDS_PER_MINUTE = 60L
private const val TRACK_ID_PREFIX_LENGTH = 8

@Composable
internal fun ColumnScope.WorkshopResultMessage(
    draft: WorkshopDraft?, offline: Boolean, back: () -> Unit, openConfirmed: () -> Unit,
) {
    val spacing = SoundScapeTheme.spacing
    Box(Modifier.weight(1f).padding(spacing.space24), contentAlignment = Alignment.Center) {
        when {
            draft?.confirmed == true -> WorkshopMessage("Плейлист сохранён",
                "Сервер подтвердил сохранение плейлиста «${draft.title}».", R.drawable.ic_circle_check,
                "Открыть плейлист", openConfirmed)
            offline -> WorkshopMessage("Результат недоступен без интернета",
                "На устройстве нет результата генерации.", R.drawable.ic_offline, "Вернуться к запросу", back)
            else -> WorkshopMessage("Результат недоступен", "Вернись к параметрам генерации.",
                R.drawable.ic_alert, "Вернуться к запросу", back)
        }
    }
}

@Composable
internal fun ColumnScope.WorkshopResultEditor(
    state: WorkshopState,
    draft: WorkshopDraft,
    model: PlaylistWorkshopViewModel,
    offline: Boolean,
    editing: Boolean,
    reordering: Boolean,
    signIn: () -> Unit,
    toggleEditing: () -> Unit,
    editConfirmed: () -> Unit,
    toggleReordering: () -> Unit,
    openCatalog: () -> Unit,
) {
    val spacing = SoundScapeTheme.spacing
    val canEdit = !draft.confirmed && !state.saving && !draft.uncertain
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(spacing.space16),
        verticalArrangement = Arrangement.spacedBy(spacing.space12)) {
        WorkshopResultStatus(state, draft, offline, signIn, model.catalogActions::refreshMetadata)
        WorkshopResultSummary(state, draft, canEdit, editing, model, toggleEditing, editConfirmed)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Композиции", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            if (canEdit) TextButton(toggleReordering, enabled = draft.tracks.size > 1) {
                Text(if (reordering) "Готово" else "Изменить порядок")
            }
        }
        WorkshopTracks(draft, state, canEdit, reordering, model::edit)
        if (canEdit)
            SoundScapeButton(
                "Добавить из каталога",
                openCatalog,
                Modifier.fillMaxWidth(),
                variant = SoundScapeButtonVariant.Outlined,
                enabled = draft.tracks.size < MAX_DRAFT_TRACKS,
                leadingIcon = { WorkshopIcon(R.drawable.ic_plus) },
            )
    }
}

@Composable
private fun WorkshopResultStatus(
    state: WorkshopState, draft: WorkshopDraft, offline: Boolean, signIn: () -> Unit, retryMetadata: () -> Unit,
) {
    when {
        state.saving ->
            WorkshopBanner("Сохраняем плейлист…", "Дождись подтверждения сервера.")
        state.saveError != null ->
            WorkshopBanner(
                state.saveError.message(),
                if (draft.uncertain) "Перед повтором проверим, сохранился ли плейлист."
                else "Изменения остались в редакторе.",
                error = true,
            )
        offline ->
            WorkshopBanner("Нет подключения", "Черновик доступен для редактирования.")
        state.metadataLoading ->
            WorkshopBanner(
                "Проверяем подборку…",
                "Сверяем композиции с музыкальным каталогом.",
            )
        state.metadataError ->
            WorkshopBanner(
                "Не удалось загрузить данные треков",
                "Результат генерации сохранён.",
                true,
            )
    }
    if (state.saveError == DomainError.Unauthenticated) {
        SoundScapeButton("Войти в аккаунт", signIn, Modifier.fillMaxWidth())
    }
    if (state.metadataError && !offline)
        TextButton(retryMetadata) { Text("Повторить проверку треков") }
}

@Composable
private fun WorkshopResultSummary(
    state: WorkshopState,
    draft: WorkshopDraft,
    canEdit: Boolean,
    editing: Boolean,
    model: PlaylistWorkshopViewModel,
    toggleEditing: () -> Unit,
    editConfirmed: () -> Unit,
) {
    val colors = SoundScapeTheme.colors
    val spacing = SoundScapeTheme.spacing
    WorkshopCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.space8),
        ) {
            WorkshopIcon(
                if (draft.confirmed) R.drawable.ic_circle_check else R.drawable.ic_sparkles
            )
            Text(
                if (draft.confirmed) "ПЛЕЙЛИСТ СОХРАНЁН" else "ПРЕДЛОЖЕНИЕ ИИ",
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = colors.textMuted,
            )
            if (canEdit)
                TextButton(toggleEditing) {
                    Text(if (editing) "Готово" else "Изменить")
                }
            if (draft.confirmed)
                TextButton(editConfirmed) {
                    Text("Изменить")
                }
        }
        if (editing && canEdit) {
            WorkshopField(draft.title, { value -> model.edit { it.copy(title = value) } },
                "Название плейлиста", MAX_DRAFT_TITLE_LENGTH, true, error = draft.title.isBlank())
            WorkshopField(draft.description, { value -> model.edit { it.copy(description = value) } },
                "Описание", MAX_DRAFT_DESCRIPTION_LENGTH, true, lines = 3)
        } else {
            Text(draft.title, style = MaterialTheme.typography.titleLarge)
            if (draft.description.isNotBlank()) Text(draft.description, color = colors.textMuted,
                style = MaterialTheme.typography.bodyMedium)
        }
        draft.context?.let { WorkshopBanner(it, "Места подборки") }
        draft.warnings.forEach {
            Text(it, color = colors.textMuted, style = MaterialTheme.typography.bodySmall)
        }
        HorizontalDivider(color = colors.outline)
        WorkshopDurations(state, draft)
        if (canEdit)
            VisibilityPicker(draft.visibility) { selection ->
                model.edit { it.copy(visibility = selection) }
            }
    }
}

@Composable
private fun WorkshopDurations(state: WorkshopState, draft: WorkshopDraft) {
    val allKnown = draft.tracks.all { it.id in state.metadata }
    val inAppKnown = allKnown && draft.tracks.all { it.availability != null }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        WorkshopStat("Треков", draft.tracks.size.toString())
        WorkshopStat(
            "Всего",
            if (allKnown)
                duration(draft.tracks.sumOf { state.metadata[it.id]?.durationMs ?: 0 })
            else "—",
        )
        WorkshopStat(
            "В приложении",
            if (inAppKnown)
                duration(
                    draft.tracks
                        .filter { it.availability == TrackAvailability.IN_APP }
                        .sumOf { state.metadata[it.id]?.durationMs ?: 0 }
                )
            else "—",
        )
    }
}

@Composable
internal fun WorkshopSaveFooter(state: WorkshopState, draft: WorkshopDraft, offline: Boolean, submit: () -> Unit) {
    val label = when {
        draft.uncertain -> "Проверить сохранение"
        state.saveError != null -> "Повторить сохранение"
        else -> "Сохранить плейлист"
    }
    WorkshopFooter(
        label, enabled = draft.valid && !offline, busy = state.saving, busyLabel = "Сохранение…", click = submit,
    )
}

@Composable
private fun WorkshopTracks(
    draft: WorkshopDraft,
    state: WorkshopState,
    canEdit: Boolean,
    reordering: Boolean,
    edit: ((WorkshopDraft) -> WorkshopDraft) -> Unit,
) {
    if (draft.tracks.isEmpty()) {
        WorkshopMessage("Список пуст", "Добавь треки для сохранения.", R.drawable.ic_music)
    }
    draft.tracks.forEachIndexed { index, track ->
        val data = state.metadata[track.id]
        val availability = when (track.availability) {
            TrackAvailability.IN_APP -> stringResource(R.string.search_in_app)
            TrackAvailability.EXTERNAL_ONLY -> stringResource(R.string.search_external)
            null -> null
        }
        val title = data?.title ?: "Трек · ${track.id.toString().take(TRACK_ID_PREFIX_LENGTH)}"
        val subtitle = listOfNotNull(data?.artists, data?.durationMs?.let(::duration), availability, track.reason)
            .joinToString(" · ").ifBlank { null }
        val move: ((Int) -> Unit)? = if (reordering) {
            { to -> edit { it.copy(tracks = it.tracks.reordered(index, to)) } }
        } else null
        key(track.id) {
            OrderedWorkshopRow(index, draft.tracks.size, title, subtitle, canEdit, move) {
                edit { it.copy(tracks = it.tracks.filterNot { row -> row.id == track.id }) }
            }
        }
    }
}

@Composable
private fun WorkshopStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = SoundScapeTheme.colors.textMuted)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun VisibilityPicker(value: Visibility, select: (Visibility) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Text("Видимость", style = MaterialTheme.typography.labelMedium)
    Box {
        OutlinedButton({ open = true }, Modifier.fillMaxWidth()) {
            Text(value.label(), Modifier.weight(1f))
            WorkshopIcon(R.drawable.ic_chevron_down)
        }
        DropdownMenu(open, { open = false }) {
            Visibility.entries.forEach { item ->
                DropdownMenuItem({ Text(item.label()) }, { select(item); open = false })
            }
        }
    }
}

private fun Visibility.label() = when (this) {
    Visibility.PRIVATE -> "Только я"
    Visibility.PUBLIC -> "Публичный"
    Visibility.UNLISTED -> "По ссылке"
}

private fun duration(milliseconds: Long): String {
    val seconds = milliseconds / 1000
    return String.format(Locale.ROOT, "%d:%02d", seconds / SECONDS_PER_MINUTE, seconds % SECONDS_PER_MINUTE)
}
