package app.memento.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import app.memento.MainViewModel
import app.memento.R
import app.memento.contacts.Birthday
import app.memento.contacts.Contact
import app.memento.contacts.ContactImportState
import app.memento.contacts.ContactList
import app.memento.contacts.ContactsController
import app.memento.contacts.ContactsMirror
import app.memento.contacts.DuplicatePolicy
import app.memento.contacts.ImportOutcome
import app.memento.suite.SectionTitle
import app.memento.suite.SettingsCard
import app.memento.suite.SettingsHint
import java.time.LocalDate

private val VCARD_TYPES = setOf("text/vcard", "text/x-vcard", "text/directory")

/** The `.vcf` another app asks to open or shares with this one, if [intent] is that. */
fun vcardUri(intent: Intent?): Uri? {
    intent ?: return null
    val type = intent.type?.lowercase()?.substringBefore(';')?.trim()
    return when (intent.action) {
        Intent.ACTION_VIEW -> intent.data?.takeIf { type in VCARD_TYPES || it.lastPathSegment?.endsWith(".vcf", ignoreCase = true) == true }
        Intent.ACTION_SEND -> if (type in VCARD_TYPES) IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) else null
        else -> null
    }
}

/** A round badge with the contact's initial. */
@Composable
fun ContactAvatar(c: Contact, size: Dp = 40.dp) {
    val initial = c.displayName.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "#"
    Box(
        Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initial, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.SemiBold,
            style = if (size > 48.dp) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleMedium,
        )
    }
}

val MONTHS = listOf("janvier", "février", "mars", "avril", "mai", "juin", "juillet", "août", "septembre", "octobre", "novembre", "décembre")

/** "14 mai 1990 (36 ans)", "14 mai" without the year. */
fun birthdayText(b: Birthday, today: LocalDate = LocalDate.now()): String {
    val date = "${b.day}${if (b.day == 1) "er" else ""} ${MONTHS[b.month - 1]}" + (b.year?.let { " $it" } ?: "")
    val age = b.year?.let { y ->
        val had = today.monthValue > b.month || (today.monthValue == b.month && today.dayOfMonth >= b.day)
        (today.year - y - if (had) 0 else 1).takeIf { it >= 0 }
    }
    return if (age != null) "$date ($age an${if (age > 1) "s" else ""})" else date
}

/** "Contacts" section of the settings: sort order and the copy into the phone's contacts. */
@Composable
fun ContactsSettings(vm: MainViewModel) {
    val c = vm.contacts
    val ctx = LocalContext.current
    val appName = stringResource(R.string.app_name)
    var refused by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val granted = result.values.isNotEmpty() && result.values.all { it }
        refused = !granted
        c.setMirror(granted)
    }
    SectionTitle("Contacts")
    SettingsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Prénom d'abord", fontWeight = FontWeight.SemiBold)
                SettingsHint("Trie et regroupe la liste par prénom plutôt que par nom. Réglage de ce téléphone.")
            }
            Switch(c.firstNameFirst, { c.changeFirstNameFirst(it) })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Mes contacts dans le téléphone (appels, SMS, app Contacts)", fontWeight = FontWeight.SemiBold)
                SettingsHint(
                    "Synchronise les contacts avec ceux du téléphone, sous un compte « $appName », dans les deux sens : leur nom " +
                        "s'affiche lors d'un appel, dans les SMS, les messageries, la voiture, et un contact créé, modifié ou " +
                        "supprimé dans l'app Contacts du téléphone l'est aussi ici (et sur le PC, par le dossier). Modifié des deux " +
                        "côtés entre deux synchros : la version la plus récente gagne, l'autre est ajoutée à ses notes. Supprimé sur " +
                        "le téléphone : $appName propose de l'annuler pendant 7 jours. Dans l'app Contacts de Samsung, réglez " +
                        "« Emplacement de stockage par défaut » sur $appName (Contacts → menu → Paramètres) pour que les " +
                        "nouveaux contacts y soient enregistrés. Désactiver retire ces copies du téléphone (les fichiers restent). " +
                        "Demande l'accès aux contacts.",
                )
            }
            Switch(c.mirrorEnabled, { on ->
                when {
                    !on -> c.setMirror(false)
                    c.mirrorPermissionsGranted() -> c.setMirror(true)
                    else -> ask.launch(ContactsMirror.PERMISSIONS)
                }
            })
        }
        c.mirrorStatus?.let { if (c.mirrorFailed) Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) else SettingsHint(it) }
        // asks Android directly; the settings page only once Android stops asking (refused twice)
        if (c.mirrorEnabled && !c.mirrorPermissionsGranted()) {
            Button(onClick = { ask.launch(ContactsMirror.PERMISSIONS) }) { Text("Autoriser l'accès aux contacts") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (c.mirrorEnabled) OutlinedButton(onClick = { c.runMirror() }) { Text("Mettre à jour maintenant") }
            if (refused) {
                OutlinedButton(onClick = {
                    ctx.startActivity(
                        Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }) { Text("Autorisations de l'app") }
            }
        }
        Column {
            Text("$appName comme app de contacts", fontWeight = FontWeight.SemiBold)
            SettingsHint(
                "Android n'a pas de réglage « app de contacts par défaut ». La première fois qu'une app propose " +
                    "« Créer un contact », « Ajouter aux contacts » ou ouvre un contact, choisis $appName puis « Toujours » : " +
                    "ces actions ouvriront ensuite la fiche ou l'éditeur de $appName. L'app Contacts du téléphone affiche toujours " +
                    "les contacts synchronisés (réglage ci-dessus activé) et permet de les modifier. Un contact d'un autre compte ouvert " +
                    "ainsi peut être importé dans $appName. Pour revenir en arrière : Paramètres du téléphone → Applications → " +
                    "$appName → Définir par défaut → Effacer les valeurs par défaut.",
            )
        }
    }
}

