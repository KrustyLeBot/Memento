package app.memento.agenda

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A mail invitation (`testdata/agenda/interview_0.ics`) vs a calendar export: default target, the preview's event
 * list, LOCATION placeholders, the meeting link of the description, the links of the detail. Same cases as
 * Python's `tests/test_agenda_invite.py`.
 */
class AgendaInviteTest {
    private val meet = "https://meet.proton.me/join/id-EXAMPLE001#pwd-testvector42"
    private val settings = CalendarSettings(listOf(Calendar("travail", "Travail", "#1E88E5"), Calendar("perso", "Perso", "#43A047")), "perso")

    private fun interview(zone: ZoneId = Vectors.DEVICE) =
        IcsImport.read(listOf(IcsSource.fromFile(File(Vectors.DIR, "interview_0.ics"))), zone, Vectors.NOW)

    @Test
    fun invitationGoesToTheDefaultCalendar() {
        val store = AgendaStore(MemoryFs())
        store.saveCalendars(settings)
        val parsed = interview()
        val pv = IcsImport.preview(parsed, emptyList(), settings)[0]
        assertFalse(pv.calendarExport)
        assertEquals("perso", pv.suggestedCalendar)
        assertEquals(1, pv.events)
        val e = parsed[0].events[0]
        assertEquals("Interview with Proton", e.title)
        assertEquals("Europe/Zurich", e.tz)
        assertEquals(Stamp.of(LocalDateTime.of(2026, 10, 26, 17, 0)), e.start)
        assertEquals(Stamp.of(LocalDateTime.of(2026, 10, 26, 18, 0)), e.end)
        assertNull(e.location)
        assertTrue(e.body, e.body.startsWith("Visio : $meet\n\nHi Camille,"))
        assertEquals(2, e.body.split(meet).size - 1)
        assertEquals(listOf(ImportItem("Interview with Proton", "lun. 26 oct. 2026, 17:00–18:00 (Zurich)", null)), pv.items)

        val (report, after) = IcsImport.apply(store, parsed, ImportOptions(), emptyList(), settings, Vectors.DEVICE, "inv", Vectors.NOW)
        assertEquals(emptyList<String>(), report.newCalendars)
        assertEquals(listOf("travail", "perso"), after.calendars.map { it.id })
        val events = store.scan().events
        assertEquals(listOf("Interview with Proton" to "perso"), events.map { it.title to after.calendar(it.calendar).id })
        val (undo, _) = IcsImport.undo(store, "inv", events, after)
        assertEquals(1, undo.deleted); assertEquals(emptyList<String>(), undo.calendarsRemoved)
        assertTrue(store.scan().events.isEmpty())
    }

    @Test
    fun invitationCanStillGoToANewCalendarNamedAfterTheFile() {
        val (report, after) = IcsImport.apply(AgendaStore(MemoryFs()), interview(), ImportOptions(mapOf(0 to ImportTarget())),
            emptyList(), settings, Vectors.DEVICE, "inv", Vectors.NOW)
        assertEquals(listOf("interview_0"), report.newCalendars.map { after.calendar(it).name })
    }

    @Test
    fun previewShowsTheDeviceTimeWhenItDiffers() {
        val ny = ZoneId.of("America/New_York")
        assertEquals("lun. 26 oct. 2026, 17:00–18:00 (Zurich) · soit lun. 26 oct. 2026, 12:00–13:00 (New York)",
            IcsImport.whenText(interview(ny)[0].events[0], ny))
    }

    @Test
    fun locationPlaceholdersAreNoLocation() {
        listOf("null", "NULL", "undefined", "None", "", "   ", null).forEach { assertNull(it, IcsImport.cleanLocation(it)) }
        assertEquals("Salle 4", IcsImport.cleanLocation("  Salle 4 "))
    }

    private fun ics(name: String?, count: Int, uid: String): ByteArray {
        val lines = arrayListOf("BEGIN:VCALENDAR", "VERSION:2.0")
        if (name != null) lines.add("X-WR-CALNAME:$name")
        for (i in 0 until count) lines += listOf("BEGIN:VEVENT", "UID:$uid$i", "DTSTART:202611%02dT090000Z".format(i % 28 + 1),
            "SUMMARY:E$i", "LOCATION:undefined", "END:VEVENT")
        lines.add("END:VCALENDAR")
        return (lines.joinToString("\r\n") + "\r\n").toByteArray()
    }

