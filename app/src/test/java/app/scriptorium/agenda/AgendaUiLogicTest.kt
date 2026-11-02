package app.scriptorium.agenda

import app.scriptorium.Note
import app.scriptorium.NoteFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId

private val PARIS: ZoneId = ZoneId.of("Europe/Paris")

private fun note(extra: String, title: String = "Réunion", id: String = "abc123", path: String = "agenda/2026/x-$id.md", body: String = ""): Note =
    NoteFormat.parse(path, "---\nid: $id\ntitle: $title\ncreated: 2026-10-01T10:00:00+02:00\nupdated: 2026-10-01T10:00:00+02:00\n$extra---\n$body", 0)

private fun ev(extra: String, title: String = "Réunion", id: String = "abc123", body: String = ""): Event =
    EventFormat.fromNote(note(extra, title, id, body = body))!!

private fun utc(s: String): Instant = Instant.parse(s)

class RuleEditorTest {
    private val thu = Stamp(LocalDate.of(2026, 11, 12), LocalTime.of(14, 30)) // 2nd Thursday of November

    private fun round(rule: String, start: Stamp = thu, tz: String? = "Europe/Paris"): String {
        val spec = RuleEditor.parse(rule, start, tz, PARIS) ?: error("not expressible: $rule")
        return RuleEditor.build(spec, start, tz, PARIS)
    }

    @Test
    fun presetsBuildTheUsualRules() {
        val d = thu.date
        fun b(p: RepeatPreset) = RuleEditor.build(RuleEditor.presetSpec(p, d)!!, thu, "Europe/Paris", PARIS)
        assertEquals("FREQ=DAILY", b(RepeatPreset.DAILY))
        assertEquals("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR", b(RepeatPreset.WEEKDAYS))
        assertEquals("FREQ=WEEKLY;BYDAY=TH", b(RepeatPreset.WEEKLY))
        assertEquals("FREQ=WEEKLY;INTERVAL=2;BYDAY=TH", b(RepeatPreset.BIWEEKLY))
        assertEquals("FREQ=MONTHLY;BYMONTHDAY=12", b(RepeatPreset.MONTHLY_DAY))
        assertEquals("FREQ=MONTHLY;BYDAY=2TH", b(RepeatPreset.MONTHLY_NTH))
        assertEquals("FREQ=YEARLY", b(RepeatPreset.YEARLY))
    }

    @Test
    fun presetDetection() {
        fun d(r: String?) = RuleEditor.detect(r, thu, "Europe/Paris", PARIS)
        assertEquals(RepeatPreset.NONE, d(null))
        assertEquals(RepeatPreset.DAILY, d("FREQ=DAILY"))
        assertEquals(RepeatPreset.WEEKDAYS, d("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR"))
        assertEquals(RepeatPreset.WEEKDAYS, d("FREQ=DAILY;BYDAY=FR,MO,TU,WE,TH"))
        assertEquals(RepeatPreset.WEEKLY, d("FREQ=WEEKLY"))
        assertEquals(RepeatPreset.WEEKLY, d("FREQ=WEEKLY;WKST=SU;BYDAY=TH"))
        assertEquals(RepeatPreset.BIWEEKLY, d("FREQ=WEEKLY;INTERVAL=2;BYDAY=TH"))
        assertEquals(RepeatPreset.MONTHLY_DAY, d("FREQ=MONTHLY"))
        assertEquals(RepeatPreset.MONTHLY_NTH, d("FREQ=MONTHLY;BYDAY=TH;BYSETPOS=2"))
        assertEquals(RepeatPreset.YEARLY, d("RRULE:FREQ=YEARLY;BYMONTH=11;BYMONTHDAY=12"))
        assertEquals(RepeatPreset.CUSTOM, d("FREQ=WEEKLY;BYDAY=TU,TH"))
        assertEquals(RepeatPreset.CUSTOM, d("FREQ=DAILY;COUNT=5"))
        assertEquals(RepeatPreset.CUSTOM, d("FREQ=MONTHLY;BYDAY=-1FR"))
        assertEquals(RepeatPreset.IMPORTED, d("FREQ=HOURLY"))
        assertEquals(RepeatPreset.IMPORTED, d("FREQ=MONTHLY;BYDAY=MO,TU;BYSETPOS=1"))
        assertEquals(RepeatPreset.IMPORTED, d("FREQ=YEARLY;BYWEEKNO=20"))
        assertEquals(RepeatPreset.IMPORTED, d("FREQ=WEEKLY;INTERVAL=2;WKST=SU;BYDAY=MO,SU"))
        assertEquals(RepeatPreset.IMPORTED, d("FREQ=MONTHLY;BYMONTHDAY=1,15"))
    }

