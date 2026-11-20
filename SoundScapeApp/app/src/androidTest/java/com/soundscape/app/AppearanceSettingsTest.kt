package com.soundscape.app

import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.core.view.WindowCompat
import com.soundscape.app.core.designsystem.ui.theme.SoundScapePalettes
import com.soundscape.app.core.domain.model.ThemeMode
import com.soundscape.app.core.domain.model.ThemePreset
import com.soundscape.app.core.data.settings.DataStoreSettingsRepository
import com.soundscape.app.core.data.settings.SettingsModule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import org.junit.Rule
import org.junit.Test

class AppearanceSettingsTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: TestRule = RuleChain.outerRule(isolateSettings()).around(compose)

    private fun isolateSettings(): TestRule = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
                val store = SettingsModule.store(context)
                val repository = DataStoreSettingsRepository(store)
                val original = runBlocking(Dispatchers.IO) { store.data.first() }
                try {
                    runBlocking(Dispatchers.IO) {
                        repository.setMode(ThemeMode.DARK)
                        repository.setPreset(ThemePreset.VIOLET)
                        repository.setDynamicColor(false)
                    }
                    base.evaluate()
                } finally {
                    runBlocking(Dispatchers.IO) {
                        store.updateData { original }
                        assertEquals(original, store.data.first())
                    }
                }
            }
        }
    }

    @Test fun savedThemeAppliesToMinimalShellAfterActivityRecreation() {
        assertSavedTheme()
        compose.activityRule.scenario.recreate()
        assertSavedTheme()
    }

    private fun assertSavedTheme() {
        compose.waitUntil(15000) {
            runCatching { compose.onNodeWithTag("home-feed").assertExists() }.isSuccess
        }
        compose.onNodeWithTag("home-feed").assertExists()
        val pixels = compose.onRoot().captureToImage().toPixelMap()
        assertEquals(SoundScapePalettes.VioletDark.background.toArgb(),
            pixels[pixels.width - 1, pixels.height / 2].toArgb())
        compose.runOnIdle {
            val controller = WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView)
            assertFalse(controller.isAppearanceLightStatusBars)
            assertFalse(controller.isAppearanceLightNavigationBars)
        }
    }
}
