package app.scriptorium.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import app.scriptorium.Settings
import app.scriptorium.activity.ActivityDetail
import app.scriptorium.activity.Sport
import app.scriptorium.map.Bbox
import app.scriptorium.map.Json
import app.scriptorium.map.MapHost
import app.scriptorium.map.MapPayload
import app.scriptorium.map.MapSquare
import app.scriptorium.map.NetState
import app.scriptorium.map.OfflinePacks
import app.scriptorium.map.Pack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private class ActivityMapData(val track: String?, val lines: String, val dots: String, val square: Bbox)

/**
 * Sends an activity to the shared map: ski days with the descent/climb colouring of [mapLines], other
 * sports as one track the page colours by speed. Built off the main thread; skipped when the map already
 * shows this very content (the screen was only recreated, e.g. by a rotation).
 */
@Composable
fun ActivityMapContent(detail: ActivityDetail, skiMap: SkiMap) {
    val analysis = detail.analysis ?: return
    if (analysis.samples.size < 2) return
    val ski = detail.sport == Sport.SKI
    val key = listOf(detail.note.path, detail.note.updated, ski, skiMap, analysis.samples.size)
    LaunchedEffect(key) {
        if (MapHost.session == key) return@LaunchedEffect // recreated screen (rotation): the page already shows it
        val data = withContext(Dispatchers.Default) {
            val s = analysis.samples
            val lons = DoubleArray(s.size) { s[it].lon }
            val lats = DoubleArray(s.size) { s[it].lat }
            val lines = if (ski) MapPayload.lines(mapLines(analysis, true, skiMap).map { Triple(it.lons, it.lats, MapPayload.color(it.color.toArgb())) }) else "[]"
            val track = if (ski) null else MapPayload.track(lons, lats, DoubleArray(s.size) { s[it].speed }, analysis.stats.avgSpeed.takeIf { it > 0 })
            val dots = "[" + listOf(s.first() to "#00c853", s.last() to "#ff1744").joinToString(",") { (p, c) ->
                "{\"lon\":${Json.num(p.lon)},\"lat\":${Json.num(p.lat)},\"color\":\"$c\"}"
            } + "]"
            ActivityMapData(track, lines, dots, MapSquare.of(Bbox.of(lons, lats)!!))
        }
        if (!MapHost.begin(key)) return@LaunchedEffect
        // the square first, as on Windows: the 3D block is built once, for this square, not first for the track's
        MapHost.call("setSquare", MapPayload.bbox(data.square))
        if (data.track != null) MapHost.call("setTrack", data.track)
        MapHost.call("setLines", data.lines)
        MapHost.call("setDots", data.dots)
        MapHost.call("fit")
    }
}

/** The map of an activity, with its 3D / 2D switch and the full-screen button. */
@Composable
fun ActivityMapView(settings: Settings, detail: ActivityDetail, full: Boolean, onFull: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    var pref3d by remember { mutableStateOf(settings.map3d) }
    val online by NetState.online.collectAsState()
    val packs by OfflinePacks.packs.collectAsState()
    val start = detail.analysis?.samples?.firstOrNull()
    val forced2d = !online && (start == null || !Pack.covers3d(packs, start.lon, start.lat, System.currentTimeMillis(), OfflinePacks.pinnedIds()))
    val is3d = pref3d && !forced2d
    LaunchedEffect(is3d) { MapHost.call("setMode", Json.quote(if (is3d) "3d" else "2d")) }
    LaunchedEffect(full) { MapHost.call("fit") }

    Box(modifier) {
        SharedMap(
            Modifier.fillMaxSize(), is3d,
            onEvent = { type, e ->
                if (type == "mode" && !forced2d) {
                    val v = e["value"] == "3d"
                    if (v != pref3d) { pref3d = v; settings.map3d = v }
                }
            },
        )
        MapModeSwitch(is3d, enabled = !forced2d, modifier = Modifier.align(Alignment.TopStart).padding(if (full) 40.dp else 8.dp, if (full) 40.dp else 8.dp, 0.dp, 0.dp)) {
            pref3d = it
            settings.map3d = it
        }
        if (forced2d) OfflineBadge(Modifier.align(Alignment.BottomStart).padding(8.dp))
        Surface(
            shape = CircleShape, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
            modifier = Modifier.align(Alignment.TopEnd).padding(top = if (full) 40.dp else 8.dp, end = if (full) 12.dp else 8.dp),
        ) {
            IconButton(onClick = { onFull(!full) }) {
                if (full) Icon(Icons.Default.FullscreenExit, "Réduire la carte") else Icon(Icons.Default.Fullscreen, "Agrandir la carte")
            }
        }
    }
}

/** Says, discreetly, why the map is flat. */
@Composable
fun OfflineBadge(modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f), modifier = modifier) {
        Text(
            "Hors ligne : vue 2D", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}
