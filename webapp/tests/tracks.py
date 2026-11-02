"""Made-up GPX tracks for the activities tests and the screenshots: a walk around a valley and a ski day of lifts
and descents, both in the French Alps (so the IGN photo has something to show). Never real recordings."""
from __future__ import annotations

import math
from datetime import datetime, timezone

M = 111_320.0  # metres per degree of latitude (about)


def _gpx(points, name: str, segments_at=()) -> str:
    out = ['<?xml version="1.0" encoding="UTF-8"?>',
           '<gpx version="1.1" creator="tests" xmlns="http://www.topografix.com/GPX/1/1" '
           'xmlns:gpxtpx="http://www.garmin.com/xmlschemas/TrackPointExtension/v1">',
           f"<trk><name>{name}</name><trkseg>"]
    for i, (t, lat, lon, alt, hr) in enumerate(points):
        if i in segments_at:
            out.append("</trkseg><trkseg>")
        stamp = datetime.fromtimestamp(t / 1000, timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
        ele = f"<ele>{alt:.1f}</ele>" if alt is not None else ""
        ext = (f"<extensions><gpxtpx:TrackPointExtension><gpxtpx:hr>{hr}</gpxtpx:hr></gpxtpx:TrackPointExtension>"
               f"</extensions>") if hr is not None else ""
        out.append(f'<trkpt lat="{lat:.7f}" lon="{lon:.7f}">{ele}<time>{stamp}</time>{ext}</trkpt>')
    out.append("</trkseg></trk></gpx>")
    return "\n".join(out)


def walk(start_ms: int, seconds: int = 9000, altitude: bool = True, name: str = "Tour du lac",
         lat0: float = 45.9235, lon0: float = 6.8700) -> str:
    """A loop of about 1.3 m/s with two stops of 2 minutes and a manual pause (a second segment)."""
    kx = M * math.cos(math.radians(lat0))
    points, t, theta, along = [], start_ms, 0.0, 0.0
    radius = 1.25 * seconds / (2 * math.pi)  # about one loop
    pause_at = None
    for i in range(seconds):
        stopped = seconds // 3 <= i < seconds // 3 + 120 or 2 * seconds // 3 <= i < 2 * seconds // 3 + 120
        speed = 0.0 if stopped else 1.35 + 0.3 * math.sin(i / 400) + 0.12 * math.sin(i / 47)
        along += speed
        theta = along / radius
        wiggle = 25 * math.sin(along / 90)
        r = radius + wiggle
        lat = lat0 + (r * math.sin(theta)) / M
        lon = lon0 + (r * (1 - math.cos(theta))) / kx
        alt = 1040 + 260 * math.sin(theta / 2) ** 2 + 4 * math.sin(along / 37) if altitude else None
        hr = int(105 + 25 * math.sin(theta / 2) ** 2 + (0 if stopped else 8))
        if i == seconds // 2:
            t += 300_000  # a manual pause: the watch's timer stopped for 5 minutes
            pause_at = i
        points.append((t, lat, lon, alt, hr))
        t += 1000
    return _gpx(points, name, segments_at=(pause_at,))


def ski(start_ms: int, laps: int = 6, name: str = "Journée de ski", lat0: float = 45.2980, lon0: float = 6.5800) -> str:
    """Lifts straight up the slope (about 4.5 m/s, +1.5 m/s of altitude), descents winding back down (about 11 m/s),
    a short wait at the bottom; each lap a little to the side of the previous one."""
    kx = M * math.cos(math.radians(lat0))
    points, t = [], start_ms
    for lap in range(laps):
        side = (lap % 3 - 1) * 450.0  # metres east of the first lift
        base = (lat0, lon0 + side / kx, 1850.0)
        top_n, top_e, top_alt = 1700.0, 700.0 + side, 2450.0
        # wait at the bottom
        for _ in range(60):
            points.append((t, base[0], base[1], base[2], 95))
            t += 1000
        # lift: straight line
        lift = 400
        for k in range(lift):
            f = (k + 1) / lift
            n, e = top_n * f, side + (top_e - side) * f
            points.append((t, lat0 + n / M, lon0 + e / kx, base[2] + (top_alt - base[2]) * f, 92))
            t += 1000
        # a short stop at the top
        for _ in range(40):
            points.append((t, lat0 + top_n / M, lon0 + top_e / kx, top_alt, 90))
            t += 1000
        # descent: winding back to the base
        down = 200
        for k in range(down):
            f = (k + 1) / down + 0.025 * math.sin((k + 1) / 11)  # speeding up and slowing down
            n = top_n * (1 - f)
            e = top_e + (side - top_e) * f + 140 * math.sin(f * math.pi * 5)
            points.append((t, lat0 + n / M, lon0 + e / kx, top_alt + (base[2] - top_alt) * f, 140))
            t += 1000
    return _gpx(points, name)
