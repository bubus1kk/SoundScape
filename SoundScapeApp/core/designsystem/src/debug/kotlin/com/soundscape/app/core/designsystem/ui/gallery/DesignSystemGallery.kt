package com.soundscape.app.core.designsystem.ui.gallery

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import com.soundscape.app.core.designsystem.ui.components.*
import com.soundscape.app.core.designsystem.ui.theme.*

@Composable
fun DesignSystemGallery(onDarkModeChanged: (Boolean) -> Unit = {}) {
    var presetName by rememberSaveable { mutableStateOf(SoundScapePreset.Atlas.name) }
    var modeName by rememberSaveable { mutableStateOf(SoundScapeMode.System.name) }
    var dynamic by rememberSaveable { mutableStateOf(false) }
    val options = ThemeOptions(SoundScapePreset.valueOf(presetName), SoundScapeMode.valueOf(modeName), dynamic)
    val resolved = resolveTheme(options, isSystemInDarkTheme(), Build.VERSION.SDK_INT)
    LaunchedEffect(resolved.dark) { onDarkModeChanged(resolved.dark) }
    SoundScapeTheme(options) {
        val spacing = SoundScapeTheme.spacing
        Scaffold { insets ->
            Column(
                Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets)
                    .imePadding().verticalScroll(rememberScrollState()).padding(spacing.space16),
                verticalArrangement = Arrangement.spacedBy(spacing.space12),
            ) {
                Heading("SoundScape — UI Demo")
                Text("Только debug. Изменения оформления не затрагивают обычный запуск приложения.")
                Text("Пресет")
                for (preset in SoundScapePreset.entries) {
                    SoundScapeFilterChip(preset.name, preset.name == presetName, { presetName = preset.name })
                }
                Text("Режим")
                for (mode in SoundScapeMode.entries) {
                    SoundScapeFilterChip(mode.name, mode.name == modeName, { modeName = mode.name })
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.space8)) {
                    Text("Dynamic Color", Modifier.weight(1f))
                    Switch(dynamic, { dynamic = it }, modifier = Modifier.semantics {
                        this[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription] = listOf("Dynamic Color")
                    })
                }
                Text("Применено: ${if (resolved.dark) "Dark" else "Light"}; " +
                    if (resolved.dynamicColor) "Material You. Выбранный пресет ${options.preset} сохранён." else "${options.preset}, статическая палитра.")
                if (Build.VERSION.SDK_INT < 31) {
                    Text("Dynamic Color недоступен на API ${Build.VERSION.SDK_INT}. " +
                        "При включённом переключателе применяется статическая палитра выбранного пресета.")
                }
                SoundScapeButton("Сброс: Atlas / System / Dynamic off", {
                    presetName = SoundScapePreset.Atlas.name
                    modeName = SoundScapeMode.System.name
                    dynamic = false
                })
                Heading("13 исходных ролей выбранной статической палитры")
                Text("${options.preset} / ${if (resolved.dark) "Dark" else "Light"}. " +
                    if (resolved.dynamicColor) "Справочные значения: сейчас не применяются." else "Эта палитра применяется сейчас.")
                ColorRows(roles(SoundScapePalettes.colors(options.preset, resolved.dark)))
                Heading("Фактически применённые семантические токены")
                Text(if (resolved.dynamicColor) "Реальные динамические значения системы." else "Статические значения активной темы.")
                ColorRows(roles(SoundScapeTheme.colors))
                Heading("Material 3 и расширения")
                val scheme = MaterialTheme.colorScheme
                ColorRows(listOf(
                    "Material.primary" to scheme.primary, "Material.onPrimary" to scheme.onPrimary,
                    "Material.primaryContainer" to scheme.primaryContainer,
                    "Material.onPrimaryContainer" to scheme.onPrimaryContainer,
                    "Material.background" to scheme.background, "Material.onBackground" to scheme.onBackground,
                    "Material.surface" to scheme.surface, "Material.onSurface" to scheme.onSurface,
                    "Material.surfaceContainerHighest" to scheme.surfaceContainerHighest,
                    "Material.outline" to scheme.outline, "Material.error" to scheme.error,
                    "Material.onError" to scheme.onError,
                    "success" to SoundScapeTheme.colors.success,
                    "danger" to SoundScapeTheme.colors.danger,
                    "playerTrack" to SoundScapeTheme.colors.playerTrack,
                    "playerProgress" to SoundScapeTheme.colors.playerProgress,
                ))
                TypographyExamples()
                DimensionExamples()
                ButtonExamples()
                FieldExamples()
                ChipExamples()
            }
        }
    }
}

private fun roles(c: SoundScapeColors): List<Pair<String, Color>> = listOf(
    "background" to c.background, "surface" to c.surface, "surface-alt" to c.surfaceAlt,
    "text" to c.text, "text-muted" to c.textMuted, "primary" to c.primary,
    "on-primary" to c.onPrimary, "primary-soft" to c.primarySoft,
    "on-primary-soft" to c.onPrimarySoft, "outline" to c.outline,
    "success" to c.success, "danger" to c.danger, "player-track" to c.playerTrack,
)

