package com.soundscape.app.core.designsystem.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeResolutionTest {
    @Test fun defaultsFollowSystemWithAtlasAndNoDynamic() {
        for (dark in listOf(false, true)) {
            assertEquals(ResolvedTheme(SoundScapePreset.Atlas, dark, false),
                resolveTheme(ThemeOptions(), dark, 37))
        }
    }

    @Test fun explicitModesOverrideSystemForEveryPreset() {
        for (preset in SoundScapePreset.entries) {
            for (system in listOf(false, true)) {
                assertEquals(ResolvedTheme(preset, false, false),
                    resolveTheme(ThemeOptions(preset, SoundScapeMode.Light), system, 26))
                assertEquals(ResolvedTheme(preset, true, false),
                    resolveTheme(ThemeOptions(preset, SoundScapeMode.Dark), system, 37))
            }
        }
    }

    @Test fun dynamicRequiresOptInAndApi31WithoutLosingPresetOrMode() {
        for (preset in SoundScapePreset.entries) {
            for (mode in SoundScapeMode.entries) {
                for (system in listOf(false, true)) {
                    val dark = mode == SoundScapeMode.Dark ||
                        (mode == SoundScapeMode.System && system)
                    val options = ThemeOptions(preset, mode, true)
                    for (sdk in listOf(26, 30, 31, 37)) {
                        assertEquals(ResolvedTheme(preset, dark, sdk >= 31),
                            resolveTheme(options, system, sdk))
                        assertEquals(ResolvedTheme(preset, dark, false),
                            resolveTheme(options.copy(dynamicColor = false), system, sdk))
                    }
                    assertEquals(preset, options.preset)
                }
            }
        }
    }
}
