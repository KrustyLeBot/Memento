package app.memento.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.memento.MainViewModel
import app.memento.NoteFormat
import app.memento.agenda.AgendaController
import app.memento.agenda.AgendaLayout
import app.memento.agenda.AgendaSources
import app.memento.agenda.AgendaText
import app.memento.agenda.Calendar
import app.memento.agenda.CalendarSettings
import app.memento.agenda.DuplicatePolicy
import app.memento.agenda.IcsImportState
import app.memento.agenda.ImportItem
import app.memento.agenda.ImportOptions
import app.memento.agenda.ImportTarget
import app.memento.suite.ConfirmDialog
import app.memento.suite.SectionTitle
import app.memento.suite.SettingsCard
import app.memento.suite.SettingsHint
import app.memento.suite.SuiteTopBar
import java.time.LocalDate
import java.time.ZoneId

private fun dateRange(first: LocalDate?, last: LocalDate?): String? = when {
    first == null || last == null -> null
    first == last -> "le ${AgendaText.shortDate(first)} ${first.year}"
    else -> "du ${AgendaText.shortDate(first)} ${first.year} au ${AgendaText.shortDate(last)} ${last.year}"
}

private fun plural(n: Int, one: String, many: String) = "$n ${if (n > 1) many else one}"

/** One calendar of the file in the preview: where it goes. */
private class TargetChoice(var calendarId: String?, var name: String, var color: String, var skip: Boolean)

