"""What the note frontmatter says about an activity, display helpers and the slippy-map maths."""
from __future__ import annotations

import json
import math
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
    def from_analysis(sport: Sport, a: Analysis, track: Track, file: str | None, alt: str | None = None) -> "ActivityInfo":
        s = a.stats
        first = a.samples[0].time if a.samples else 0
        return ActivityInfo(
            sport=sport, start=track.start_time or first, elapsed=s.elapsed_ms // 1000, moving=s.moving_ms // 1000,
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


def fmt_speed_for(sport: Sport, ms: float) -> str:
    return fmt_pace(ms) if sport == Sport.WALK else fmt_speed(ms)


# ---- Web Mercator ----

TILE = 256
MIN_ZOOM, MAX_ZOOM = 2, 18


def world_x(lon: float, zoom: float) -> float:
    return (lon + 180.0) / 360.0 * 2.0 ** zoom


def world_y(lat: float, zoom: float) -> float:
    r = math.radians(max(-85.0511, min(85.0511, lat)))
    return (1 - math.log(math.tan(r) + 1 / math.cos(r)) / math.pi) / 2 * 2.0 ** zoom


def lon_of(x: float, zoom: float) -> float:
    return x / 2.0 ** zoom * 360.0 - 180.0


def lat_of(y: float, zoom: float) -> float:
    n = math.pi - 2 * math.pi * y / 2.0 ** zoom
    return math.degrees(math.atan(math.sinh(n)))


def fit_zoom(min_lat, min_lon, max_lat, max_lon, width_px, height_px) -> float:
    w = max(world_x(max_lon, 0) - world_x(min_lon, 0), 1e-9)
    h = max(world_y(min_lat, 0) - world_y(max_lat, 0), 1e-9)
    zx = math.log2(width_px * 0.85 / TILE / w)
    zy = math.log2(height_px * 0.85 / TILE / h)
    return max(MIN_ZOOM, min(17.0, zx, zy))
