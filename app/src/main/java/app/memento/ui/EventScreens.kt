package app.memento.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Event
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.memento.MainViewModel
import app.memento.fold
import app.memento.agenda.AgendaLayout
import app.memento.agenda.AgendaText
import app.memento.agenda.EditMode
import app.memento.agenda.Entry
import app.memento.agenda.Links
import app.memento.agenda.Event
import app.memento.agenda.EventEditor
import app.memento.agenda.Freq
import app.memento.agenda.MonthlyMode
import app.memento.agenda.RepeatPreset
import app.memento.agenda.RuleEditor
import app.memento.agenda.RuleSpec
import app.memento.agenda.SeriesScope
import app.memento.agenda.Zones
import app.memento.suite.ConfirmDialog
import app.memento.suite.SuiteTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// ---- detail ----

@Composable
private fun InfoRow(icon: ImageVector, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 16.dp).size(22.dp))
        Column(Modifier.weight(1f)) { content() }
    }
}

/** The scope question of a series: "cet événement" / "tous les événements". */
@Composable
private fun ScopeDialog(title: String, onDismiss: () -> Unit, onPick: (SeriesScope) -> Unit) {
    var scope by remember { mutableStateOf(SeriesScope.THIS) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                listOf(SeriesScope.THIS to "Cet événement", SeriesScope.ALL to "Tous les événements").forEach { (s, label) ->
                    Row(Modifier.fillMaxWidth().clickable { scope = s }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(scope == s, { scope = s })
                        Text(label)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDismiss(); onPick(scope) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

/** Detail of an event occurrence: when, where, repetition, reminders, the description (the note). */
@OptIn(ExperimentalMaterial3Api::class)
/** Opens [url] in the browser (ACTION_VIEW), a snackbar when nothing can. */
private fun openLink(context: android.content.Context, vm: MainViewModel, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: android.content.ActivityNotFoundException) {
        vm.say(app.memento.Snack("Aucune application pour ouvrir ce lien"))
    }
}

/** [text] with its links ([Links.spans]) clickable. */
private fun linkified(text: String, color: Color, open: (String) -> Unit): AnnotatedString = buildAnnotatedString {
    var at = 0
    for ((range, url) in Links.spans(text)) {
        append(text.substring(at, range.first))
        withLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = color, textDecoration = TextDecoration.Underline))) { open(url) }) {
            append(text.substring(range.first, range.last + 1))
        }
        at = range.last + 1
    }
    append(text.substring(at))
}

