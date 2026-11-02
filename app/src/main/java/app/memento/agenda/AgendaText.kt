package app.memento.agenda

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** French texts of the agenda (dates, time ranges, reminders), shared by the screens, the widgets and the notifications. */
object AgendaText {
    val MONTHS = listOf("janvier", "février", "mars", "avril", "mai", "juin", "juillet", "août", "septembre", "octobre", "novembre", "décembre")
    val MONTHS_SHORT = listOf("janv.", "févr.", "mars", "avr.", "mai", "juin", "juil.", "août", "sept.", "oct.", "nov.", "déc.")
    val WEEKDAYS = listOf("lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi", "dimanche")
    val WEEKDAYS_SHORT = listOf("lun.", "mar.", "mer.", "jeu.", "ven.", "sam.", "dim.")
    /** One letter per weekday, for the month grid header. */
    val WEEKDAYS_LETTER = listOf("L", "M", "M", "J", "V", "S", "D")

    fun weekday(d: DayOfWeek) = WEEKDAYS[d.value - 1]
    fun weekdayShort(d: DayOfWeek) = WEEKDAYS_SHORT[d.value - 1]

    private fun dayNumber(day: Int) = if (day == 1) "1er" else day.toString()

    /** "14:30" (24 h) or "2:30 PM". */
    fun time(t: LocalTime, h24: Boolean): String =
        if (h24) String.format(java.util.Locale.ROOT, "%02d:%02d", t.hour, t.minute)
        else String.format(java.util.Locale.ROOT, "%d:%02d %s", if (t.hour % 12 == 0) 12 else t.hour % 12, t.minute, if (t.hour < 12) "AM" else "PM")

    /** "12 nov." (+ the year when it is not [currentYear]). */
    fun shortDate(d: LocalDate, currentYear: Int? = null): String =
        "${d.dayOfMonth} ${MONTHS_SHORT[d.monthValue - 1]}" + if (currentYear != null && d.year != currentYear) " ${d.year}" else ""

    /** "12 novembre 2026". */
    fun longDate(d: LocalDate): String = "${dayNumber(d.dayOfMonth)} ${MONTHS[d.monthValue - 1]} ${d.year}"

    /** "lundi 12 novembre 2026". */
    fun fullDate(d: LocalDate): String = "${weekday(d.dayOfWeek)} ${longDate(d)}"

    /** "Novembre 2026". */
    fun monthTitle(year: Int, month: Int): String = MONTHS[month - 1].replaceFirstChar { it.uppercase() } + " $year"

    /** "Aujourd'hui", "Demain", "Hier", else "lundi 12 novembre" (+ the year when it is not this year's). */
    fun dayTitle(d: LocalDate, today: LocalDate): String = when (d) {
        today -> "Aujourd'hui"
        today.plusDays(1) -> "Demain"
        today.minusDays(1) -> "Hier"
        else -> "${weekday(d.dayOfWeek)} ${dayNumber(d.dayOfMonth)} ${MONTHS[d.monthValue - 1]}" + if (d.year != today.year) " ${d.year}" else ""
    }

    /** "Du 1 au 3 déc.", "Du 30 nov. au 2 déc.", with the years when they differ. */
    fun dayRange(first: LocalDate, last: LocalDate): String = when {
        first.year != last.year -> "Du ${shortDate(first)} ${first.year} au ${shortDate(last)} ${last.year}"
        first.monthValue != last.monthValue -> "Du ${shortDate(first)} au ${shortDate(last)}"
        else -> "Du ${first.dayOfMonth} au ${shortDate(last)}"
    }

    private fun localTime(i: Instant, zone: ZoneId): LocalTime = i.atZone(zone).toLocalTime()

    /**
     * Start and end shown for a timed entry: the device zone, or with [eventZone] the event's own wall times
     * ("Afficher les heures dans le fuseau de l'événement").
     */
    private fun times(e: Entry, zone: ZoneId, eventZone: Boolean): Pair<LocalTime, LocalTime> {
        val o = e.occurrence
        if (eventZone && o != null && !o.start.allDay) {
            return o.start.time!! to (o.end?.time ?: o.start.time)
        }
        return localTime(e.start, zone) to localTime(e.end, zone)
    }

    /**
     * Second line of an entry in a one-line list (the widget): "14:30 - 15:15", "14:30" (no end),
     * "Toute la journée", or "Du 1 au 3 déc." for an entry over several days.
     */
    fun timeLine(e: Entry, zone: ZoneId, h24: Boolean, eventZone: Boolean = false): String {
        if (e.endDate != e.startDate) return dayRange(e.startDate, e.endDate)
        if (e.allDay) return "Toute la journée"
        val (s, en) = times(e, zone, eventZone)
        return if (e.end == e.start) time(s, h24) else "${time(s, h24)} - ${time(en, h24)}"
    }

