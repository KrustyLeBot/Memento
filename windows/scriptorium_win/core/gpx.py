"""GPX reader. A new <trkseg> after the first is taken as a pause (the timer was stopped there)."""
from __future__ import annotations

import re
from datetime import datetime, timezone

from .fit import FitError
from .track import Pause, Track, TrackPoint

_TRKPT = re.compile(r"<trkpt\b([^>]*?)(/>|>(.*?)</trkpt>)", re.S)
_SEG = re.compile(r"<trkseg\b")
_LAT = re.compile(r"""\blat\s*=\s*["']([^"']+)["']""")
_LON = re.compile(r"""\blon\s*=\s*["']([^"']+)["']""")
_ELE = re.compile(r"<ele>\s*([^<\s]+)\s*</ele>")
_TIME = re.compile(r"<time>\s*([^<\s]+)\s*</time>")
_HR = re.compile(r"<(?:\w+:)?hr>\s*(\d+)\s*</(?:\w+:)?hr>")
_NAME = re.compile(r"<trk\b[^>]*>.*?<name>\s*(?:<!\[CDATA\[)?(.*?)(?:\]\]>)?\s*</name>", re.S)


def looks_like_gpx(text: str) -> bool:
    return "<gpx" in text.lower()


def parse_time(s: str) -> int | None:
    try:
        if s.endswith("Z"):
            s = s[:-1] + "+00:00"
        d = datetime.fromisoformat(s)
        if d.tzinfo is None:
            d = d.replace(tzinfo=timezone.utc)
        return round(d.timestamp() * 1000)
    except ValueError:
        return None


def read(text: str) -> Track:
    if not looks_like_gpx(text):
        raise FitError("Ce n'est pas un fichier GPX")
    points: list[TrackPoint] = []
    seg_starts: list[int] = []
    seg_pos = [m.start() for m in _SEG.finditer(text)]
    seg_idx = -1
    for m in _TRKPT.finditer(text):
        while seg_idx + 1 < len(seg_pos) and seg_pos[seg_idx + 1] < m.start():
            seg_idx += 1
            seg_starts.append(len(points))
        attrs, inner = m.group(1), m.group(3) or ""
        la, lo = _LAT.search(attrs), _LON.search(attrs)
        tm = _TIME.search(inner)
        if not (la and lo and tm):
            continue
        try:
            lat, lon = float(la.group(1)), float(lo.group(1))
        except ValueError:
            continue
        t = parse_time(tm.group(1))
        if t is None:
            continue
        ele = _ELE.search(inner)
        hr = _HR.search(inner)
        alt = None
        if ele:
            try:
                alt = float(ele.group(1))
            except ValueError:
                pass
        points.append(TrackPoint(t, lat, lon, alt, int(hr.group(1)) if hr else None, gps_alt=alt))
    pauses = []
    for s in seg_starts[1:]:
        if 0 < s < len(points):
            a, b = points[s - 1].time, points[s].time
            if b > a:
                pauses.append(Pause(a, b))
    ordered = sorted(points, key=lambda q: q.time)
    hrs = [q.hr for q in ordered if q.hr is not None]
    name = _NAME.search(text)
    return Track(
        points=ordered, pauses=pauses, name=(name.group(1).strip() or None) if name else None,
        start_time=ordered[0].time if ordered else None,
        hr_avg=round(sum(hrs) / len(hrs)) if hrs else None, hr_max=max(hrs) if hrs else None,
    )
