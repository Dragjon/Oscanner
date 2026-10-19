package dev.olaf.oscanner.core.design

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Figtree = FontFamily(
    Font(R.font.figtree_regular, FontWeight.Normal),
    Font(R.font.figtree_medium, FontWeight.Medium),
    Font(R.font.figtree_semibold, FontWeight.SemiBold),
)

private fun style(size: Int, line: Int, weight: FontWeight) = TextStyle(
    fontFamily = Figtree,
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = weight,
    letterSpacing = 0.sp,
)

/** Material roles mapped to one family; sizes per PLAN.md §7.2. */
val OscannerTypography = Typography(
    displayLarge = style(48, 56, FontWeight.SemiBold),
    displayMedium = style(40, 48, FontWeight.SemiBold),
    displaySmall = style(34, 40, FontWeight.SemiBold),
    headlineLarge = style(32, 38, FontWeight.SemiBold),
    headlineMedium = style(28, 34, FontWeight.SemiBold),
    headlineSmall = style(24, 30, FontWeight.SemiBold),
    titleLarge = style(20, 26, FontWeight.SemiBold),
    titleMedium = style(16, 22, FontWeight.SemiBold),
    titleSmall = style(14, 20, FontWeight.SemiBold),
    bodyLarge = style(16, 24, FontWeight.Normal),
    bodyMedium = style(14, 20, FontWeight.Normal),
    bodySmall = style(12, 16, FontWeight.Normal),
    labelLarge = style(15, 20, FontWeight.SemiBold),
    labelMedium = style(12, 16, FontWeight.Medium),
    labelSmall = style(11, 14, FontWeight.Medium),
)
