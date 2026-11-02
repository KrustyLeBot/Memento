package app.memento.agenda

import biweekly.ICalDataType
import biweekly.io.ParseContext
import biweekly.io.scribe.property.RecurrenceRuleScribe
import biweekly.parameter.ICalParameters
import biweekly.util.Recurrence
import biweekly.util.com.google.ical.iter.RecurrenceIteratorFactory
import biweekly.util.com.google.ical.values.DateTimeValue
import biweekly.util.com.google.ical.values.DateTimeValueImpl
import biweekly.util.com.google.ical.values.DateValue
import biweekly.util.com.google.ical.values.DateValueImpl
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.TimeZone

/** One instance of a (possibly recurring) event. */
data class Occurrence(
    val event: Event,
    /** Wall start in the event's zone (a date for all-day). */
    val start: Stamp,
    /** Wall end (all-day: last day included); null for a zero-length timed event. */
    val end: Stamp?,
    val startInstant: Instant,
    /** All-day: 00:00 (device zone) of the day after the last day. */
    val endInstant: Instant,
) {
    /** Stable id of the occurrence: event id + its wall start. */
    val key: String get() = "${event.id}|$start"
}

data class Expansion(
    val occurrences: List<Occurrence> = emptyList(),
    val truncated: Boolean = false,
    /** Unreadable rule (French); the event then shows as a single occurrence. */
    val error: String? = null,
)

/**
 * Occurrences of an event in a window [from, to) (README, "Agenda", recurrence). Same rules as Python's
 * `core/agenda/recurrence.py`:
 * - a series is expanded in wall time of its zone (`tz`, or the device zone when floating) by biweekly's RRULE
 *   iterator, each wall start then becomes an instant ([Zones.toInstant]);
 * - DTSTART is always the first occurrence and counts toward COUNT (RFC 5545), even off the rule; COUNT and
 *   UNTIL are applied here: UNTIL as a date (compared to the date), a local date-time (wall time) or UTC `Z`;
 * - EXDATE and the `recurrence_at` of exception files remove occurrences (an exact stamp; a date removes the day of
 *   an all-day series, and the occurrence at the series' start time that day of a timed one);
 * - timed occurrences last the exact duration DTEND - DTSTART (DTEND in `end_tz` when set, the occurrence's end
 *   wall time then given in that zone); all-day ones are dates of the device zone;
 * - in the window when overlapping it; a zero-length one when from <= start < to;
 * - at most [MAX_OCCURRENCES] per window and [MAX_ITERATIONS] rule steps, then `truncated`.
 */
object RecurrenceExpander {
    const val MAX_OCCURRENCES = 10_000
    const val MAX_ITERATIONS = 1_000_000

    /** The RRULE without COUNT and UNTIL (upper case), COUNT, and UNTIL as a Stamp or a UTC instant. */
    data class Rule(val body: String, val count: Int?, val untilStamp: Stamp?, val untilInstant: Instant?)

    private val UNTIL = Regex("""^(\d{4})(\d{2})(\d{2})(?:T(\d{2})(\d{2})(\d{2})(Z?))?$""")

    fun parseRule(rrule: String): Rule {
        var text = rrule.trim()
        if (text.uppercase().startsWith("RRULE:")) text = text.substring(6)
        val parts = ArrayList<String>()
        var count: Int? = null
        var untilStamp: Stamp? = null
        var untilInstant: Instant? = null
        for (part in text.split(";")) {
            if (part.isBlank()) continue
            val key = part.substringBefore("=").trim().uppercase()
            val value = part.substringAfter("=", "").trim().uppercase()
            when (key) {
                "COUNT" -> {
                    count = value.toIntOrNull() ?: throw IllegalArgumentException("COUNT illisible : $value")
                    if (count < 1) throw IllegalArgumentException("COUNT < 1")
                }
                "UNTIL" -> {
                    val m = UNTIL.matchEntire(value) ?: throw IllegalArgumentException("UNTIL illisible : $value")
                    val g = m.groupValues
                    val d = try { LocalDate.of(g[1].toInt(), g[2].toInt(), g[3].toInt()) } catch (e: java.time.DateTimeException) {
                        throw IllegalArgumentException("UNTIL illisible : $value")
                    }
                    if (g[4].isEmpty()) untilStamp = Stamp(d)
                    else {
                        val t = LocalDateTime.of(d, LocalTime.of(g[4].toInt(), g[5].toInt(), g[6].toInt()))
                        if (g[7] == "Z") untilInstant = t.toInstant(java.time.ZoneOffset.UTC) else untilStamp = Stamp.of(t)
                    }
                }
                else -> parts.add("$key=$value")
            }
        }
        if (parts.none { it.startsWith("FREQ=") }) throw IllegalArgumentException("FREQ manquant")
        return Rule(parts.joinToString(";"), count, untilStamp, untilInstant)
    }

