package app.scriptorium.agenda

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * Time zones (README, "Agenda", time zones). Same rules as Python's `core/agenda/zones.py`:
 * - wall time -> instant: a time in a daylight-saving gap moves forward by the length of the gap, a repeated
 *   time takes its first instant (java.time's `ZonedDateTime.ofLocal`, also RFC 5545's rule);
 * - [resolveTzid]: an IANA name as is, a Windows name through the CLDR table, a prefixed name by its tail,
 *   else an embedded VTIMEZONE recognised from its offsets and transition months; null when nothing matches;
 * - [vtimezoneLines]: the VTIMEZONE block written on export.
 */
object Zones {
    /** CLDR windowsZones.xml, territory "001", trimmed. Order matters for the recognition of VTIMEZONEs. */
    val WINDOWS_ZONES: Map<String, String> = linkedMapOf(
        "Romance Standard Time" to "Europe/Paris",
        "W. Europe Standard Time" to "Europe/Berlin",
        "Central Europe Standard Time" to "Europe/Budapest",
        "Central European Standard Time" to "Europe/Warsaw",
        "GMT Standard Time" to "Europe/London",
        "Greenwich Standard Time" to "Atlantic/Reykjavik",
        "UTC" to "Etc/UTC",
        "GTB Standard Time" to "Europe/Bucharest",
        "FLE Standard Time" to "Europe/Helsinki",
        "E. Europe Standard Time" to "Europe/Chisinau",
        "Kaliningrad Standard Time" to "Europe/Kaliningrad",
        "Belarus Standard Time" to "Europe/Minsk",
        "Russian Standard Time" to "Europe/Moscow",
        "Russia Time Zone 3" to "Europe/Samara",
        "Turkey Standard Time" to "Europe/Istanbul",
        "Israel Standard Time" to "Asia/Jerusalem",
        "Middle East Standard Time" to "Asia/Beirut",
        "Jordan Standard Time" to "Asia/Amman",
        "Syria Standard Time" to "Asia/Damascus",
        "Egypt Standard Time" to "Africa/Cairo",
        "Morocco Standard Time" to "Africa/Casablanca",
        "W. Central Africa Standard Time" to "Africa/Lagos",
        "South Africa Standard Time" to "Africa/Johannesburg",
        "E. Africa Standard Time" to "Africa/Nairobi",
        "Libya Standard Time" to "Africa/Tripoli",
        "Sudan Standard Time" to "Africa/Khartoum",
        "Namibia Standard Time" to "Africa/Windhoek",
        "Sao Tome Standard Time" to "Africa/Sao_Tome",
        "Azores Standard Time" to "Atlantic/Azores",
        "Cape Verde Standard Time" to "Atlantic/Cape_Verde",
        "Arabic Standard Time" to "Asia/Baghdad",
        "Arab Standard Time" to "Asia/Riyadh",
        "Iran Standard Time" to "Asia/Tehran",
        "Arabian Standard Time" to "Asia/Dubai",
        "Azerbaijan Standard Time" to "Asia/Baku",
        "Georgian Standard Time" to "Asia/Tbilisi",
        "Caucasus Standard Time" to "Asia/Yerevan",
        "Afghanistan Standard Time" to "Asia/Kabul",
        "Pakistan Standard Time" to "Asia/Karachi",
        "West Asia Standard Time" to "Asia/Tashkent",
        "Ekaterinburg Standard Time" to "Asia/Yekaterinburg",
        "India Standard Time" to "Asia/Kolkata",
        "Sri Lanka Standard Time" to "Asia/Colombo",
        "Nepal Standard Time" to "Asia/Kathmandu",
        "Central Asia Standard Time" to "Asia/Almaty",
        "Bangladesh Standard Time" to "Asia/Dhaka",
        "Myanmar Standard Time" to "Asia/Yangon",
        "N. Central Asia Standard Time" to "Asia/Novosibirsk",
        "SE Asia Standard Time" to "Asia/Bangkok",
        "North Asia Standard Time" to "Asia/Krasnoyarsk",
        "China Standard Time" to "Asia/Shanghai",
        "North Asia East Standard Time" to "Asia/Irkutsk",
        "Singapore Standard Time" to "Asia/Singapore",
        "W. Australia Standard Time" to "Australia/Perth",
        "Taipei Standard Time" to "Asia/Taipei",
        "Tokyo Standard Time" to "Asia/Tokyo",
        "Korea Standard Time" to "Asia/Seoul",
        "Yakutsk Standard Time" to "Asia/Yakutsk",
        "Cen. Australia Standard Time" to "Australia/Adelaide",
        "AUS Central Standard Time" to "Australia/Darwin",
        "E. Australia Standard Time" to "Australia/Brisbane",
        "AUS Eastern Standard Time" to "Australia/Sydney",
        "West Pacific Standard Time" to "Pacific/Port_Moresby",
        "Tasmania Standard Time" to "Australia/Hobart",
        "Vladivostok Standard Time" to "Asia/Vladivostok",
        "New Zealand Standard Time" to "Pacific/Auckland",
        "Fiji Standard Time" to "Pacific/Fiji",
        "Tonga Standard Time" to "Pacific/Tongatapu",
        "Samoa Standard Time" to "Pacific/Apia",
        "Line Islands Standard Time" to "Pacific/Kiritimati",
        "Mauritius Standard Time" to "Indian/Mauritius",
        "UTC+12" to "Etc/GMT-12",
        "UTC+13" to "Etc/GMT-13",
        "Dateline Standard Time" to "Etc/GMT+12",
        "UTC-11" to "Etc/GMT+11",
        "UTC-09" to "Etc/GMT+9",
        "UTC-08" to "Etc/GMT+8",
        "UTC-02" to "Etc/GMT+2",
        "Hawaiian Standard Time" to "Pacific/Honolulu",
        "Aleutian Standard Time" to "America/Adak",
        "Alaskan Standard Time" to "America/Anchorage",
        "Pacific Standard Time" to "America/Los_Angeles",
        "Pacific Standard Time (Mexico)" to "America/Tijuana",
        "US Mountain Standard Time" to "America/Phoenix",
        "Mountain Standard Time" to "America/Denver",
        "Mountain Standard Time (Mexico)" to "America/Mazatlan",
        "Yukon Standard Time" to "America/Whitehorse",
        "Central Standard Time" to "America/Chicago",
        "Central Standard Time (Mexico)" to "America/Mexico_City",
        "Canada Central Standard Time" to "America/Regina",
        "Central America Standard Time" to "America/Guatemala",
        "Easter Island Standard Time" to "Pacific/Easter",
        "Eastern Standard Time" to "America/New_York",
        "US Eastern Standard Time" to "America/Indiana/Indianapolis",
        "Eastern Standard Time (Mexico)" to "America/Cancun",
        "SA Pacific Standard Time" to "America/Bogota",
        "Haiti Standard Time" to "America/Port-au-Prince",
        "Cuba Standard Time" to "America/Havana",
        "Turks And Caicos Standard Time" to "America/Grand_Turk",
        "Venezuela Standard Time" to "America/Caracas",
        "Paraguay Standard Time" to "America/Asuncion",
        "Atlantic Standard Time" to "America/Halifax",
        "Central Brazilian Standard Time" to "America/Cuiaba",
        "SA Western Standard Time" to "America/La_Paz",
        "Pacific SA Standard Time" to "America/Santiago",
        "Newfoundland Standard Time" to "America/St_Johns",
        "Tocantins Standard Time" to "America/Araguaina",
        "E. South America Standard Time" to "America/Sao_Paulo",
        "SA Eastern Standard Time" to "America/Cayenne",
        "Argentina Standard Time" to "America/Argentina/Buenos_Aires",
        "Montevideo Standard Time" to "America/Montevideo",
        "Magallanes Standard Time" to "America/Punta_Arenas",
        "Saint Pierre Standard Time" to "America/Miquelon",
        "Bahia Standard Time" to "America/Bahia",
    )
    private val WINDOWS_LOWER = WINDOWS_ZONES.mapKeys { it.key.lowercase() }
    const val REFERENCE_YEAR = 2026

