package app.memento.activity

import app.memento.Note
import app.memento.NoteFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

private const val T0 = 1_700_000_000_000L
private const val M_PER_DEG = 111_320.0

/** Straight line going north from (45, 6): [speed] m/s, [vz] m/s of altitude, one fix per [step] s. */
private fun leg(
    from: TrackPoint?, seconds: Int, speed: Double, vz: Double = 0.0, step: Int = 1, noise: Double = 0.0,
    hr: Int? = null, startAlt: Double = 1000.0,
): List<TrackPoint> {
    val out = ArrayList<TrackPoint>()
    var lat = from?.lat ?: 45.0
    val lon = from?.lon ?: 6.0
    var alt = from?.alt ?: startAlt
    var t = from?.time ?: T0
    var i = 0
    while (i < seconds) {
        i += step
        t += step * 1000L
        lat += speed * step / M_PER_DEG
        alt += vz * step
        val jitter = if (noise != 0.0) noise * sin(t / 1000.0 * 1.7) else 0.0
        out.add(TrackPoint(t, lat + jitter / M_PER_DEG, lon + jitter * 0.7 / M_PER_DEG, alt, hr))
    }
    return out
}

class FitTest {
    @Test
    fun writeThenReadKeepsPointsPausesAndSession() {
        val pts = leg(null, 120, 3.0, vz = 0.1, hr = 130).mapIndexed { i, p ->
            p.copy(gpsAlt = p.alt!! + 2, pressure = 900.0 - i * 0.01)
        }
        val pause = Pause(pts[40].time + 500, pts[60].time - 500)
        val track = Track(pts, listOf(pause), sessionElapsedMs = 120_000, sessionTimerMs = 100_000)
        val bytes = Fit.write(track, Sport.BIKE, 360.0)
        assertEquals("a valid FIT CRC checks to zero", 0, Fit.crc(bytes))

        val back = Fit.read(bytes)
        assertEquals(pts.size, back.points.size)
        assertEquals(pts[10].lat, back.points[10].lat, 1e-6)
        assertEquals(pts[10].lon, back.points[10].lon, 1e-6)
        assertEquals(pts[10].alt!!, back.points[10].alt!!, 0.2)
        assertEquals(130, back.points[10].hr)
        assertEquals(pts[10].gpsAlt!!, back.points[10].gpsAlt!!, 0.01)
        assertEquals(pts[10].pressure!!, back.points[10].pressure!!, 0.01)
        assertEquals(pts[10].time, back.points[10].time)
        assertEquals(Sport.BIKE, Sport.fromFit(back.fitSport))
        assertEquals(1, back.pauses.size)
        assertEquals(pause.start / 1000, back.pauses[0].start / 1000)
        assertEquals(pause.end / 1000, back.pauses[0].end / 1000)
        assertEquals(100_000L, back.sessionTimerMs)
        assertEquals(360.0, back.sessionDistance!!, 0.01)
    }

    @Test
    fun negativeCoordinatesSurvive() {
        val p = TrackPoint(T0, -33.8688, -151.2093, null)
        val back = Fit.read(Fit.write(Track(listOf(p, p.copy(time = T0 + 1000)), emptyList()), Sport.GPS, 0.0))
        assertEquals(-33.8688, back.points[0].lat, 1e-6)
        assertEquals(-151.2093, back.points[0].lon, 1e-6)
        assertNull(back.points[0].alt)
        assertNull(back.points[0].hr)
    }

    @Test(expected = Fit.FitException::class)
    fun refusesOtherFiles() {
        Fit.read("hello world, not a FIT file".toByteArray())
    }
}

