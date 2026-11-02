package app.scriptorium.agenda

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * The event editor's fields, without Compose (tested on the JVM). The times of an all-day draft are kept so
 * that switching "Toute la journée" off and on again does not lose them. [rrule] is the file's rule verbatim
 * until the user picks another one ("Règle importée" stays untouched).
 */
data class EventDraft(
    val title: String = "",
    val allDay: Boolean = false,
    val startDate: LocalDate,
    val startTime: LocalTime = LocalTime.of(9, 0),
    val endDate: LocalDate = startDate,
    val endTime: LocalTime = startTime.plusHours(1),
    /** IANA zone; null = floating ("celui de l'appareil"). */
    val tz: String? = null,
    val calendar: String? = null,
    val location: String = "",
    val rrule: String? = null,
    val alerts: List<Int> = emptyList(),
    /** The file had no end (timed): it gets none unless the end is changed. */
    val noEnd: Boolean = false,
) {
    val start: Stamp get() = if (allDay) Stamp(startDate) else Stamp(startDate, startTime)
    val end: Stamp? get() = when {
        allDay -> if (endDate == startDate) null else Stamp(endDate)
        noEnd && endDate == startDate && endTime == startTime -> null
        else -> Stamp(endDate, endTime)
    }

    /** French problem, or null when the draft can be saved. */
    fun problem(): String? {
        val e = end ?: return null
        if (e < start) return "La fin est avant le début"
        return null
    }

    /** Moves the start, keeping the duration (what calendar apps do when the start changes). */
    fun withStart(date: LocalDate, time: LocalTime = startTime): EventDraft {
        val duration = java.time.Duration.between(startDate.atTime(startTime), endDate.atTime(endTime))
        val days = java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate)
        val newEnd = if (allDay) date.atTime(endTime).plusDays(days) else date.atTime(time).plus(duration)
        return copy(startDate = date, startTime = time, endDate = newEnd.toLocalDate(), endTime = if (allDay) endTime else newEnd.toLocalTime())
    }

    /** The edited event: [base] with the draft's fields (title, times, zone, calendar, place, rule, alerts). */
    fun applyTo(base: Event): Event {
        val newStart = start
        val exdate = if (base.isSeries || rrule != null) carryExdates(base.exdate, base.start, newStart) else base.exdate
        return base.copy(
            note = base.note.copy(title = title.trim()),
            start = newStart, end = end, tz = if (allDay) null else tz, calendar = calendar,
            location = location.trim().ifEmpty { null }, rrule = rrule?.ifBlank { null }, exdate = exdate,
            alerts = alerts.distinct().sorted(), warnings = emptyList(),
        )
    }

    companion object {
        fun fromEvent(e: Event): EventDraft {
            val start = e.start
            val startTime = start.time ?: LocalTime.of(9, 0)
            val end = e.end
            val endDate = end?.date ?: start.date
            val endTime = end?.time ?: if (start.allDay) startTime.plusHours(1) else startTime
            return EventDraft(
                title = e.title, allDay = start.allDay, startDate = start.date, startTime = startTime,
                endDate = endDate, endTime = endTime, tz = e.tz, calendar = e.calendar, location = e.location.orEmpty(),
                rrule = e.rrule, alerts = e.alerts, noEnd = end == null && !start.allDay,
            )
        }

        /** A new event at [at] (one hour), in the device zone, with the default reminders. */
        fun new(at: LocalDateTime, allDay: Boolean, device: ZoneId, calendar: String?, alerts: List<Int>): EventDraft =
            EventDraft(
                allDay = allDay, startDate = at.toLocalDate(), startTime = at.toLocalTime(),
                endDate = at.plusHours(1).toLocalDate(), endTime = at.plusHours(1).toLocalTime(),
                tz = device.id, calendar = calendar, alerts = alerts,
            ).let { if (allDay) it.copy(endDate = it.startDate) else it }

        /**
         * `exdate` follows the form of `start`, and a timed exdate only removes the occurrence at that exact wall
         * time: when the series' start changes form or time of day, its exdates follow (same dates).
         */
        fun carryExdates(exdate: List<Stamp>, oldStart: Stamp, newStart: Stamp): List<Stamp> {
            if (exdate.isEmpty()) return exdate
            return exdate.map { x ->
                when {
                    newStart.allDay -> Stamp(x.date)
                    x.allDay || (x.time == oldStart.time) -> Stamp(x.date, newStart.time)
                    else -> x
                }
            }.distinct()
        }
    }
}