    private val available: Set<String> by lazy { ZoneId.getAvailableZoneIds() }
    private val cache = java.util.concurrent.ConcurrentHashMap<String, ZoneId>()

    fun isValid(name: String?): Boolean = !name.isNullOrEmpty() && name in available

    fun zone(name: String): ZoneId = cache.getOrPut(name) { ZoneId.of(name) }

    /** The event's zone, or the fallback (device zone) for a floating event or an unknown name. */
    fun zoneOr(name: String?, fallback: ZoneId): ZoneId = if (isValid(name)) zone(name!!) else fallback

    /** The zone of the event's end: its `end_tz` when known (timed events with `tz` only), else the start's. */
    fun endZoneOr(e: Event, fallback: ZoneId): ZoneId =
        if (!e.allDay && e.tz != null && isValid(e.endTz)) zone(e.endTz!!) else zoneOr(e.tz, fallback)

    /** Wall time of [zone] -> instant (gap: moved forward by the gap; overlap: the first instant). */
    fun toInstant(wall: LocalDateTime, zone: ZoneId): Instant = ZonedDateTime.ofLocal(wall, zone, null).toInstant()

    fun toWall(instant: Instant, zone: ZoneId): LocalDateTime = LocalDateTime.ofInstant(instant, zone)

    fun dayStart(d: LocalDate, zone: ZoneId): Instant = toInstant(d.atStartOfDay(), zone)