    @Test
    fun customRulesRoundTrip() {
        assertEquals("FREQ=DAILY;INTERVAL=3", round("FREQ=DAILY;INTERVAL=3"))
        assertEquals("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE", round("FREQ=WEEKLY;INTERVAL=2;BYDAY=WE,MO"))
        assertEquals("FREQ=MONTHLY;BYDAY=-1FR", round("FREQ=MONTHLY;BYDAY=FR;BYSETPOS=-1"))
        assertEquals("FREQ=MONTHLY;INTERVAL=2;BYMONTHDAY=-1", round("FREQ=MONTHLY;INTERVAL=2;BYMONTHDAY=-1"))
        assertEquals("FREQ=YEARLY;BYMONTH=3;BYMONTHDAY=5", round("FREQ=YEARLY;BYMONTH=3;BYMONTHDAY=5"))
        assertEquals("FREQ=WEEKLY;BYDAY=TH;COUNT=10", round("FREQ=WEEKLY;COUNT=10"))
    }

    @Test
    fun untilFollowsTheEventForm() {
        val spec = RuleEditor.defaults(Freq.WEEKLY, thu.date).copy(until = LocalDate.of(2026, 12, 31))
        // zoned: end of the last day in Paris, in UTC (winter: UTC+1)
        assertEquals("FREQ=WEEKLY;BYDAY=TH;UNTIL=20261231T225959Z", RuleEditor.build(spec, thu, "Europe/Paris", PARIS))
        assertEquals("FREQ=WEEKLY;BYDAY=TH;UNTIL=20261231T235959", RuleEditor.build(spec, thu, null, PARIS))
        assertEquals("FREQ=WEEKLY;BYDAY=TH;UNTIL=20261231", RuleEditor.build(spec, Stamp(thu.date), null, PARIS))
        // and back to the same day, whatever the form
        for (tz in listOf("Europe/Paris", null)) {
            val r = RuleEditor.build(spec, thu, tz, PARIS)
            assertEquals(LocalDate.of(2026, 12, 31), RuleEditor.parse(r, thu, tz, PARIS)!!.until)
        }
        // the last Thursday before UNTIL is the last occurrence
        val e = ev("start: 2026-11-12T14:30\ntz: Europe/Paris\nrrule: ${RuleEditor.build(spec, thu, "Europe/Paris", PARIS)}\n")
        val all = RecurrenceExpander.expand(e, utc("2026-01-01T00:00:00Z"), utc("2028-01-01T00:00:00Z"), PARIS).occurrences
        assertEquals(LocalDate.of(2026, 12, 31), all.last().start.date)
    }

    @Test
    fun labelsAndDescriptions() {
        assertEquals("Toutes les semaines le jeudi", RuleEditor.presetLabel(RepeatPreset.WEEKLY, thu.date))
        assertEquals("Tous les mois le 2e jeudi", RuleEditor.presetLabel(RepeatPreset.MONTHLY_NTH, thu.date))
        assertEquals("Tous les mois le dernier jeudi", RuleEditor.presetLabel(RepeatPreset.MONTHLY_NTH, LocalDate.of(2026, 10, 29)))
        assertEquals("Tous les ans le 12 novembre", RuleEditor.presetLabel(RepeatPreset.YEARLY, thu.date))
        val s = RuleEditor.parse("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,TH;COUNT=6", thu, "Europe/Paris", PARIS)!!
        assertEquals("Toutes les 2 semaines le lundi et le jeudi, 6 fois", RuleEditor.describe(s))
        assertEquals("Règle importée : FREQ=HOURLY", RuleEditor.summary("FREQ=HOURLY", thu, null, PARIS))
        assertEquals("Tous les jours", RuleEditor.summary("FREQ=DAILY", thu, null, PARIS))
    }

