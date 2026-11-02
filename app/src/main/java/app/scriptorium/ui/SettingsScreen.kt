package app.scriptorium.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.scriptorium.BuildConfig
import app.scriptorium.MainViewModel
import app.scriptorium.Settings
import app.scriptorium.StorageConfig
import app.scriptorium.ThemeMode
import app.scriptorium.suite.AboutSettings
import app.scriptorium.suite.SectionTitle
import app.scriptorium.suite.SettingsCard
import app.scriptorium.suite.SettingsHint
import app.scriptorium.suite.SuiteTopBar
import app.scriptorium.suite.SyncSettings
import app.scriptorium.suite.UpdateSettings
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.text.font.FontWeight
import app.scriptorium.scan.ScanFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel, onPickFolder: () -> Unit) {
    Scaffold(topBar = { SuiteTopBar("Paramètres") }) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            SectionTitle("Stockage")
            SettingsCard {
                Text("Dossier des notes", fontWeight = FontWeight.SemiBold)
                SettingsHint(vm.storageLabel.ifEmpty { "—" })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onPickFolder) { Text("Choisir un dossier") }
                    if (vm.settings.storage !is StorageConfig.Default) {
                        TextButton(onClick = { vm.requestStorageChange(StorageConfig.Default) }) { Text("Dossier par défaut") }
                    }
                }
            }

            if (vm.legacyFolderLeft) {
                var confirm by remember { mutableStateOf(false) }
                SettingsCard {
                    Text("Ancien dossier Griffonote", fontWeight = FontWeight.SemiBold)
                    SettingsHint("Documents/Griffonote est toujours là ; tout son contenu a été copié ici. Change d'abord le dossier dans Synchrotron, sinon la suppression partira aussi dans le cloud.")
                    OutlinedButton(onClick = { confirm = true }) { Text("Supprimer l'ancien dossier") }
                }
                if (confirm) {
                    AlertDialog(
                        onDismissRequest = { confirm = false },
                        title = { Text("Supprimer Documents/Griffonote ?") },
                        text = { Text("Synchrotron ne doit plus synchroniser ce dossier. La suppression est définitive.") },
                        confirmButton = { TextButton(onClick = { confirm = false; vm.deleteLegacyFolder() }) { Text("Supprimer") } },
                        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Annuler") } },
                    )
                }
            }

            SectionTitle("Modules")
            SettingsCard {
                SettingsHint("Les modules masqués disparaissent de la barre du bas ; leurs fichiers restent dans le dossier.")
                app.scriptorium.Module.hideable.forEach { m ->
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(m.label, modifier = Modifier.weight(1f))
                        androidx.compose.material3.Switch(checked = m !in vm.hiddenModules, onCheckedChange = { vm.setModuleVisible(m, it) })
                    }
                }
            }

            SectionTitle("Affichage")
            SettingsCard {
                Text("Présentation", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = vm.gridView, onClick = { vm.changeGridView(true) }, label = { Text("Grille") })
                    FilterChip(selected = !vm.gridView, onClick = { vm.changeGridView(false) }, label = { Text("Liste") })
                }
                Text("Thème", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(vm.theme == ThemeMode.SYSTEM, { vm.changeTheme(ThemeMode.SYSTEM) }, { Text("Système") })
                    FilterChip(vm.theme == ThemeMode.LIGHT, { vm.changeTheme(ThemeMode.LIGHT) }, { Text("Clair") })
                    FilterChip(vm.theme == ThemeMode.DARK, { vm.changeTheme(ThemeMode.DARK) }, { Text("Sombre") })
                }
            }

            SectionTitle("Scans")
            SettingsCard {
                Text("Format des pages", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ScanFormat.entries.forEach { f ->
                        FilterChip(vm.scanFormat == f, { vm.changeScanFormat(f) }, { Text(f.label) })
                    }
                }
                SettingsHint("Automatique : A4 ou US Letter selon la forme de la page.")
            }

            SectionTitle("Activités")
            SettingsCard {
                val cfg by vm.repo.activitySettings.collectAsState()
                var mode by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(vm.settings.gpsMode) }
                Text("Précision du GPS à l'enregistrement", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    app.scriptorium.GpsMode.values().forEach { m ->
                        FilterChip(mode == m, { mode = m; vm.settings.gpsMode = m }, { Text(m.label) })
                    }
                }
                SettingsHint("Normale : un point toutes les 2 à 5 s selon le sport. Économie : un point toutes les 12 s, la batterie dure plus.")
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Affiner l'altitude avec le relief à l'enregistrement", fontWeight = FontWeight.SemiBold)
                        SettingsHint("À la sauvegarde, corrige la dérive du baromètre avec le modèle de relief (envoie les positions à opentopodata.org, quelques dizaines de secondes). Synchronisé avec le PC.")
                    }
                    androidx.compose.material3.Switch(cfg.refineAltitude, { vm.changeActivitySettings(cfg.copy(refineAltitude = it)) })
                }
                Text("Sports comptés dans les totaux", fontWeight = FontWeight.SemiBold)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    app.scriptorium.activity.Sport.values().forEach { s ->
                        FilterChip(
                            s in cfg.totals,
                            { vm.changeActivitySettings(cfg.copy(totals = if (s in cfg.totals) cfg.totals - s else cfg.totals + s)) },
                            { Text(sportEmoji(s) + " " + s.label.substringBefore(" (")) },
                        )
                    }
                }
                SettingsHint("Distance, durée et dénivelé de l'écran Activités. Réglage synchronisé avec le PC.")
            }

            if (vm.settings.storage is StorageConfig.Default) SyncSettings(Settings.defaultDir().path)
            UpdateSettings(vm.updates)
            AboutSettings("Scriptorium ${BuildConfig.VERSION_NAME}", "Des notes en Markdown, une par fichier, lisibles par n'importe quel éditeur.")
            Spacer(Modifier.height(32.dp))
        }
    }

    vm.pendingStorage?.let { target ->
        AlertDialog(
            onDismissRequest = { vm.pendingStorage = null },
            title = { Text("Changer de dossier") },
            text = {
                Text(
                    "Déplacer les notes existantes vers " +
                        (if (target is StorageConfig.Default) "le dossier par défaut" else "le nouveau dossier") +
                        " ou repartir de zéro ? Dans ce cas, les anciens fichiers restent où ils sont.",
                )
            },
            confirmButton = { TextButton(onClick = { vm.confirmStorageChange(move = true) }) { Text("Déplacer") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { vm.pendingStorage = null }) { Text("Annuler") }
                    TextButton(onClick = { vm.confirmStorageChange(move = false) }) { Text("Repartir de zéro") }
                }
            },
        )
    }
}

