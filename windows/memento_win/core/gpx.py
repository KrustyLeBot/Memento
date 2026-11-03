"""GPX reader. A new <trkseg> after the first is taken as a pause (the timer was stopped there). A file with no <time>
at all is read as an untimed track (Track.untimed)."""
from __future__ import annotations

import re
from datetime import datetime, timezone

from .fit import FitError
from .track import Pause, Track, TrackPoint

_TRKPT_START = re.compile(r"<trkpt\b")
_SEG = re.compile(r"<trkseg\b")
_LAT = re.compile(r"""\blat\s*=\s*["']([^"']+)["']""")
_LON = re.compile(r"""\blon\s*=\s*["']([^"']+)["']""")
_ELE = re.compile(r"<ele>\s*([^<\s]+)\s*</ele>")
_TIME = re.compile(r"<time>\s*([^<\s]+)\s*</time>")
_HR = re.compile(r"<(?:\w+:)?hr>\s*(\d+)\s*</(?:\w+:)?hr>")
_TRK = re.compile(r"<trk\b[^>]*>")
UNTIMED_STEP_MS = 1000


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


def _trkpts(text: str):
    """(start, attributes, inner text) of each <trkpt>, in one linear pass: a point's inner text ends at its
    </trkpt> and never runs past the next <trkpt (a backtracking regex over a crafted file of unclosed points was
    quadratic: minutes for a few hundred kilobytes, under the data lock)."""
    starts = [m.start() for m in _TRKPT_START.finditer(text)]
    for i, start in enumerate(starts):
        limit = starts[i + 1] if i + 1 < len(starts) else len(text)
        close = text.find(">", start, limit)
        if close < 0:
            continue
        if text[close - 1] == "/":
            yield start, text[start + 6:close - 1], ""
            continue
        end = text.find("</trkpt>", close, limit)
        if end >= 0:
            yield start, text[start + 6:close], text[close + 1:end]


def _name(text: str) -> str | None:
    trk = _TRK.search(text)
    if trk is None:
        return None
    start = text.find("<name>", trk.end())
    end = text.find("</name>", start + 6) if start >= 0 else -1
    if end < 0:
        return None
    name = text[start + 6:end].strip()
    if name.startswith("<![CDATA["):
        name = name[9:].removesuffix("]]>")
    return name.strip() or None


def read(text: str) -> Track:
    if not looks_like_gpx(text):
        raise FitError("Ce n'est pas un fichier GPX")
    points: list[TrackPoint] = []
    untimed: list[TrackPoint] = []
    seg_starts: list[int] = []
    seg_pos = [m.start() for m in _SEG.finditer(text)]
    seg_idx = -1
    for start, attrs, inner in _trkpts(text):
        while seg_idx + 1 < len(seg_pos) and seg_pos[seg_idx + 1] < start:
            seg_idx += 1
            seg_starts.append(len(points))
        la, lo = _LAT.search(attrs), _LON.search(attrs)
        if not (la and lo):
            continue
        try:
            lat, lon = float(la.group(1)), float(lo.group(1))
        except ValueError:
            continue
        tm = _TIME.search(inner)
        t = parse_time(tm.group(1)) if tm else None
        ele = _ELE.search(inner)
        hr = _HR.search(inner)
        alt = None
        if ele:
            try:
                alt = float(ele.group(1))
            except ValueError:
                pass
        if t is None:
            untimed.append(TrackPoint(len(untimed) * UNTIMED_STEP_MS, lat, lon, alt, int(hr.group(1)) if hr else None, gps_alt=alt))
            continue
        points.append(TrackPoint(t, lat, lon, alt, int(hr.group(1)) if hr else None, gps_alt=alt))
    # timed points win: a file with a few untimed ones among them is a normal activity
    if not points and untimed:
        hrs = [q.hr for q in untimed if q.hr is not None]
        return Track(points=untimed, name=_name(text), untimed=True,
                     hr_avg=round(sum(hrs) / len(hrs)) if hrs else None, hr_max=max(hrs) if hrs else None)
    pauses = []
    for s in seg_starts[1:]:
        if 0 < s < len(points):
            a, b = points[s - 1].time, points[s].time
            if b > a:
                pauses.append(Pause(a, b))
    ordered = sorted(points, key=lambda q: q.time)
    hrs = [q.hr for q in ordered if q.hr is not None]
    return Track(
        points=ordered, pauses=pauses, name=_name(text),
        start_time=ordered[0].time if ordered else None,
        hr_avg=round(sum(hrs) / len(hrs)) if hrs else None, hr_max=max(hrs) if hrs else None,
    )
