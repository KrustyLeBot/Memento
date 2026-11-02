"""Occurrences of an event in a window [from, to) (README, "Agenda", recurrence).

Rules, identical on Android (`Recurrence.kt`):
- A series is expanded in wall time of its zone (`tz`, or the device zone when floating) by the RRULE library
  (`dateutil.rrule` here, biweekly on Android); each wall start is then turned into an instant (zones.to_instant),
  so a weekly 14:30 stays 14:30 across daylight saving.
- DTSTART is always the first occurrence and counts toward COUNT (RFC 5545 3.3.10), even when it does not match
  the rule. COUNT and UNTIL are applied here, not by the library: UNTIL may be a date (compared to the date),
  a local date-time (compared in wall time) or UTC `...Z` (compared as an instant).
- EXDATE and the `recurrence_at` of exception files remove occurrences: an exact stamp; a date removes the day of
  an all-day series, and the occurrence at the series' start time that day of a timed one (as exported).
- Timed occurrences last the exact duration DTEND - DTSTART of the first one (RFC 5545), DTEND being a wall time
  of `end_tz` when set (the occurrence's end wall time is then given in that zone). All-day ones are dates
  of the device zone: [first day 00:00, day after the last day 00:00).
- An occurrence is in the window when it overlaps it; a zero-length one when from <= start < to.
- At most MAX_OCCURRENCES occurrences per window and MAX_ITERATIONS rule steps: beyond, `truncated`.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta, timezone
from typing import Iterator

from dateutil.rrule import rrulestr

from . import zones
from .model import Event, format_stamp, is_all_day, stamp_date

MAX_OCCURRENCES = 10_000
MAX_ITERATIONS = 1_000_000
UTC = timezone.utc


@dataclass
class Occurrence:
    event: Event
    start: date              # wall stamp in the event's zone (a date for all-day)
    end: date | None         # wall end (all-day: last day included); None for a zero-length timed event
    start_instant: datetime  # aware UTC
    end_instant: datetime    # aware UTC; all-day: 00:00 (device zone) of the day after the last day

    @property
    def key(self) -> str:
        """Stable id of the occurrence: event id + its wall start."""
        return f"{self.event.id}|{format_stamp(self.start)}"


@dataclass
class Expansion:
    occurrences: list[Occurrence] = field(default_factory=list)
    truncated: bool = False
    error: str | None = None  # unreadable rule (French); the event then shows as a single occurrence


@dataclass
class Rule:
    body: str              # the RRULE without COUNT and UNTIL, upper case
    count: int | None
    until: date | None     # date, naive datetime, or aware UTC datetime


_UNTIL_RE = re.compile(r"^(\d{4})(\d{2})(\d{2})(?:T(\d{2})(\d{2})(\d{2})(Z?))?$")


def parse_rule(rrule: str) -> Rule:
    text = rrule.strip()
    if text.upper().startswith("RRULE:"):
        text = text[6:]
    parts, count, until = [], None, None
    for part in text.split(";"):
        if not part.strip():
            continue
        key, _, value = part.partition("=")
        key, value = key.strip().upper(), value.strip().upper()
        if key == "COUNT":
            count = int(value)
            if count < 1:
                raise ValueError("COUNT < 1")
        elif key == "UNTIL":
            m = _UNTIL_RE.match(value)
            if not m:
                raise ValueError(f"UNTIL illisible : {value}")
            if m[4] is None:
                until = date(int(m[1]), int(m[2]), int(m[3]))
            else:
                until = datetime(int(m[1]), int(m[2]), int(m[3]), int(m[4]), int(m[5]), int(m[6]),
                                 tzinfo=UTC if m[7] else None)
        else:
            parts.append(f"{key}={value}")
    if not any(p.startswith("FREQ=") for p in parts):
        raise ValueError("FREQ manquant")
    return Rule(";".join(parts), count, until)


def _as_datetime(d: date) -> datetime:
    return d if isinstance(d, datetime) else datetime(d.year, d.month, d.day)


def _within_until(rule: Rule, stamp: date, all_day: bool, tz, device_tz) -> bool:
    u = rule.until
    if u is None:
        return True
    if not isinstance(u, datetime):
        return stamp_date(stamp) <= u
    if u.tzinfo is None:
        return stamp_date(stamp) <= u.date() if all_day else stamp <= u
    if all_day:
        return stamp_date(stamp) <= zones.to_wall(u, device_tz).date()
    return zones.to_instant(stamp, tz) <= u


def series_starts(event: Event, device_zone: str) -> Iterator[date]:
    """Wall starts of the series, in order (dates for all-day), COUNT and UNTIL applied, EXDATE not.
    Raises ValueError for an unreadable rule. Stops after MAX_ITERATIONS steps (then raises StopIteration
    with no marker: `expand` counts the steps itself)."""
    rule = parse_rule(event.rrule or "")
    all_day = event.all_day
    tz = zones.zone_or(event.tz, device_zone)
    device_tz = zones.zone(device_zone)
    dtstart = _as_datetime(event.start)
    generated = iter(rrulestr(rule.body, dtstart=dtstart, cache=False))
    emitted = 0

    def wrap(value: datetime) -> date:
        return value.date() if all_day else value

    first = next(generated, None)
    if first != dtstart:
        candidates = [dtstart] + ([first] if first is not None else [])
    else:
        candidates = [first]
    for value in candidates:
        stamp = wrap(value)
        if rule.count is not None and emitted >= rule.count or not _within_until(rule, stamp, all_day, tz, device_tz):
            return
        emitted += 1
        yield stamp
    for value in generated:
        stamp = wrap(value)
        if rule.count is not None and emitted >= rule.count or not _within_until(rule, stamp, all_day, tz, device_tz):
            return
        emitted += 1
        yield stamp


def _excluded(stamp: date, exact: set, days: set) -> bool:
    if isinstance(stamp, datetime):
        return stamp in exact or stamp.date() in days
    return stamp in days


def expand(event: Event, window_from: datetime, window_to: datetime, device_zone: str,
           overridden: list[date] | None = None) -> Expansion:
    """Occurrences of `event` overlapping [window_from, window_to) (aware datetimes). `overridden`: the
    `recurrence_at` of the series' exception files, removed like EXDATE (a sync may bring an exception
    before the series' updated exdate)."""
    out = Expansion()
    if event.cancelled:
        return out
    device_tz = zones.zone(device_zone)
    tz = zones.zone_or(event.tz, device_zone)
    all_day = event.all_day

    if all_day:
        days = (event.end - event.start).days if event.end is not None else 0

        def make(stamp: date) -> Occurrence:
            end_day = stamp + timedelta(days=days)
            return Occurrence(event, stamp, end_day, zones.day_start(stamp, device_tz),
                              zones.day_start(end_day + timedelta(days=1), device_tz))
    else:
        # with `end_tz` the end is a wall time of another zone: the duration is the instant difference of the
        # first occurrence, applied to every occurrence's start (RFC 5545, DTEND with its own TZID)
        end_tz = zones.end_zone_or(event, device_zone)
        first_start = zones.to_instant(event.start, tz)
        duration = zones.to_instant(event.end, end_tz) - first_start if event.end is not None else timedelta(0)

        def make(stamp: date) -> Occurrence:
            s = zones.to_instant(stamp, tz)
            e = s + duration
            return Occurrence(event, stamp, zones.to_wall(e, end_tz) if event.end is not None else None, s, e)

    def inside(o: Occurrence) -> bool:
        if o.end_instant == o.start_instant:
            return window_from <= o.start_instant < window_to
        return o.start_instant < window_to and o.end_instant > window_from

    if not event.rrule:
        o = make(event.start)
        if inside(o):
            out.occurrences.append(o)
        return out

    removed = list(event.exdate) + list(overridden or [])
    if all_day:
        exact: set = set()
        day_set = {x.date() if isinstance(x, datetime) else x for x in removed}
    else:
        exact = {x if isinstance(x, datetime) else datetime.combine(x, event.start.time()) for x in removed}
        day_set = set()
    try:
        steps = 0
        for stamp in series_starts(event, device_zone):
            steps += 1
            if steps > MAX_ITERATIONS:
                out.truncated = True
                break
            o = make(stamp)
            if o.start_instant >= window_to:
                break
            if _excluded(stamp, exact, day_set) or not inside(o):
                continue
            if len(out.occurrences) >= MAX_OCCURRENCES:
                out.truncated = True
                break
            out.occurrences.append(o)
    except (ValueError, TypeError, OverflowError) as error:
        out.occurrences = []
        out.error = f"règle de répétition illisible ({event.rrule}) : {error}"
        o = make(event.start)
        if inside(o):
            out.occurrences.append(o)
    return out
