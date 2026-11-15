package com.soundscape.app

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider

import com.soundscape.app.presentation.diary.DiaryViewModel
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class DiaryScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val created = mutableSetOf<UUID>()
    private fun model() = ViewModelProvider(compose.activity)[DiaryViewModel::class.java]
    private fun openEditor() {
        compose.waitUntil(15000) {
            runCatching { compose.onAllNodesWithText("Дневник").fetchSemanticsNodes().isNotEmpty() }
                .getOrDefault(false)
        }
        compose.onNodeWithText("Дневник").performClick()
        compose.onNodeWithText("Новый черновик").performClick()
        compose.runOnIdle { created += model().state.value.id!! }
    }
    @After fun cleanup() = runBlocking {
        val db = androidx.room.Room.databaseBuilder(compose.activity.applicationContext,
            com.soundscape.app.core.database.SoundScapeDatabase::class.java, "soundscape-cache.db")
            .addMigrations(com.soundscape.app.core.database.MIGRATION_1_2).build()
        try {
            val dao = db.diaryDraftDao()
            created.forEach { id -> dao.header("device_local", id.toString())?.let {
                dao.delete("device_local", id.toString(), it.revision)
            } }
        } finally { db.close() }
    }
    @Test fun unsavedTextSurvivesRotation() {
        openEditor()
        compose.onNodeWithText("Название").performTextInput("Rotation draft")
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        compose.waitUntil(15000) {
            compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        }
        compose.onNodeWithText("Rotation draft").assertExists()
        compose.runOnIdle {
            assertEquals("Rotation draft", model().state.value.form.title)
            assertTrue(model().state.value.dirty)
        }
    }
    @Test fun partialDraftSavesAndExitCanBeCancelled() {
        openEditor()
        compose.onNodeWithText("Название").performTextInput("Partial diary")
        compose.onNodeWithText("К списку").performScrollTo().performClick()
        compose.onNodeWithText("Продолжить редактирование").performClick()
        compose.onNodeWithText("Сохранить черновик").performScrollTo().performClick()
        compose.waitUntil(10000) { model().state.value.revision == 1L }
        compose.runOnIdle { assertFalse(model().state.value.dirty) }
        compose.onNodeWithText("К списку").performScrollTo().performClick()
        compose.onNodeWithText("Partial diary").assertExists()
    }
    @Test fun importProgressBlocksSaveAndExitUntilCancellationCompletes() {
        openEditor()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        compose.runOnIdle {
            val operation = model().photoImports.begin()!!
            model().photoImports.selected(operation, import = { gate.await(); UUID.randomUUID() }, release = {})
        }
        compose.onNodeWithText("Импорт фотографии. Сохранение и выход временно недоступны.").assertExists()
        compose.onNodeWithText("Сохранить черновик").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("К списку").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Отменить импорт").performScrollTo().performClick()
        compose.waitUntil(10000) { !model().photoImports.isBusy }
        compose.onNodeWithText("Сохранить черновик").performScrollTo().assertIsEnabled()
    }

}
