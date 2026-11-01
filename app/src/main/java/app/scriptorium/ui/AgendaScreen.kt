package app.scriptorium.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.scriptorium.MainViewModel
import app.scriptorium.suite.SuiteTopBar

/** Placeholder replaced by the agenda module. */
@Composable
fun AgendaScreen(vm: MainViewModel) {
    Scaffold(topBar = { SuiteTopBar("Agenda") }) { p ->
        Box(Modifier.padding(p).fillMaxSize(), contentAlignment = Alignment.Center) { Text("Agenda") }
    }
}
