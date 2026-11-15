package com.soundscape.app.core.designsystem.ui.theme


import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.soundscape.app.core.designsystem.R

val Manrope = FontFamily(
    Font(R.font.manrope_semibold, FontWeight.SemiBold),
    Font(R.font.manrope_bold, FontWeight.Bold),
)
val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
)

private fun style(family: FontFamily, weight: FontWeight, size: Int, line: Int) = TextStyle(
    fontFamily = family, fontWeight = weight,
    fontSize = size.sp, lineHeight = line.sp, letterSpacing = 0.sp,
)

private val display = style(Manrope, FontWeight.Bold, 32, 40)
private val headlineL = style(Manrope, FontWeight.Bold, 28, 36)
private val headlineM = style(Manrope, FontWeight.SemiBold, 24, 32)
private val titleL = style(Manrope, FontWeight.SemiBold, 20, 28)
private val titleM = style(Manrope, FontWeight.SemiBold, 18, 26)
private val bodyL = style(Inter, FontWeight.Normal, 16, 24)
private val bodyM = style(Inter, FontWeight.Normal, 14, 21)
private val labelL = style(Inter, FontWeight.SemiBold, 14, 20)
private val labelM = style(Inter, FontWeight.Medium, 12, 18)
private val caption = style(Inter, FontWeight.Normal, 12, 18)

val SoundScapeTypography = Typography(
    displayLarge = display, displayMedium = display, displaySmall = display,
    headlineLarge = headlineL, headlineMedium = headlineM, headlineSmall = headlineM,
    titleLarge = titleL, titleMedium = titleM, titleSmall = titleM,
    bodyLarge = bodyL, bodyMedium = bodyM, bodySmall = caption,
    labelLarge = labelL, labelMedium = labelM, labelSmall = labelM,
)
