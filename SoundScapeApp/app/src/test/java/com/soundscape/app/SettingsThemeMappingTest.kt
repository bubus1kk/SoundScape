package com.soundscape.app

import com.soundscape.app.core.domain.model.ThemeMode
import com.soundscape.app.core.domain.model.ThemePreset
import com.soundscape.app.core.domain.model.UserSettings

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsThemeMappingTest {
    @Test fun mapsAllModesPalettesAndDynamicPreference() {
        for (mode in ThemeMode.entries) for (preset in ThemePreset.entries) for (dynamic in listOf(false, true)) {
            val mapped = UserSettings(mode, preset, dynamic).toThemeOptions()
            assertEquals(mode.name, mapped.mode.name.uppercase())
            assertEquals(preset.name, mapped.preset.name.uppercase())
            assertEquals(dynamic, mapped.dynamicColor)
        }
    }
}
