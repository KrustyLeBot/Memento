"""Activity analysis: cleaning, stops, distance, climb and ski runs. Same algorithms as the Android app."""
from __future__ import annotations

import math
from dataclasses import dataclass, field
from operator import attrgetter

from .track import EARTH_RADIUS, Pause, Sport, Track, TrackPoint

MAX_SPEED = 50.0          # m/s: faster between two fixes is a GPS jump
STOP_RADIUS = 8.0         # m: 1 km/h for 30 s
STOP_MIN_MS = 30_000
ALT_WINDOW_MS = 10_000
SPEED_WINDOW_MS = 5_000
CLIMB_THRESHOLD = 3.0     # m
DOWN_RATE = -0.3          # m/s of altitude
DOWN_MIN_SPEED = 3.0      # m/s
DOWN_NO_ALT_SPEED = 4.2   # m/s: without altitude, faster than a lift
RUN_MERGE_MS = 15_000
RUN_MIN_DROP = 20.0
RUN_MIN_MS = 15_000
PAUSE_BRIDGE = 100.0      # m
UP_RATE = 0.15            # m/s of altitude
CLIMB_MERGE_MS = 40_000
CLIMB_MIN_GAIN = 30.0
CLIMB_MIN_MS = 60_000
MAX_WINDOW_MS = 15_000


@dataclass(slots=True)  # no per-object dict: a 60-hour race has 200 000 samples
class Sample:
    time: int
    lat: float
    lon: float
    dist: float            # metres since the start, stops excluded
    alt: float | None      # smoothed
    speed: float           # smoothed, m/s
    hr: int | None
    down: bool
    stopped: bool
    up: bool = False       # part of a ski climb (lift, uphill)


@dataclass
class Stop:
    start: int
    end: int
    lat: float
    lon: float
    manual: bool

    @property
    def duration_ms(self) -> int:
        return self.end - self.start


@dataclass
class Run:
    first: int
    last: int
    distance: float
    drop: float
    max_speed: float
    duration_ms: int

    @property
    def avg_speed(self) -> float:
        return self.distance / (self.duration_ms / 1000) if self.duration_ms > 0 else 0.0


@dataclass
class Climb:
    first: int
    last: int
    distance: float
    gain: float
    duration_ms: int


@dataclass
class Stats:
    elapsed_ms: int = 0
    moving_ms: int = 0
    distance: float = 0.0
    ascent: float = 0.0
    descent: float = 0.0
    max_speed: float = 0.0
    min_alt: float | None = None
    max_alt: float | None = None
    hr_avg: int | None = None
    hr_max: int | None = None
    runs: int = 0
    run_drop: float = 0.0
    run_max_speed: float = 0.0
    run_avg_speed: float = 0.0
    run_longest: float = 0.0
    climbs: int = 0
    climb_gain: float = 0.0

    @property
    def avg_speed(self) -> float:
        return self.distance / (self.moving_ms / 1000) if self.moving_ms > 0 else 0.0


@dataclass
class Quality:
    points: int = 0
    dropped: int = 0
    has_altitude: bool = False
    has_hr: bool = False
    median_interval_sec: float = 0.0
    manufacturer: int | None = None


@dataclass
class Analysis:
    samples: list[Sample]
    stops: list[Stop]
    pauses: list[Stop]
    runs: list[Run]
    climbs: list[Climb]
    stats: Stats
    quality: Quality = field(default_factory=Quality)


def clean(track: Track) -> list[TrackPoint]:
    """Sorted by time, no repeated instants, no impossible jumps, nothing recorded while paused."""
    out: list[TrackPoint] = []
    pauses = [(pa.start, pa.end) for pa in track.pauses]
    check_speed = not track.untimed
    radians, cos, sin, asin, sqrt = math.radians, math.cos, math.sin, math.asin, math.sqrt
    prev = None
    prev_t = prev_lat_r = prev_cos = 0.0
    for p in sorted(track.points, key=attrgetter("time")):
        if not (-90 <= p.lat <= 90 and -180 <= p.lon <= 180):
            continue
        t = p.time
        if pauses and any(start < t < end for start, end in pauses):
            continue
        if check_speed:
            # track.distance's formula with each point's radians and cosine computed once, not once per pair
            lat_r = radians(p.lat)
            cos_r = cos(lat_r)
        if prev is not None:
            dt = (t - prev_t) / 1000
            if dt <= 0:
                continue
            # stand-in times: a route's points can be far apart, which is no GPS jump
            if check_speed:
                a = sin((lat_r - prev_lat_r) / 2) ** 2 + prev_cos * cos_r * sin(radians(p.lon - prev.lon) / 2) ** 2
                if 2 * EARTH_RADIUS * asin(sqrt(a)) / dt > MAX_SPEED:
                    continue
        out.append(p)
        prev, prev_t = p, t
        if check_speed:
            prev_lat_r, prev_cos = lat_r, cos_r
    return out


