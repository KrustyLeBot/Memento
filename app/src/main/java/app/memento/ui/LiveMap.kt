package app.memento.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.memento.Settings
import app.memento.activity.Fmt
import app.memento.map.Json
import app.memento.map.MapHost
import app.memento.map.MapPayload
import app.memento.map.MapSquare
import app.memento.map.NetState
import app.memento.map.OfflinePacks
import app.memento.map.Pack
import app.memento.map.RouteProgress
import app.memento.record.Live
import app.memento.record.Recorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The live map, shown in 3D from a covering pack or online, otherwise in 2D: position, trail by speed, the route in
 * white. [onFull] toggles the full-screen view ([full]), where [bottomInset] keeps the map's own buttons clear of
 * the recording controls drawn over it.
 */
@Composable
fun LiveMap(
    settings: Settings,
    l: Live,
    modifier: Modifier = Modifier,
    full: Boolean = false,
    onFull: (Boolean) -> Unit = {},
    bottomInset: Dp = 0.dp,
) {
    val scope = rememberCoroutineScope()
    var pref3d by remember { mutableStateOf(settings.map3d) }
    val online by NetState.online.collectAsState()
    val packs by OfflinePacks.packs.collectAsState()
    val covered = l.lat != null && l.lon != null && Pack.covers3d(packs, l.lon, l.lat, System.currentTimeMillis(), OfflinePacks.pinnedIds())
    val forced2d = !online && !covered
    val is3d = pref3d && !forced2d
    var following by remember { mutableStateOf(true) }
    val route = remember(l.start, l.route != null) { Recorder.route() }
    // how much of the trail the page has: point count and the trail's epoch
    val sent = remember { intArrayOf(0, -1) }

    fun pos(s: Live) {
        if (s.lat != null && s.lon != null) {
            MapHost.call("setPos", "${Json.num(s.lon)},${Json.num(s.lat)},${s.heading?.let { Json.num(it.toDouble(), 0) } ?: "null"},${s.accuracy?.let { Json.num(it.toDouble(), 0) } ?: "null"}")
        }
    }
    fun pushAll() {
        scope.launch {
            val (payload, t) = withContext(Dispatchers.Default) {
                val t = Recorder.trail()
                MapPayload.live(t.lons, t.lats, t.speeds) to t
            }
            MapHost.call("setLive", payload)
            sent[0] = t.lats.size
            sent[1] = t.epoch
            Recorder.state.value?.let { pos(it) }
        }
    }

    LaunchedEffect(l.start, route) {
        if (MapHost.begin(listOf("live", l.start, route?.name, route?.size))) {
            if (route != null) {
                val payload = withContext(Dispatchers.Default) { MapPayload.route(route) }
                MapHost.call("setRoute", payload)
                route.bbox?.let { MapHost.call("setSquare", MapPayload.bbox(MapSquare.of(it))) }
            } else {
                MapHost.call("setLiveSquare", "10")
            }
            MapHost.call("setFollow", "true")
            following = true
        }
        pushAll()
    }
    LaunchedEffect(is3d) { MapHost.call("setMode", Json.quote(if (is3d) "3d" else "2d")) }
    // the view changed size: the page resizes itself, the camera keeps following or shows the whole route again
    LaunchedEffect(full) { if (!following) MapHost.call("fit") }
    // one call per GPS fix; a gap (thinned trail, missed updates) resends the whole trail
    LaunchedEffect(l.fixes) {
        val added = Recorder.trailSince(sent[0], sent[1])
        if (added == null) { pushAll(); return@LaunchedEffect }
        for (i in added.lats.indices) {
            MapHost.call("appendLive", "${Json.num(added.lons[i])},${Json.num(added.lats[i])},${Json.num(added.speeds[i], 2)}")
        }
        sent[0] += added.lats.size
        pos(l)
    }

    Box(modifier) {
        SharedMap(
            Modifier.fillMaxSize(), is3d,
            onEvent = { type, e ->
                when (type) {
                    "ready" -> pushAll()
                    "follow" -> following = e["value"] == true
                    "mode" -> if (!forced2d) {
                        val v = e["value"] == "3d"
                        if (v != pref3d) { pref3d = v; settings.map3d = v }
                    }
                }
            },
            onShown = { pushAll() },
        )
        // full screen: below the status bar, as the activity map does
        val top = if (full) 40.dp else 8.dp
        MapModeSwitch(is3d, enabled = !forced2d, modifier = Modifier.align(Alignment.TopStart).padding(start = top, top = top)) {
            pref3d = it
            settings.map3d = it
        }
        MapOverlaySurface(
            shape = CircleShape,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = top, end = if (full) 12.dp else 8.dp),
        ) {
            IconButton(onClick = { onFull(!full) }) {
                if (full) Icon(Icons.Default.FullscreenExit, "Réduire la carte") else Icon(Icons.Default.Fullscreen, "Agrandir la carte")
            }
        }
        MapResetButton(Modifier.align(Alignment.TopEnd).padding(top = top + 56.dp, end = if (full) 12.dp else 8.dp))
        if (forced2d) OfflineBadge(Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 8.dp + bottomInset))
        if (!following) {
            SmallFloatingActionButton(
                onClick = { following = true; MapHost.call("setFollow", "true") },
                modifier = Modifier.align(Alignment.BottomEnd).then(if (full) Modifier.liftAboveSnackbar() else Modifier).padding(end = 8.dp, bottom = 8.dp + bottomInset),
                containerColor = MapOverlay.container, contentColor = MapOverlay.content,
            ) { Icon(Icons.Default.MyLocation, "Recentrer sur ma position") }
        } else {
            // the whole route (or the live square) at once; the button above brings the camera back
            SmallFloatingActionButton(
                onClick = { following = false; MapHost.call("setFollow", "false"); MapHost.call("fit") },
                modifier = Modifier.align(Alignment.BottomEnd).then(if (full) Modifier.liftAboveSnackbar() else Modifier).padding(end = 8.dp, bottom = 8.dp + bottomInset),
                containerColor = MapOverlay.container, contentColor = MapOverlay.content,
            ) { Icon(Icons.Default.ZoomOutMap, "Vue d'ensemble") }
        }
    }
}

