package app.scriptorium.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import app.scriptorium.Module

private val Module.icon: ImageVector
    get() = when (this) {
        Module.NOTES -> Icons.Default.EditNote
        Module.AGENDA -> Icons.Default.CalendarMonth
        Module.ACTIVITIES -> Icons.Default.DirectionsRun
        Module.CONTACTS -> Icons.Default.Person
    }

/** Bottom bar between the modules; shown on the home screen of each module only. */
@Composable
fun ModuleBar(modules: List<Module>, current: Module, onSelect: (Module) -> Unit) {
    NavigationBar {
        modules.forEach { m ->
            NavigationBarItem(
                selected = m == current,
                onClick = { if (m != current) onSelect(m) },
                icon = { Icon(m.icon, null) },
                label = { Text(m.label) },
            )
        }
    }
}
