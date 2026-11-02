package app.memento.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.memento.MainViewModel
import app.memento.contacts.Contact
import app.memento.contacts.ContactList
import app.memento.contacts.RequestDialog

/** What another app's contact action needs to ask: new or existing contact, import, the Contacts permission. */
@Composable
fun ContactRequestDialogs(vm: MainViewModel) {
    val flow = vm.contacts.requests
    when (val d = flow.dialog) {
        null -> Unit
        is RequestDialog.Choose -> AlertDialog(
            onDismissRequest = { flow.cancel() },
            title = { Text("Ajouter aux contacts") },
            text = { Text(summary(d.draft)) },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { flow.createNew(d.draft) }) { Text("Nouveau contact") }
                    TextButton(onClick = { flow.pickExisting(d.draft) }) { Text("Ajouter à un contact existant") }
                    TextButton(onClick = { flow.cancel() }) { Text("Annuler") }
                }
            },
        )
        is RequestDialog.Pick -> PickContactDialog(vm, d.draft)
        is RequestDialog.Import -> AlertDialog(
            onDismissRequest = { flow.cancel() },
            title = { Text(d.draft.displayName) },
            text = {
                Text(
                    "Ce contact vient d'un autre compte du téléphone, pas de Mémento. " +
                        "L'importer dans Mémento pour le ${if (d.edit) "modifier" else "retrouver ici"} ?",
                )
            },
            confirmButton = { TextButton(onClick = { flow.createNew(d.draft) }) { Text("Importer") } },
            dismissButton = { TextButton(onClick = { flow.cancel() }) { Text("Annuler") } },
        )
        is RequestDialog.Permission -> PermissionDialog(vm)
    }
}

private fun summary(c: Contact): String {
    val parts = listOfNotNull(c.name.ifEmpty { null }) + c.phones.map { it.number } + c.emails + listOfNotNull(c.address)
    return parts.joinToString("\n").ifEmpty { "Créer un contact ou compléter un contact de Mémento." }
}

@Composable
private fun PickContactDialog(vm: MainViewModel, draft: Contact) {
    val flow = vm.contacts.requests
    val all by vm.contacts.contacts.collectAsState()
    var query by remember { mutableStateOf("") }
    val found = remember(all, query) { ContactList.search(all.filter { !it.conflict }, query, vm.contacts.firstNameFirst) }
    AlertDialog(
        onDismissRequest = { flow.cancel() },
        title = { Text("Ajouter à…") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { query = it }, placeholder = { Text("Rechercher") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(found, key = { it.id.ifEmpty { it.path } }) { c ->
                        Row(
                            Modifier.fillMaxWidth().clickable { flow.addTo(c, draft) }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            ContactAvatar(c, 36.dp)
                            Column(Modifier.weight(1f)) {
                                Text(c.displayName, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                c.phones.firstOrNull()?.let {
                                    Text(it.number, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
                if (found.isEmpty()) Text("Aucun contact", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { flow.createNew(draft) }) { Text("Nouveau contact") } },
        dismissButton = { TextButton(onClick = { flow.cancel() }) { Text("Annuler") } },
    )
}

private fun canRead(ctx: android.content.Context) =
    androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.READ_CONTACTS) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED

@Composable
private fun PermissionDialog(vm: MainViewModel) {
    val flow = vm.contacts.requests
    val ctx = LocalContext.current
    var refused by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        refused = !granted
        flow.permissionResult(granted)
    }
    AlertDialog(
        onDismissRequest = { flow.cancel() },
        title = { Text("Accès aux contacts") },
        text = {
            Text(
                if (refused) "Autorisation refusée. Pour ouvrir dans Mémento les contacts choisis dans d'autres apps, " +
                    "autorise « Contacts » dans les autorisations de l'app."
                else "Pour savoir quel contact l'autre app a ouvert, Mémento doit lire les contacts du téléphone " +
                    "(il n'y écrit que sa propre copie).",
            )
        },
        confirmButton = {
            if (refused) {
                Row {
                    // back from the app's permissions: try again without another round trip
                    TextButton(onClick = { flow.permissionResult(canRead(ctx)) }) { Text("Réessayer") }
                    TextButton(onClick = {
                        ctx.startActivity(
                            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }) { Text("Autorisations de l'app") }
                }
            } else {
                TextButton(onClick = { ask.launch(android.Manifest.permission.READ_CONTACTS) }) { Text("Autoriser") }
            }
        },
        dismissButton = { TextButton(onClick = { flow.cancel() }) { Text("Annuler") } },
    )
}