class GpxTest {
    @Test
    fun theHandReadTimeIsJavaTimes() {
        val r = java.util.Random(3)
        fun slow(s: String): Long? = try { java.time.Instant.parse(s).toEpochMilli() } catch (_: Exception) { null }
        val samples = ArrayList<String>()
        repeat(50_000) {
            val ms = (r.nextLong() % 4_000_000_000_000L).let { if (it < 0) -it else it } - 1_000_000_000_000L
            val text = java.time.Instant.ofEpochMilli(ms).plusNanos(r.nextInt(1_000_000).toLong()).toString()
            samples.add(text)
            samples.add(text.substringBefore('.').removeSuffix("Z") + "Z")
        }
        samples.addAll(
            listOf(
                "2024-02-29T10:00:00Z", "2023-02-29T10:00:00Z", "2000-02-29T00:00:00Z", "1900-02-29T00:00:00Z", "2024-04-31T00:00:00Z",
                "2024-01-01T24:00:00Z", "2024-01-01T23:60:00Z", "2024-01-01T23:59:60Z", "2024-13-01T00:00:00Z", "2024-00-10T00:00:00Z",
                "2024-01-01T10:00:00.Z", "2024-01-01T10:00:00.1234567891Z", "2024-01-01T10:00:00.5Z", "2024-01-01T10:00:00+02:00",
                "2024-01-01t10:00:00Z", "2024-01-01T10:00:0aZ", "1969-12-31T23:59:59.999Z", "0000-01-01T00:00:00Z",
            ),
        )
        for (s in samples) {
            val fast = Gpx.utcTime(s)
            if (fast != null) assertEquals(s, slow(s), fast)
            assertEquals(s, slow(s) ?: runCatching { java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrNull(), Gpx.parseTime(s))
        }
    }

    @Test
    fun readsPointsHeartRateAndSegmentsAsPauses() {
        val gpx = """<?xml version="1.0"?>
<gpx version="1.1" creator="test"><trk><name>Tour du lac</name>
<trkseg>
<trkpt lat="45.0" lon="6.0"><ele>1000.5</ele><time>2024-01-01T10:00:00Z</time><extensions><gpxtpx:TrackPointExtension><gpxtpx:hr>120</gpxtpx:hr></gpxtpx:TrackPointExtension></extensions></trkpt>
<trkpt lon="6.0001" lat="45.0001"><ele>1001</ele><time>2024-01-01T10:00:10Z</time></trkpt>
</trkseg>
<trkseg>
<trkpt lat="45.001" lon="6.001"><time>2024-01-01T10:30:00.500Z</time></trkpt>
</trkseg></trk></gpx>"""
        val t = Gpx.read(gpx)
        assertEquals(3, t.points.size)
        assertEquals("Tour du lac", t.name)
        assertEquals(1000.5, t.points[0].alt!!, 0.001)
        assertEquals(120, t.points[0].hr)
        assertEquals(6.0001, t.points[1].lon, 1e-9)
        assertNull(t.points[2].alt)
        assertEquals(1, t.pauses.size)
        assertEquals((30 * 60 - 10) * 1000L + 500L, t.pauses[0].end - t.pauses[0].start)
    }
    @Test
    fun aGpxWithoutTimesIsAnUntimedTrackWithDistanceAndClimbButNoDuration() {
        // a Strava export of a route: no <time>, points far apart (no GPS jump), the first one climbing
        val pts = (0 until 20).joinToString("\n") { i ->
            "<trkpt lat=\"${-21.0 - i * 0.005}\" lon=\"55.5\"><ele>${100 + i * 50}</ele></trkpt>"
        }
        val gpx = "<?xml version=\"1.0\"?><gpx creator=\"StravaGPX\"><trk><name>Diagonale</name><trkseg>$pts</trkseg></trk></gpx>"
        val t = Gpx.read(gpx)
        assertTrue(t.untimed)
        assertEquals(20, t.points.size)
        assertNull(t.startTime)
        val a = Analyzer.analyze(t, Sport.WALK)
        assertEquals(20, a.samples.size)  // ~556 m apart: kept, not dropped as jumps
        assertEquals(19 * 0.005 * M_PER_DEG, a.stats.distance, 50.0)
        assertTrue(a.stats.ascent > 900)
        assertEquals(0L, a.stats.elapsedMs)
        assertEquals(0L, a.stats.movingMs)
        assertEquals(0.0, a.stats.maxSpeed, 0.0)
        assertTrue(a.stops.isEmpty() && a.samples.all { it.speed == 0.0 })
        val info = ActivityInfo.from(Sport.WALK, a, t, "attachments/x.gpx", untimedStart = T0)
        assertEquals(T0, info.start)
        assertEquals(0L, info.elapsed)
        assertNull(info.vmax)
    }

    @Test
    fun timedPointsWinOverAFewUntimedOnes() {
        val gpx = """<gpx><trk><trkseg>
<trkpt lat="45.0" lon="6.0"><time>2024-01-01T10:00:00Z</time></trkpt>
<trkpt lat="45.0001" lon="6.0"></trkpt>
<trkpt lat="45.0002" lon="6.0"><time>2024-01-01T10:00:10Z</time></trkpt>
</trkseg></trk></gpx>"""
        val t = Gpx.read(gpx)
        assertTrue(!t.untimed)
        assertEquals(2, t.points.size)
    }
}

class AnalysisTest {
    @Test
    fun aStopIsDetectedAndNotCountedAsMovingTime() {
        val a = leg(null, 300, 1.4, noise = 0.0)
        // standing still for 60 s with GPS jitter of ±2 m
        val b = leg(a.last(), 60, 0.0, noise = 2.0)
        val c = leg(b.last(), 300, 1.4)
        val res = Analyzer.analyze(Track(a + b + c), Sport.WALK)
        assertEquals(1, res.stops.size)
        val stop = res.stops[0]
        assertTrue("stop lasts about a minute", abs(stop.durationMs - 60_000L) < 10_000)
        assertEquals(659_000L, res.stats.elapsedMs)
        assertTrue("moving time without the stop", abs(res.stats.movingMs - 600_000L) < 12_000)
        // 600 s at 1.4 m/s = 840 m; the jitter must not add distance
        assertTrue("distance ${res.stats.distance}", abs(res.stats.distance - 840.0) < 25.0)
    }

