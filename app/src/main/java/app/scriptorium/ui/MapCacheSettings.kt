package app.scriptorium.ui

import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import app.scriptorium.MainViewModel
import app.scriptorium.Snack
import app.scriptorium.map.OfflinePacks
import app.scriptorium.map.TileServer
import app.scriptorium.suite.SectionTitle
import app.scriptorium.suite.SettingsCard
import app.scriptorium.suite.SettingsHint
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
        SettingsHint("Le cache garde les cartes déjà vues (300 Mo au plus). Les parcours préparés sont supprimés d'eux-mêmes après 24 h.")
        OutlinedButton(onClick = { confirm = true }) { Text("Vider le cache des cartes") }
    }

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
                        vm.snack = Snack(
                            if (kept.isEmpty()) "Cache des cartes vidé"
                            else "Cache des cartes vidé, sauf « ${kept.joinToString(" », « ") { it.name }} » utilisé par l'enregistrement en cours",
                        )
                    }
                }) { Text("Vider") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Annuler") } },
        )
    }
}

private fun mb(b: Long) = (b + 500_000) / 1_000_000
