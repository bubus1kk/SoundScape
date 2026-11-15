package com.soundscape.app.presentation.diary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.soundscape.app.core.domain.model.Track
import java.util.UUID

@Composable
fun DiaryScreen(model: DiaryViewModel, tracks: List<Track>, onSearch: (String) -> Unit,
    onPickPhoto: () -> Unit, thumbnail: @Composable (UUID) -> Unit) {
    val state by model.state.collectAsState()
    var deleting by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Дневник", style = MaterialTheme.typography.headlineMedium)
        Text("Сохранено только на устройстве. Обновление приложения сохраняет" +
            " черновики. Очистка данных или удаление приложения удалит их." +
            " Перенос на другое устройство не гарантируется.")
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.loading) CircularProgressIndicator()
        if (state.id == null) {
            Button(onClick = model::create) { Text("Новый черновик") }
            TextButton(onClick = model::retry) { Text("Обновить список") }
            state.drafts.forEach { draft ->
                OutlinedButton(onClick = { model.open(draft) }) {
                    Column {
                        Text(draft.input.title.ifBlank { "Без названия" })
                        Text("Изменён: ${draft.updatedAt.atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()}")
                    }
                }
            }
            if (!state.loading && state.error == null && state.drafts.isEmpty()) Text("Черновиков пока нет")
        } else {
            DiaryEditor(model, state, tracks, onSearch, onPickPhoto, thumbnail) { deleting = true }
        }
    }
    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("Удалить черновик?") },
        text = { Text("Черновик и его локальные фотографии будут удалены.") },
        confirmButton = { TextButton(onClick = { deleting = false; model.delete() }) { Text("Удалить") } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text("Отмена") } })
}

@Composable
fun DiaryExitDialog(model: DiaryViewModel) {
    val state by model.state.collectAsState()
    val importing by model.photoImports.active.collectAsState()
    val operationsEnabled = !state.busy && importing == null
    if (state.confirmExit) AlertDialog(onDismissRequest = model::stay,
        title = { Text("Есть несохранённые изменения") },
        text = { Text(state.error ?: "Сохранить черновик перед выходом?") },
        confirmButton = { TextButton(enabled = operationsEnabled, onClick = { model.save(exit =
            true) }) { Text("Сохранить и выйти") } },
        dismissButton = { Column {
            TextButton(enabled = operationsEnabled, onClick = model::discard) { Text("Выйти без сохранения") }
            TextButton(enabled = operationsEnabled, onClick = model::stay) { Text("Продолжить редактирование") }
        } })
}

@Composable
private fun DiaryEditor(model: DiaryViewModel, state: DiaryUiState, tracks: List<Track>,
    onSearch: (String) -> Unit, onPickPhoto: () -> Unit, thumbnail: @Composable (UUID) -> Unit,
    onDelete: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val importing by model.photoImports.active.collectAsState()
    if (importing != null) {
        CircularProgressIndicator()
        Text("Импорт фотографии. Сохранение и выход временно недоступны.")
        TextButton(onClick = model.photoImports::cancel) { Text("Отменить импорт") }
    }
            val form = state.form
            val enabled = state.editing && !state.busy && importing == null
            fun change(value: DiaryForm) = model.change(value)
            OutlinedTextField(form.title, { change(form.copy(title = it)) }, enabled = enabled, label =
                { Text("Название") })
            OutlinedTextField(form.date, { change(form.copy(date = it)) }, enabled = enabled, label =
                { Text("Дата: ГГГГ-ММ-ДД") })
            OutlinedTextField(form.location, { change(form.copy(location = it)) }, enabled = enabled, label =
                { Text("Место") })
            OutlinedTextField(form.latitude, { change(form.copy(latitude = it)) }, enabled = enabled, label =
                { Text("Широта") })
            OutlinedTextField(form.longitude, { change(form.copy(longitude = it)) }, enabled = enabled, label =
                { Text("Долгота") })
            OutlinedTextField(form.body, { change(form.copy(body = it)) }, enabled = enabled, label =
                { Text("Текст") })
            OutlinedTextField(form.musicPin, { change(form.copy(musicPin = it)) }, enabled = enabled, label =
                { Text("UUID музыкальной отметки (необязательно)") })
            Text("Треки (${form.tracks.size}/100)")
            form.tracks.forEachIndexed { index, id ->
                Text(tracks.find { it.id == id }?.let { "${it.title} — ${it.artist}" } ?: "Трек каталога: $id")
                if (enabled) Row {
                    TextButton(onClick = { change(form.copy(tracks =
                        form.tracks.filterNot { it == id })) }) { Text("Убрать трек") }
                    if (index > 0) TextButton(onClick = {
                        val reordered = form.tracks.toMutableList()
                        java.util.Collections.swap(reordered, index, index - 1)
                        change(form.copy(tracks = reordered))
                    }) { Text("Выше") }
                }
            }
            if (enabled) {
                OutlinedTextField(query, { query = it }, label = { Text("Поиск треков каталога") })
                Button(onClick = { onSearch(query) }) { Text("Найти треки") }
                tracks.filterNot { it.id in form.tracks }.forEach { track ->
                    TextButton(enabled = form.tracks.size < 100,
                        onClick = { change(form.copy(tracks = form.tracks + track.id)) }) {
                        Text("Добавить: ${track.title} — ${track.artist}")
                    }
                }
            }
            DiaryPhotos(model, form, enabled, onPickPhoto, thumbnail)
            DiaryEditorActions(model, state, !state.busy && importing == null, onDelete)
}

@Composable
private fun DiaryPhotos(model: DiaryViewModel, form: DiaryForm, enabled: Boolean,
    onPickPhoto: () -> Unit, thumbnail: @Composable (UUID) -> Unit) {
            Text("Фотографии (${form.photos.size}/10)")
            form.photos.forEachIndexed { index, photo ->
                thumbnail(photo.id)
                if (enabled) Row {
                    TextButton(onClick = { model.change(form.copy(photos =
                        form.photos.filterNot { it == photo })) }) { Text("Убрать фото") }
                    if (index > 0) TextButton(onClick = {
                        val reordered = form.photos.toMutableList()
                        java.util.Collections.swap(reordered, index, index - 1)
                        model.change(form.copy(photos = reordered))
                    }) { Text("Выше") }
                }
            }
            if (enabled) Button(enabled = form.photos.size < 10, onClick = onPickPhoto) { Text("Добавить фото") }

}

@Composable
private fun DiaryEditorActions(model: DiaryViewModel, state: DiaryUiState,
    operationsEnabled: Boolean, onDelete: () -> Unit) {
            if (state.editing) Button(enabled = operationsEnabled, onClick =
                { model.save() }) { Text("Сохранить черновик") }
            else Button(enabled = operationsEnabled, onClick = model::edit) { Text("Редактировать") }
            if (state.revision != null) TextButton(enabled = operationsEnabled, onClick = onDelete) { Text("Удалить") }
            TextButton(enabled = operationsEnabled, onClick = { model.leave {} }) { Text("К списку") }
            if (!state.dirty) state.drafts.find { it.id == state.id }?.let { Text("Сохранено: ${it.updatedAt}") }
}
