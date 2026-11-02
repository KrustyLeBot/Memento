package app.scriptorium.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.scriptorium.MainViewModel
import app.scriptorium.Screen
import app.scriptorium.agenda.AgendaText
import app.scriptorium.agenda.CalendarSettings
import app.scriptorium.reminders.Reminders
import app.scriptorium.suite.SectionTitle
import app.scriptorium.suite.SettingsCard
import app.scriptorium.suite.SettingsHint
import java.time.DayOfWeek

/** "Agenda" section of the settings: display, defaults of new events, reminders' access and channel, widgets. */
@Composable
fun AgendaSettings(vm: MainViewModel) {
    val c = vm.agenda
    val context = LocalContext.current
    val settings by vm.repo.calendars.collectAsState()
    val h24 = is24h()
    var resumed by remember { mutableStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumed++
        onPauseOrDispose { }
    }
    var addAlert by remember { mutableStateOf<Boolean?>(null) } // all-day?

    SectionTitle("Agenda")
    SettingsCard {
        Text("La semaine commence le", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(DayOfWeek.MONDAY, DayOfWeek.SUNDAY, DayOfWeek.SATURDAY).forEach { d ->
                FilterChip(c.weekStart == d, { c.changeWeekStart(d) }, { Text(AgendaText.weekday(d)) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Heures dans le fuseau de l'événement", fontWeight = FontWeight.SemiBold)
                SettingsHint("En voyage, un rendez-vous « 14:30 à Paris » s'affiche 14:30 au lieu de l'heure locale.")
            }
            Switch(c.eventZoneTimes, { c.changeEventZoneTimes(it) })
        }
        Text("Agenda par défaut", fontWeight = FontWeight.SemiBold)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            settings.calendars.forEach { k ->
                FilterChip(settings.defaultCalendar().id == k.id, { c.updateCalendars { it.copy(default = k.id) } },
                    { Text(k.name) }, leadingIcon = { ColorDot(k.color) })
            }
        }
        listOf(false to "Rappels par défaut", true to "Rappels par défaut (journée entière)").forEach { (allDay, label) ->
            Text(label, fontWeight = FontWeight.SemiBold)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                val list = settings.defaultAlerts(allDay)
                list.forEach { a ->
                    InputChip(true, { c.updateCalendars { it.withDefaultAlerts(allDay, list - a) } }, { Text(AgendaText.alertLabel(a, allDay, h24)) },
                        trailingIcon = { Icon(Icons.Default.Close, "Retirer") })
                }
                FilterChip(false, { addAlert = allDay }, { Text("Ajouter") }, leadingIcon = { Icon(Icons.Default.Add, null) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Anniversaires des contacts", fontWeight = FontWeight.SemiBold)
                SettingsHint("Chaque contact avec une date de naissance, tous les ans.")
            }
            Switch(settings.layer(CalendarSettings.LAYER_BIRTHDAYS).visible, { v ->
                c.updateCalendars { it.withLayer(CalendarSettings.LAYER_BIRTHDAYS) { l -> l.copy(visible = v) } }
            })
        }
        OutlinedButton(onClick = { vm.calendarsReturn = Screen.SETTINGS; vm.screen = Screen.AGENDA_CALENDARS }) { Text("Agendas et calques…") }
        SettingsHint("Agendas, couleurs, agenda et rappels par défaut sont synchronisés avec le PC (agenda/calendars.json).")
    }

    SettingsCard {
        @Suppress("UNUSED_EXPRESSION") resumed
        val notifications = Reminders.notificationsAllowed(context)
        val exact = Reminders.canExact(context)
        val battery = Reminders.ignoresBatteryOptimizations(context)
        Text("Rappels", fontWeight = FontWeight.SemiBold)
        SettingsHint(if (notifications) "Notifications autorisées." else "Notifications désactivées pour l'app : aucun rappel ne s'affichera.",
            if (notifications) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = { Reminders.openChannelSettings(context) }) { Text("Canal « Rappels d'agenda » (son, priorité)") }
        SettingsHint(if (exact) "Alarmes exactes : autorisées, les rappels sonnent à la minute."
            else "Alarmes exactes : refusées, les rappels peuvent arriver avec quelques minutes de retard.",
            if (exact) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            OutlinedButton(onClick = { context.startActivity(Reminders.exactAlarmSettings(context)) }) { Text("Alarmes et rappels") }
        }
        SettingsHint(if (battery) "Optimisation de la batterie : désactivée pour l'app."
            else "Sur Samsung, l'optimisation de la batterie peut retarder ou bloquer les rappels : désactive-la pour l'app.")
        if (!battery) OutlinedButton(onClick = { context.startActivity(Reminders.batteryExemptionIntent(context)) }) { Text("Ne pas optimiser la batterie") }
        SettingsHint("Widgets : appui long sur l'écran d'accueil → Widgets → Scriptorium (« Agenda » et « Aujourd'hui »).")
    }

    addAlert?.let { allDay ->
        AlertPickerDialog(allDay, h24, { addAlert = null }) { a ->
            val list = settings.defaultAlerts(allDay)
            if (a !in list) c.updateCalendars { it.withDefaultAlerts(allDay, list + a) }
        }
    }
}
