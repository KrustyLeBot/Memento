"""The dated-entries index: everything the calendar shows in a window, and the alarms of the next hours.

Pure functions over what the app already holds in memory (events of `agenda/`, notes, activity notes,
contacts' birthdays, `calendars.json`); nothing is written, nothing derived is stored. Same rules as Android's
`Entries.kt`:

- EVENT: occurrences of the event files (`recurrence.expand`), colour and visibility of their calendar
  (unknown calendar id = the default calendar); second line = the location.
- NOTE: notes with `start` (expanded like events), or with `reminder` (a zero-length entry at that instant);
  colour of the Notes layer; second line = checklist progress "3/5".
- ACTIVITY: one timed entry from `start` lasting `elapsed`, shown on its start day only (`end_date` = start
  day); colour of the Activités layer (optional sport filter); second line "12,3 km · 1 h 05".
- BIRTHDAY: every year, all-day, "Anniversaire de <nom>", second line "(34 ans)" when the year is known
  (29 February -> 28 February on other years).
Sorted by start, all-day first at equal start, then title, then key.
"""
from __future__ import annotations

import math
import re
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta, timezone
from enum import Enum

from ..note import Note
from . import zones
from .calendars import LAYER_ACTIVITIES, LAYER_BIRTHDAYS, LAYER_NOTES, CalendarSettings
from .model import AgendaFormatError, Event, checklist_progress, event_from_note
from .recurrence import Occurrence, expand

UTC = timezone.utc
ALARM_HORIZON = timedelta(hours=48)
_MS = timedelta(milliseconds=1)


class EntryKind(Enum):
    EVENT = "event"
    NOTE = "note"
    ACTIVITY = "activity"
    BIRTHDAY = "birthday"


@dataclass
class Birthday:
    contact_id: str
    name: str        # display name ("Marie Dupont")
    birthday: str    # "1990-05-14" or "--05-14"
    path: str | None = None  # the contact file, to open it


@dataclass
class AgendaSources:
    events: list[Event] = field(default_factory=list)       # agenda/ files (AgendaStore.scan)
    notes: list[Note] = field(default_factory=list)         # regular notes: dated ones are picked here
    activities: list[Note] = field(default_factory=list)    # notes of activities/ (with .activity)
    birthdays: list[Birthday] = field(default_factory=list)
    settings: CalendarSettings = field(default_factory=CalendarSettings)


@dataclass
class Entry:
    kind: EntryKind
    key: str                 # stable: "<id>|<wall start>" (events, notes), "activity|<id>", "birthday|<id>|<year>"
    title: str
    subtitle: str | None     # second line
    color: str               # "#RRGGBB"
    all_day: bool
    start: datetime          # aware UTC (all-day: 00:00 of the first day, device zone)
    end: datetime            # aware UTC (all-day: 00:00 of the day after the last day)
    start_date: date         # device zone
    end_date: date           # last day shown, included
    target_id: str           # note / event / activity / contact id to open
    target_path: str | None  # its file, when known
    calendar_id: str | None = None   # events: the resolved calendar
    event: Event | None = None       # events and dated notes
    occurrence: Occurrence | None = None  # wall start/end in the event's zone ("14:30 à Paris")
    sport: str | None = None         # activities: sport key (the UI picks the emoji)
    recurring: bool = False


@dataclass
class EntriesResult:
    entries: list[Entry] = field(default_factory=list)
    problems: list[str] = field(default_factory=list)  # French, one line each: unreadable files, rules, truncations


@dataclass
class Alarm:
    key: str                  # "<id>|<wall start>|<offset minutes>" or "<id>|reminder|<ms>": stable across rescans
    at: datetime              # aware UTC
    kind: EntryKind
    title: str
    target_id: str
    target_path: str | None
    occurrence_start: datetime  # aware UTC
    all_day: bool
    offset_minutes: int
    location: str | None = None


def _local_date(instant: datetime, tz) -> date:
    return instant.astimezone(tz).date()


def _round_half_up(x: float) -> int:
    return math.floor(x + 0.5)


def activity_subtitle(distance_m: float, elapsed_s: int) -> str | None:
    """"12,3 km · 1 h 05" (distance with one decimal, duration "1 h 05" or "45 min")."""
    parts = []
    if distance_m and distance_m > 0:
        tenths = _round_half_up(distance_m / 100.0)
        parts.append(f"{tenths // 10},{tenths % 10} km")
    if elapsed_s and elapsed_s > 0:
        minutes = elapsed_s // 60
        parts.append(f"{minutes // 60} h {minutes % 60:02d}" if minutes >= 60 else f"{minutes} min")
    return " · ".join(parts) or None


_BIRTHDAY_RE = re.compile(r"^(?:(\d{4})-?|--)(\d{2})-?(\d{2})$")


def parse_birthday(text: str) -> tuple[int | None, int, int]:
    """(year or None, month, day) from "1990-05-14", "19900514", "--05-14" or "--0514". ValueError otherwise."""
    m = _BIRTHDAY_RE.match(text.strip())
    if not m:
        raise ValueError(text)
    year = int(m[1]) if m[1] else None
    month, day = int(m[2]), int(m[3])
    date(year or 2000, month, day)  # validates (2000 is a leap year: 29 February passes)
    return year, month, day


