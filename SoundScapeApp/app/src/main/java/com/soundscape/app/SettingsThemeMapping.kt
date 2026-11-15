package com.soundscape.app

import com.soundscape.app.core.designsystem.ui.theme.SoundScapeMode
import com.soundscape.app.core.designsystem.ui.theme.SoundScapePreset
import com.soundscape.app.core.designsystem.ui.theme.ThemeOptions
import com.soundscape.app.core.domain.model.ThemeMode
import com.soundscape.app.core.domain.model.ThemePreset
import com.soundscape.app.core.domain.model.UserSettings

fun UserSettings.toThemeOptions(): ThemeOptions = ThemeOptions(
    mode = when (mode) {
        ThemeMode.LIGHT -> SoundScapeMode.Light
        ThemeMode.DARK -> SoundScapeMode.Dark
        ThemeMode.SYSTEM -> SoundScapeMode.System
    },
    preset = when (preset) {
        ThemePreset.ATLAS -> SoundScapePreset.Atlas
        ThemePreset.AURORA -> SoundScapePreset.Aurora
        ThemePreset.VIOLET -> SoundScapePreset.Violet
    },
    dynamicColor = dynamicColor,
)