    @Test
    fun aManualPauseIsNotCounted() {
        val a = leg(null, 200, 3.0)
        val gap = leg(a.last(), 1800, 0.0, step = 60) // 30 min, one fix a minute
        val b = leg(gap.last(), 200, 3.0)
        val pause = Pause(a.last().time + 1000, b.first().time - 1000)
        val res = Analyzer.analyze(Track(a + b, listOf(pause)), Sport.BIKE)
        assertTrue("moving ${res.stats.movingMs}", abs(res.stats.movingMs - 400_000L) < 4_000)
        assertTrue(res.stats.elapsedMs > 2_000_000L)
        assertEquals(1, res.pauses.size)
        assertTrue(abs(res.stats.distance - 1200.0) < 20.0)
        assertTrue(gap.isNotEmpty())
    }

    @Test
    fun gpsJumpsAreDropped() {
        val pts = leg(null, 60, 2.0).toMutableList()
        pts[30] = pts[30].copy(lat = pts[30].lat + 0.5) // 55 km away for one second
        val res = Analyzer.analyze(Track(pts), Sport.WALK)
        assertEquals(1, res.quality.dropped)
        assertTrue(abs(res.stats.distance - 118.0) < 5.0)
    }

    @Test
    fun noiseOnFlatGroundIsNoClimb() {
        val pts = leg(null, 600, 1.5).mapIndexed { i, p -> p.copy(alt = 1000.0 + (if (i % 2 == 0) 1.2 else -1.2)) }
        val res = Analyzer.analyze(Track(pts), Sport.WALK)
        assertEquals(0.0, res.stats.ascent, 0.001)
        assertEquals(0.0, res.stats.descent, 0.001)
    }

    @Test
    fun aRealClimbIsCounted() {
        val pts = leg(null, 600, 1.0, vz = 0.2)
        val res = Analyzer.analyze(Track(pts), Sport.WALK)
        assertTrue("ascent ${res.stats.ascent}", abs(res.stats.ascent - 120.0) < 10.0)
        assertEquals(0.0, res.stats.descent, 3.5)
    }

