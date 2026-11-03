package app.memento.ui

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.memento.MainViewModel
import app.memento.Note
import app.memento.Snack
import app.memento.Zone3dPolicy
import app.memento.map.OfflinePacks
import app.memento.map.Pack
import app.memento.map.Route
import app.memento.map.RouteFile
import app.memento.map.TilePlan
import app.memento.record.Recorder
import app.memento.record.StartRoute
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
            vm.say(Snack("Parcours illisible : ${e.message}"))
        }
    }
}

private fun readUri(context: Context, uri: Uri): Route {
    // the size is checked while reading: a huge file picked by mistake must not be loaded whole first
    val bytes = context.contentResolver.openInputStream(uri)?.use { s ->
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = s.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > 50_000_000) throw java.io.IOException("Fichier trop gros")
        }
        out.toByteArray()
    } ?: throw java.io.IOException("Fichier illisible")
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
                                "${sportEmoji(a.sport)} ${formatDate(a.start)} · ${app.memento.activity.Fmt.distance(a.distance)}",
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
fun prepareRoute(vm: MainViewModel, scope: CoroutineScope, route: Route, plan: TilePlan? = null, onStarted: (String) -> Unit = {}) {
    scope.launch {
        try {
            val id = withContext(Dispatchers.IO) { if (plan != null) OfflinePacks.prepareRoute(route, plan) else OfflinePacks.prepareRoute(route) }
            onStarted(id)
        } catch (e: OfflinePacks.PackException) {
            vm.say(Snack(e.message ?: "Préparation impossible"))
        } catch (e: java.io.IOException) {
            // writing the pack's first files (storage full): uncaught, it would crash the app mid-recording start
            vm.say(Snack("Préparation impossible : ${e.message}"))
        }
    }
}

/**
 * The pack of a route followed from the start of a recording without being prepared: 2D + 3D or 2D as the "3D hors
 * ligne" setting says; on "Demander", 2D at once and the question under the recording ([StartRoute]).
 */
fun startRoutePack(vm: MainViewModel, scope: CoroutineScope, route: Route, policy: Zone3dPolicy) {
    scope.launch {
        try {
            val plan3d = withContext(Dispatchers.Default) { OfflinePacks.planRoute(route, with3d = true) }
            val plan2d = if (policy == Zone3dPolicy.ALWAYS) null else withContext(Dispatchers.Default) { OfflinePacks.planRoute(route, with3d = false) }
            val id = withContext(Dispatchers.IO) { OfflinePacks.prepareRoute(route, plan2d ?: plan3d) }
            if (!Recorder.active) return@launch
            Recorder.routePack = id
            if (policy == Zone3dPolicy.ASK && plan2d != null && plan3d.demZooms != null) {
                StartRoute.ask(id, plan3d.estimatedBytes - plan2d.estimatedBytes)
            }
        } catch (e: OfflinePacks.PackException) {
            vm.say(Snack(e.message ?: "Préparation impossible"))
        } catch (e: java.io.IOException) {
            vm.say(Snack("Préparation impossible : ${e.message}"))
        }
    }
}

/** The two plans of a route being prepared, for the choice between them. */
private class RoutePlans(val route: Route, val with3d: TilePlan, val flat: TilePlan)

/** "Préparer un parcours": the prepared routes, their size and expiry, and the button to add one. */
@Composable
fun PreparedRoutesSection(vm: MainViewModel) {
    val scope = rememberCoroutineScope()
    val packs by OfflinePacks.packs.collectAsState()
    val progress by OfflinePacks.progress.collectAsState()
    var choosing by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<RoutePlans?>(null) }
    val picker = rememberRoutePicker(vm) { route ->
        scope.launch {
            try {
                pending = withContext(Dispatchers.Default) {
                    RoutePlans(route, OfflinePacks.planRoute(route, with3d = true), OfflinePacks.planRoute(route, with3d = false))
                }
            } catch (e: OfflinePacks.PackException) {
                vm.say(Snack(e.message ?: "Préparation impossible"))
            }
        }
    }
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
    pending?.let { p ->
        val has3d = p.with3d.demZooms != null
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Préparer « ${p.route.name.ifBlank { "Parcours" }} »") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Télécharger aussi la 3D hors ligne (relief, bâtiments) ?", fontWeight = FontWeight.SemiBold)
                    if (has3d) Text("2D + 3D : environ ${megabytes(p.with3d.estimatedBytes)} Mo. ${p.with3d.detailLabel()}.")
                    else Text("Parcours trop étendu pour la 3D hors ligne.")
                    Text("2D seule : environ ${megabytes(p.flat.estimatedBytes)} Mo. ${p.flat.detailLabel()}.")
                    Text(
                        "Gardé 24 h sur le téléphone, puis supprimé. Mieux vaut le télécharger en Wi-Fi.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = { pending = null; prepareRoute(vm, scope, p.route, p.flat) }) { Text("2D seule") }
                    if (has3d) TextButton(onClick = { pending = null; prepareRoute(vm, scope, p.route, p.with3d) }) { Text("2D + 3D") }
                }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Annuler") } },
        )
    }
}

private fun megabytes(b: Long) = (b + 500_000) / 1_000_000

@Composable
private fun PackRow(vm: MainViewModel, p: Pack, progress: OfflinePacks.Progress?) {
    val running = OfflinePacks.isRunning(p.id)
    var confirmDelete by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.name, style = MaterialTheme.typography.bodyLarge)
                // a complete pack's bytes are the files on disk, summed when its download ended
                val size = "${megabytes(if (p.complete) p.bytes else progress?.bytes ?: p.bytes)} Mo"
                val detail = if (p.corridor != null) " · HD le long du tracé" else ""
                val state = when {
                    p.complete -> (if (p.has3d) "2D + 3D" else "2D seulement") + detail + " · $size"
                    running -> "Téléchargement… $size"
                    else -> "Interrompu · $size"
                }
                Text("$state · supprimé ${expiryLabel(p.expiresAt())}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!p.complete && !running) {
                TextButton(onClick = {
                    try { OfflinePacks.resume(p.id) } catch (e: OfflinePacks.PackException) { vm.say(Snack(e.message ?: "Reprise impossible")) }
                }) { Text("Reprendre") }
            }
            IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Supprimer le parcours préparé") }
        }
        if (running && progress != null) LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth())
        progress?.error?.let { if (!running) Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
    if (confirmDelete) {
        app.memento.suite.ConfirmDialog(
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
                                vm.say(Snack("Parcours illisible : ${e.message}"))
                            }
                        }
                    }) { Text(p.name + if (p.complete) "" else " (carte incomplète)") }
                }
                HorizontalDivider()
            }
            Text(
                "Un parcours pris dans un fichier ou une activité est préparé pour le hors ligne au démarrage de l'enregistrement.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
