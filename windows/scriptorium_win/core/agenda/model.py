"""An event is a note: the agenda keys of its frontmatter, parsed into an `Event` and written back.

File format (README, "Agenda"): flat keys after the usual note keys.

    start: 2026-11-12T14:30        # a date alone (2026-11-12) means all-day
    end: 2026-11-12T15:15          # all-day: last day INCLUDED
    tz: Europe/Paris               # absent = floating (the device zone)
    end_tz: America/New_York       # the end's own zone (a flight); absent = tz. Timed events with tz only
    calendar: perso
    rrule: FREQ=WEEKLY;BYDAY=TH
    exdate: [2026-11-19T14:30]     # same form as start
    alerts: [-15m, -1d]            # minutes / hours / days / weeks, relative to the start
    location: …
    status: confirmed              # confirmed | tentative | cancelled
    uid: 0a1b2c@google.com
    recurrence_of: <series id>     # an edited occurrence of a series…
    recurrence_at: 2026-11-19T14:30  # …and the original start of that occurrence
    import: <import id>            # set on every file an ICS import created
    ical_hash: 1a2b3c4d            # hash of the body the import wrote (re-import: was the description edited?)
    ical_extra:                    # ICS properties the app does not model, verbatim, written back on export
      - "CATEGORIES:Santé"

The same keys on a regular note (`start`, `end`, `tz`, `alerts`) make it a dated note.
"""
from __future__ import annotations

import re
import time
from dataclasses import dataclass, field, replace
from datetime import date, datetime

from .. import note as fmt
from ..note import Note
from . import zones

AGENDA_DIR = "agenda"
AGENDA_KEYS = ("start", "end", "tz", "end_tz", "calendar", "rrule", "exdate", "alerts", "location", "status", "uid",
               "recurrence_of", "recurrence_at", "import", "ical_hash", "ical_extra")
STATUSES = ("confirmed", "tentative", "cancelled")

_KEY_RE = re.compile(r"^([A-Za-z_][\w-]*):[ \t]*(.*)$")
_STAMP_RE = re.compile(r"^(\d{4})-(\d{2})-(\d{2})(?:[T ](\d{2}):(\d{2})(?::(\d{2}))?)?$")
_ALERT_RE = re.compile(r"^([+-]?)(\d+)\s*([mhdw])$")
_UNIT_MINUTES = {"w": 10080, "d": 1440, "h": 60, "m": 1}

Stamp = date  # a `date` (all-day) or a naive `datetime` (wall time of the event's zone)


class AgendaFormatError(ValueError):
    """A file that cannot be an event (its `start` is unreadable). The message is French, for the UI."""


# ---- stamps ----

def parse_stamp(raw: str) -> date:
    """`2026-11-12` -> date; `2026-11-12T14:30[:05]` -> naive datetime. ValueError otherwise."""
    m = _STAMP_RE.match(fmt.unquote(raw).strip())
    if not m:
        raise ValueError(f"date invalide : {raw!r}")
    y, mo, d = int(m[1]), int(m[2]), int(m[3])
    if m[4] is None:
        return date(y, mo, d)
    return datetime(y, mo, d, int(m[4]), int(m[5]), int(m[6] or 0))


def format_stamp(value: date) -> str:
    if isinstance(value, datetime):
        text = f"{value.year:04d}-{value.month:02d}-{value.day:02d}T{value.hour:02d}:{value.minute:02d}"
        return text + (f":{value.second:02d}" if value.second else "")
    return f"{value.year:04d}-{value.month:02d}-{value.day:02d}"


def is_all_day(value: date) -> bool:
    return not isinstance(value, datetime)


def stamp_date(value: date) -> date:
    return value.date() if isinstance(value, datetime) else value


# ---- alerts ----

def parse_alert(raw: str) -> int:
    """`-15m` -> -15 (minutes, negative = before the start). ValueError otherwise."""
    m = _ALERT_RE.match(fmt.unquote(raw).strip())
    if not m:
        raise ValueError(f"rappel invalide : {raw!r}")
    minutes = int(m[2]) * _UNIT_MINUTES[m[3]]
    return -minutes if m[1] == "-" else minutes


def format_alert(minutes: int) -> str:
    sign = "-" if minutes < 0 else ""
    n = abs(minutes)
    for unit, size in _UNIT_MINUTES.items():
        if n and n % size == 0:
            return f"{sign}{n // size}{unit}"
    return f"{sign}{n}m"


# ---- the event ----

