package com.soundscape.app

import android.os.Bundle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.luminance
import androidx.core.view.WindowCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soundscape.app.core.designsystem.ui.theme.SoundScapeTheme
import com.soundscape.app.presentation.shell.ShellScreen
import com.soundscape.app.presentation.shell.ShellViewModel
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.soundscape.app.presentation.settings.SettingsScreen
import com.soundscape.app.presentation.settings.SettingsViewModel
import dagger.hilt.android.AndroidEntryPoint

private const val LIGHT_BACKGROUND_THRESHOLD = 0.5f

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val shellViewModel = ViewModelProvider(this)[ShellViewModel::class.java]
        val settingsModel = ViewModelProvider(this)[SettingsViewModel::class.java]
        var firstContentReady = false
        splash.setKeepOnScreenCondition { !firstContentReady }
        setContent {
            val settingsState = settingsModel.state.collectAsStateWithLifecycle()
            val state = shellViewModel.state.collectAsStateWithLifecycle()
            val currentSettings = settingsState.value
            AppearanceGate(currentSettings, settingsModel::retryRead,
                // Latch open on success OR failure; retries cannot bring back the launch splash.
                onReady = { firstContentReady = true }) { options ->
                SoundScapeTheme(options = options) {
                    val lightBars = MaterialTheme.colorScheme.background.luminance() > LIGHT_BACKGROUND_THRESHOLD
                    SideEffect {
                        WindowCompat.getInsetsController(window, window.decorView).apply {
                            isAppearanceLightStatusBars = lightBars
                            isAppearanceLightNavigationBars = lightBars
                        }
                    }
                    ShellScreen(state.value) {
                        SettingsScreen(settingsState.value, settingsModel)
                    }
                }
            }
        }
    }
}
