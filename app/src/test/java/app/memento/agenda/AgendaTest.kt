package app.memento.agenda

import app.memento.Fs
import app.memento.FileFs
import app.memento.Note
import app.memento.NoteFormat
import app.memento.activity.ActivityInfo
import app.memento.activity.Sport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** In-memory [Fs] for the agenda tests. */
class MemoryFs(val files: MutableMap<String, ByteArray> = LinkedHashMap()) : Fs {
    override fun list(dir: String): List<Fs.Entry> {
        val prefix = if (dir.isEmpty()) "" else "$dir/"
        val out = LinkedHashMap<String, Boolean>()
        for (p in files.keys) if (p.startsWith(prefix)) {
            val rest = p.substring(prefix.length)
            val name = rest.substringBefore('/')
            out[name] = (out[name] ?: false) || '/' in rest
        }
        return out.map { Fs.Entry(it.key, it.value, 1_790_000_000_000L) }
    }
    override fun readBytes(path: String) = files[path]
    override fun writeBytes(path: String, bytes: ByteArray) { files[path] = bytes }
    override fun delete(path: String) { files.remove(path) }
    override fun exists(path: String) = path in files
    override fun lastModified(path: String) = if (path in files) 1_790_000_000_000L else 0L
    override fun describe() = "memory"
}

/** The shared vectors of `testdata/agenda/` (same computation as `windows/tests/agenda_vectors.py`). */
object Vectors {
    val DIR = File("../testdata/agenda")
    val DEVICE: ZoneId = ZoneId.of("Europe/Paris")
    val FROM: Instant = Instant.parse("2026-01-01T00:00:00Z")
    val TO: Instant = Instant.parse("2027-01-01T00:00:00Z")
    const val NOW = 1_793_520_000_000L
    const val IMPORT_ID = "20261101T092000-vector"
    private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")

    fun iso(i: Instant): String = ISO.format(LocalDateTime.ofInstant(i, ZoneOffset.UTC))

    fun occurrences(store: AgendaStore): List<Map<String, Any?>> {
        val scan = store.scan()
        assertEquals(emptyList<String>(), scan.problems)
        val r = AgendaIndex.entries(AgendaSources(events = scan.events, settings = store.calendars()), FROM, TO, DEVICE)
        assertEquals(emptyList<String>(), r.problems)
        return r.entries.map { e ->
            if (e.allDay) linkedMapOf("title" to e.title, "all_day" to true, "start" to e.startDate.toString(), "end" to e.endDate.toString())
            else linkedMapOf("title" to e.title, "all_day" to false, "start" to iso(e.start), "end" to iso(e.end))
        }
    }

    class Imported(val store: AgendaStore, val previews: List<CalendarPreview>, val report: ImportReport)

    fun import(name: String, fs: MemoryFs = MemoryFs()): Imported {
        val store = AgendaStore(fs)
        val parsed = IcsImport.read(listOf(IcsSource.fromFile(File(DIR, "$name.ics"))), DEVICE, NOW)
        // No default reminders: the vectors check the ICS mapping alone (same on the Python side).
        val settings = store.calendars().withDefaultAlerts(false, emptyList())
        val previews = IcsImport.preview(parsed, emptyList(), settings)
        val (report, _) = IcsImport.apply(store, parsed, ImportOptions(), emptyList(), settings, DEVICE, IMPORT_ID, NOW)
        return Imported(store, previews, report)
    }

    fun compute(name: String): Map<String, Any?> {
        val imp = import(name)
        return linkedMapOf(
            "preview" to imp.previews.map { p ->
                linkedMapOf(
                    "name" to p.name, "events" to p.events.toLong(), "series" to p.series.toLong(), "exceptions" to p.exceptions.toLong(),
                    "cancelled" to p.cancelled.toLong(), "ignored" to p.ignored.toLong(), "unresolved_zones" to p.unresolvedZones.toLong(),
                    "first" to p.first?.toString(), "last" to p.last?.toString(), "errors" to p.errors.size.toLong(),
                )
            },
            "created" to imp.report.created.toLong(),
            "occurrences" to occurrences(imp.store),
        )
    }

    fun filesOf(store: AgendaStore): Map<String, String> =
        store.paths().associate { p -> val t = store.fs.readText(p)!!; NoteFormat.parse(p, t, 0).title to t }

