package app.memento

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class SnackQueueTest {
    private var now = 0L
    private val queue = SnackQueue { now }

    @Test
    fun showsInPostingOrder() {
        queue.post(Snack("un"))
        queue.post(Snack("deux"))
        queue.post(Snack("trois"))
        val seen = mutableListOf<String>()
        while (true) {
            val e = queue.current ?: break
            seen += e.snack.text
            queue.start(e)
            queue.finish(e, actionPerformed = false)
        }
        assertEquals(listOf("un", "deux", "trois"), seen)
    }

    @Test
    fun samePlainMessageIsQueuedOnce() {
        queue.post(Snack("Synchronisation en cours"))
        queue.post(Snack("Autre"))
        queue.post(Snack("Synchronisation en cours"))
        assertEquals(2, queue.size)
        // once gone, it may come back
        queue.finish(queue.current!!, false)
        queue.post(Snack("Synchronisation en cours"))
        assertEquals(2, queue.size)
    }

    @Test
    fun everyUndoIsKept() {
        var undone = 0
        queue.post(Snack("Note supprimée") { undone += 1 })
        queue.post(Snack("Note supprimée") { undone += 10 })
        assertEquals(2, queue.size)
        queue.finish(queue.current!!, actionPerformed = true)
        queue.finish(queue.current!!, actionPerformed = true)
        assertEquals(11, undone)
    }

    @Test
    fun consumedOnce() {
        var undone = 0
        queue.post(Snack("Contact supprimé") { undone++ })
        val e = queue.current!!
        queue.finish(e, actionPerformed = true)
        queue.finish(e, actionPerformed = true)
        assertEquals(1, undone)
        assertNull(queue.current)
    }

    @Test
    fun dismissedWithoutActionDoesNotUndo() {
        var undone = false
        queue.post(Snack("Note supprimée") { undone = true })
        queue.finish(queue.current!!, actionPerformed = false)
        assertEquals(false, undone)
        assertNull(queue.current)
    }

    @Test
    fun interruptedSnackStaysAndResumesWithTimeLeft() {
        queue.post(Snack("Cache des cartes vidé"))
        val e = queue.current!!
        assertEquals(4_000, queue.start(e))
        now += 1_500
        queue.interrupt(e) // rotation: the host went away mid-show
        assertSame(e, queue.current)
        now += 60_000 // time off screen does not count
        assertEquals(2_500, queue.start(e))
        now += 3_000
        queue.interrupt(e)
        assertEquals(0, queue.start(e))
    }

    @Test
    fun durations() {
        assertEquals(4_000, Snack("x").millis)
        assertEquals(10_000, Snack("x") {}.millis)
        assertEquals(10_000, Snack("x", duration = SnackDuration.LONG).millis)
        assertEquals(4_000, Snack("x", duration = SnackDuration.SHORT) {}.millis)
        assertNull(Snack("x", action = "Reprendre").actionLabel)
        assertEquals("Reprendre", Snack("x", action = "Reprendre") {}.actionLabel)
    }
}
