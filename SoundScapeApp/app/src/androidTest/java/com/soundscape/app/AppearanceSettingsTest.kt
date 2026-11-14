package com.soundscape.app

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowCompat
import org.junit.Assert.assertEquals
import com.soundscape.app.core.domain.model.ThemeMode
import com.soundscape.app.core.domain.model.ThemePreset
import com.soundscape.app.core.data.settings.DataStoreSettingsRepository
import com.soundscape.app.core.data.settings.SettingsModule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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

    @Test fun selectionsSurviveActivityRecreation() {
        compose.waitUntil(15000) { compose.onAllNodesWithText("Тёмная").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Тёмная").assertIsSelected()
        compose.onNodeWithText("Violet").assertIsSelected()
        for (label in listOf("Светлая", "Тёмная", "Системная", "Тёмная", "Atlas", "Aurora", "Violet")) {
            compose.waitUntil(15000) { runCatching { compose.onNodeWithText(label).assertIsEnabled() }.isSuccess }
            compose.onNodeWithText(label).performScrollTo().performClick()
            compose.waitUntil(15000) { runCatching { compose.onNodeWithText(label).assertIsSelected() }.isSuccess }
            if (label == "Светлая" || label == "Тёмная") {
                compose.runOnIdle {
                    val activity = compose.activity
                    val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                    assertEquals(label == "Светлая", controller.isAppearanceLightStatusBars)
                    assertEquals(label == "Светлая", controller.isAppearanceLightNavigationBars)
                }
            }
        }
        val toggle = compose.onNodeWithContentDescription("Динамические цвета")
        toggle.performScrollTo()
        compose.waitUntil(15000) { runCatching { toggle.assertIsEnabled() }.isSuccess }
        if (runCatching { toggle.assertIsOn() }.isFailure) toggle.performClick()
        compose.waitUntil(15000) { runCatching { toggle.assertIsOn() }.isSuccess }
        compose.activityRule.scenario.recreate()
        compose.waitUntil(15000) { runCatching { compose.onNodeWithText("Тёмная").assertIsSelected() }.isSuccess }
        compose.onNodeWithText("Violet").assertIsSelected()
        compose.onNodeWithContentDescription("Динамические цвета").assertIsOn()
        compose.onNodeWithContentDescription("Динамические цвета").performScrollTo().performClick()
        compose.waitUntil(15000) { runCatching {
            compose.onNodeWithContentDescription("Динамические цвета").assertIsOff()
        }.isSuccess }
    }
}
