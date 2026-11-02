"""Reader for the part of the Garmin FIT format an activity needs (see the Android app's Fit.kt)."""
from __future__ import annotations

import struct

from .track import Pause, Track, TrackPoint

_EPOCH_OFFSET = 631_065_600
_SEMICIRCLE = 180.0 / 2147483648.0

_MSG_FILE_ID, _MSG_SPORT, _MSG_SESSION, _MSG_RECORD, _MSG_EVENT = 0, 12, 18, 20, 21
_MSG_FIELD_DESCRIPTION = 206

_SIZES = {0: 1, 1: 1, 2: 1, 10: 1, 13: 1, 3: 2, 4: 2, 11: 2, 5: 4, 6: 4, 8: 4, 12: 4, 9: 8, 14: 8, 15: 8, 16: 8}
_SIGNED = {1, 3, 5, 14}
_INVALID = {0: 0xFF, 2: 0xFF, 13: 0xFF, 1: 0x7F, 3: 0x7FFF, 4: 0xFFFF, 5: 0x7FFFFFFF, 6: 0xFFFFFFFF,
            10: 0, 11: 0, 12: 0}


class FitError(Exception):
    pass


def looks_like_fit(data: bytes) -> bool:
    return len(data) >= 12 and data[8:12] == b".FIT"


def read(data: bytes) -> Track:
    if not looks_like_fit(data):
        raise FitError("Ce n'est pas un fichier FIT")
    points: list[TrackPoint] = []
    hrs: list[int] = []
    events: list[tuple[int, int]] = []
    sport = start = elapsed = timer = distance = manufacturer = None

    pos = 0
    while pos + 12 <= len(data) and looks_like_fit(data[pos:pos + 12]):
        header_size = data[pos]
        data_size = struct.unpack_from("<I", data, pos + 4)[0]
        p = pos + header_size
        end = min(len(data), p + data_size)
        defs: dict[int, tuple] = {}
        dev_names: dict[tuple[int, int], str] = {}
        last_ts = 0
        while p < end:
            header = data[p]
            p += 1
            if header & 0x80:  # compressed timestamp header
                local = (header >> 5) & 3
                if local not in defs:
                    raise FitError("Fichier FIT corrompu")
                offset = header & 0x1F
                ts = (last_ts & ~0x1F) + offset
                if offset < (last_ts & 0x1F):
                    ts += 32
                last_ts = ts
                big, glob, fields, dev, size = defs[local]
                if p + size > end:
                    break
                num, strs, devv = _fields(data, p, big, fields, dev)
                p += size
                num[253] = ts
                _handle(glob, num, strs, devv, points, hrs, events, dev_names)
                continue
            local = header & 0x0F
            if header & 0x40:  # definition
                has_dev = bool(header & 0x20)
                if p + 5 > end:
                    break
                big = data[p + 1] == 1
                glob = struct.unpack_from(">H" if big else "<H", data, p + 2)[0]
                count = data[p + 4]
                p += 5
                if p + count * 3 > end:
                    break
                fields = [(data[p + 3 * i], data[p + 3 * i + 1], data[p + 3 * i + 2]) for i in range(count)]
                p += count * 3
                dev = []
                if has_dev:
                    dc = data[p]
                    p += 1
                    dev = [(data[p + 3 * i], data[p + 3 * i + 1], data[p + 3 * i + 2]) for i in range(dc)]
                    p += dc * 3
                size = sum(f[1] for f in fields) + sum(d[1] for d in dev)
                defs[local] = (big, glob, fields, dev, size)
            else:
                if local not in defs:
                    raise FitError("Fichier FIT corrompu")
                big, glob, fields, dev, size = defs[local]
                if p + size > end:
                    break
                num, strs, devv = _fields(data, p, big, fields, dev)
                p += size
                if 253 in num:
                    last_ts = num[253]
                if _handle(glob, num, strs, devv, points, hrs, events, dev_names):
                    if glob == _MSG_SESSION:
                        sport = num.get(5, sport)
                        if 2 in num:
                            start = (num[2] + _EPOCH_OFFSET) * 1000
                        elapsed = num.get(7, elapsed)
                        timer = num.get(8, timer)
                        if 9 in num:
                            distance = num[9] / 100.0
                    elif glob == _MSG_SPORT and sport is None:
                        sport = num.get(0)
                    elif glob == _MSG_FILE_ID:
                        manufacturer = num.get(1, manufacturer)
        pos = end + 2

    pauses: list[Pause] = []
    stopped_at = None
    for t, kind in sorted(events):
        if kind in (1, 4, 8):
            if stopped_at is None:
                stopped_at = t
        elif kind == 0:
            if stopped_at is not None and t > stopped_at:
                pauses.append(Pause(stopped_at, t))
            stopped_at = None
    points.sort(key=lambda q: q.time)
    return Track(
        points=points, pauses=pauses, fit_sport=sport,
        start_time=start if start is not None else (points[0].time if points else None),
        session_elapsed_ms=elapsed, session_timer_ms=timer, session_distance=distance,
        hr_avg=round(sum(hrs) / len(hrs)) if hrs else None, hr_max=max(hrs) if hrs else None,
        manufacturer=manufacturer,
    )


