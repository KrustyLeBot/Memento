package app.memento.agenda

import app.memento.Note
import app.memento.NoteFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** `end_tz`: an end in its own zone (a flight), same rules as `windows/tests/test_agenda.py` (end_tz section). */
class EndZoneTest {
    private val paris: ZoneId = ZoneId.of("Europe/Paris")
    private val ny = "America/New_York"

    private fun note(extra: String): Note =
        NoteFormat.parse("agenda/2026/x-abc123.md", "---\nid: abc123\ntitle: Vol\ncreated: 2026-10-01T10:00:00+02:00\nupdated: 2026-10-01T10:00:00+02:00\n$extra---\n", 0)

    private fun ev(extra: String): Event = EventFormat.fromNote(note(extra))!!
    private fun utc(s: String): Instant = Instant.parse(s)
    private fun dt(y: Int, m: Int, d: Int, h: Int, mi: Int = 0) = Stamp.of(LocalDateTime.of(y, m, d, h, mi))
    private fun occ(e: Event) = RecurrenceExpander.expand(e, utc("2026-01-01T00:00:00Z"), utc("2027-01-01T00:00:00Z"), paris).occurrences

    @Test
    fun endTzIsReadWrittenAndOnlyKeptOnZonedTimedEvents() {
        val e = ev("start: 2026-12-10T10:00\nend: 2026-12-10T13:00\ntz: Europe/Paris\nend_tz: America/New_York\n")
        assertEquals("Europe/Paris", e.tz); assertEquals(ny, e.endTz); assertEquals(emptyList<String>(), e.warnings)
        val text = NoteFormat.serialize(EventFormat.toNote(e))
        assertTrue(text, "tz: Europe/Paris\nend_tz: America/New_York\n" in text)
        assertEquals(e, EventFormat.fromNote(NoteFormat.parse(e.path, text, 0)))
        val floating = ev("start: 2026-12-10T10:00\nend: 2026-12-10T13:00\nend_tz: America/New_York\n")
        assertNull(floating.endTz); assertTrue("end_tz ignoré" in floating.warnings[0])
        val allDay = ev("start: 2026-12-10\ntz: Europe/Paris\nend_tz: America/New_York\n")
        assertNull(allDay.endTz); assertTrue("end_tz ignoré" in allDay.warnings[0])
        val unknown = ev("start: 2026-12-10T10:00\nend: 2026-12-10T13:00\ntz: Europe/Paris\nend_tz: Mars/Olympus\n")
        assertEquals("Mars/Olympus", unknown.endTz); assertTrue("fuseau de fin inconnu" in unknown.warnings[0])
        assertEquals(utc("2026-12-10T12:00:00Z"), occ(unknown)[0].endInstant)
    }

    @Test
    fun endBeforeStartComparesInstantsWithEndTz() {
        val later = ev("start: 2026-12-10T14:00\nend: 2026-12-10T12:00\ntz: Europe/Paris\nend_tz: America/New_York\n")
        assertEquals(dt(2026, 12, 10, 12), later.end); assertEquals(emptyList<String>(), later.warnings)
        val before = ev("start: 2026-12-10T14:00\nend: 2026-12-10T07:00\ntz: Europe/Paris\nend_tz: America/New_York\n")
        assertNull(before.end); assertTrue("fin avant le début" in before.warnings[0])
    }

    @Test
    fun expansionKeepsTheFirstDurationAndGivesTheEndInItsZone() {
        val flight = ev("start: 2026-12-10T10:00\nend: 2026-12-10T13:00\ntz: Europe/Paris\nend_tz: America/New_York\n")
        val o = occ(flight)[0]
        assertEquals(listOf(utc("2026-12-10T09:00:00Z"), utc("2026-12-10T18:00:00Z")), listOf(o.startInstant, o.endInstant))
        assertEquals(dt(2026, 12, 10, 13), o.end)
        val series = ev("start: 2026-10-19T08:00\nend: 2026-10-19T08:30\ntz: Europe/Paris\nend_tz: Europe/London\nrrule: FREQ=WEEKLY;COUNT=2\n")
        assertEquals(
            listOf(Triple(utc("2026-10-19T06:00:00Z"), utc("2026-10-19T07:30:00Z"), dt(2026, 10, 19, 8, 30)),
                Triple(utc("2026-10-26T07:00:00Z"), utc("2026-10-26T08:30:00Z"), dt(2026, 10, 26, 8, 30))),
            occ(series).map { Triple(it.startInstant, it.endInstant, it.end) },
        )
        val entry = AgendaIndex.entries(AgendaSources(events = listOf(flight)), utc("2026-12-10T00:00:00Z"), utc("2026-12-11T00:00:00Z"), paris).entries[0]
        assertEquals(listOf(utc("2026-12-10T09:00:00Z"), utc("2026-12-10T18:00:00Z")), listOf(entry.start, entry.end))
        assertEquals(LocalDate.of(2026, 12, 10), entry.endDate)
        assertEquals("Départ 10:00 (Paris) → arrivée 13:00 (New York)", AgendaText.departureArrival(entry.occurrence!!, true))
        val back = ev("start: 2026-12-17T18:30\nend: 2026-12-18T08:00\ntz: America/New_York\nend_tz: Europe/Paris\n")
        assertEquals("Départ 18:30 (New York) → arrivée le 18 déc. à 08:00 (Paris)", AgendaText.departureArrival(occ(back)[0], true))
        assertNull(AgendaText.departureArrival(occ(ev("start: 2026-12-10T10:00\nend: 2026-12-10T13:00\ntz: Europe/Paris\n"))[0], true))
    }

