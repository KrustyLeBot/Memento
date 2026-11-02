"""Altitude for a track whose file has none (see Elevation.kt): terrain model lookups, kept in a `.alt` file."""
from __future__ import annotations

import bisect
import json
import time
import urllib.request
from dataclasses import replace

from .track import Track, TrackPoint, distance_between

BASE = "https://api.opentopodata.org/v1/srtm30m?locations="
PER_REQUEST = 100
STEP = 30.0
MAX_SAMPLES = 1500


class ElevationError(Exception):
    pass


def missing(track: Track) -> bool:
    return len(track.points) >= 2 and all(p.alt is None for p in track.points)


def pick(points: list[TrackPoint]) -> list[TrackPoint]:
    """One point about every 30 m (more spaced on very long tracks: 1500 at most)."""
    if not points:
        return []
    total = sum(distance_between(a, b) for a, b in zip(points, points[1:]))
    step = max(STEP, total / MAX_SAMPLES)
    out = [points[0]]
    last = points[0]
    for p in points:
        if distance_between(last, p) >= step:
            out.append(p)
            last = p
    if out[-1] is not points[-1]:
        out.append(points[-1])
    return out


def http_get(url: str) -> str:
    request = urllib.request.Request(url, headers={"User-Agent": "Memento (https://github.com/KrustyLeBot/Memento)"})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return response.read().decode("utf-8")
    except Exception as error:  # noqa: BLE001
        raise ElevationError(f"Service d'altitude indisponible : {error}") from error


def query(samples: list[TrackPoint], get=http_get, pause=lambda: time.sleep(1.1), progress=lambda n, total: None):
    """(time, altitude) pairs for the samples; the service allows one request per second."""
    out: list[tuple[int, float]] = []
    chunks = [samples[i:i + PER_REQUEST] for i in range(0, len(samples), PER_REQUEST)]
    for n, chunk in enumerate(chunks):
        if n:
            pause()
        progress(n, len(chunks))
        body = get(BASE + "|".join(f"{p.lat:.5f},{p.lon:.5f}" for p in chunk))
        try:
            data = json.loads(body)
            results = data["results"]
            ok = data.get("status") == "OK"
        except (ValueError, KeyError, TypeError) as error:
            raise ElevationError("Service d'altitude : réponse inattendue") from error
        if not ok or len(results) != len(chunk):
            raise ElevationError("Service d'altitude : réponse inattendue")
        for p, r in zip(chunk, results):
            if r.get("elevation") is not None:
                out.append((p.time, float(r["elevation"])))
    if len(out) < 2:
        raise ElevationError("Pas d'altitude connue pour cette zone")
    return out


def encode(alts: list[tuple[int, float]]) -> str:
    return "".join(f"{t},{a:.1f}\n" for t, a in alts)


def parse(text: str) -> list[tuple[int, float]]:
    out = []
    for line in text.splitlines():
        parts = line.split(",")
        try:
            out.append((int(parts[0]), float(parts[1])))
        except (ValueError, IndexError):
            continue
    return sorted(out)


def apply(track: Track, alts: list[tuple[int, float]]) -> Track:
    """The track with the altitudes interpolated (by time) onto every point that has none."""
    if not alts:
        return track
    times = [t for t, _ in alts]
    points = []
    for p in track.points:
        if p.alt is not None:
            points.append(p)
            continue
        i = bisect.bisect_right(times, p.time) - 1
        if i < 0:
            alt = alts[0][1]
        elif i >= len(alts) - 1:
            alt = alts[-1][1]
        else:
            (t0, a0), (t1, a1) = alts[i], alts[i + 1]
            alt = a0 + (a1 - a0) * (p.time - t0) / max(t1 - t0, 1)
        points.append(replace(p, alt=alt))
    return replace(track, points=points)