    @Suppress("UNCHECKED_CAST")
    fun expected(name: String): Map<String, Any?> = Json.parse(File(DIR, "expected/$name.json").readText()) as Map<String, Any?>

    fun names(): List<String> = File(DIR, "expected").listFiles()!!.map { it.name.removeSuffix(".json") }.sorted()
}

class AgendaVectorTest {
    private val exportNow = Instant.parse("2026-11-01T09:20:00Z")

    @Test
    fun vectorsGiveTheSameOccurrencesAndReportsAsPython() {
        val names = Vectors.names()
        assertEquals(listOf("google_perso", "outlook_calendrier", "proton_travail", "vols_paris_new_york"), names)
        for (name in names) {
            val expected = Vectors.expected(name)
            assertEquals(Vectors.NOW, expected["now_ms"])
            val got = Vectors.compute(name)
            assertEquals("$name preview", expected["preview"], got["preview"])
            assertEquals("$name created", expected["created"], got["created"])
            assertEquals("$name occurrences", expected["occurrences"], got["occurrences"])
        }
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun vectorFilesContainTheExpectedLines() {
        for (name in Vectors.names()) {
            val files = Vectors.filesOf(Vectors.import(name).store)
            for ((title, needles) in Vectors.expected(name)["files"] as Map<String, List<String>>) {
                val text = files[title] ?: fail("$name: $title missing").let { "" }
                for (n in needles) assertTrue("$name/$title: $n\n$text", n in text)
            }
        }
    }

    @Test
    fun roundTripExportImportGivesTheSameOccurrences() {
        for (name in Vectors.names()) {
            val store = Vectors.import(name).store
            val before = Vectors.occurrences(store)
            val (zip, warnings) = IcsExport.exportAll(store.scan().events, store.calendars(), exportNow)
            assertEquals(emptyList<String>(), warnings)
            val fresh = AgendaStore(MemoryFs())
            val parsed = IcsImport.read(listOf(IcsSource.fromBytes("export.zip", zip)), Vectors.DEVICE, Vectors.NOW)
            assertTrue(parsed.all { it.preview.errors.isEmpty() })
            IcsImport.apply(fresh, parsed, ImportOptions(), emptyList(), fresh.calendars(), Vectors.DEVICE, "again", Vectors.NOW)
            assertEquals(name, before, Vectors.occurrences(fresh))
        }
    }

    @Test
    fun exportIsByteIdenticalToPython() {
        val store = AgendaStore(FileFs(File(Vectors.DIR, "export")))
        val scan = store.scan()
        assertEquals(emptyList<String>(), scan.problems)
        val r = IcsExport.exportCalendar(scan.events, store.calendars(), "perso", exportNow)
        assertEquals(File(Vectors.DIR, "export/expected_perso.ics").readText(), r.text)
        assertEquals(listOf("Yoga : ligne ical_extra DTSTAMP ignorée (écrite par l'export)"), r.warnings)
    }

    @Test
    fun calendarsJsonIsWrittenLikePython() {
        val text = File(Vectors.DIR, "export/agenda/calendars.json").readText()
        val s = CalendarSettings.parse(text)
        assertEquals(text, s.toJson())
        assertEquals("perso", s.calendar("nope").id)
        assertNull(s.problem)
        val bad = CalendarSettings.parse("{oups")
        assertNotNull(bad.problem)
        val keep = CalendarSettings.parse("{\"calendars\": [{\"id\": \"a\", \"name\": \"A\", \"color\": \"#4F8BF5\", \"x\": 1}], \"y\": [1]}")
        assertTrue("\"x\": 1" in keep.toJson() && "\"y\": [" in keep.toJson())
    }
}

class AgendaLogicTest {
    private val paris: ZoneId = ZoneId.of("Europe/Paris")

    private fun note(extra: String, title: String = "Évènement", path: String = "agenda/2026/x-abc123.md", body: String = ""): Note =
        NoteFormat.parse(path, "---\nid: abc123\ntitle: $title\ncreated: 2026-10-01T10:00:00+02:00\nupdated: 2026-10-01T10:00:00+02:00\n$extra---\n$body", 0)