    @Test
    fun nextOccurrencesPreview() {
        val e = ev("start: 2026-11-12T14:30\ntz: Europe/Paris\nrrule: FREQ=WEEKLY;INTERVAL=2;BYDAY=TH\nexdate: [2026-11-26T14:30]\n")
        assertEquals("Prochaines : 12 nov., 10 déc., 24 déc.", RuleEditor.nextText(e, utc("2026-11-01T00:00:00Z"), PARIS, LocalDate.of(2026, 11, 1)))
        val yearly = ev("start: 2026-02-28\nrrule: FREQ=YEARLY\n")
        assertEquals("Prochaines : 28 févr. 2027, 28 févr. 2028, 28 févr. 2029",
            RuleEditor.nextText(yearly, utc("2026-11-01T00:00:00Z"), PARIS, LocalDate.of(2026, 11, 1)))
        val over = ev("start: 2026-01-05\nrrule: FREQ=DAILY;COUNT=2\n")
        assertEquals("Aucune occurrence à venir", RuleEditor.nextText(over, utc("2026-11-01T00:00:00Z"), PARIS, LocalDate.of(2026, 11, 1)))
    }
}

class EventDraftTest {
    @Test
    fun draftRoundTripKeepsTheRuleVerbatim() {
        val e = ev("start: 2026-11-12T14:30\nend: 2026-11-12T15:15\ntz: Europe/Paris\nrrule: FREQ=MONTHLY;BYDAY=MO,TU;BYSETPOS=1\nalerts: [-15m]\nlocation: Salle 2\n")
        val d = EventDraft.fromEvent(e)
        assertEquals(e.copy(warnings = emptyList()), d.applyTo(e))
    }

    @Test
    fun startMoveKeepsDurationAndValidates() {
        val d = EventDraft(startDate = LocalDate.of(2026, 3, 1), startTime = LocalTime.of(23, 0), endDate = LocalDate.of(2026, 3, 2), endTime = LocalTime.of(1, 0))
        val m = d.withStart(LocalDate.of(2026, 3, 5), LocalTime.of(10, 0))
        assertEquals(LocalDate.of(2026, 3, 5), m.endDate)
        assertEquals(LocalTime.of(12, 0), m.endTime)
        assertEquals("La fin est avant le début", m.copy(endTime = LocalTime.of(9, 0)).problem())
        assertNull(m.problem())
    }

    @Test
    fun allDayEndIsInclusiveAndZoneFree() {
        val d = EventDraft(allDay = true, startDate = LocalDate.of(2026, 12, 1), endDate = LocalDate.of(2026, 12, 3), tz = "Europe/Paris")
        val e = d.applyTo(EventFormat.newEvent("x", Stamp(LocalDate.of(2026, 1, 1))))
        assertEquals(Stamp(LocalDate.of(2026, 12, 3)), e.end)
        assertNull(e.tz)
        val single = d.copy(endDate = d.startDate).applyTo(e)
        assertNull(single.end)
    }

    @Test
    fun noEndStaysWithoutEnd() {
        val e = ev("start: 2026-11-12T14:30\n")
        val d = EventDraft.fromEvent(e)
        assertNull(d.applyTo(e).end)
        assertEquals(Stamp(LocalDate.of(2026, 11, 12), LocalTime.of(16, 0)), d.copy(endTime = LocalTime.of(16, 0)).applyTo(e).end)
    }

