package app.scriptorium.agenda

import app.scriptorium.fold
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** The entries of one day of the agenda list. */
data class DayGroup(val date: LocalDate, val entries: List<Entry>)

/** A timed entry placed in a day column of the week/day grid: minutes from 00:00, and its column among overlaps. */
data class Placed(val entry: Entry, val startMin: Int, val endMin: Int, val column: Int, val columns: Int)

/** Pure layout of the agenda views (list, month, week, day). */
object AgendaLayout {
    /** Shortest block drawn in the hour grid, so a zero-length entry stays tappable. */
    const val MIN_BLOCK_MIN = 30

    fun weekStartOf(d: LocalDate, firstDay: DayOfWeek): LocalDate = d.with(TemporalAdjusters.previousOrSame(firstDay))

    /** The days of [d]'s week. */
    fun weekDays(d: LocalDate, firstDay: DayOfWeek): List<LocalDate> = weekStartOf(d, firstDay).let { s -> (0L..6L).map { s.plusDays(it) } }

    /** The month grid: whole weeks from the one holding the 1st to the one holding the last day. */
    fun monthGrid(month: YearMonth, firstDay: DayOfWeek): List<LocalDate> {
        val first = weekStartOf(month.atDay(1), firstDay)
        val last = weekStartOf(month.atEndOfMonth(), firstDay).plusDays(6)
        return (0..ChronoUnit.DAYS.between(first, last)).map { first.plusDays(it) }
    }

    /** Weekday headers in display order. */
    fun weekdayOrder(firstDay: DayOfWeek): List<DayOfWeek> = (0L..6L).map { firstDay.plus(it) }

    fun onDay(e: Entry, d: LocalDate): Boolean = !d.isBefore(e.startDate) && !d.isAfter(e.endDate)

    /** All-day entries, and timed ones of 24 hours or more, go to the all-day strip of the grids. */
    fun isStrip(e: Entry): Boolean = e.allDay || Duration.between(e.start, e.end) >= Duration.ofHours(24)

    /** Day order: all-day and long entries first, then by start, title, key. */
    val DAY_ORDER: Comparator<Entry> = compareBy<Entry>({ if (isStrip(it)) 0 else 1 }, { it.start }, { it.title }, { it.key })

    /**
     * Agenda list: the days of [from, toExclusive) that have entries (an entry over several days shows on each),
     * plus [today] always (empty or not), in order.
     */
    fun groupByDay(entries: List<Entry>, from: LocalDate, toExclusive: LocalDate, today: LocalDate): List<DayGroup> {
        val byDay = java.util.TreeMap<LocalDate, MutableList<Entry>>()
        for (e in entries) {
            var d = if (e.startDate.isBefore(from)) from else e.startDate
            while (!d.isAfter(e.endDate) && d.isBefore(toExclusive)) {
                byDay.getOrPut(d) { ArrayList() }.add(e)
                d = d.plusDays(1)
            }
        }
        if (!today.isBefore(from) && today.isBefore(toExclusive)) byDay.getOrPut(today) { ArrayList() }
        return byDay.map { (d, list) -> DayGroup(d, list.distinctBy { it.key }.sortedWith(DAY_ORDER)) }
    }

    /** Entries of each day of [days], sorted for display. */
    fun perDay(entries: List<Entry>, days: List<LocalDate>): Map<LocalDate, List<Entry>> =
        days.associateWith { d -> entries.filter { onDay(it, d) }.distinctBy { it.key }.sortedWith(DAY_ORDER) }

    /** Month cell: the first [max] entries and how many more ("+N"). */
    fun cell(entries: List<Entry>, max: Int = 3): Pair<List<Entry>, Int> =
        entries.take(max) to maxOf(0, entries.size - max)

    private fun wallMinute(i: Instant, zone: ZoneId): Int = i.atZone(zone).toLocalTime().let { it.hour * 60 + it.minute }

    /**
     * Timed entries of [day] in the hour grid (device [zone]), clipped to the day and placed by wall-clock time
     * (the grid's hour lines are wall-clock hours, also on daylight-saving days); overlapping entries share the
     * width in columns (each cluster of overlaps gets as many columns as it needs).
     */
    fun placeTimed(entries: List<Entry>, day: LocalDate, zone: ZoneId): List<Placed> {
        val dayStart = Zones.dayStart(day, zone)
        val dayEnd = Zones.dayStart(day.plusDays(1), zone)
        val dayLen = 24 * 60
        data class Item(val e: Entry, val s: Int, val en: Int)
        val items = entries.filter { !isStrip(it) && it.start.isBefore(dayEnd) && (it.end.isAfter(dayStart) || (it.end == it.start && !it.start.isBefore(dayStart))) }
            .map { e ->
                val s = if (e.start.isAfter(dayStart)) wallMinute(e.start, zone) else 0
                val raw = if (e.end.isBefore(dayEnd)) wallMinute(e.end, zone) else dayLen
                Item(e, s, minOf(dayLen, maxOf(raw, s + MIN_BLOCK_MIN)))
            }
            .sortedWith(compareBy<Item>({ it.s }, { -it.en }, { it.e.title }, { it.e.key }))
        val out = ArrayList<Placed>()
        var cluster = ArrayList<Pair<Item, Int>>()
        var clusterEnd = -1
        val colEnds = ArrayList<Int>()
        fun flush() {
            val n = colEnds.size
            cluster.forEach { (it, col) -> out.add(Placed(it.e, it.s, it.en, col, n)) }
            cluster = ArrayList()
            colEnds.clear()
        }
        for (it in items) {
            if (it.s >= clusterEnd && cluster.isNotEmpty()) flush()
            var col = colEnds.indexOfFirst { end -> end <= it.s }
            if (col < 0) { colEnds.add(it.en); col = colEnds.size - 1 } else colEnds[col] = it.en
            cluster.add(it to col)
            clusterEnd = maxOf(if (cluster.size == 1) it.en else clusterEnd, it.en)
        }
        if (cluster.isNotEmpty()) flush()
        return out
    }