    private fun ev(extra: String, title: String = "Évènement"): Event = EventFormat.fromNote(note(extra, title))!!
    private fun utc(s: String): Instant = Instant.parse(s)
    private fun dt(y: Int, m: Int, d: Int, h: Int, mi: Int = 0) = Stamp.of(LocalDateTime.of(y, m, d, h, mi))
    private fun occ(e: Event, from: String = "2026-01-01T00:00:00Z", to: String = "2027-01-01T00:00:00Z", zone: ZoneId = paris, over: List<Stamp> = emptyList()) =
        RecurrenceExpander.expand(e, utc(from), utc(to), zone, over)

    @Test
    fun eventRoundTripKeepsUnknownKeysAndOrder() {
        val e = ev("start: 2026-11-12T14:30\nfoo: bar\nend: 2026-11-12T15:15\ntz: Europe/Paris\nalerts: [-15m, -1d, 2h]\nexdate:\n  - 2026-11-19T14:30\nical_extra:\n  - \"X-A:b;c\"\n  - \"CATEGORIES:Santé\"\n")
        assertEquals(listOf(-15, -1440, 120), e.alerts)
        assertEquals(listOf(dt(2026, 11, 19, 14, 30)), e.exdate)
        assertEquals(listOf("X-A:b;c", "CATEGORIES:Santé"), e.icalExtra)
        assertEquals(listOf("foo: bar"), e.note.extra)
        val text = NoteFormat.serialize(EventFormat.toNote(e))
        assertTrue(text, "start: 2026-11-12T14:30\nend: 2026-11-12T15:15\ntz: Europe/Paris\nexdate: [2026-11-19T14:30]\nalerts: [-15m, -1d, 2h]\n" in text)
        assertTrue(text.indexOf("ical_extra:") < text.indexOf("foo: bar"))
        assertEquals(e, EventFormat.fromNote(NoteFormat.parse(e.path, text, 0)))
    }

    @Test
    fun problemsAreReportedNotSwallowed() {
        try { ev("start: demain\n"); fail() } catch (_: AgendaFormatException) {}
        val e = ev("start: 2026-11-12\nend: 2026-11-12T10:00\nalerts: [-15x]\nexdate: [nope]\ntz: Mars/Olympus\n")
        assertNull(e.end); assertEquals(4, e.warnings.size); assertEquals("Mars/Olympus", e.tz)
        assertNull(EventFormat.fromNote(note("title2: x\n")))
        assertEquals(listOf(0, -10080, 2880, -90), listOf("0m", "-1w", "+2d", "-90m").map { Alerts.parse(it) })
        assertEquals(listOf("0m", "-1w", "2d", "-90m", "-1h"), listOf(0, -10080, 2880, -90, -60).map { Alerts.format(it) })
        assertEquals(Stamp(LocalDate.of(2026, 11, 12), LocalTime.of(8, 5, 9)), Stamp.parse("2026-11-12 08:05:09"))
    }

    @Test
    fun recurrenceRules() {
        val off = ev("start: 2026-11-10T09:00\ntz: Europe/Paris\nrrule: FREQ=WEEKLY;BYDAY=TH;COUNT=3\n")
        assertEquals(listOf(dt(2026, 11, 10, 9), dt(2026, 11, 12, 9), dt(2026, 11, 19, 9)), occ(off).occurrences.map { it.start })
        val dateEx = ev("start: 2026-11-02T09:00\nrrule: FREQ=DAILY;UNTIL=20261105T090000\nexdate: [2026-11-03]\n")
        assertEquals(listOf(2, 4, 5), occ(dateEx).occurrences.map { it.start.date.dayOfMonth })
        val keep = ev("start: 2026-11-02T09:00\ntz: Europe/Paris\nrrule: FREQ=DAILY;UNTIL=20261104T080000Z\n")
        val drop = ev("start: 2026-11-02T09:00\ntz: Europe/Paris\nrrule: FREQ=DAILY;UNTIL=20261104T075959Z\n")
        assertEquals(3, occ(keep).occurrences.size); assertEquals(2, occ(drop).occurrences.size)
        val series = ev("start: 2026-11-02T09:00\nrrule: FREQ=DAILY;COUNT=3\n")
        assertEquals(listOf(2, 4), occ(series, over = listOf(dt(2026, 11, 3, 9))).occurrences.map { it.start.date.dayOfMonth })
    }

