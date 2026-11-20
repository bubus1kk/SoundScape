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
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soundscape.app.core.designsystem.ui.theme.SoundScapeTheme
import com.soundscape.app.navigation.SoundScapeNavHost
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import coil3.ImageLoader
import com.soundscape.app.core.domain.repository.ProfileSession
import com.soundscape.app.media.EditorialMediaImages
import com.soundscape.app.presentation.settings.SettingsViewModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

private const val LIGHT_BACKGROUND_THRESHOLD = 0.5f

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject @EditorialMediaImages lateinit var imageLoader: ImageLoader
    @Inject lateinit var profileSession: ProfileSession

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycleScope.launch { profileSession.restore() }
        val settingsModel = ViewModelProvider(this)[SettingsViewModel::class.java]
        var initialReadPending = true
        splash.setKeepOnScreenCondition {
            if (!settingsModel.state.value.initialLoading) initialReadPending = false
            initialReadPending
        }
        setContent {
            val settingsState = settingsModel.state.collectAsStateWithLifecycle()
            val currentSettings = settingsState.value
            AppearanceGate(currentSettings, settingsModel::retryRead) { options ->
                SoundScapeTheme(options = options) {
                    val lightBars = MaterialTheme.colorScheme.background.luminance() > LIGHT_BACKGROUND_THRESHOLD
                    SideEffect {
                        WindowCompat.getInsetsController(window, window.decorView).apply {
                            isAppearanceLightStatusBars = lightBars
                            isAppearanceLightNavigationBars = lightBars
                        }
                    }
                    SoundScapeNavHost(imageLoader = imageLoader)
                }
            }
        }
    }
}
