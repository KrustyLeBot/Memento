"""What the agenda shows, kept in memory between requests: an incremental scan of `agenda/` (only the files whose
time or size changed are read again), the other sources (dated notes, activities, birthdays), and the entries of the
last windows asked for. Same approach as the Windows screen (ui/agenda_data.py); every rule (format, recurrence,
entries) is the core's.

The recurrence and entry modules pull in dateutil: they are imported on first use (the warm-up at startup, see
warm.py). The index stays resident; the idle trim (`trim()`) forgets only the windows' entries.

Every window inside the "wide" one (today - WIDE_BEFORE … today + WIDE_AFTER, built in the background at warm-up and
after each change) is cut out of it instead of being expanded again: an entry belongs to a window by the same
overlap rule as the core's (recurrence.expand), so the cut is exactly what build_entries would return.
"""
from __future__ import annotations

import os
from datetime import date, datetime, timedelta

from ..core import fmt
from ..errors import bad_request

CACHE_SIZE = 8
MAX_DAYS = 400  # widest window one request may ask for
WIDE_BEFORE = timedelta(days=100)  # month grid of 3 months back (it starts up to 6 days before the 1st)
WIDE_AFTER = timedelta(days=140)   # month grid of 3 months ahead, and the list view's first chunks
# Problems of an expansion depend on the window (a rule failing or truncated further on): with one of them in the
# wide window, the cut could differ from a direct build, so windows are built directly.
WINDOW_PROBLEMS = ("règle de répétition illisible", "trop d'occurrences, liste coupée")


def core():
    """The agenda core, imported on first use (dateutil is a few MB the idle server does not need)."""
    from memento_win.core.agenda import calendars, entries, frdates, links, model, recurrence, rules, zones
    return calendars, entries, frdates, links, model, recurrence, rules, zones


def default_zone() -> str:
    """The container's zone (TZ), the best guess of the browser's before its first request."""
    from memento_win.core.agenda import zones
    name = os.environ.get("TZ", "").lstrip(":")
    return name if name and zones.is_valid(name) else "UTC"


def scan_files(agenda, root, files: dict):
    """The events of agenda/, reading only the files whose time or size changed since `files` (path ->
    ((mtime_ns, size), Event | None, problem | None), updated in place). Needs no lock when `files` is private."""
    from memento_win.core.agenda.model import AgendaFormatError
    base = str(root)
    events, problems, seen = [], [], set()
    for path in agenda.paths():
        seen.add(path)
        try:
            st = os.stat(f"{base}/{path}")
        except OSError as error:
            problems.append(f"{path} : {error}")
            continue
        stamp = (st.st_mtime_ns, st.st_size)
        cached = files.get(path)
        if cached is None or cached[0] != stamp:
            try:
                cached = (stamp, agenda.read(path), None)
            except AgendaFormatError as error:
                cached = (stamp, None, str(error))
            except (OSError, UnicodeDecodeError) as error:
                cached = (stamp, None, f"{path} : {error}")
            files[path] = cached
        if cached[1] is not None:
            events.append(cached[1])
        else:
            problems.append(cached[2])
    for stale in set(files) - seen:
        del files[stale]
    return events, problems


def overlaps(e, lo: datetime, hi: datetime) -> bool:
    """recurrence.expand's rule: a zero-length entry when lo <= start < hi, any other when it overlaps."""
    if e.end == e.start:
        return lo <= e.start < hi
    return e.start < hi and e.end > lo


def zone_of(raw: str | None) -> str:
    """The browser's IANA zone (`tz` query parameter); UTC when missing or unknown."""
    from memento_win.core.agenda import zones
    return raw if raw and zones.is_valid(raw) else "UTC"


def parse_day(raw: str | None, what: str) -> date:
    try:
        return date.fromisoformat(raw or "")
    except ValueError:
        raise bad_request(f"Date invalide ({what})") from None


def ms(instant: datetime) -> int:
    return int(instant.timestamp() * 1000)


def entry_json(e) -> dict:
    """One entry as the views draw it: instants in ms (the browser shows them in its zone), the occurrence's own wall
    times for "heures du fuseau de l'événement", and what a click needs."""
    _, _, frdates, _, model, _, _, _ = core()
    ev, o = e.event, e.occurrence
    out = {
        "kind": e.kind.value, "key": e.key, "title": e.title, "subtitle": e.subtitle, "color": e.color,
        "all_day": e.all_day, "start": ms(e.start), "end": ms(e.end), "start_date": e.start_date.isoformat(),
        "end_date": e.end_date.isoformat(), "target_id": e.target_id, "target_path": e.target_path,
        "calendar_id": e.calendar_id, "recurring": e.recurring, "sport": e.sport,
    }
    if o is not None:
        out["occurrence"] = {"start": model.format_stamp(o.start),
                             "end": model.format_stamp(o.end) if o.end is not None else None}
    if ev is not None:
        out.update(tz=ev.tz, end_tz=ev.end_tz, location=ev.location, tentative=ev.status == "tentative",
                   exception=bool(ev.recurrence_of))
        if o is not None and ev.end_tz:
            out["flight"] = frdates.departure_arrival(o.start, o.end, ev.tz, ev.end_tz)
    return out