@dataclass
class Event:
    """An event file (in `agenda/`) or a dated note. `note` keeps everything that is not an agenda key
    (id, path, title, body, created, attachments, unknown keys), so saving never loses anything."""
    note: Note
    start: date
    end: date | None = None
    tz: str | None = None
    end_tz: str | None = None  # the end's own zone: `end` is a wall time there (timed events with `tz` only)
    calendar: str | None = None
    rrule: str | None = None
    exdate: list[date] = field(default_factory=list)
    alerts: list[int] = field(default_factory=list)
    location: str | None = None
    status: str | None = None
    uid: str | None = None
    recurrence_of: str | None = None
    recurrence_at: date | None = None
    import_id: str | None = None
    ical_hash: str | None = None
    ical_extra: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)  # non-fatal problems met while reading the file (French)

    @property
    def id(self) -> str:
        return self.note.id

    @property
    def path(self) -> str:
        return self.note.path

    @property
    def title(self) -> str:
        return self.note.title

    @property
    def body(self) -> str:
        return self.note.body

    @property
    def all_day(self) -> bool:
        return is_all_day(self.start)

    @property
    def is_series(self) -> bool:
        return bool(self.rrule)

    @property
    def is_exception(self) -> bool:
        return bool(self.recurrence_of)

    @property
    def cancelled(self) -> bool:
        return self.status == "cancelled"

    def copy(self, **changes) -> "Event":
        return replace(self, **changes)

    def with_note(self, **changes) -> "Event":
        return replace(self, note=self.note.copy(**changes))


def _blocks(extra: list[str]) -> list[tuple[str | None, list[str]]]:
    """Groups frontmatter lines into key blocks, like the note parser (lines before any key: key None)."""
    out: list[tuple[str | None, list[str]]] = []
    for line in extra:
        m = _KEY_RE.match(line)
        if m and not line.startswith((" ", "-")):
            out.append((m[1], [line]))
        elif out:
            out[-1][1].append(line)
        else:
            out.append((None, [line]))
    return out


def _scalar(block: list[str]) -> str:
    return fmt.unquote(_KEY_RE.match(block[0])[2])


def _list(block: list[str]) -> list[str]:
    first = _KEY_RE.match(block[0])[2].strip()
    if first:
        return fmt.parse_list(first)
    return [fmt.unquote(b.strip()[1:].strip()) for b in block[1:] if b.strip().startswith("-")]


def agenda_values(note: Note) -> tuple[dict[str, list[str]], list[str]]:
    """The agenda key blocks of a note's unknown keys, and the other lines (kept verbatim)."""
    found: dict[str, list[str]] = {}
    rest: list[str] = []
    for key, block in _blocks(note.extra):
        if key in AGENDA_KEYS:
            found[key] = block
        else:
            rest.extend(block)
    return found, rest


def has_start(note: Note) -> bool:
    return any(key == "start" for key, _ in _blocks(note.extra))


def event_from_note(note: Note) -> Event | None:
    """None when the note has no `start` (not in the calendar). Raises AgendaFormatError when `start` is
    unreadable; smaller problems (bad end, alert, exdate) are dropped and listed in `warnings`."""
    found, rest = agenda_values(note)
    if "start" not in found:
        return None
    warnings: list[str] = []
    raw_start = _scalar(found["start"])
    try:
        start = parse_stamp(raw_start)
    except ValueError:
        raise AgendaFormatError(f"{note.path} : début illisible ({raw_start!r})") from None

    def stamp(key: str, same_form: bool = True) -> date | None:
        if key not in found:
            return None
        raw = _scalar(found[key])
        if not raw:
            return None
        try:
            value = parse_stamp(raw)
        except ValueError:
            warnings.append(f"{key} illisible : {raw!r}")
            return None
        if same_form and is_all_day(value) != is_all_day(start):
            warnings.append(f"{key} ignoré : {raw!r} n'a pas la forme du début")
            return None
        return value

    text = {k: (_scalar(found[k]) or None) if k in found else None
            for k in ("tz", "end_tz", "calendar", "rrule", "location", "status", "uid", "recurrence_of", "import",
                      "ical_hash")}
    end_tz = text["end_tz"]
    if end_tz:
        if is_all_day(start):
            warnings.append(f"end_tz ignoré : {end_tz!r} (événement sur la journée)")
            end_tz = None
        elif not text["tz"]:
            warnings.append(f"end_tz ignoré : {end_tz!r} (pas de fuseau tz pour le début)")
            end_tz = None
        elif not zones.is_valid(end_tz):
            warnings.append(f"fuseau de fin inconnu : {end_tz!r} (celui du début est utilisé)")
    end = stamp("end")
    if end is not None and ends_before_start(start, end, text["tz"], end_tz):
        warnings.append(f"fin avant le début ignorée : {format_stamp(end)}")
        end = None
    exdate: list[date] = []
    for raw in _list(found["exdate"]) if "exdate" in found else []:
        try:
            exdate.append(parse_stamp(raw))
        except ValueError:
            warnings.append(f"exdate illisible : {raw!r}")
    alerts: list[int] = []
    for raw in _list(found["alerts"]) if "alerts" in found else []:
        try:
            alerts.append(parse_alert(raw))
        except ValueError:
            warnings.append(f"rappel illisible : {raw!r}")
    if text["tz"] and not zones.is_valid(text["tz"]):
        warnings.append(f"fuseau inconnu : {text['tz']!r} (celui de l'appareil est utilisé)")
    rrule = text["rrule"]
    if rrule and rrule.upper().startswith("RRULE:"):
        rrule = rrule[6:]
    status = text["status"].lower() if text["status"] else None
    return Event(
        note=note.copy(extra=rest),
        start=start, end=end, tz=text["tz"], end_tz=end_tz, calendar=text["calendar"], rrule=rrule or None,
        exdate=exdate, alerts=alerts, location=text["location"], status=status, uid=text["uid"],
        recurrence_of=text["recurrence_of"], recurrence_at=stamp("recurrence_at", same_form=False),
        import_id=text["import"], ical_hash=text["ical_hash"],
        ical_extra=_list(found["ical_extra"]) if "ical_extra" in found else [],
        warnings=warnings,
    )