@Composable
fun EventDetailScreen(vm: MainViewModel) {
    val c = vm.agenda
    val context = LocalContext.current
    val sources by c.sources.collectAsState()
    val zone = ZoneId.systemDefault()
    val h24 = is24h()
    val today = rememberToday()
    val id = c.detailId
    val entry by produceState<Entry?>(null, sources, id, c.detailAt) {
        value = if (id == null) null else withContext(Dispatchers.Default) { AgendaLayout.entryFor(sources, id, c.detailAt, Instant.now(), zone) }
    }
    val event = sources.events.firstOrNull { it.id == id }
    var menu by remember { mutableStateOf(false) }
    var askEdit by remember { mutableStateOf(false) }
    var askDelete by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val e = entry

    Scaffold(
        topBar = {
            SuiteTopBar("", onBack = { c.closeDetail() }) {
                if (e != null && event != null) {
                    IconButton(onClick = { if (event.isSeries) askEdit = true else c.edit(e, SeriesScope.ALL) }) { Icon(Icons.Default.Edit, "Modifier") }
                    IconButton(onClick = { if (event.isSeries) askDelete = true else confirmDelete = true }) { Icon(Icons.Default.Delete, "Supprimer") }
                    if (event.isException) Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Menu") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem({ Text("Modifier toute la série") }, { menu = false; c.editSeriesOf(event) })
                            DropdownMenuItem({ Text("Rétablir l'occurrence d'origine") }, { menu = false; c.restoreOccurrence(event) })
                        }
                    }
                }
            }
        },
    ) { p ->
        if (event == null || e == null) {
            Text(if (event == null && sources.events.isNotEmpty()) "Événement introuvable (supprimé ?)" else "Chargement…",
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(p).padding(24.dp))
            return@Scaffold
        }
        val cal = sources.settings.calendar(event.calendar)
        Column(Modifier.padding(p).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 8.dp, end = 16.dp).size(22.dp).clip(RoundedCornerShape(6.dp)).background(agendaColor(cal.color)))
                Text(event.title.ifBlank { "(Sans titre)" }, style = MaterialTheme.typography.headlineSmall)
            }
            Text(cal.name, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 38.dp, top = 2.dp, bottom = 8.dp))
            InfoRow(Icons.Default.Schedule) {
                val (line1, line2) = AgendaText.detailWhen(e, zone, h24, c.eventZoneTimes)
                Text(line1.replaceFirstChar { it.uppercase() })
                line2?.let { Text(it) }
                val tz = event.tz
                val o = e.occurrence
                val flight = o?.let { AgendaText.departureArrival(it, h24) }
                if (flight != null) {
                    Text(flight, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    if (c.eventZoneTimes) Text("Heure de l'appareil : ${AgendaText.timeLine(e, zone, h24)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                } else if (!event.allDay && tz != null && Zones.isValid(tz) && Zones.zone(tz).rules != zone.rules && o != null) {
                    val wall = AgendaText.time(o.start.time!!, h24) + (o.end?.time?.let { " – " + AgendaText.time(it, h24) } ?: "")
                    Text(if (c.eventZoneTimes) "Heure de l'appareil : ${AgendaText.timeLine(e, zone, h24)}" else "$wall à ${AgendaText.city(tz)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (event.isSeries) InfoRow(Icons.Default.Repeat) {
                Text(RuleEditor.summary(event.rrule, event.start, event.tz, zone))
                Text(RuleEditor.nextText(event, Instant.now(), zone, today), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (event.isException) InfoRow(Icons.Default.Repeat) {
                Text("Occurrence modifiée d'une série", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            event.location?.let { place ->
                InfoRow(Icons.Default.Place, Modifier.clickable {
                    val geo = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(place)))
                    try {
                        context.startActivity(geo)
                    } catch (_: android.content.ActivityNotFoundException) {
                        vm.say(app.memento.Snack("Aucune application de cartes"))
                    }
                }) { Text(place, color = MaterialTheme.colorScheme.primary) }
            }
            if (event.alerts.isNotEmpty()) InfoRow(Icons.Default.Notifications) {
                event.alerts.sorted().forEach { Text(AgendaText.alertLabel(it, event.allDay, h24)) }
            }
            InfoRow(Icons.AutoMirrored.Filled.Notes) {
                Surface(
                    shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { c.editDescription(event) },
                ) {
                    Column(Modifier.padding(12.dp)) {
                        val text = event.note.plainBody().trim()
                        if (text.isEmpty()) Text("Ajouter une description, une liste, des photos…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else {
                            val linkColor = MaterialTheme.colorScheme.primary
                            Text(remember(text, linkColor) { linkified(text, linkColor) { openLink(context, vm, it) } }, color = MaterialTheme.colorScheme.onSurface)
                        }
                        val files = event.note.attachments.size + (if (event.note.pdf != null) 1 else 0)
                        if (files > 0) Text("$files pièce${if (files > 1) "s" else ""} jointe${if (files > 1) "s" else ""}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (event.isSeries) Text("La description est commune à toute la série.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
            val links = remember(event.body) { Links.extract(event.body) }
            if (links.isNotEmpty()) InfoRow(Icons.Default.Link) {
                Text("Liens", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                links.forEach { link ->
                    Text(link.label, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().clickable { openLink(context, vm, link.url) }.padding(vertical = 6.dp))
                }
            }
            if (event.importId != null || event.warnings.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                if (event.importId != null) Text("Importé d'un fichier .ics", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                event.warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
    if (askEdit && e != null) ScopeDialog("Modifier l'événement récurrent", { askEdit = false }) { c.edit(e, it) }
    if (askDelete && e != null) ScopeDialog("Supprimer l'événement récurrent", { askDelete = false }) { c.delete(e, it) }
    if (confirmDelete && e != null) ConfirmDialog("Supprimer l'événement ?", e.title, "Supprimer", { c.delete(e, SeriesScope.ALL) }, { confirmDelete = false }, destructive = true)
}

// ---- editor ----

@Composable
private fun FieldRow(icon: ImageVector?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(modifier.fillMaxWidth().heightIn(min = 52.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(40.dp)) { if (icon != null) Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        Box(Modifier.weight(1f)) { content() }
    }
}

/** The event editor (Part B §4.2). The description is the note: "Description" saves, then opens the note editor. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventEditorScreen(vm: MainViewModel, ed: EventEditor) {
    val c = vm.agenda
    val sources by c.sources.collectAsState()
    val zone = ZoneId.systemDefault()
    val h24 = is24h()
    val today = rememberToday()
    val d = ed.draft
    var pickDate by remember { mutableStateOf<Boolean?>(null) } // true = start, false = end
    var pickTime by remember { mutableStateOf<Boolean?>(null) }
    var showZone by remember { mutableStateOf(!d.allDay && d.tz != null && d.tz != zone.id) }
    var zoneDialog by remember { mutableStateOf(false) }
    var endZoneDialog by remember { mutableStateOf(false) }
    var calMenu by remember { mutableStateOf(false) }
    var repeatDialog by remember { mutableStateOf(false) }
    var customDialog by remember { mutableStateOf(false) }
    var alertDialog by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    val close = { if (ed.changed) discard = true else c.cancelEditor() }
    BackHandler { close() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = close) { Icon(Icons.Default.Close, "Fermer") } },
                title = { Text(when (ed.mode) { EditMode.New -> "Nouvel événement"; is EditMode.Occurrence -> "Cet événement"; else -> "Modifier" }) },
                actions = { TextButton(onClick = { c.saveEditor() }) { Text("Enregistrer", fontWeight = FontWeight.SemiBold) } },
            )
        },
    ) { p ->
        Column(Modifier.padding(p).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            TextField(
                d.title, { ed.draft = d.copy(title = it.replace("\n", " ")) }, placeholder = { Text("Titre", style = MaterialTheme.typography.headlineSmall) },
                textStyle = MaterialTheme.typography.headlineSmall, singleLine = true,
                colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent),
                modifier = Modifier.fillMaxWidth().padding(start = 28.dp),
            )
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            if (ed.mode == EditMode.File && ed.base.isSeries) {
                Text("Toute la série : le début est celui de la première occurrence.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 40.dp, top = 4.dp))
            }
            FieldRow(Icons.Default.Schedule) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Toute la journée", Modifier.weight(1f))
                    Switch(d.allDay, { v ->
                        val settings = sources.settings
                        val alerts = if (ed.mode == EditMode.New && d.alerts == settings.defaultAlerts(d.allDay)) settings.defaultAlerts(v) else d.alerts
                        ed.draft = d.copy(allDay = v, alerts = alerts, endDate = if (v && d.endDate.isBefore(d.startDate)) d.startDate else d.endDate)
                    })
                }
            }
            listOf(true, false).forEach { isStart ->
                FieldRow(null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val date = if (isStart) d.startDate else d.endDate
                        val time = if (isStart) d.startTime else d.endTime
                        Text(if (isStart) "Début" else "Fin", Modifier.width(56.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${AgendaText.weekdayShort(date.dayOfWeek)} ${AgendaText.shortDate(date, today.year)}",
                            Modifier.weight(1f).clickable { pickDate = isStart }.padding(vertical = 10.dp))
                        if (!d.allDay) Text(AgendaText.time(time, h24), Modifier.clickable { pickTime = isStart }.padding(10.dp))
                    }
                }
            }
            d.problem()?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 40.dp)) }
            if (!d.allDay) {
                val endTz = d.endTz
                if (showZone || endTz != null) FieldRow(Icons.Default.Public, Modifier.clickable { zoneDialog = true }) {
                    Column {
                        Text(d.tz?.let { zoneLabel(it) } ?: "Celui de l'appareil (heure flottante)")
                        Text(if (endTz != null) "Fuseau de départ" else "Fuseau horaire", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else Text("Fuseau horaire…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 40.dp, bottom = 4.dp).clickable { showZone = true; zoneDialog = true })
                // a flight: the end is a wall time of its own zone (end_tz), which needs a start zone too
                Row(Modifier.padding(start = 28.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(endTz != null, { on ->
                        ed.draft = if (on) d.copy(tz = d.tz ?: zone.id, endTz = d.tz ?: zone.id) else d.copy(endTz = null)
                        if (on) endZoneDialog = true
                    })
                    Text("Fuseau d'arrivée différent", style = MaterialTheme.typography.bodyMedium)
                }
                if (endTz != null) FieldRow(Icons.Default.Public, Modifier.clickable { endZoneDialog = true }) {
                    Column {
                        Text(zoneLabel(endTz))
                        Text("Fuseau d'arrivée (heure de fin)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            val cal = sources.settings.calendar(d.calendar)
            FieldRow(Icons.Default.Event, Modifier.clickable { calMenu = true }) {
                Box {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ColorDot(cal.color, Modifier.size(14.dp))
                        Text(cal.name, Modifier.padding(start = 10.dp))
                    }
                    DropdownMenu(calMenu, { calMenu = false }) {
                        sources.settings.calendars.forEach { k ->
                            DropdownMenuItem({ Text(k.name) }, { calMenu = false; ed.draft = d.copy(calendar = k.id) }, leadingIcon = { ColorDot(k.color, Modifier.size(14.dp)) })
                        }
                    }
                }
            }
            FieldRow(Icons.Default.Place) {
                OutlinedTextField(d.location, { ed.draft = d.copy(location = it.replace("\n", " ")) }, placeholder = { Text("Lieu") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
            }
            if (ed.mode !is EditMode.Occurrence) {
                FieldRow(Icons.Default.Repeat, Modifier.clickable { repeatDialog = true }) {
                    Column {
                        Text(RuleEditor.summary(d.rrule, d.start, d.tz, zone))
                        if (RuleEditor.detect(d.rrule, d.start, d.tz, zone) == RepeatPreset.IMPORTED) {
                            Text("Règle importée, conservée telle quelle", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (d.rrule != null) {
                            val preview by produceState("", d, ed.base) {
                                value = withContext(Dispatchers.Default) { RuleEditor.nextText(d.applyTo(ed.base), Instant.now(), zone, today) }
                            }
                            Text(preview, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            FieldRow(Icons.Default.Notifications) {
                Column {
                    d.alerts.sorted().forEach { a ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(AgendaText.alertLabel(a, d.allDay, h24), Modifier.weight(1f))
                            IconButton(onClick = { ed.draft = d.copy(alerts = d.alerts - a) }) { Icon(Icons.Default.Close, "Retirer le rappel") }
                        }
                    }
                    Text("Ajouter un rappel", color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { alertDialog = true }.padding(vertical = 10.dp))
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            FieldRow(Icons.AutoMirrored.Filled.Notes, Modifier.clickable { c.saveEditor(thenDescription = true) }) {
                val body = ed.base.note.plainBody().trim()
                Column {
                    Text(body.ifEmpty { "Description" }, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        color = if (body.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                    Text("Enregistre puis ouvre la note de l'événement (listes, photos, dessins)", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(48.dp))
        }
    }

    pickDate?.let { isStart ->
        AgendaDatePicker(if (isStart) d.startDate else d.endDate, { pickDate = null }) { v ->
            ed.draft = if (isStart) d.withStart(v).let { it.copy(rrule = RuleEditor.followStart(d.rrule, d.start, it.start, d.tz, zone)) }
            else d.copy(endDate = v, noEnd = false)
        }
    }
    pickTime?.let { isStart ->
        AgendaTimePicker(if (isStart) d.startTime else d.endTime, { pickTime = null }) { v ->
            ed.draft = if (isStart) d.withStart(d.startDate, v) else d.copy(endTime = v, noEnd = false)
        }
    }
    if (zoneDialog) ZoneDialog(d.tz, { zoneDialog = false }, floating = d.endTz == null) { ed.draft = d.copy(tz = it) }
    if (endZoneDialog) ZoneDialog(d.endTz, { endZoneDialog = false }, title = "Fuseau d'arrivée", floating = false) { ed.draft = d.copy(endTz = it) }
    if (repeatDialog) RepeatDialog(d.startDate, RuleEditor.detect(d.rrule, d.start, d.tz, zone), { repeatDialog = false }) { preset ->
        when (preset) {
            RepeatPreset.CUSTOM -> customDialog = true
            RepeatPreset.NONE -> ed.draft = d.copy(rrule = null)
            else -> ed.draft = d.copy(rrule = RuleEditor.build(RuleEditor.presetSpec(preset, d.startDate)!!, d.start, d.tz, zone))
        }
    }
    if (customDialog) {
        val initial = d.rrule?.let { RuleEditor.parse(it, d.start, d.tz, zone) } ?: RuleEditor.defaults(Freq.WEEKLY, d.startDate)
        CustomRuleDialog(initial, d.startDate, c.weekStart, { customDialog = false }, preview = { spec ->
            RuleEditor.nextText(d.copy(rrule = RuleEditor.build(spec, d.start, d.tz, zone)).applyTo(ed.base), Instant.now(), zone, today)
        }) { spec -> ed.draft = d.copy(rrule = RuleEditor.build(spec, d.start, d.tz, zone)) }
    }
    if (alertDialog) AlertPickerDialog(d.allDay, h24, { alertDialog = false }) { a -> if (a !in d.alerts) ed.draft = d.copy(alerts = d.alerts + a) }
    if (discard) ConfirmDialog("Abandonner les modifications ?", null, "Abandonner", { c.cancelEditor() }, { discard = false }, destructive = true)
}

/** "Paris (UTC+01:00)". */
private fun zoneLabel(id: String): String {
    if (!Zones.isValid(id)) return "$id (inconnu)"
    val offset = Zones.zone(id).rules.getOffset(Instant.now()).id.let { if (it == "Z") "+00:00" else it }
    return "${AgendaText.city(id)} (UTC$offset) · $id"
}

@Composable
private fun ZoneDialog(
    current: String?, onDismiss: () -> Unit, title: String = "Fuseau horaire", floating: Boolean = true, onPick: (String?) -> Unit,
) {
    var q by remember { mutableStateOf("") }
    val device = ZoneId.systemDefault().id
    val all = remember { java.time.ZoneId.getAvailableZoneIds().filter { '/' in it && !it.startsWith("Etc/") && !it.startsWith("SystemV/") }.sorted() }
    val shown = remember(q) {
        val f = q.trim().fold()
        all.filter { f.isBlank() || it.fold().contains(f) || AgendaText.city(it).fold().contains(f) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(q, { q = it }, placeholder = { Text("Rechercher (ville, région)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    if (q.isBlank()) {
                        if (floating) item { ZoneItem("Celui de l'appareil (heure flottante)", current == null) { onPick(null); onDismiss() } }
                        item { ZoneItem("Ici : ${zoneLabel(device)}", current == device) { onPick(device); onDismiss() } }
                    }
                    items(shown) { z -> ZoneItem(zoneLabel(z), current == z) { onPick(z); onDismiss() } }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

@Composable
private fun ZoneItem(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(label, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp))
}

@Composable
private fun RepeatDialog(start: LocalDate, current: RepeatPreset, onDismiss: () -> Unit, onPick: (RepeatPreset) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Répétition") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                RuleEditor.MENU.forEach { p ->
                    Row(Modifier.fillMaxWidth().clickable { onDismiss(); onPick(p) }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(p == current || (p == RepeatPreset.CUSTOM && current == RepeatPreset.IMPORTED), { onDismiss(); onPick(p) })
                        Text(RuleEditor.presetLabel(p, start))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

/** "Personnalisé…": every N days/weeks/months/years, weekdays, day of month or n-th weekday, end. */
@Composable
private fun CustomRuleDialog(
    initial: RuleSpec, start: LocalDate, weekStart: java.time.DayOfWeek, onDismiss: () -> Unit,
    preview: (RuleSpec) -> String, onDone: (RuleSpec) -> Unit,
) {
    var s by remember { mutableStateOf(initial) }
    var pickUntil by remember { mutableStateOf(false) }
    var pickYearly by remember { mutableStateOf(false) }
    val text by produceState("", s) { value = withContext(Dispatchers.Default) { preview(s) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Répétition personnalisée") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Tous les ")
                    OutlinedTextField(s.interval.toString(), { v -> v.filter { it.isDigit() }.take(3).toIntOrNull()?.let { s = s.copy(interval = maxOf(1, it)) } },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(72.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(Freq.DAILY to "jours", Freq.WEEKLY to "semaines", Freq.MONTHLY to "mois", Freq.YEARLY to "ans").forEach { (f, label) ->
                        FilterChip(s.freq == f, { s = RuleEditor.defaults(f, start).copy(interval = s.interval, until = s.until, count = s.count) }, { Text(label) })
                    }
                }
                when (s.freq) {
                    Freq.WEEKLY -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        AgendaLayout.weekdayOrder(weekStart).forEach { wd ->
                            val on = wd in s.weekdays
                            Box(
                                Modifier.size(34.dp).clip(RoundedCornerShape(17.dp))
                                    .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh)
                                    .clickable {
                                        val next = if (on) s.weekdays - wd else s.weekdays + wd
                                        if (next.isNotEmpty()) s = s.copy(weekdays = next)
                                    },
                                contentAlignment = Alignment.Center,
                            ) { Text(AgendaText.WEEKDAYS_LETTER[wd.value - 1], color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface) }
                        }
                    }
                    Freq.MONTHLY -> Column {
                        val nth = RuleEditor.nthOf(start)
                        val options = buildList {
                            add(RuleSpec(Freq.MONTHLY, monthlyMode = MonthlyMode.DAY_OF_MONTH, monthDay = start.dayOfMonth) to "Le ${if (start.dayOfMonth == 1) "1er" else start.dayOfMonth} du mois")
                            add(RuleSpec(Freq.MONTHLY, monthlyMode = MonthlyMode.NTH_WEEKDAY, nth = nth, nthWeekday = start.dayOfWeek) to
                                "Le ${if (nth == -1) "dernier" else if (nth == 1) "1er" else "${nth}e"} ${AgendaText.weekday(start.dayOfWeek)}")
                            if (nth == 4 && start.plusWeeks(1).monthValue != start.monthValue) {
                                add(RuleSpec(Freq.MONTHLY, monthlyMode = MonthlyMode.NTH_WEEKDAY, nth = -1, nthWeekday = start.dayOfWeek) to "Le dernier ${AgendaText.weekday(start.dayOfWeek)}")
                            }
                            if (start.plusDays(1).monthValue != start.monthValue) {
                                add(RuleSpec(Freq.MONTHLY, monthlyMode = MonthlyMode.DAY_OF_MONTH, monthDay = -1) to "Le dernier jour du mois")
                            }
                        }
                        options.forEach { (o, label) ->
                            val on = s.monthlyMode == o.monthlyMode && (if (o.monthlyMode == MonthlyMode.DAY_OF_MONTH) s.monthDay == o.monthDay else s.nth == o.nth && s.nthWeekday == o.nthWeekday)
                            Row(Modifier.fillMaxWidth().clickable { s = s.copy(monthlyMode = o.monthlyMode, monthDay = o.monthDay, nth = o.nth, nthWeekday = o.nthWeekday) },
                                verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(on, { s = s.copy(monthlyMode = o.monthlyMode, monthDay = o.monthDay, nth = o.nth, nthWeekday = o.nthWeekday) })
                                Text(label)
                            }
                        }
                        if (options.none { (o, _) -> s.monthlyMode == o.monthlyMode && (if (o.monthlyMode == MonthlyMode.DAY_OF_MONTH) s.monthDay == o.monthDay else s.nth == o.nth && s.nthWeekday == o.nthWeekday) }) {
                            Text(RuleEditor.describe(s), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Freq.YEARLY -> Text("Le ${if (s.yearDay == 1) "1er" else s.yearDay} ${AgendaText.MONTHS[s.yearMonth - 1]} (modifier)",
                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { pickYearly = true })
                    Freq.DAILY -> Unit
                }
                HorizontalDivider()
                Text("Fin", fontWeight = FontWeight.SemiBold)
                Row(Modifier.fillMaxWidth().clickable { s = s.copy(until = null, count = null) }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(s.until == null && s.count == null, { s = s.copy(until = null, count = null) }); Text("Jamais")
                }
                Row(Modifier.fillMaxWidth().clickable { pickUntil = true }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(s.until != null && s.count == null, { pickUntil = true })
                    Text("Jusqu'au " + (s.until?.let { AgendaText.shortDate(it) + " " + it.year } ?: "…"))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(s.count != null, { s = s.copy(count = s.count ?: 10, until = null) })
                    Text("Après ")
                    OutlinedTextField((s.count ?: 10).toString(), { v -> v.filter { it.isDigit() }.take(4).toIntOrNull()?.let { s = s.copy(count = maxOf(1, it), until = null) } },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(80.dp))
                    Text(" fois")
                }
                Text(RuleEditor.describe(s), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { onDismiss(); onDone(s) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
    if (pickUntil) AgendaDatePicker(s.until ?: start.plusMonths(3), { pickUntil = false }) { s = s.copy(until = it, count = null) }
    if (pickYearly) AgendaDatePicker(LocalDate.of(start.year, s.yearMonth, minOf(s.yearDay, java.time.YearMonth.of(start.year, s.yearMonth).lengthOfMonth())),
        { pickYearly = false }) { s = s.copy(yearMonth = it.monthValue, yearDay = it.dayOfMonth) }
}

/** Reminder offsets: the usual ones and a custom one; for all-day events "la veille à 18 h" style. */
@Composable
fun AlertPickerDialog(allDay: Boolean, h24: Boolean, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val presets = if (allDay) listOf(540, -360, -900, -2340, -9540) else listOf(0, -5, -10, -15, -30, -60, -1440, -10080)
    var custom by remember { mutableStateOf(false) }
    var amount by remember { mutableStateOf("2") }
    var unit by remember { mutableStateOf(if (allDay) 1440 else 60) }
    var time by remember { mutableStateOf(java.time.LocalTime.of(9, 0)) }
    var pickTime by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rappel") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (!custom) {
                    presets.forEach { a ->
                        Text(AgendaText.alertLabel(a, allDay, h24), Modifier.fillMaxWidth().clickable { onDismiss(); onPick(a) }.padding(vertical = 10.dp))
                    }
                    Text("Personnalisé…", color = MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxWidth().clickable { custom = true }.padding(vertical = 10.dp))
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(amount, { amount = it.filter { c -> c.isDigit() }.take(4) }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(80.dp))
                        Text(if (allDay) " jours avant" else " avant", Modifier.padding(start = 8.dp))
                    }
                    if (allDay) {
                        Text("à ${AgendaText.time(time, h24)}", color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { pickTime = true }.padding(vertical = 10.dp))
                    } else Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(1 to "min", 60 to "h", 1440 to "jours", 10080 to "sem.").forEach { (u, label) -> FilterChip(unit == u, { unit = u }, { Text(label) }) }
                    }
                }
            }
        },
        confirmButton = {
            if (custom) TextButton(onClick = {
                val n = amount.toIntOrNull() ?: 0
                val minutes = if (allDay) -n * 1440 + time.hour * 60 + time.minute else -n * unit
                onDismiss(); onPick(minutes)
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
    if (pickTime) AgendaTimePicker(time, { pickTime = false }) { time = it }
}
