package com.soundscape.app.core.domain.model

/** Preferred light, dark or system appearance. */
enum class ThemeMode { LIGHT, DARK, SYSTEM }

/** Named color preset available in the app. */
enum class ThemePreset { ATLAS, AURORA, VIOLET }

/** User-selected appearance settings. */
data class UserSettings(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    val preset: ThemePreset = ThemePreset.ATLAS,
    val dynamicColor: Boolean = false,
)
