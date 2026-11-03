"""Reader for the part of the Garmin FIT format an activity needs (see the Android app's Fit.kt)."""
from __future__ import annotations

import struct
from operator import attrgetter

from .track import Pause, Track, TrackPoint

_EPOCH_OFFSET = 631_065_600
_SEMICIRCLE = 180.0 / 2147483648.0

_MSG_FILE_ID, _MSG_SPORT, _MSG_SESSION, _MSG_RECORD, _MSG_EVENT = 0, 12, 18, 20, 21
_MSG_FIELD_DESCRIPTION = 206

# The fields read, per message: anything else is skipped without being decoded (a 60-hour race is 800 000 messages)
_WANTED = {
    # 253 everywhere: any message's timestamp anchors the compressed-timestamp records that follow it
    _MSG_FILE_ID: {253, 1},
    _MSG_SPORT: {253, 0},
    _MSG_SESSION: {253, 2, 5, 7, 8, 9},
    _MSG_RECORD: {253, 0, 1, 2, 3, 78},
    _MSG_EVENT: {253, 0, 1},
}
_CODES = {0: "B", 1: "b", 2: "B", 10: "B", 13: "B", 3: "h", 4: "H", 11: "H", 5: "i", 6: "I", 12: "I",
          14: "q", 15: "q", 16: "Q"}
_SIZES = {0: 1, 1: 1, 2: 1, 10: 1, 13: 1, 3: 2, 4: 2, 11: 2, 5: 4, 6: 4, 8: 4, 12: 4, 9: 8, 14: 8, 15: 8, 16: 8}
_SIGNED = {1, 3, 5, 14}
_INVALID = {0: 0xFF, 2: 0xFF, 13: 0xFF, 1: 0x7F, 3: 0x7FFF, 4: 0xFFFF, 5: 0x7FFFFFFF, 6: 0xFFFFFFFF,
            10: 0, 11: 0, 12: 0}


class FitError(Exception):
    pass


def looks_like_fit(data: bytes) -> bool:
    return len(data) >= 12 and data[8:12] == b".FIT"


def read(data: bytes) -> Track:
    """The generic reader's track, faster: one table lookup per message, records decoded without a dict."""
    if not looks_like_fit(data):
        raise FitError("Ce n'est pas un fichier FIT")
    points: list[TrackPoint] = []
    hrs: list[int] = []
    events: list[tuple[int, int]] = []
    summary = dict.fromkeys(_SUMMARY)
    append = points.append

    pos = 0
    while pos + 12 <= len(data) and looks_like_fit(data[pos:pos + 12]):
        header_size = data[pos]
        data_size = struct.unpack_from("<I", data, pos + 4)[0]
        p = pos + header_size
        end = min(len(data), p + data_size)
        table: list[tuple | None] = [None] * 64  # data message header -> plan of its local message's definition
        dev_names: dict[tuple[int, int], str] = {}
        while p < end:
            header = data[p]
            p += 1
            plan = table[header] if header < 64 else None
            if plan is not None:
                kind, size, glob, payload = plan
                if p + size > end:
                    break
                if kind == _RECORD:
                    st, (its, vts), (ila, vla), (ilo, vlo), (ial, val), (iea, vea), (ihr, vhr) = payload
                    t = st.unpack_from(data, p)
                    p += size
                    if its is None or t[its] == vts:
                        continue
                    hr = None
                    if ihr is not None and t[ihr] != vhr:
                        hr = t[ihr]
                        hrs.append(hr)
                    if ila is None or ilo is None or t[ila] == vla or t[ilo] == vlo:
                        continue
                    if iea is not None and t[iea] != vea:
                        alt = t[iea] / 5.0 - 500
                    elif ial is not None and t[ial] != val:
                        alt = t[ial] / 5.0 - 500
                    else:
                        alt = None
                    append(TrackPoint((t[its] + _EPOCH_OFFSET) * 1000, t[ila] * _SEMICIRCLE, t[ilo] * _SEMICIRCLE,
                                      alt, hr, None, None))
                elif kind == _GENERIC:
                    big, fields, dev, compiled = payload
                    num, strs, devv = _decode(data, p, big, fields, dev, compiled)
                    p += size
                    if _handle(glob, num, strs, devv, points, hrs, events, dev_names):
                        _summary(glob, num, summary)
                else:
                    p += size
                continue
            if header & 0x80:
                return _read_generic(data)  # a compressed timestamp counts from the last message's: the full walk
            if not header & 0x40:
                raise FitError("Fichier FIT corrompu")
            local = header & 0x0F
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
            plan = _plan(big, glob, fields, dev, size, _compile(big, glob, fields, dev))
            for h in (local, local | 0x10, local | 0x20, local | 0x30):  # every data header naming this local message
                table[h] = plan
        pos = end + 2
    return _track(points, hrs, events, summary)


_SUMMARY = ("sport", "start", "elapsed", "timer", "distance", "manufacturer")
_SKIP, _RECORD, _GENERIC = 0, 1, 2


def _plan(big: bool, glob: int, fields, dev, size: int, compiled):
    """(kind, size, global number, payload) for the data messages of one definition."""
    if compiled is None and glob != _MSG_FIELD_DESCRIPTION:
        return (_SKIP, size, glob, None)
    if glob == _MSG_RECORD and compiled[2] == 0:
        st, keys, _floats = compiled
        numbers = [number for number, _base in keys]
        if len(set(numbers)) == len(numbers):  # a repeated field: the generic dict keeps its last valid value
            at = {number: (i, _unpacked_invalid(base)) for i, (number, base) in enumerate(keys)}
            return (_RECORD, size, glob, (st,) + tuple(at.get(n, (None, None)) for n in (253, 0, 1, 2, 78, 3)))
    return (_GENERIC, size, glob, (big, fields, dev, compiled))