def _sort_key(e: Entry):
    return (e.start, 0 if e.all_day else 1, e.title, e.key)


def _overrides(events: list[Event]) -> dict[str, list[date]]:
    out: dict[str, list[date]] = {}
    for ev in events:
        if ev.recurrence_of and ev.recurrence_at is not None:
            out.setdefault(ev.recurrence_of, []).append(ev.recurrence_at)
    return out


def dated_notes(notes: list[Note]) -> tuple[list[Event], list[str]]:
    """The notes with a `start`, read as events (conflict copies left out), and the unreadable ones."""
    out, problems = [], []
    for n in notes:
        if n.conflict:
            continue
        try:
            ev = event_from_note(n)
        except AgendaFormatError as error:
            problems.append(str(error))
            continue
        if ev is not None:
            out.append(ev)
            problems.extend(f"{n.path} : {w}" for w in ev.warnings)
    return out, problems


def _occurrence_entry(kind: EntryKind, o: Occurrence, color: str, subtitle: str | None,
                      calendar_id: str | None, device_tz) -> Entry:
    ev = o.event
    if ev.all_day:
        start_date, end_date = o.start, o.end
    else:
        start_date = _local_date(o.start_instant, device_tz)
        end_date = max(start_date, _local_date(o.end_instant - _MS, device_tz))
    return Entry(kind, o.key, ev.title, subtitle, color, ev.all_day, o.start_instant, o.end_instant,
                 start_date, end_date, ev.id, ev.path or None, calendar_id, ev, o, None, bool(ev.rrule))


def build_entries(sources: AgendaSources, window_from: datetime, window_to: datetime, device_zone: str,
                  respect_visibility: bool = True) -> EntriesResult:
    """Every entry overlapping [window_from, window_to) (aware datetimes), sorted."""
    result = EntriesResult()
    device_tz = zones.zone(device_zone)
    settings = sources.settings
    overrides = _overrides(sources.events)

    def add_expansion(kind: EntryKind, ev: Event, color: str, subtitle: str | None, cal_id: str | None):
        exp = expand(ev, window_from, window_to, device_zone, overrides.get(ev.id))
        if exp.error:
            result.problems.append(f"{ev.path or ev.title} : {exp.error}")
        if exp.truncated:
            result.problems.append(f"{ev.path or ev.title} : trop d'occurrences, liste coupée")
        for o in exp.occurrences:
            result.entries.append(_occurrence_entry(kind, o, color, subtitle, cal_id, device_tz))

    for ev in sources.events:
        if ev.note.conflict:
            continue
        result.problems.extend(f"{ev.path} : {w}" for w in ev.warnings)
        cal = settings.calendar(ev.calendar)
        if respect_visibility and not cal.visible:
            continue
        add_expansion(EntryKind.EVENT, ev, cal.color, ev.location, cal.id)

    notes_layer = settings.layer(LAYER_NOTES)
    if notes_layer.visible or not respect_visibility:
        dated, problems = dated_notes(sources.notes)
        result.problems.extend(problems)
        for ev in dated:
            progress = checklist_progress(ev.body)
            add_expansion(EntryKind.NOTE, ev, notes_layer.color, f"{progress[0]}/{progress[1]}" if progress else None, None)
        dated_ids = {ev.id for ev in dated}
        for n in sources.notes:
            if n.conflict or n.reminder is None or n.id in dated_ids:
                continue
            at = datetime.fromtimestamp(n.reminder / 1000, UTC)
            if not (window_from <= at < window_to):
                continue
            progress = checklist_progress(n.body)
            d = _local_date(at, device_tz)
            result.entries.append(Entry(EntryKind.NOTE, f"{n.id}|reminder|{n.reminder}", n.title,
                                        f"{progress[0]}/{progress[1]}" if progress else None, notes_layer.color,
                                        False, at, at, d, d, n.id, n.path or None))

    acts = settings.layer(LAYER_ACTIVITIES)
    if acts.visible or not respect_visibility:
        for n in sources.activities:
            info = n.activity
            if info is None or n.conflict:
                continue
            if respect_visibility and acts.sports is not None and info.sport.key not in acts.sports:
                continue
            start = datetime.fromtimestamp(info.start / 1000, UTC)
            end = start + timedelta(seconds=max(0, info.elapsed))
            inside = (window_from <= start < window_to) if end == start else (start < window_to and end > window_from)
            if not inside:
                continue
            d = _local_date(start, device_tz)
            result.entries.append(Entry(EntryKind.ACTIVITY, f"activity|{n.id}", n.title,
                                        activity_subtitle(info.distance, info.elapsed), acts.color, False,
                                        start, end, d, d, n.id, n.path or None, sport=info.sport.key))

    bdays = settings.layer(LAYER_BIRTHDAYS)
    if bdays.visible or not respect_visibility:
        first = _local_date(window_from, device_tz)
        last = _local_date(window_to - _MS, device_tz)
        for b in sources.birthdays:
            try:
                year, month, day = parse_birthday(b.birthday)
            except ValueError:
                result.problems.append(f"{b.name} : anniversaire illisible ({b.birthday!r})")
                continue
            for y in range(first.year, last.year + 1):
                if year is not None and y < year:
                    continue
                try:
                    d = date(y, month, day)
                except ValueError:
                    d = date(y, 2, 28)
                s, e = zones.day_start(d, device_tz), zones.day_start(d + timedelta(days=1), device_tz)
                if not (s < window_to and e > window_from):
                    continue
                age = y - year if year is not None else 0
                subtitle = (f"({age} an)" if age == 1 else f"({age} ans)") if age >= 1 else None
                result.entries.append(Entry(EntryKind.BIRTHDAY, f"birthday|{b.contact_id}|{y}",
                                            f"Anniversaire de {b.name}", subtitle, bdays.color, True, s, e, d, d,
                                            b.contact_id, b.path))

    result.entries.sort(key=_sort_key)
    return result


