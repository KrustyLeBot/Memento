package app.scriptorium.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarViewDay
import androidx.compose.material.icons.filled.CalendarViewMonth
import androidx.compose.material.icons.filled.CalendarViewWeek
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.scriptorium.MainViewModel
import app.scriptorium.Screen
import app.scriptorium.agenda.AgendaController
import app.scriptorium.agenda.AgendaLayout
import app.scriptorium.agenda.AgendaSources
import app.scriptorium.agenda.AgendaText
import app.scriptorium.agenda.AgendaView
import app.scriptorium.agenda.Entry
import app.scriptorium.reminders.Reminders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Today, updated when the date changes while the screen is open. */
@Composable
fun rememberToday(): LocalDate {
    val today by produceState(LocalDate.now()) {
        while (true) {
            delay(30_000)
            if (value != LocalDate.now()) value = LocalDate.now()
        }
    }
    return today
}

/** The Agenda module: Agenda (list), Mois, Semaine and Jour views, search, FAB, import/export menu. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgendaScreen(vm: MainViewModel) {
    val c = vm.agenda
    val context = LocalContext.current
    val sources by c.sources.collectAsState()
    val scan by vm.repo.agenda.collectAsState()
    val scans by vm.repo.scans.collectAsState()
    val snackbar = rememberVmSnackbar(vm)
    val today = rememberToday()
    val h24 = is24h()
    var menu by remember { mutableStateOf(false) }
    var viewMenu by remember { mutableStateOf(false) }
    var jump by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var pastImports by remember { mutableStateOf(false) }
    var problems by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { c.startImport(it) }
    BackHandler(enabled = c.searching) { c.searching = false; c.query = "" }

    // never on top of the one-time import dialog (a widget can open the agenda at the very first start)
    if (vm.legacyImport == null) AskReminderAccess(c, sources)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (c.searching) {
                TopAppBar(
                    navigationIcon = { IconButton(onClick = { c.searching = false; c.query = "" }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Fermer la recherche") } },
                    title = {
                        TextField(
                            c.query, { c.query = it }, placeholder = { Text("Titre, lieu, description") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                    actions = { if (c.query.isNotEmpty()) IconButton(onClick = { c.query = "" }) { Icon(Icons.Default.Close, "Effacer") } },
                )
            } else {
                TopAppBar(
                    title = {
                        Text(title(c, today), fontWeight = FontWeight.Bold, modifier = Modifier.clickable { jump = true })
                    },
                    actions = {
                        IconButton(onClick = { c.searching = true }) { Icon(Icons.Default.Search, "Rechercher") }
                        IconButton(onClick = { c.goToday() }) { Icon(Icons.Default.Today, "Aujourd'hui") }
                        Box {
                            IconButton(onClick = { viewMenu = true }) { Icon(viewIcon(c.view), "Vue : ${c.view.label}") }
                            DropdownMenu(viewMenu, { viewMenu = false }) {
                                AgendaView.entries.forEach { v ->
                                    DropdownMenuItem({ Text(v.label) }, { viewMenu = false; c.changeView(v) }, leadingIcon = { Icon(viewIcon(v), null) })
                                }
                            }
                        }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Menu") }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem({ Text("Aller à une date") }, { menu = false; jump = true })
                                DropdownMenuItem({ Text("Agendas affichés") }, { menu = false; vm.calendarsReturn = Screen.AGENDA; vm.screen = Screen.AGENDA_CALENDARS })
                                DropdownMenuItem({ Text("Importer un fichier .ics") }, { menu = false; pick.launch(arrayOf("text/calendar", "application/zip", "application/octet-stream", "*/*")) })
                                DropdownMenuItem({ Text("Exporter en .ics") }, { menu = false; exporting = true })
                                DropdownMenuItem({ Text("Imports passés") }, { menu = false; pastImports = true })
                                val n = scan.problems.size
                                if (n > 0) DropdownMenuItem({ Text("Fichiers illisibles ($n)", color = MaterialTheme.colorScheme.error) }, { menu = false; problems = true })
                            }
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            if (!c.searching) FloatingActionButton(onClick = { c.newEvent(if (c.view == AgendaView.AGENDA) today else c.selected) }) {
                Icon(Icons.Default.Add, "Nouvel événement")
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (vm.syncing) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (scans == 0) {
                Text("Lecture du dossier…", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp))
                return@Column
            }
            if (c.searching) {
                SearchResults(c, sources, today, h24)
                return@Column
            }
            when (c.view) {
                AgendaView.AGENDA -> AgendaList(c, sources, today, h24)
                AgendaView.MONTH -> MonthView(c, sources, today, h24)
                AgendaView.WEEK -> TimeGridView(c, sources, today, h24, days = 7)
                AgendaView.DAY -> TimeGridView(c, sources, today, h24, days = 1)
            }
        }
    }

    if (jump) AgendaDatePicker(c.selected, { jump = false }) { c.goTo(it) }
    if (exporting) ExportDialog(c, sources.settings) { exporting = false }
    if (pastImports) PastImportsDialog(c, sources) { pastImports = false }
    if (problems) {
        AlertDialog(
            onDismissRequest = { problems = false },
            title = { Text("Fichiers illisibles") },
            text = { LazyColumn { items(scan.problems) { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp)) } } },
            confirmButton = { TextButton(onClick = { problems = false }) { Text("OK") } },
        )
    }
    IcsImportDialog(c, sources.settings)
}

