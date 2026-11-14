package com.soundscape.app.core.designsystem.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

@Immutable
data class SoundScapeColors(
    val background: Color,
    val surface: Color,
    val surfaceAlt: Color,
    val text: Color,
    val textMuted: Color,
    val primary: Color,
    val onPrimary: Color,
    val primarySoft: Color,
    val onPrimarySoft: Color,
    val outline: Color,
    val success: Color,
    val danger: Color,
    val playerTrack: Color,
) {
    val controlOutline: Color get() = textMuted

    val playerProgress: Color get() = primary
}
