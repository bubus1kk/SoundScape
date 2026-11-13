package com.soundscape.app.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.soundscape.app.core.domain.model.ThemeMode
import com.soundscape.app.core.domain.model.ThemePreset
import com.soundscape.app.core.domain.model.UserSettings
import com.soundscape.app.core.domain.repository.SettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.map

/** Preferences adapter. IO/corruption errors are never disguised as successfully loaded defaults. */
class DataStoreSettingsRepository @Inject constructor(private val store: DataStore<Preferences>) : SettingsRepository {
    override val settings = store.data.map { preferences ->
        UserSettings(
            mode = ThemeMode.entries.firstOrNull { it.name == preferences[MODE] } ?: ThemeMode.SYSTEM,
            preset = ThemePreset.entries.firstOrNull { it.name == preferences[PRESET] } ?: ThemePreset.ATLAS,
            dynamicColor = preferences[DYNAMIC] ?: false,
        )
    }

    override suspend fun setMode(mode: ThemeMode) { store.edit { it[MODE] = mode.name } }
    override suspend fun setPreset(preset: ThemePreset) { store.edit { it[PRESET] = preset.name } }
    override suspend fun setDynamicColor(enabled: Boolean) { store.edit { it[DYNAMIC] = enabled } }

    private companion object {
        val MODE = stringPreferencesKey("theme_mode")
        val PRESET = stringPreferencesKey("theme_preset")
        val DYNAMIC = booleanPreferencesKey("dynamic_color")
    }
}