    private fun ics(vararg lines: String): ByteArray =
        (listOf("BEGIN:VCALENDAR", "VERSION:2.0", "BEGIN:VEVENT", "UID:x@test") + lines + listOf("SUMMARY:Vol", "END:VEVENT", "END:VCALENDAR"))
            .joinToString("\r\n", postfix = "\r\n").toByteArray()

    private fun one(vararg lines: String): Pair<Event, CalendarPreview> {
        val p = IcsImport.read(listOf(IcsSource.fromBytes("v.ics", ics(*lines))), Vectors.DEVICE, Vectors.NOW)[0]
        return p.events[0] to p.preview
    }

    @Test
    fun importEndZoneRules() {
        var (e, _) = one("DTSTART;TZID=Europe/Paris:20261210T100000", "DTEND;TZID=America/New_York:20261210T130000")
        assertEquals(listOf(dt(2026, 12, 10, 10), dt(2026, 12, 10, 13)), listOf(e.start, e.end)); assertEquals("Europe/Paris" to ny, e.tz to e.endTz)
        e = one("DTSTART;TZID=Europe/Paris:20261210T100000", "DTEND;TZID=Europe/Paris:20261210T130000").first
        assertNull(e.endTz)
        e = one("DTSTART;TZID=Europe/Paris:20261210T100000", "DTEND:20261210T180000Z").first
        assertEquals(dt(2026, 12, 10, 19), e.end); assertEquals("Europe/Paris" to null, e.tz to e.endTz)
        e = one("DTSTART:20261210T090000Z", "DTEND:20261210T180000Z").first
        assertEquals(listOf(dt(2026, 12, 10, 10), dt(2026, 12, 10, 19)), listOf(e.start, e.end)); assertEquals("Europe/Paris" to null, e.tz to e.endTz)
        val (u, pv) = one("DTSTART;TZID=America/New_York:20261210T100000", "DTEND;TZID=Nowhere/Land:20261210T230000")
        assertEquals(dt(2026, 12, 10, 23), u.end); assertEquals(ny to "Europe/Paris", u.tz to u.endTz); assertEquals(1, pv.unresolvedZones)
        e = one("DTSTART;TZID=Europe/Paris:20261210T100000", "DTEND;TZID=America/New_York:20261210T030000").first
        assertNull(e.end); assertNull(e.endTz)
    }

    @Test
    fun exportWritesDtendInEndTzWithItsVtimezone() {
        val flight = EventFormat.newEvent("Vol", dt(2026, 12, 10, 10), dt(2026, 12, 10, 13), tz = "Europe/Paris", now = Vectors.NOW).copy(endTz = ny)
        val text = IcsExport.exportCalendar(listOf(flight), CalendarSettings(), null, utc("2026-11-01T09:20:00Z")).text
        assertTrue(text, "DTSTART;TZID=Europe/Paris:20261210T100000\r\n" in text)
        assertTrue(text, "DTEND;TZID=America/New_York:20261210T130000\r\n" in text)
        assertTrue("TZID:America/New_York\r\n" in text && "TZID:Europe/Paris\r\n" in text)
        val back = IcsImport.read(listOf(IcsSource.fromBytes("v.ics", text.toByteArray())), Vectors.DEVICE, Vectors.NOW)[0].events[0]
        assertEquals(listOf(flight.start, flight.end), listOf(back.start, back.end)); assertEquals("Europe/Paris" to ny, back.tz to back.endTz)
    }

    @Test
    fun draftEndZoneValidationAndApply() {
        val base = EventDraft(startDate = LocalDate.of(2026, 12, 10), startTime = LocalTime.of(10, 0), endTime = LocalTime.of(13, 0), tz = "Europe/Paris")
        val flight = base.copy(endTz = ny)
        assertNull(flight.problem())
        val applied = flight.applyTo(EventFormat.newEvent("", dt(2026, 12, 10, 10), now = Vectors.NOW))
        assertEquals(ny, applied.endTz); assertEquals(dt(2026, 12, 10, 13), applied.end)
        assertEquals(ny, EventDraft.fromEvent(applied).endTz)
        // 03:00 New York is 09:00 Paris, before the 10:00 departure; walls alone would not see it
        assertEquals("L'arrivée (heure de New York) doit être après le départ", flight.copy(endTime = LocalTime.of(3, 0)).problem())
        // Paris 14:00 -> New York 12:00: an earlier wall, a later instant
        assertNull(base.copy(startTime = LocalTime.of(14, 0), endTime = LocalTime.of(12, 0), endTz = ny).problem())
        // same zone as the start, all-day or no start zone: no end_tz written
        assertNull(base.copy(endTz = "Europe/Paris").applyTo(applied).endTz)
        assertNull(flight.copy(allDay = true).applyTo(applied).endTz)
        assertNull(flight.copy(tz = null).applyTo(applied).endTz)
    }
}
