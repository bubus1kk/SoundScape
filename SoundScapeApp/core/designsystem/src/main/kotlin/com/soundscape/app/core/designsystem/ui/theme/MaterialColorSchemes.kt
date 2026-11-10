package com.soundscape.app.core.designsystem.ui.theme


import androidx.compose.material3.ColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme

/** Every Material role is explicit; aliases preserve the original Figma colors. */
@Suppress("DEPRECATION")
fun materialColorScheme(preset: SoundScapePreset, dark: Boolean): ColorScheme {
    val p = SoundScapePalettes.colors(preset, dark)
    val light = SoundScapePalettes.colors(preset, false)
    val inverse = SoundScapePalettes.colors(preset, !dark)
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = p.primary, onPrimary = p.onPrimary,
        primaryContainer = p.primarySoft, onPrimaryContainer = p.onPrimarySoft,
        inversePrimary = inverse.primary,
        secondary = p.primary, onSecondary = p.onPrimary,
        secondaryContainer = p.primarySoft, onSecondaryContainer = p.onPrimarySoft,
        tertiary = p.primary, onTertiary = p.onPrimary,
        tertiaryContainer = p.primarySoft, onTertiaryContainer = p.onPrimarySoft,
        background = p.background, onBackground = p.text,
        surface = p.surface, onSurface = p.text,
        surfaceVariant = p.surfaceAlt, onSurfaceVariant = p.textMuted,
        surfaceTint = p.primary,
        inverseSurface = inverse.surface, inverseOnSurface = inverse.text,
        error = p.danger, onError = p.background,
        errorContainer = p.surfaceAlt, onErrorContainer = p.danger,
        outline = p.outline, outlineVariant = p.outline,
        scrim = light.text,
        surfaceBright = if (dark) p.surfaceAlt else p.surface,
        surfaceDim = if (dark) p.background else p.surfaceAlt,
        surfaceContainerLowest = p.background,
        surfaceContainerLow = p.surface, surfaceContainer = p.surface,
        surfaceContainerHigh = p.surfaceAlt, surfaceContainerHighest = p.surfaceAlt,
        primaryFixed = light.primarySoft, primaryFixedDim = light.primarySoft,
        onPrimaryFixed = light.onPrimarySoft, onPrimaryFixedVariant = light.onPrimarySoft,
        secondaryFixed = light.primarySoft, secondaryFixedDim = light.primarySoft,
        onSecondaryFixed = light.onPrimarySoft, onSecondaryFixedVariant = light.onPrimarySoft,
        tertiaryFixed = light.primarySoft, tertiaryFixedDim = light.primarySoft,
        onTertiaryFixed = light.onPrimarySoft, onTertiaryFixedVariant = light.onPrimarySoft,
    )
}
