package app.scriptorium.agenda

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

enum class Freq { DAILY, WEEKLY, MONTHLY, YEARLY }

/** Monthly rules: "le 12" (day of the month) or "le 2e jeudi" / "le dernier vendredi". */
enum class MonthlyMode { DAY_OF_MONTH, NTH_WEEKDAY }

/**
 * What the custom repetition editor can express: every N days / weeks / months / years, weekdays (weekly), day
 * of the month or n-th weekday (monthly, -1 = the last), a date (yearly), and the end (never, until a date, after
 * N occurrences). Everything maps to plain RRULE parts.
 */
data class RuleSpec(
    val freq: Freq,
    val interval: Int = 1,
    /** Weekly: the weekdays (never empty once parsed: the start's weekday by default). */
    val weekdays: Set<DayOfWeek> = emptySet(),
    val monthlyMode: MonthlyMode = MonthlyMode.DAY_OF_MONTH,
    /** Monthly by day: 1..31, or -1 for the last day of the month. */
    val monthDay: Int = 1,
    /** Monthly by weekday: 1..5, or -1 for the last one. */
    val nth: Int = 1,
    val nthWeekday: DayOfWeek = DayOfWeek.MONDAY,
    /** Yearly: month and day (the start's by default). */
    val yearMonth: Int = 1,
    val yearDay: Int = 1,
    /** Last day included. */
    val until: LocalDate? = null,
    val count: Int? = null,
)

enum class RepeatPreset { NONE, DAILY, WEEKDAYS, WEEKLY, BIWEEKLY, MONTHLY_DAY, MONTHLY_NTH, YEARLY, CUSTOM, IMPORTED }

/**
 * The repetition presets and the custom editor, from RRULE text and back. A rule the editor cannot express
 * ([parse] returns null) is shown read-only as "Règle importée" and kept untouched.
 */
