package app.scriptorium.ui

import android.content.Context
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.scriptorium.MainViewModel
import app.scriptorium.Note
import app.scriptorium.Snack
import app.scriptorium.map.OfflinePacks
import app.scriptorium.map.Pack
import app.scriptorium.map.Route
import app.scriptorium.map.RouteFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** A route chosen to follow; [packId] when it comes from a prepared pack, null when it still has to be prepared. */
class RouteChoice(val route: Route, val packId: String?)

private enum class Source { FILE, ACTIVITY }

/** Where a route comes from: a GPX/FIT file or an activity of the app. Hosts the file picker and the activity list. */
class RoutePicker(
    val pickFile: () -> Unit,
    val pickActivity: () -> Unit,
)

@Composable
fun rememberRoutePicker(vm: MainViewModel, onRoute: (Route) -> Unit): RoutePicker {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var choosingActivity by remember { mutableStateOf(false) }
    val file = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) load(vm, scope, { readUri(context, uri) }, onRoute)
    }
    if (choosingActivity) {
        ActivityChooser(vm, onDismiss = { choosingActivity = false }) { note ->
            choosingActivity = false
            load(vm, scope, { readActivity(vm, note) }, onRoute)
        }
    }
    return remember { RoutePicker({ file.launch(arrayOf("*/*")) }, { choosingActivity = true }) }
}

private fun load(vm: MainViewModel, scope: CoroutineScope, read: () -> Route, onRoute: (Route) -> Unit) {
    scope.launch {
        try {
            onRoute(withContext(Dispatchers.IO) { read() })
        } catch (e: Exception) {
            vm.snack = Snack("Parcours illisible : ${e.message}")
        }
    }
}

private fun readUri(context: Context, uri: Uri): Route {
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw java.io.IOException("Fichier illisible")
    if (bytes.size > 50_000_000) throw java.io.IOException("Fichier trop gros")
    val name = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.')?.ifBlank { null } ?: "Parcours"
    return RouteFile.read(bytes, name)
}

private fun readActivity(vm: MainViewModel, note: Note): Route {
    val file = note.activity?.file ?: throw java.io.IOException("Cette activité n'a pas de tracé")
    val bytes = vm.repo.fs.readBytes(file) ?: throw java.io.IOException("Fichier introuvable (pas encore synchronisé ?)")
    return RouteFile.read(bytes, note.title.ifBlank { "Activité" }).let { Route(note.title.ifBlank { it.name }, it.lats, it.lons, it.eles) }
}

