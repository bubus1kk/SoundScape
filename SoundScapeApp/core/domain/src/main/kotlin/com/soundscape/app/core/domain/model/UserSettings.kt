package com.soundscape.app.core.domain.model

enum class ThemeMode { LIGHT, DARK, SYSTEM }

enum class ThemePreset { ATLAS, AURORA, VIOLET }

data class UserSettings(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    val preset: ThemePreset = ThemePreset.ATLAS,
    val dynamicColor: Boolean = false,
)
