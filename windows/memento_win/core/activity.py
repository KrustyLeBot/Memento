"""What the note frontmatter says about an activity, and display helpers."""
from __future__ import annotations

import json
import time
from dataclasses import dataclass

from .analysis import Analysis
from .track import Sport, Track


ALT_REFINED = "baro+relief"  # `alt` of a recording whose barometer altitude was corrected with the terrain model


@dataclass
class ActivityInfo:
    sport: Sport
    start: int            # epoch millis
    elapsed: int          # seconds
    moving: int           # seconds
    distance: float       # metres
    ascent: int = 0
    descent: int = 0
    hr: int | None = None
    vmax: float | None = None   # m/s
    runs: int = 0
    file: str | None = None     # track file, relative to the notes folder
    alt: str | None = None      # altitude looked up from the terrain model (attachments/….alt)
    climbs: int = 0             # skiing: climbs (lifts, uphill) ...
    drop: int = 0               # ... and metres skied down

    def to_json(self) -> str:
        d: dict = {"sport": self.sport.key, "start": self.start, "elapsed": self.elapsed, "moving": self.moving,
                   "distance": round(self.distance, 1), "ascent": self.ascent, "descent": self.descent}
        if self.hr is not None:
            d["hr"] = self.hr
        if self.vmax is not None:
            d["vmax"] = round(self.vmax, 2)
        if self.runs > 0:
            d["runs"] = self.runs
        if self.file:
            d["file"] = self.file
        if self.alt:
            d["alt"] = self.alt
        if self.climbs > 0:
            d["climbs"] = self.climbs
        if self.drop > 0:
            d["drop"] = self.drop
        return json.dumps(d, ensure_ascii=False)

    @staticmethod
    def parse(raw: str) -> "ActivityInfo | None":
        try:
            d = json.loads(raw)
            start = int(float(d["start"]))
        except (ValueError, KeyError, TypeError):
            return None
        if not isinstance(d, dict):
            return None

        def num(key, default=0):
            try:
                return float(d.get(key, default))
            except (TypeError, ValueError):
                return default

        return ActivityInfo(
            sport=Sport.of(d.get("sport")), start=start, elapsed=int(num("elapsed")), moving=int(num("moving")),
            distance=num("distance"), ascent=int(num("ascent")), descent=int(num("descent")),
            hr=int(num("hr")) if "hr" in d else None, vmax=num("vmax") if "vmax" in d else None,
            runs=int(num("runs")), file=d.get("file") or None, alt=d.get("alt") or None,
            climbs=int(num("climbs")), drop=int(num("drop")),
        )

    @staticmethod
    def from_analysis(sport: Sport, a: Analysis, track: Track, file: str | None, alt: str | None = None,
                      untimed_start: int | None = None) -> "ActivityInfo":
        """untimed_start: the date of an untimed track, which has none (the import, or the date it already has)."""
        s = a.stats
        first = a.samples[0].time if a.samples else 0
        if track.untimed:
            start = untimed_start if untimed_start is not None else int(time.time() * 1000)
        else:
            start = track.start_time or first
        return ActivityInfo(
            sport=sport, start=start, elapsed=s.elapsed_ms // 1000, moving=s.moving_ms // 1000,
            distance=s.distance, ascent=round(s.ascent), descent=round(s.descent), hr=s.hr_avg,
            vmax=(s.run_max_speed if sport == Sport.SKI and s.runs > 0 else (s.max_speed or None)),
            runs=s.runs, file=file, alt=alt, climbs=s.climbs, drop=round(s.run_drop),
        )


# ---- display, in French ----

def _fr(text: str) -> str:
    return text.replace(".", ",")


def fmt_distance(m: float) -> str:
    return f"{round(m)} m" if m < 1000 else _fr(f"{m / 1000:.2f} km")


def fmt_duration(seconds: int) -> str:
    h, m, s = seconds // 3600, seconds % 3600 // 60, seconds % 60
    return f"{h} h {m:02d}" if h else f"{m} min {s:02d}"


def fmt_clock(seconds: int) -> str:
    h, m, s = seconds // 3600, seconds % 3600 // 60, seconds % 60
    return f"{h}:{m:02d}:{s:02d}" if h else f"{m:02d}:{s:02d}"


def fmt_speed(ms: float) -> str:
    return _fr(f"{ms * 3.6:.1f} km/h")


def fmt_pace(ms: float) -> str:
    if ms < 0.2:
        return "–"
    sec = round(1000 / ms)
    return f"{sec // 60}:{sec % 60:02d} /km"


CHART_SLOWEST_PACE_S = 20 * 60.0  # stops would otherwise flatten a pace chart


def chart_pace(ms: float) -> float:
    """Pace in seconds per km for a chart, capped at CHART_SLOWEST_PACE_S."""
    return CHART_SLOWEST_PACE_S if ms <= 1000 / CHART_SLOWEST_PACE_S else 1000 / ms


def fmt_speed_for(sport: Sport, ms: float) -> str:
    return fmt_pace(ms) if sport == Sport.WALK else fmt_speed(ms)
