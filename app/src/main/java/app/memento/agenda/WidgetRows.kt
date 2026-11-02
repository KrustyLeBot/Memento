package app.memento.agenda

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** What one agenda widget shows (its configuration screen). */
data class WidgetOptions(
    /** 7, 14, 30 or 90. */
    val days: Int = 14,
    /** Today's entries already over stay listed. */
    val showFinished: Boolean = false,
    val showRefresh: Boolean = true,
    /** 0..100, of the card's background. */
    val opacity: Int = 90,
    /** Calendars not shown (ids). */
    val hiddenCalendars: Set<String> = emptySet(),
    val showActivities: Boolean = true,
    val showNotes: Boolean = true,
    val showBirthdays: Boolean = true,
) {
    fun toJson(): String = Json.write(linkedMapOf(
        "days" to days, "show_finished" to showFinished, "show_refresh" to showRefresh, "opacity" to opacity,
        "hidden_calendars" to hiddenCalendars.sorted(), "activities" to showActivities, "notes" to showNotes, "birthdays" to showBirthdays,
    ))

    companion object {
        val DAY_CHOICES = listOf(7, 14, 30, 90)

        @Suppress("UNCHECKED_CAST")
        fun parse(text: String?): WidgetOptions {
            if (text.isNullOrBlank()) return WidgetOptions()
            val m = Json.parse(text) as? Map<String, Any?> ?: throw Json.JsonException("pas un objet JSON")
            val d = WidgetOptions()
            fun num(k: String, def: Int) = (m[k] as? Number)?.toInt() ?: def
            fun bool(k: String, def: Boolean) = m[k] as? Boolean ?: def
            return WidgetOptions(
                days = num("days", d.days).takeIf { it in DAY_CHOICES } ?: d.days,
                showFinished = bool("show_finished", d.showFinished),
                showRefresh = bool("show_refresh", d.showRefresh),
                opacity = num("opacity", d.opacity).coerceIn(0, 100),
                hiddenCalendars = (m["hidden_calendars"] as? List<Any?>).orEmpty().map { it.toString() }.toSet(),
                showActivities = bool("activities", d.showActivities),
                showNotes = bool("notes", d.showNotes),
                showBirthdays = bool("birthdays", d.showBirthdays),
            )
        }
    }
}

/** One row of the agenda widget. */
data class WidgetRow(
    val entry: Entry,
    /** The day the row is listed under (today for an entry that started before). */
    val day: LocalDate,
    /** Only on the first row of a day: "mer." and "11". */
    val weekday: String?,
    val dayNumber: String?,
    val color: String,
    /** Sport emoji, 📝 for a note, 🎂 for a birthday; none for events. */
    val icon: String?,
    val title: String,
    /** "20:00 - 20:30", "Toute la journée", "Du 1 au 3 déc.". */
    val time: String,
)

/** The widgets' rows, from the index's entries (pure, tested). */
object WidgetRows {
    fun icon(e: Entry, sportEmoji: (String) -> String): String? = when (e.kind) {
        EntryKind.ACTIVITY -> e.sport?.let(sportEmoji)
        EntryKind.NOTE -> "📝"
        EntryKind.BIRTHDAY -> "🎂"
        EntryKind.EVENT -> null
    }

    fun visible(e: Entry, o: WidgetOptions): Boolean = when (e.kind) {
        EntryKind.EVENT -> e.calendarId !in o.hiddenCalendars
        EntryKind.ACTIVITY -> o.showActivities
        EntryKind.NOTE -> o.showNotes
        EntryKind.BIRTHDAY -> o.showBirthdays
    }

    /**
     * [entries]: the index's entries from today 00:00 for [WidgetOptions.days] days. Entries already over are left
     * out unless [WidgetOptions.showFinished] (all-day ones of today stay). Each entry is one row, on its first
     * day in the window; the date block is on the first row of each day only.
     */
    fun rows(entries: List<Entry>, now: Instant, zone: ZoneId, o: WidgetOptions, h24: Boolean, sportEmoji: (String) -> String): List<WidgetRow> {
        val today = now.atZone(zone).toLocalDate()
        val last = today.plusDays(o.days.toLong() - 1)
        val kept = entries.filter { e ->
            visible(e, o) && !e.endDate.isBefore(today) && !e.startDate.isAfter(last) &&
                (o.showFinished || e.allDay || e.end.isAfter(now) || (e.end == e.start && !e.start.isBefore(now)))
        }
        val placed = kept.map { (if (it.startDate.isBefore(today)) today else it.startDate) to it }
            .sortedWith(compareBy<Pair<LocalDate, Entry>>({ it.first }, { if (it.second.allDay) 0 else 1 }, { it.second.start }, { it.second.title }, { it.second.key }))
        var previous: LocalDate? = null
        return placed.map { (day, e) ->
            val first = day != previous
            previous = day
            WidgetRow(
                entry = e, day = day,
                weekday = if (first) AgendaText.weekdayShort(day.dayOfWeek) else null,
                dayNumber = if (first) day.dayOfMonth.toString() else null,
                color = e.color, icon = icon(e, sportEmoji), title = e.title.ifBlank { "(Sans titre)" },
                time = AgendaText.timeLine(e, zone, h24),
            )
        }
    }

    /** The small "Aujourd'hui" widget: today's next entry (or the first all-day one) and how many others today. */
    data class Today(val next: Entry?, val others: Int)

    fun today(entries: List<Entry>, now: Instant, zone: ZoneId, o: WidgetOptions = WidgetOptions()): Today {
        val today = now.atZone(zone).toLocalDate()
        val list = entries.filter { visible(it, o) && AgendaLayout.onDay(it, today) }
        val pending = list.filter { it.allDay || it.end.isAfter(now) || (it.end == it.start && !it.start.isBefore(now)) }
        val next = pending.filter { !it.allDay && !it.start.isBefore(now) }.minByOrNull { it.start }
            ?: pending.filter { !it.allDay }.minByOrNull { it.start }
            ?: pending.firstOrNull { it.allDay }
        return Today(next, if (next == null) 0 else pending.size - 1)
    }
}
