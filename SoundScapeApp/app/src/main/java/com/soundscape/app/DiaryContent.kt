package com.soundscape.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.soundscape.app.core.data.diary.DiaryDraftPhotoStore
import com.soundscape.app.presentation.diary.DiaryScreen
import com.soundscape.app.presentation.diary.DiaryExitDialog
import com.soundscape.app.presentation.diary.DiarySearchViewModel
import com.soundscape.app.presentation.diary.DiaryViewModel
import java.util.UUID
import kotlinx.coroutines.CancellationException

@Composable
fun DiaryContent(model: DiaryViewModel, photos: DiaryDraftPhotoStore, bridge: DiarySearchViewModel) {
    val results by bridge.tracks.collectAsState()
    val searchError by bridge.error.collectAsState()
    val request by model.photoImports.selection.collectAsState()
    request?.let { operation ->
        androidx.compose.runtime.key(operation.id) { DiaryPhotoPicker(model, photos, operation) }
    }
    searchError?.let { Text(it) }
    DiaryScreen(model, results, bridge::search, onPickPhoto = { model.photoImports.begin() }) { id ->
        DiaryThumbnail(id, photos)
    }
}

@Composable
fun DiaryExitSurface(model: DiaryViewModel, photos: DiaryDraftPhotoStore) {
    DiaryPhotoCleanup(model, photos)
    DiaryExitDialog(model)
}

@Composable
private fun DiaryThumbnail(id: UUID, photos: DiaryDraftPhotoStore) {
    val image by produceState<android.graphics.Bitmap?>(null, id) {
        value = try { photos.thumbnail(id) }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { null }
    }
    image?.let { Image(it.asImageBitmap(), "Фотография черновика", Modifier.size(128.dp)) }
        ?: Text("Фото недоступно или загружается.")
}



@Composable
private fun DiaryPhotoCleanup(model: DiaryViewModel, photos: DiaryDraftPhotoStore) {
    val state by model.state.collectAsState()
    var previousPhotos by androidx.compose.runtime.remember { mutableStateOf(emptyList<UUID>()) }
    LaunchedEffect(state.id, state.form.photos) {
        val retained = if (state.id == null) emptyList() else state.form.photos.map { it.id }
        val removed = previousPhotos - retained.toSet()
        if (removed.isNotEmpty()) {
            try { photos.release(removed) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { model.error("Не удалось очистить неиспользуемые фото. Повторите открытие раздела.") }
        }
        previousPhotos = retained
    }
    LaunchedEffect(photos) {
        try { photos.release(emptyList()) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { model.error("Не удалось проверить фотографии. Черновики доступны; повторите" +
            " открытие раздела.") }
    }
}

@Composable
private fun DiaryPhotoPicker(model: DiaryViewModel, photos: DiaryDraftPhotoStore,
    operation: com.soundscape.app.presentation.diary.DiaryPhotoOperation) {
    var launched by rememberSaveable(operation.id.toString()) { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) model.photoImports.dismissed(operation)
        else model.photoImports.selected(operation, import = { photos.import(uri) },
            release = { photos.release(listOf(it)) })
    }
    LaunchedEffect(operation.id) {
        if (!launched) {
            launched = true
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }
}
