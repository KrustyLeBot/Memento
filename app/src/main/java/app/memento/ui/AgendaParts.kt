package app.memento.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.memento.agenda.AgendaText
import app.memento.agenda.CalendarSettings
import app.memento.agenda.Entry
import app.memento.agenda.EntryKind
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

private val ICS_TYPES = setOf("text/calendar", "text/x-vcalendar", "application/ics")

/** The `.ics` file(s) another app asks to open or shares with this one, if [intent] is that. */
fun calendarUris(intent: android.content.Intent?): List<android.net.Uri> {
    intent ?: return emptyList()
    val type = intent.type?.lowercase()?.substringBefore(';')?.trim()
    return when (intent.action) {
        android.content.Intent.ACTION_VIEW -> listOfNotNull(intent.data?.takeIf {
            type in ICS_TYPES || it.lastPathSegment?.endsWith(".ics", ignoreCase = true) == true
        })
        android.content.Intent.ACTION_SEND -> if (type in ICS_TYPES) listOfNotNull(
            androidx.core.content.IntentCompat.getParcelableExtra(intent, android.content.Intent.EXTRA_STREAM, android.net.Uri::class.java),
        ) else emptyList()
        android.content.Intent.ACTION_SEND_MULTIPLE -> if (type in ICS_TYPES) {
            androidx.core.content.IntentCompat.getParcelableArrayListExtra(intent, android.content.Intent.EXTRA_STREAM, android.net.Uri::class.java).orEmpty()
        } else emptyList()
        else -> emptyList()
    }
}

/** "#RRGGBB" of the calendar files as a Compose colour (grey when unreadable). */
fun agendaColor(hex: String?): Color = try {
    Color(android.graphics.Color.parseColor(hex ?: "#78858F"))
} catch (_: IllegalArgumentException) {
    Color(0xFF78858F)
}

/** The calendar's colour as the background of white text (darkened when needed, see [app.memento.agenda.ChipColor]). */
fun agendaChipColor(hex: String?): Color = Color(app.memento.agenda.ChipColor.behindWhiteText(agendaColor(hex).toArgb()))

/** The system's 12/24 h choice. */
@Composable
fun is24h(): Boolean = android.text.format.DateFormat.is24HourFormat(LocalContext.current)

@Composable
fun ColorDot(hex: String?, modifier: Modifier = Modifier.size(12.dp)) {
    Box(modifier.clip(CircleShape).background(agendaColor(hex)))
}

/** Sport emoji, 📝 or 🎂 before the title of an entry of a layer. */
fun entryIcon(e: Entry): String? = when (e.kind) {
    EntryKind.ACTIVITY -> e.sport?.let { sportEmoji(app.memento.activity.Sport.of(it)) }
    EntryKind.NOTE -> "📝"
    EntryKind.BIRTHDAY -> "🎂"
    EntryKind.EVENT -> null
}

fun entryTitle(e: Entry): String = listOfNotNull(entryIcon(e), e.title.ifBlank { "(Sans titre)" }).joinToString(" ")

/** A filled block in the entry's colour: title, then the time line and the second line (place, "3/5"…). */
@Composable
fun EntryChip(e: Entry, time: String?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val bg = agendaChipColor(e.color)
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(bg).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(entryTitle(e), color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val second = listOfNotNull(time, e.subtitle?.ifBlank { null }).joinToString(" · ")
        if (second.isNotEmpty()) {
            Text(second, color = Color.White.copy(alpha = 0.88f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The fixed palette of the calendars, as round swatches. */
@Composable
fun PalettePicker(selected: String?, onPick: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CalendarSettings.PALETTE.chunked(6).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (hex, name) ->
                    val on = hex.equals(selected, ignoreCase = true)
                    Box(
                        Modifier.size(36.dp).clip(CircleShape).background(agendaColor(hex))
                            .border(if (on) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                            .clickable { onPick(hex) },
                        contentAlignment = Alignment.Center,
                    ) { if (on) Icon(Icons.Default.Check, name, tint = Color.White) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgendaDatePicker(initial: LocalDate, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onPick(java.time.Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                onDismiss()
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    ) { DatePicker(state) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgendaTimePicker(initial: LocalTime, onDismiss: () -> Unit, onPick: (LocalTime) -> Unit) {
    val state = rememberTimePickerState(initial.hour, initial.minute, is24h())
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onPick(LocalTime.of(state.hour, state.minute)); onDismiss() }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
        text = { TimePicker(state) },
    )
}

/** Left column of a day in the agenda list: "mer." over "11", today in a filled circle. */
@Composable
fun DayBadge(d: LocalDate, today: LocalDate, modifier: Modifier = Modifier) {
    Column(modifier.width(48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        val isToday = d == today
        Text(AgendaText.weekdayShort(d.dayOfWeek), style = MaterialTheme.typography.labelMedium,
            color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(if (isToday) MaterialTheme.colorScheme.primary else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Text(d.dayOfMonth.toString(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                color = if (isToday) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
        }
    }
}
