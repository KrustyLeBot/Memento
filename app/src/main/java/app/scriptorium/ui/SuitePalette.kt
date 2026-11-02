// Generated: the same recipe in every app of the suite (Synchrotron, Scriptorium, Ecoutify, Météorite).
package app.scriptorium.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Fixed brand colors (never the wallpaper's), from the app accent #B0446A: Material's tonal-spot
 * scheme for surfaces and containers, the accent at full strength for `primary`.
 */
internal val SuiteLightColors = lightColorScheme(
    primary = Color(0xFF912C52),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFAAFC3),
    onPrimaryContainer = Color(0xFF622D3E),
    inversePrimary = Color(0xFFFFB1C6),
    secondary = Color(0xFF75575F),
    onSecondary = Color(0xFFFFF7F7),
    secondaryContainer = Color(0xFFFFD9E1),
    onSecondaryContainer = Color(0xFF674A51),
    tertiary = Color(0xFF72547B),
    onTertiary = Color(0xFFFFF7FB),
    tertiaryContainer = Color(0xFFF6D0FD),
    onTertiaryContainer = Color(0xFF614469),
    background = Color(0xFFFFF8F8),
    onBackground = Color(0xFF3C2F32),
    surface = Color(0xFFFFF8F8),
    onSurface = Color(0xFF3C2F32),
    surfaceVariant = Color(0xFFF3DDE1),
    onSurfaceVariant = Color(0xFF6B5B5E),
    surfaceTint = Color(0xFFA1385E),
    inverseSurface = Color(0xFF130D0E),
    inverseOnSurface = Color(0xFFA69A9C),
    error = Color(0xFFA8364B),
    onError = Color(0xFFFFF7F7),
    errorContainer = Color(0xFFF97386),
    onErrorContainer = Color(0xFF6E0523),
    outline = Color(0xFF88767A),
    outlineVariant = Color(0xFFC1ADB1),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFFF8F8),
    surfaceContainer = Color(0xFFFCE9EC),
    surfaceContainerHigh = Color(0xFFF7E3E7),
    surfaceContainerHighest = Color(0xFFF3DDE1),
    surfaceContainerLow = Color(0xFFFFF0F2),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFEBD5D9),
)

internal val SuiteDarkColors = darkColorScheme(
    primary = Color(0xFFFFB1C6),
    onPrimary = Color(0xFF640330),
    primaryContainer = Color(0xFF734552),
    onPrimaryContainer = Color(0xFFFFDAE2),
    inversePrimary = Color(0xFFA1385E),
    secondary = Color(0xFFE3BDC5),
    onSecondary = Color(0xFF543940),
    secondaryContainer = Color(0xFF4E343B),
    onSecondaryContainer = Color(0xFFDBB6BE),
    tertiary = Color(0xFFFDE3FF),
    onTertiary = Color(0xFF694C72),
    tertiaryContainer = Color(0xFFF6D0FD),
    onTertiaryContainer = Color(0xFF614469),
    background = Color(0xFF130D0E),
    onBackground = Color(0xFFF6E0E4),
    surface = Color(0xFF130D0E),
    onSurface = Color(0xFFF6E0E4),
    surfaceVariant = Color(0xFF2F2225),
    onSurfaceVariant = Color(0xFFBAA6AA),
    surfaceTint = Color(0xFFFFB1C6),
    inverseSurface = Color(0xFFFFF8F8),
    inverseOnSurface = Color(0xFF5C5354),
    error = Color(0xFFF97386),
    onError = Color(0xFF490013),
    errorContainer = Color(0xFF871C34),
    onErrorContainer = Color(0xFFFF97A3),
    outline = Color(0xFF837175),
    outlineVariant = Color(0xFF534447),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF36292C),
    surfaceContainer = Color(0xFF211719),
    surfaceContainerHigh = Color(0xFF281D1F),
    surfaceContainerHighest = Color(0xFF2F2225),
    surfaceContainerLow = Color(0xFF191113),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceDim = Color(0xFF130D0E),
)

/** Rounded shapes shared by the whole suite. */
internal val SuiteShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)