    @Test
    fun exdatesFollowTheSeriesTimeAndForm() {
        val e = ev("start: 2026-11-12T14:30\ntz: Europe/Paris\nrrule: FREQ=WEEKLY\nexdate: [2026-11-19T14:30, 2026-11-26T09:00]\n")
        val later = EventDraft.fromEvent(e).withStart(e.start.date, LocalTime.of(16, 0)).applyTo(e)
        assertEquals(listOf(Stamp(LocalDate.of(2026, 11, 19), LocalTime.of(16, 0)), Stamp(LocalDate.of(2026, 11, 26), LocalTime.of(9, 0))), later.exdate)
        val occurrences = RecurrenceExpander.expand(later, utc("2026-11-01T00:00:00Z"), utc("2026-12-01T00:00:00Z"), PARIS).occurrences
        assertFalse(occurrences.any { it.start.date == LocalDate.of(2026, 11, 19) })
        val allDay = EventDraft.fromEvent(e).copy(allDay = true).applyTo(e)
        assertEquals(listOf(Stamp(LocalDate.of(2026, 11, 19)), Stamp(LocalDate.of(2026, 11, 26))), allDay.exdate)
    }
}

class AgendaLayoutTest {
    private val settings = CalendarSettings()

    private fun entries(vararg events: Event, from: String = "2026-11-01T00:00:00Z", to: String = "2026-12-01T00:00:00Z") =
        AgendaIndex.entries(AgendaSources(events = events.toList(), settings = settings), utc(from), utc(to), PARIS).entries

    @Test
    fun monthGridStartsOnTheChosenDay() {
        val g = AgendaLayout.monthGrid(YearMonth.of(2026, 11), DayOfWeek.MONDAY)
        assertEquals(LocalDate.of(2026, 10, 26), g.first())
        assertEquals(LocalDate.of(2026, 12, 6), g.last())
        assertEquals(42, g.size)
        val sunday = AgendaLayout.monthGrid(YearMonth.of(2026, 2), DayOfWeek.SUNDAY)
        assertEquals(LocalDate.of(2026, 2, 1), sunday.first())
        assertEquals(28, sunday.size)
        assertEquals(DayOfWeek.SUNDAY, AgendaLayout.weekdayOrder(DayOfWeek.SUNDAY).first())
    }

