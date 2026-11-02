package app.memento.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Event
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.memento.EditorState
import app.memento.MainViewModel
import app.memento.Note
import app.memento.agenda.AgendaFormatException
import app.memento.agenda.AgendaText
import app.memento.agenda.EventFormat
import app.memento.agenda.Stamp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** The date of a regular note: its `start` (and alerts), or the old `reminder` field read as a dated note. */
data class NoteDate(val start: Stamp, val alerts: List<Int>, val problem: String? = null)

fun noteDate(n: Note?): NoteDate? {
    n ?: return null
    val e = try {
        EventFormat.fromNote(n)
    } catch (x: AgendaFormatException) {
        return NoteDate(Stamp(LocalDate.now()), emptyList(), x.message ?: "date illisible")
    }
    if (e != null) return NoteDate(e.start, e.alerts)
    val r = n.reminder ?: return null
    return NoteDate(Stamp.of(Instant.ofEpochMilli(r).atZone(ZoneId.systemDefault()).toLocalDateTime()), listOf(0))
}

/** "📅 lun. 12 nov. · 14:30 · 15 min avant" under the title of a dated note; a tap edits it. */
@Composable
fun NoteDateChip(ed: EditorState, fg: Color, onClick: () -> Unit) {
    @Suppress("UNUSED_EXPRESSION") ed.dateVersion
    val d = noteDate(ed.base) ?: return
    val h24 = is24h()
    val text = listOfNotNull(
        "${AgendaText.weekdayShort(d.start.date.dayOfWeek)} ${AgendaText.shortDate(d.start.date, LocalDate.now().year)}",
        d.start.time?.let { AgendaText.time(it, h24) },
        d.alerts.minOrNull()?.let { AgendaText.alertLabel(it, d.start.allDay, h24) },
        d.problem,
    ).joinToString(" · ")
    AssistChip(onClick = onClick, label = { Text(text, color = fg) }, leadingIcon = { Icon(Icons.Default.Event, null, tint = fg) },
        modifier = Modifier.padding(bottom = 4.dp))
}

/** "Ajouter à l'agenda": a date, an optional time, an optional reminder; "Retirer de l'agenda" when dated. */
@Composable
fun NoteDateDialog(vm: MainViewModel, ed: EditorState, onDismiss: () -> Unit) {
    val current = noteDate(ed.base)
    val h24 = is24h()
    var date by remember { mutableStateOf(current?.start?.date ?: LocalDate.now()) }
    var time by remember { mutableStateOf(current?.start?.time ?: LocalTime.of(9, 0)) }
    var timed by remember { mutableStateOf(current?.start?.time != null) }
    var alert by remember { mutableStateOf(current?.alerts?.minOrNull()) }
    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    val choices: List<Int?> = if (timed) listOf(null, 0, -5, -15, -30, -60, -1440) else listOf(null, 540, -360)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (current == null) "Ajouter à l'agenda" else "Date de la note") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("${AgendaText.weekday(date.dayOfWeek)} ${AgendaText.longDate(date)}", fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { pickDate = true }.padding(vertical = 10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Heure", Modifier.weight(1f))
                    Switch(timed, { timed = it; alert = null })
                }
                if (timed) Text(AgendaText.time(time, h24), color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { pickTime = true }.padding(vertical = 10.dp))
                Text("Rappel", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                (choices + listOfNotNull(alert?.takeIf { it !in choices })).forEach { a ->
                    Row(Modifier.fillMaxWidth().clickable { alert = a }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(alert == a, { alert = a })
                        Text(if (a == null) "Aucun" else AgendaText.alertLabel(a, !timed, h24))
                    }
                }
                current?.problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                vm.setNoteDate(if (timed) Stamp(date, time) else Stamp(date), listOfNotNull(alert))
            }) { Text("OK") }
        },
        dismissButton = {
            Row {
                if (current != null) TextButton(onClick = { onDismiss(); vm.setNoteDate(null, emptyList()) }) {
                    Text("Retirer de l'agenda", color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onDismiss) { Text("Annuler") }
            }
        },
    )
    if (pickDate) AgendaDatePicker(date, { pickDate = false }) { date = it }
    if (pickTime) AgendaTimePicker(time, { pickTime = false }) { time = it }
}