    @Test
    fun zonesFloatingAllDayAndDst() {
        val ny = ZoneId.of("America/New_York")
        val floating = ev("start: 2026-11-05T08:00\n")
        val zoned = ev("start: 2026-11-05T08:00\ntz: Europe/Paris\n")
        assertEquals(utc("2026-11-05T13:00:00Z"), occ(floating, zone = ny).occurrences[0].startInstant)
        assertEquals(utc("2026-11-05T07:00:00Z"), occ(floating).occurrences[0].startInstant)
        assertEquals(utc("2026-11-05T07:00:00Z"), occ(zoned, zone = ny).occurrences[0].startInstant)
        val allDay = ev("start: 2026-11-20\nend: 2026-11-21\ntz: Asia/Tokyo\n")
        val o = occ(allDay, zone = ZoneId.of("America/Los_Angeles")).occurrences[0]
        assertEquals(Stamp(LocalDate.of(2026, 11, 21)), o.end)
        assertEquals(utc("2026-11-20T08:00:00Z"), o.startInstant); assertEquals(utc("2026-11-22T08:00:00Z"), o.endInstant)
        // gap: 02:30 does not exist on 29 March -> 03:30 CEST; fold: 02:30 on 25 October -> the first one (CEST)
        assertEquals(utc("2026-03-29T01:30:00Z"), Zones.toInstant(LocalDateTime.of(2026, 3, 29, 2, 30), paris))
        assertEquals(utc("2026-10-25T00:30:00Z"), Zones.toInstant(LocalDateTime.of(2026, 10, 25, 2, 30), paris))
    }

    @Test
    fun windowCapBadRuleAndSkipAhead() {
        val e = ev("start: 2026-11-05T23:00\nend: 2026-11-06T01:00\ntz: Europe/Paris\n")
        assertEquals(1, occ(e, "2026-11-05T23:00:00Z", "2026-11-06T00:00:00Z").occurrences.size)
        assertEquals(0, occ(e, "2026-11-06T00:00:00Z", "2026-11-07T00:00:00Z").occurrences.size)
        val point = ev("start: 2026-11-05T10:00\ntz: Europe/Paris\n")
        assertEquals(1, occ(point, "2026-11-05T09:00:00Z", "2026-11-05T09:00:01Z").occurrences.size)
        assertEquals(0, occ(point, "2026-11-05T08:00:00Z", "2026-11-05T09:00:00Z").occurrences.size)
        val minutely = occ(ev("start: 2026-01-01T00:00\nrrule: FREQ=MINUTELY\n"), "2026-01-01T00:00:00Z", "2026-02-01T00:00:00Z")
        assertTrue(minutely.truncated); assertEquals(RecurrenceExpander.MAX_OCCURRENCES, minutely.occurrences.size)
        val bad = occ(ev("start: 2026-11-01T10:00\nrrule: FREQ=SOMETIMES\n"))
        assertNotNull(bad.error); assertEquals(1, bad.occurrences.size)
        assertEquals(0, occ(ev("start: 2026-11-01T10:00\nstatus: cancelled\n")).occurrences.size)
        // skipping ahead (no COUNT) gives what a full walk from DTSTART gives
        for (rule in listOf("FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=-1", "FREQ=WEEKLY;INTERVAL=2;BYDAY=TU,SA", "FREQ=YEARLY;BYMONTH=2;BYMONTHDAY=29", "FREQ=DAILY;INTERVAL=3")) {
            val s = ev("start: 2016-02-29T07:15\ntz: Europe/Paris\nrrule: $rule\n")
            val walked = RecurrenceExpander.seriesStarts(s, paris).takeWhile { it.date.year < 2027 }.filter { it.date.year == 2026 }.toList()
            assertEquals(rule, walked, occ(s).occurrences.map { it.start })
        }
    }

    private fun settingsWith(layers: Map<String, Layer> = emptyMap()) = CalendarSettings(
        calendars = listOf(Calendar("perso", "Perso", "#4F8BF5"), Calendar("cache", "Caché", "#D9534F", false)),
        layers = CalendarSettings.defaultLayers() + layers,
    )