    /** The same, on one [day] of a day-grouped list: a multi-day entry says where that day falls in it. */
    fun timeLineOn(e: Entry, day: LocalDate, zone: ZoneId, h24: Boolean, eventZone: Boolean = false): String {
        if (e.endDate == e.startDate) return timeLine(e, zone, h24, eventZone)
        if (e.allDay) {
            val n = java.time.temporal.ChronoUnit.DAYS.between(e.startDate, day) + 1
            val total = java.time.temporal.ChronoUnit.DAYS.between(e.startDate, e.endDate) + 1
            return "Toute la journée (jour $n/$total)"
        }
        val (s, en) = times(e, zone, eventZone)
        return when (day) {
            e.startDate -> "À partir de ${time(s, h24)}"
            e.endDate -> "Jusqu'à ${time(en, h24)}"
            else -> "Toute la journée"
        }
    }

    /** "Paris" from "Europe/Paris", "New York" from "America/New_York". */
    fun city(zone: String): String = zone.substringAfterLast('/').replace('_', ' ')

    /**
     * Detail screen, an end in its own zone (a flight): "Départ 10:00 (Paris) → arrivée 13:00 (New York)", the
     * arrival on another day "… → arrivée le 18 déc. à 08:00 (Paris)". Null for any other event.
     */
    fun departureArrival(o: Occurrence, h24: Boolean): String? {
        val e = o.event
        val tz = e.tz ?: return null
        val endTz = e.endTz ?: return null
        val start = o.start.time ?: return null
        val endStamp = o.end ?: return null
        val end = endStamp.time ?: return null
        if (endTz == tz || !Zones.isValid(endTz)) return null
        val at = time(end, h24)
        val arrival = if (endStamp.date != o.start.date) "le ${shortDate(endStamp.date)} à $at" else at
        return "Départ ${time(start, h24)} (${city(tz)}) → arrivée $arrival (${city(endTz)})"
    }

    /**
     * Detail screen: "lundi 12 novembre 2026", "14:30 – 15:15" or "Toute la journée", over several days
     * "Du 1 au 3 déc. 2026". [zone] is the zone the times are shown in.
     */
    fun detailWhen(e: Entry, zone: ZoneId, h24: Boolean, eventZone: Boolean = false): Pair<String, String?> {
        if (e.allDay) {
            return if (e.startDate == e.endDate) fullDate(e.startDate) to "Toute la journée"
            else "${dayRange(e.startDate, e.endDate)} ${e.endDate.year}" to "Toute la journée"
        }
        val (s, en) = times(e, zone, eventZone)
        val first = if (eventZone && e.occurrence != null) e.occurrence.start.date else e.startDate
        val last = if (eventZone && e.occurrence?.end != null) e.occurrence.end!!.date else e.endDate
        if (first != last) {
            return "${fullDate(first)}, ${time(s, h24)}" to "jusqu'au ${fullDate(last)}, ${time(en, h24)}"
        }
        return fullDate(first) to (if (e.end == e.start) time(s, h24) else "${time(s, h24)} – ${time(en, h24)}")
    }

    /**
     * Reminder label. Timed: "Au début", "15 min avant", "1 h 30 avant", "1 jour avant", "1 semaine avant", "10 min après".
     * All-day (offset from 00:00 of the day): "Le jour même à 09:00", "La veille à 18:00", "2 jours avant à 09:00".
     */
    fun alertLabel(minutes: Int, allDay: Boolean, h24: Boolean = true): String {
        if (allDay) {
            val days = Math.floorDiv(minutes, 1440)
            val t = LocalTime.of(Math.floorMod(minutes, 1440) / 60, Math.floorMod(minutes, 1440) % 60)
            val at = " à ${time(t, h24)}"
            return when {
                days == 0 -> "Le jour même$at"
                days == -1 -> "La veille$at"
                days == -7 -> "1 semaine avant$at"
                days < 0 -> "${-days} jours avant$at"
                days == 1 -> "Le lendemain$at"
                else -> "$days jours après$at"
            }
        }
        if (minutes == 0) return "Au début"
        val n = Math.abs(minutes)
        val amount = when {
            n % 10080 == 0 -> (n / 10080).let { if (it == 1) "1 semaine" else "$it semaines" }
            n % 1440 == 0 -> (n / 1440).let { if (it == 1) "1 jour" else "$it jours" }
            n % 60 == 0 -> "${n / 60} h"
            n > 60 -> String.format(java.util.Locale.ROOT, "%d h %02d", n / 60, n % 60)
            else -> "$n min"
        }
        return if (minutes < 0) "$amount avant" else "$amount après"
    }
}
