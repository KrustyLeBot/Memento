"""What the agenda screens show, kept in memory: an incremental scan of `agenda/`, the other sources (dated notes,
activities, birthdays), and the entries of a window of days, cached until something changes.

Only the visible window is expanded (`entries`); events that cannot touch it are filtered out before the
expansion, so a calendar of thousands of single events costs one list filter per navigation.
"""
from __future__ import annotations

import os
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta

from PySide6.QtCore import QTimeZone

from ..core import note as fmt
from ..core.agenda import zones
from ..core.agenda.calendars import CalendarSettings
from ..core.agenda.entries import (AgendaSources, Birthday, EntriesResult, Entry, EntryKind, _occurrence_entry,
                                   build_entries, dated_notes)
from ..core.agenda.model import AgendaFormatError, Event, has_start, stamp_date
from ..core.agenda.recurrence import expand
from ..core.note import Note

_CACHE_SIZE = 12


def device_zone() -> str:
    """The IANA zone of this PC (Qt converts the Windows zone name), UTC when it cannot be resolved."""
    raw = bytes(QTimeZone.systemTimeZoneId().data()).decode("ascii", "replace")
    if zones.is_valid(raw):
        return raw
    return zones.resolve_tzid(raw) or "UTC"


@dataclass
class AgendaPrefs:
    """Settings of this PC (QSettings); the synced ones live in calendars.json."""
    week_start: int = 0            # 0 = Monday, 6 = Sunday
    event_zone_times: bool = False  # show the event's own wall times
    default_alerts: list[int] = field(default_factory=list)
    view: str = "agenda"

    @staticmethod
    def from_config(config) -> "AgendaPrefs":
        return AgendaPrefs(getattr(config, "agenda_week_start", 0), getattr(config, "agenda_event_zone", False),
                           list(getattr(config, "agenda_default_alerts", []) or []), getattr(config, "agenda_view", "agenda"))