/**
 * The live map over the whole screen: the essentials of the recording stay on top of it (a compact strip of figures,
 * "Hors trace" when following a route) and the pause / stop buttons at the bottom. Leaving it (its button, or Back)
 * returns to the figures screen.
 */
@Composable
fun LiveFullMap(settings: Settings, l: Live, onExit: () -> Unit, onPauseResume: () -> Unit, onStop: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(l.paused, l.finished) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val elapsed = l.elapsed(now) / 1000
    Box(Modifier.fillMaxSize()) {
        LiveMap(settings, l, Modifier.fillMaxSize(), full = true, onFull = { if (!it) onExit() }, bottomInset = 88.dp)
        Column(
            Modifier.align(Alignment.TopCenter).padding(top = 92.dp, start = 12.dp, end = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            MapOverlaySurface(shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "${Fmt.clock(elapsed)} · ${Fmt.distance(l.distance)} · D+ ${Math.round(l.ascent)} m" + if (l.paused) " · en pause" else "",
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    )
                    val r = l.route
                    if (r != null && r.matched) {
                        Text(
                            "Reste ${Fmt.distance(r.remaining)}" + (r.remainingUp?.let { " · D+ ${Math.round(it)} m" } ?: ""),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            val r = l.route
            if (r != null && r.offRoute) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = RoundedCornerShape(8.dp), shadowElevation = MapOverlay.elevation,
                ) {
                    Text(
                        "Hors trace" + if (!r.distanceToRoute.isNaN()) " · à ${Fmt.distance(r.distanceToRoute)}" else "",
                        style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
        // above the navigation bar
        MapOverlaySurface(shape = RoundedCornerShape(16.dp), modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 24.dp).snackbarAnchor()) {
            Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onPauseResume, modifier = Modifier.weight(1f).height(48.dp)) {
                    Text(if (l.paused) "Reprendre" else "Pause", fontSize = 16.sp)
                }
                Button(onClick = onStop, modifier = Modifier.weight(1f).height(48.dp)) { Text("Terminer", fontSize = 16.sp) }
            }
        }
    }
}

/** Done and remaining figures while following a route. */
@Composable
fun RouteFigures(l: Live, elapsedSec: Long) {
    val r = l.route ?: return
    val now = System.currentTimeMillis()
    val avg = if (l.movingMs > 0) l.distance / (l.movingMs / 1000.0) else 0.0
    val eta = if (r.matched) RouteProgress.eta(now, r.remaining, avg) else null
    Column {
        if (r.offRoute) {
            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                Text(
                    "Hors trace" + if (!r.distanceToRoute.isNaN()) " · à ${Fmt.distance(r.distanceToRoute)}" else "",
                    color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
        StatGrid(
            listOf(
                "Fait" to "${Fmt.clock(elapsedSec)} · ${Fmt.distance(l.distance)}",
                "D+ fait" to "${Math.round(l.ascent)} m",
                "Reste" to if (r.matched) Fmt.distance(r.remaining) else "—",
                "Arrivée estimée" to (eta?.let { formatHour(it) } ?: "—"),
                "D+ restant" to (r.remainingUp?.takeIf { r.matched }?.let { "${Math.round(it)} m" } ?: "—"),
                "D− restant" to (r.remainingDown?.takeIf { r.matched }?.let { "${Math.round(it)} m" } ?: "—"),
            ),
        )
    }
}

/** The offline map of the followed route being prepared during the recording. */
@Composable
fun RoutePackProgress() {
    val packs by OfflinePacks.packs.collectAsState()
    val progress by OfflinePacks.progress.collectAsState()
    val id = Recorder.routePack ?: return
    if (packs.none { it.id == id }) return
    val pr = progress[id] ?: return
    Column(Modifier.fillMaxWidth()) {
        Text(
            pr.error?.let { "Carte hors ligne : $it" } ?: "Carte hors ligne en préparation… ${Math.round(pr.fraction * 100)} %",
            style = MaterialTheme.typography.bodySmall,
            color = if (pr.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (pr.error == null) LinearProgressIndicator(progress = { pr.fraction }, modifier = Modifier.fillMaxWidth())
    }
}
