package app.memento.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import app.memento.Module

private val Module.icon: ImageVector
    get() = when (this) {
        Module.NOTES -> Icons.Default.EditNote
        Module.AGENDA -> Icons.Default.CalendarMonth
        Module.ACTIVITIES -> Icons.Default.DirectionsRun
        Module.CONTACTS -> Icons.Default.Person
    }

/**
 * Bottom bar between the modules, with the settings as its last entry; shown on the home screen of
 * each module and on the settings. [current] null means the settings are shown.
 */
@Composable
fun ModuleBar(modules: List<Module>, current: Module?, onSelect: (Module) -> Unit, onSettings: () -> Unit, modifier: Modifier = Modifier) {
    NavigationBar(modifier) {
        modules.forEach { m ->
            NavigationBarItem(
                selected = m == current,
                onClick = { if (m != current) onSelect(m) },
                icon = { Icon(m.icon, null) },
                label = { BarLabel(m.label) },
            )
        }
        NavigationBarItem(
            selected = current == null,
            onClick = { if (current != null) onSettings() },
            icon = { Icon(Icons.Default.Settings, null) },
            label = { BarLabel("Réglages") },
        )
    }
}

/** Five entries on a narrow phone: a slightly smaller label that never wraps or gets cut mid-word. */
@Composable
private fun BarLabel(text: String) {
    Text(text, maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelSmall)
}
