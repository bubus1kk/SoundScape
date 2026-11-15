package com.soundscape.app

import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.soundscape.app.core.designsystem.ui.theme.SoundScapeMode
import com.soundscape.app.core.designsystem.ui.theme.SoundScapePreset
import com.soundscape.app.core.designsystem.ui.theme.ThemeOptions
import com.soundscape.app.core.domain.model.ThemeMode
import com.soundscape.app.core.domain.model.ThemePreset
import com.soundscape.app.core.domain.model.UserSettings
import com.soundscape.app.presentation.settings.SettingsUiState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AppearanceGateTest {
    @get:Rule val compose = createComposeRule()
    private val saved = UserSettings(ThemeMode.DARK, ThemePreset.VIOLET)

    @Test fun firstContentUsesSavedThemeAndNeverDefaults() {
        val state = mutableStateOf(SettingsUiState())
        val seen = mutableListOf<ThemeOptions>()
        var ready = false
        compose.setContent {
            AppearanceGate(state.value, {}, onReady = { ready = true }) { options ->
                SideEffect { seen.add(options) }
                Text("main-content")
            }
        }
        compose.onNodeWithText("main-content").assertDoesNotExist()
        compose.runOnIdle {
            assertFalse(ready)
            assertEquals(emptyList<ThemeOptions>(), seen)
        }
        compose.runOnIdle { state.value = SettingsUiState(settings = saved) }
        compose.onNodeWithText("main-content").assertExists()
        compose.runOnIdle {
            val expected = ThemeOptions(SoundScapePreset.Violet, SoundScapeMode.Dark)
            assertTrue(ready)
            assertTrue(seen.isNotEmpty())
            assertTrue(seen.all { it == expected })
        }
    }

    @Test fun errorReleasesLoadingAndRetryCanRestoreContent() {
        val state = mutableStateOf(SettingsUiState())
        var ready = false
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val retry = context.getString(com.soundscape.app.presentation.R.string.settings_retry)
        val error = context.getString(com.soundscape.app.presentation.R.string.settings_read_error)
        compose.setContent {
            AppearanceGate(state.value, { state.value = SettingsUiState() },
                onReady = { ready = true }) { Text("main-content") }
        }
        compose.runOnIdle { state.value = SettingsUiState(readFailed = true) }
        compose.onNodeWithText(error).assertExists()
        compose.runOnIdle { assertTrue(ready) }
        compose.onNodeWithText("main-content").assertDoesNotExist()
        compose.onNodeWithText(retry).performClick()
        compose.onNodeWithText(error).assertDoesNotExist()
        compose.runOnIdle { assertTrue(ready) }
        compose.onNodeWithText("main-content").assertDoesNotExist()
        compose.runOnIdle { state.value = SettingsUiState(settings = saved) }
        compose.onNodeWithText("main-content").assertExists()
    }
}
