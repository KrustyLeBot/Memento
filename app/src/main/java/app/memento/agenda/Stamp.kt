package app.memento.agenda

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * A date or a wall date-time as written in the files: `2026-11-12` (all-day) or `2026-11-12T14:30[:05]`
 * (wall time of the event's zone). A date and the same date at 00:00 are different stamps (Python's `date`
 * vs `datetime`, `core/agenda/model.py`).
 */
data class Stamp(val date: LocalDate, val time: LocalTime? = null) : Comparable<Stamp> {
    val allDay: Boolean get() = time == null

    /** 00:00 for a date. */
    fun dateTime(): LocalDateTime = date.atTime(time ?: LocalTime.MIDNIGHT)

    /** Same kind of stamp shifted (whole days for a date). */
    fun plusSeconds(seconds: Long): Stamp =
        if (time == null) Stamp(date.plusDays(Math.floorDiv(seconds, 86_400L))) else of(dateTime().plusSeconds(seconds))

    fun plusDays(days: Long): Stamp = Stamp(date.plusDays(days), time)

    override fun compareTo(other: Stamp): Int = compareValuesBy(this, other, { it.dateTime() }, { if (it.allDay) 0 else 1 })

    override fun toString(): String = format(this)

    companion object {
        private val RE = Regex("""^(\d{4})-(\d{2})-(\d{2})(?:[T ](\d{2}):(\d{2})(?::(\d{2}))?)?$""")

        fun of(date: LocalDate) = Stamp(date, null)
        fun of(dt: LocalDateTime) = Stamp(dt.toLocalDate(), dt.toLocalTime().withNano(0))

        /** Throws [IllegalArgumentException] (French message) when [raw] is not a stamp. */
        fun parse(raw: String): Stamp {
            val text = app.memento.NoteFormat.unquote(raw).trim()
            val m = RE.matchEntire(text) ?: throw IllegalArgumentException("date invalide : '$raw'")
            try {
                val d = LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
                if (m.groupValues[4].isEmpty()) return Stamp(d)
                val t = LocalTime.of(m.groupValues[4].toInt(), m.groupValues[5].toInt(), m.groupValues[6].ifEmpty { "0" }.toInt())
                return Stamp(d, t)
            } catch (e: java.time.DateTimeException) {
                throw IllegalArgumentException("date invalide : '$raw'")
            }
        }

        fun format(s: Stamp): String {
            val d = String.format(java.util.Locale.ROOT, "%04d-%02d-%02d", s.date.year, s.date.monthValue, s.date.dayOfMonth)
            val t = s.time ?: return d
            val hm = String.format(java.util.Locale.ROOT, "%sT%02d:%02d", d, t.hour, t.minute)
            return if (t.second != 0) hm + String.format(java.util.Locale.ROOT, ":%02d", t.second) else hm
        }
    }
}

/** Alerts: signed minutes relative to the start (`-15m`, `-1d`, `0m`, `2h`, `-1w`). */
object Alerts {
    private val RE = Regex("""^([+-]?)(\d+)\s*([mhdw])$""")
    private val UNITS = linkedMapOf('w' to 10080, 'd' to 1440, 'h' to 60, 'm' to 1)

    fun parse(raw: String): Int {
        val m = RE.matchEntire(app.memento.NoteFormat.unquote(raw).trim())
            ?: throw IllegalArgumentException("rappel invalide : '$raw'")
        val minutes = m.groupValues[2].toInt() * UNITS.getValue(m.groupValues[3][0])
        return if (m.groupValues[1] == "-") -minutes else minutes
    }

    fun format(minutes: Int): String {
        val sign = if (minutes < 0) "-" else ""
        val n = Math.abs(minutes)
        for ((unit, size) in UNITS) if (n != 0 && n % size == 0) return "$sign${n / size}$unit"
        return "$sign${n}m"
    }
}