    /** Minutes from 00:00 of [day] to [now] (for the current-time line), null when [now] is another day. */
    fun nowMinute(now: Instant, day: LocalDate, zone: ZoneId): Int? {
        val t = now.atZone(zone)
        return if (t.toLocalDate() == day) t.hour * 60 + t.minute else null
    }

    /** Default slot of a new event on [day]: the next full hour today, 09:00 another day. */
    fun defaultStart(day: LocalDate, now: java.time.LocalDateTime): java.time.LocalDateTime =
        if (day == now.toLocalDate() && now.hour < 23) now.toLocalDate().atTime(now.hour + 1, 0) else day.atTime(9, 0)

    /**
     * The entry of event [eventId] to show in its detail: the occurrence starting at [at] when it still exists,
     * else the next one from [now], else the latest one (the series may have been edited meanwhile).
     */
    fun entryFor(sources: AgendaSources, eventId: String, at: Instant?, now: Instant, device: ZoneId): Entry? {
        val event = sources.events.firstOrNull { it.id == eventId } ?: return null
        val sub = AgendaSources(events = sources.events.filter { it.id == eventId || it.recurrenceOf == eventId }, settings = sources.settings)
        fun window(from: Instant, to: Instant) =
            AgendaIndex.entries(sub, from, to, device, respectVisibility = false).entries.filter { it.targetId == eventId }
        if (at != null) window(at.minus(Duration.ofDays(1)), at.plus(Duration.ofDays(2))).firstOrNull { it.start == at }?.let { return it }
        RuleEditor.nextStarts(event, now, 1, device).firstOrNull()?.let { o ->
            return window(o.startInstant, o.startInstant.plusSeconds(1)).firstOrNull { it.start == o.startInstant }
        }
        return window(Zones.dayStart(event.start.date, Zones.zoneOr(event.tz, device)).minus(Duration.ofDays(1)), now.plus(Duration.ofDays(1))).maxByOrNull { it.start }
    }

    /** One ICS import still present in the folder ("Annuler l'import" from the agenda menu). */
    data class PastImport(val id: String, val events: Int, val calendars: List<String>, val at: Instant?)

    /** The imports whose files are still there (by `import:` id), the latest first. */
    fun pastImports(events: List<Event>, settings: CalendarSettings): List<PastImport> {
        val byId = events.filter { it.importId != null }.groupBy { it.importId!! }
        val ids = byId.keys + settings.calendars.mapNotNull { it.importId }
        return ids.distinct().map { id ->
            val list = byId[id].orEmpty()
            val cals = (list.map { settings.calendar(it.calendar).name } + settings.calendars.filter { it.importId == id }.map { it.name }).distinct()
            val at = try {
                java.time.LocalDateTime.parse(id.substringBefore('-'), java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))
                    .toInstant(java.time.ZoneOffset.UTC)
            } catch (_: java.time.format.DateTimeParseException) {
                null
            }
            PastImport(id, list.size, cals, at)
        }.sortedWith(compareByDescending<PastImport> { it.at ?: Instant.EPOCH }.thenBy { it.id })
    }

    /** Every word of [query] in the title, the location or the description (accents and case ignored). */
    fun matches(e: Event, query: String): Boolean {
        val words = query.fold().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return false
        val hay = listOf(e.title, e.location.orEmpty(), e.body).joinToString("\n").fold()
        return words.all { hay.contains(it) }
    }

    /**
     * Search of events and dated notes: one entry per matching file, its next occurrence from [now] (within
     * [ahead]) or else its latest past one (within [behind]); upcoming ones first, by date, then past ones,
     * the latest first.
     */
    fun search(
        sources: AgendaSources, query: String, now: Instant, device: ZoneId,
        ahead: Duration = Duration.ofDays(366L * 3), behind: Duration = Duration.ofDays(366L * 5),
    ): List<Entry> {
        val events = sources.events.filter { !it.note.conflict && matches(it, query) }
        val notes = AgendaIndex.datedNotes(sources.notes).first.filter { matches(it, query) }
        if (events.isEmpty() && notes.isEmpty()) return emptyList()
        val sub = sources.copy(
            events = sources.events.filter { e -> e.id in events.map { it.id }.toSet() || e.recurrenceOf != null },
            notes = sources.notes.filter { n -> n.id in notes.map { it.id }.toSet() },
            activities = emptyList(), birthdays = emptyList(),
        )
        val wanted = (events.map { it.id } + notes.map { it.id }).toSet()
        val all = AgendaIndex.entries(sub, now.minus(behind), now.plus(ahead), device, respectVisibility = false).entries
            .filter { it.targetId in wanted }
        val upcoming = all.filter { !it.end.isBefore(now) }.groupBy { it.targetId }.mapValues { it.value.minByOrNull { e -> e.start }!! }
        val past = all.filter { it.end.isBefore(now) && it.targetId !in upcoming }.groupBy { it.targetId }.mapValues { it.value.maxByOrNull { e -> e.start }!! }
        return upcoming.values.sortedWith(compareBy({ it.start }, { it.title })) + past.values.sortedWith(compareByDescending<Entry> { it.start }.thenBy { it.title })
    }
}
