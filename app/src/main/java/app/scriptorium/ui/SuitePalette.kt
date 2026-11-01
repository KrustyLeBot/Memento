// Generated: the same recipe in every app of the suite (Synchrotron, Scriptorium, Ecoutify, Météorite).
package app.scriptorium.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Fixed brand colors (never the wallpaper's), from the app accent #F9A825: Material's tonal-spot
 * scheme for surfaces and containers, the accent at full strength for `primary`.
 */
internal val SuiteLightColors = lightColorScheme(
    primary = Color(0xFF835400),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFAC681),
    onPrimaryContainer = Color(0xFF613E04),
    inversePrimary = Color(0xFFFFB957),
    secondary = Color(0xFF715B41),
    onSecondary = Color(0xFFFFF7F3),
    secondaryContainer = Color(0xFFFBDEBC),
    onSecondaryContainer = Color(0xFF624E35),
    tertiary = Color(0xFF86523C),
    onTertiary = Color(0xFFFFF7F5),
    tertiaryContainer = Color(0xFFFFB99E),
    onTertiaryContainer = Color(0xFF643622),
    background = Color(0xFFFFF8F4),
    onBackground = Color(0xFF3B3125),
    surface = Color(0xFFFFF8F4),
    onSurface = Color(0xFF3B3125),
    surfaceVariant = Color(0xFFF1E0CE),
    onSurfaceVariant = Color(0xFF695D4F),
    surfaceTint = Color(0xFF835400),
    inverseSurface = Color(0xFF120D08),
    inverseOnSurface = Color(0xFFA59B92),
    error = Color(0xFFA73B21),
    onError = Color(0xFFFFF7F6),
    errorContainer = Color(0xFFFD795A),
    onErrorContainer = Color(0xFF6E1400),
    outline = Color(0xFF86796A),
    outlineVariant = Color(0xFFBFAF9F),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFFF8F4),
    surfaceContainer = Color(0xFFFAEBDD),
    surfaceContainerHigh = Color(0xFFF5E6D6),
    surfaceContainerHighest = Color(0xFFF1E0CE),
    surfaceContainerLow = Color(0xFFFFF1E5),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFE8D7C6),
)

internal val SuiteDarkColors = darkColorScheme(
    primary = Color(0xFFFFCB8A),
    onPrimary = Color(0xFF462B00),
    primaryContainer = Color(0xFF6C4C21),
    onPrimaryContainer = Color(0xFFFFDEB7),
    inversePrimary = Color(0xFF835400),
    secondary = Color(0xFFDEC2A2),
    onSecondary = Color(0xFF503D25),
    secondaryContainer = Color(0xFF4A3820),
    onSecondaryContainer = Color(0xFFD6BB9B),
    tertiary = Color(0xFFFFCCB9),
    onTertiary = Color(0xFF6F3E2A),
    tertiaryContainer = Color(0xFFFFB99E),
    onTertiaryContainer = Color(0xFF643622),
    background = Color(0xFF120D08),
    onBackground = Color(0xFFF4E3D1),
    surface = Color(0xFF120D08),
    onSurface = Color(0xFFF4E3D1),
    surfaceVariant = Color(0xFF2D2419),
    onSurfaceVariant = Color(0xFFB8A999),
    surfaceTint = Color(0xFFFFB957),
    inverseSurface = Color(0xFFFFF8F4),
    inverseOnSurface = Color(0xFF5B544C),
    error = Color(0xFFF97758),
    onError = Color(0xFF450900),
    errorContainer = Color(0xFF85230A),
    onErrorContainer = Color(0xFFFF9B82),
    outline = Color(0xFF807365),
    outlineVariant = Color(0xFF514639),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF342A1F),
    surfaceContainer = Color(0xFF1F1810),
    surfaceContainerHigh = Color(0xFF261E15),
    surfaceContainerHighest = Color(0xFF2D2419),
    surfaceContainerLow = Color(0xFF18120B),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceDim = Color(0xFF120D08),
)

/** Rounded shapes shared by the whole suite. */
internal val SuiteShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)