def _fields(data: bytes, p: int, big: bool, fields, dev):
    num: dict[int, int] = {}
    strs: dict[int, str] = {}
    devv: dict[tuple[int, int], float] = {}
    order = ">" if big else "<"
    for number, size, btype in fields:
        base = btype & 0x1F
        if base == 7:
            raw = data[p:p + size].split(b"\x00", 1)[0]
            strs[number] = raw.decode("utf-8", "replace")
        else:
            width = _SIZES.get(base, 0)
            if 1 <= width <= 8 and size >= width:
                signed = base in _SIGNED
                value = int.from_bytes(data[p:p + width], "big" if big else "little", signed=signed)
                raw_unsigned = int.from_bytes(data[p:p + width], "big" if big else "little")
                if base in _INVALID and raw_unsigned == _INVALID[base]:
                    pass
                elif base in (14, 15, 16) and raw_unsigned == (1 << 64) - 1:
                    pass
                elif base in (8, 9):
                    pass  # floats are not used by the fields we read
                else:
                    num[number] = value
        p += size
    for number, size, idx in dev:
        if size == 4:
            devv[(idx, number)] = struct.unpack_from(order + "f", data, p)[0]
        p += size
    return num, strs, devv


def _handle(glob, num, strs, devv, points, hrs, events, dev_names) -> bool:
    """True for the summary messages the caller reads itself."""
    if glob == _MSG_RECORD:
        ts = num.get(253)
        if ts is None:
            return False
        if 3 in num:
            hrs.append(num[3])
        if 0 not in num or 1 not in num:
            return False
        if 78 in num:
            alt = num[78] / 5.0 - 500
        elif 2 in num:
            alt = num[2] / 5.0 - 500
        else:
            alt = None
        gps_alt = pressure = None
        for key, value in devv.items():
            if value != value:  # NaN
                continue
            name = dev_names.get(key)
            if name == "gps_altitude":
                gps_alt = float(value)
            elif name == "pressure":
                pressure = float(value)
        points.append(TrackPoint((ts + _EPOCH_OFFSET) * 1000, num[0] * _SEMICIRCLE, num[1] * _SEMICIRCLE,
                                 alt, num.get(3), gps_alt, pressure))
    elif glob == _MSG_EVENT:
        ts = num.get(253)
        if ts is not None and num.get(0) == 0 and 1 in num:
            events.append(((ts + _EPOCH_OFFSET) * 1000, num[1]))
    elif glob == _MSG_FIELD_DESCRIPTION:
        if 0 in num and 1 in num and 3 in strs:
            dev_names[(num[0], num[1])] = strs[3]
    elif glob in (_MSG_SESSION, _MSG_SPORT, _MSG_FILE_ID):
        return True
    return False
