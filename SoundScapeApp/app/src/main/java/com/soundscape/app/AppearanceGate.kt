package com.soundscape.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.soundscape.app.core.designsystem.ui.theme.SoundScapeSpacing
import com.soundscape.app.core.designsystem.ui.theme.SoundScapeTheme
import com.soundscape.app.core.designsystem.ui.theme.ThemeOptions
import com.soundscape.app.presentation.R
import com.soundscape.app.presentation.settings.SettingsUiState

@Composable
internal fun AppearanceGate(
    state: SettingsUiState,
    onRetry: () -> Unit,
    onReady: () -> Unit = {},
    content: @Composable (ThemeOptions) -> Unit,
) {
    SideEffect { if (!state.initialLoading) onReady() }
    val settings = state.settings
    if (settings != null) {
        content(settings.toThemeOptions())
    } else {
        SoundScapeTheme {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.padding(SoundScapeSpacing.space16)) {
                    if (state.readFailed) {
                        Text(stringResource(R.string.settings_read_error))
                        Button(onClick = onRetry) { Text(stringResource(R.string.settings_retry)) }
                    } else {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }
}