class AgendaData:
    def __init__(self):
        self.store = None
        self.zone = "UTC"
        self.sources = AgendaSources()
        self.problems: list[str] = []      # unreadable files, French, one line each
        self.version = 0                   # bumped whenever the sources change
        self._files: dict[str, tuple[tuple[int, int], Event | None, str | None]] = {}
        self._bounds: list[tuple[date, date, Event]] = []
        self._entries: dict[tuple, EntriesResult] = {}

    def reset(self, store) -> None:
        self.store = store
        self._files.clear()
        self._entries.clear()
        self.sources = AgendaSources()
        self.version += 1

    # ---- loading ----

    def _scan_events(self) -> tuple[list[Event], list[str]]:
        """Reads only the files whose time or size changed since the last scan."""
        agenda = self.store.agenda
        events, problems, seen = [], [], set()
        for path in agenda.paths():
            seen.add(path)
            try:
                st = os.stat(self.store.root / path)
                stamp = (st.st_mtime_ns, st.st_size)
            except OSError as error:
                problems.append(f"{path} : {error}")
                continue
            cached = self._files.get(path)
            if cached is None or cached[0] != stamp:
                try:
                    cached = (stamp, agenda.read(path), None)
                except AgendaFormatError as error:
                    cached = (stamp, None, str(error))
                except (OSError, UnicodeDecodeError) as error:
                    cached = (stamp, None, f"{path} : {error}")
                self._files[path] = cached
            if cached[1] is not None:
                events.append(cached[1])
            else:
                problems.append(cached[2])
        for stale in set(self._files) - seen:
            del self._files[stale]
        agenda.remember_ids({e.id for e in events})
        return events, problems

    def load(self, notes: list[Note] | None = None, activities: list[Note] | None = None,
             zone: str | None = None) -> bool:
        """Rescans; True when anything the agenda shows changed (the views then redraw)."""
        if self.store is None:
            return False
        events, problems = self._scan_events()
        settings = self.store.agenda.calendars()
        if settings.problem:
            problems.append(settings.problem)
        notes = self.store.scan() if notes is None else notes
        dated = [n for n in notes if not n.conflict and (n.reminder is not None or has_start(n))]
        acts = self.store.scan_activities() if activities is None else activities
        acts = [n for n in acts if n.activity is not None and not n.conflict]
        people = self.store.scan_contacts() if notes is None or not self.store.contacts.contacts else self.store.contacts.contacts
        birthdays = [Birthday(c.id, c.display_name, c.birthday.encode(), c.path)
                     for c in people if c.birthday and not c.conflict]
        sources = AgendaSources(events, dated, acts, birthdays, settings)
        zone = zone or device_zone()
        if sources == self.sources and zone == self.zone and problems == self.problems:
            return False
        self.sources, self.zone, self.problems = sources, zone, problems
        self._bounds = []
        for e in events:
            if e.rrule or e.recurrence_of:
                continue
            first = stamp_date(e.start) - timedelta(days=2)  # a zone can move an instant by more than a day
            last = stamp_date(e.end if e.end is not None else e.start) + timedelta(days=2)
            self._bounds.append((first, last, e))
        self._entries.clear()
        self.version += 1
        return True

    @property
    def settings(self) -> CalendarSettings:
        return self.sources.settings

    @property
    def tz(self):
        return zones.zone(self.zone)

    def today(self) -> date:
        return datetime.now(self.tz).date()

    def now(self) -> datetime:
        return datetime.now(self.tz)

    # ---- windows ----

    def entries(self, first: date, last: date) -> EntriesResult:
        """Entries overlapping the days [first, last] (device zone), sorted, visibility applied."""
        key = (first, last, self.version)
        hit = self._entries.get(key)
        if hit is not None:
            return hit
        events = [e for e in self.sources.events if e.rrule or e.recurrence_of]
        events += [e for lo, hi, e in self._bounds if lo <= last and hi >= first]
        window = AgendaSources(events, self.sources.notes, self.sources.activities, self.sources.birthdays,
                               self.sources.settings)
        tz = self.tz
        result = build_entries(window, zones.day_start(first, tz), zones.day_start(last + timedelta(days=1), tz),
                               self.zone)
        if len(self._entries) >= _CACHE_SIZE:
            self._entries.pop(next(iter(self._entries)))
        self._entries[key] = result
        return result

    def by_day(self, first: date, last: date) -> dict[date, list[Entry]]:
        """Every day of [first, last] with the entries touching it."""
        out: dict[date, list[Entry]] = {first + timedelta(days=i): [] for i in range((last - first).days + 1)}
        for e in self.entries(first, last).entries:
            d = max(e.start_date, first)
            while d <= min(e.end_date, last):
                out[d].append(e)
                d += timedelta(days=1)
        return out

    # ---- lookups ----

    def event(self, event_id: str) -> Event | None:
        return next((e for e in self.sources.events if e.id == event_id), None)

    def exceptions_of(self, series_id: str) -> list[Event]:
        return [e for e in self.sources.events if e.recurrence_of == series_id]

    def note(self, note_id: str) -> Note | None:
        return next((n for n in self.sources.notes if n.id == note_id), None)

    def activity(self, note_id: str) -> Note | None:
        return next((n for n in self.sources.activities if n.id == note_id), None)

    def taken_ids(self) -> set[str]:
        """Ids a new event must avoid (notes, activities)."""
        ids = {n.id for n in self.store.scan()} if self.store else set()
        return ids | {n.id for n in self.sources.activities}

    # ---- search ----

    def search(self, query: str, limit: int = 200) -> list[Entry]:
        """Events and dated notes whose title, location or description contain the words of the query: one
        entry each, the next occurrence from today (or the last past one), sorted by date."""
        words = fmt.fold(query).split()
        if not words:
            return []
        today = self.today()
        tz = self.tz
        hits: list[Entry] = []
        notes_layer = self.settings.layer("notes")
        candidates = [(EntryKind.EVENT, e) for e in self.sources.events if not e.note.conflict]
        candidates += [(EntryKind.NOTE, e) for e in dated_notes(self.sources.notes)[0]]
        for kind, ev in candidates:
            hay = fmt.fold(" ".join(x for x in (ev.title, ev.location or "", ev.body) if x))
            if not all(w in hay for w in words):
                continue
            entry = self._representative(kind, ev, today, tz, notes_layer.color)
            if entry is not None:
                hits.append(entry)
            if len(hits) >= limit:
                break
        future = sorted((h for h in hits if h.end_date >= today), key=lambda h: h.start)
        past = sorted((h for h in hits if h.end_date < today), key=lambda h: h.start, reverse=True)
        return future + past

    def _representative(self, kind: EntryKind, ev: Event, today: date, tz, color: str) -> Entry | None:
        overrides = [x.recurrence_at for x in self.exceptions_of(ev.id) if x.recurrence_at is not None]
        start = zones.day_start(today, tz)
        exp = expand(ev, start, start + timedelta(days=3 * 366), self.zone, overrides)
        if not exp.occurrences:
            exp = expand(ev, datetime(1900, 1, 1, tzinfo=zones.UTC), start, self.zone, overrides)
            if not exp.occurrences:
                return None
            exp.occurrences = exp.occurrences[-1:]
        o = exp.occurrences[0]
        cal = self.settings.calendar(ev.calendar)
        return _occurrence_entry(kind, o, cal.color if kind == EntryKind.EVENT else color,
                                 ev.location if kind == EntryKind.EVENT else None,
                                 cal.id if kind == EntryKind.EVENT else None, tz)

    def import_ids(self) -> list[tuple[str, int, list[str]]]:
        """(import id, number of files, calendar names) of the imports still present, newest first."""
        groups: dict[str, list[Event]] = {}
        for e in self.sources.events:
            if e.import_id:
                groups.setdefault(e.import_id, []).append(e)
        out = []
        for import_id, events in groups.items():
            names = sorted({self.settings.calendar(e.calendar).name for e in events}, key=fmt.fold)
            out.append((import_id, len(events), names))
        return sorted(out, key=lambda t: t[0], reverse=True)
