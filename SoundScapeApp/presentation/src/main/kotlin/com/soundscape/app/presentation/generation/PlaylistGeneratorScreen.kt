package com.soundscape.app.presentation.generation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import com.soundscape.app.core.designsystem.ui.components.SoundScapeButton
import com.soundscape.app.core.designsystem.ui.components.SoundScapeButtonVariant
import com.soundscape.app.core.designsystem.ui.theme.SoundScapeTheme
import com.soundscape.app.core.domain.model.DomainError
import com.soundscape.app.core.domain.repository.SearchCategory
import com.soundscape.app.presentation.R

@Composable
fun PlaylistGeneratorScreen(
    state: WorkshopState,
    model: PlaylistWorkshopViewModel,
    offline: Boolean,
    back: () -> Unit,
    signIn: () -> Unit,
    openResult: () -> Unit,
    chromeVisible: (Boolean) -> Unit = {},
) {
    var reorderStops by rememberSaveable { mutableStateOf(false) }
    var choosingPlace by rememberSaveable { mutableStateOf(false) }
    var requestSignIn by rememberSaveable { mutableStateOf(false) }
    val block = generatorBlock(state, requestSignIn, offline)
    val validInput = state.form.request() != null
    val focus = LocalFocusManager.current
    SideEffect { chromeVisible(block == null) }
    Column(Modifier.fillMaxSize().imePadding().background(SoundScapeTheme.colors.background)) {
        if (block != null) {
            GeneratorBlockContent(block, state, offline, signIn, model::dismissGenerationError)
        } else {
            GeneratorTopBar(back, generatorShowsMark(state, validInput))
            GeneratorBody(
                state, model, openResult, reorderStops,
                choosePlace = { choosingPlace = true }, toggleReordering = { reorderStops = !reorderStops },
            )
            GeneratorSubmit(state, validInput) {
                focus.clearFocus()
                if (state.account == null) requestSignIn = true else model.generate(openResult)
            }
        }
    }
    if (choosingPlace) GeneratorPlaceLookup(state, model, offline) { choosingPlace = false }
}

private fun generatorBlock(state: WorkshopState, requestSignIn: Boolean, offline: Boolean): String? =
    when {
        state.generationError is DomainError.RateLimited -> "Лимит генераций исчерпан"
        requestSignIn && state.account == null || state.generationError == DomainError.Unauthenticated ->
            "Необходимо войти в аккаунт"
        offline || state.generationError == DomainError.Network -> "Нет подключения к интернету"
        else -> null
    }

private fun generatorShowsMark(state: WorkshopState, validInput: Boolean): Boolean {
    val promptMode = state.form.mode in listOf(GenerationMode.QUICK, GenerationMode.ADVANCED)
    return validInput && !state.generating && state.generationError != DomainError.Server && promptMode
}

@Composable
private fun ColumnScope.GeneratorBlockContent(
    block: String, state: WorkshopState, offline: Boolean, signIn: () -> Unit, dismiss: () -> Unit,
) {
    val spacing = SoundScapeTheme.spacing
    val auth = block == "Необходимо войти в аккаунт"
    val quota = state.generationError is DomainError.RateLimited
    Box(Modifier.weight(1f).padding(spacing.space24), contentAlignment = Alignment.Center) {
        GeneratorBlocked(block, when {
            auth -> "Создание плейлистов с помощью ИИ доступно после авторизации в SoundScape."
            quota -> "Новая подборка будет доступна после восстановления лимита."
            else -> "Для генерации требуется интернет. Введённые параметры сохраняются."
        }, if (auth) R.drawable.ic_lock else if (quota) R.drawable.ic_clock else R.drawable.ic_offline,
            if (auth) "Войти в аккаунт" else if (quota || !offline) "Вернуться" else null,
            if (auth) signIn else dismiss)
    }
}

