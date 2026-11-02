package app.memento.ui

import app.memento.agenda.AgendaView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListMemoryTest {
    @Test
    fun anItemCutByAnEdgeOfTheViewportIsNotFullyShown() {
        assertTrue(fullyShown(100, 200, 0, 800))
        assertTrue(fullyShown(0, 800, 0, 800))
        assertFalse(fullyShown(-10, 90, 0, 800))
        assertFalse(fullyShown(750, 850, 0, 800))
    }

    @Test
    fun theAgendaKeepsItsPlaceUntilAnotherViewIsChosen() {
        val m = AgendaMemory()
        m.showing(AgendaView.AGENDA)
        m.started = true
        m.hourScroll = 400
        m.showing(AgendaView.AGENDA) // back from an entry
        assertTrue(m.started)
        assertEquals(400, m.hourScroll)
        m.showing(AgendaView.WEEK)
        assertFalse(m.started)
        assertEquals(-1, m.hourScroll)
    }
}
