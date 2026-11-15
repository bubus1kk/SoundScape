package com.soundscape.app

import android.os.Bundle
import androidx.activity.compose.BackHandler
import com.soundscape.app.presentation.diary.DiarySearchViewModel
import com.soundscape.app.presentation.diary.DiaryViewModel
import com.soundscape.app.presentation.library.LibraryTab
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
import com.soundscape.app.presentation.library.LibraryScreen
import com.soundscape.app.presentation.library.LibraryViewModel
import dagger.hilt.android.AndroidEntryPoint

private const val LIGHT_BACKGROUND_THRESHOLD = 0.5f

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @javax.inject.Inject lateinit var photoStore: com.soundscape.app.core.data.diary.DiaryDraftPhotoStore
    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val shellViewModel = ViewModelProvider(this)[ShellViewModel::class.java]
        val libraryModel = ViewModelProvider(this)[LibraryViewModel::class.java]
        val diarySearch = ViewModelProvider(this)[DiarySearchViewModel::class.java]
        val diaryModel = ViewModelProvider(this)[DiaryViewModel::class.java]
        val settingsModel = ViewModelProvider(this)[SettingsViewModel::class.java]
        var initialReadPending = true
        splash.setKeepOnScreenCondition {
            if (!settingsModel.state.value.initialLoading) initialReadPending = false
            initialReadPending
        }
        setContent {
            val settingsState = settingsModel.state.collectAsStateWithLifecycle()
            val state = shellViewModel.state.collectAsStateWithLifecycle()
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
                    ConnectivityRefreshEffect(libraryModel)
                    ShellScreen(state.value) {
                        val tab = libraryModel.tab.collectAsStateWithLifecycle()
                        val diaryState = diaryModel.state.collectAsStateWithLifecycle()
                        BackHandler(enabled = tab.value == LibraryTab.DIARY && diaryState.value.id != null) {
                            diaryModel.leave {}
                        }
                        DiaryExitSurface(diaryModel, photoStore)
                        LibraryScreen(libraryModel, diary = {
                            DiaryContent(diaryModel, photoStore, diarySearch)
                        }, open = { destination ->
                            if (tab.value == LibraryTab.DIARY) diaryModel.leave { libraryModel.open(destination) }
                            else libraryModel.open(destination)
                        }) { SettingsScreen(settingsState.value, settingsModel) }
                    }
                }
            }
        }
    }
}