@Composable
private fun ColumnScope.GeneratorBody(
    state: WorkshopState,
    model: PlaylistWorkshopViewModel,
    openResult: () -> Unit,
    reorderStops: Boolean,
    choosePlace: () -> Unit,
    toggleReordering: () -> Unit,
) {
    val spacing = SoundScapeTheme.spacing
    val colors = SoundScapeTheme.colors
    val form = state.form
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())
        .padding(horizontal = spacing.space16).padding(top = if (state.generating || state.generationError != null)
            spacing.space12 else spacing.space16, bottom = spacing.space16),
        verticalArrangement = Arrangement.spacedBy(spacing.space20)) {
        GeneratorNotices(state)
        Row(
            horizontalArrangement = Arrangement.spacedBy(spacing.space12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GeneratorBadge(R.drawable.ic_sparkles, spacing.space48)
            Column {
                Text("Твоя музыка с ИИ", style = MaterialTheme.typography.titleLarge)
                Text("Выбери способ создания подборки", color = colors.textMuted,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(spacing.space8)) {
            GenerationMode.entries.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.space8)) {
                    row.forEach { mode ->
                        ModeCard(mode, form.mode == mode, Modifier.weight(1f), !state.generating) {
                            model.form { it.copy(mode = mode) }
                        }
                    }
                }
            }
        }
        if (state.draft != null) {
            SoundScapeButton("Вернуться к результату", openResult, Modifier.fillMaxWidth(),
                enabled = !state.generating, variant = SoundScapeButtonVariant.Outlined)
        }
        GeneratorFormCard {
            when (form.mode) {
                GenerationMode.QUICK -> QuickGeneratorForm(state, model)
                GenerationMode.ADVANCED -> AdvancedGeneratorForm(state, model)
                GenerationMode.PLACE -> PlaceGeneratorForm(state, model, choosePlace)
                GenerationMode.JOURNEY ->
                    JourneyGeneratorForm(state, model, reorderStops, choosePlace, toggleReordering)
            }
        }
    }
}

@Composable
private fun GeneratorSubmit(state: WorkshopState, validInput: Boolean, onSubmit: () -> Unit) {
    GeneratorFooter(
        if (state.generationError == DomainError.Server) "Повторить генерацию"
        else "Сгенерировать плейлист",
        enabled = validInput && state.sessionReady && state.draft?.uncertain != true,
        busy = state.generating, click = onSubmit)
}

@Composable
private fun GeneratorPlaceLookup(
    state: WorkshopState, model: PlaylistWorkshopViewModel, offline: Boolean, dismiss: () -> Unit,
) {
    val form = state.form
    WorkshopLookup(
        "Выбрать место",
        SearchCategory.PLACE,
        state,
        model,
        offline,
        dismiss = dismiss,
        selected =
            form.stops
                .map { it.id }
                .toSet()
                .takeIf { form.mode == GenerationMode.JOURNEY }
                .orEmpty(),
    ) { hit ->
        model.form {
            if (
                it.mode == GenerationMode.JOURNEY &&
                    it.stops.size < MAX_JOURNEY_STOPS &&
                    it.stops.none { stop -> stop.id == hit.id }
            ) {
                it.copy(stops = it.stops + hit)
            } else if (it.mode == GenerationMode.PLACE) it.copy(place = hit) else it
        }
        dismiss()
    }
}

@Composable
private fun GeneratorNotices(state: WorkshopState) {
    if (state.generating) GeneratorNotice("Подбираем музыку…",
        "Проверяем кандидатов из музыкального каталога.", loading = true)
    state.generationError?.let { error ->
        val noCandidates = error is DomainError.Validation && error.reason == "no_candidates"
        GeneratorNotice(if (error == DomainError.Server) "Не удалось создать подборку" else error.message(),
            if (noCandidates) "Попробуй изменить запрос или параметры."
            else "Параметры не потеряны. Генерацию можно повторить.", loading = false)
    }
}