    @Test
    fun calendarExportsStillProposeNewCalendars() {
        val zip = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
                z.putNextEntry(ZipEntry("Takeout/perso@gmail.com.ics")); z.write(ics("Perso Google", 3, "a")); z.closeEntry()
                z.putNextEntry(ZipEntry("Takeout/sport.ics")); z.write(ics(null, 2, "b")); z.closeEntry()
            }
        }.toByteArray()
        val parsed = IcsImport.read(listOf(
            IcsSource.fromBytes("takeout.zip", zip), IcsSource.fromBytes("big.ics", ics(null, 21, "c")),
            IcsSource.fromBytes("small.ics", ics(null, 2, "d")),
        ), Vectors.DEVICE, Vectors.NOW)
        val pvs = IcsImport.preview(parsed, emptyList(), settings)
        assertEquals(
            listOf(Triple("Perso Google", true, null), Triple("sport", true, null), Triple("big", true, null), Triple("small", false, "perso")),
            parsed.zip(pvs).map { (p, pv) -> Triple(p.name, pv.calendarExport, pv.suggestedCalendar) },
        )
        assertNull(pvs[2].items); assertEquals(2, pvs[3].items?.size)
        assertTrue(parsed.all { p -> p.events.all { it.location == null } })
        val (report, after) = IcsImport.apply(AgendaStore(MemoryFs()), parsed, ImportOptions(), emptyList(), settings, Vectors.DEVICE, "zip", Vectors.NOW)
        assertEquals(listOf("Perso Google", "sport", "big"), report.newCalendars.map { after.calendar(it).name })
    }

    private fun body(desc: String, extra: String = ""): String {
        val text = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nDESCRIPTION:$desc\r\n${extra}END:VEVENT\r\nEND:VCALENDAR\r\n"
        return IcsImport.buildBody(Ics.readCalendars(text.byteInputStream(), ArrayList())[0].children[0])
    }

    @Test
    fun meetingLinkLineIsNotDuplicated() {
        assertEquals("Rejoindre : https://teams.microsoft.com/l/meetup-join/abc\nOrdre du jour",
            body("Rejoindre : https://teams.microsoft.com/l/meetup-join/abc\\nOrdre du jour"))
        assertEquals("Visio : https://us02web.zoom.us/j/123\n\nOrdre du jour\nZoom : https://us02web.zoom.us/j/123.",
            body("Ordre du jour\\nZoom : https://us02web.zoom.us/j/123."))
        assertEquals("Voir https://exemple.fr/doc", body("Voir https://exemple.fr/doc"))
        assertEquals("Voir https://meet.google.com/abc", body("Voir https://meet.google.com/abc", "X-GOOGLE-CONFERENCE:https://meet.google.com/abc\r\n"))
    }

    @Test
    fun linksOfABody() {
        val text = "Ordre du jour : <https://exemple.fr/agenda/2026/reunion-du-conseil-annuel?id=42>, puis " +
            "(https://fr.wikipedia.org/wiki/Foo_(bar)). Doc : https://exemple.fr/doc. Rejoindre : $meet ; encore https://exemple.fr/doc !"
        assertEquals(listOf(
            meet to "Rejoindre la visio (Proton Meet)",
            "https://exemple.fr/agenda/2026/reunion-du-conseil-annuel?id=42" to "exemple.fr/agenda/2026/reunion-du-conse…",
            "https://fr.wikipedia.org/wiki/Foo_(bar)" to "fr.wikipedia.org/wiki/Foo_(bar)",
            "https://exemple.fr/doc" to "exemple.fr/doc",
        ), Links.extract(text).map { it.url to it.label })
        assertEquals(Links.MAX, Links.extract((0 until 15).joinToString(" ") { "https://x.org/$it" }).size)
        assertEquals(emptyList<LinkItem>(), Links.extract("rien ici, http:// ni ftp://x.org"))
        assertEquals(listOf(3 until 21 to "https://www.x.org/"), Links.spans("a <https://www.x.org/> b"))
        assertEquals("x.org", Links.label("https://www.x.org/"))
    }
}
