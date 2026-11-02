import math
import unittest
from pathlib import Path

from memento_win.core import activity as act
from memento_win.core import analysis, fit, gpx
from memento_win.core import note as fmt
from memento_win.core.track import Pause, Sport, Track, TrackPoint

T0 = 1_700_000_000_000
M = 111_320.0


def leg(frm, seconds, speed, vz=0.0, step=1, noise=0.0, hr=None, start_alt=1000.0):
    out = []
    lat = frm.lat if frm else 45.0
    lon = frm.lon if frm else 6.0
    alt = frm.alt if frm else start_alt
    t = frm.time if frm else T0
    i = 0
    while i < seconds:
        i += step
        t += step * 1000
        lat += speed * step / M
        alt += vz * step
        j = noise * math.sin(t / 1000 * 1.7) if noise else 0.0
        out.append(TrackPoint(t, lat + j / M, lon + j * 0.7 / M, alt, hr))
    return out


class FitTest(unittest.TestCase):
    def test_reads_a_file_written_by_the_android_app(self):
        track = fit.read((Path(__file__).parent / "data" / "synthetic.fit").read_bytes())
        self.assertEqual(90, len(track.points))
        p = track.points[9]
        self.assertAlmostEqual(45.0 + 10 * 3.0 / M, p.lat, places=5)
        self.assertAlmostEqual(6.0001, p.lon, places=5)
        self.assertAlmostEqual(1001.0, p.alt, delta=0.2)
        self.assertEqual(120 + 10 % 5, p.hr)
        self.assertAlmostEqual(1003.0, p.gps_alt, places=1)
        self.assertAlmostEqual(900.0 - 0.1, p.pressure, places=1)
        self.assertEqual(Sport.BIKE, Sport.from_fit(track.fit_sport))
        self.assertEqual(1, len(track.pauses))
        self.assertEqual((T0 + 30_500) // 1000, track.pauses[0].start // 1000)
        self.assertEqual((T0 + 40_500) // 1000, track.pauses[0].end // 1000)
        self.assertEqual(79_000, track.session_timer_ms)
        self.assertAlmostEqual(267.0, track.session_distance, places=1)

    def test_refuses_other_files(self):
        with self.assertRaises(fit.FitError):
            fit.read(b"definitely not a FIT file")


class GpxTest(unittest.TestCase):
    def test_points_heart_rate_and_segments(self):
        text = """<?xml version="1.0"?><gpx version="1.1"><trk><name>Tour du lac</name><trkseg>
<trkpt lat="45.0" lon="6.0"><ele>1000.5</ele><time>2024-01-01T10:00:00Z</time><extensions><gpxtpx:TrackPointExtension><gpxtpx:hr>120</gpxtpx:hr></gpxtpx:TrackPointExtension></extensions></trkpt>
<trkpt lon="6.0001" lat="45.0001"><ele>1001</ele><time>2024-01-01T10:00:10Z</time></trkpt>
</trkseg><trkseg><trkpt lat="45.001" lon="6.001"><time>2024-01-01T10:30:00.500Z</time></trkpt></trkseg></trk></gpx>"""
        t = gpx.read(text)
        self.assertEqual(3, len(t.points))
        self.assertEqual("Tour du lac", t.name)
        self.assertEqual(1000.5, t.points[0].alt)
        self.assertEqual(120, t.points[0].hr)
        self.assertIsNone(t.points[2].alt)
        self.assertEqual(1, len(t.pauses))
        self.assertEqual((30 * 60 - 10) * 1000 + 500, t.pauses[0].end - t.pauses[0].start)


class AnalysisTest(unittest.TestCase):
    def test_a_stop_is_not_moving_time(self):
        a = leg(None, 300, 1.4)
        b = leg(a[-1], 60, 0.0, noise=2.0)
        c = leg(b[-1], 300, 1.4)
        res = analysis.analyze(Track(a + b + c), Sport.WALK)
        self.assertEqual(1, len(res.stops))
        self.assertLess(abs(res.stops[0].duration_ms - 60_000), 10_000)
        self.assertEqual(659_000, res.stats.elapsed_ms)
        self.assertLess(abs(res.stats.moving_ms - 600_000), 12_000)
        self.assertLess(abs(res.stats.distance - 840.0), 25.0)

    def test_a_manual_pause_is_not_counted(self):
        a = leg(None, 200, 3.0)
        gap = leg(a[-1], 1800, 0.0, step=60)
        b = leg(gap[-1], 200, 3.0)
        res = analysis.analyze(Track(a + b, [Pause(a[-1].time + 1000, b[0].time - 1000)]), Sport.BIKE)
        self.assertLess(abs(res.stats.moving_ms - 400_000), 4_000)
        self.assertGreater(res.stats.elapsed_ms, 2_000_000)
        self.assertEqual(1, len(res.pauses))
        self.assertLess(abs(res.stats.distance - 1200.0), 20.0)

    def test_gps_jumps_are_dropped(self):
        pts = leg(None, 60, 2.0)
        pts[30] = TrackPoint(pts[30].time, pts[30].lat + 0.5, pts[30].lon, pts[30].alt)
        res = analysis.analyze(Track(pts), Sport.WALK)
        self.assertEqual(1, res.quality.dropped)

    def test_noise_on_flat_ground_is_no_climb(self):
        pts = [TrackPoint(p.time, p.lat, p.lon, 1000.0 + (1.2 if i % 2 == 0 else -1.2))
               for i, p in enumerate(leg(None, 600, 1.5))]
        res = analysis.analyze(Track(pts), Sport.WALK)
        self.assertEqual(0.0, res.stats.ascent)
        self.assertEqual(0.0, res.stats.descent)

    def test_a_real_climb_is_counted(self):
        res = analysis.analyze(Track(leg(None, 600, 1.0, vz=0.2)), Sport.WALK)
        self.assertLess(abs(res.stats.ascent - 120.0), 10.0)

    def test_ski_runs_ignore_the_lift(self):
        pts, last = [], None
        for _ in range(3):
            down = leg(last, 120, 12.0, vz=-1.2)
            lift = leg(down[-1], 300, 2.0, vz=0.5)
            pts += down + lift
            last = lift[-1]
        res = analysis.analyze(Track(pts), Sport.SKI)
        self.assertEqual(3, res.stats.runs)
        self.assertEqual(3, res.stats.climbs)
        self.assertLess(abs(res.stats.climb_gain - 3 * 150.0), 40.0)
        self.assertTrue(any(x.up for x in res.samples))
        self.assertFalse(any(x.up and x.down for x in res.samples))
        self.assertLess(abs(res.stats.run_drop - 3 * 144.0), 40.0)
        self.assertLess(abs(res.stats.run_max_speed - 12.0), 1.0)
        self.assertGreater(min(s.speed for s in res.samples if s.down), 3.0)

    def test_no_runs_for_other_sports(self):
        self.assertEqual(0, analysis.analyze(Track(leg(None, 120, 12.0, vz=-1.2)), Sport.BIKE).stats.runs)

    def test_ski_without_altitude_falls_back_on_speed(self):
        strip = lambda ps: [TrackPoint(p.time, p.lat, p.lon) for p in ps]
        raw_down = leg(None, 90, 10.0)
        down = strip(raw_down)
        lift = strip(leg(raw_down[-1], 200, 2.0))
        res = analysis.analyze(Track(down + lift), Sport.SKI)
        self.assertEqual(1, res.stats.runs)
        self.assertFalse(res.quality.has_altitude)

    def test_quality(self):
        res = analysis.analyze(Track(leg(None, 100, 2.0, step=5, hr=140)), Sport.WALK)
        self.assertEqual(5.0, res.quality.median_interval_sec)
        self.assertTrue(res.quality.has_hr)
        self.assertEqual(140, res.stats.hr_avg)


class InfoTest(unittest.TestCase):
    def test_info_round_trips_through_the_note(self):
        info = act.ActivityInfo(Sport.SKI, T0, 7200, 6000, 25432.4, 410, 1500, 128, 21.3, 9, "attachments/ski-ab12cd.fit", None, 8, 3400)
        n = fmt.Note(path="activities/ski-ab12cd.md", id="ab12cd", title="Ski à La Plagne", created=T0, updated=T0,
                     activity=info, attachments=["attachments/ski-ab12cd.fit"])
        text = fmt.serialize(n)
        self.assertIn('activity: {"sport": "ski"', text)
        back = fmt.parse(n.path, text, T0)
        self.assertEqual(Sport.SKI, back.activity.sport)
        self.assertAlmostEqual(25432.4, back.activity.distance, places=1)
        self.assertEqual(9, back.activity.runs)
        self.assertEqual(8, back.activity.climbs)
        self.assertEqual(3400, back.activity.drop)
        self.assertEqual("attachments/ski-ab12cd.fit", back.activity.file)

    def test_the_kotlin_serialisation_parses_here(self):
        raw = '{"sport": "bike", "start": 1700000000000, "elapsed": 3600, "moving": 3000, "distance": 25000.0, "ascent": 120, "descent": 118, "hr": 140, "vmax": 12.50, "file": "attachments/a.fit"}'
        info = act.ActivityInfo.parse(raw)
        self.assertEqual(Sport.BIKE, info.sport)
        self.assertEqual(12.5, info.vmax)

    def test_plain_note_has_no_activity(self):
        n = fmt.Note(path="a.md", id="aaaaaa", title="x", created=T0, updated=T0)
        self.assertIsNone(fmt.parse("a.md", fmt.serialize(n), T0).activity)

    def test_french_formatting(self):
        self.assertEqual("850 m", act.fmt_distance(850.2))
        self.assertEqual("12,35 km", act.fmt_distance(12345.0))
        self.assertEqual("1:05:03", act.fmt_clock(3903))
        self.assertEqual("5:00 /km", act.fmt_pace(3.3333))
        self.assertEqual("36,0 km/h", act.fmt_speed(10.0))


if __name__ == "__main__":
    unittest.main()


class SportAndElevationTest(unittest.TestCase):
    def test_a_generic_sport_stays_other(self):
        self.assertEqual(Sport.GPS, Sport.guess(0))
        self.assertEqual(Sport.GPS, Sport.guess(None))
        self.assertEqual(Sport.EBIKE, Sport.guess(21))
        self.assertEqual(Sport.SKI, Sport.guess(13))

    def test_terrain_elevation_makes_ski_runs_detectable_without_altitude(self):
        from memento_win.core import elevation
        strip = lambda ps: [TrackPoint(p.time, p.lat, p.lon) for p in ps]
        raw_down = leg(None, 120, 12.0)
        down = strip(raw_down)
        lift = strip(leg(raw_down[-1], 300, 2.0))
        track = Track(down + lift)
        self.assertTrue(elevation.missing(track))
        turn = 45.0 + 1440.0 / M

        def fake(url):
            lats = [float(c.split(",")[0]) for c in url.split("locations=")[1].split("|")]
            alts = [2000 - (la - 45.0) * M * 0.1 if la <= turn else 2000 - 144 + (la - turn) * M * 0.25 for la in lats]
            import json
            return json.dumps({"results": [{"elevation": a} for a in alts], "status": "OK"})

        samples = elevation.pick(analysis.clean(track))
        self.assertLess(len(samples), len(track.points) / 4)
        alts = elevation.query(samples, get=fake, pause=lambda: None)
        filled = elevation.apply(track, elevation.parse(elevation.encode(alts)))
        self.assertTrue(all(p.alt is not None for p in filled.points))
        res = analysis.analyze(filled, Sport.SKI)
        self.assertEqual(1, res.stats.runs)
        self.assertLess(abs(res.stats.run_drop - 144.0), 25.0)

    def test_a_bad_answer_is_an_error(self):
        from memento_win.core import elevation
        with self.assertRaises(elevation.ElevationError):
            elevation.query(leg(None, 100, 5.0)[:3], get=lambda url: '{"error": "quota"}', pause=lambda: None)