    @Test
    fun skiRunsIgnoreTheLift() {
        val all = ArrayList<TrackPoint>()
        var last: TrackPoint? = null
        repeat(3) {
            val down = leg(last, 120, 12.0, vz = -1.2)   // 144 m of drop
            val lift = leg(down.last(), 300, 2.0, vz = 0.5) // 150 m up, slow
            all += down + lift
            last = lift.last()
        }
        val res = Analyzer.analyze(Track(all), Sport.SKI)
        assertEquals(3, res.stats.runs)
        assertEquals(3, res.stats.climbs)
        assertTrue("climb gain ${res.stats.climbGain}", abs(res.stats.climbGain - 3 * 150.0) < 40.0)
        assertTrue(res.samples.any { it.up })
        assertTrue(res.samples.none { it.up && it.down })
        assertTrue("run drop ${res.stats.runDrop}", abs(res.stats.runDrop - 3 * 144.0) < 40.0)
        assertTrue("run speed ${res.stats.runMaxSpeed}", abs(res.stats.runMaxSpeed - 12.0) < 1.0)
        assertTrue(res.samples.any { it.down })
        assertTrue("lift samples are not downhill", res.samples.filter { !it.down }.all { it.speed < 3.0 || it.alt != null })
        val onlyLiftSpeed = res.samples.filter { it.down }.minOf { it.speed }
        assertTrue(onlyLiftSpeed > 3.0)
    }

    @Test
    fun noRunsForOtherSports() {
        val down = leg(null, 120, 12.0, vz = -1.2)
        assertEquals(0, Analyzer.analyze(Track(down), Sport.BIKE).stats.runs)
    }

    @Test
    fun skiWithoutAltitudeFallsBackOnSpeed() {
        val down = leg(null, 90, 10.0).map { it.copy(alt = null) }
        val lift = leg(down.last(), 200, 2.0).map { it.copy(alt = null) }
        val res = Analyzer.analyze(Track(down + lift), Sport.SKI)
        assertEquals(1, res.stats.runs)
        assertTrue(!res.quality.hasAltitude)
    }

    @Test
    fun qualityReportsSamplingAndHeartRate() {
        val res = Analyzer.analyze(Track(leg(null, 100, 2.0, step = 5, hr = 140)), Sport.WALK)
        assertEquals(5.0, res.quality.medianIntervalSec, 0.01)
        assertTrue(res.quality.hasHr)
        assertEquals(140, res.stats.hrAvg)
        assertEquals(140, res.stats.hrMin)
        assertNull(Analyzer.analyze(Track(leg(null, 100, 2.0, step = 5)), Sport.WALK).stats.hrMin)
    }
}

class InfoAndFusionTest {
    @Test
    fun infoRoundTripsThroughTheNoteFrontmatter() {
        val info = ActivityInfo(Sport.SKI, T0, 7200, 6000, 25432.4, 410, 1500, 128, 21.3, 9, "attachments/ski-ab12cd.fit", null, 8, 3400)
        val note = Note(path = "activities/ski-ab12cd.md", id = "ab12cd", title = "Ski à La Plagne", created = T0, updated = T0, activity = info, attachments = listOf("attachments/ski-ab12cd.fit"))
        val text = NoteFormat.serialize(note)
        assertTrue(text.contains("activity: {\"sport\": \"ski\""))
        val back = NoteFormat.parse(note.path, text, T0)
        assertEquals(info.sport, back.activity!!.sport)
        assertEquals(info.start, back.activity!!.start)
        assertEquals(25432.4, back.activity!!.distance, 0.05)
        assertEquals(9, back.activity!!.runs)
        assertEquals(8, back.activity!!.climbs)
        assertEquals(3400, back.activity!!.drop)
        assertEquals("attachments/ski-ab12cd.fit", back.activity!!.file)
        assertEquals(21.3, back.activity!!.vmax!!, 0.01)
    }

    @Test
    fun noActivityKeyMeansPlainNote() {
        val note = Note(path = "a.md", id = "aaaaaa", title = "x", created = T0, updated = T0)
        assertNull(NoteFormat.parse("a.md", NoteFormat.serialize(note), T0).activity)
    }

