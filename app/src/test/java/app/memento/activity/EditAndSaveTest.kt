package app.memento.activity

import app.memento.Note
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditAndSaveTest {
    private val t0 = 1_700_000_000_000L

    /** Straight line north from (45, 6) at [speed] m/s, one fix per second. */
    private fun line(seconds: Int, speed: Double, subSecond: Boolean = false): Track = Track(
        (1..seconds).map { s ->
            TrackPoint(t0 + s * 1000L + (if (subSecond) (s * 377L) % 1000 else 0L), 45.0 + speed * s / 111_320.0, 6.0, 1000.0)
        },
    )

    private fun noteFor(track: Track, sport: Sport, title: String): Note {
        val info = ActivityInfo.from(sport, Analyzer.analyze(track, sport), track, "attachments/a-ab12cd.fit")
        return Note(path = "activities/a-ab12cd.md", id = "ab12cd", title = title, created = t0, updated = t0, activity = info)
    }

    @Test
    fun anEditSavedWhileTheTrackLoadedKeepsTheLoadedTrack() {
        val track = line(300, 3.0)
        val before = noteFor(track, Sport.WALK, "avant")
        val loaded = ActivityDetail(before, track, Analyzer.analyze(track, Sport.WALK))
        val res = afterEdit(before.copy(title = "après"), ActivityDetail(before, null, null), loaded, null)
        assertEquals("après", res.note.title)
        assertTrue(res.track === track)
        assertTrue("the loaded figures are kept", res.analysis === loaded.analysis)
    }

    @Test
    fun aSportChangedWhileTheTrackLoadedIsAnalysedForTheNewSport() {
        val track = line(300, 3.0)
        val before = noteFor(track, Sport.WALK, "x")
        val loaded = ActivityDetail(before, track, Analyzer.analyze(track, Sport.WALK))
        val bike = before.copy(activity = before.activity!!.copy(sport = Sport.BIKE))
        val res = afterEdit(bike, ActivityDetail(before, null, null), loaded, null)
        assertEquals(Analyzer.analyze(track, Sport.BIKE), res.analysis)
    }

    @Test
    fun anEditOfALoadedTrackKeepsItsOwnFigures() {
        val track = line(300, 3.0)
        val before = noteFor(track, Sport.WALK, "x")
        val shown = ActivityDetail(before, track, Analyzer.analyze(track, Sport.WALK))
        val bike = Analyzer.analyze(track, Sport.BIKE)
        val res = afterEdit(before.copy(activity = before.activity!!.copy(sport = Sport.BIKE)), shown, shown, bike)
        assertTrue(res.analysis === bike)
    }

    @Test
    fun anEditWhileStillLoadingLeavesTheLoadToOpen() {
        val before = noteFor(line(300, 3.0), Sport.WALK, "x")
        val res = afterEdit(before, ActivityDetail(before, null, null), ActivityDetail(before, null, null), null)
        assertNull(res.track)
        assertNull(res.analysis)
    }

    @Test
    fun aRecordingIsAnalysedAsItIsStored() {
        // sub-second fixes: the FIT keeps whole seconds, and every later open reads the FIT
        val (bytes, stored) = asStored(line(300, 3.0, subSecond = true), Sport.WALK)
        assertTrue(stored.points.all { it.time % 1000 == 0L })
        assertEquals(Analyzer.analyze(Fit.read(bytes), Sport.WALK), Analyzer.analyze(stored, Sport.WALK))
    }
}
