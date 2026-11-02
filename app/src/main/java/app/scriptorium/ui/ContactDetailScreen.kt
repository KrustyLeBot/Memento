package app.scriptorium.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.scriptorium.MainViewModel
import app.scriptorium.Snack
import app.scriptorium.contacts.Contact
import app.scriptorium.contacts.Phones
import app.scriptorium.suite.SuiteTopBar

/** One contact: a row per number and e-mail with its action (dial, SMS, mail, map); long press copies. */
@Composable
fun ContactDetailScreen(vm: MainViewModel) {
    val c = vm.contacts
    val all by c.contacts.collectAsState()
    val contact = remember(all, c.openId) { c.current() }
    if (contact == null) {
        // deleted meanwhile (sync, another device)
        LaunchedEffect(Unit) { c.back() }
        return
    }
    val snackbar = rememberVmSnackbar(vm)
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var menu by remember { mutableStateOf(false) }

    fun launch(intent: Intent, what: String) {
        try {
            ctx.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            vm.snack = Snack("Aucune application pour $what")
        }
    }
    fun copy(text: String, what: String) {
        clipboard.setText(AnnotatedString(text))
        vm.snack = Snack("$what copié")
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            SuiteTopBar("", onBack = { vm.back() }) {
                IconButton(onClick = { c.toggleFavorite(contact) }) {
                    Icon(if (contact.favorite) Icons.Default.Star else Icons.Default.StarBorder, if (contact.favorite) "Retirer des favoris" else "Ajouter aux favoris")
                }
                IconButton(onClick = { c.edit(contact) }) { Icon(Icons.Default.Edit, "Modifier") }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Menu") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text("Modifier") }, { menu = false; c.edit(contact) })
                        DropdownMenuItem({ Text("Partager (.vcf)") }, { menu = false; c.share(contact) })
                        DropdownMenuItem(
                            { Text(if (contact.favorite) "Retirer des favoris" else "Ajouter aux favoris") },
                            { menu = false; c.toggleFavorite(contact) },
                        )
                        DropdownMenuItem({ Text("Supprimer", color = MaterialTheme.colorScheme.error) }, { menu = false; c.delete(contact) })
                    }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                ContactAvatar(contact, 88.dp)
                Spacer(Modifier.height(10.dp))
                Text(contact.displayName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                if (contact.conflict) Text("Copie en conflit (synchro) : supprime celle qui est en trop", color = MaterialTheme.colorScheme.error)
            }
            if (contact.phones.isNotEmpty()) {
                DetailCard {
                    contact.phones.forEach { p ->
                        var rowMenu by remember(p) { mutableStateOf(false) }
                        Box {
                            DetailRow(
                                Icons.Default.Phone, p.number, p.label,
                                onClick = { launch(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", Phones.dialable(p.number), null)), "appeler") },
                                onLongClick = { rowMenu = true },
                            ) {
                                IconButton(onClick = { launch(Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", Phones.dialable(p.number), null)), "les SMS") }) {
                                    Icon(Icons.AutoMirrored.Filled.Message, "SMS")
                                }
                            }
                            DropdownMenu(rowMenu, { rowMenu = false }) {
                                DropdownMenuItem({ Text("Copier") }, { rowMenu = false; copy(p.number, "Numéro") })
                                DropdownMenuItem({ Text("SMS") }, {
                                    rowMenu = false
                                    launch(Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", Phones.dialable(p.number), null)), "les SMS")
                                })
                            }
                        }
                    }
                }
            }
            if (contact.emails.isNotEmpty()) {
                DetailCard {
                    contact.emails.forEach { e ->
                        DetailRow(
                            Icons.Default.Email, e, null,
                            onClick = { launch(Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", e, null)), "les e-mails") },
                            onLongClick = { copy(e, "E-mail") },
                        )
                    }
                }
            }
            contact.address?.let { a ->
                DetailCard {
                    DetailRow(
                        Icons.Default.Place, a, null,
                        onClick = { launch(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(a))), "les cartes") },
                        onLongClick = { copy(a, "Adresse") },
                    )
                }
            }
            contact.birthday?.let { b ->
                DetailCard { DetailRow(Icons.Default.Cake, birthdayText(b), "anniversaire", onClick = {}, onLongClick = {}) }
            }
            if (contact.notes.isNotBlank()) {
                DetailCard {
                    Text("Notes", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(contact.notes)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun DetailCard(content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) { content() }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun DetailRow(
    icon: ImageVector, text: String, label: String?, onClick: () -> Unit, onLongClick: () -> Unit,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(text, style = MaterialTheme.typography.bodyLarge)
            if (!label.isNullOrBlank()) Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing()
    }
}
