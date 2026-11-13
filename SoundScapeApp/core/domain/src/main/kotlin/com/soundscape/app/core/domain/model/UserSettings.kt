package com.soundscape.app.core.domain.model

/** Persisted appearance choice, independent of the UI implementation. */
enum class ThemeMode { LIGHT, DARK, SYSTEM }

/** Stable application palette identifiers. */
enum class ThemePreset { ATLAS, AURORA, VIOLET }

/** Defaults match the existing SoundScape theme. */
data class UserSettings(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    val preset: ThemePreset = ThemePreset.ATLAS,
    val dynamicColor: Boolean = false,
)
