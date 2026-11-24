package com.soundscape.app.presentation.generation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soundscape.app.core.designsystem.ui.theme.SoundScapeTheme
import com.soundscape.app.core.domain.repository.SearchFacet
import com.soundscape.app.core.domain.repository.SearchOption
import com.soundscape.app.presentation.R
import kotlin.math.roundToInt

private const val GENERATOR_PROMPT_LIMIT = 1000

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GeneratorSlider(count: Int, enabled: Boolean, change: (Int) -> Unit) {
    val colors = SoundScapeTheme.colors
    val spacing = SoundScapeTheme.spacing
    val counts = 5f..50f
    Slider(count.toFloat(), { change(it.roundToInt()) }, enabled = enabled, valueRange = counts,
        thumb = {
            Spacer(Modifier.size(18.dp).background(if (enabled) colors.primary else colors.outline, CircleShape))
        },
        track = { slider ->
            val fraction = ((slider.value - counts.start) / (counts.endInclusive - counts.start)).coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth().height(spacing.space4).background(colors.playerTrack, CircleShape)) {
                Spacer(Modifier.fillMaxWidth(fraction).fillMaxHeight()
                    .background(if (enabled) colors.primary else colors.outline, CircleShape))
            }
        })
}

@Composable
internal fun ModeCard(
    mode: GenerationMode,
    selected: Boolean,
    modifier: Modifier,
    enabled: Boolean,
    click: () -> Unit,
) {
    val colors = SoundScapeTheme.colors
    val spacing = SoundScapeTheme.spacing
    val (title, subtitle, icon) = when (mode) {
        GenerationMode.QUICK -> Triple("Быстрый", "По запросу", R.drawable.ic_zap)
        GenerationMode.ADVANCED -> Triple("Расширенный", "Точные параметры", R.drawable.ic_sliders_horizontal)
        GenerationMode.PLACE -> Triple("По месту", "Город или точка", R.drawable.ic_map_pin)
        GenerationMode.JOURNEY -> Triple("По маршруту", "Музыкальное путешествие", R.drawable.ic_route)
    }
    Surface(modifier, shape = MaterialTheme.shapes.medium, color = if (selected) colors.primarySoft else colors.surface,
        border = BorderStroke(1.dp, if (selected) colors.primary else colors.surfaceAlt)) {
        Column(Modifier.fillMaxWidth().selectable(selected, enabled, onClick = click).heightIn(min = 98.dp)
            .padding(spacing.space12), verticalArrangement = Arrangement.spacedBy(spacing.space4)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Icon(
                    painterResource(icon),
                    null,
                    Modifier.size(spacing.space24),
                    tint = if (selected) colors.primary else colors.textMuted,
                )
                if (selected)
                    Icon(
                        painterResource(R.drawable.ic_circle_check),
                        null,
                        Modifier.size(18.dp),
                        tint = colors.primary,
                    )
            }
            Spacer(Modifier.height(spacing.space4))
            Text(title, style = MaterialTheme.typography.labelLarge, color = colors.text)
            Text(subtitle, color = colors.textMuted, style = MaterialTheme.typography.bodySmall.copy(
                fontSize = 10.sp, lineHeight = 14.sp))
        }
    }
}

@Composable
internal fun FacetPicker(facet: SearchFacet, selected: SearchOption?, state: WorkshopState,
    model: PlaylistWorkshopViewModel, modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    val genre = facet == SearchFacet.Genre
    val any = if (genre) "Любой" else "Любое"
    Column(modifier, verticalArrangement = Arrangement.spacedBy(SoundScapeTheme.spacing.space8)) {
        Text(if (genre) "Жанр" else "Настроение", style = MaterialTheme.typography.labelMedium)
        Box {
            GeneratorSelector(selected?.name ?: any, !state.generating, open = {
                model.catalogActions.loadOptions(facet)
                open = true
            })
            DropdownMenu(open, { open = false }) {
                fun choose(option: SearchOption?) {
                    model.form { if (genre) it.copy(genre = option) else it.copy(mood = option) }
                    open = false
                }
                DropdownMenuItem({ Text(any) }, { choose(null) })
                val options = state.options[facet].orEmpty()
                options.forEach { option -> DropdownMenuItem({ Text(option.name) }, { choose(option) }) }
                if (options.isEmpty()) DropdownMenuItem({ Text(when {
                    facet in state.optionErrors -> "Справочник недоступен"
                    facet in state.options -> "Список пока пуст"
                    else -> "Загрузка…"
                }) }, {}, enabled = false)
            }
        }
    }
}

@Composable
internal fun GeneratorFormCard(content: @Composable ColumnScope.() -> Unit) {
    val spacing = SoundScapeTheme.spacing
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = SoundScapeTheme.colors.surface) {
        Column(Modifier.padding(horizontal = spacing.space16, vertical = spacing.space20),
            verticalArrangement = Arrangement.spacedBy(spacing.space12), content = content)
    }
}

@Composable
internal fun GeneratorField(value: String, change: (String) -> Unit, label: String, enabled: Boolean,
    tall: Boolean = false, invalid: Boolean = false, placeholder: String? = null) {
    val colors = SoundScapeTheme.colors
    val spacing = SoundScapeTheme.spacing
    val shape = MaterialTheme.shapes.medium
    var focused by remember { mutableStateOf(false) }
    BasicTextField(value, { change(it.take(GENERATOR_PROMPT_LIMIT)) },
        modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        enabled = enabled, textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.text),
        cursorBrush = SolidColor(colors.primary), maxLines = if (tall) 5 else 4,
        decorationBox = { input ->
            Column(verticalArrangement = Arrangement.spacedBy(spacing.space8)) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = colors.text)
                Box(Modifier.fillMaxWidth().heightIn(min = if (tall)
                    spacing.space48 + spacing.space48 + spacing.space8 else spacing.space48 + spacing.space32)
                    .background(colors.background, shape)
                    .border(1.dp, when {
                        invalid -> colors.danger
                        focused -> colors.primary
                        else -> colors.outline
                    }, shape).padding(spacing.space12), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty() && placeholder != null) Text(placeholder,
                        style = MaterialTheme.typography.bodyMedium, color = colors.textMuted)
                    input()
                }
            }
        })
}

@Composable
internal fun GeneratorValidation(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = SoundScapeTheme.colors.danger)
}

@Composable
internal fun GeneratorExample(text: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = SoundScapeTheme.colors
    val spacing = SoundScapeTheme.spacing
    Box(Modifier.heightIn(min = spacing.space40).clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center) {
        Surface(shape = CircleShape, color = colors.surface, border = BorderStroke(1.dp, colors.outline)) {
            Text(text, Modifier.padding(horizontal = spacing.space12, vertical = spacing.space8),
                style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
        }
    }
}

@Composable
internal fun GeneratorSelector(text: String, enabled: Boolean, invalid: Boolean = false, open: () -> Unit) {
    val colors = SoundScapeTheme.colors
    val spacing = SoundScapeTheme.spacing
    Surface(
        onClick = open,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = colors.background,
        border = BorderStroke(1.dp, if (invalid) colors.danger else colors.outline),
    ) {
        Row(
            Modifier.heightIn(min = spacing.space48).padding(horizontal = spacing.space12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text,
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = if (invalid) colors.textMuted else colors.text,
            )
            Icon(
                painterResource(R.drawable.ic_chevron_right),
                null,
                Modifier.size(spacing.space16),
                tint = colors.textMuted,
            )
        }
    }
}
