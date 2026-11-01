"""Track model and geometry shared by the FIT/GPX readers and the analysis. Pure Python, no Qt."""
from __future__ import annotations

import math
from dataclasses import dataclass, field, replace
from enum import Enum


class Sport(Enum):
    WALK = ("walk", "Marche", True)
    BIKE = ("bike", "Vélo", True)
    EBIKE = ("ebike", "Vélo électrique", True)
    SKI = ("ski", "Ski", True)
    GPS = ("gps", "Autre (GPS)", True)
    PLAIN = ("plain", "Autre (sans GPS)", False)

    def __init__(self, key: str, label: str, has_gps: bool):
        self.key = key
        self.label = label
        self.has_gps = has_gps

    @staticmethod
    def of(key: str | None) -> "Sport":
        return next((s for s in Sport if s.key == key), Sport.GPS)

    @staticmethod
    def from_fit(code: int | None) -> "Sport":
        if code in (1, 11, 17, 16):  # running, walking, hiking, mountaineering
            return Sport.WALK
        if code == 2:  # cycling
            return Sport.BIKE
        if code == 21:  # e-biking
            return Sport.EBIKE
        if code in (12, 13, 14):  # cross-country, alpine, snowboarding
            return Sport.SKI
        return Sport.GPS


    @staticmethod
    def guess(fit_code: int | None) -> "Sport":
        """The sport the file declares; "generic" (some watches) or none stays "Autre": the user picks."""
        return Sport.GPS if fit_code in (None, 0) else Sport.from_fit(fit_code)


@dataclass(frozen=True)
class TrackPoint:
    time: int  # epoch millis
    lat: float
    lon: float
    alt: float | None = None
    hr: int | None = None
    gps_alt: float | None = None
    pressure: float | None = None


@dataclass(frozen=True)
class Pause:
    start: int
    end: int


@dataclass
class Track:
    points: list[TrackPoint]
    pauses: list[Pause] = field(default_factory=list)
    fit_sport: int | None = None
    name: str | None = None
    start_time: int | None = None
    session_elapsed_ms: int | None = None
    session_timer_ms: int | None = None
    session_distance: float | None = None
    hr_avg: int | None = None
    hr_max: int | None = None
    manufacturer: int | None = None

    def copy(self, **changes) -> "Track":
        return replace(self, **changes)


_R = 6371008.8


def distance(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = p2 - p1, math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * _R * math.asin(math.sqrt(a))


def distance_between(a: TrackPoint, b: TrackPoint) -> float:
    return distance(a.lat, a.lon, b.lat, b.lon)
