package app.memento.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.memento.MainViewModel
import app.memento.Note
import app.memento.activity.ActivityInfo
import app.memento.activity.Fmt
import app.memento.activity.Sport
import app.memento.record.Recorder
import app.memento.suite.SuiteTopBar

private data class Totals(val count: Int, val distance: Double, val seconds: Long, val ascent: Int, val skiRuns: Int, val skiDrop: Int, val hasSki: Boolean)

/** Only the sports ticked in the settings count (all by default); skiing, when counted, also gets its descents line. */
private fun totals(list: List<ActivityInfo>, included: Set<Sport>): Totals {
    val counted = list.filter { it.sport in included }
    val ski = counted.filter { it.sport == Sport.SKI }
    return Totals(counted.size, counted.sumOf { it.distance }, counted.sumOf { it.moving }, counted.sumOf { it.ascent }, ski.sumOf { it.runs }, ski.sumOf { it.drop }, ski.isNotEmpty())
}

/** The "Activités" folder: figures for the week, the month and overall, then the latest activities. */
@Composable
@OptIn(ExperimentalFoundationApi::class)
fun ActivitiesScreen(vm: MainViewModel) {
    val all by vm.activities.activities.collectAsState()
    val live by Recorder.state.collectAsState()
    var filter by vm.lists::activitySport
    var selected by remember { mutableStateOf(setOf<String>()) }
    var askRelief by remember { mutableStateOf(false) }
    val selecting = selected.isNotEmpty()
    BackHandler(enabled = selecting) { selected = emptySet() }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.activities.startImport(uri)
    }

    val infos = remember(all) { all.mapNotNull { n -> n.activity?.let { n to it } }.sortedByDescending { it.second.start } }
    val cfg by vm.repo.activitySettings.collectAsState()
    val week = remember(infos, cfg) { totals(infos.map { it.second }.filter { it.start >= startOfWeek() }, cfg.totals) }
    val month = remember(infos, cfg) { totals(infos.map { it.second }.filter { it.start >= startOfMonth() }, cfg.totals) }
    val overall = remember(infos, cfg) { totals(infos.map { it.second }, cfg.totals) }
    val shown = infos.filter { filter == null || it.second.sport == filter }
    val busy = vm.activities.busy || vm.activities.reliefProgress != null
    val tapped = vm.lists.activityTapped
    val tappedAt = tapped?.let { id -> shown.indexOfFirst { it.first.id == id } }
    // the rows come after the buttons, the selection line, the live card, the progress, the figures and the chips
    val leading = 4 + (if (live != null) 1 else 0) + (if (busy) 1 else 0)
    RevealOnReturn(vm.lists.activities, tappedAt?.let { if (it >= 0) shown[it].first.path else tapped }, tappedAt?.let { if (it < 0) -1 else leading + it }) {
        vm.lists.activityTapped = null
    }

    Scaffold(
        topBar = { SuiteTopBar("Activités") },
    ) { padding ->
        LazyColumn(
            state = vm.lists.activities,
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.openRecord() }, modifier = Modifier.weight(1f)) { Text("Enregistrer") }
                    OutlinedButton(onClick = { pick.launch(arrayOf("*/*")) }, modifier = Modifier.weight(1f)) { Text("Importer FIT / GPX") }
                }
            }
            item {
                if (selecting) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${selected.size} sélectionnée${if (selected.size > 1) "s" else ""}", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        TextButton(onClick = { selected = shown.map { it.first.path }.toSet() }) { Text("Tout") }
                        TextButton(onClick = { selected = emptySet() }) { Text("Annuler") }
                        Button(onClick = { askRelief = true }) { Text("Altitude") }
                    }
                } else if (infos.isNotEmpty()) {
                    OutlinedButton(onClick = { askRelief = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Compléter l'altitude de toutes les activités (relief)")
                    }
                }
            }
            live?.let { l ->
                item {
                    Card(
                        onClick = { vm.openRecord() },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text(
                                if (l.finished) "Activité interrompue : à enregistrer" else "Enregistrement en cours",
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text("${sportEmoji(l.sport)} ${l.sport.label} · touche pour ${if (l.finished) "terminer" else "revenir"}")
                        }
                    }
                }
            }
            if (busy) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator()
                        vm.activities.reliefProgress?.let { Text("  $it") }
                    }
                }
            }
            item {
                SummaryTable(listOf("Semaine" to week, "Mois" to month, "Total" to overall))
            }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("Tout") })
                    Sport.values().forEach { s ->
                        FilterChip(selected = filter == s, onClick = { filter = if (filter == s) null else s }, label = { Text(sportEmoji(s) + " " + s.label.substringBefore(" (")) })
                    }
                }
            }
            if (shown.isEmpty()) {
                item {
                    Text(
                        if (infos.isEmpty()) "Aucune activité. Importe un fichier FIT ou GPX (montre, Strava), ou enregistre-en une."
                        else "Aucune activité de ce sport.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            }
            items(shown, key = { it.first.path }) { (note, info) ->
                ActivityRow(
                    note, info, selecting, note.path in selected,
                    onClick = { if (selecting) selected = if (note.path in selected) selected - note.path else selected + note.path else { vm.lists.activityTapped = note.id; vm.openActivity(note) } },
                    onLongClick = { selected = selected + note.path },
                )
            }
        }
    }

    if (askRelief) {
        val targets = if (selecting) infos.filter { it.first.path in selected }.map { it.first } else infos.map { it.first }
        AlertDialog(
            onDismissRequest = { askRelief = false },
            title = { Text("Compléter l'altitude") },
            text = {
                Text(
                    "${targets.size} activité${if (targets.size > 1) "s" else ""}. Pour celles dont le fichier n'a pas d'altitude, les positions " +
                        "(environ une par 30 m) sont envoyées à opentopodata.org, à raison d'une requête par seconde. " +
                        "Celles qui ont déjà une altitude (enregistrées avec le baromètre, ou déjà complétées) sont ignorées.",
                )
            },
            confirmButton = { TextButton(onClick = { askRelief = false; vm.activities.fillAltitudes(targets); selected = emptySet() }) { Text("Lancer") } },
            dismissButton = { TextButton(onClick = { askRelief = false }) { Text("Annuler") } },
        )
    }

    vm.activities.pending?.let { p ->
        var title by remember(p) { mutableStateOf(p.title) }
        var sport by remember(p) { mutableStateOf(p.sport) }
        var relief by remember(p) { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = { vm.activities.cancelImport() },
            title = { Text("Importer l'activité") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(title, { title = it }, label = { Text("Nom") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    SportChips(sport, listOf(Sport.WALK, Sport.BIKE, Sport.EBIKE, Sport.SKI, Sport.GPS)) { sport = it }
                    if (p.existing != null) {
                        Text(
                            "Déjà importée : « ${p.existing.title} ». Elle sera remplacée (nom, description et photos conservés).",
                            color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.SemiBold,
                        )
                    }
                    if (p.needsAltitude) {
                        Row(Modifier.clickable { relief = !relief }, verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Checkbox(relief, { relief = it })
                            Text("Compléter l'altitude avec le relief (envoie les positions à opentopodata.org)", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Text(
                        "${p.track.points.size} points" + (p.track.hrAvg?.let { " · FC moyenne $it" } ?: ""),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { vm.activities.confirmImport(title, sport, relief) }) { Text(if (p.existing != null) "Remplacer" else "Importer") } },
            dismissButton = { TextButton(onClick = { vm.activities.cancelImport() }) { Text("Annuler") } },
        )
    }
}

@Composable
fun SportChips(selected: Sport, sports: List<Sport>, onSelect: (Sport) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        sports.forEach { s ->
            FilterChip(selected = selected == s, onClick = { onSelect(s) }, label = { Text(sportEmoji(s) + " " + s.label.substringBefore(" (")) })
        }
    }
}

/** One card, one row per figure: the columns stay aligned whatever the width of the phone. */
@Composable
private fun SummaryTable(columns: List<Pair<String, Totals>>) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            @Composable
            fun line(style: androidx.compose.ui.text.TextStyle, weight: FontWeight?, color: androidx.compose.ui.graphics.Color?, cell: (Totals) -> String) {
                Row(Modifier.fillMaxWidth()) {
                    columns.forEach { (_, t) ->
                        Text(
                            cell(t), style = style, fontWeight = weight, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                            color = color ?: androidx.compose.ui.graphics.Color.Unspecified, modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth()) {
                columns.forEach { (title, _) ->
                    Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                }
            }
            line(MaterialTheme.typography.bodyMedium, FontWeight.SemiBold, null) { Fmt.distance(it.distance) }
            line(MaterialTheme.typography.bodySmall, null, null) { Fmt.duration(it.seconds) }
            line(MaterialTheme.typography.bodySmall, null, MaterialTheme.colorScheme.onSurfaceVariant) { "${it.count} act." }
            if (columns.any { it.second.ascent > 0 }) line(MaterialTheme.typography.bodySmall, null, null) { if (it.ascent > 0) "↑ ${it.ascent} m" else "–" }
            if (columns.any { it.second.hasSki }) {
                line(MaterialTheme.typography.bodySmall, null, null) { if (it.hasSki) "⛷️ ${it.skiRuns} desc." else "–" }
                line(MaterialTheme.typography.bodySmall, null, null) { if (it.hasSki) "↓ ${it.skiDrop} m" else "–" }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun ActivityRow(note: Note, info: ActivityInfo, selecting: Boolean, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selecting) androidx.compose.material3.Checkbox(selected, null, Modifier.padding(end = 8.dp))
            Text(sportEmoji(info.sport), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(end = 12.dp))
            Column(Modifier.weight(1f)) {
                Text(note.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(formatDate(info.start), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(summaryLine(info), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

fun summaryLine(i: ActivityInfo): String {
    val parts = ArrayList<String>()
    if (i.sport == Sport.SKI) {
        // the whole track includes the lifts: what counts for skiing is the descents
        parts.add(Fmt.duration(if (i.moving > 0) i.moving else i.elapsed))
        if (i.runs > 0) parts.add("${i.runs} descentes" + if (i.drop > 0) " · ${i.drop} m" else "")
        if (i.climbs > 0) parts.add("${i.climbs} montées")
        return parts.joinToString(" · ")
    }
    if (i.distance > 0) parts.add(Fmt.distance(i.distance))
    // an untimed GPX (no <time> in the file) has no duration to show
    if (i.moving > 0 || i.elapsed > 0) parts.add(Fmt.duration(if (i.moving > 0) i.moving else i.elapsed))
    if (i.sport.hasGps && i.moving > 0 && i.distance > 0) parts.add(Fmt.speedFor(i.sport, i.distance / i.moving))
    if (i.ascent > 0) parts.add("↑ ${i.ascent} m")
    return parts.joinToString(" · ")
}
