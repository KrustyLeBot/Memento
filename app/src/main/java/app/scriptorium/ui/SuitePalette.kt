// Generated: the same recipe in every app of the suite (Synchrotron, Scriptorium, Ecoutify, Météorite).
package app.scriptorium.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Fixed brand colors (never the wallpaper's), from the app accent #22A862: Material's tonal-spot
 * scheme for surfaces and containers, the accent at full strength for `primary`.
 */
internal val SuiteLightColors = lightColorScheme(
    primary = Color(0xFF006D3B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB8F0C5),
    onPrimaryContainer = Color(0xFF295B3B),
    inversePrimary = Color(0xFF63DE91),
    secondary = Color(0xFF506454),
    onSecondary = Color(0xFFE8FFEA),
    secondaryContainer = Color(0xFFD2E8D4),
    onSecondaryContainer = Color(0xFF435647),
    tertiary = Color(0xFF576434),
    onTertiary = Color(0xFFF2FFC8),
    tertiaryContainer = Color(0xFFEBFBBC),
    onTertiaryContainer = Color(0xFF546131),
    background = Color(0xFFF7FAF4),
    onBackground = Color(0xFF2C342D),
    surface = Color(0xFFF7FAF4),
    onSurface = Color(0xFF2C342D),
    surfaceVariant = Color(0xFFDCE5DA),
    onSurfaceVariant = Color(0xFF596159),
    surfaceTint = Color(0xFF006D3B),
    inverseSurface = Color(0xFF0B0F0C),
    inverseOnSurface = Color(0xFF9A9E98),
    error = Color(0xFFA83836),
    onError = Color(0xFFFFF7F6),
    errorContainer = Color(0xFFFA746F),
    onErrorContainer = Color(0xFF6E0A12),
    outline = Color(0xFF747D74),
    outlineVariant = Color(0xFFABB4AB),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFF7FAF4),
    surfaceContainer = Color(0xFFE9F0E7),
    surfaceContainerHigh = Color(0xFFE3EAE1),
    surfaceContainerHighest = Color(0xFFDCE5DA),
    surfaceContainerLow = Color(0xFFF0F5ED),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFD3DCD2),
)

internal val SuiteDarkColors = darkColorScheme(
    primary = Color(0xFF63DE91),
    onPrimary = Color(0xFF00391C),
    primaryContainer = Color(0xFF355A40),
    onPrimaryContainer = Color(0xFFC3EECB),
    inversePrimary = Color(0xFF006D3B),
    secondary = Color(0xFFB6CCB9),
    onSecondary = Color(0xFF314536),
    secondaryContainer = Color(0xFF2D4031),
    onSecondaryContainer = Color(0xFFAFC5B1),
    tertiary = Color(0xFFF3FFCB),
    onTertiary = Color(0xFF576434),
    tertiaryContainer = Color(0xFFE3F2B4),
    onTertiaryContainer = Color(0xFF4F5C2C),
    background = Color(0xFF0B0F0C),
    onBackground = Color(0xFFDFE8DD),
    surface = Color(0xFF0B0F0C),
    onSurface = Color(0xFFDFE8DD),
    surfaceVariant = Color(0xFF202821),
    onSurfaceVariant = Color(0xFFA4ADA4),
    surfaceTint = Color(0xFF63DE91),
    inverseSurface = Color(0xFFF7FAF4),
    inverseOnSurface = Color(0xFF525651),
    error = Color(0xFFFA746F),
    onError = Color(0xFF490006),
    errorContainer = Color(0xFF871F21),
    onErrorContainer = Color(0xFFFF9993),
    outline = Color(0xFF6F786F),
    outlineVariant = Color(0xFF414A42),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF262E27),
    surfaceContainer = Color(0xFF151B16),
    surfaceContainerHigh = Color(0xFF1A211C),
    surfaceContainerHighest = Color(0xFF202821),
    surfaceContainerLow = Color(0xFF0F1510),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceDim = Color(0xFF0B0F0C),
)

/** Rounded shapes shared by the whole suite. */
internal val SuiteShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)
