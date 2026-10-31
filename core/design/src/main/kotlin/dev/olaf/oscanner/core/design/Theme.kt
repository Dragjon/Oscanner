package dev.olaf.oscanner.core.design

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.olaf.oscanner.core.model.ThemeMode

/*
 * Soft Card, Night. Warm charcoal ground, cards one tonal step up, and one orange that only
 * ever marks something pressable. Ink on orange is dark: white on this orange fails AA.
 */
private val Ink = Color(0xFF1E0E07)

private val NightScheme = darkColorScheme(
    primary = Color(0xFFFF6A3D),
    onPrimary = Ink,
    primaryContainer = Color(0xFF3B231A),
    onPrimaryContainer = Color(0xFFFFDBCE),
    secondary = Color(0xFFB3A99D),
    onSecondary = Color(0xFF161412),
    secondaryContainer = Color(0xFF3B231A),
    onSecondaryContainer = Color(0xFFFFDBCE),
    tertiary = Color(0xFF9AD3A6),
    onTertiary = Color(0xFF0E2615),
    background = Color(0xFF161412),
    onBackground = Color(0xFFF4EEE6),
    surface = Color(0xFF161412),
    onSurface = Color(0xFFF4EEE6),
    surfaceVariant = Color(0xFF2C2824),
    onSurfaceVariant = Color(0xFFB3A99D),
    surfaceContainerLowest = Color(0xFF110F0E),
    surfaceContainerLow = Color(0xFF1C1A17),
    surfaceContainer = Color(0xFF221F1C),
    surfaceContainerHigh = Color(0xFF2C2824),
    surfaceContainerHighest = Color(0xFF36312C),
    surfaceBright = Color(0xFF3A352F),
    surfaceDim = Color(0xFF161412),
    outline = Color(0xFF7D7369),
    outlineVariant = Color(0xFF3A352F),
    inverseSurface = Color(0xFFF4EEE6),
    inverseOnSurface = Color(0xFF221F1C),
    inversePrimary = Color(0xFFB8401A),
    error = Color(0xFFFF9C9C),
    onError = Color(0xFF3A0B0B),
    errorContainer = Color(0xFF4A1C1C),
    onErrorContainer = Color(0xFFFFD9D6),
    scrim = Color(0xFF000000),
)

/*
 * Soft Card, Day. Near-white warm paper and a lighter coral, so the light theme feels airy
 * rather than beige. Ink on the coral stays dark (about 8:1).
 */
private val DayScheme = lightColorScheme(
    primary = Color(0xFFFF8052),
    onPrimary = Ink,
    primaryContainer = Color(0xFFFFEBE1),
    onPrimaryContainer = Color(0xFF3B1607),
    secondary = Color(0xFF6F625A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFEBE1),
    onSecondaryContainer = Color(0xFF3B1607),
    tertiary = Color(0xFF2E7D4F),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFFFFAF5),
    onBackground = Color(0xFF2B241F),
    surface = Color(0xFFFFFAF5),
    onSurface = Color(0xFF2B241F),
    surfaceVariant = Color(0xFFFBF2E9),
    onSurfaceVariant = Color(0xFF6F625A),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFDFA),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFFBF2E9),
    surfaceContainerHighest = Color(0xFFF5E9DC),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFF5E9DC),
    outline = Color(0xFF9A8C80),
    outlineVariant = Color(0xFFEEE3D7),
    inverseSurface = Color(0xFF2B241F),
    inverseOnSurface = Color(0xFFFFFAF5),
    inversePrimary = Color(0xFFFF6A3D),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410E0B),
    scrim = Color(0xFF000000),
)

/** Roles Material 3 has no slot for. */
@Immutable
data class OscannerExtras(
    val success: Color,
    /** Paper-white outline for the detected page in the camera: status, never an action. */
    val detectionQuad: Color,
    /** Hatch drawn over what an erase tool is about to remove. */
    val maskHatch: Color,
    /** Light theme gives the floating dock one soft ambient shadow; dark relies on tone alone. */
    val dockShadow: Boolean,
    /**
     * The floating dock: a step lighter than the cards under it (in light, white on the shadow),
     * with a hairline edge, so it stays apart from whatever scrolls behind it.
     */
    val dock: Color,
    val dockEdge: Color,
)

private val NightExtras = OscannerExtras(
    success = Color(0xFF9AD3A6),
    detectionQuad = Color(0xFFF4EEE6),
    maskHatch = Color(0x59F4EEE6),
    dockShadow = false,
    dock = Color(0xFF302B27),
    dockEdge = Color(0x24F4EEE6),
)

private val DayExtras = OscannerExtras(
    success = Color(0xFF2E7D4F),
    detectionQuad = Color(0xFFF4EEE6),
    maskHatch = Color(0x4D2B241F),
    dockShadow = true,
    dock = Color(0xFFFFFFFF),
    dockEdge = Color(0xFFE6D9CB),
)

val LocalOscannerExtras = staticCompositionLocalOf { NightExtras }

val OscannerShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun isDarkTheme(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.Dark -> true
    ThemeMode.Light -> false
    ThemeMode.System -> isSystemInDarkTheme()
}

@Composable
fun OscannerTheme(
    themeMode: ThemeMode = ThemeMode.Dark,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = isDarkTheme(themeMode)
    val base = if (dark) NightScheme else DayScheme
    val scheme = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        withWallpaperAction(base, dark)
    } else {
        base
    }
    CompositionLocalProvider(LocalOscannerExtras provides if (dark) NightExtras else DayExtras) {
        MaterialTheme(
            colorScheme = scheme,
            typography = OscannerTypography,
            shapes = OscannerShapes,
        ) {
            // Text and icons drawn straight on a screen's background (not inside a Surface) would
            // otherwise default to black, which vanishes on the dark theme.
            CompositionLocalProvider(LocalContentColor provides scheme.onBackground, content = content)
        }
    }
}

/**
 * Wallpaper color replaces only the action color; grounds and text stay ours so the
 * "only actions are colored" rule and the contrast checks survive.
 */
@Composable
@RequiresApi(Build.VERSION_CODES.S)
private fun withWallpaperAction(base: ColorScheme, dark: Boolean): ColorScheme {
    val context = LocalContext.current
    val dynamic = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    return base.copy(
        primary = dynamic.primary,
        onPrimary = dynamic.onPrimary,
        primaryContainer = dynamic.primaryContainer,
        onPrimaryContainer = dynamic.onPrimaryContainer,
        secondaryContainer = dynamic.primaryContainer,
        onSecondaryContainer = dynamic.onPrimaryContainer,
    )
}