    @Test
    fun entriesKindsColoursAndVisibility() {
        val events = listOf(ev("start: 2026-11-10T10:00\ncalendar: inconnu\nlocation: Ici\n", "A"), ev("start: 2026-11-10T11:00\ncalendar: cache\n", "Caché"))
        val notes = listOf(
            NoteFormat.parse("liste-n00001.md", "---\nid: n00001\ntitle: Courses\nstart: 2026-11-10\n---\n- [x] a\n- [ ] b\n- [x] c\n", 0),
            NoteFormat.parse("rappel-n00002.md", "---\nid: n00002\ntitle: Rappel\nreminder: 2026-11-10T09:00:00+01:00\n---\n", 0),
        )
        val act = Note(path = "activities/sortie-a00001.md", id = "a00001", title = "Sortie", created = 0, updated = 0,
            activity = ActivityInfo(Sport.BIKE, utc("2026-11-10T22:30:00Z").toEpochMilli(), 3900, 3600, 12345.0))
        val sources = AgendaSources(events, notes, listOf(act), listOf(Birthday("c1", "Marie Dupont", "1992-11-10")), settingsWith())
        val r = AgendaIndex.entries(sources, utc("2026-11-09T23:00:00Z"), utc("2026-11-11T23:00:00Z"), paris)
        assertEquals(emptyList<String>(), r.problems)
        assertEquals(listOf(
            listOf(EntryKind.BIRTHDAY, "Anniversaire de Marie Dupont", "(34 ans)", "#D64D6F", true),
            listOf(EntryKind.NOTE, "Courses", "2/3", "#8E63CE", true),
            listOf(EntryKind.NOTE, "Rappel", null, "#8E63CE", false),
            listOf(EntryKind.EVENT, "A", "Ici", "#4F8BF5", false),
            listOf(EntryKind.ACTIVITY, "Sortie", "12,3 km · 1 h 05", "#2E9E5B", false),
        ), r.entries.map { listOf(it.kind, it.title, it.subtitle, it.color, it.allDay) })
        assertEquals(LocalDate.of(2026, 11, 10), r.entries.last().endDate)
        val hidden = AgendaIndex.entries(AgendaSources(events, notes, listOf(act), emptyList(), settingsWith(mapOf(
            "notes" to Layer(false, "#8E63CE"), "activities" to Layer(true, "#2E9E5B", sports = listOf("walk"))))),
            utc("2026-11-09T23:00:00Z"), utc("2026-11-11T23:00:00Z"), paris)
        assertEquals(listOf("A"), hidden.entries.map { it.title })
        assertEquals("12,3 km · 45 min", AgendaIndex.activitySubtitle(12250.0, 2700))
        assertEquals("1,0 km", AgendaIndex.activitySubtitle(950.0, 0))
    }

    @Test
    fun birthdays() {
        assertEquals(Triple(null, 2, 29), AgendaIndex.parseBirthday("--02-29"))
        assertEquals(Triple(1990, 5, 14), AgendaIndex.parseBirthday("19900514"))
        try { AgendaIndex.parseBirthday("1990-02-30"); fail() } catch (_: IllegalArgumentException) {}
        val b = listOf(Birthday("a", "Léap", "2000-02-29"), Birthday("b", "Sans année", "--03-01"), Birthday("c", "Bébé", "2027-02-01"), Birthday("d", "Cassé", "hier"))
        val r = AgendaIndex.entries(AgendaSources(birthdays = b, settings = settingsWith()), utc("2027-01-01T00:00:00Z"), utc("2027-03-31T00:00:00Z"), paris)
        assertEquals(listOf(
            Triple("Anniversaire de Bébé", LocalDate.of(2027, 2, 1), null),
            Triple("Anniversaire de Léap", LocalDate.of(2027, 2, 28), "(27 ans)"),
            Triple("Anniversaire de Sans année", LocalDate.of(2027, 3, 1), null),
        ), r.entries.map { Triple(it.title, it.startDate, it.subtitle) })
        assertEquals(listOf("Cassé : anniversaire illisible ('hier')"), r.problems)
    }

    @Test
    fun alarmsHorizonKeysAndReminders() {
        val series = ev("start: 2026-11-02T09:00\ntz: Europe/Paris\nrrule: FREQ=DAILY\nalerts: [-15m, -1d]\n", "Daily")
        val allDay = ev("start: 2026-11-03\nalerts: [-1h]\n", "Jour")
        val n = NoteFormat.parse("n-n00003.md", "---\nid: n00003\ntitle: Note\nreminder: 2026-11-03T12:00:00+01:00\n---\n", 0)
        val got = AgendaIndex.alarms(AgendaSources(listOf(series, allDay), listOf(n)), utc("2026-11-02T08:00:00Z"), paris)
        assertEquals(listOf(
            "2026-11-03T09:00|-1440" to "2026-11-02T08:00:00Z",
            "2026-11-03|-60" to "2026-11-02T22:00:00Z",
            "2026-11-03T09:00|-15" to "2026-11-03T07:45:00Z",
            "2026-11-04T09:00|-1440" to "2026-11-03T08:00:00Z",
            "reminder|${n.reminder}" to "2026-11-03T11:00:00Z",
            "2026-11-04T09:00|-15" to "2026-11-04T07:45:00Z",
        ), got.map { it.key.substringAfter('|') to Vectors.iso(it.at) })
    }

