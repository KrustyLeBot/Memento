package app.memento.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import app.memento.MainViewModel
import app.memento.Settings
import app.memento.Snack
import app.memento.Zone3dPolicy
import app.memento.map.TilePlan
import app.memento.map.TilePlans
import app.memento.map.OfflinePacks
import app.memento.map.TileServer
import app.memento.suite.SectionTitle
import app.memento.suite.SettingsCard
import app.memento.suite.SettingsHint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Map storage in use, measured on disk. */
private class MapUsage(val cacheBytes: Long, val packs: Int, val packBytes: Long)

/** "Cartes": what the browsing cache and the offline packs take, and the button that empties both. */
@Composable
fun MapCacheSettings(vm: MainViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val packs by OfflinePacks.packs.collectAsState()
    var usage by remember { mutableStateOf<MapUsage?>(null) }
    var cleared by remember { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(packs, cleared) {
        usage = withContext(Dispatchers.IO) {
            TileServer.init(context)
            OfflinePacks.init(context)
            val (n, bytes) = OfflinePacks.diskUsage()
            MapUsage(TileServer.cache.sizeBytes(), n, bytes)
        }
    }

    SectionTitle("Cartes")
    SettingsCard {
        val u = usage
        Text(
            if (u == null) "Calcul de la place utilisée…"
            else "Cache de navigation : ${mb(u.cacheBytes)} Mo · Parcours préparés : ${u.packs} (${mb(u.packBytes)} Mo)",
            fontWeight = FontWeight.SemiBold,
        )
        SettingsHint("Le cache garde les cartes déjà vues (300 Mo au plus). Les parcours préparés et les zones autour du départ sont supprimés d'eux-mêmes après 24 h.")
        OutlinedButton(onClick = { confirm = true }) { Text("Vider le cache des cartes") }
    }
    StartZoneSettings(vm.settings)

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Vider le cache des cartes ?") },
            text = {
                Text(
                    "Les cartes déjà vues et tous les parcours préparés (et zones autour du départ) sont effacés du téléphone. " +
                        "Une carte utilisée par l'enregistrement en cours est gardée.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    scope.launch {
                        val kept = withContext(Dispatchers.IO) {
                            TileServer.cache.clear()
                            OfflinePacks.clearAll()
                        }
                        cleared++
                        vm.say(Snack(
                            if (kept.isEmpty()) "Cache des cartes vidé"
                            else "Cache des cartes vidé, sauf « ${kept.joinToString(" », « ") { it.name }} » utilisé par l'enregistrement en cours",
                        ))
                    }
                }) { Text("Vider") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Annuler") } },
        )
    }
}

/** The offline zone downloaded around the start of a recording without route: the 3D question and both sizes. */
@Composable
private fun StartZoneSettings(settings: Settings) {
    var policy by remember { mutableStateOf(settings.zone3d) }
    var side2d by remember { mutableIntStateOf(settings.zone2dKm) }
    var side3d by remember { mutableIntStateOf(settings.zone3dKm) }
    val lat = remember { settings.lastStartLat }
    val plan2d = remember(side2d) { TilePlans.forZone(0.0, lat, side2d) }
    val plan3d = remember(side2d, side3d) { TilePlans.forZone3d(0.0, lat, side2d, side3d) }
    SettingsCard {
        Text("3D hors ligne (parcours préparé, enregistrement avec ou sans parcours)", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Zone3dPolicy.values().forEach { p ->
                FilterChip(selected = policy == p, onClick = { policy = p; settings.zone3d = p }, label = { Text(p.label) })
            }
        }
        SettingsHint(
            "La carte 2D se télécharge toujours (Wi-Fi ou données mobiles) : photo jusqu'au zoom 19 à 1 km du départ ou le " +
                "long du tracé, zoom 17 un peu plus loin, moins fine ailleurs. La 3D (relief, bâtiments, sommets) suit ce choix, " +
                "pour un parcours préparé comme pour un enregistrement ; « Demander » la propose à la préparation, ou une fois " +
                "l'enregistrement lancé sans le retarder. Les deux sont gardées 24 h.",
        )
        KmSlider("Zone 2D hors ligne autour du départ", side2d, TilePlans.ZONE_2D_KM, "≈ ${mb(plan2d.estimatedBytes)} Mo", reduced(plan2d)) {
            side2d = it
            settings.zone2dKm = it
        }
        val extra = (plan3d.estimatedBytes - plan2d.estimatedBytes).coerceAtLeast(0)
        KmSlider("Zone 3D hors ligne autour du départ", side3d, TilePlans.ZONE_3D_KM, "≈ ${mb(extra)} Mo en plus de la 2D", reduced(plan3d)) {
            side3d = it
            settings.zone3dKm = it
        }
        SettingsHint("Côté du carré centré sur le départ. Estimations pour une photo aérienne moyenne ; une ville pèse plus.")
    }
}

/** The estimate gave way to [TilePlans.ZONE_BUDGET]: the zone is downloaded with a coarser photo. */
private fun reduced(p: TilePlan) = p.orthoZooms.last < TilePlans.ZONE_ZOOMS.last || p.corridor == null

@Composable
private fun KmSlider(label: String, km: Int, range: IntRange, estimate: String, reduced: Boolean, onChange: (Int) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("$label : $km km", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(estimate, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            km.toFloat(), { onChange(TilePlans.snapKm(it.toInt(), range)) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first) / TilePlans.ZONE_KM_STEP - 1,
        )
        if (reduced) SettingsHint("Plus de ${mb(TilePlans.ZONE_BUDGET)} Mo : photo moins fine pour tenir", MaterialTheme.colorScheme.error)
    }
}

private fun mb(b: Long) = (b + 500_000) / 1_000_000