    fun offsetMinutes(zone: ZoneId, instant: Instant): Int = zone.rules.getOffset(instant).totalSeconds / 60

    data class Transition(val instant: Instant, val before: Int, val after: Int)

    /** Each offset change of the year, to the minute (probed day by day, like the Python side). */
    fun transitions(zone: ZoneId, year: Int): List<Transition> {
        val out = ArrayList<Transition>()
        var t = LocalDate.of(year, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC)
        val end = LocalDate.of(year + 1, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC)
        var prev = offsetMinutes(zone, t)
        while (t < end) {
            val next = t.plus(1, ChronoUnit.DAYS)
            val off = offsetMinutes(zone, next)
            if (off != prev) {
                var lo = t
                var hi = next
                while (ChronoUnit.MINUTES.between(lo, hi) > 1) {
                    val mid = lo.plus(ChronoUnit.MINUTES.between(lo, hi) / 2, ChronoUnit.MINUTES)
                    if (offsetMinutes(zone, mid) == prev) lo = mid else hi = mid
                }
                out.add(Transition(hi, prev, off))
                prev = off
            }
            t = next
        }
        return out
    }

    private data class Signature(val std: Int, val dst: Int?, val dstMonth: Int?, val stdMonth: Int?)

    private fun signature(zone: ZoneId, year: Int): Signature? {
        val trans = transitions(zone, year)
        if (trans.isEmpty()) return Signature(offsetMinutes(zone, LocalDate.of(year, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC)), null, null, null)
        if (trans.size != 2) return null
        val std = minOf(trans[0].after, trans[1].after)
        val dst = maxOf(trans[0].after, trans[1].after)
        val toDst = trans.first { it.after == dst }
        val toStd = trans.first { it.after == std }
        return Signature(std, dst, monthUtc(toDst.instant), monthUtc(toStd.instant))
    }

    private fun monthUtc(i: Instant) = LocalDateTime.ofInstant(i, ZoneOffset.UTC).monthValue

