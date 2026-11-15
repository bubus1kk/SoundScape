package com.soundscape.app.core.designsystem.ui.theme


import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

private val LocalSoundScapeColors = staticCompositionLocalOf<SoundScapeColors> {
    error("SoundScape colors require a SoundScapeTheme ancestor")
}

object SoundScapeTheme {
    val colors: SoundScapeColors
        @Composable @ReadOnlyComposable get() = LocalSoundScapeColors.current
    val spacing: SoundScapeSpacing get() = SoundScapeSpacing
    val radii: SoundScapeRadii get() = SoundScapeRadii
}

@Composable
fun SoundScapeTheme(
    options: ThemeOptions = ThemeOptions(),
    content: @Composable () -> Unit,
) {
    val resolved = resolveTheme(options, isSystemInDarkTheme(), Build.VERSION.SDK_INT)
    val scheme = if (resolved.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val context = LocalContext.current
        if (resolved.dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        materialColorScheme(resolved.preset, resolved.dark)
    }
    val colors = if (resolved.dynamicColor) dynamicSoundScapeColors(scheme)
        else SoundScapePalettes.colors(resolved.preset, resolved.dark)
    CompositionLocalProvider(LocalSoundScapeColors provides colors) {
        MaterialTheme(
            colorScheme = scheme,
            typography = SoundScapeTypography,
            shapes = SoundScapeShapes,
            content = content,
        )
    }
}