    @Test
    fun birthdayAlarmsCoverEveryContactAndFollowTheSetting() {
        val people = listOf(Birthday("c1", "Marie", "1990-11-18"), Birthday("c2", "Zoé", "--02-29"), Birthday("c3", "Bad", "hier"))
        val now = utc("2026-11-10T08:00:00Z")
        val got = AgendaIndex.alarms(AgendaSources(birthdays = people), now, paris) // default: 1 week before at 09:00
        assertEquals(listOf(Triple("c1|2026-11-18|-9540", "Anniversaire de Marie", "2026-11-11T08:00:00Z")),
            got.map { Triple(it.key, it.title, Vectors.iso(it.at)) })
        assertTrue(got.all { it.kind == EntryKind.BIRTHDAY })
        var settings = CalendarSettings().withBirthdayAlerts(listOf(-9540, -360)) // + the day before at 18:00
        val two = AgendaIndex.alarms(AgendaSources(birthdays = people, settings = settings), now, paris, Duration.ofDays(10))
        assertEquals(listOf("c1|2026-11-18|-9540" to "2026-11-11T08:00:00Z", "c1|2026-11-18|-360" to "2026-11-17T17:00:00Z"),
            two.map { it.key to Vectors.iso(it.at) })
        val src = AgendaSources(birthdays = people, settings = settings)
        assertTrue(AgendaIndex.alarmAlive(src, "c1|2026-11-18|-360", paris))
        assertFalse(AgendaIndex.alarmAlive(src, "c1|2026-11-18|-100", paris))
        settings = settings.withBirthdayAlerts(emptyList())
        assertEquals(emptyList<Alarm>(), AgendaIndex.alarms(AgendaSources(birthdays = people, settings = settings), now, paris))
        assertFalse(AgendaIndex.alarmAlive(AgendaSources(birthdays = people, settings = settings), "c1|2026-11-18|-360", paris))
        val hidden = CalendarSettings().withLayer(CalendarSettings.LAYER_BIRTHDAYS) { it.copy(visible = false) } // a hidden layer is not announced
        assertEquals(emptyList<Alarm>(), AgendaIndex.alarms(AgendaSources(birthdays = people, settings = hidden), now, paris))
    }

    @Test
    fun duplicatesPoliciesBodyPreservationAndUndo() {
        val store = Vectors.import("google_perso").store
        val dentiste = store.scan().events.first { it.title == "Dentiste" }
        store.save(dentiste.withNote { it.copy(body = "Mes notes à moi") })
        var settings = store.calendars()
        for ((policy, counts) in listOf(DuplicatePolicy.SKIP to Triple(0, 0, 6), DuplicatePolicy.UPDATE to Triple(0, 6, 0))) {
            val parsed = IcsImport.read(listOf(IcsSource.fromFile(File(Vectors.DIR, "google_perso.ics"))), Vectors.DEVICE, Vectors.NOW)
            val pv = IcsImport.preview(parsed, store.scan().events, settings)
            assertEquals("perso-google", pv[0].suggestedCalendar); assertEquals(5, pv[0].duplicates)
            val (rep, s2) = IcsImport.apply(store, parsed, ImportOptions(duplicates = policy), store.scan().events, settings, Vectors.DEVICE, "i-$policy", Vectors.NOW)
            settings = s2
            val c = rep.calendars[0]
            assertEquals(counts, Triple(c.created, c.updated, c.skippedDuplicates))
        }
        val events = store.scan().events
        assertEquals(6, events.size)
        assertEquals("Mes notes à moi", events.first { it.title == "Dentiste" }.body)
        assertTrue(events.all { it.importId == Vectors.IMPORT_ID })

        // a later import adds an exception to the old series; undoing it removes the exdate it added
        val yoga = events.first { it.title == "Yoga" }
        val late = "BEGIN:VCALENDAR\r\nX-WR-CALNAME:Perso Google\r\nBEGIN:VEVENT\r\nUID:yoga-004@google.com\r\nRECURRENCE-ID;TZID=Europe/Paris:20261022T183000\r\nDTSTART;TZID=Europe/Paris:20261023T183000\r\nSUMMARY:Yoga vendredi\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        val parsed = IcsImport.read(listOf(IcsSource.fromBytes("x.ics", late.toByteArray())), Vectors.DEVICE, Vectors.NOW)
        IcsImport.preview(parsed, events, settings)
        val (rep, s3) = IcsImport.apply(store, parsed, ImportOptions(), events, settings, Vectors.DEVICE, "late", Vectors.NOW)
        assertEquals(1, rep.calendars[0].exceptions)
        assertTrue(dt(2026, 10, 22, 18, 30) in store.scan().events.first { it.id == yoga.id }.exdate)
        val (undo, _) = IcsImport.undo(store, "late", store.scan().events, s3)
        assertEquals(1, undo.deleted); assertEquals(1, undo.exdatesRemoved)
        assertEquals(yoga.exdate, store.scan().events.first { it.id == yoga.id }.exdate)
    }