    @Test
    fun fusionFollowsThePressureAndSlowlyTheGps() {
        val f = AltitudeFusion()
        // sea-level-ish start: GPS says 500 m at the pressure of 954.6 hPa
        val p0 = 954.6
        val first = f.update(T0, p0, 500.0, 5f)
        assertEquals(500.0, first!!, 0.01)
        // pressure drops 1 hPa = about 8.3 m up; GPS is noisy (+/- 15 m) and must not move the result much
        val up = f.update(T0 + 10_000, p0 - 1.0, 530.0, 5f)
        assertTrue("up $up", up!! in 507.0..512.0)
        // without GPS the barometer carries on
        val noGps = f.update(T0 + 20_000, p0 - 2.0, null, null)
        assertTrue("noGps $noGps", noGps!! > up)
        assertEquals(700.0, AltitudeFusion().update(T0, null, 700.0, 5f)!!, 0.001)
        assertNull(AltitudeFusion().update(T0, 950.0, null, null))
    }

    @Test
    fun mercatorRoundTrips() {
        val z = 12.0
        val x = MapMath.worldX(6.0, z)
        val y = MapMath.worldY(45.0, z)
        assertEquals(6.0, MapMath.lon(x, z), 1e-9)
        assertEquals(45.0, MapMath.lat(y, z), 1e-9)
        assertNotNull(MapMath.fitZoom(45.0, 6.0, 45.01, 6.01, 800.0, 600.0))
        assertTrue(MapMath.fitZoom(45.0, 6.0, 45.01, 6.01, 800.0, 600.0) in 12.0..17.0)
    }

    @Test
    fun formattingIsFrench() {
        assertEquals("850 m", Fmt.distance(850.2))
        assertEquals("12,35 km", Fmt.distance(12345.0))
        assertEquals("1:05:03", Fmt.clock(3903))
        assertEquals("5:00 /km", Fmt.pace(3.3333))
        assertEquals("36,0 km/h", Fmt.speed(10.0))
    }
}

class RecordingLogTest {
    @Test
    fun logRoundTripsAndSurvivesATornLastLine() {
        val pts = leg(null, 5, 2.0).mapIndexed { i, p -> p.copy(alt = 1000.0 + i, gpsAlt = 1001.0 + i, pressure = 900.0 - i) }
        val text = buildString {
            appendLine(RecordingLog.header(Sport.SKI, T0))
            appendLine(RecordingLog.point(pts[0]))
            appendLine(RecordingLog.event(T0 + 1500, "pause"))
            appendLine(RecordingLog.event(T0 + 3500, "resume"))
            for (p in pts.drop(1)) appendLine(RecordingLog.point(p))
            append("P,17000000")
        }
        val rec = RecordingLog.parse(text)!!
        assertEquals(Sport.SKI, rec.sport)
        assertEquals(5, rec.track.points.size)
        assertEquals(pts[2].lat, rec.track.points[2].lat, 1e-7)
        assertEquals(1002.0, rec.track.points[2].alt!!, 0.05)
        assertEquals(899.0, rec.track.points[1].pressure!!, 0.01)
        assertEquals(1, rec.track.pauses.size)
        assertEquals(2000L, rec.track.pauses[0].end - rec.track.pauses[0].start)
    }

    @Test
    fun aLogEndingPausedKeepsNoPause() {
        val text = RecordingLog.header(Sport.WALK, T0) + "\n" + RecordingLog.event(T0 + 10, "pause") + "\n"
        assertTrue(RecordingLog.parse(text)!!.track.pauses.isEmpty())
        assertNull(RecordingLog.parse("nothing here"))
    }

    @Test
    fun climbCounterIgnoresNoise() {
        val c = ClimbCounter()
        listOf(100.0, 101.0, 100.5, 101.5, 100.0, 105.0, 104.0, 110.0).forEach { c.add(it) }
        assertEquals(10.0, c.ascent, 0.001)
    }
}

class SportAndElevationTest {
    @Test
    fun aGenericSportStaysOther() {
        assertEquals(Sport.GPS, Sport.guess(0))
        assertEquals(Sport.GPS, Sport.guess(null))
        assertEquals(Sport.EBIKE, Sport.guess(21))
        assertEquals(Sport.SKI, Sport.guess(13))
        assertEquals(Sport.EBIKE, Sport.fromFit(21))
        assertEquals(21, Sport.toFit(Sport.EBIKE))
    }