def ends_before_start(start: date, end: date, tz: str | None, end_tz: str | None) -> bool:
    """With two known zones the instants are compared (Paris 14:00 -> New York 12:00 is fine), else the walls."""
    if not is_all_day(start) and zones.is_valid(tz) and zones.is_valid(end_tz):
        return zones.to_instant(end, zones.zone(end_tz)) < zones.to_instant(start, zones.zone(tz))
    return end < start


def _ical_line(line: str) -> str:
    escaped = line.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n").replace("\t", "\\t")
    return f'  - "{escaped}"'


def agenda_lines(event: Event) -> list[str]:
    """The agenda keys of the event, in the fixed order of the format."""
    out = [f"start: {format_stamp(event.start)}"]
    if event.end is not None:
        out.append(f"end: {format_stamp(event.end)}")
    if event.tz:
        out.append(f"tz: {fmt.yaml_quote(event.tz)}")
    if event.end_tz:
        out.append(f"end_tz: {fmt.yaml_quote(event.end_tz)}")
    if event.calendar:
        out.append(f"calendar: {fmt.yaml_quote(event.calendar)}")
    if event.rrule:
        out.append(f"rrule: {fmt.yaml_quote(event.rrule)}")
    if event.exdate:
        out.append("exdate: [" + ", ".join(format_stamp(x) for x in event.exdate) + "]")
    if event.alerts:
        out.append("alerts: [" + ", ".join(format_alert(x) for x in event.alerts) + "]")
    if event.location:
        out.append(f"location: {fmt.yaml_quote(event.location)}")
    if event.status:
        out.append(f"status: {event.status}")
    if event.uid:
        out.append(f"uid: {fmt.yaml_quote(event.uid)}")
    if event.recurrence_of:
        out.append(f"recurrence_of: {fmt.yaml_quote(event.recurrence_of)}")
    if event.recurrence_at is not None:
        out.append(f"recurrence_at: {format_stamp(event.recurrence_at)}")
    if event.import_id:
        out.append(f"import: {fmt.yaml_quote(event.import_id)}")
    if event.ical_hash:
        out.append(f"ical_hash: {fmt.yaml_quote(event.ical_hash)}")
    if event.ical_extra:
        out.append("ical_extra:")
        out.extend(_ical_line(x) for x in event.ical_extra)
    return out


def event_to_note(event: Event) -> Note:
    """The note to write: agenda keys first, then the other unknown keys as they were."""
    return event.note.copy(extra=agenda_lines(event) + list(event.note.extra))


def strip_agenda(note: Note) -> Note:
    """The note without any agenda key ("Retirer de l'agenda" on a dated note)."""
    return note.copy(extra=agenda_values(note)[1])


def now_ms() -> int:
    return int(time.time() * 1000)


def new_event(title: str, start: date, end: date | None = None, tz: str | None = None,
              calendar: str | None = None, body: str = "", now: int | None = None, **fields) -> Event:
    """A new, unsaved event (empty path: `AgendaStore.save` fixes it). Its uid is `<id>@scriptorium`."""
    now = now_ms() if now is None else now
    event_id = fmt.generate_id()
    note = Note(path="", id=event_id, title=title, body=body, created=now, updated=now)
    fields.setdefault("uid", f"{event_id}@scriptorium")
    return Event(note=note, start=start, end=end, tz=tz, calendar=calendar, **fields)


def checklist_progress(body: str) -> tuple[int, int] | None:
    """(checked, total) of the note's checklist items, None without a checklist."""
    total = done = 0
    for line in body.split("\n"):
        m = fmt.CHECK_RE.match(line)
        if m:
            total += 1
            done += m[1] != " "
    return (done, total) if total else None


def fnv32(text: str) -> str:
    """FNV-1a 32 bits of the UTF-8 text, 8 hex digits (same on Android)."""
    h = 0x811C9DC5
    for b in text.encode("utf-8"):
        h ^= b
        h = (h * 0x01000193) & 0xFFFFFFFF
    return f"{h:08x}"