    @Test
    fun sinceCutoffUndoRemovesCalendarsZipAndLatin1() {
        val store = AgendaStore(MemoryFs())
        val parsed = IcsImport.read(listOf(IcsSource.fromFile(File(Vectors.DIR, "google_perso.ics"))), Vectors.DEVICE, Vectors.NOW)
        val (rep, settings) = IcsImport.apply(store, parsed, ImportOptions(since = LocalDate.of(2026, 11, 1)), emptyList(), store.calendars(), Vectors.DEVICE, "cut", Vectors.NOW)
        assertEquals(3, rep.calendars[0].beforeCutoff)
        assertEquals(listOf("Anniversaire de mariage", "Dentiste", "Vacances à la montagne"), store.scan().events.map { it.title }.sorted())
        val (undo, after) = IcsImport.undo(store, "cut", store.scan().events, settings)
        assertEquals(3, undo.deleted); assertEquals(listOf("perso-google"), undo.calendarsRemoved)
        assertEquals(listOf("perso"), after.calendars.map { it.id }); assertEquals(listOf("perso"), store.calendars().calendars.map { it.id })

        val zip = ByteArrayOutputStream()
        ZipOutputStream(zip).use { z ->
            z.putNextEntry(ZipEntry("Takeout/Agenda/Perso.ics")); z.write(File(Vectors.DIR, "proton_travail.ics").readBytes()); z.closeEntry()
            z.putNextEntry(ZipEntry("Takeout/Agenda/lisez-moi.txt")); z.write("x".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("Takeout/Agenda/vide.ics")); z.write("pas un calendrier\r\n".toByteArray()); z.closeEntry()
        }
        val fromZip = IcsImport.read(listOf(IcsSource.fromBytes("export.zip", zip.toByteArray())), Vectors.DEVICE, Vectors.NOW)
        assertEquals(listOf("Proton Travail", "vide"), fromZip.map { it.name })
        assertTrue(fromZip[1].preview.errors.isNotEmpty() && fromZip[1].events.isEmpty())

        val latin = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:x\r\nDTSTART;VALUE=DATE:20261105\r\nSUMMARY:Fête\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n".toByteArray(Charsets.ISO_8859_1)
        val s2 = AgendaStore(MemoryFs())
        val p2 = IcsImport.read(listOf(IcsSource.fromBytes("vieux.ics", latin)), Vectors.DEVICE, Vectors.NOW)
        assertEquals("Fête", p2[0].events[0].title)
        val (r2, cal2) = IcsImport.apply(s2, p2, ImportOptions(targets = mapOf(0 to ImportTarget(name = "Anciens", color = "#2B8FC4"))), emptyList(), s2.calendars(), Vectors.DEVICE, "l1", Vectors.NOW)
        assertEquals("1 événement, 0 série, 0 ignoré", r2.summary())
        assertEquals("#2B8FC4", cal2.calendar("anciens").color)
    }