/** The import dialog (Part B §5.2): preview per calendar, options, progress, report with undo. */
@Composable
fun IcsImportDialog(c: AgendaController, settings: CalendarSettings) {
    when (val s = c.importState) {
        null -> Unit
        IcsImportState.Reading -> AlertDialog(
            onDismissRequest = {}, title = { Text("Lecture du fichier…") },
            text = { LinearProgressIndicator(Modifier.fillMaxWidth()) }, confirmButton = {},
        )
        is IcsImportState.Running -> AlertDialog(
            onDismissRequest = {}, title = { Text("Import en cours…") },
            text = {
                Column {
                    LinearProgressIndicator(progress = { if (s.total > 0) s.done.toFloat() / s.total else 0f }, modifier = Modifier.fillMaxWidth())
                    Text("${s.done} / ${s.total}", Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = {},
        )
        is IcsImportState.Preview -> ImportPreview(c, s, settings)
        is IcsImportState.Report -> {
            val r = s.report
            var confirmUndo by remember { mutableStateOf(false) }
            AlertDialog(
                onDismissRequest = { c.closeReport() },
                title = { Text("Import terminé") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(r.summary(), fontWeight = FontWeight.SemiBold)
                        r.calendars.forEach { k ->
                            val parts = listOfNotNull(
                                plural(k.created, "créé", "créés"),
                                k.updated.takeIf { it > 0 }?.let { "$it mis à jour" },
                                k.skippedDuplicates.takeIf { it > 0 }?.let { plural(it, "doublon ignoré", "doublons ignorés") },
                                k.beforeCutoff.takeIf { it > 0 }?.let { "$it avant la date choisie" },
                                k.unresolvedZones.takeIf { it > 0 }?.let { plural(it, "fuseau inconnu (heure de l'appareil)", "fuseaux inconnus (heure de l'appareil)") },
                            )
                            Text("${settings.calendar(k.calendarId).name} : ${parts.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
                            k.errors.take(5).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                            if (k.errors.size > 5) Text("… et ${k.errors.size - 5} autres", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { c.closeReport() }) { Text("OK") } },
                dismissButton = { TextButton(onClick = { confirmUndo = true }) { Text("Annuler l'import") } },
            )
            if (confirmUndo) ConfirmDialog("Annuler l'import ?", "Supprime tout ce que cet import a créé.", "Annuler l'import",
                { c.undoImport(r.importId) }, { confirmUndo = false }, destructive = true)
        }
    }
}

/** Each event of a small import: title, when (its zone, and the device's when it differs), place. */
@Composable
private fun ImportItems(items: List<ImportItem>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { item ->
            Column {
                Text(item.title, fontWeight = FontWeight.SemiBold)
                Text(item.whenText, style = MaterialTheme.typography.bodySmall)
                item.location?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

/** "Ajouter cet événement à …": one event of a mail invitation, the target switchable, the full form one tap away. */
@Composable
private fun AddOneEvent(c: AgendaController, s: IcsImportState.Preview, settings: CalendarSettings, item: ImportItem, more: () -> Unit) {
    val pv = s.previews[0]
    var calendarId by remember(s) { mutableStateOf(pv.suggestedCalendar ?: settings.defaultCalendar().id) }
    var menu by remember { mutableStateOf(false) }
    val target = settings.calendar(calendarId)
    AlertDialog(
        onDismissRequest = { c.cancelImport() },
        title = { Text("Ajouter cet événement") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ImportItems(listOf(item))
                Box {
                    Row(Modifier.clickable { menu = true }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        ColorDot(target.color, Modifier.size(12.dp))
                        Text("Agenda : ${target.name}", Modifier.padding(start = 8.dp), color = MaterialTheme.colorScheme.primary)
                    }
                    DropdownMenu(menu, { menu = false }) {
                        settings.calendars.forEach { k ->
                            DropdownMenuItem({ Text(k.name) }, { calendarId = k.id; menu = false }, leadingIcon = { ColorDot(k.color) })
                        }
                        DropdownMenuItem({ Text("Nouvel agenda, autres options…") }, { menu = false; more() })
                    }
                }
                if (calendarId == pv.suggestedCalendar && pv.duplicates > 0) {
                    Text("Déjà présent dans cet agenda : il sera mis à jour.", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = more) { Text("Plus d'options…") }
            }
        },
        confirmButton = {
            TextButton(onClick = { c.confirmImport(ImportOptions(mapOf(0 to ImportTarget(calendarId = calendarId)))) }) {
                Text("Ajouter à « ${target.name} »")
            }
        },
        dismissButton = { TextButton(onClick = { c.cancelImport() }) { Text("Annuler") } },
    )
}

@Composable
private fun ImportPreview(c: AgendaController, s: IcsImportState.Preview, settings: CalendarSettings) {
    val single = s.previews.singleOrNull()?.takeIf { !it.calendarExport }?.items?.singleOrNull()
    var full by remember(s) { mutableStateOf(single == null) }
    if (!full && single != null) return AddOneEvent(c, s, settings, single) { full = true }
    val choices = remember(s) {
        var taken = settings
        mutableStateListOf<TargetChoice>().apply {
            s.parsed.forEachIndexed { i, p ->
                val color = CalendarSettings.nextColor(taken)
                taken = taken.copy(calendars = taken.calendars + Calendar("tmp$i", p.name, color))
                add(TargetChoice(s.previews[i].suggestedCalendar, p.name, color, false))
            }
        }
    }
    var version by remember { mutableStateOf(0) } // TargetChoice is mutable: bump to redraw
    var since by remember { mutableStateOf<LocalDate?>(null) }
    var pickSince by remember { mutableStateOf(false) }
    var policy by remember { mutableStateOf(DuplicatePolicy.UPDATE) }
    AlertDialog(
        onDismissRequest = { c.cancelImport() },
        title = { Text("Importer") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                @Suppress("UNUSED_EXPRESSION") version
                s.previews.forEachIndexed { i, pv ->
                    val ch = choices[i]
                    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(!ch.skip, { ch.skip = !it; version++ })
                                Text(pv.source, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            val counts = listOfNotNull(
                                plural(pv.events, "événement", "événements"),
                                pv.series.takeIf { it > 0 }?.let { plural(it, "série", "séries") },
                                pv.exceptions.takeIf { it > 0 }?.let { plural(it, "occurrence modifiée", "occurrences modifiées") },
                                dateRange(pv.first, pv.last),
                                (pv.ignored + pv.cancelled + pv.errors.size).takeIf { it > 0 }?.let { plural(it, "ignoré", "ignorés") },
                                pv.unresolvedZones.takeIf { it > 0 }?.let { plural(it, "fuseau inconnu", "fuseaux inconnus") },
                            )
                            Text(counts.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                            pv.items?.let { ImportItems(it) }
                            if (!ch.skip) {
                                var menu by remember { mutableStateOf(false) }
                                Box {
                                    val target = ch.calendarId?.let { id -> settings.calendars.firstOrNull { it.id == id } }
                                    Row(Modifier.clickable { menu = true }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                        ColorDot(target?.color ?: ch.color, Modifier.size(12.dp))
                                        Text("Vers : " + (target?.name ?: "nouvel agenda"), Modifier.padding(start = 8.dp), color = MaterialTheme.colorScheme.primary)
                                    }
                                    DropdownMenu(menu, { menu = false }) {
                                        DropdownMenuItem({ Text("Nouvel agenda") }, { ch.calendarId = null; menu = false; version++ })
                                        settings.calendars.forEach { k ->
                                            DropdownMenuItem({ Text(k.name) }, { ch.calendarId = k.id; menu = false; version++ }, leadingIcon = { ColorDot(k.color) })
                                        }
                                    }
                                }
                                if (ch.calendarId == null) {
                                    OutlinedTextField(ch.name, { ch.name = it; version++ }, label = { Text("Nom de l'agenda") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                    PalettePicker(ch.color) { ch.color = it; version++ }
                                } else if (ch.calendarId == pv.suggestedCalendar && pv.duplicates > 0) {
                                    Text(plural(pv.duplicates, "événement déjà présent", "événements déjà présents"), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(since != null, { on -> if (on) pickSince = true else since = null })
                    Text(since?.let { "Seulement depuis le ${AgendaText.shortDate(it)} ${it.year}" } ?: "Tout importer (ou depuis le …)",
                        Modifier.clickable { pickSince = true })
                }
                Text("Événements déjà présents (même identifiant)", fontWeight = FontWeight.SemiBold)
                listOf(DuplicatePolicy.UPDATE to "Mettre à jour", DuplicatePolicy.SKIP to "Ignorer", DuplicatePolicy.KEEP to "Garder les deux").forEach { (p, label) ->
                    Row(Modifier.fillMaxWidth().clickable { policy = p }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(policy == p, { policy = p })
                        Text(label)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = choices.any { !it.skip }, onClick = {
                val targets = choices.mapIndexed { i, ch ->
                    i to ImportTarget(calendarId = ch.calendarId, name = ch.name.trim().ifEmpty { null }, color = ch.color, skip = ch.skip)
                }.toMap()
                c.confirmImport(ImportOptions(targets, since, policy))
            }) { Text("Importer") }
        },
        dismissButton = { TextButton(onClick = { c.cancelImport() }) { Text("Annuler") } },
    )
    if (pickSince) AgendaDatePicker(since ?: LocalDate.now().minusYears(1), { pickSince = false }) { since = it }
}

/** "Exporter en .ics": one calendar, or all of them as a `.zip`; optionally the activities and dated notes. */
@Composable
fun ExportDialog(c: AgendaController, settings: CalendarSettings, onDismiss: () -> Unit) {
    var choice by remember { mutableStateOf<String?>(null) }
    var layers by remember { mutableStateOf(c.prefs.exportWithLayers) }
    val ics = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/calendar")) { uri -> if (uri != null) c.export(uri, choice, layers) }
    val zip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> if (uri != null) c.export(uri, null, layers) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Exporter") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth().clickable { choice = null }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(choice == null, { choice = null }); Text("Tous les agendas (.zip)")
                }
                settings.calendars.forEach { k ->
                    Row(Modifier.fillMaxWidth().clickable { choice = k.id }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(choice == k.id, { choice = k.id }); ColorDot(k.color); Text(k.name + " (.ics)", Modifier.padding(start = 8.dp))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(layers, { layers = it }); Text("Inclure les activités et les notes datées")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                val id = choice
                if (id == null) zip.launch("agenda-${LocalDate.now()}.zip")
                else ics.launch("${NoteFormat.slug(settings.calendar(id).name)}.ics")
            }) { Text("Exporter") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

/** The imports whose files are still there, each with "Annuler l'import". */
@Composable
fun PastImportsDialog(c: AgendaController, sources: AgendaSources, onDismiss: () -> Unit) {
    val list = remember(sources) { AgendaLayout.pastImports(sources.events, sources.settings) }
    var undo by remember { mutableStateOf<AgendaLayout.PastImport?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Imports passés") },
        text = {
            if (list.isEmpty()) Text("Aucun import dans le dossier.")
            else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list, key = { it.id }) { p ->
                    Column(Modifier.fillMaxWidth()) {
                        val at = p.at?.atZone(ZoneId.systemDefault())
                        Text(at?.let { "${AgendaText.shortDate(it.toLocalDate())} ${it.year} à ${AgendaText.time(it.toLocalTime(), true)}" } ?: p.id, fontWeight = FontWeight.SemiBold)
                        Text("${plural(p.events, "fichier", "fichiers")} · ${p.calendars.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { undo = p }) { Text("Annuler l'import") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
    )
    undo?.let { p ->
        ConfirmDialog("Annuler cet import ?", "Supprime les ${plural(p.events, "fichier", "fichiers")} qu'il a créés.", "Annuler l'import",
            { onDismiss(); c.undoImport(p.id) }, { undo = null }, destructive = true)
    }
}

/** "Agendas": visibility, name, colour, default, delete; and the layers (activities, notes, birthdays). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarsScreen(vm: MainViewModel) {
    val c = vm.agenda
    val settings by vm.repo.calendars.collectAsState()
    val agenda by vm.repo.agenda.collectAsState()
    val snackbar = rememberVmSnackbar(vm)
    var editing by remember { mutableStateOf<Calendar?>(null) }
    var creating by remember { mutableStateOf(false) }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { SuiteTopBar("Agendas", onBack = { vm.back() }) },
        floatingActionButton = { FloatingActionButton(onClick = { creating = true }) { Icon(Icons.Default.Add, "Nouvel agenda") } },
    ) { p ->
        LazyColumn(Modifier.padding(p).fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp)) {
            settings.problem?.let { msg -> item { SettingsHint(msg, MaterialTheme.colorScheme.error) } }
            item { SectionTitle("Agendas") }
            items(settings.calendars, key = { it.id }) { k ->
                Row(Modifier.fillMaxWidth().clickable { editing = k }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    ColorDot(k.color, Modifier.size(18.dp))
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(k.name)
                        val n = settings.eventsOf(k.id, agenda.events).size
                        SettingsHint(plural(n, "événement", "événements") + if (k.id == settings.defaultCalendar().id) " · par défaut" else "")
                    }
                    Switch(k.visible, { v -> c.updateCalendars { it.withCalendar(k.id) { cal -> cal.copy(visible = v) } } })
                }
            }
            item { SectionTitle("Calques") }
            listOf(
                CalendarSettings.LAYER_ACTIVITIES to "Activités", CalendarSettings.LAYER_NOTES to "Notes datées", CalendarSettings.LAYER_BIRTHDAYS to "Anniversaires",
            ).forEach { (key, label) ->
                item(key = "layer:$key") {
                    var colors by remember { mutableStateOf(false) }
                    val layer = settings.layer(key)
                    Column {
                        Row(Modifier.fillMaxWidth().clickable { colors = !colors }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            ColorDot(layer.color, Modifier.size(18.dp))
                            Text(label, Modifier.weight(1f).padding(start = 14.dp))
                            Switch(layer.visible, { v -> c.updateCalendars { it.withLayer(key) { l -> l.copy(visible = v) } } })
                        }
                        if (colors) PalettePicker(layer.color) { hex -> c.updateCalendars { it.withLayer(key) { l -> l.copy(color = hex) } } }
                    }
                }
            }
            item { SettingsHint("Masquer un agenda ou un calque ne fait que le cacher des vues : ses rappels sonnent toujours. Réglages synchronisés avec le PC.") }
        }
    }
    editing?.let { k ->
        CalendarEditDialog(
            k, isDefault = k.id == settings.defaultCalendar().id, events = settings.eventsOf(k.id, agenda.events).size,
            canDelete = settings.calendars.size > 1, onDismiss = { editing = null },
            onSave = { name, color, makeDefault ->
                c.updateCalendars { s ->
                    s.withCalendar(k.id) { it.copy(name = name.trim().ifEmpty { it.name }, color = color) }.let { if (makeDefault) it.copy(default = k.id) else it }
                }
            },
            onDelete = { c.deleteCalendar(k.id) },
        )
    }
    if (creating) {
        CalendarEditDialog(null, false, 0, false, { creating = false }, onSave = { name, color, makeDefault ->
            if (name.isNotBlank()) c.updateCalendars { s ->
                val (next, cal) = s.addCalendar(name, color)
                if (makeDefault) next.copy(default = cal.id) else next
            }
        }, onDelete = {})
    }
}

@Composable
private fun CalendarEditDialog(
    cal: Calendar?, isDefault: Boolean, events: Int, canDelete: Boolean, onDismiss: () -> Unit,
    onSave: (String, String, Boolean) -> Unit, onDelete: () -> Unit,
) {
    val settingsColor = cal?.color ?: CalendarSettings.PALETTE[0].first
    var name by remember { mutableStateOf(cal?.name.orEmpty()) }
    var color by remember { mutableStateOf(settingsColor) }
    var makeDefault by remember { mutableStateOf(isDefault) }
    var confirm by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (cal == null) "Nouvel agenda" else "Agenda") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Nom") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                PalettePicker(color) { color = it }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(enabled = !isDefault) { makeDefault = !makeDefault }) {
                    Checkbox(makeDefault, { if (!isDefault) makeDefault = it }, enabled = !isDefault)
                    Icon(Icons.Default.Star, null, Modifier.size(16.dp))
                    Text(" Agenda par défaut des nouveaux événements")
                }
                if (cal != null && canDelete) {
                    OutlinedButton(onClick = { confirm = true }) { Text("Supprimer l'agenda", color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDismiss(); onSave(name, color, makeDefault) }, enabled = name.isNotBlank()) { Text("Enregistrer") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
    if (confirm && cal != null) ConfirmDialog(
        "Supprimer « ${cal.name} » ?",
        if (events > 0) "Ses ${plural(events, "événement", "événements")} seront supprimés aussi (annulable juste après)." else "Il ne contient aucun événement.",
        "Supprimer", { onDismiss(); onDelete() }, { confirm = false }, destructive = true,
    )
}
