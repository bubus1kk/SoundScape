package com.soundscape.app.presentation.generation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.soundscape.app.core.designsystem.ui.components.SoundScapeButton
import com.soundscape.app.core.designsystem.ui.components.SoundScapeButtonVariant
import com.soundscape.app.core.designsystem.ui.theme.SoundScapeTheme
import com.soundscape.app.core.domain.repository.SearchFacet
import com.soundscape.app.presentation.R

@Composable
internal fun QuickGeneratorForm(state: WorkshopState, model: PlaylistWorkshopViewModel) {
    val form = state.form
    val spacing = SoundScapeTheme.spacing
    WorkshopHeading(
        "Быстрая генерация",
        "Опиши настроение, ситуацию или идею. Остальное подберёт ИИ.",
    )
    GeneratorField(
        form.quick,
        { value -> model.form { it.copy(quick = value) } },
        "Твой запрос *",
        !state.generating,
        tall = true,
        invalid = form.quick.isBlank(),
        placeholder = "Например, спокойная музыка для дождливого вечера…",
    )
    if (form.quick.isBlank())
        GeneratorValidation("Введи описание будущей подборки")
    Text("Примеры запросов", style = MaterialTheme.typography.labelMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(spacing.space8)) {
        listOf("Ночная поездка", "Спокойный вечер").forEach { example ->
            GeneratorExample(example, !state.generating) { model.form { it.copy(quick = example) } }
        }
    }
    GeneratorExample("Музыка для работы", !state.generating) {
        model.form { it.copy(quick = "Музыка для работы") }
    }
}

@Composable
internal fun AdvancedGeneratorForm(state: WorkshopState, model: PlaylistWorkshopViewModel) {
    val form = state.form
    val spacing = SoundScapeTheme.spacing
    val colors = SoundScapeTheme.colors
    WorkshopHeading("Расширенный запрос", "Настрой подборку более точно.")
    GeneratorField(form.advanced, { value -> model.form { it.copy(advanced = value) } },
        "Описание *", !state.generating)
    Row(horizontalArrangement = Arrangement.spacedBy(spacing.space8)) {
        FacetPicker(SearchFacet.Genre, form.genre, state, model, Modifier.weight(1f))
        FacetPicker(SearchFacet.Mood, form.mood, state, model, Modifier.weight(1f))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Количество треков", style = MaterialTheme.typography.labelLarge)
        Text(form.count.toString(), style = MaterialTheme.typography.labelLarge)
    }
    GeneratorSlider(form.count, !state.generating) { value ->
        model.form { it.copy(count = value) }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("5", style = MaterialTheme.typography.labelSmall)
        Text("50", style = MaterialTheme.typography.labelSmall)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(form.onlyInApp, { checked -> model.form { it.copy(onlyInApp = checked) } },
            enabled = !state.generating, colors = CheckboxDefaults.colors(
                checkedColor = colors.primary, uncheckedColor = colors.outline))
        Text("Только воспроизводимые в приложении", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun PlaceGeneratorForm(state: WorkshopState, model: PlaylistWorkshopViewModel, choosePlace: () -> Unit) {
    val form = state.form
    val spacing = SoundScapeTheme.spacing
    val colors = SoundScapeTheme.colors
    WorkshopHeading(
        "Музыка по месту",
        "Учитывает атмосферу выбранного города или отметки.",
    )
    Text(
        "Город или музыкальное место *",
        style = MaterialTheme.typography.labelMedium,
    )
    GeneratorSelector(
        form.place?.let {
            listOfNotNull(it.title, it.subtitle).joinToString(", ")
        } ?: "Выбрать место",
        !state.generating,
        invalid = form.place == null,
        open = choosePlace,
    )
    if (form.place == null)
        GeneratorValidation("Выбери город или музыкальное место")
    Surface(shape = MaterialTheme.shapes.small, color = colors.background) {
        Row(
            Modifier.fillMaxWidth().padding(spacing.space12),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.space12),
        ) {
            GeneratorBadge(R.drawable.ic_map_pin, spacing.space40)
            Column(verticalArrangement = Arrangement.spacedBy(spacing.space4)) {
                Text(
                    form.place?.title ?: "Место не выбрано",
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    if (form.place == null) "Для генерации требуется выбор"
                    else "Географическая подборка",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textMuted,
                )
            }
        }
    }
    GeneratorField(form.placePrompt, { value -> model.form { it.copy(placePrompt = value) } },
        "Дополнительные пожелания", !state.generating)
}

@Composable
internal fun JourneyGeneratorForm(
    state: WorkshopState,
    model: PlaylistWorkshopViewModel,
    reorderStops: Boolean,
    choosePlace: () -> Unit,
    toggleReordering: () -> Unit,
) {
    val form = state.form
    val spacing = SoundScapeTheme.spacing
    val colors = SoundScapeTheme.colors
    WorkshopHeading("Музыка по маршруту", "Выбери не менее двух мест. Порядок влияет на подборку.")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Места маршрута", style = MaterialTheme.typography.labelLarge)
        Text(
            "${form.stops.size} выбрано",
            color = colors.textMuted,
            style = MaterialTheme.typography.labelMedium,
        )
    }
    JourneyStops(state, model, reorderStops)
    Row(
        horizontalArrangement = Arrangement.spacedBy(spacing.space12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SoundScapeButton(
            "Добавить место",
            choosePlace,
            Modifier.weight(1f),
            variant = SoundScapeButtonVariant.Secondary,
            enabled = !state.generating && form.stops.size < MAX_JOURNEY_STOPS,
            leadingIcon = { WorkshopIcon(R.drawable.ic_plus) },
        )
        Surface(
            onClick = toggleReordering,
            enabled = !state.generating && form.stops.size > 1,
            shape = MaterialTheme.shapes.medium,
            color = colors.primarySoft,
        ) {
            Box(
                Modifier.size(spacing.space48),
                contentAlignment = Alignment.Center,
            ) {
                WorkshopIcon(
                    R.drawable.ic_arrow_down_up,
                    "Изменить порядок остановок",
                )
            }
        }
    }
    if (form.stops.size < 2)
        GeneratorValidation("Добавь ещё минимум одно место")
    Row(
        horizontalArrangement = Arrangement.spacedBy(spacing.space4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WorkshopIcon(R.drawable.ic_info)
        Text("Продолжительность — по трекам, не по времени поездки.",
            color = colors.textMuted, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun JourneyStops(state: WorkshopState, model: PlaylistWorkshopViewModel, reorderStops: Boolean) {
    val form = state.form
    val spacing = SoundScapeTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.space8)) {
        form.stops.forEachIndexed { index, stop ->
            key(stop.id) {
                GeneratorStop(
                    index,
                    form.stops.size,
                    stop.title,
                    !state.generating,
                    reorderStops,
                    move = { to ->
                        model.form {
                            it.copy(stops = it.stops.reordered(index, to))
                        }
                    },
                    remove = {
                        model.form {
                            it.copy(
                                stops =
                                    it.stops.filterNot { item ->
                                        item.id == stop.id
                                    }
                            )
                        }
                    },
                )
            }
        }
    }
}