def build(sources, bounds, first: date, last: date, zone: str):
    """The entries of the days [first, last] of `zone` (sources and bounds are never changed in place: a load
    replaces them, so this may run without the lock)."""
    _, entries, _, _, _, _, _, zones = core()
    events = [e for e in sources.events if e.rrule or e.recurrence_of]
    events += [e for lo, hi, e in bounds if lo <= last and hi >= first]
    window = entries.AgendaSources(events, sources.notes, sources.activities, sources.birthdays, sources.settings)
    tz = zones.zone(zone)
    return entries.build_entries(window, zones.day_start(first, tz), zones.day_start(last + timedelta(days=1), tz), zone)


class AgendaIndex:
    def __init__(self, data):
        self.data = data
        self.drop()

    def drop(self) -> None:
        """Forgets everything (under the data lock): the next request rescans."""
        self._files: dict = {}  # path -> ((mtime_ns, size), Event | None, problem | None)
        self._bounds: list = []
        self._entries: dict = {}
        self._wide = None  # (zone, version, first day, last day, EntriesResult)
        self._wide_tried = None  # (zone, version, first day, last day) of the last one built, usable or not
        self.sources = None
        self.problems: list[str] = []
        self.version = 0
        self.zone = default_zone()  # the zone of the last window asked for: the wide window's

    def trim(self) -> None:
        """Idle hook: the windows' entries go, the index and the wide window stay."""
        self._entries.clear()

    def files(self) -> dict:
        return dict(self._files)

    def seed(self, files: dict) -> None:
        """Entries read elsewhere (a background scan, a saved index); the scans read again any whose file has
        another time or size by then."""
        for path, value in files.items():
            cached = self._files.get(path)
            if cached is None or cached[0] != value[0]:
                self._files[path] = value

    # ---- loading (under the data lock) ----

    def _scan_events(self):
        agenda = self.data.store.agenda
        events, problems = scan_files(agenda, self.data.root, self._files)
        agenda.remember_ids({e.id for e in events})
        return events, problems

    def load(self):
        """Rescans (changed files only) and returns the sources; the entry cache survives when nothing changed."""
        calendars, entries, _, _, model, _, _, _ = core()
        events, problems = self._scan_events()
        store = self.data.store
        settings = store.agenda.calendars()
        if settings.problem:
            problems.append(settings.problem)
        notes = [n for n in store.scan() if not n.conflict and (n.reminder is not None or model.has_start(n))]
        acts = [n for n in store.scan_activities() if n.activity is not None and not n.conflict]
        birthdays = [entries.Birthday(c.id, c.display_name, c.birthday.encode(), c.path)
                     for c in store.scan_contacts() if c.birthday and not c.conflict]
        sources = entries.AgendaSources(events, notes, acts, birthdays, settings)
        if sources == self.sources and problems == self.problems:
            return self.sources
        self.sources, self.problems = sources, problems
        self._bounds = []
        for e in events:
            if e.rrule or e.recurrence_of:
                continue
            first = model.stamp_date(e.start) - timedelta(days=2)  # a zone can move an instant by more than a day
            last = model.stamp_date(e.end if e.end is not None else e.start) + timedelta(days=2)
            self._bounds.append((first, last, e))
        self._entries.clear()
        self._wide = None
        self.version += 1
        return sources

    # ---- queries (under the data lock, after load) ----

    def entries(self, first: date, last: date, zone: str):
        """Entries overlapping the days [first, last] of `zone`, visibility applied (cached)."""
        _, entries, _, _, _, _, _, zones = core()
        if last < first or (last - first).days > MAX_DAYS:
            raise bad_request("Période invalide")
        self.zone = zone
        key = (first, last, zone, self.version)
        hit = self._entries.get(key)
        if hit is not None:
            return hit
        wide = self._wide
        if wide is not None and wide[:2] == (zone, self.version) and wide[2] <= first and last <= wide[3]:
            tz = zones.zone(zone)
            lo, hi = zones.day_start(first, tz), zones.day_start(last + timedelta(days=1), tz)
            return entries.EntriesResult([e for e in wide[4].entries if overlaps(e, lo, hi)], list(wide[4].problems))
        result = self._build(first, last, zone)
        if len(self._entries) >= CACHE_SIZE:
            self._entries.pop(next(iter(self._entries)))
        self._entries[key] = result
        return result

    def prepare_wide(self, today: date | None = None) -> None:
        """Builds the wide window now (under the lock); the warmer uses wide_job() to build it outside the lock."""
        job = self.wide_job(today)
        if job is not None:
            self.install_wide(job())

    def wide_job(self, today: date | None = None):
        """(Under the lock, after load) A function building the wide window around today in the zone of the last
        request from the sources as they are now, to run without the lock; None when it is already there."""
        _, _, _, _, _, _, _, zones = core()
        zone, version, sources, bounds = self.zone, self.version, self.sources, self._bounds
        today = today or datetime.now(zones.zone(zone)).date()
        first, last = today - WIDE_BEFORE, today + WIDE_AFTER
        if sources is None or self._wide_tried == (zone, version, first, last):
            return None
        return lambda: (zone, version, first, last, build(sources, bounds, first, last, zone))

    def install_wide(self, wide) -> None:
        """(Under the lock) Keeps a window built by wide_job's function, unless the sources changed meanwhile."""
        if wide[1] != self.version:
            return
        self._wide_tried = wide[:4]
        usable = not any(marker in p for p in wide[4].problems for marker in WINDOW_PROBLEMS)
        self._wide = wide if usable else None

    def wants_wide(self) -> bool:
        """True when no wide window was built (or found unusable) for the current zone and sources."""
        tried = self._wide_tried
        return tried is None or tried[:2] != (self.zone, self.version)

    def _build(self, first: date, last: date, zone: str):
        return build(self.sources, self._bounds, first, last, zone)

    def event(self, event_id: str):
        return next((e for e in self.sources.events if e.id == event_id), None)

    def exceptions_of(self, series_id: str) -> list:
        return [e for e in self.sources.events if e.recurrence_of == series_id]

    def taken_ids(self) -> set[str]:
        """Ids a new event must avoid (notes, activities)."""
        return {n.id for n in self.data.store.scan()} | {n.id for n in self.sources.activities}

    def search(self, query: str, zone: str, limit: int = 200) -> list:
        """Events and dated notes whose title, location or description hold every word: one entry each, the next
        occurrence from today (else the last past one); upcoming first, then the past, newest first (as on Windows)."""
        _, entries, _, _, _, recurrence, _, zones = core()
        words = fmt.fold(query).split()
        if not words:
            return []
        tz = zones.zone(zone)
        today = datetime.now(tz).date()
        s = self.sources
        notes_color = s.settings.layer("notes").color
        candidates = [(entries.EntryKind.EVENT, e) for e in s.events if not e.note.conflict]
        candidates += [(entries.EntryKind.NOTE, e) for e in entries.dated_notes(s.notes)[0]]
        hits = []
        for kind, ev in candidates:
            hay = fmt.fold(" ".join(x for x in (ev.title, ev.location or "", ev.body) if x))
            if not all(w in hay for w in words):
                continue
            overrides = [x.recurrence_at for x in self.exceptions_of(ev.id) if x.recurrence_at is not None]
            start = zones.day_start(today, tz)
            exp = recurrence.expand(ev, start, start + timedelta(days=3 * 366), zone, overrides)
            if not exp.occurrences:
                exp = recurrence.expand(ev, datetime(1900, 1, 1, tzinfo=zones.UTC), start, zone, overrides)
                if not exp.occurrences:
                    continue
                exp.occurrences = exp.occurrences[-1:]
            cal = s.settings.calendar(ev.calendar)
            is_event = kind == entries.EntryKind.EVENT
            hits.append(entries._occurrence_entry(kind, exp.occurrences[0], cal.color if is_event else notes_color,
                                                  ev.location if is_event else None, cal.id if is_event else None, tz))
            if len(hits) >= limit:
                break
        future = sorted((h for h in hits if h.end_date >= today), key=lambda h: h.start)
        past = sorted((h for h in hits if h.end_date < today), key=lambda h: h.start, reverse=True)
        return future + past

    def imports(self) -> list[dict]:
        """The imports whose files are still there, newest first."""
        groups: dict[str, list] = {}
        for e in self.sources.events:
            if e.import_id:
                groups.setdefault(e.import_id, []).append(e)
        settings = self.sources.settings
        out = []
        for import_id, events in groups.items():
            names = sorted({settings.calendar(e.calendar).name for e in events}, key=fmt.fold)
            out.append({"id": import_id, "files": len(events), "calendars": names, "date": import_date(import_id)})
        return sorted(out, key=lambda d: d["id"], reverse=True)


def import_date(import_id: str) -> str:
    """"20261102T101500-k3f9a2" -> "2 nov. 2026, 10:15 UTC" (as on Windows)."""
    from memento_win.core.agenda.frdates import short_date
    try:
        stamp = datetime.strptime(import_id.split("-", 1)[0], "%Y%m%dT%H%M%S")
    except ValueError:
        return import_id
    return f"{short_date(stamp.date())}, {stamp:%H:%M} UTC"
