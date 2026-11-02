package app.memento.agenda

import app.memento.NoteFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Regressions found by the adversarial review of the Android agenda. */
class AgendaReviewTest {
    private val paris: ZoneId = ZoneId.of("Europe/Paris")

    private fun ev(extra: String, title: String = "E", id: String = "abc123"): Event = EventFormat.fromNote(NoteFormat.parse(
        "agenda/2026/x-$id.md", "---\nid: $id\ntitle: $title\ncreated: 2026-10-01T10:00:00+02:00\nupdated: 2026-10-01T10:00:00+02:00\n$extra---\n", 0,
    ))!!

    @Test
    fun allDayAlertsKeepTheirWallTimeAcrossDaylightSaving() {
        // Tuesday 31 March 2026, all-day; the clocks went forward on Sunday 29 March
        val e = ev("start: 2026-03-31\nalerts: [-9540m, -2340m, -360m]\n")
        val got = AgendaIndex.alarms(AgendaSources(listOf(e)), Instant.parse("2026-03-24T00:00:00Z"), paris, java.time.Duration.ofDays(8))
        assertEquals(
            listOf("2026-03-24T09:00", "2026-03-29T09:00", "2026-03-30T18:00"),
            got.map { java.time.LocalDateTime.ofInstant(it.at, paris).toString() },
        )
        // timed alerts stay exact durations (what every calendar app does)
        val t = ev("start: 2026-03-30T09:00\ntz: Europe/Paris\nalerts: [-1d]\n", id = "abc124")
        val a = AgendaIndex.alarms(AgendaSources(listOf(t)), Instant.parse("2026-03-28T00:00:00Z"), paris).single()
        assertEquals(Instant.parse("2026-03-29T07:00:00Z"), a.at)
    }

    @Test
    fun aRuleBiweeklyRejectsAtRunTimeDoesNotBreakTheAgenda() {
        // BYMONTH=13 passes biweekly's parser and throws AssertionError (an Error) from its iterator
        val bad = ev("start: 2026-11-05T10:00\ntz: Europe/Paris\nrrule: FREQ=YEARLY;BYMONTH=13\nalerts: [-10m]\n", "Cassé")
        val good = ev("start: 2026-11-06T10:00\ntz: Europe/Paris\n", "Bon", id = "abc124")
        val s = AgendaSources(listOf(bad, good))
        val r = AgendaIndex.entries(s, Instant.parse("2026-11-01T00:00:00Z"), Instant.parse("2026-12-01T00:00:00Z"), paris)
        assertEquals(listOf("Cassé", "Bon"), r.entries.map { it.title })
        assertTrue(r.problems.toString(), r.problems.single().contains("règle de répétition illisible"))
        assertEquals(1, AgendaIndex.alarms(s, Instant.parse("2026-11-05T00:00:00Z"), paris).size)
        assertNotNull(RuleEditor.nextStarts(bad, Instant.parse("2026-11-01T00:00:00Z"), 3, paris))

        val ics = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:x\r\nDTSTART:20261105T100000\r\nRRULE:FREQ=YEARLY;BYMONTH=13\r\n" +
            "SUMMARY:Cassé\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        val store = AgendaStore(MemoryFs())
        val parsed = IcsImport.read(listOf(IcsSource.fromBytes("a.ics", ics.toByteArray())), paris)
        val (report, _) = IcsImport.apply(store, parsed, ImportOptions(since = LocalDate.of(2026, 1, 1)), emptyList(), store.calendars(), paris)
        assertEquals(1, report.created)
    }

    @Test
    fun aPresetRuleFollowsTheNewStartDay() {
        val mon = Stamp(LocalDate.of(2026, 11, 9), LocalTime.of(9, 0))
        val wed = Stamp(LocalDate.of(2026, 11, 11), LocalTime.of(9, 0))
        fun follow(rule: String?) = RuleEditor.followStart(rule, mon, wed, "Europe/Paris", paris)
        assertEquals("FREQ=WEEKLY;BYDAY=WE", follow("FREQ=WEEKLY;BYDAY=MO"))
        assertEquals("FREQ=WEEKLY;INTERVAL=2;BYDAY=WE", follow("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO"))
        assertEquals("FREQ=MONTHLY;BYDAY=2WE", follow("FREQ=MONTHLY;BYDAY=2MO"))
        assertEquals("FREQ=MONTHLY;BYMONTHDAY=11", follow("FREQ=MONTHLY;BYMONTHDAY=9"))
        assertEquals("FREQ=DAILY", follow("FREQ=DAILY"))
        // custom and imported rules are the user's: untouched
        assertEquals("FREQ=WEEKLY;BYDAY=MO,TH", follow("FREQ=WEEKLY;BYDAY=MO,TH"))
        assertEquals("FREQ=WEEKLY;BYDAY=MO;COUNT=4", follow("FREQ=WEEKLY;BYDAY=MO;COUNT=4"))
        assertEquals("FREQ=MONTHLY;BYDAY=MO;BYSETPOS=-1;BYHOUR=9", follow("FREQ=MONTHLY;BYDAY=MO;BYSETPOS=-1;BYHOUR=9"))
        assertEquals(null, follow(null))
        assertEquals("FREQ=WEEKLY;BYDAY=MO", RuleEditor.followStart("FREQ=WEEKLY;BYDAY=MO", mon, Stamp(mon.date, LocalTime.of(10, 0)), "Europe/Paris", paris))
    }