@Composable
private fun Heading(text: String) {
    Text(text, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
}

@Composable
private fun ColorRows(colors: List<Pair<String, Color>>) {
    val spacing = SoundScapeTheme.spacing
    for ((name, color) in colors) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.space12)) {
            Surface(Modifier.size(spacing.space40), color = color,
                shape = MaterialTheme.shapes.small,
                border = androidx.compose.foundation.BorderStroke(
                    androidx.compose.ui.unit.Dp.Hairline, SoundScapeTheme.colors.outline)) { }
            Text("$name  #${Integer.toHexString(color.toArgb()).uppercase().padStart(8, '0')}",
                Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun TypographyExamples() {
    Heading("Типографика: 10 стилей")
    val t = MaterialTheme.typography
    val styles: List<Pair<String, TextStyle>> = listOf(
        "Display" to t.displaySmall, "Headline L" to t.headlineLarge,
        "Headline M" to t.headlineMedium, "Title L" to t.titleLarge,
        "Title M" to t.titleMedium, "Body L" to t.bodyLarge,
        "Body M" to t.bodyMedium, "Label L" to t.labelLarge,
        "Label M" to t.labelMedium, "Caption" to t.bodySmall,
    )
    for ((name, style) in styles) {
        Text(name, style = t.bodySmall, color = SoundScapeTheme.colors.textMuted)
        Text("Музыкальное путешествие — SoundScape 0123456789. Длинная строка для проверки переноса и системного масштаба шрифта.", style = style)
    }
}

@Composable
private fun DimensionExamples() {
    Heading("Отступы")
    val s = SoundScapeTheme.spacing
    val spaces = listOf(s.space0, s.space4, s.space8, s.space12, s.space16,
        s.space20, s.space24, s.space32, s.space40, s.space48)
    for (space in spaces) {
        Column {
            Text("${space.value.toInt()} dp", style = MaterialTheme.typography.bodySmall)
            Box(Modifier.size(width = space, height = s.space8).background(SoundScapeTheme.colors.primary))
        }
    }
    Heading("Скругления")
    val r = SoundScapeTheme.radii
    for (radius in listOf(r.radius8, r.radius12, r.radius16, r.radius24, r.radius28)) {
        Surface(Modifier.fillMaxWidth().heightIn(min = s.space48),
            shape = RoundedCornerShape(radius), color = SoundScapeTheme.colors.primarySoft,
            contentColor = SoundScapeTheme.colors.onPrimarySoft) {
            Text("Радиус ${radius.value.toInt()} dp", Modifier.padding(s.space16))
        }
    }
}

@Composable
private fun ButtonExamples() {
    Heading("Кнопки: Default / Disabled / Loading")
    var count by rememberSaveable { mutableIntStateOf(0) }
    Text("Выполнено действий: $count. Disabled и Loading не должны менять счётчик. Нажмите и удерживайте Default для проверки Pressed.")
    for (variant in SoundScapeButtonVariant.entries) {
        Text(variant.name, style = MaterialTheme.typography.titleMedium)
        SoundScapeButton("${variant.name} — Default", { count++ }, variant = variant)
        SoundScapeButton("${variant.name} — Disabled", { count++ }, variant = variant, enabled = false)
        SoundScapeButton("${variant.name} — Loading", { count++ }, variant = variant,
            loading = true, loadingText = "${variant.name} — Загрузка…")
    }
}

@Composable
private fun FieldExamples() {
    Heading("Поля ввода")
    var empty by rememberSaveable { mutableStateOf("") }
    var filled by rememberSaveable { mutableStateOf("Ночной город") }
    var focused by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf("") }
    val requester = remember { FocusRequester() }
    SoundScapeTextField(empty, { empty = it }, "Empty — название", placeholder = "Введите название")
    SoundScapeTextField(filled, { filled = it }, "Filled — название", supportingText = "Поле можно редактировать")
    SoundScapeButton("Установить реальный фокус", { requester.requestFocus() })
    SoundScapeTextField(focused, { focused = it }, "Focused — коснитесь поля",
        modifier = Modifier.focusRequester(requester), placeholder = "Проверка курсора и клавиатуры")
    SoundScapeTextField(error, { error = it }, "Error — название", isError = true,
        errorText = "Длинное сообщение об ошибке: укажите название музыкального путешествия, чтобы продолжить. Текст должен переноситься и оставаться читаемым при крупном системном шрифте.")
    SoundScapeTextField("Недоступно", {}, "Disabled — название", enabled = false)
}

@Composable
private fun ChipExamples() {
    Heading("Фильтры")
    var first by rememberSaveable { mutableStateOf(true) }
    var second by rememberSaveable { mutableStateOf(false) }
    var disabledActions by rememberSaveable { mutableIntStateOf(0) }
    SoundScapeFilterChip("Selected — Электроника", first, { first = !first })
    SoundScapeFilterChip("Unselected — Джаз", second, { second = !second })
    SoundScapeFilterChip("Disabled selected", true, { disabledActions++ }, enabled = false)
    SoundScapeFilterChip("Disabled unselected", false, { disabledActions++ }, enabled = false)
    Text("Действий disabled-фильтров: $disabledActions (ожидается 0)")
}