    @Test
    fun groupByDayKeepsTodayAndSpreadsMultiDayEntries() {
        val trip = ev("start: 2026-11-10\nend: 2026-11-12\n", "Voyage", "trip01")
        val meet = ev("start: 2026-11-11T09:00\nend: 2026-11-11T10:00\ntz: Europe/Paris\n", "Point", "meet01")
        val groups = AgendaLayout.groupByDay(entries(trip, meet), LocalDate.of(2026, 11, 1), LocalDate.of(2026, 12, 1), LocalDate.of(2026, 11, 5))
        assertEquals(listOf(LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 11), LocalDate.of(2026, 11, 12)), groups.map { it.date })
        assertTrue(groups[0].entries.isEmpty())
        assertEquals(listOf("Voyage", "Point"), groups[2].entries.map { it.title })
    }

    @Test
    fun monthCellShowsThreeAndCountsTheRest() {
        val list = (1..5).map { ev("start: 2026-11-11T0$it:00\ntz: Europe/Paris\n", "E$it", "ev000$it") }
        val day = AgendaLayout.perDay(entries(*list.toTypedArray()), listOf(LocalDate.of(2026, 11, 11)))[LocalDate.of(2026, 11, 11)]!!
        val (shown, more) = AgendaLayout.cell(day)
        assertEquals(listOf("E1", "E2", "E3"), shown.map { it.title })
        assertEquals(2, more)
    }

    @Test
    fun overlappingEntriesShareColumns() {
        val a = ev("start: 2026-11-11T09:00\nend: 2026-11-11T11:00\ntz: Europe/Paris\n", "A", "aaaaa1")
        val b = ev("start: 2026-11-11T10:00\nend: 2026-11-11T12:00\ntz: Europe/Paris\n", "B", "bbbbb1")
        val c = ev("start: 2026-11-11T11:00\nend: 2026-11-11T11:30\ntz: Europe/Paris\n", "C", "ccccc1")
        val d = ev("start: 2026-11-11T14:00\ntz: Europe/Paris\n", "D", "ddddd1")
        val night = ev("start: 2026-11-10T22:00\nend: 2026-11-11T02:00\ntz: Europe/Paris\n", "Nuit", "night1")
        val placed = AgendaLayout.placeTimed(entries(a, b, c, d, night), LocalDate.of(2026, 11, 11), PARIS).associateBy { it.entry.title }
        assertEquals(0, placed["Nuit"]!!.startMin)
        assertEquals(120, placed["Nuit"]!!.endMin)
        assertEquals(1, placed["Nuit"]!!.columns)
        assertEquals(listOf(0, 1, 0), listOf("A", "B", "C").map { placed[it]!!.column })
        assertEquals(listOf(2, 2, 2), listOf("A", "B", "C").map { placed[it]!!.columns })
        assertEquals(14 * 60 + AgendaLayout.MIN_BLOCK_MIN, placed["D"]!!.endMin)
        assertEquals(1, placed["D"]!!.columns)
    }

    @Test
    fun dstDayGridUsesRealMinutes() {
        // 29 March 2026: Paris skips 02:00 -> 03:00; 04:00 local is 3 hours after midnight
        val e = ev("start: 2026-03-29T04:00\nend: 2026-03-29T05:00\ntz: Europe/Paris\n", "Tôt", "early1")
        val p = AgendaLayout.placeTimed(entries(e, from = "2026-03-01T00:00:00Z", to = "2026-04-01T00:00:00Z"), LocalDate.of(2026, 3, 29), PARIS).single()
        assertEquals(180, p.startMin)
    }

    @Test
    fun longTimedEntriesGoToTheStrip() {
        val long = ev("start: 2026-11-10T08:00\nend: 2026-11-12T08:00\ntz: Europe/Paris\n", "Salon", "salon1")
        val e = entries(long).single()
        assertTrue(AgendaLayout.isStrip(e))
        assertTrue(AgendaLayout.placeTimed(listOf(e), LocalDate.of(2026, 11, 11), PARIS).isEmpty())
    }

    @Test
    fun searchFindsNextOrLatestOccurrence() {
        val weekly = ev("start: 2026-01-08T19:00\ntz: Europe/Paris\nrrule: FREQ=WEEKLY\nlocation: Gymnase\n", "Badminton", "badmin")
        val past = ev("start: 2025-06-01\n", "Dentiste", "dent01", body = "Carte vitale")
        val noteNote = note("start: 2026-12-24\n", "Cadeaux", "gift01", path = "cadeaux-gift01.md")
        val s = AgendaSources(events = listOf(weekly, past), notes = listOf(noteNote), settings = settings)
        val now = utc("2026-11-02T12:00:00Z")
        assertEquals(LocalDate.of(2026, 11, 5), AgendaLayout.search(s, "gymnase", now, PARIS).single().startDate)
        assertEquals(LocalDate.of(2025, 6, 1), AgendaLayout.search(s, "carte VITALE", now, PARIS).single().startDate)
        assertEquals(EntryKind.NOTE, AgendaLayout.search(s, "cadéaux", now, PARIS).single().kind)
        assertTrue(AgendaLayout.search(s, "   ", now, PARIS).isEmpty())
    }

    @Test
    fun defaultStartIsNextHourToday() {
        val now = LocalDateTime.of(2026, 11, 2, 14, 20)
        assertEquals(LocalDateTime.of(2026, 11, 2, 15, 0), AgendaLayout.defaultStart(now.toLocalDate(), now))
        assertEquals(LocalDateTime.of(2026, 11, 3, 9, 0), AgendaLayout.defaultStart(LocalDate.of(2026, 11, 3), now))
    }
}

