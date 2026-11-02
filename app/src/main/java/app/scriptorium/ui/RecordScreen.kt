package app.scriptorium.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.scriptorium.GpsMode
import app.scriptorium.MainViewModel
import app.scriptorium.activity.ActivityController
import app.scriptorium.activity.Fmt
import app.scriptorium.activity.Sport
import app.scriptorium.record.Live
import app.scriptorium.record.Recorder
import app.scriptorium.record.RecordingService
import app.scriptorium.map.OfflinePacks
import app.scriptorium.suite.ConfirmDialog
import app.scriptorium.suite.SuiteTopBar
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/** Start, follow and stop an activity. Recording goes on in a service when this screen is left. */
@Composable
fun RecordScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val live by Recorder.state.collectAsState()
    var sport by remember { mutableStateOf(Sport.WALK) }
    var mode by remember { mutableStateOf(vm.settings.gpsMode) }
    var confirmStop by remember { mutableStateOf(false) }
    var showMap by remember { mutableStateOf(false) }
    var follow by remember { mutableStateOf<RouteChoice?>(null) }
    val scope = rememberCoroutineScope()

    fun begin() {
        Recorder.begin(context.filesDir, sport, System.currentTimeMillis())
        val f = follow?.takeIf { sport.hasGps }
        if (f != null) {
            Recorder.followRoute(f.route)
            Recorder.routePack = f.packId
            showMap = true
            val pack = f.packId?.let { id -> OfflinePacks.packs.value.firstOrNull { it.id == id } }
            when {
                pack == null -> prepareRoute(vm, scope, f.route) { if (Recorder.active) Recorder.routePack = it }
                !pack.complete && !OfflinePacks.isRunning(pack.id) -> try {
                    OfflinePacks.resume(pack.id)
                } catch (e: OfflinePacks.PackException) {
                    vm.snack = app.scriptorium.Snack("Carte hors ligne incomplète : ${e.message}")
                }
            }
        }
        if (sport.hasGps) RecordingService.start(context)
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true) begin()
        else vm.snack = app.scriptorium.Snack("Sans la localisation, impossible d'enregistrer un tracé")
    }
    fun start() {
        if (!sport.hasGps) { begin(); return }
        val wanted = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissions.launch(wanted.toTypedArray())
    }

    Scaffold(topBar = { SuiteTopBar("Enregistrer", onBack = { vm.back() }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            val l = live
            if (l == null) {
                val scroll = rememberScrollState()
                LaunchedEffect(vm.showPreparedRoutes) {
                    if (!vm.showPreparedRoutes) return@LaunchedEffect
                    vm.showPreparedRoutes = false
                    // the offline maps end the screen: wait for its first layout, then go to the end
                    kotlinx.coroutines.withTimeoutOrNull(2_000) { androidx.compose.runtime.snapshotFlow { scroll.maxValue }.first { it > 0 } }
                    scroll.animateScrollTo(scroll.maxValue)
                }
                Column(Modifier.padding(16.dp).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Quelle activité ?", style = MaterialTheme.typography.titleMedium)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(Sport.WALK, Sport.BIKE, Sport.EBIKE).forEach { s -> SportButton(s, sport == s, Modifier.weight(1f)) { sport = s } }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(Sport.SKI, Sport.GPS, Sport.PLAIN).forEach { s -> SportButton(s, sport == s, Modifier.weight(1f)) { sport = s } }
                    }
                    if (sport.hasGps) {
                        Text("Précision du GPS", style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GpsMode.values().forEach { m ->
                                FilterChip(selected = mode == m, onClick = { mode = m; vm.settings.gpsMode = m }, label = { Text(m.label) })
                            }
                        }
                        Text(
                            if (mode == GpsMode.NORMAL) "Un point toutes les ${RecordingService.fixInterval(sport, mode) / 1000} s : bon compromis précision / batterie."
                            else "Un point toutes les 12 s : la batterie dure plus, les arrêts et les virages sont moins fins.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            "Chronomètre seul, sans GPS. Tu pourras ajouter la distance ensuite (tapis de marche, salle…).",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (sport.hasGps) FollowRouteRow(vm, follow) { follow = it }
                    Button(onClick = { start() }, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Démarrer", fontSize = 18.sp) }
                    if (sport.hasGps) {
                        TextButton(onClick = {
                            context.startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }) { Text("Samsung tue l'enregistrement ? Désactiver l'optimisation batterie") }
                    }
                    PreparedRoutesSection(vm)
                }
            } else {
                LiveView(vm, l, showMap, { showMap = it }, onStop = { confirmStop = true })
            }
        }
    }

    if (confirmStop) {
        ConfirmDialog(
            title = "Terminer l'activité ?", text = null, confirm = "Terminer",
            onConfirm = {
                if (live?.sport?.hasGps == true) RecordingService.start(context, RecordingService.ACTION_STOP)
                else Recorder.finish(System.currentTimeMillis())
            },
            onDismiss = { confirmStop = false },
        )
    }

    val done = live?.takeIf { it.finished }
    if (done != null) {
        var title by remember(done.start) { mutableStateOf(ActivityController.defaultTitle(done.sport, done.start)) }
        var km by remember(done.start) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Enregistrer l'activité") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("${sportEmoji(done.sport)} ${done.sport.label} · ${Fmt.clock(done.elapsed(done.pauseStart) / 1000)}" + if (done.sport.hasGps) " · ${Fmt.distance(done.distance)}" else "")
                    OutlinedTextField(title, { title = it }, label = { Text("Nom") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (!done.sport.hasGps) {
                        OutlinedTextField(km, { km = it }, label = { Text("Distance (km), facultatif") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val log = Recorder.readLog()
                    if (log != null) {
                        vm.activities.saveRecording(log, title, km.replace(',', '.').toDoubleOrNull()?.times(1000))
                        vm.screen = app.scriptorium.Screen.ACTIVITIES
                    }
                }) { Text("Enregistrer") }
            },
            dismissButton = { TextButton(onClick = { Recorder.discard() }) { Text("Abandonner") } },
        )
    }
}

@Composable
private fun SportButton(s: Sport, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val container = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer
    androidx.compose.material3.Card(
        onClick = onClick,
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = container),
        modifier = modifier,
    ) {
        Column(Modifier.fillMaxWidth().height(96.dp).padding(horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(sportEmoji(s), fontSize = 30.sp)
            Text(
                s.label, style = MaterialTheme.typography.labelMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                maxLines = 2, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            )
        }
    }
}

@Composable
private fun LiveView(vm: MainViewModel, l: Live, showMap: Boolean, setMap: (Boolean) -> Unit, onStop: () -> Unit) {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(l.paused, l.finished) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val elapsed = l.elapsed(now) / 1000

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("${sportEmoji(l.sport)} ${l.sport.label}" + if (l.paused) " · en pause" else "", style = MaterialTheme.typography.titleMedium)
        Text(
            Fmt.clock(elapsed), fontSize = 64.sp, fontWeight = FontWeight.Bold,
            color = if (l.paused) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
        if (l.sport.hasGps) {
            if (l.route != null) {
                RouteFigures(l, elapsed)
                RoutePackProgress()
            } else {
                val items = ArrayList<Pair<String, String>>()
                items.add("Distance" to Fmt.distance(l.distance))
                when (l.sport) {
                    Sport.WALK -> {
                        items.add("Allure" to Fmt.pace(l.speed))
                        items.add("Dénivelé +" to "${Math.round(l.ascent)} m")
                        items.add("Allure moyenne" to if (elapsed > 0) Fmt.pace(l.distance / elapsed) else "–")
                    }
                    Sport.SKI -> {
                        items.add("Vitesse" to Fmt.speed(l.speed))
                        items.add("Altitude" to (l.altitude?.let { "${Math.round(it)} m" } ?: "–"))
                    }
                    else -> {
                        items.add("Vitesse" to Fmt.speed(l.speed))
                        items.add("Vitesse moyenne" to if (elapsed > 0) Fmt.speed(l.distance / elapsed) else "–")
                        items.add("Dénivelé +" to "${Math.round(l.ascent)} m")
                    }
                }
                StatGrid(items)
            }
            if (l.fixes == 0) {
                Text("En attente du signal GPS…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(onClick = { setMap(!showMap) }) { Text(if (showMap) "Masquer la carte" else "Afficher la carte") }
            if (showMap) {
                LiveMap(vm.settings, l, Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(16.dp)))
            }
        }
        Box(Modifier.weight(if (showMap) 0.001f else 1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val gps = l.sport.hasGps
            OutlinedButton(
                onClick = {
                    val t = System.currentTimeMillis()
                    if (l.paused) { if (gps) RecordingService.start(context, RecordingService.ACTION_RESUME) else Recorder.resume(t) }
                    else { if (gps) RecordingService.start(context, RecordingService.ACTION_PAUSE) else Recorder.pause(t) }
                },
                modifier = Modifier.weight(1f).height(56.dp),
            ) { Text(if (l.paused) "Reprendre" else "Pause", fontSize = 16.sp) }
            Button(onClick = onStop, modifier = Modifier.weight(1f).height(56.dp)) { Text("Terminer", fontSize = 16.sp) }
        }
    }
}