@Composable
private fun ActivityChooser(vm: MainViewModel, onDismiss: () -> Unit, onPick: (Note) -> Unit) {
    val all by vm.activities.activities.collectAsState()
    val list = remember(all) { all.filter { it.activity?.file != null && it.activity.sport.hasGps }.sortedByDescending { it.activity?.start ?: 0 } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choisir une activité") },
        text = {
            if (list.isEmpty()) Text("Aucune activité avec un tracé.")
            else LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(list, key = { it.path }) { n ->
                    Column(Modifier.fillMaxWidth().clickable { onPick(n) }.padding(vertical = 10.dp)) {
                        Text(n.title.ifBlank { "Activité" }, style = MaterialTheme.typography.bodyLarge)
                        n.activity?.let { a ->
                            Text(
                                "${sportEmoji(a.sport)} ${formatDate(a.start)} · ${app.scriptorium.activity.Fmt.distance(a.distance)}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

/** Starts preparing [route] for offline use, from a background thread; problems become a snack. */
fun prepareRoute(vm: MainViewModel, scope: CoroutineScope, route: Route, onStarted: (String) -> Unit = {}) {
    scope.launch {
        try {
            val id = withContext(Dispatchers.IO) { OfflinePacks.prepareRoute(route) }
            onStarted(id)
        } catch (e: OfflinePacks.PackException) {
            vm.snack = Snack(e.message ?: "Préparation impossible")
        }
    }
}

/** "Préparer un parcours": the prepared routes, their size and expiry, and the button to add one. */
@Composable
fun PreparedRoutesSection(vm: MainViewModel) {
    val scope = rememberCoroutineScope()
    val packs by OfflinePacks.packs.collectAsState()
    val progress by OfflinePacks.progress.collectAsState()
    var choosing by remember { mutableStateOf(false) }
    val picker = rememberRoutePicker(vm) { route -> prepareRoute(vm, scope, route) }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { OfflinePacks.refresh() } }

    Section("Parcours hors ligne") {
        Text(
            "Prépare un parcours avant de partir (en Wi-Fi par exemple) : la carte, le relief et les sommets autour " +
                "sont téléchargés pour fonctionner sans réseau. Un parcours préparé est gardé 24 h, puis supprimé.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        packs.forEach { p -> PackRow(vm, p, progress[p.id]) }
        OutlinedButton(onClick = { choosing = true }) { Text("Préparer un parcours") }
    }
    if (choosing) {
        SourceDialog(
            title = "Préparer un parcours",
            onDismiss = { choosing = false },
            onSource = { s -> choosing = false; if (s == Source.FILE) picker.pickFile() else picker.pickActivity() },
        )
    }
}

@Composable
private fun PackRow(vm: MainViewModel, p: Pack, progress: OfflinePacks.Progress?) {
    val running = OfflinePacks.isRunning(p.id)
    var confirmDelete by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.name, style = MaterialTheme.typography.bodyLarge)
                val size = "${(progress?.bytes ?: p.bytes) / 1_000_000} Mo"
                val state = when {
                    p.complete -> (if (p.has3d) "2D + 3D" else "2D seulement") + " · $size"
                    running -> "Téléchargement… $size"
                    else -> "Interrompu · $size"
                }
                Text("$state · supprimé ${expiryLabel(p.expiresAt())}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!p.complete && !running) {
                TextButton(onClick = {
                    try { OfflinePacks.resume(p.id) } catch (e: OfflinePacks.PackException) { vm.snack = Snack(e.message ?: "Reprise impossible") }
                }) { Text("Reprendre") }
            }
            IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Supprimer le parcours préparé") }
        }
        if (running && progress != null) LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth())
        progress?.error?.let { if (!running) Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
    if (confirmDelete) {
        app.scriptorium.suite.ConfirmDialog(
            title = "Supprimer « ${p.name} » ?", text = "La carte hors ligne de ce parcours est effacée du téléphone.",
            confirm = "Supprimer", onConfirm = { confirmDelete = false; OfflinePacks.delete(p.id) }, onDismiss = { confirmDelete = false }, destructive = true,
        )
    }
}

/** "à 14:05" today, "demain à 14:05" otherwise. */
fun expiryLabel(at: Long, now: Long = System.currentTimeMillis()): String {
    val a = Calendar.getInstance().apply { timeInMillis = at }
    val n = Calendar.getInstance().apply { timeInMillis = now }
    val hour = SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(at))
    return if (a.get(Calendar.DAY_OF_YEAR) == n.get(Calendar.DAY_OF_YEAR) && a.get(Calendar.YEAR) == n.get(Calendar.YEAR)) "à $hour" else "demain à $hour"
}

@Composable
private fun SourceDialog(title: String, onDismiss: () -> Unit, onSource: (Source) -> Unit, extra: @Composable () -> Unit = {}) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                extra()
                TextButton(onClick = { onSource(Source.FILE) }) { Text("Un fichier GPX ou FIT…") }
                TextButton(onClick = { onSource(Source.ACTIVITY) }) { Text("Une activité de l'application…") }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

/** "Suivre un parcours" at the start of a recording: none, a prepared route, a file or an activity. */
@Composable
fun FollowRouteRow(vm: MainViewModel, choice: RouteChoice?, onChoice: (RouteChoice?) -> Unit) {
    val scope = rememberCoroutineScope()
    val packs by OfflinePacks.packs.collectAsState()
    var choosing by remember { mutableStateOf(false) }
    val picker = rememberRoutePicker(vm) { route -> onChoice(RouteChoice(route, null)) }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Suivre un parcours", style = MaterialTheme.typography.titleSmall)
            Text(
                choice?.route?.name?.ifBlank { "Parcours" } ?: "Aucun",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (choice != null) TextButton(onClick = { onChoice(null) }) { Text("Retirer") }
        TextButton(onClick = { choosing = true }) { Text("Choisir") }
    }
    if (choosing) {
        val prepared = packs.filter { it.hasRoute }
        SourceDialog(
            title = "Suivre un parcours",
            onDismiss = { choosing = false },
            onSource = { s -> choosing = false; if (s == Source.FILE) picker.pickFile() else picker.pickActivity() },
        ) {
            if (prepared.isNotEmpty()) {
                Text("Parcours préparés", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                prepared.forEach { p ->
                    TextButton(onClick = {
                        choosing = false
                        scope.launch {
                            try {
                                onChoice(RouteChoice(withContext(Dispatchers.IO) { OfflinePacks.route(p.id) }, p.id))
                            } catch (e: Exception) {
                                vm.snack = Snack("Parcours illisible : ${e.message}")
                            }
                        }
                    }) { Text(p.name + if (p.complete) "" else " (carte incomplète)") }
                }
                HorizontalDivider()
            }
            Text(
                "Un fichier ou une activité est préparé pour le hors ligne pendant l'enregistrement.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