class AgendaTextTest {
    @Test
    fun timeLines() {
        val s = CalendarSettings()
        fun one(e: Event) = AgendaIndex.entries(AgendaSources(events = listOf(e), settings = s), utc("2026-11-01T00:00:00Z"), utc("2027-01-01T00:00:00Z"), PARIS).entries.first()
        val timed = one(ev("start: 2026-11-11T20:00\nend: 2026-11-11T20:30\ntz: Europe/Paris\n"))
        assertEquals("20:00 - 20:30", AgendaText.timeLine(timed, PARIS, true))
        assertEquals("8:00 PM - 8:30 PM", AgendaText.timeLine(timed, PARIS, false))
        assertEquals("Toute la journée", AgendaText.timeLine(one(ev("start: 2026-11-11\n")), PARIS, true))
        assertEquals("Du 1 au 3 déc.", AgendaText.timeLine(one(ev("start: 2026-12-01\nend: 2026-12-03\n")), PARIS, true))
        assertEquals("Du 30 nov. au 2 déc.", AgendaText.timeLine(one(ev("start: 2026-11-30\nend: 2026-12-02\n")), PARIS, true))
        assertEquals("09:00", AgendaText.timeLine(one(ev("start: 2026-11-11T09:00\ntz: Europe/Paris\n")), PARIS, true))
        // seen from New York, in the device zone or in the event's own
        val ny = ZoneId.of("America/New_York")
        val paris = AgendaIndex.entries(AgendaSources(events = listOf(ev("start: 2026-11-11T14:30\nend: 2026-11-11T15:00\ntz: Europe/Paris\n")), settings = s),
            utc("2026-11-01T00:00:00Z"), utc("2027-01-01T00:00:00Z"), ny).entries.first()
        assertEquals("08:30 - 09:00", AgendaText.timeLine(paris, ny, true))
        assertEquals("14:30 - 15:00", AgendaText.timeLine(paris, ny, true, eventZone = true))
        val night = one(ev("start: 2026-11-10T22:00\nend: 2026-11-12T02:00\ntz: Europe/Paris\n"))
        assertEquals("À partir de 22:00", AgendaText.timeLineOn(night, LocalDate.of(2026, 11, 10), PARIS, true))
        assertEquals("Toute la journée", AgendaText.timeLineOn(night, LocalDate.of(2026, 11, 11), PARIS, true))
        assertEquals("Jusqu'à 02:00", AgendaText.timeLineOn(night, LocalDate.of(2026, 11, 12), PARIS, true))
    }

    @Test
    fun alertLabels() {
        assertEquals("Au début", AgendaText.alertLabel(0, false))
        assertEquals("15 min avant", AgendaText.alertLabel(-15, false))
        assertEquals("1 h 30 avant", AgendaText.alertLabel(-90, false))
        assertEquals("1 jour avant", AgendaText.alertLabel(-1440, false))
        assertEquals("1 semaine avant", AgendaText.alertLabel(-10080, false))
        assertEquals("La veille à 18:00", AgendaText.alertLabel(-360, true))
        assertEquals("Le jour même à 09:00", AgendaText.alertLabel(540, true))
        assertEquals("2 jours avant à 09:00", AgendaText.alertLabel(-2340, true))
    }

    @Test
    fun dayTitles() {
        val today = LocalDate.of(2026, 11, 2)
        assertEquals("Aujourd'hui", AgendaText.dayTitle(today, today))
        assertEquals("Demain", AgendaText.dayTitle(today.plusDays(1), today))
        assertEquals("dimanche 1er novembre", AgendaText.dayTitle(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 5)))
        assertEquals("vendredi 1er janvier 2027", AgendaText.dayTitle(LocalDate.of(2027, 1, 1), today))
    }
}

class WidgetRowsTest {
    private val settings = CalendarSettings(
        calendars = listOf(Calendar("perso", "Perso", "#4F8BF5"), Calendar("travail", "Travail", "#E0663A")),
    )

