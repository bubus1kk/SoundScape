package com.soundscape.app.core.domain.repository

import com.soundscape.app.core.domain.model.ThemeMode
import com.soundscape.app.core.domain.model.ThemePreset
import com.soundscape.app.core.domain.model.UserSettings
import kotlinx.coroutines.flow.Flow

/** Device-wide appearance preferences. Storage failures propagate to the caller. */
interface SettingsRepository {
    /** Emits persisted settings; missing or unknown values use defaults. */
    val settings: Flow<UserSettings>
    /** Atomically changes only the theme mode. */
    suspend fun setMode(mode: ThemeMode)
    /** Atomically changes only the palette. */
    suspend fun setPreset(preset: ThemePreset)
    /** Stores the preference even on devices without dynamic color support. */
    suspend fun setDynamicColor(enabled: Boolean)
}
