package app.memento

import app.memento.agenda.EventDraft
import app.memento.agenda.Stamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class DraftCodecTest {
    @Test
    fun eventDraftRoundTrips() {
        val initial = EventDraft(startDate = LocalDate.of(2026, 11, 3))
        val edited = initial.copy(
            title = "Dîner : chez Léa\nétage 2 = porte B", allDay = false, startTime = LocalTime.of(19, 30),
            endDate = LocalDate.of(2026, 11, 4), endTime = LocalTime.of(0, 15), tz = "Europe/Paris", endTz = "America/New_York", calendar = "perso",
            location = "#3 rue \\ de la Paix", rrule = "FREQ=WEEKLY;BYDAY=TU", alerts = listOf(10, 1440), noEnd = false,
        )
        val d = EventEditorDraft("occurrence", seriesId = "abc123", recurrenceAt = Stamp(LocalDate.of(2026, 11, 3), LocalTime.of(19, 0)), draft = edited, initial = initial)
        assertEquals(d, DraftCodec.decodeEvent(DraftCodec.encodeEvent(d)))
        val n = EventEditorDraft("new", draft = initial.copy(allDay = true), initial = initial)
        assertEquals(n, DraftCodec.decodeEvent(DraftCodec.encodeEvent(n)))
    }

    @Test
    fun contactDraftKeepsWhatWasTyped() {
        val d = ContactEditorDraft(
            originalPath = null, firstName = "Marie", lastName = "", phones = listOf(PhoneDraft("06 12", "mobile"), PhoneDraft("", null)),
            emails = listOf(""), address = "1 rue X\nParis", birthDay = "31", birthMonth = 2, birthYear = "19x", notes = "a=b:c", favorite = true,
        )
        assertEquals(d, DraftCodec.decodeContact(DraftCodec.encodeContact(d)))
    }

    @Test
    fun unreadableDraftIsDropped() {
        assertNull(DraftCodec.decodeEvent("mode=new\n"))
        assertNull(DraftCodec.decodeEvent("garbage"))
    }
}