private fun ignoredText(ignored: Map<String, Int>): String =
    ignored.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key} (${it.value})" }

private fun plural(n: Int, one: String, many: String) = "$n ${if (n > 1) many else one}"

fun reportText(o: ImportOutcome): String = buildList {
    add(plural(o.created.size, "contact créé", "contacts créés"))
    if (o.updated > 0) add(plural(o.updated, "contact mis à jour", "contacts mis à jour"))
    if (o.unchanged > 0) add(plural(o.unchanged, "déjà à jour", "déjà à jour"))
    if (o.skipped > 0) add(plural(o.skipped, "doublon non importé", "doublons non importés"))
    if (o.bad > 0) add(plural(o.bad, "fiche illisible ignorée", "fiches illisibles ignorées"))
    if (o.empty > 0) add(plural(o.empty, "fiche vide ignorée", "fiches vides ignorées"))
    if (o.ignored.isNotEmpty()) add("Non repris : " + ignoredText(o.ignored))
}.joinToString("\n")

/** Preview, options, progress and report of a vCard import. */
@Composable
fun ContactImportDialogs(c: ContactsController) {
    when (val s = c.importState) {
        null -> Unit
        ContactImportState.Reading -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Lecture du fichier…") },
            text = { LinearProgressIndicator(Modifier.fillMaxWidth()) },
            confirmButton = {},
        )
        is ContactImportState.Preview -> {
            var policy by remember(s) { mutableStateOf(DuplicatePolicy.KEEP_BOTH) }
            val p = s.preview
            AlertDialog(
                onDismissRequest = { c.cancelImport() },
                title = { Text("Importer des contacts") },
                text = {
                    Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        s.fileName?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
                        Text(plural(p.cards, "contact à importer", "contacts à importer"), fontWeight = FontWeight.SemiBold)
                        if (p.duplicates > 0) Text(plural(p.duplicates, "ressemble à un contact déjà présent", "ressemblent à des contacts déjà présents"))
                        if (p.blank > 0) Text(plural(p.blank, "fiche sans nom ni numéro", "fiches sans nom ni numéro"))
                        if (p.bad > 0) Text(plural(p.bad, "fiche illisible (ignorée)", "fiches illisibles (ignorées)"), color = MaterialTheme.colorScheme.error)
                        if (p.empty > 0) Text(plural(p.empty, "fiche vide (ignorée)", "fiches vides (ignorées)"))
                        if (p.ignored.isNotEmpty()) {
                            Text("Non repris : " + ignoredText(p.ignored), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("Doublons", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                        DuplicatePolicy.entries.forEach { option ->
                            Row(Modifier.fillMaxWidth().clickable { policy = option }, verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(policy == option, { policy = option })
                                Text(option.label)
                            }
                        }
                        if (policy == DuplicatePolicy.KEEP_BOTH) SettingsHint("Rien ne peut être perdu ; l'outil Doublons aide à fusionner ensuite.")
                    }
                },
                confirmButton = { TextButton(onClick = { c.confirmImport(policy) }, enabled = p.cards > 0) { Text("Importer") } },
                dismissButton = { TextButton(onClick = { c.cancelImport() }) { Text("Annuler") } },
            )
        }
        is ContactImportState.Running -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Import en cours…") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(progress = { if (s.total > 0) s.done.toFloat() / s.total else 0f }, modifier = Modifier.fillMaxWidth())
                    Text("${s.done} / ${s.total}")
                }
            },
            confirmButton = {},
        )
        is ContactImportState.Report -> AlertDialog(
            onDismissRequest = { c.closeReport() },
            title = { Text("Import terminé") },
            text = { Text(reportText(s.outcome)) },
            confirmButton = { TextButton(onClick = { c.closeReport() }) { Text("OK") } },
            dismissButton = {
                TextButton(onClick = { c.undoImport(s.outcome) }) { Text("Annuler l'import", color = MaterialTheme.colorScheme.error) }
            },
        )
    }
}

/** "236 contacts" in the list's header. */
fun countText(n: Int, searching: Boolean): String =
    if (searching) (if (n <= 1) "$n résultat" else "$n résultats") else ContactList.countLabel(n)