    @Test
    fun rowsGroupByDayWithDateBlockOnFirstRowOnly() {
        val now = utc("2026-11-11T15:00:00Z") // 16:00 in Paris
        val events = listOf(
            ev("start: 2026-11-11\ncalendar: travail\n", "Virements", "vir001"),
            ev("start: 2026-11-11T08:00\nend: 2026-11-11T09:00\ntz: Europe/Paris\n", "Passé", "past01"),
            ev("start: 2026-11-11T20:00\nend: 2026-11-11T20:30\ntz: Europe/Paris\n", "Poubelles", "poub01"),
            ev("start: 2026-11-13T10:00\ntz: Europe/Paris\ncalendar: travail\n", "Réunion", "reun01"),
            ev("start: 2026-11-09\nend: 2026-11-12\n", "Congés", "conge1"),
            ev("start: 2026-12-20\n", "Trop loin", "far001"),
        )
        val note = note("start: 2026-11-13\n", "Courses", "cours1", path = "courses-cours1.md", body = "- [x] pain\n- [ ] lait\n")
        val entries = AgendaIndex.entries(AgendaSources(events = events, notes = listOf(note), settings = settings),
            utc("2026-11-10T23:00:00Z"), utc("2026-11-24T23:00:00Z"), PARIS).entries
        val rows = WidgetRows.rows(entries, now, PARIS, WidgetOptions(days = 14), true) { "🚴" }
        assertEquals(listOf("Congés", "Virements", "Poubelles", "Courses", "Réunion"), rows.map { it.title })
        assertEquals(listOf("mer.", null, null, "ven.", null), rows.map { it.weekday })
        assertEquals(listOf("11", null, null, "13", null), rows.map { it.dayNumber })
        assertEquals("Du 9 au 12 nov.", rows[0].time)
        assertEquals("20:00 - 20:30", rows[2].time)
        assertEquals("📝", rows[3].icon)
        assertEquals("#E0663A", rows[1].color)

        val withPast = WidgetRows.rows(entries, now, PARIS, WidgetOptions(showFinished = true), true) { "" }
        assertTrue(withPast.any { it.title == "Passé" })
        val noWork = WidgetRows.rows(entries, now, PARIS, WidgetOptions(hiddenCalendars = setOf("travail"), showNotes = false), true) { "" }
        assertEquals(listOf("Congés", "Poubelles"), noWork.map { it.title })
        val week = WidgetRows.rows(entries, now, PARIS, WidgetOptions(days = 7), true) { "" }
        assertFalse(week.any { it.title == "Trop loin" })
    }

    @Test
    fun todaySummary() {
        val now = utc("2026-11-11T15:00:00Z")
        val events = listOf(
            ev("start: 2026-11-11\n", "Journée", "jour01"),
            ev("start: 2026-11-11T18:00\ntz: Europe/Paris\n", "Apéro", "apero1"),
            ev("start: 2026-11-11T20:00\ntz: Europe/Paris\n", "Film", "film01"),
            ev("start: 2026-11-11T08:00\nend: 2026-11-11T09:00\ntz: Europe/Paris\n", "Fini", "fini01"),
        )
        val entries = AgendaIndex.entries(AgendaSources(events = events, settings = settings), utc("2026-11-10T23:00:00Z"), utc("2026-11-11T23:00:00Z"), PARIS).entries
        val t = WidgetRows.today(entries, now, PARIS)
        assertEquals("Apéro", t.next!!.title)
        assertEquals(2, t.others)
        assertNull(WidgetRows.today(emptyList(), now, PARIS).next)
    }

    @Test
    fun optionsRoundTrip() {
        val o = WidgetOptions(days = 30, showFinished = true, showRefresh = false, opacity = 40, hiddenCalendars = setOf("travail"), showBirthdays = false)
        assertEquals(o, WidgetOptions.parse(o.toJson()))
        assertEquals(WidgetOptions(), WidgetOptions.parse(null))
        assertEquals(14, WidgetOptions.parse("{\"days\": 5}").days)
    }
}

class ReminderPlannerTest {
    private val now = utc("2026-11-11T12:00:00Z")

