package app.scriptorium.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import app.scriptorium.Settings
import app.scriptorium.activity.Fmt
import app.scriptorium.map.Json
import app.scriptorium.map.MapHost
import app.scriptorium.map.MapPayload
import app.scriptorium.map.MapSquare
import app.scriptorium.map.NetState
import app.scriptorium.map.OfflinePacks
import app.scriptorium.map.Pack
import app.scriptorium.map.RouteProgress
import app.scriptorium.record.Live
import app.scriptorium.record.Recorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The live map, shown in 3D from a covering pack or online, otherwise in 2D: position, trail by speed, the route in white. */
@Composable
fun LiveMap(settings: Settings, l: Live, modifier: Modifier = Modifier) {
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
                MapHost.call("setLiveSquare", "5")
            }
            MapHost.call("setFollow", "true")
            following = true
        }
        pushAll()
    }
    LaunchedEffect(is3d) { MapHost.call("setMode", Json.quote(if (is3d) "3d" else "2d")) }
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
        MapModeSwitch(is3d, enabled = !forced2d, modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {
            pref3d = it
            settings.map3d = it
        }
        if (forced2d) OfflineBadge(Modifier.align(Alignment.BottomStart).padding(8.dp))
        if (!following) {
            SmallFloatingActionButton(
                onClick = { following = true; MapHost.call("setFollow", "true") },
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                containerColor = MapOverlay.container, contentColor = MapOverlay.content,
            ) { Icon(Icons.Default.MyLocation, "Recentrer sur ma position") }
        } else {
            // the whole route (or the live square) at once; the button above brings the camera back
            SmallFloatingActionButton(
                onClick = { following = false; MapHost.call("setFollow", "false"); MapHost.call("fit") },
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                containerColor = MapOverlay.container, contentColor = MapOverlay.content,
            ) { Icon(Icons.Default.ZoomOutMap, "Vue d'ensemble") }
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