    private val ruleCache = java.util.concurrent.ConcurrentHashMap<String, Recurrence>()

    private fun recurrence(body: String): Recurrence = ruleCache.getOrPut(body) {
        val context = ParseContext()
        context.version = biweekly.ICalVersion.V2_0
        val prop = try {
            RecurrenceRuleScribe().parseText(body, ICalDataType.RECUR, ICalParameters(), context)
        } catch (e: Exception) {
            throw IllegalArgumentException(e.message ?: e.javaClass.simpleName)
        }
        val value = prop.value
        if (value == null || value.frequency == null || context.warnings.isNotEmpty()) {
            throw IllegalArgumentException(context.warnings.joinToString("; ").ifEmpty { "FREQ illisible" })
        }
        value
    }

    private fun toDateValue(s: Stamp): DateValue {
        val d = s.date
        val t = s.time ?: return DateValueImpl(d.year, d.monthValue, d.dayOfMonth)
        return DateTimeValueImpl(d.year, d.monthValue, d.dayOfMonth, t.hour, t.minute, t.second)
    }

    private fun toStamp(v: DateValue, allDay: Boolean): Stamp {
        val d = LocalDate.of(v.year(), v.month(), v.day())
        if (allDay) return Stamp(d)
        val t = if (v is DateTimeValue) LocalTime.of(v.hour(), v.minute(), v.second()) else LocalTime.MIDNIGHT
        return Stamp(d, t)
    }

    private fun withinUntil(rule: Rule, s: Stamp, allDay: Boolean, zone: ZoneId, device: ZoneId): Boolean {
        rule.untilStamp?.let { u ->
            if (u.allDay || allDay) return s.date <= u.date
            return s.dateTime() <= u.dateTime()
        }
        rule.untilInstant?.let { u ->
            if (allDay) return s.date <= Zones.toWall(u, device).toLocalDate()
            return Zones.toInstant(s.dateTime(), zone) <= u
        }
        return true
    }

    /**
     * Wall starts of the series, in order, COUNT and UNTIL applied, EXDATE not. Throws
     * [IllegalArgumentException] for an unreadable rule. [skipTo]: a wall time before which the caller does not
     * need starts (used only without COUNT, where skipping cannot change which starts exist).
     */
    fun seriesStarts(event: Event, device: ZoneId, skipTo: Stamp? = null): Sequence<Stamp> {
        val rule = parseRule(event.rrule ?: "")
        val recur = recurrence(rule.body)
        val allDay = event.allDay
        val zone = Zones.zoneOr(event.tz, device)
        val dtstart = toDateValue(event.start)
        return sequence {
            val it = RecurrenceIteratorFactory.createRecurrenceIterator(recur, dtstart, TimeZone.getTimeZone("UTC"))
            var emitted = 0
            val first = if (it.hasNext()) toStamp(it.next(), allDay) else null
            val head = if (first != event.start) listOfNotNull(event.start, first) else listOf(first)
            for (s in head) {
                if (rule.count != null && emitted >= rule.count || !withinUntil(rule, s, allDay, zone, device)) return@sequence
                emitted++
                yield(s)
            }
            if (rule.count == null && skipTo != null && skipTo > event.start) it.advanceTo(toDateValue(skipTo))
            while (it.hasNext()) {
                val s = toStamp(it.next(), allDay)
                if (rule.count != null && emitted >= rule.count || !withinUntil(rule, s, allDay, zone, device)) return@sequence
                emitted++
                yield(s)
            }
        }
    }

