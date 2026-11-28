package com.soundscape.app.core.domain.repository

import com.soundscape.app.core.domain.model.ThemeMode
import com.soundscape.app.core.domain.model.ThemePreset
import com.soundscape.app.core.domain.model.UserSettings
import kotlinx.coroutines.flow.Flow

/** Reads and updates the user's appearance preferences. */
interface SettingsRepository {
    val settings: Flow<UserSettings>
    /** Sets the preferred light, dark or system appearance. */
    suspend fun setMode(mode: ThemeMode)
    /** Selects the preferred theme preset. */
    suspend fun setPreset(preset: ThemePreset)
    /** Sets whether dynamic colors may be used. */
    suspend fun setDynamicColor(enabled: Boolean)
}
