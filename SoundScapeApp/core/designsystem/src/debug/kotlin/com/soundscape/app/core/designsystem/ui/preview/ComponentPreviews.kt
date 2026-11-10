package com.soundscape.app.core.designsystem.ui.preview

import android.content.res.Configuration
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.soundscape.app.core.designsystem.ui.components.*
import com.soundscape.app.core.designsystem.ui.theme.*

@Preview(name = "Buttons Light", widthDp = 360)
@Preview(name = "Buttons Dark", widthDp = 360, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Buttons Large Font", widthDp = 320, fontScale = 2f)
@Composable
private fun ButtonPreviews() = PreviewContent {
    for (variant in SoundScapeButtonVariant.entries) {
        SoundScapeButton(variant.name, {}, variant = variant)
        SoundScapeButton(variant.name, {}, variant = variant, enabled = false)
        SoundScapeButton(variant.name, {}, variant = variant, loading = true)
    }
}

@Preview(name = "Fields Light", widthDp = 360)
@Preview(name = "Fields Dark", widthDp = 360, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Fields Large Font", widthDp = 320, fontScale = 2f)
@Composable
private fun TextFieldPreviews() = PreviewContent {
    var text by remember { mutableStateOf("") }
    SoundScapeTextField(text, { text = it }, "Название плейлиста", placeholder = "Введите название")
    SoundScapeTextField("Ночной город", {}, "Название плейлиста", supportingText = "Вспомогательная подпись")
    val interaction = remember { MutableInteractionSource() }
    LaunchedEffect(interaction) { interaction.emit(FocusInteraction.Focus()) }
    SoundScapeTextField("В фокусе", {}, "Название плейлиста", interactionSource = interaction)
    SoundScapeTextField("", {}, "Название плейлиста", placeholder = "Введите название",
        isError = true, errorText = "Введите название перед сохранением плейлиста")
    SoundScapeTextField("Недоступно", {}, "Название плейлиста", enabled = false)
}

@Preview(name = "Chips Light", widthDp = 360)
@Preview(name = "Chips Dark", widthDp = 360, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Chips Large Font", widthDp = 320, fontScale = 2f)
@Composable
private fun FilterChipPreviews() = PreviewContent {
    var selected by remember { mutableStateOf(true) }
    SoundScapeFilterChip("Электроника", selected, { selected = !selected })
    SoundScapeFilterChip("Джаз", false, {})
    SoundScapeFilterChip("Электроника", true, {}, enabled = false)
    SoundScapeFilterChip("Джаз", false, {}, enabled = false)
    SoundScapeFilterChip("Длинное название музыкального фильтра", true, {})
}

@Composable
private fun PreviewContent(content: @Composable () -> Unit) {
    SoundScapeTheme {
        Surface {
            Column(
                Modifier.fillMaxWidth().padding(SoundScapeTheme.spacing.space16),
                verticalArrangement = Arrangement.spacedBy(SoundScapeTheme.spacing.space8),
            ) { content() }
        }
    }
}
