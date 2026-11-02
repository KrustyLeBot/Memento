package app.scriptorium.ui

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.scriptorium.MainViewModel
import app.scriptorium.activity.ActivityDetail
import app.scriptorium.activity.Fmt
import app.scriptorium.activity.Sport
import app.scriptorium.isImage
import app.scriptorium.suite.ConfirmDialog
import app.scriptorium.suite.SuiteTopBar

@Composable
fun ActivityDetailScreen(vm: MainViewModel, detail: ActivityDetail) {
    val note = detail.note
    val info = note.activity
    var editing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var fullMap by remember { mutableStateOf(false) }
    var skiMap by remember { mutableStateOf(SkiMap.BOTH) }
    var distanceDialog by remember { mutableStateOf(false) }
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.activities.addPhoto(note, uri)
    }
    val analysis = detail.analysis
    val ski = detail.sport == Sport.SKI
    val hasMap = (analysis?.samples?.size ?: 0) >= 2
    ActivityMapContent(detail, skiMap)

    if (fullMap && hasMap) {
        ActivityMapView(vm.settings, detail, full = true, onFull = { fullMap = it }, modifier = Modifier.fillMaxSize())
        return
    }

    Scaffold(
        topBar = {
            SuiteTopBar(note.title.ifBlank { "Activité" }, onBack = { vm.back() }) {
                IconButton(onClick = { editing = true }) { Icon(Icons.Default.Edit, "Modifier") }
                IconButton(onClick = { deleting = true }) { Icon(Icons.Default.Delete, "Supprimer") }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            if (info != null) {
                Text(
                    sportEmoji(info.sport) + " " + info.sport.label + " · " + formatDate(info.start),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            if (note.body.isNotBlank()) {
                Text(note.body, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            }

            when {
                detail.error != null -> Section("Fichier") { Text(detail.error, color = MaterialTheme.colorScheme.error) }
                info?.file != null && analysis == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }

            if (analysis != null && hasMap) {
                ActivityMapView(
                    vm.settings, detail, full = false, onFull = { fullMap = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(300.dp).clip(RoundedCornerShape(16.dp)),
                )
                if (ski) {
                    // Scrolls rather than squeezing the last chip on narrow phones.
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = skiMap == SkiMap.BOTH, onClick = { skiMap = SkiMap.BOTH }, label = { Text("Descentes + montées") })
                        FilterChip(selected = skiMap == SkiMap.DOWN, onClick = { skiMap = SkiMap.DOWN }, label = { Text("Descentes") })
                        FilterChip(selected = skiMap == SkiMap.ALL, onClick = { skiMap = SkiMap.ALL }, label = { Text("Tout") })
                    }
                }
            }

            if (info != null) {
                Section("Résumé") {
                    val items = ArrayList<Pair<String, String>>()
                    val moving = info.moving
                    if (info.distance > 0 || info.sport.hasGps) items.add("Distance" to Fmt.distance(info.distance))
                    items.add("Durée" to Fmt.duration(info.elapsed))
                    if (info.sport.hasGps) {
                        items.add("En mouvement" to Fmt.duration(moving))
                        if (moving > 0 && info.distance > 0) {
                            items.add((if (info.sport == Sport.WALK) "Allure moyenne" else "Vitesse moyenne") to Fmt.speedFor(info.sport, info.distance / moving))
                        }
                        info.vmax?.let { if (!ski) items.add((if (info.sport == Sport.WALK) "Allure max" else "Vitesse max") to Fmt.speedFor(info.sport, it)) }
                        if (analysis?.quality?.hasAltitude != false && !ski) {
                            items.add("Dénivelé +" to "${info.ascent} m")
                            items.add("Dénivelé −" to "${info.descent} m")
                        }
                    } else if (info.distance > 0 && info.elapsed > 0) {
                        items.add("Vitesse moyenne" to Fmt.speed(info.distance / info.elapsed))
                    }
                    info.hr?.let { items.add("FC moyenne" to "$it bpm") }
                    analysis?.stats?.hrMax?.let { items.add("FC max" to "$it bpm") }
                    StatGrid(items)
                    if (info.file == null) {
                        TextButton(onClick = { distanceDialog = true }) { Text(if (info.distance > 0) "Modifier la distance" else "Ajouter une distance") }
                    }
                }
            }

            if (analysis != null && ski) {
                val st = analysis.stats
                Section("Ski") {
                    if (st.runs == 0) {
                        Text("Aucune descente détectée.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        StatGrid(
                            listOf(
                                "Descentes" to "${st.runs}",
                                "Dénivelé descendu" to if (analysis.quality.hasAltitude) "${Math.round(st.runDrop)} m" else "–",
                                "Vitesse max" to Fmt.speed(st.runMaxSpeed),
                                "Vitesse moyenne" to Fmt.speed(st.runAvgSpeed),
                                "Plus longue" to Fmt.distance(st.runLongest),
                                "Montées" to if (analysis.quality.hasAltitude) "${st.climbs} · +${Math.round(st.climbGain)} m" else "–",
                            ),
                        )
                        Text("Sur la carte : descentes du violet au rouge selon la vitesse, montées en bleu.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            if (analysis != null) {
                val stops = (analysis.pauses + analysis.stops).sortedBy { it.start }
                if (stops.isNotEmpty()) {
                    Section("Pauses et arrêts") {
                        stops.forEach { s ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(if (s.manual) "Pause" else "Arrêt détecté")
                                Text("${Fmt.duration(s.durationMs / 1000)} · ${formatHour(s.start)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }

                val idx = remember(analysis) { thin(analysis.samples.size) }
                if (analysis.quality.hasAltitude && idx.size >= 2) {
                    val alts = idx.map { analysis.samples[it].alt }
                    if (alts.all { it != null }) {
                        ProfileChart(
                            "Altitude", DoubleArray(idx.size) { analysis.samples[idx[it]].dist }, DoubleArray(idx.size) { alts[it]!! },
                            "${Math.round(analysis.stats.minAlt ?: 0.0)} m", "${Math.round(analysis.stats.maxAlt ?: 0.0)} m", MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
                if (idx.size >= 2 && analysis.stats.maxSpeed > 0) {
                    ProfileChart(
                        if (detail.sport == Sport.WALK) "Allure" else "Vitesse",
                        DoubleArray(idx.size) { analysis.samples[idx[it]].dist }, DoubleArray(idx.size) { analysis.samples[idx[it]].speed * 3.6 },
                        "0 km/h", Fmt.speed(analysis.stats.maxSpeed), MaterialTheme.colorScheme.primary,
                    )
                }
                if (analysis.quality.hasHr) {
                    val hrIdx = idx.filter { analysis.samples[it].hr != null }
                    if (hrIdx.size >= 2) {
                        ProfileChart(
                            "Fréquence cardiaque", DoubleArray(hrIdx.size) { analysis.samples[hrIdx[it]].dist }, DoubleArray(hrIdx.size) { analysis.samples[hrIdx[it]].hr!!.toDouble() },
                            "${analysis.samples.mapNotNull { it.hr }.minOrNull() ?: 0} bpm", "${analysis.stats.hrMax ?: 0} bpm", Color(0xFFFF1744),
                        )
                    }
                }
            }

            if (analysis != null && !analysis.quality.hasAltitude && info?.sport?.hasGps == true && info.alt == null) {
                Section("Altitude") {
                    Text("Ce fichier n'a pas d'altitude : le dénivelé et les descentes de ski sont approximatifs.", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { vm.activities.fetchAltitude(note) }, enabled = vm.activities.reliefProgress == null) {
                        Text(vm.activities.reliefProgress ?: "Compléter avec le relief (en ligne)")
                    }
                    Text("Les positions sont envoyées à opentopodata.org ; le résultat est gardé dans un petit fichier .alt.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Section("Photos") {
                val photos = note.attachments.filter { isImage(it) }
                if (photos.isNotEmpty()) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        photos.forEach { p -> PhotoTile(vm, p) { vm.activities.removePhoto(note, p) } }
                    }
                }
                TextButton(onClick = { photo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                    Icon(Icons.Default.AddAPhoto, null, Modifier.size(18.dp))
                    Text("  Ajouter une photo")
                }
            }

            if (analysis != null) {
                val q = analysis.quality
                Section("Qualité des données") {
                    Text(
                        "${q.points} points" + (if (q.dropped > 0) " (${q.dropped} écartés)" else "") +
                            " · un point toutes les ${Math.round(q.medianIntervalSec)} s\n" +
                            "Altitude : " + (if (q.hasAltitude) (if (info?.alt == app.scriptorium.activity.ActivityInfo.ALT_REFINED) "baromètre corrigé avec le relief" else if (info?.alt != null) "calculée d'après le relief" else "oui") else "absente du fichier") +
                            " · Fréquence cardiaque : " + (if (q.hasHr) "oui" else "non"),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Box(Modifier.height(24.dp))
        }
    }

    if (editing && info != null) {
        var title by remember { mutableStateOf(note.title) }
        var description by remember { mutableStateOf(note.body) }
        var sport by remember { mutableStateOf(info.sport) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("Modifier l'activité") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(title, { title = it }, label = { Text("Nom") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(description, { description = it }, label = { Text("Description") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                    SportChips(sport, if (info.file != null) listOf(Sport.WALK, Sport.BIKE, Sport.EBIKE, Sport.SKI, Sport.GPS) else listOf(Sport.PLAIN)) { sport = it }
                }
            },
            confirmButton = { TextButton(onClick = { editing = false; vm.activities.update(note, title, description, sport) }) { Text("Enregistrer") } },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("Annuler") } },
        )
    }
    if (deleting) {
        ConfirmDialog(
            title = "Supprimer cette activité ?", text = "Le fichier du tracé et les photos sont supprimés aussi.",
            confirm = "Supprimer", onConfirm = { vm.activities.delete(note); vm.back() }, onDismiss = { deleting = false }, destructive = true,
        )
    }
    if (distanceDialog && info != null) {
        var km by remember { mutableStateOf(if (info.distance > 0) String.format(java.util.Locale.ROOT, "%.2f", info.distance / 1000) else "") }
        AlertDialog(
            onDismissRequest = { distanceDialog = false },
            title = { Text("Distance (km)") },
            text = { OutlinedTextField(km, { km = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton(onClick = {
                    distanceDialog = false
                    km.replace(',', '.').toDoubleOrNull()?.let { vm.activities.setManualDistance(note, it * 1000) }
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { distanceDialog = false }) { Text("Annuler") } },
        )
    }
}

@Composable
private fun PhotoTile(vm: MainViewModel, path: String, onRemove: () -> Unit) {
    val px = with(LocalDensity.current) { 140.dp.roundToPx() }
    var thumb by remember(path) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(path) { thumb = runCatching { vm.thumbnail(path, px) }.getOrNull() }
    Box(Modifier.size(140.dp).clip(RoundedCornerShape(12.dp)).clickable { vm.openFile(path) }) {
        thumb?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.size(140.dp)) }
        Surface(
            shape = CircleShape, color = Color.Black.copy(alpha = 0.55f), contentColor = Color.White,
            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(24.dp).clip(CircleShape).clickable(onClick = onRemove),
        ) { Icon(Icons.Default.Close, "Retirer la photo", Modifier.padding(4.dp)) }
    }
}