def alarms(sources: AgendaSources, now: datetime, device_zone: str,
           horizon: timedelta = ALARM_HORIZON) -> list[Alarm]:
    """Alarms falling in [now, now + horizon): event and dated-note `alerts`, note `reminder`s; an all-day alert is
    a wall time of the device zone (00:00 of the day + offset), so "2 jours avant à 09:00" stays at 09:00 across a
    daylight-saving change. Calendar and layer visibility do not silence them (hiding is a view filter); cancelled
    events have none."""
    out: list[Alarm] = []
    end = now + horizon
    overrides = _overrides(sources.events)
    dated, _ = dated_notes(sources.notes)
    candidates = [(EntryKind.EVENT, ev) for ev in sources.events if not ev.note.conflict] + \
                 [(EntryKind.NOTE, ev) for ev in dated]
    for kind, ev in candidates:
        if not ev.alerts:
            continue
        lead = max([-a for a in ev.alerts if a < 0], default=0)
        lag = max([a for a in ev.alerts if a > 0], default=0)
        # all-day alerts are wall times: a day of slack covers a daylight-saving change in between
        slack = timedelta(days=1) if ev.all_day else timedelta(0)
        exp = expand(ev, now - timedelta(minutes=lag) - slack, end + timedelta(minutes=lead) + slack, device_zone,
                     overrides.get(ev.id))
        for o in exp.occurrences:
            for offset in sorted(set(ev.alerts)):
                if ev.all_day:
                    wall = datetime.combine(o.start, datetime.min.time()) + timedelta(minutes=offset)
                    at = zones.to_instant(wall, zones.zone(device_zone))
                else:
                    at = o.start_instant + timedelta(minutes=offset)
                if now <= at < end:
                    out.append(Alarm(f"{o.key}|{offset}", at, kind, ev.title, ev.id, ev.path or None,
                                     o.start_instant, ev.all_day, offset, ev.location))
    for n in sources.notes:
        if n.conflict or n.reminder is None:
            continue
        at = datetime.fromtimestamp(n.reminder / 1000, UTC)
        if now <= at < end:
            out.append(Alarm(f"{n.id}|reminder|{n.reminder}", at, EntryKind.NOTE, n.title, n.id, n.path or None,
                             at, False, 0))
    out.extend(_birthday_alarms(sources, now, end, device_zone))
    out.sort(key=lambda a: (a.at, a.key))
    return out


def _birthday_alarms(sources: AgendaSources, now: datetime, end: datetime, device_zone: str) -> list[Alarm]:
    """The reminders of every contact birthday (`birthday_alerts`, all-day offsets from 00:00 of the day); none when
    the Anniversaires layer is hidden (nobody wants a reminder of what the calendar does not show)."""
    alerts = sorted(set(sources.settings.birthday_alerts()))
    if not alerts or not sources.settings.layer(LAYER_BIRTHDAYS).visible:
        return []
    tz = zones.zone(device_zone)
    slack = timedelta(days=1)
    first = _local_date(now - timedelta(minutes=max(max(alerts), 0)) - slack, tz)
    last = _local_date(end + timedelta(minutes=max(-min(alerts), 0)) + slack, tz)
    out = []
    for b in sources.birthdays:
        try:
            year, month, day = parse_birthday(b.birthday)
        except ValueError:
            continue
        for y in range(first.year, last.year + 1):
            if year is not None and y < year:
                continue
            try:
                d = date(y, month, day)
            except ValueError:
                d = date(y, 2, 28)
            for offset in alerts:
                at = zones.to_instant(datetime.combine(d, datetime.min.time()) + timedelta(minutes=offset), tz)
                if now <= at < end:
                    out.append(Alarm(f"{b.contact_id}|{d.isoformat()}|{offset}", at, EntryKind.BIRTHDAY,
                                     f"Anniversaire de {b.name}", b.contact_id, b.path or None,
                                     zones.day_start(d, tz), True, offset))
    return out

