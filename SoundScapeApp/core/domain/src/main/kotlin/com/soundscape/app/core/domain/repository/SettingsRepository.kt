package com.soundscape.app.core.domain.repository

import com.soundscape.app.core.domain.model.ThemeMode
import com.soundscape.app.core.domain.model.ThemePreset
import com.soundscape.app.core.domain.model.UserSettings
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    val settings: Flow<UserSettings>
    suspend fun setMode(mode: ThemeMode)
    suspend fun setPreset(preset: ThemePreset)
    suspend fun setDynamicColor(enabled: Boolean)
}
