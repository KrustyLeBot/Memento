package app.memento.agenda

import app.memento.NoteFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class AgendaManagementTest {
    private val paris: ZoneId = ZoneId.of("Europe/Paris")
    private val now = Instant.parse("2026-11-02T12:00:00Z")

    private fun ev(extra: String, title: String, id: String): Event = EventFormat.fromNote(NoteFormat.parse(
        "agenda/2026/x-$id.md", "---\nid: $id\ntitle: $title\ncreated: 2026-10-01T10:00:00+02:00\nupdated: 2026-10-01T10:00:00+02:00\n$extra---\n", 0,
    ))!!

    @Test
    fun detailFindsTheOccurrenceOrTheNextOne() {
        val weekly = ev("start: 2026-10-01T19:00\ntz: Europe/Paris\nrrule: FREQ=WEEKLY\n", "Chorale", "chor01")
        val s = AgendaSources(events = listOf(weekly))
        val at = Instant.parse("2026-10-15T17:00:00Z")
        assertEquals(at, AgendaLayout.entryFor(s, "chor01", at, now, paris)!!.start)
        // that occurrence no longer exists (series moved): the next one from now
        val moved = s.copy(events = listOf(weekly.copy(start = Stamp(LocalDate.of(2026, 10, 1), LocalTime.of(20, 0)))))
        assertEquals(LocalDate.of(2026, 11, 5), AgendaLayout.entryFor(moved, "chor01", at, now, paris)!!.startDate)
        val past = ev("start: 2026-01-10\n", "Vieux", "old001")
        assertEquals(LocalDate.of(2026, 1, 10), AgendaLayout.entryFor(AgendaSources(events = listOf(past)), "old001", null, now, paris)!!.startDate)
        assertNull(AgendaLayout.entryFor(s, "nope00", null, now, paris))
    }

    @Test
    fun pastImportsAreListedLatestFirst() {
        val a = ev("start: 2026-01-10\nimport: 20261101T092000-abcdef\ncalendar: perso\n", "A", "imp001")
        val b = ev("start: 2026-01-11\nimport: 20261102T080000-ghijkl\ncalendar: sport\n", "B", "imp002")
        val c = ev("start: 2026-01-12\nimport: 20261102T080000-ghijkl\ncalendar: sport\n", "C", "imp003")
        val settings = CalendarSettings(calendars = listOf(Calendar("perso", "Perso", "#4F8BF5"), Calendar("sport", "Sport", "#2E9E5B", importId = "20261102T080000-ghijkl")))
        val list = AgendaLayout.pastImports(listOf(a, b, c, ev("start: 2026-01-12\n", "D", "noimp1")), settings)
        assertEquals(listOf("20261102T080000-ghijkl", "20261101T092000-abcdef"), list.map { it.id })
        assertEquals(2, list[0].events)
        assertEquals(listOf("Sport"), list[0].calendars)
        assertEquals(Instant.parse("2026-11-02T08:00:00Z"), list[0].at)
    }

    @Test
    fun calendarsAreAddedRemovedAndHoldTheirEvents() {
        val (s1, travail) = CalendarSettings().addCalendar("Travail")
        assertEquals("travail", travail.id)
        assertEquals(CalendarSettings.PALETTE[1].first, travail.color)
        val (s2, again) = s1.addCalendar("Travail")
        assertEquals("travail-2", again.id)
        val series = ev("start: 2026-01-05T09:00\ntz: Europe/Paris\nrrule: FREQ=WEEKLY\ncalendar: travail\n", "Point", "ser001")
        val exception = ev("start: 2026-01-12T10:00\ntz: Europe/Paris\nrecurrence_of: ser001\nrecurrence_at: 2026-01-12T09:00\n", "Point", "exc001")
        val other = ev("start: 2026-01-05\n", "Perso", "per001")
        assertEquals(listOf("ser001", "exc001"), s2.eventsOf("travail", listOf(series, exception, other)).map { it.id })
        val s3 = s2.copy(default = "travail").removeCalendar("travail")
        assertEquals("perso", s3.default)
        try {
            CalendarSettings().removeCalendar("perso")
            error("the last calendar cannot go")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun defaultAlertsAreSyncedInTheCalendarsFile() {
        val s = CalendarSettings()
        assertEquals(listOf(-15), s.defaultAlerts(false))
        assertEquals(emptyList<Int>(), s.defaultAlerts(true))
        val t = s.withDefaultAlerts(false, listOf(-60, -10)).withDefaultAlerts(true, listOf(-360))
        val back = CalendarSettings.parse(t.toJson())
        assertEquals(listOf(-60, -10), back.defaultAlerts(false))
        assertEquals(listOf(-360), back.defaultAlerts(true))
        assertEquals(listOf<Int>(), back.withDefaultAlerts(false, emptyList()).defaultAlerts(false))
    }

    @Test
    fun birthdayAlertsDefaultToAWeekBeforeAndAreSynced() {
        val s = CalendarSettings()
        assertEquals(listOf(-9540), s.birthdayAlerts()) // 1 week before at 09:00
        val back = CalendarSettings.parse(s.withBirthdayAlerts(listOf(-360, -9540, -360)).toJson())
        assertEquals(listOf(-9540, -360), back.birthdayAlerts())
        assertEquals(listOf<Int>(), CalendarSettings.parse(s.withBirthdayAlerts(emptyList()).toJson()).birthdayAlerts())
    }
}
