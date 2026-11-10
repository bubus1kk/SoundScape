package com.soundscape.app.core.designsystem.ui.theme


import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

object SoundScapeRadii {
    val radius8 = 8.dp
    val radius12 = 12.dp
    val radius16 = 16.dp
    val radius24 = 24.dp
    val radius28 = 28.dp
}

val SoundScapeShapes = Shapes(
    extraSmall = RoundedCornerShape(SoundScapeRadii.radius8),
    small = RoundedCornerShape(SoundScapeRadii.radius12),
    medium = RoundedCornerShape(SoundScapeRadii.radius16),
    large = RoundedCornerShape(SoundScapeRadii.radius24),
    extraLarge = RoundedCornerShape(SoundScapeRadii.radius28),
)
