package com.soundscape.app.core.designsystem.ui.theme

import androidx.compose.ui.graphics.Color

/** Verified against Figma foundation swatches (78 values); opacity 1. */
object SoundScapePalettes {
    val AtlasLight = SoundScapeColors(
        background = Color(0xFFF5F7FC),
        surface = Color(0xFFFFFFFF),
        surfaceAlt = Color(0xFFEAF0FA),
        text = Color(0xFF17243B),
        textMuted = Color(0xFF4F617D),
        primary = Color(0xFF2855BD),
        onPrimary = Color(0xFFFFFFFF),
        primarySoft = Color(0xFFDFE8FA),
        onPrimarySoft = Color(0xFF1B376B),
        outline = Color(0xFFBECADD),
        success = Color(0xFF116C50),
        danger = Color(0xFFAE334A),
        playerTrack = Color(0xFFC7D4E9),
    )

    val AtlasDark = SoundScapeColors(
        background = Color(0xFF0D1423),
        surface = Color(0xFF172236),
        surfaceAlt = Color(0xFF22314A),
        text = Color(0xFFF2F6FC),
        textMuted = Color(0xFFC0CEDE),
        primary = Color(0xFF9CBDFE),
        onPrimary = Color(0xFF172D54),
        primarySoft = Color(0xFF2B446D),
        onPrimarySoft = Color(0xFFD4E3FF),
        outline = Color(0xFF566985),
        success = Color(0xFF7DE1BA),
        danger = Color(0xFFFFAFBE),
        playerTrack = Color(0xFF536782),
    )

    val AuroraLight = SoundScapeColors(
        background = Color(0xFFF3F9F7),
        surface = Color(0xFFFFFFFF),
        surfaceAlt = Color(0xFFE4F1EC),
        text = Color(0xFF17342E),
        textMuted = Color(0xFF4A625B),
        primary = Color(0xFF0B6A57),
        onPrimary = Color(0xFFFFFFFF),
        primarySoft = Color(0xFFCFF1E5),
        onPrimarySoft = Color(0xFF165B4D),
        outline = Color(0xFFB4D1C7),
        success = Color(0xFF136949),
        danger = Color(0xFFA53648),
        playerTrack = Color(0xFFB5DCD1),
    )

    val AuroraDark = SoundScapeColors(
        background = Color(0xFF0B1C1A),
        surface = Color(0xFF142C29),
        surfaceAlt = Color(0xFF20403A),
        text = Color(0xFFE6F8F2),
        textMuted = Color(0xFFBED4CB),
        primary = Color(0xFF88E6CA),
        onPrimary = Color(0xFF0D3027),
        primarySoft = Color(0xFF255345),
        onPrimarySoft = Color(0xFFC2F5E2),
        outline = Color(0xFF52766D),
        success = Color(0xFF8CE3B8),
        danger = Color(0xFFFFAFBE),
        playerTrack = Color(0xFF557E73),
    )

    val VioletLight = SoundScapeColors(
        background = Color(0xFFF8F6FC),
        surface = Color(0xFFFFFFFF),
        surfaceAlt = Color(0xFFF0EAF9),
        text = Color(0xFF2D2243),
        textMuted = Color(0xFF665778),
        primary = Color(0xFF6745B7),
        onPrimary = Color(0xFFFFFFFF),
        primarySoft = Color(0xFFEAE0FA),
        onPrimarySoft = Color(0xFF49318E),
        outline = Color(0xFFCFC1E1),
        success = Color(0xFF196A51),
        danger = Color(0xFFAA344D),
        playerTrack = Color(0xFFD4C4EC),
    )

    val VioletDark = SoundScapeColors(
        background = Color(0xFF161021),
        surface = Color(0xFF241A35),
        surfaceAlt = Color(0xFF352646),
        text = Color(0xFFF5EDFF),
        textMuted = Color(0xFFD1C2DE),
        primary = Color(0xFFCEB0FF),
        onPrimary = Color(0xFF30184F),
        primarySoft = Color(0xFF4E3974),
        onPrimarySoft = Color(0xFFEFE0FF),
        outline = Color(0xFF796591),
        success = Color(0xFF88E5BE),
        danger = Color(0xFFFFAFBE),
        playerTrack = Color(0xFF725E8B),
    )

    fun colors(preset: SoundScapePreset, dark: Boolean): SoundScapeColors = when (preset) {
        SoundScapePreset.Atlas -> if (dark) AtlasDark else AtlasLight
        SoundScapePreset.Aurora -> if (dark) AuroraDark else AuroraLight
        SoundScapePreset.Violet -> if (dark) VioletDark else VioletLight
    }
}
