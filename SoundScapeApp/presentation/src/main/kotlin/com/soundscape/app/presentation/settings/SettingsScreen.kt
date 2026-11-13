package com.soundscape.app.presentation.settings

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import com.soundscape.app.core.designsystem.ui.components.SoundScapeFilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.soundscape.app.core.designsystem.ui.theme.SoundScapeSpacing
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.soundscape.app.core.domain.model.ThemeMode
import com.soundscape.app.core.domain.model.ThemePreset
import com.soundscape.app.presentation.R

/** Appearance controls use the surrounding existing application theme. */
@Composable
fun SettingsScreen(state: SettingsUiState, model: SettingsViewModel, modifier: Modifier = Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(SoundScapeSpacing.space16)) {
        Text(stringResource(R.string.appearance_title), style = MaterialTheme.typography.headlineSmall)
        if (state.settings == null && !state.readFailed) CircularProgressIndicator()
        if (state.readFailed) {
            Text(stringResource(R.string.settings_read_error))
            Button(onClick = model::retryRead) { Text(stringResource(R.string.settings_retry)) }
        }
        state.settings?.let { settings ->
            val enabled = !state.saving && !state.readFailed
            Text(stringResource(R.string.theme_mode))
            ThemeMode.entries.forEach { mode ->
                val label = when (mode) {
                    ThemeMode.LIGHT -> R.string.theme_light
                    ThemeMode.DARK -> R.string.theme_dark
                    ThemeMode.SYSTEM -> R.string.theme_system
                }
                SoundScapeFilterChip(text = stringResource(label),
                    selected = settings.mode == mode, enabled = enabled, onClick = { model.setMode(mode) })
            }
            Text(stringResource(R.string.theme_palette))
            ThemePreset.entries.forEach { preset ->
                SoundScapeFilterChip(text = preset.name.lowercase().replaceFirstChar { it.uppercase() },
                    selected = settings.preset == preset, enabled = enabled, onClick = { model.setPreset(preset) })
            }
            DynamicColorsControl(enabled, settings.dynamicColor, model::setDynamicColor)
        }
        if (state.writeFailed) Text(stringResource(R.string.settings_write_error))
    }
}

@Composable
private fun DynamicColorsControl(enabled: Boolean, checked: Boolean, onChange: (Boolean) -> Unit) {
    val dynamicLabel = stringResource(R.string.theme_dynamic)
    Row(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.theme_dynamic), Modifier.weight(1f))
        Switch(modifier = Modifier.semantics { contentDescription = dynamicLabel },
            checked = checked, enabled = enabled,
            onCheckedChange = onChange)
    }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) Text(stringResource(R.string.dynamic_unavailable))
    if (checked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Text(stringResource(R.string.dynamic_palette_hint))
    }
}
