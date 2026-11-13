package com.soundscape.app.presentation.settings

import com.soundscape.app.core.domain.model.ThemeMode
import com.soundscape.app.core.domain.model.ThemePreset
import com.soundscape.app.core.domain.model.UserSettings
import com.soundscape.app.core.domain.repository.SettingsRepository
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun close() { Dispatchers.resetMain() }
    private class Fake : SettingsRepository {
        val values = MutableStateFlow(UserSettings())
        var readGate: CompletableDeferred<Unit>? = null
        var failRead = false
        var failWrite = false
        override val settings get() = flow {
            readGate?.await()
            if (failRead) throw IOException()
            emitAll(values)
        }
        override suspend fun setMode(mode: ThemeMode) {
            if (failWrite) throw IOException()
            values.value = values.value.copy(mode = mode)
        }
        override suspend fun setPreset(preset: ThemePreset) { values.value = values.value.copy(preset = preset) }
        override suspend fun setDynamicColor(enabled: Boolean) {
            values.value = values.value.copy(dynamicColor = enabled)
        }
    }
    @Test fun initialReadWaitsForSavedSettingsWithoutPublishingDefaults() = runTest(dispatcher) {
        val ready = CompletableDeferred<Unit>()
        val saved = UserSettings(ThemeMode.DARK, ThemePreset.VIOLET)
        val repository = Fake().apply { readGate = ready; values.value = saved }
        val model = SettingsViewModel(repository)
        runCurrent()
        assertTrue(model.state.value.initialLoading)
        assertNull(model.state.value.settings)
        ready.complete(Unit)
        runCurrent()
        assertFalse(model.state.value.initialLoading)
        assertEquals(saved, model.state.value.settings)
    }
    @Test fun loadsAndAppliesRepositoryValues() = runTest(dispatcher) {
        val repository = Fake()
        val model = SettingsViewModel(repository)
        assertNull(model.state.value.settings)
        runCurrent()
        model.setMode(ThemeMode.DARK); runCurrent()
        model.setPreset(ThemePreset.VIOLET); runCurrent()
        model.setDynamicColor(true); runCurrent()
        assertEquals(UserSettings(ThemeMode.DARK, ThemePreset.VIOLET, true), model.state.value.settings)
    }
    @Test fun writeFailureDoesNotApplyUnpersistedChoice() = runTest(dispatcher) {
        val repository = Fake()
        val model = SettingsViewModel(repository)
        runCurrent(); repository.failWrite = true
        model.setMode(ThemeMode.DARK); runCurrent()
        assertTrue(model.state.value.writeFailed)
        assertFalse(model.state.value.saving)
        assertEquals(ThemeMode.SYSTEM, model.state.value.settings!!.mode)
        repository.failWrite = false
        model.setMode(ThemeMode.DARK); runCurrent()
        assertFalse(model.state.value.writeFailed)
    }
    @Test fun readFailureCanBeRetried() = runTest(dispatcher) {
        val repository = Fake().apply { failRead = true }
        val model = SettingsViewModel(repository)
        runCurrent(); assertTrue(model.state.value.readFailed)
        assertFalse(model.state.value.initialLoading)
        repository.failRead = false
        repository.values.value = UserSettings(ThemeMode.DARK, ThemePreset.VIOLET)
        model.retryRead()
        assertTrue(model.state.value.initialLoading)
        runCurrent()
        assertFalse(model.state.value.readFailed)
        assertEquals(UserSettings(ThemeMode.DARK, ThemePreset.VIOLET), model.state.value.settings)
    }
}