    /** The first zone of the table whose rules of the reference year match an embedded VTIMEZONE. */
    fun recognise(std: Int, dst: Int?, dstMonth: Int?, stdMonth: Int?): String? {
        if (dst == null) {
            if (std % 60 == 0 && Math.abs(std) <= 14 * 60) {
                val hours = -std / 60
                return if (hours == 0) "Etc/UTC" else "Etc/GMT" + (if (hours > 0) "+" else "-") + Math.abs(hours)
            }
            return null
        }
        val wanted = Signature(std, dst, dstMonth, stdMonth)
        for (name in WINDOWS_ZONES.values.distinct()) {
            if (!isValid(name)) continue
            if (signature(zone(name), REFERENCE_YEAR) == wanted) return name
        }
        return null
    }

    /** What an embedded VTIMEZONE says: standard and daylight offsets (minutes) and their RRULE months. */
    data class VTimezoneInfo(val std: Int?, val dst: Int?, val dstMonth: Int?, val stdMonth: Int?)

    /** ICS TZID -> IANA name, or null. */
    fun resolveTzid(tzid: String, vtimezone: VTimezoneInfo? = null): String? {
        val name = tzid.trim().trim('"')
        if (isValid(name)) return name
        WINDOWS_LOWER[name.lowercase()]?.let { return it }
        val parts = name.split("/").filter { it.isNotEmpty() }
        for (n in listOf(3, 2)) {
            if (parts.size > n) {
                val tail = parts.takeLast(n).joinToString("/")
                if (isValid(tail)) return tail
            }
        }
        if (vtimezone?.std != null) return recognise(vtimezone.std, vtimezone.dst, vtimezone.dstMonth, vtimezone.stdMonth)
        return null
    }

    // ---- VTIMEZONE output ----

    private fun fmtOffset(minutes: Int): String {
        val sign = if (minutes >= 0) "+" else "-"
        val m = Math.abs(minutes)
        return String.format(java.util.Locale.ROOT, "%s%02d%02d", sign, m / 60, m % 60)
    }

    private val DAYS = listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")

    /** VTIMEZONE of an IANA zone: the yearly rules of [year] (DTSTART in 1970), or a fixed offset. */
    fun vtimezoneLines(name: String, year: Int): List<String> {
        val zone = zone(name)
        val trans = transitions(zone, year)
        val out = arrayListOf("BEGIN:VTIMEZONE", "TZID:$name")
        if (trans.size != 2) {
            val off = offsetMinutes(zone, LocalDate.of(year, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC))
            out += listOf("BEGIN:STANDARD", "TZOFFSETFROM:${fmtOffset(off)}", "TZOFFSETTO:${fmtOffset(off)}",
                "DTSTART:19700101T000000", "END:STANDARD", "END:VTIMEZONE")
            return out
        }
        val dstOff = maxOf(trans[0].after, trans[1].after)
        for (t in trans.sortedBy { if (it.after == dstOff) 0 else 1 }) {
            val kind = if (t.after == dstOff) "DAYLIGHT" else "STANDARD"
            val local = LocalDateTime.ofInstant(t.instant, ZoneOffset.UTC).plusMinutes(t.before.toLong())
            val day = local.toLocalDate()
            val n = if (day.dayOfMonth + 7 > day.lengthOfMonth()) -1 else (day.dayOfMonth - 1) / 7 + 1
            val first = LocalDate.of(1970, day.monthValue, 1)
            val d1970 = if (n > 0) first.with(TemporalAdjusters.dayOfWeekInMonth(n, day.dayOfWeek))
            else first.with(TemporalAdjusters.lastInMonth(day.dayOfWeek))
            out += listOf(
                "BEGIN:$kind", "TZOFFSETFROM:${fmtOffset(t.before)}", "TZOFFSETTO:${fmtOffset(t.after)}",
                String.format(java.util.Locale.ROOT, "DTSTART:%04d%02d%02dT%02d%02d%02d", 1970, d1970.monthValue, d1970.dayOfMonth, local.hour, local.minute, local.second),
                "RRULE:FREQ=YEARLY;BYMONTH=${local.monthValue};BYDAY=$n${DAYS[day.dayOfWeek.value - 1]}", "END:$kind",
            )
        }
        out.add("END:VTIMEZONE")
        return out
    }
}
