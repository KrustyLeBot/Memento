"""Data handed to the map page (web/map): track speeds for the speed colouring, and the 3D square rule, which
must stay identical to `squareOf` in web/map/map.js and to the Android host."""
from __future__ import annotations

import math

from .track import distance

MOVING = 0.3  # m/s: slower points do not count in the mean speed
SMOOTH = 4    # points on each side


def square_of(w: float, s: float, e: float, n: float) -> tuple[float, float, float, float]:
    """Square around a bbox: centred on it, side = its larger side + 25 %, at least 1 km."""
    lon_c, lat_c = (w + e) / 2, (s + n) / 2
    kx = 111320 * math.cos(math.radians(lat_c))
    half = max(max((e - w) * kx, (n - s) * 110540) / 2 * 1.25, 500.0)
    return (lon_c - half / kx, lat_c - half / 110540, lon_c + half / kx, lat_c + half / 110540)


def track_speeds(times_ms: list[int | None], lats: list[float], lons: list[float],
                 device: list[float | None] | None = None) -> tuple[list[float] | None, float | None]:
    """Speeds (m/s) smoothed over ±4 points, and the mean over moving points. A point's speed is the device's
    when given, else distance/time from the previous point. Without timestamps there is no speed: (None, None)."""
    n = len(lats)
    if n < 2:
        return None, None
    raw: list[float] = []
    for i in range(n):
        v = device[i] if device is not None else None
        if v is None:
            a, b = (i - 1, i) if i > 0 else (0, 1)
            ta, tb = times_ms[a], times_ms[b]
            if ta is None or tb is None:
                return None, None
            dt = (tb - ta) / 1000
            v = distance(lats[a], lons[a], lats[b], lons[b]) / dt if dt > 0 else (raw[-1] if raw else 0.0)
        raw.append(max(0.0, float(v)))
    smooth = []
    for i in range(n):
        window = raw[max(0, i - SMOOTH):i + SMOOTH + 1]
        smooth.append(sum(window) / len(window))
    moving = [v for v in smooth if v > MOVING]
    return smooth, (sum(moving) / len(moving) if moving else None)


def thin_indices(n: int, limit: int = 20000) -> list[int]:
    """Every point up to `limit`, else evenly spaced ones (first and last kept)."""
    if n <= limit:
        return list(range(n))
    return [i * (n - 1) // (limit - 1) for i in range(limit)]