object RuleEditor {
    private val DAY_CODES = listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")
    private val NTH_DAY = Regex("""^([+-]?\d)?(MO|TU|WE|TH|FR|SA|SU)$""")
    private val WORKDAYS = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)

    fun code(d: DayOfWeek) = DAY_CODES[d.value - 1]
    private fun day(code: String) = DayOfWeek.of(DAY_CODES.indexOf(code) + 1)

    /** The n-th weekday of [d] in its month: 1..4, or -1 when it is the 5th (that is, the last). */
    fun nthOf(d: LocalDate): Int = ((d.dayOfMonth - 1) / 7 + 1).let { if (it >= 5) -1 else it }

    /** Defaults of a new custom rule for an event starting on [start]. */
    fun defaults(freq: Freq, start: LocalDate) = RuleSpec(
        freq = freq, weekdays = setOf(start.dayOfWeek), monthDay = start.dayOfMonth,
        nth = nthOf(start), nthWeekday = start.dayOfWeek, yearMonth = start.monthValue, yearDay = start.dayOfMonth,
    )

    /**
     * The spec of [rrule] for an event starting at [start] (zone [tz], floating when null, in [device]), or null
     * when the editor cannot express it.
     */
    fun parse(rrule: String, start: Stamp, tz: String?, device: ZoneId): RuleSpec? {
        val rule = try {
            RecurrenceExpander.parseRule(rrule)
        } catch (_: IllegalArgumentException) {
            return null
        }
        val parts = LinkedHashMap<String, String>()
        for (p in rule.body.split(";")) {
            if (p.isBlank()) continue
            val k = p.substringBefore("=")
            if (k in parts) return null
            parts[k] = p.substringAfter("=", "")
        }
        val freq = when (parts.remove("FREQ")) {
            "DAILY" -> Freq.DAILY
            "WEEKLY" -> Freq.WEEKLY
            "MONTHLY" -> Freq.MONTHLY
            "YEARLY" -> Freq.YEARLY
            else -> return null
        }
        val interval = parts.remove("INTERVAL")?.let { it.toIntOrNull()?.takeIf { n -> n >= 1 } ?: return null } ?: 1
        val wkst = parts.remove("WKST")
        val byDay = parts.remove("BYDAY")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
        val byMonthDay = parts.remove("BYMONTHDAY")?.split(",")
        val byMonth = parts.remove("BYMONTH")?.split(",")
        val bySetPos = parts.remove("BYSETPOS")?.split(",")
        if (parts.isNotEmpty()) return null
        val until = rule.untilStamp?.date ?: rule.untilInstant?.let { Zones.toWall(it, Zones.zoneOr(tz, device)).toLocalDate() }
        val base = defaults(freq, start.date).copy(interval = interval, until = until, count = rule.count)
        val days = byDay?.map { NTH_DAY.matchEntire(it) ?: return null }

        fun plainDays(): Set<DayOfWeek>? = days?.let { ds -> if (ds.any { it.groupValues[1].isNotEmpty() }) null else ds.map { day(it.groupValues[2]) }.toSet() }

        return when (freq) {
            Freq.DAILY -> {
                if (byMonthDay != null || byMonth != null || bySetPos != null) return null
                if (days == null) base
                // "Du lundi au vendredi" written as DAILY;BYDAY=… (some clients): the same set of days when weekly
                else if (interval == 1) base.copy(freq = Freq.WEEKLY, weekdays = plainDays() ?: return null)
                else null
            }
            Freq.WEEKLY -> {
                if (byMonthDay != null || byMonth != null || bySetPos != null) return null
                val set = if (days == null) setOf(start.date.dayOfWeek) else plainDays() ?: return null
                // WKST changes which weeks count only for "every N weeks" on several days
                if (wkst != null && wkst != "MO" && interval > 1 && set.size > 1) return null
                base.copy(weekdays = set)
            }
            Freq.MONTHLY -> {
                if (byMonth != null) return null
                when {
                    byMonthDay != null -> {
                        if (days != null || bySetPos != null || byMonthDay.size != 1) return null
                        val n = byMonthDay[0].toIntOrNull() ?: return null
                        if (n !in 1..31 && n != -1) return null
                        base.copy(monthlyMode = MonthlyMode.DAY_OF_MONTH, monthDay = n)
                    }
                    days != null -> {
                        if (days.size != 1) return null
                        val g = days[0].groupValues
                        val n = if (bySetPos != null) {
                            if (bySetPos.size != 1 || g[1].isNotEmpty()) return null
                            bySetPos[0].toIntOrNull() ?: return null
                        } else g[1].ifEmpty { return null }.toIntOrNull() ?: return null
                        if (n !in 1..5 && n != -1) return null
                        base.copy(monthlyMode = MonthlyMode.NTH_WEEKDAY, nth = n, nthWeekday = day(g[2]))
                    }
                    bySetPos != null -> null
                    else -> base.copy(monthlyMode = MonthlyMode.DAY_OF_MONTH, monthDay = start.date.dayOfMonth)
                }
            }
            Freq.YEARLY -> {
                if (days != null || bySetPos != null) return null
                if (byMonth == null && byMonthDay == null) return base
                if (byMonth?.size != 1 || byMonthDay?.size != 1) return null
                val m = byMonth[0].toIntOrNull() ?: return null
                val d = byMonthDay[0].toIntOrNull() ?: return null
                try { LocalDate.of(2024, m, d) } catch (_: java.time.DateTimeException) { return null }
                base.copy(yearMonth = m, yearDay = d)
            }
        }
    }

    private val UTC_FMT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
    private val DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd")

    /**
     * The RRULE of [spec] for an event starting at [start]. UNTIL: a date for all-day events, the end of that
     * day in UTC for zoned events (RFC 5545 wants UTC when DTSTART has a zone), local for floating ones.
     */
    fun build(spec: RuleSpec, start: Stamp, tz: String?, device: ZoneId): String {
        val parts = arrayListOf("FREQ=${spec.freq.name}")
        if (spec.interval > 1) parts.add("INTERVAL=${spec.interval}")
        when (spec.freq) {
            Freq.DAILY -> Unit
            Freq.WEEKLY -> {
                val days = spec.weekdays.ifEmpty { setOf(start.date.dayOfWeek) }
                parts.add("BYDAY=" + days.sortedBy { it.value }.joinToString(",") { code(it) })
            }
            Freq.MONTHLY -> parts.add(
                if (spec.monthlyMode == MonthlyMode.DAY_OF_MONTH) "BYMONTHDAY=${spec.monthDay}"
                else "BYDAY=${spec.nth}${code(spec.nthWeekday)}",
            )
            Freq.YEARLY -> if (spec.yearMonth != start.date.monthValue || spec.yearDay != start.date.dayOfMonth) {
                parts.add("BYMONTH=${spec.yearMonth}")
                parts.add("BYMONTHDAY=${spec.yearDay}")
            }
        }
        spec.count?.let { parts.add("COUNT=$it") }
        if (spec.count == null) spec.until?.let { u ->
            parts.add("UNTIL=" + when {
                start.allDay -> DATE_FMT.format(u)
                Zones.isValid(tz) -> UTC_FMT.format(LocalDateTime.ofInstant(Zones.toInstant(u.atTime(LocalTime.of(23, 59, 59)), Zones.zone(tz!!)), ZoneOffset.UTC))
                else -> DATE_FMT.format(u) + "T235959"
            })
        }
        return parts.joinToString(";")
    }

    /** The spec of a preset for an event starting on [start]; null for NONE, CUSTOM and IMPORTED. */
    fun presetSpec(p: RepeatPreset, start: LocalDate): RuleSpec? = when (p) {
        RepeatPreset.DAILY -> defaults(Freq.DAILY, start)
        RepeatPreset.WEEKDAYS -> defaults(Freq.WEEKLY, start).copy(weekdays = WORKDAYS)
        RepeatPreset.WEEKLY -> defaults(Freq.WEEKLY, start)
        RepeatPreset.BIWEEKLY -> defaults(Freq.WEEKLY, start).copy(interval = 2)
        RepeatPreset.MONTHLY_DAY -> defaults(Freq.MONTHLY, start)
        RepeatPreset.MONTHLY_NTH -> defaults(Freq.MONTHLY, start).copy(monthlyMode = MonthlyMode.NTH_WEEKDAY)
        RepeatPreset.YEARLY -> defaults(Freq.YEARLY, start)
        else -> null
    }

    /** The presets offered in the menu (both monthly forms). */
    val MENU = listOf(
        RepeatPreset.NONE, RepeatPreset.DAILY, RepeatPreset.WEEKDAYS, RepeatPreset.WEEKLY, RepeatPreset.BIWEEKLY,
        RepeatPreset.MONTHLY_DAY, RepeatPreset.MONTHLY_NTH, RepeatPreset.YEARLY, RepeatPreset.CUSTOM,
    )

    /** Which preset [rrule] is; CUSTOM when the editor can express it otherwise, IMPORTED when it cannot. */
    fun detect(rrule: String?, start: Stamp, tz: String?, device: ZoneId): RepeatPreset {
        if (rrule.isNullOrBlank()) return RepeatPreset.NONE
        val spec = parse(rrule, start, tz, device) ?: return RepeatPreset.IMPORTED
        if (spec.count != null || spec.until != null) return RepeatPreset.CUSTOM
        return MENU.firstOrNull { p -> presetSpec(p, start.date)?.let { normal(it) == normal(spec) } == true } ?: RepeatPreset.CUSTOM
    }

    /** Fields that do not take part in the rule of this frequency are reset, so equal rules compare equal. */
    private fun normal(s: RuleSpec): RuleSpec {
        val z = RuleSpec(s.freq, s.interval, until = s.until, count = s.count)
        return when (s.freq) {
            Freq.DAILY -> z
            Freq.WEEKLY -> z.copy(weekdays = s.weekdays)
            Freq.MONTHLY -> if (s.monthlyMode == MonthlyMode.DAY_OF_MONTH) z.copy(monthDay = s.monthDay)
            else z.copy(monthlyMode = MonthlyMode.NTH_WEEKDAY, nth = s.nth, nthWeekday = s.nthWeekday)
            Freq.YEARLY -> z.copy(yearMonth = s.yearMonth, yearDay = s.yearDay)
        }
    }

    private fun nthText(n: Int) = when (n) {
        -1 -> "dernier"
        1 -> "1er"
        else -> "${n}e"
    }

    private fun daysText(days: Set<DayOfWeek>): String {
        val names = days.sortedBy { it.value }.map { "le " + AgendaText.weekday(it) }
        return if (names.size == 1) names[0] else names.dropLast(1).joinToString(", ") + " et " + names.last()
    }

    /** Menu label of a preset for an event starting on [start]. */
    fun presetLabel(p: RepeatPreset, start: LocalDate): String = when (p) {
        RepeatPreset.NONE -> "Jamais"
        RepeatPreset.DAILY -> "Tous les jours"
        RepeatPreset.WEEKDAYS -> "Du lundi au vendredi"
        RepeatPreset.WEEKLY -> "Toutes les semaines le ${AgendaText.weekday(start.dayOfWeek)}"
        RepeatPreset.BIWEEKLY -> "Toutes les 2 semaines le ${AgendaText.weekday(start.dayOfWeek)}"
        RepeatPreset.MONTHLY_DAY -> "Tous les mois le ${if (start.dayOfMonth == 1) "1er" else start.dayOfMonth}"
        RepeatPreset.MONTHLY_NTH -> "Tous les mois le ${nthText(nthOf(start))} ${AgendaText.weekday(start.dayOfWeek)}"
        RepeatPreset.YEARLY -> "Tous les ans le ${if (start.dayOfMonth == 1) "1er" else start.dayOfMonth} ${AgendaText.MONTHS[start.monthValue - 1]}"
        RepeatPreset.CUSTOM -> "Personnalisé…"
        RepeatPreset.IMPORTED -> "Règle importée"
    }

    /** "Toutes les 2 semaines le lundi et le jeudi, jusqu'au 31 déc. 2026". */
    fun describe(s: RuleSpec): String {
        val n = s.interval
        val head = when (s.freq) {
            Freq.DAILY -> if (n == 1) "Tous les jours" else "Tous les $n jours"
            Freq.WEEKLY -> (if (n == 1) "Toutes les semaines" else "Toutes les $n semaines") +
                if (s.weekdays == WORKDAYS && n == 1) ", du lundi au vendredi" else " " + daysText(s.weekdays)
            Freq.MONTHLY -> (if (n == 1) "Tous les mois" else "Tous les $n mois") + " " + when {
                s.monthlyMode == MonthlyMode.NTH_WEEKDAY -> "le ${nthText(s.nth)} ${AgendaText.weekday(s.nthWeekday)}"
                s.monthDay == -1 -> "le dernier jour"
                else -> "le ${if (s.monthDay == 1) "1er" else s.monthDay}"
            }
            Freq.YEARLY -> (if (n == 1) "Tous les ans" else "Tous les $n ans") +
                " le ${if (s.yearDay == 1) "1er" else s.yearDay} ${AgendaText.MONTHS[s.yearMonth - 1]}"
        }
        val end = when {
            s.count != null -> if (s.count == 1) ", 1 fois" else ", ${s.count} fois"
            s.until != null -> ", jusqu'au ${AgendaText.shortDate(s.until)} ${s.until.year}"
            else -> ""
        }
        return head + end
    }

    /** Text of a rule as shown under "Répétition": a preset label, the custom description, or the raw imported rule. */
    fun summary(rrule: String?, start: Stamp, tz: String?, device: ZoneId): String {
        if (rrule.isNullOrBlank()) return "Jamais"
        val spec = parse(rrule, start, tz, device) ?: return "Règle importée : $rrule"
        return when (val p = detect(rrule, start, tz, device)) {
            RepeatPreset.CUSTOM, RepeatPreset.IMPORTED -> describe(spec)
            else -> presetLabel(p, start.date)
        }
    }

    /** The first [n] occurrence starts on or after [from] (instants), for the "Prochaines : …" line. */
    fun nextStarts(event: Event, from: Instant, n: Int, device: ZoneId): List<Occurrence> {
        var span = java.time.Duration.ofDays(62)
        val limit = from.plus(java.time.Duration.ofDays(366L * 30))
        while (true) {
            val to = from.plus(span)
            val x = RecurrenceExpander.expand(event, from, to, device)
            val found = x.occurrences.filter { !it.startInstant.isBefore(from) }
            if (found.size >= n || to.isAfter(limit) || x.error != null) return found.take(n)
            span = span.multipliedBy(4)
        }
    }

    /** "Prochaines : 12 nov., 26 nov., 10 déc." (empty when the series has no future occurrence). */
    fun nextText(event: Event, from: Instant, device: ZoneId, today: LocalDate): String {
        val next = nextStarts(event, from, 3, device)
        if (next.isEmpty()) return "Aucune occurrence à venir"
        return "Prochaines : " + next.joinToString(", ") { AgendaText.shortDate(it.start.date, today.year) }
    }
}
