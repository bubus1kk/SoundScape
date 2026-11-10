package com.soundscape.app.core.designsystem.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlOutlineContrastTest {
    private fun contrast(a: Color, b: Color): Double {
        val x = a.luminance().toDouble()
        val y = b.luminance().toDouble()
        return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
    }

    @Test fun interactiveBoundariesMeetContrastAcrossAllStaticThemes() {
        for (preset in SoundScapePreset.entries) for (dark in listOf(false, true)) {
            val c = SoundScapePalettes.colors(preset, dark)
            assertEquals(c.textMuted, c.controlOutline)
            for ((surfaceName, surface) in listOf(
                "surface" to c.surface, "background" to c.background,
                "surfaceAlt" to c.surfaceAlt, "primarySoft" to c.primarySoft,
            )) {
                val ratio = contrast(c.controlOutline, surface)
                assertTrue("$preset dark=$dark controlOutline/$surfaceName=$ratio", ratio >= 3.0)
            }
        }
    }

    @Test fun focusErrorAndSelectedIndicatorsMeetContrastOnActualSurfaces() {
        for (preset in SoundScapePreset.entries) for (dark in listOf(false, true)) {
            val c = SoundScapePalettes.colors(preset, dark)
            for ((name, indicator, surface) in listOf(
                Triple("focus inside", c.primary, c.surface),
                Triple("focus outside", c.primary, c.background),
                Triple("error inside", c.danger, c.surface),
                Triple("error outside", c.danger, c.background),
                Triple("selected check", c.primary, c.primarySoft),
            )) {
                val ratio = contrast(indicator, surface)
                assertTrue("$preset dark=$dark $name=$ratio", ratio >= 3.0)
            }
        }
    }
}