    private fun alarm(key: String, at: String) = Alarm(key, utc(at), EntryKind.EVENT, key, "id", null, utc(at), false, -15)

    @Test
    fun futureAlarmsAreRegisteredAndVanishedOnesCancelled() {
        val book = ReminderBook(scheduled = mapOf("gone|x|-15" to utc("2026-11-11T13:00:00Z").toEpochMilli(), "a|1|-15" to 0L))
        val p = ReminderPlanner.plan(listOf(alarm("a|1|-15", "2026-11-11T14:00:00Z"), alarm("b|1|0", "2026-11-12T08:00:00Z")), book, now)
        assertEquals(listOf("a|1|-15", "b|1|0"), p.schedule.map { it.key })
        assertEquals(listOf("gone|x|-15"), p.cancel)
        assertEquals(setOf("a|1|-15", "b|1|0"), p.book.scheduled.keys)
        assertEquals(utc("2026-11-11T14:00:00Z").toEpochMilli(), p.book.scheduled["a|1|-15"])
    }

    @Test
    fun firedAlarmsAreNeverRegisteredAgain() {
        val a = alarm("a|1|-15", "2026-11-11T14:00:00Z")
        val book = ReminderPlanner.fired(ReminderBook(scheduled = mapOf(a.key to a.at.toEpochMilli())), a.key, a.at.toEpochMilli())
        val p = ReminderPlanner.plan(listOf(a, a), book, now)
        assertTrue(p.schedule.isEmpty())
        assertTrue(a.key in p.book.fired)
        // a sync bringing the same event twice (same key) registers it once
        val twice = ReminderPlanner.plan(listOf(alarm("c|1|0", "2026-11-11T16:00:00Z"), alarm("c|1|0", "2026-11-11T16:00:00Z")), ReminderBook(), now)
        assertEquals(1, twice.schedule.size)
    }

    @Test
    fun lateAlarmsOnlyWhenRegisteredBefore() {
        val late = alarm("late|1|0", "2026-11-11T11:30:00Z")
        val fresh = alarm("new|1|0", "2026-11-11T11:45:00Z")
        val tooOld = alarm("old|1|0", "2026-11-11T10:00:00Z")
        val book = ReminderBook(scheduled = listOf(late, tooOld).associate { it.key to it.at.toEpochMilli() })
        val p = ReminderPlanner.plan(listOf(late, fresh, tooOld), book, now)
        assertEquals(listOf("late|1|0"), p.schedule.map { it.key })
        assertEquals(listOf("old|1|0"), p.cancel)
    }

    @Test
    fun oldFiredKeysAreForgottenAndBookRoundTrips() {
        val book = ReminderBook(fired = mapOf("x" to utc("2026-11-01T00:00:00Z").toEpochMilli(), "y" to utc("2026-11-10T00:00:00Z").toEpochMilli()),
            snoozed = mapOf(ReminderPlanner.snoozeKey("y", 5) to utc("2026-11-11T12:10:00Z").toEpochMilli()))
        val p = ReminderPlanner.plan(emptyList(), book, now)
        assertEquals(setOf("y"), p.book.fired.keys)
        assertEquals(1, p.book.snoozed.size)
        assertEquals(p.book, ReminderBook.parse(p.book.toJson()))
        assertTrue(ReminderPlanner.isSnooze(ReminderPlanner.snoozeKey("y", 5)))
    }

    @Test
    fun alarmKeysAreStableAcrossRescans() {
        val e = ev("start: 2026-11-11T14:00\ntz: Europe/Paris\nrrule: FREQ=DAILY\nalerts: [-15m, -1d]\n")
        val s = AgendaSources(events = listOf(e))
        val first = AgendaIndex.alarms(s, now, PARIS).map { it.key }
        val again = AgendaIndex.alarms(s.copy(events = listOf(EventFormat.fromNote(EventFormat.toNote(e))!!)), now, PARIS).map { it.key }
        assertEquals(first, again)
        assertEquals(4, first.size)
    }
}
