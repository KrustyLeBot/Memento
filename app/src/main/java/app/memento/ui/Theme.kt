package app.memento.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import app.memento.DEFAULT_COLOR
import app.memento.ThemeMode

@Composable
fun MementoTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(colorScheme = if (dark) SuiteDarkColors else SuiteLightColors, shapes = SuiteShapes, content = content)
}

/** Keep-like palette: (id, French label, light, dark). */
data class NoteColor(val id: String, val label: String, val light: Color, val dark: Color)

val NoteColors = listOf(
    NoteColor(DEFAULT_COLOR, "Par défaut", Color.Transparent, Color.Transparent),
    NoteColor("red", "Corail", Color(0xFFF28B82), Color(0xFF77172E)),
    NoteColor("orange", "Pêche", Color(0xFFFBBC04), Color(0xFF692B17)),
    NoteColor("yellow", "Sable", Color(0xFFFFF475), Color(0xFF7C4A03)),
    NoteColor("green", "Menthe", Color(0xFFCCFF90), Color(0xFF264D3B)),
    NoteColor("teal", "Sauge", Color(0xFFA7FFEB), Color(0xFF0C625D)),
    NoteColor("blue", "Brume", Color(0xFFCBF0F8), Color(0xFF256377)),
    NoteColor("darkblue", "Orage", Color(0xFFAECBFA), Color(0xFF284255)),
    NoteColor("purple", "Lavande", Color(0xFFD7AEFB), Color(0xFF472E5B)),
    NoteColor("pink", "Rose", Color(0xFFFDCFE8), Color(0xFF6C394F)),
    NoteColor("brown", "Argile", Color(0xFFE6C9A8), Color(0xFF4B443A)),
    NoteColor("gray", "Graphite", Color(0xFFE8EAED), Color(0xFF232427)),
)

@Composable
fun isDarkTheme(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f

private fun Color.luminance(): Float = 0.299f * red + 0.587f * green + 0.114f * blue

/** Background for a note colour id; falls back to the surface colour for "default". */
@Composable
fun noteBackground(id: String): Color {
    val c = NoteColors.firstOrNull { it.id == id }
    if (c == null || c.id == DEFAULT_COLOR) return MaterialTheme.colorScheme.surfaceContainer
    return if (isDarkTheme()) c.dark else c.light
}

@Composable
fun noteForeground(id: String): Color {
    val c = NoteColors.firstOrNull { it.id == id }
    if (c == null || c.id == DEFAULT_COLOR) return MaterialTheme.colorScheme.onSurface
    return if (isDarkTheme()) Color(0xFFE8EAED) else Color(0xFF202124)
}
