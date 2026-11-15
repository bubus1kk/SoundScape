package com.soundscape.app.core.designsystem.ui.theme


enum class SoundScapePreset { Atlas, Aurora, Violet }
enum class SoundScapeMode { Light, Dark, System }

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
