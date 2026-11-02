package app.memento.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.memento.Settings
import app.memento.agenda.AgendaData
import app.memento.agenda.CalendarSettings
import app.memento.agenda.WidgetOptions
import app.memento.suite.SectionTitle
import app.memento.suite.SettingsCard
import app.memento.suite.SettingsHint
import app.memento.suite.SuiteTopBar
import app.memento.ui.ColorDot
import app.memento.ui.MementoTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Configuration of an agenda widget: calendars and layers, days ahead, finished entries, ⟳ button, opacity. */
class WidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val id = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // leaving without saving must not leave a widget behind
        setResult(Activity.RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        val theme = Settings(this).theme
        setContent { MementoTheme(theme) { Config(id) } }
    }

    private fun save(id: Int, o: WidgetOptions) {
        AgendaWidgets.saveOptions(this, id, o)
        AgendaWidgets.updateAll(this)
        setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        finish()
    }

    @Composable
    private fun Config(id: Int) {
        var o by remember { mutableStateOf(AgendaWidgets.options(this, id)) }
        val settings by produceState<CalendarSettings?>(null) {
            value = withContext(Dispatchers.IO) { AgendaData.load(this@WidgetConfigActivity)?.settings ?: CalendarSettings() }
        }
        Scaffold(topBar = { SuiteTopBar("Widget Agenda") }) { p ->
            Column(Modifier.padding(p).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                SectionTitle("Contenu")
                SettingsCard {
                    Text("Agendas", fontWeight = FontWeight.SemiBold)
                    val cals = settings?.calendars.orEmpty()
                    if (settings == null) SettingsHint("Lecture des agendas…")
                    cals.forEach { c ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(c.id !in o.hiddenCalendars, { v ->
                                o = o.copy(hiddenCalendars = if (v) o.hiddenCalendars - c.id else o.hiddenCalendars + c.id)
                            })
                            ColorDot(c.color, Modifier.size(12.dp))
                            Text(c.name, Modifier.padding(start = 8.dp))
                        }
                    }
                    Text("Calques", fontWeight = FontWeight.SemiBold)
                    LayerRow("Activités", o.showActivities) { o = o.copy(showActivities = it) }
                    LayerRow("Notes datées", o.showNotes) { o = o.copy(showNotes = it) }
                    LayerRow("Anniversaires", o.showBirthdays) { o = o.copy(showBirthdays = it) }
                }
                SectionTitle("Affichage")
                SettingsCard {
                    Text("Jours affichés", fontWeight = FontWeight.SemiBold)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WidgetOptions.DAY_CHOICES.forEach { d -> FilterChip(o.days == d, { o = o.copy(days = d) }, { Text("$d jours") }) }
                    }
                    SwitchRow("Garder les événements terminés du jour", o.showFinished) { o = o.copy(showFinished = it) }
                    SwitchRow("Bouton d'actualisation", o.showRefresh) { o = o.copy(showRefresh = it) }
                    Text("Opacité du fond : ${o.opacity} %", fontWeight = FontWeight.SemiBold)
                    Slider(o.opacity.toFloat(), { o = o.copy(opacity = it.toInt()) }, valueRange = 0f..100f, steps = 9)
                    SettingsHint("Le widget suit le thème clair ou sombre du téléphone.")
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { finish() }) { Text("Annuler") }
                    Button(onClick = { save(id, o) }) { Text("Enregistrer") }
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }

    @Composable
    private fun LayerRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked, onChange)
            Text(label)
        }
    }

    @Composable
    private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f))
            Switch(checked, onChange)
        }
    }
}
