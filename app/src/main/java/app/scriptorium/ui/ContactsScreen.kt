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

/** Placeholder replaced by the contacts module. */
@Composable
fun ContactsScreen(vm: MainViewModel) {
    Scaffold(topBar = { SuiteTopBar("Contacts") }) { p ->
        Box(Modifier.padding(p).fillMaxSize(), contentAlignment = Alignment.Center) { Text("Contacts") }
    }
}