def analyze(track: Track, sport: Sport) -> Analysis:
    total = len(track.points)
    kept = clean(track)
    n = len(kept)
    times = [p.time for p in kept]
    seg = [0] * n
    if track.pauses:
        pauses = [(p.start, p.end) for p in track.pauses]
        for i in range(1, n):
            t0, t1 = times[i - 1], times[i]
            seg[i] = seg[i - 1] + (1 if any(start < t1 and end > t0 for start, end in pauses) else 0)

    stop_idx = [False] * n
    stop_of = [-1] * n  # which stop a point belongs to: two points in the same one is an O(1) check
    stops: list[Stop] = []
    # "within STOP_RADIUS": a flat projection around the first point, exact to the micrometre at 8 m, without the
    # trigonometry of a full distance (a 200 000-point file asks it more than a million times)
    lat_r = [math.radians(p.lat) for p in kept]
    lon_r = [math.radians(p.lon) for p in kept]
    cos_lat = [math.cos(v) for v in lat_r]
    r2 = (STOP_RADIUS / EARTH_RADIUS) ** 2
    pi, two_pi = math.pi, 2 * math.pi
    i = 0
    m = 0
    while i < n:
        # a stop reaches the first point STOP_MIN_MS later: unless that one is in the circle, no walk from i can be a
        # stop (one test instead of walking every point a few metres apart while moving)
        if m < i:
            m = i
        while m < n and times[m] - times[i] < STOP_MIN_MS:
            m += 1
        if m == n or seg[m] != seg[i]:
            i += 1
            continue
        dlon = lon_r[m] - lon_r[i]
        if dlon > pi or dlon < -pi:  # across the antimeridian: 179.9999 and -179.9999 are 0.0002 degree apart
            dlon -= two_pi if dlon > 0 else -two_pi
        if (dlon * cos_lat[i]) ** 2 + (lat_r[m] - lat_r[i]) ** 2 > r2:
            i += 1
            continue
        j = i
        while j + 1 < n and seg[j + 1] == seg[i]:
            dlon = lon_r[j + 1] - lon_r[i]
            if dlon > pi or dlon < -pi:
                dlon -= two_pi if dlon > 0 else -two_pi
            if (dlon * cos_lat[i]) ** 2 + (lat_r[j + 1] - lat_r[i]) ** 2 > r2:
                break
            j += 1
        if j > i and times[j] - times[i] >= STOP_MIN_MS:
            for k in range(i, j + 1):
                stop_idx[k] = True
                stop_of[k] = len(stops)
            stops.append(Stop(kept[i].time, kept[j].time, kept[i].lat, kept[i].lon, False))
            i = j + 1
        else:
            i += 1

    lons = [p.lon for p in kept]
    radians, sin, asin, sqrt = math.radians, math.sin, math.asin, math.sqrt
    diameter = 2 * EARTH_RADIUS

    cum = [0.0] * n
    moving = 0
    total_dist = 0.0
    for k in range(1, n):
        new_seg = seg[k] != seg[k - 1]
        if new_seg or not (stop_of[k] >= 0 and stop_of[k] == stop_of[k - 1]):
            # track.distance from the radians and cosines above: the same operations in the same order, same result
            d = diameter * asin(sqrt(sin((lat_r[k] - lat_r[k - 1]) / 2) ** 2
                                     + cos_lat[k - 1] * cos_lat[k] * sin(radians(lons[k] - lons[k - 1]) / 2) ** 2))
            if not new_seg:
                total_dist += d
                moving += times[k] - times[k - 1]
            elif d <= PAUSE_BRIDGE:
                # a pause (watch auto-pause, button): what was walked across it still counts, unless it is a jump
                total_dist += d
        cum[k] = total_dist

    # the smoothing window is in time: over stand-in times it would average points kilometres apart on a route
    alt = [p.alt for p in kept] if track.untimed else _smooth_altitude(kept)
    speed = _smooth_speed(times, seg, cum)
    has_alt = any(a is not None for a in alt)

    down = [False] * n
    runs: list[Run] = []
    if sport == Sport.SKI and n > 1:
        step_down = [False] * n
        for k in range(1, n):
            if seg[k] != seg[k - 1]:
                continue
            dt = (kept[k].time - kept[k - 1].time) / 1000
            if dt <= 0:
                continue
            if has_alt and alt[k] is not None and alt[k - 1] is not None:
                step_down[k] = (alt[k] - alt[k - 1]) / dt <= DOWN_RATE and speed[k] >= DOWN_MIN_SPEED
            else:
                step_down[k] = speed[k] >= DOWN_NO_ALT_SPEED
        k = 1
        while k < n:
            if not step_down[k]:
                k += 1
                continue
            end = k
            m = k + 1
            while m < n and seg[m] == seg[k]:
                if step_down[m]:
                    end = m
                    m += 1
                    continue
                q = m
                while q < n and not step_down[q] and seg[q] == seg[k] and kept[q].time - kept[end].time <= RUN_MERGE_MS:
                    q += 1
                if q < n and step_down[q] and seg[q] == seg[k] and kept[q].time - kept[end].time <= RUN_MERGE_MS:
                    m = q
                    continue
                break
            first = k - 1
            dur = kept[end].time - kept[first].time
            drop = alt[first] - alt[end] if has_alt and alt[first] is not None and alt[end] is not None else 0.0
            if dur >= RUN_MIN_MS and (not has_alt or drop >= RUN_MIN_DROP):
                vmax = 0.0
                for q in range(first, end + 1):
                    down[q] = True
                    vmax = max(vmax, speed[q])
                runs.append(Run(first, end, cum[end] - cum[first], max(drop, 0.0), vmax, dur))
            k = end + 1

    up = [False] * n
    climbs: list[Climb] = []
    if sport == Sport.SKI and has_alt and n > 1:
        step_up = [False] * n
        for k in range(1, n):
            if seg[k] != seg[k - 1]:
                continue
            dt = (kept[k].time - kept[k - 1].time) / 1000
            if dt > 0 and alt[k] is not None and alt[k - 1] is not None:
                step_up[k] = (alt[k] - alt[k - 1]) / dt >= UP_RATE
        k = 1
        while k < n:
            if not step_up[k]:
                k += 1
                continue
            end = k
            m = k + 1
            while m < n and seg[m] == seg[k]:
                if step_up[m]:
                    end = m
                    m += 1
                    continue
                q = m
                while q < n and not step_up[q] and seg[q] == seg[k] and kept[q].time - kept[end].time <= CLIMB_MERGE_MS:
                    q += 1
                if q < n and step_up[q] and seg[q] == seg[k] and kept[q].time - kept[end].time <= CLIMB_MERGE_MS:
                    m = q
                    continue
                break
            first = k - 1
            gain = alt[end] - alt[first] if alt[first] is not None and alt[end] is not None else 0.0
            dur = kept[end].time - kept[first].time
            if gain >= CLIMB_MIN_GAIN and dur >= CLIMB_MIN_MS:
                for q in range(first, end + 1):
                    if not down[q]:
                        up[q] = True
                climbs.append(Climb(first, end, cum[end] - cum[first], gain, dur))
            k = end + 1

    ascent = descent = 0.0
    ref = None
    for a in alt:
        if a is None:
            continue
        if ref is None:
            ref = a
        elif a - ref >= CLIMB_THRESHOLD:
            ascent += a - ref
            ref = a
        elif ref - a >= CLIMB_THRESHOLD:
            descent += ref - a
            ref = a

    samples = [Sample(kept[k].time, kept[k].lat, kept[k].lon, cum[k], alt[k], speed[k], kept[k].hr, down[k], stop_idx[k], up[k])
               for k in range(n)]
    pause_stops = []
    if kept:
        for p in track.pauses:
            if p.end > kept[0].time and p.start < kept[-1].time:
                ref0 = next((q for q in reversed(kept) if q.time <= p.start), kept[0])
                pause_stops.append(Stop(p.start, p.end, ref0.lat, ref0.lon, True))

    hrs = [q.hr for q in kept if q.hr is not None]
    alts = [a for a in alt if a is not None]
    elapsed = kept[-1].time - kept[0].time if n >= 2 else (track.session_elapsed_ms or 0)
    intervals = sorted([(b - a) / 1000 for a, b in zip(times, times[1:])])
    run_ms = sum(r.duration_ms for r in runs)
    stats = Stats(
        elapsed_ms=elapsed,
        moving_ms=moving if n >= 2 else (track.session_timer_ms or 0),
        distance=_device_or_computed(track.session_distance, cum[-1] if n >= 2 else 0.0),
        ascent=ascent, descent=descent,
        max_speed=_max_window_speed(times, seg, cum),
        min_alt=min(alts) if alts else None, max_alt=max(alts) if alts else None,
        hr_avg=round(sum(hrs) / len(hrs)) if hrs else track.hr_avg,
        hr_max=max(hrs) if hrs else track.hr_max,
        runs=len(runs), run_drop=sum(r.drop for r in runs),
        run_max_speed=max((r.max_speed for r in runs), default=0.0),
        run_avg_speed=sum(r.distance for r in runs) / (run_ms / 1000) if run_ms else 0.0,
        run_longest=max((r.distance for r in runs), default=0.0),
        climbs=len(climbs), climb_gain=sum(c.gain for c in climbs),
    )
    quality = Quality(
        points=total, dropped=total - n, has_altitude=has_alt, has_hr=bool(hrs) or track.hr_avg is not None,
        median_interval_sec=intervals[len(intervals) // 2] if intervals else 0.0, manufacturer=track.manufacturer,
    )
    if track.untimed:
        # stand-in times one second apart: on a file recorded at 1 Hz (most watches) the stops above still keep the
        # jitter of standing still out of the distance, but their durations mean nothing and are not shown
        stops = []
        for s in samples:
            s.speed = 0.0
        stats.elapsed_ms = stats.moving_ms = 0
        stats.max_speed = stats.run_max_speed = stats.run_avg_speed = 0.0
        quality.median_interval_sec = 0.0
    return Analysis(samples, stops, pause_stops, runs, climbs, stats, quality)


def _max_window_speed(times: list[int], seg: list[int], cum: list[float]) -> float:
    """Top speed held for MAX_WINDOW_MS: single GPS catch-up spikes (after a signal gap) do not count."""
    best, j, n = 0.0, 0, len(times)
    least = MAX_WINDOW_MS / 1000 * 0.8
    for i in range(n):
        if j < i:
            j = i
        si, ti = seg[i], times[i]
        while j + 1 < n and seg[j + 1] == si and times[j] - ti < MAX_WINDOW_MS:
            j += 1
        dt = (times[j] - ti) / 1000
        if dt >= least:
            v = (cum[j] - cum[i]) / dt
            if v > best:
                best = v
    return best


def _device_or_computed(device: float | None, computed: float) -> float:
    """The device's own total when the file has one and it is in the same range as ours (its sensors know more)."""
    if device and device > 0 and (computed <= 0 or computed * 0.8 <= device <= computed * 1.25):
        return device
    return computed


def _smooth_altitude(p: list[TrackPoint]) -> list[float | None]:
    times = [q.time for q in p]
    alts = [q.alt for q in p]
    # Nones before each index: a window without any is summed as a plain slice (same values, same order, same sum)
    nones = [0] * (len(p) + 1)
    for i, a in enumerate(alts):
        nones[i + 1] = nones[i] + (a is None)
    out: list[float | None] = []
    lo = hi = 0
    n = len(p)
    for i in range(n):
        if alts[i] is None:
            out.append(None)
            continue
        t = times[i]
        while times[lo] < t - ALT_WINDOW_MS:
            lo += 1
        if hi < i:
            hi = i
        while hi + 1 < n and times[hi + 1] <= t + ALT_WINDOW_MS:
            hi += 1
        if nones[hi + 1] == nones[lo]:
            out.append(sum(alts[lo:hi + 1]) / (hi + 1 - lo))
        else:
            vals = [a for a in alts[lo:hi + 1] if a is not None]
            out.append(sum(vals) / len(vals))
    return out


def _smooth_speed(times: list[int], seg: list[int], cum: list[float]) -> list[float]:
    n = len(times)
    out = [0.0] * n
    # times strictly increase (clean): both ends of the window only move forward, which makes this linear
    lo = hi = 0
    for i in range(n):
        si, t = seg[i], times[i]
        if seg[lo] != si:
            lo = i
        while t - times[lo] > SPEED_WINDOW_MS:
            lo += 1
        if hi < i:
            hi = i
        while hi + 1 < n and seg[hi + 1] == si and times[hi + 1] - t <= SPEED_WINDOW_MS:
            hi += 1
        a, b = lo, hi
        if a == b:
            if i > 0 and seg[i - 1] == si:
                a = i - 1
            elif i + 1 < n and seg[i + 1] == si:
                b = i + 1
        dt = (times[b] - times[a]) / 1000
        out[i] = (cum[b] - cum[a]) / dt if dt > 0 else 0.0
    return out