    @Test
    fun exportVectorRoundTripsWithOverridesAndNoExdateForThem() {
        val store = AgendaStore(app.memento.FileFs(java.io.File(Vectors.DIR, "export")))
        val before = Vectors.occurrences(store)
        val yoga = before.filter { (it["title"] as String).startsWith("Yoga") }.map { it["start"] }
        assertEquals(7, yoga.size)
        assertTrue("2026-10-15T16:30:00Z" !in yoga && "2026-10-20T16:30:00Z" !in yoga)
        val now = Instant.parse("2026-11-01T09:20:00Z")
        val text = IcsExport.exportCalendar(store.scan().events, store.calendars(), "perso", now).text
        assertTrue("EXDATE;TZID=Europe/Paris:20261015T183000" in text) // a date alone on a timed series: its start time
        assertTrue("EXDATE;TZID=Europe/Paris:20261020T183000" !in text) // overridden, not excluded
        assertTrue("RECURRENCE-ID;TZID=Europe/Paris:20261020T183000" in text)
        val (zip, _) = IcsExport.exportAll(store.scan().events, store.calendars(), now)
        val fresh = AgendaStore(MemoryFs())
        val parsed = IcsImport.read(listOf(IcsSource.fromBytes("export.zip", zip)), Vectors.DEVICE, Vectors.NOW)
        IcsImport.apply(fresh, parsed, ImportOptions(), emptyList(), fresh.calendars(), Vectors.DEVICE, "again", Vectors.NOW)
        assertEquals(before, Vectors.occurrences(fresh))
    }

    @Test
    fun aDateExdateOnATimedSeriesRemovesOnlyTheStartTimeOccurrence() {
        val e = ev("start: 2026-11-02T09:00\ntz: Europe/Paris\nrrule: FREQ=DAILY;BYHOUR=9,17;COUNT=4\nexdate: [2026-11-02]\n")
        val got = RecurrenceExpander.expand(e, Instant.parse("2026-11-01T00:00:00Z"), Instant.parse("2026-11-10T00:00:00Z"), paris).occurrences
        assertEquals(listOf("2026-11-02T17:00", "2026-11-03T09:00", "2026-11-03T17:00"), got.map { it.start.toString() })
    }

    @Test
    fun snoozesOfVanishedRemindersAreCancelledAndOneSnoozePerNotification() {
        val series = ev("start: 2026-11-02T09:00\ntz: Europe/Paris\nrrule: FREQ=DAILY\nexdate: [2026-11-04T09:00]\nalerts: [-15m]\n")
        val n = NoteFormat.parse("n-n00003.md", "---\nid: n00003\ntitle: Note\nreminder: 2026-11-03T12:00:00+01:00\n---\n", 0)
        val s = AgendaSources(listOf(series), listOf(n))
        assertTrue(AgendaIndex.alarmAlive(s, "abc123|2026-11-03T09:00|-15", paris))
        assertTrue(AgendaIndex.alarmAlive(s, "n00003|reminder|${n.reminder}", paris))
        assertTrue(!AgendaIndex.alarmAlive(s, "abc123|2026-11-04T09:00|-15", paris)) // occurrence deleted
        assertTrue(!AgendaIndex.alarmAlive(s, "abc123|2026-11-03T09:00|-30", paris)) // alert removed
        assertTrue(!AgendaIndex.alarmAlive(s, "abc123|2026-11-03T10:00|-15", paris)) // moved
        assertTrue(!AgendaIndex.alarmAlive(AgendaSources(), "abc123|2026-11-03T09:00|-15", paris)) // event deleted
        assertTrue(!AgendaIndex.alarmAlive(s, "n00003|reminder|1", paris))

        assertEquals(ReminderPlanner.snoozeKey("abc123|2026-11-03T09:00|-15"), ReminderPlanner.snoozeKey("abc123|2026-11-03T09:00|-15"))
        val now = Instant.parse("2026-11-03T08:00:00Z")
        val alive = ReminderPlanner.snoozeKey("abc123|2026-11-03T09:00|-15")
        val gone = ReminderPlanner.snoozeKey("abc123|2026-11-04T09:00|-15")
        val book = ReminderBook(snoozed = mapOf(alive to now.toEpochMilli(), gone to now.toEpochMilli()))
        val p = ReminderPlanner.plan(emptyList(), book, now) { AgendaIndex.alarmAlive(s, it, paris) }
        assertEquals(listOf(gone), p.cancel)
        assertEquals(setOf(alive), p.book.snoozed.keys)
    }
}