    private fun excluded(s: Stamp, exact: Set<Stamp>, days: Set<LocalDate>): Boolean =
        if (s.allDay) s.date in days else s in exact || s.date in days

    /**
     * Occurrences of [event] overlapping [from, to). [overridden]: the `recurrence_at` of the series'
     * exception files, removed like EXDATE (a sync may bring an exception before the series' exdate).
     */
    fun expand(event: Event, from: Instant, to: Instant, device: ZoneId, overridden: List<Stamp> = emptyList()): Expansion {
        if (event.cancelled) return Expansion()
        val zone = Zones.zoneOr(event.tz, device)
        val allDay = event.allDay
        val make: (Stamp) -> Occurrence
        val span: Duration
        if (allDay) {
            val days = event.end?.let { java.time.temporal.ChronoUnit.DAYS.between(event.start.date, it.date) } ?: 0L
            span = Duration.ofDays(days + 1)
            make = { s ->
                val endDay = s.date.plusDays(days)
                Occurrence(event, s, Stamp(endDay), Zones.dayStart(s.date, device), Zones.dayStart(endDay.plusDays(1), device))
            }
        } else {
            // with `end_tz` the end is a wall time of another zone: the duration is the instant difference of the
            // first occurrence, applied to every occurrence's start (RFC 5545, DTEND with its own TZID)
            val endZone = Zones.endZoneOr(event, device)
            val firstStart = Zones.toInstant(event.start.dateTime(), zone)
            val duration = event.end?.let { Duration.between(firstStart, Zones.toInstant(it.dateTime(), endZone)) } ?: Duration.ZERO
            span = duration
            make = { s ->
                val st = Zones.toInstant(s.dateTime(), zone)
                val en = st.plus(duration)
                Occurrence(event, s, if (event.end != null) Stamp.of(Zones.toWall(en, endZone)) else null, st, en)
            }
        }
        fun inside(o: Occurrence): Boolean =
            if (o.endInstant == o.startInstant) !o.startInstant.isBefore(from) && o.startInstant.isBefore(to)
            else o.startInstant.isBefore(to) && o.endInstant.isAfter(from)

        if (!event.isSeries) {
            val o = make(event.start)
            return Expansion(if (inside(o)) listOf(o) else emptyList())
        }
        val removed = event.exdate + overridden
        // a date alone on a timed series means the occurrence at the series' start time that day (as exported)
        val exact = removed.map { if (it.allDay && !allDay) Stamp(it.date, event.start.time) else it }.filter { !it.allDay }.toSet()
        val days = if (allDay) removed.map { it.date }.toSet() else emptySet()
        val out = ArrayList<Occurrence>()
        var truncated = false
        try {
            // Skipping ahead: a wall time safely before the window (event span + two days of zone offsets).
            val skipWall = Zones.toWall(from, zone).minus(span).minusDays(2)
            val skipTo = if (allDay) Stamp(skipWall.toLocalDate()) else Stamp.of(skipWall)
            var steps = 0
            for (s in seriesStarts(event, device, skipTo)) {
                if (++steps > MAX_ITERATIONS) { truncated = true; break }
                val o = make(s)
                if (!o.startInstant.isBefore(to)) break
                if (excluded(s, exact, days) || !inside(o)) continue
                if (out.size >= MAX_OCCURRENCES) { truncated = true; break }
                out.add(o)
            }
        } catch (e: IllegalArgumentException) {
            val o = make(event.start)
            return Expansion(if (inside(o)) listOf(o) else emptyList(), error = "règle de répétition illisible (${event.rrule}) : ${e.message}")
        } catch (e: java.time.DateTimeException) {
            val o = make(event.start)
            return Expansion(if (inside(o)) listOf(o) else emptyList(), error = "règle de répétition illisible (${event.rrule}) : ${e.message}")
        } catch (e: AssertionError) {
            // biweekly's iterator asserts on values its parser accepts (BYMONTH=13): one bad file must not stop the agenda
            val o = make(event.start)
            return Expansion(if (inside(o)) listOf(o) else emptyList(), error = "règle de répétition illisible (${event.rrule}) : ${e.message}")
        }
        return Expansion(out, truncated)
    }
}
