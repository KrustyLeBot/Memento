"""Activity analysis: cleaning, stops, distance, climb and ski runs. Same algorithms as the Android app."""
from __future__ import annotations

from dataclasses import dataclass, field

from .track import Pause, Sport, Track, TrackPoint, distance_between

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


@dataclass
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
    for p in sorted(track.points, key=lambda q: q.time):
        if not (-90 <= p.lat <= 90 and -180 <= p.lon <= 180):
            continue
        if any(pa.start < p.time < pa.end for pa in track.pauses):
            continue
        if out:
            prev = out[-1]
            dt = (p.time - prev.time) / 1000
            if dt <= 0 or distance_between(prev, p) / dt > MAX_SPEED:
                continue
        out.append(p)
    return out


def analyze(track: Track, sport: Sport) -> Analysis:
    total = len(track.points)
    kept = clean(track)
    n = len(kept)
    seg = [0] * n
    for i in range(1, n):
        crosses = any(p.start < kept[i].time and p.end > kept[i - 1].time for p in track.pauses)
        seg[i] = seg[i - 1] + (1 if crosses else 0)

    stop_idx = [False] * n
    stops: list[Stop] = []
    i = 0
    while i < n:
        j = i
        while j + 1 < n and seg[j + 1] == seg[i] and distance_between(kept[i], kept[j + 1]) <= STOP_RADIUS:
            j += 1
        if j > i and kept[j].time - kept[i].time >= STOP_MIN_MS:
            for k in range(i, j + 1):
                stop_idx[k] = True
            stops.append(Stop(kept[i].time, kept[j].time, kept[i].lat, kept[i].lon, False))
            i = j + 1
        else:
            i += 1

    def same_stop(a: int, b: int) -> bool:
        return any(a >= s.start and b <= s.end for s in stops)

    cum = [0.0] * n
    moving = 0
    for k in range(1, n):
        cum[k] = cum[k - 1]
        if seg[k] != seg[k - 1]:
            # a pause (watch auto-pause, button): what was walked across it still counts, unless it is a jump
            d = distance_between(kept[k - 1], kept[k])
            if d <= PAUSE_BRIDGE:
                cum[k] += d
            continue
        in_stop = stop_idx[k] and stop_idx[k - 1] and same_stop(kept[k - 1].time, kept[k].time)
        if not in_stop:
            cum[k] += distance_between(kept[k - 1], kept[k])
            moving += kept[k].time - kept[k - 1].time

    alt = _smooth_altitude(kept)
    speed = _smooth_speed(kept, seg, cum)
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
    intervals = sorted((kept[k].time - kept[k - 1].time) / 1000 for k in range(1, n))
    run_ms = sum(r.duration_ms for r in runs)
    stats = Stats(
        elapsed_ms=elapsed,
        moving_ms=moving if n >= 2 else (track.session_timer_ms or 0),
        distance=_device_or_computed(track.session_distance, cum[-1] if n >= 2 else 0.0),
        ascent=ascent, descent=descent,
        max_speed=_max_window_speed(kept, seg, cum),
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
    return Analysis(samples, stops, pause_stops, runs, climbs, stats, quality)


def _max_window_speed(p: list[TrackPoint], seg: list[int], cum: list[float]) -> float:
    """Top speed held for MAX_WINDOW_MS: single GPS catch-up spikes (after a signal gap) do not count."""
    best, j = 0.0, 0
    for i in range(len(p)):
        j = max(j, i)
        while j + 1 < len(p) and seg[j + 1] == seg[i] and p[j].time - p[i].time < MAX_WINDOW_MS:
            j += 1
        dt = (p[j].time - p[i].time) / 1000
        if dt >= MAX_WINDOW_MS / 1000 * 0.8:
            best = max(best, (cum[j] - cum[i]) / dt)
    return best


def _device_or_computed(device: float | None, computed: float) -> float:
    """The device's own total when the file has one and it is in the same range as ours (its sensors know more)."""
    if device and device > 0 and (computed <= 0 or computed * 0.8 <= device <= computed * 1.25):
        return device
    return computed


def _smooth_altitude(p: list[TrackPoint]) -> list[float | None]:
    out: list[float | None] = []
    lo = hi = 0
    for i in range(len(p)):
        if p[i].alt is None:
            out.append(None)
            continue
        while p[lo].time < p[i].time - ALT_WINDOW_MS:
            lo += 1
        hi = max(hi, i)
        while hi + 1 < len(p) and p[hi + 1].time <= p[i].time + ALT_WINDOW_MS:
            hi += 1
        vals = [q.alt for q in p[lo:hi + 1] if q.alt is not None]
        out.append(sum(vals) / len(vals))
    return out


def _smooth_speed(p: list[TrackPoint], seg: list[int], cum: list[float]) -> list[float]:
    n = len(p)
    out = [0.0] * n
    for i in range(n):
        lo = i
        while lo > 0 and seg[lo - 1] == seg[i] and p[i].time - p[lo - 1].time <= SPEED_WINDOW_MS:
            lo -= 1
        hi = i
        while hi + 1 < n and seg[hi + 1] == seg[i] and p[hi + 1].time - p[i].time <= SPEED_WINDOW_MS:
            hi += 1
        if lo == hi:
            if i > 0 and seg[i - 1] == seg[i]:
                lo = i - 1
            elif i + 1 < n and seg[i + 1] == seg[i]:
                hi = i + 1
        dt = (p[hi].time - p[lo].time) / 1000
        out[i] = (cum[hi] - cum[lo]) / dt if dt > 0 else 0.0
    return out