    @Test
    fun icsHelpersZonesAndPalette() {
        val line = "DESCRIPTION:" + "é".repeat(80)
        val folded = Ics.fold(line)
        assertTrue(folded.split("\r\n").all { it.toByteArray().size <= 75 })
        assertEquals(listOf(1 to line), Ics.logicalLines(folded.byteInputStream()).toList())
        assertEquals("a,b;c\nd\\e", Ics.unescapeText("a\\,b\\;c\\nd\\\\e"))
        assertEquals("a\\,b\\;c\\nd\\\\e", Ics.escapeText("a,b;c\nd\\e"))
        assertEquals("Lien : https://x.org/a?b=1&c=2\n\nét&eacute;",
            Ics.htmlToText("<p>Lien&nbsp;: <a href=\"https://x.org/a?b=1&amp;c=2\">https://x.org/a?b=1&amp;c=2</a></p><p>&#233;t&eacute;</p>"))
        val p = Ics.parseLine("ATTENDEE;CN=\"Dupont, Marie\";ROLE=REQ:mailto:m@x.org")
        assertEquals(listOf("ATTENDEE", "Dupont, Marie", "mailto:m@x.org"), listOf(p.name, p.param("CN"), p.value))

        assertTrue(Zones.WINDOWS_ZONES.values.all { Zones.isValid(it) })
        assertEquals("Europe/Paris", Zones.resolveTzid("Romance Standard Time"))
        assertEquals("Europe/Paris", Zones.resolveTzid("/citadel.org/20190405_1/Europe/Paris"))
        assertNull(Zones.resolveTzid("Nowhere"))
        assertNull(Zones.recognise(330, null, null, null)); assertEquals("Etc/GMT-1", Zones.recognise(60, null, null, null))
        assertEquals(listOf("BEGIN:VTIMEZONE", "TZID:Asia/Tokyo", "BEGIN:STANDARD", "TZOFFSETFROM:+0900", "TZOFFSETTO:+0900",
            "DTSTART:19700101T000000", "END:STANDARD", "END:VTIMEZONE"), Zones.vtimezoneLines("Asia/Tokyo", 2026))

        fun lum(h: String): Double {
            fun f(i: Int): Double { val c = h.substring(i, i + 2).toInt(16) / 255.0; return if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4) }
            return 0.2126 * f(1) + 0.7152 * f(3) + 0.0722 * f(5)
        }
        fun contrast(a: String, b: String): Double { val x = lum(a); val y = lum(b); return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05) }
        assertEquals(12, CalendarSettings.PALETTE.size)
        assertTrue(CalendarSettings.PALETTE.all { contrast(it.first, "#FFFFFF") >= 3 && contrast(it.first, "#121212") >= 3 })
    }

    @Test
    fun storePathsExceptionsAndRestore() {
        val dir = kotlin.io.path.createTempDirectory("agenda").toFile()
        try {
            val store = AgendaStore(FileFs(dir))
            val series = store.save(EventFormat.newEvent("Cours", dt(2027, 1, 4, 18), dt(2027, 1, 4, 19), tz = "Europe/Paris", now = Vectors.NOW).copy(rrule = "FREQ=WEEKLY"))
            assertTrue(series.path.startsWith("agenda/2027/cours-")); assertEquals("${series.id}@memento", series.uid)
            val exc = store.makeException(series, dt(2027, 1, 11, 18), dt(2027, 1, 11, 19)).copy(start = dt(2027, 1, 12, 18), end = dt(2027, 1, 12, 19))
            var (s, e) = store.saveException(series, exc)
            val scan = store.scan()
            assertEquals(2, scan.events.size); assertEquals(emptyList<String>(), scan.problems)
            val r = AgendaIndex.entries(AgendaSources(scan.events, settings = store.calendars()), utc("2027-01-01T00:00:00Z"), utc("2027-01-20T00:00:00Z"), paris)
            assertEquals(listOf(4, 12, 18), r.entries.map { it.startDate.dayOfMonth })
            s = store.restoreOccurrence(s, e, dt(2027, 1, 11, 18))
            assertTrue(s.exdate.isEmpty()); assertEquals(1, store.scan().events.size)
            File(dir, "agenda/2027/casse-zzzzzz.md").writeText("---\nid: zzzzzz\ntitle: x\nstart: hier\n---\n")
            File(dir, "agenda/2027/.x.md.synctmp-1").writeText("partial")
            val again = store.scan()
            assertEquals(1, again.events.size); assertEquals(1, again.problems.size)
            assertTrue(again.problems[0], "début illisible" in again.problems[0])
            assertFalse(again.events.any { it.id == "zzzzzz" })
        } finally {
            dir.deleteRecursively()
        }
    }
}
