package app.scriptorium.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.scriptorium.LegacyImport
import app.scriptorium.MainViewModel

/** One-time move of Documents/Griffonote (the app's former name) into Documents/Scriptorium. */
@Composable
fun LegacyImportDialog(vm: MainViewModel) {
    when (val s = vm.legacyImport ?: return) {
        is LegacyImport.Offer -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Importer depuis Griffonote") },
            text = {
                Text(
                    "Le dossier Documents/Griffonote contient ${s.files} fichiers (${s.bytes / 1_000_000} Mo). " +
                        "Les copier dans Documents/Scriptorium ? L'ancien dossier n'est pas touché pendant la copie.",
                )
            },
            confirmButton = { Button(onClick = { vm.importLegacyFolder() }) { Text("Importer") } },
            dismissButton = { TextButton(onClick = { vm.dismissLegacyImport() }) { Text("Ne pas importer") } },
        )
        is LegacyImport.Copying -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Import en cours") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (s.total == 0) "Préparation…" else "${s.done} / ${s.total} fichiers")
                    if (s.total == 0) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else LinearProgressIndicator(progress = { s.done.toFloat() / s.total }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {},
        )
        is LegacyImport.Copied -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Import terminé") },
            text = {
                Text(
                    "${s.files} fichiers copiés et vérifiés. Supprimer l'ancien dossier Documents/Griffonote ?\n\n" +
                        "Attention : si Synchrotron synchronise encore ce dossier, la suppression sera aussi faite " +
                        "dans le cloud. Change d'abord le dossier dans Synchrotron.",
                )
            },
            confirmButton = { Button(onClick = { vm.deleteLegacyFolder() }) { Text("Supprimer l'ancien dossier") } },
            dismissButton = { TextButton(onClick = { vm.dismissLegacyImport() }) { Text("Garder") } },
        )
        is LegacyImport.Failed -> AlertDialog(
            onDismissRequest = { vm.dismissLegacyImport() },
            title = { Text("Import interrompu") },
            text = { Text("${s.message}\n\nL'ancien dossier est intact. L'import sera reproposé au prochain lancement.") },
            confirmButton = { TextButton(onClick = { vm.legacyImport = null }) { Text("OK") } },
        )
    }
}
