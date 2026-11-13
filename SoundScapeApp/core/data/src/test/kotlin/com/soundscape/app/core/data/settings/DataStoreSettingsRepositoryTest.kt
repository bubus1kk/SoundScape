package com.soundscape.app.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.soundscape.app.core.domain.model.ThemeMode
import com.soundscape.app.core.domain.model.ThemePreset
import com.soundscape.app.core.domain.model.UserSettings
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DataStoreSettingsRepositoryTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var file: File
    private lateinit var job: Job
    private lateinit var store: DataStore<Preferences>
    private lateinit var repository: DataStoreSettingsRepository
    private fun open() {
        job = SupervisorJob()
        store = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO)) { file }
        repository = DataStoreSettingsRepository(store)
    }
    @Before fun setup() { file = File(folder.root, "test.preferences_pb"); open() }
    @After fun close() = runBlocking { job.cancelAndJoin() }

    @Test fun defaultsMatchExistingTheme() = runTest {
        assertEquals(UserSettings(), repository.settings.first())
    }
    @Test fun persistsEveryOptionAcrossReopen() = runTest {
        for (mode in ThemeMode.entries) {
            repository.setMode(mode)
            assertEquals(mode, repository.settings.first().mode)
        }
        for (preset in ThemePreset.entries) {
            repository.setPreset(preset)
            assertEquals(preset, repository.settings.first().preset)
        }
        repository.setDynamicColor(true)
        job.cancelAndJoin(); open()
        assertEquals(UserSettings(ThemeMode.SYSTEM, ThemePreset.VIOLET, true), repository.settings.first())
        repository.setDynamicColor(false)
        assertFalse(repository.settings.first().dynamicColor)
    }
    @Test fun concurrentUpdatesPreserveOtherFields() = runTest {
        coroutineScope {
            launch { repository.setMode(ThemeMode.DARK) }
            launch { repository.setPreset(ThemePreset.AURORA) }
            launch { repository.setDynamicColor(true) }
        }
        assertEquals(UserSettings(ThemeMode.DARK, ThemePreset.AURORA, true), repository.settings.first())
    }
    @Test fun unknownEnumValuesFallBackWithoutLosingOtherSettings() = runTest {
        repository.setDynamicColor(true)
        store.edit {
            it[stringPreferencesKey("theme_mode")] = "FUTURE"
            it[stringPreferencesKey("theme_preset")] = "FUTURE"
        }
        assertEquals(UserSettings(dynamicColor = true), repository.settings.first())
    }
    @Test fun readAndWriteFailuresPropagate() = runTest {
        val broken = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { throw IOException("read") }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                throw IOException("write")
        }
        val adapter = DataStoreSettingsRepository(broken)
        try { adapter.settings.first(); fail("Expected read failure") } catch (_: IOException) { }
        try { adapter.setMode(ThemeMode.DARK); fail("Expected write failure") } catch (_: IOException) { }
    }
    @Test fun flowPublishesPersistedUpdates() = runTest {
        val emitted = mutableListOf<UserSettings>()
        val observer = launch(start = CoroutineStart.UNDISPATCHED) { repository.settings.take(2).toList(emitted) }
        while (emitted.isEmpty()) yield()
        repository.setPreset(ThemePreset.AURORA)
        observer.join()
        assertEquals(listOf(UserSettings(), UserSettings(preset = ThemePreset.AURORA)), emitted)
    }
}