private fun viewIcon(v: AgendaView) = when (v) {
    AgendaView.AGENDA -> Icons.AutoMirrored.Filled.ViewList
    AgendaView.MONTH -> Icons.Default.CalendarViewMonth
    AgendaView.WEEK -> Icons.Default.CalendarViewWeek
    AgendaView.DAY -> Icons.Default.CalendarViewDay
}

private fun title(c: AgendaController, today: LocalDate): String {
    val d = c.selected
    return when (c.view) {
        AgendaView.AGENDA -> "Agenda"
        AgendaView.MONTH, AgendaView.WEEK -> AgendaText.monthTitle(d.year, d.monthValue)
        AgendaView.DAY -> AgendaText.dayTitle(d, today).replaceFirstChar { it.uppercase() }
    }
}

/**
 * Asked once, from the agenda: the notification permission (Android 13+), then the exact-alarm access
 * (Android 12+) with an explanation, when there are reminders to deliver.
 */
@Composable
private fun AskReminderAccess(c: AgendaController, sources: AgendaSources) {
    val context = LocalContext.current
    val hasAlerts = sources.events.any { it.alerts.isNotEmpty() } || sources.notes.any { it.reminder != null }
    var explain by remember { mutableStateOf(false) }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (!Reminders.canExact(context) && !c.prefs.exactAlarmAsked) explain = true
    }
    LaunchedEffect(hasAlerts) {
        if (!hasAlerts) return@LaunchedEffect
        val granted = Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!granted && !c.prefs.notificationsAsked) {
            c.prefs.notificationsAsked = true
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else if (!Reminders.canExact(context) && !c.prefs.exactAlarmAsked) explain = true
    }
    if (explain) {
        AlertDialog(
            onDismissRequest = { explain = false; c.prefs.exactAlarmAsked = true },
            title = { Text("Rappels à l'heure exacte") },
            text = {
                Text("Pour que les rappels sonnent à la minute près, autorise « Alarmes et rappels » pour l'app. " +
                    "Sans cela, ils arrivent avec quelques minutes de retard. Modifiable plus tard dans Paramètres → Agenda.")
            },
            confirmButton = {
                TextButton(onClick = {
                    explain = false
                    c.prefs.exactAlarmAsked = true
                    context.startActivity(Reminders.exactAlarmSettings(context))
                }) { Text("Autoriser") }
            },
            dismissButton = { TextButton(onClick = { explain = false; c.prefs.exactAlarmAsked = true }) { Text("Plus tard") } },
        )
    }
}

@Composable
private fun SearchResults(c: AgendaController, sources: AgendaSources, today: LocalDate, h24: Boolean) {
    val zone = ZoneId.systemDefault()
    val results by produceState<List<Entry>?>(null, sources, c.query) {
        delay(200)
        value = if (c.query.isBlank()) emptyList() else withContext(Dispatchers.Default) { AgendaLayout.search(sources, c.query, Instant.now(), zone) }
    }
    val list = results
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
        when {
            c.query.isBlank() -> item { Text("Cherche dans les titres, les lieux et les descriptions des événements et des notes datées.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            list == null -> item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            list.isEmpty() -> item { Text("Aucun résultat", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            else -> items(list, key = { it.key }) { e ->
                Row {
                    DayBadge(e.startDate, today)
                    Column(Modifier.weight(1f)) {
                        Text(AgendaText.dayTitle(e.startDate, today), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        EntryChip(e, AgendaText.timeLine(e, zone, h24, c.eventZoneTimes)) { c.open(e) }
                    }
                }
            }
        }
    }
}
