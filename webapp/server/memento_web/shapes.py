"""The analysis JSON the activity page draws, built from the Windows core's Analysis. Apart from the web module
(modules/activities.py re-exports it) because the analysis child (trackwork.py) builds it too: this file imports only
memento_win.core, so the child never loads starlette and asyncio for it.
"""
from __future__ import annotations

import json
from dataclasses import asdict

from memento_win.core import activity as act
from memento_win.core import mapdata
from memento_win.core.analysis import Analysis
from memento_win.core.track import Sport

CHART_POINTS = 400   # charts: enough for a smooth curve and a precise hover on a wide screen
MAP_POINTS = 5000    # the map: as the Android and Windows hosts send it


def _r(values, digits):
    return [None if v is None else round(v, digits) for v in values]


def _thin(n: int, limit: int) -> list[int]:
    return mapdata.thin_indices(n, limit)


def _bucket_means(values: list, idx: list[int]) -> list:
    """For each kept index, the mean of the values (None skipped) from halfway to the previous kept index to
    halfway to the next: every sample counts once. Kept as is when nothing was thinned."""
    total, count = [0.0], [0]
    for v in values:
        total.append(total[-1] + (v or 0.0))
        count.append(count[-1] + (v is not None))
    out = []
    for k, i in enumerate(idx):
        lo = 0 if k == 0 else (idx[k - 1] + i) // 2 + 1
        hi = len(values) if k == len(idx) - 1 else (i + idx[k + 1]) // 2 + 1
        n = count[hi] - count[lo]
        out.append((total[hi] - total[lo]) / n if n else None)
    return out


def dumps(value) -> bytes:
    return json.dumps(value, separators=(",", ":")).encode()


def _pos(v: float) -> float:
    return round(v, 6)  # 0.1 m: the core's floats carry 17 digits nobody draws


def analysis_json(a: Analysis, sport: Sport, alt_source: str | None) -> dict:
    """What the detail page draws: stats, stops, ski runs and climbs, charts and the map's data."""
    s = a.samples
    st = a.stats
    stats = {**asdict(st), "avg_speed": st.avg_speed}
    quality = {**asdict(a.quality), "alt_source": alt_source}
    stops = [{"start": x.start, "end": x.end, "duration": x.duration_ms // 1000, "manual": x.manual,
              "lat": _pos(x.lat), "lon": _pos(x.lon)} for x in sorted(a.pauses + a.stops, key=lambda x: x.start)]
    runs = [{"start": s[r.first].time, "end": s[r.last].time, "distance": round(r.distance, 1), "drop": round(r.drop),
             "max_speed": round(r.max_speed, 2), "avg_speed": round(r.avg_speed, 2), "duration": r.duration_ms // 1000,
             "lat": _pos(s[r.first].lat), "lon": _pos(s[r.first].lon)} for r in a.runs]
    climbs = [{"start": s[c.first].time, "end": s[c.last].time, "distance": round(c.distance, 1), "gain": round(c.gain),
               "duration": c.duration_ms // 1000, "lat": _pos(s[c.first].lat), "lon": _pos(s[c.first].lon)} for c in a.climbs]
    out = {"sport": sport.key, "stats": stats, "quality": quality, "stops": stops, "runs": runs, "climbs": climbs,
           "chart": None, "map": None,
           # a GPX without any <time>: its points have stand-in times, the analysis keeps no duration
           "untimed": len(s) >= 2 and st.elapsed_ms == 0}
    if len(s) < 2:
        return out
    idx = _thin(len(s), CHART_POINTS)
    pts = [s[i] for i in idx]
    walk = sport == Sport.WALK
    # speed and heart rate: the mean around each drawn point. One sample in 500 (a 60-hour race) caught whatever
    # instant it fell on, a stop or a sprint, and drew a comb instead of a curve.
    speeds = _bucket_means([p.speed for p in s], idx)
    hrs = _bucket_means([p.hr for p in s], idx)
    out["chart"] = {
        "dist": _r((p.dist for p in pts), 1),
        "lon": _r((p.lon for p in pts), 6), "lat": _r((p.lat for p in pts), 6),
        "alt": _r((p.alt for p in pts), 1) if a.quality.has_altitude and all(p.alt is not None for p in pts) else None,
        "speed": _r(speeds, 2) if st.max_speed > 0 else None,
        # walking: pace in s/km, capped (core activity.chart_pace) so a stop does not flatten the curve
        "pace": _r((act.chart_pace(v) for v in speeds), 1) if walk and st.max_speed > 0 else None,
        "hr": [None if v is None else round(v) for v in hrs]
        if a.quality.has_hr and sum(v is not None for v in hrs) >= 2 else None,
    }
    idx = _thin(len(s), MAP_POINTS)
    pts = [s[i] for i in idx]
    ski = sport == Sport.SKI
    m = {"lon": _r((p.lon for p in pts), 6), "lat": _r((p.lat for p in pts), 6),
         "dots": [{"lon": _pos(s[0].lon), "lat": _pos(s[0].lat), "color": "#00C853"},
                  {"lon": _pos(s[-1].lon), "lat": _pos(s[-1].lat), "color": "#FF1744"}]}
    if ski:
        # coloured lines are built in the page from these (descents by speed, climbs in blue), as Windows' map_lines
        lats, lons = [p.lat for p in s], [p.lon for p in s]
        m.update({"square": [round(v, 7) for v in mapdata.square_of(min(lons), min(lats), max(lons), max(lats))],
                  "t": [(p.time - pts[0].time) // 1000 for p in pts], "speed": _r((p.speed for p in pts), 2),
                  "kind": "".join("d" if p.down else "u" if p.up else "o" for p in pts),
                  "max_gap": max(a.quality.median_interval_sec * 6, 30.0)})
    else:
        speeds, avg = mapdata.track_speeds([p.time for p in pts], [p.lat for p in pts], [p.lon for p in pts],
                                           [p.speed for p in pts])
        m.update({"speeds": _r(speeds, 2) if speeds else None, "avg": avg})
    out["map"] = m
    return out
