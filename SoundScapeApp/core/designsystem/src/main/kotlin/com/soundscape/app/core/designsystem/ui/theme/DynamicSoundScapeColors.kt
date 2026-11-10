package com.soundscape.app.core.designsystem.ui.theme


import androidx.compose.material3.ColorScheme

/** All extended roles come from the SAME dynamic scheme, never from a static preset. */
internal fun dynamicSoundScapeColors(scheme: ColorScheme) = SoundScapeColors(
    background = scheme.background,
    surface = scheme.surface,
    surfaceAlt = scheme.surfaceContainerHighest,
    text = scheme.onSurface,
    textMuted = scheme.onSurfaceVariant,
    primary = scheme.primary,
    onPrimary = scheme.onPrimary,
    primarySoft = scheme.primaryContainer,
    onPrimarySoft = scheme.onPrimaryContainer,
    outline = scheme.outline,
    success = scheme.tertiary,
    danger = scheme.error,
    playerTrack = scheme.surfaceContainerHighest,
)
