package app.memento.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.memento.Settings
import app.memento.map.MapConfig
import app.memento.map.MapHost
import app.memento.map.TileServer
import app.memento.map.Tuileries
import app.memento.suite.SettingsCard
import app.memento.suite.SettingsHint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** "Cartes": the aerial photo's top zoom and the optional Tuileries tile server (its login, "Tester la connexion"). */
@Composable
fun MapSourceSettings(settings: Settings) {
    val scope = rememberCoroutineScope()
    var zoom by remember { mutableIntStateOf(settings.photoMaxZoom) }
    val saved = remember { settings.tuileries }
    var url by remember { mutableStateOf(saved.url) }
    var user by remember { mutableStateOf(saved.user) }
    var password by remember { mutableStateOf(saved.password) }
    var cfId by remember { mutableStateOf(saved.cfId) }
    var cfSecret by remember { mutableStateOf(saved.cfSecret) }
    var status by remember { mutableStateOf("") }
    var serverZoom by remember { mutableStateOf<Int?>(null) }
    fun edited() = Tuileries(url.trim(), user.trim(), password, cfId.trim(), cfSecret.trim())

    fun apply(source: Tuileries) {
        val newSource = source != MapConfig.tuileries.orEmpty()
        val reload = newSource || zoom != MapConfig.photoMaxZoom
        settings.tuileries = source
        settings.photoMaxZoom = zoom
        MapConfig.set(source, zoom)
        if (newSource) TileServer.refreshInfo()  // its credit, for the next page load
        // the page reads the photo's zoom when it loads, and a new source's tiles show on a fresh page
        if (reload) MapHost.reloadPage()
    }

    SettingsCard {
        Column {
            Text("Zoom maximal de la photo aérienne : $zoom", style = MaterialTheme.typography.bodyMedium)
            Slider(
                zoom.toFloat(), { zoom = Tuileries.clampPhotoMaxZoom(Math.round(it)) },
                onValueChangeFinished = { apply(settings.tuileries) },
                valueRange = Tuileries.PHOTO_MAX_ZOOM_RANGE.first.toFloat()..Tuileries.PHOTO_MAX_ZOOM_RANGE.last.toFloat(),
                steps = Tuileries.PHOTO_MAX_ZOOM_RANGE.last - Tuileries.PHOTO_MAX_ZOOM_RANGE.first - 1,
            )
            SettingsHint(
                "Au-delà, la carte agrandit ce niveau (17 : 1,2 m par pixel, 19 : 30 cm). Les parcours préparés s'y arrêtent aussi." +
                    (serverZoom?.let { " Le serveur de cartes archive les photos jusqu'au zoom $it." } ?: ""),
            )
        }
    }
    SettingsCard {
        Text("Serveur de cartes (Tuileries)", fontWeight = FontWeight.SemiBold)
        SettingsHint(
            "Facultatif. Renseigné, toutes les cartes (et les parcours préparés) viennent de ce serveur, et seulement de lui ; " +
                "vide, de l'IGN, d'OpenStreetMap et d'AWS directement.",
        )
        val field = Modifier.fillMaxWidth()
        // a keyboard's automatic capital or correction changed the login ("krusty" sent as "Krusty"): refused by the server
        val plain = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii)
        OutlinedTextField(url, { url = it }, label = { Text("Adresse") }, placeholder = { Text("https://tuileries.example.org/") },
            singleLine = true, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false,
                keyboardType = KeyboardType.Uri), modifier = field)
        OutlinedTextField(user, { user = it }, label = { Text("Identifiant") }, singleLine = true, keyboardOptions = plain, modifier = field)
        OutlinedTextField(password, { password = it }, label = { Text("Mot de passe") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password), modifier = field)
        OutlinedTextField(cfId, { cfId = it }, label = { Text("Cloudflare Access : identifiant client") }, singleLine = true,
            keyboardOptions = plain, modifier = field)
        OutlinedTextField(cfSecret, { cfSecret = it }, label = { Text("Cloudflare Access : secret client") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = field)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val source = edited()
                if (source.url.isNotEmpty() && !source.configured) {
                    status = "Adresse invalide : https://… attendue"
                } else {
                    apply(source)
                    status = if (source.configured) "Enregistré : les cartes viennent de Tuileries" else "Enregistré : IGN, OpenStreetMap et AWS directement"
                }
            }) { Text("Enregistrer") }
            OutlinedButton(onClick = {
                val source = edited()
                status = "Connexion…"
                scope.launch {
                    status = try {
                        val info = withContext(Dispatchers.IO) { TileServer.tuileriesInfo(source) }
                        serverZoom = info.maxZoom
                        if (source == MapConfig.tuileries) MapConfig.attribution = info.attribution
                        info.summary()
                    } catch (e: java.io.IOException) { // TuileriesException included
                        "Échec : ${e.message}"
                    }
                }
            }) { Text("Tester la connexion") }
        }
        if (status.isNotEmpty()) SettingsHint(status)
    }
}

private fun Tuileries?.orEmpty() = this ?: Tuileries("")
