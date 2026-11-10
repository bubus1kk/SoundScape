package com.soundscape.app.core.designsystem.ui.theme


enum class SoundScapePreset { Atlas, Aurora, Violet }
enum class SoundScapeMode { Light, Dark, System }

/** Immutable input. Dynamic colors never mutate or replace the selected preset. */
data class ThemeOptions(
    val preset: SoundScapePreset = SoundScapePreset.Atlas,
    val mode: SoundScapeMode = SoundScapeMode.System,
    val dynamicColor: Boolean = false,
)

data class ResolvedTheme(
    val preset: SoundScapePreset,
    val dark: Boolean,
    val dynamicColor: Boolean,
)

/** Pure resolution: Android API access and persistence stay outside this function. */
fun resolveTheme(options: ThemeOptions, systemDark: Boolean, sdkInt: Int): ResolvedTheme =
    ResolvedTheme(
        preset = options.preset,
        dark = when (options.mode) {
            SoundScapeMode.Light -> false
            SoundScapeMode.Dark -> true
            SoundScapeMode.System -> systemDark
        },
        dynamicColor = options.dynamicColor && sdkInt >= 31,
    )