def _unpacked_invalid(base: int) -> int:
    """A base type's invalid value as its struct code unpacks it (the 64-bit ones are all ones)."""
    raw = _INVALID[base] if base in _INVALID else (1 << 64) - 1
    return struct.unpack("<" + _CODES[base], raw.to_bytes(_SIZES[base], "little"))[0]


def _read_generic(data: bytes) -> Track:
    """Every message walked and decoded: the reader for compressed timestamps, and the reference for read."""
    if not looks_like_fit(data):
        raise FitError("Ce n'est pas un fichier FIT")
    points: list[TrackPoint] = []
    hrs: list[int] = []
    events: list[tuple[int, int]] = []
    summary = dict.fromkeys(_SUMMARY)

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
                big, glob, fields, dev, size, compiled = defs[local]
                if p + size > end:
                    break
                if compiled is not None or glob == _MSG_FIELD_DESCRIPTION:
                    num, strs, devv = _decode(data, p, big, fields, dev, compiled)
                    num[253] = ts
                    _handle(glob, num, strs, devv, points, hrs, events, dev_names)
                p += size
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
                defs[local] = (big, glob, fields, dev, size, _compile(big, glob, fields, dev))
            else:
                if local not in defs:
                    raise FitError("Fichier FIT corrompu")
                big, glob, fields, dev, size, compiled = defs[local]
                if p + size > end:
                    break
                if compiled is None and glob != _MSG_FIELD_DESCRIPTION:
                    # skipped, but its timestamp still anchors the compressed ones that follow
                    ts_at = _timestamp_offset(fields)
                    if ts_at is not None:
                        ts_value = struct.unpack_from(">I" if big else "<I", data, p + ts_at)[0]
                        if ts_value != 0xFFFFFFFF:
                            last_ts = ts_value
                    p += size
                    continue
                num, strs, devv = _decode(data, p, big, fields, dev, compiled)
                p += size
                if 253 in num:
                    last_ts = num[253]
                if _handle(glob, num, strs, devv, points, hrs, events, dev_names):
                    _summary(glob, num, summary)
        pos = end + 2
    return _track(points, hrs, events, summary)


def _summary(glob: int, num: dict[int, int], summary: dict) -> None:
    """Reads a session, sport or file_id message into the summary."""
    if glob == _MSG_SESSION:
        summary["sport"] = num.get(5, summary["sport"])
        if 2 in num:
            summary["start"] = (num[2] + _EPOCH_OFFSET) * 1000
        summary["elapsed"] = num.get(7, summary["elapsed"])
        summary["timer"] = num.get(8, summary["timer"])
        if 9 in num:
            summary["distance"] = num[9] / 100.0
    elif glob == _MSG_SPORT and summary["sport"] is None:
        summary["sport"] = num.get(0)
    elif glob == _MSG_FILE_ID:
        summary["manufacturer"] = num.get(1, summary["manufacturer"])


def _track(points: list[TrackPoint], hrs: list[int], events: list[tuple[int, int]], summary: dict) -> Track:
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
    points.sort(key=attrgetter("time"))
    start = summary["start"]
    return Track(
        points=points, pauses=pauses, fit_sport=summary["sport"],
        start_time=start if start is not None else (points[0].time if points else None),
        session_elapsed_ms=summary["elapsed"], session_timer_ms=summary["timer"],
        session_distance=summary["distance"],
        hr_avg=round(sum(hrs) / len(hrs)) if hrs else None, hr_max=max(hrs) if hrs else None,
        manufacturer=summary["manufacturer"],
    )


def _timestamp_offset(fields) -> int | None:
    at = 0
    for number, size, btype in fields:
        if number == 253 and size == 4 and (btype & 0x1F) in (6, 12):
            return at
        at += size
    return None


def _compile(big: bool, glob: int, fields, dev):
    """One struct for the wanted numeric fields of a definition, the others as padding; None: nothing wanted.
    Returns (struct, [(number, base type)], number of developer floats)."""
    wanted = _WANTED.get(glob)
    if wanted is None and glob != _MSG_RECORD:
        return None
    fmt = [">" if big else "<"]
    keys = []
    for number, size, btype in fields:
        base = btype & 0x1F
        code = _CODES.get(base)
        width = _SIZES.get(base, 0)
        if number in wanted and code is not None and size == width:
            fmt.append(code)
            keys.append((number, base))
        else:
            fmt.append(f"{size}x")
    floats = 0
    for number, size, idx in dev:
        if size == 4 and glob == _MSG_RECORD:
            fmt.append("f")
            floats += 1
        else:
            fmt.append(f"{size}x")
    return struct.Struct("".join(fmt)), keys, floats


def _decode(data: bytes, p: int, big: bool, fields, dev, compiled):
    if compiled is None:
        return _fields(data, p, big, fields, dev)  # the rare messages read whole (field descriptions)
    st, keys, floats = compiled
    values = st.unpack_from(data, p)
    num: dict[int, int] = {}
    for (number, base), value in zip(keys, values):
        invalid = _INVALID.get(base)
        if invalid is not None:
            if (value & ((1 << (8 * _SIZES[base])) - 1)) == invalid:
                continue
        elif base in (14, 15, 16) and (value & 0xFFFFFFFFFFFFFFFF) == 0xFFFFFFFFFFFFFFFF:
            continue
        num[number] = value
    devv: dict[tuple[int, int], float] = {}
    if floats:
        rest = values[len(keys):]
        i = 0
        for number, size, idx in dev:
            if size == 4:
                devv[(idx, number)] = rest[i]
                i += 1
    return num, {}, devv


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