    @Test
    fun terrainElevationMakesSkiRunsDetectableWithoutAltitude() {
        // 100 m drop on a 10 % slope going north, then a slow lift back up
        val down = leg(null, 120, 12.0).map { it.copy(alt = null) }
        val lift = leg(down.last().copy(alt = 0.0), 300, 2.0).map { it.copy(alt = null) }
        val track = Track(down + lift)
        assertTrue(Elevation.missing(track))
        val fake = { url: String ->
            val coords = url.substringAfter("locations=").split("|").map { it.split(",")[0].toDouble() }
            // altitude as a function of latitude: 2000 m at the start, down 10 % over the first 1440 m, then back up
            val lat0 = 45.0
            val turn = lat0 + 1440.0 / M_PER_DEG
            val alts = coords.map { lat -> if (lat <= turn) 2000 - (lat - lat0) * M_PER_DEG * 0.1 else 2000 - 144 + (lat - turn) * M_PER_DEG * 0.25 }
            "{\"results\": [" + alts.joinToString(",") { "{\"elevation\": %.1f, \"location\": {}}".format(java.util.Locale.ROOT, it) } + "], \"status\": \"OK\"}"
        }
        val samples = Elevation.pick(Analyzer.clean(track))
        assertTrue(samples.size < track.points.size / 4)
        val alts = Elevation.query(samples, fake, {})
        val filled = Elevation.apply(track, Elevation.parse(Elevation.encode(alts)))
        assertTrue(filled.points.all { it.alt != null })
        val res = Analyzer.analyze(filled, Sport.SKI)
        assertEquals(1, res.stats.runs)
        assertTrue("drop ${res.stats.runDrop}", abs(res.stats.runDrop - 144.0) < 25.0)
        assertTrue(res.quality.hasAltitude)
    }

    @Test
    fun aBadAnswerIsAnError() {
        val samples = leg(null, 100, 5.0)
        val failure = runCatching { Elevation.query(samples.take(3), { "{\"error\": \"quota\"}" }, {}) }
        assertTrue(failure.exceptionOrNull() is Elevation.ElevationException)
    }
}

class SettingsAndRefineTest {
    @Test
    fun settingsRoundTripAndDefaultToEverything() {
        val s = ActivitySettings(true, setOf(Sport.WALK, Sport.BIKE))
        val back = ActivitySettings.parse(s.toJson())
        assertTrue(back.refineAltitude)
        assertEquals(setOf(Sport.WALK, Sport.BIKE), back.totals)
        val empty = ActivitySettings.parse("{}")
        assertTrue(!empty.refineAltitude)
        assertEquals(Sport.values().toSet(), empty.totals)
        assertEquals(emptySet<Sport>(), ActivitySettings.parse("{\"totals\": []}").totals)
        assertEquals(Sport.values().toSet() - Sport.SKI, ActivitySettings(false, Sport.values().toSet() - Sport.SKI).let { ActivitySettings.parse(it.toJson()).totals })
    }

    @Test
    fun refiningFollowsTheBarometersDriftButNotTheTerrainNoise() {
        // truth: climbing 100 m over 10 minutes; the barometer drifts by +20 m over the same time
        val pts = leg(null, 600, 1.5, vz = 0.1).map { p -> p.copy(alt = p.alt!! + 20.0 * (p.time - T0) / 600_000.0) }
        val track = Track(pts)
        val truth = { p: TrackPoint -> 1000.0 + 0.1 * (p.time - T0) / 1000.0 }
        // terrain samples: the truth with a local error of ±6 m
        val dem = Elevation.pick(Analyzer.clean(track)).mapIndexed { i, p -> p.time to truth(p) + (if (i % 2 == 0) 6.0 else -6.0) }
        val refined = Elevation.refine(track, dem)
        val worst = refined.points.maxOf { abs(it.alt!! - truth(it)) }
        assertTrue("worst error $worst", worst < 8.0)
        val before = track.points.maxOf { abs(it.alt!! - truth(it)) }
        assertTrue("before $before", before > 15.0)
    }
}
