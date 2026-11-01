package app.scriptorium.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.scriptorium.StorageConfig

@Composable
fun PermissionScreen(storage: StorageConfig, onGrant: () -> Unit, onPickFolder: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.padding(32.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Scriptorium", style = MaterialTheme.typography.headlineLarge)
            Text(
                if (storage is StorageConfig.Default) {
                    "Tes notes sont de simples fichiers dans Documents/Scriptorium, pour que ton app de " +
                        "synchronisation puisse les récupérer. Scriptorium a besoin d'y accéder."
                } else {
                    "L'accès au dossier choisi a été perdu. Sélectionne-le à nouveau."
                },
                textAlign = TextAlign.Center,
            )
            if (storage is StorageConfig.Default) {
                Button(onClick = onGrant) { Text("Autoriser l'accès aux fichiers") }
            }
            OutlinedButton(onClick = onPickFolder) { Text("Choisir un autre dossier") }
        }
    }
}
