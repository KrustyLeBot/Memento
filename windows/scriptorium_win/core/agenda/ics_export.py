"""ICS export (README, "Agenda", export): the inverse mapping of the import. Same output, byte for byte, as
Android's `IcsExport.kt` (CRLF, lines folded at 75 octets).

- all-day: `DTSTART;VALUE=DATE`, DTEND = the day after the last day (exclusive); timed with a known zone:
  `TZID=<IANA>` plus one VTIMEZONE per zone used (`end_tz`: DTEND in that zone, its VTIMEZONE too); floating (or an unknown zone name): no TZID.
- RRULE verbatim, EXDATE one line per value in DTSTART's form (a date on a timed series: that day at the
  series' start time), except the occurrences an exported exception file overrides; an exception file is written
  as an override (UID of its series, RECURRENCE-ID in the series' form).
- alerts -> VALARM (ACTION:DISPLAY); `ical_extra` lines verbatim (those naming a property the export writes
  itself are left out and reported); DESCRIPTION = the note body.
- options: the activities and the dated notes as events (UTC times for activities and reminders).
"""
from __future__ import annotations

import io
import zipfile
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta, timezone

from .. import note as fmt
from ..note import Note
from . import ics, zones
from .calendars import CalendarSettings
from .entries import activity_subtitle, dated_notes
from .ics_import import CONSUMED
from .model import Event, format_stamp, is_all_day

UTC = timezone.utc
PRODID = "-//Scriptorium//Agenda//FR"


@dataclass
class ExportResult:
    text: str
    events: int = 0
    warnings: list[str] = field(default_factory=list)  # French: ical_extra lines left out, unknown zones


def _utc(instant: datetime) -> str:
    return instant.astimezone(UTC).strftime("%Y%m%dT%H%M%SZ")


def _local(value: datetime) -> str:
    return value.strftime("%Y%m%dT%H%M%S")


def _time_line(name: str, value: date, tz: str | None) -> str:
    if is_all_day(value):
        return f"{name};VALUE=DATE:{value:%Y%m%d}"
    if tz:
        return f"{name};TZID={tz}:{_local(value)}"
    return f"{name}:{_local(value)}"


class _Writer:
    def __init__(self, now: datetime):
        self.lines: list[str] = []
        self.zones: set[str] = set()
        self.warnings: list[str] = []
        self.count = 0
        self.stamp = _utc(now)

    def add(self, line: str) -> None:
        self.lines.append(line)

    def zone_of(self, ev: Event) -> str | None:
        if ev.tz and zones.is_valid(ev.tz):
            if not ev.all_day:
                self.zones.add(ev.tz)
            return ev.tz
        if ev.tz:
            self.warnings.append(f"{ev.title} : fuseau inconnu « {ev.tz} », exporté sans fuseau")
        return None

    def end_zone_of(self, ev: Event, tz: str | None) -> str | None:
        """DTEND's zone: `end_tz` when known on an event exported with its zone, else the start's."""
        if not ev.end_tz or tz is None or ev.all_day:
            return tz
        if not zones.is_valid(ev.end_tz):
            self.warnings.append(f"{ev.title} : fuseau de fin inconnu « {ev.end_tz} », exporté dans celui du début")
            return tz
        self.zones.add(ev.end_tz)
        return ev.end_tz

    def alarms(self, title: str, alerts: list[int]) -> None:
        for minutes in alerts:
            self.lines += ["BEGIN:VALARM", "ACTION:DISPLAY", f"DESCRIPTION:{ics.escape_text(title)}",
                           f"TRIGGER:{ics.format_alert_trigger(minutes)}", "END:VALARM"]

    def event(self, ev: Event, series: Event | None, uid_suffix: str, overridden: set | None = None) -> None:
        tz = self.zone_of(ev)
        self.count += 1
        self.add("BEGIN:VEVENT")
        uid = (series.uid if series is not None and series.uid else None) or ev.uid or f"{(series or ev).id}@{uid_suffix}"
        self.add(f"UID:{uid}")
        self.add(f"DTSTAMP:{self.stamp}")
        self.add(f"SUMMARY:{ics.escape_text(ev.title)}")
        self.add(_time_line("DTSTART", ev.start, tz))
        if ev.all_day:
            self.add(_time_line("DTEND", (ev.end or ev.start) + timedelta(days=1), None))
        elif ev.end is not None:
            self.add(_time_line("DTEND", ev.end, self.end_zone_of(ev, tz)))
        if ev.rrule:
            self.add(f"RRULE:{ev.rrule}")
        # an overridden occurrence is replaced by its override, never excluded too (Google drops such overrides)
        written: list[date] = []
        for x in (_exdate_stamp(ev, v) for v in ev.exdate):
            if x not in written and x not in (overridden or set()):
                written.append(x)
                self.add(_time_line("EXDATE", x, tz))
        if ev.recurrence_at is not None:
            stz = self.zone_of(series) if series is not None else tz
            at = _exdate_stamp(series, ev.recurrence_at) if series is not None else ev.recurrence_at
            self.add(_time_line("RECURRENCE-ID", at, stz))
        if ev.status in ("confirmed", "tentative", "cancelled"):
            self.add(f"STATUS:{ev.status.upper()}")
        if ev.location:
            self.add(f"LOCATION:{ics.escape_text(ev.location)}")
        if ev.body.strip():
            self.add(f"DESCRIPTION:{ics.escape_text(ev.body.strip())}")
        for raw in ev.ical_extra:
            try:
                name = ics.parse_line(raw).name
            except ics.IcsSyntaxError:
                self.warnings.append(f"{ev.title} : ligne ical_extra illisible ignorée : {raw[:60]}")
                continue
            if name in CONSUMED or name in ("BEGIN", "END"):
                self.warnings.append(f"{ev.title} : ligne ical_extra {name} ignorée (écrite par l'export)")
                continue
            self.add(raw)
        self.alarms(ev.title, ev.alerts)
        self.add("END:VEVENT")

    def plain(self, uid: str, title: str, start: datetime, end: datetime | None, description: str | None,
              alerts: list[int]) -> None:
        self.count += 1
        self.lines += ["BEGIN:VEVENT", f"UID:{uid}", f"DTSTAMP:{self.stamp}", f"SUMMARY:{ics.escape_text(title)}",
                       f"DTSTART:{_utc(start)}"]
        if end is not None and end > start:
            self.add(f"DTEND:{_utc(end)}")
        if description:
            self.add(f"DESCRIPTION:{ics.escape_text(description)}")
        self.alarms(title, alerts)
        self.add("END:VEVENT")

    def text(self, name: str, now: datetime) -> str:
        head = ["BEGIN:VCALENDAR", "VERSION:2.0", f"PRODID:{PRODID}", "CALSCALE:GREGORIAN",
                f"X-WR-CALNAME:{ics.escape_text(name)}"]
        for z in sorted(self.zones):
            head += zones.vtimezone_lines(z, now.year)
        return "".join(ics.fold(line) for line in head + self.lines + ["END:VCALENDAR"])


def _exdate_stamp(series: Event, x: date) -> date:
    """EXDATE follows DTSTART's form: a date alone on a timed series is its occurrence at the series' start time."""
    if is_all_day(x) and not series.all_day:
        return datetime.combine(x, series.start.time())
    if not is_all_day(x) and series.all_day:
        return x.date()
    return x


def _sorted(events: list[Event]) -> list[Event]:
    return sorted(events, key=lambda e: (format_stamp(e.start), e.title, e.id))


def export_calendar(events: list[Event], settings: CalendarSettings, calendar_id: str | None, now: datetime,
                    notes: list[Note] | None = None, activities: list[Note] | None = None,
                    name: str | None = None) -> ExportResult:
    """One `.ics`: the events of `calendar_id` (None: every calendar), plus the dated notes and activities given
    (pass them only when "Inclure les activités et les notes datées" is ticked)."""
    w = _Writer(now)
    by_id = {e.id: e for e in events}
    chosen = [e for e in events if not e.note.conflict and
              (calendar_id is None or settings.calendar(e.calendar).id == calendar_id)]
    overridden: dict[str, set] = {}
    for x in chosen:
        series = by_id.get(x.recurrence_of) if x.recurrence_of else None
        if series is not None and x.recurrence_at is not None:
            overridden.setdefault(series.id, set()).add(_exdate_stamp(series, x.recurrence_at))
    for ev in _sorted(chosen):
        series = by_id.get(ev.recurrence_of) if ev.recurrence_of else None
        w.event(ev, series, "scriptorium", overridden.get(ev.id))
    if notes:
        dated, problems = dated_notes(notes)
        w.warnings += problems
        dated_ids = {e.id for e in dated}
        for ev in _sorted(dated):
            w.event(ev, None, "scriptorium-note")
        for n in sorted(notes, key=lambda n: (n.reminder or 0, n.id)):
            if n.conflict or n.reminder is None or n.id in dated_ids:
                continue
            at = datetime.fromtimestamp(n.reminder / 1000, UTC)
            w.plain(f"{n.id}@scriptorium-note", n.title, at, None, n.body.strip() or None, [0])
    for n in sorted(activities or [], key=lambda n: (n.activity.start if n.activity else 0, n.id)):
        info = n.activity
        if info is None or n.conflict:
            continue
        start = datetime.fromtimestamp(info.start / 1000, UTC)
        w.plain(f"{n.id}@scriptorium-activite", n.title, start, start + timedelta(seconds=max(0, info.elapsed)),
                activity_subtitle(info.distance, info.elapsed), [])
    title = name or (settings.calendar(calendar_id).name if calendar_id else "Agenda")
    return ExportResult(w.text(title, now), w.count, w.warnings)


def export_all(events: list[Event], settings: CalendarSettings, now: datetime,
               notes: list[Note] | None = None, activities: list[Note] | None = None) -> tuple[bytes, list[str]]:
    """A `.zip` with one `.ics` per calendar (`<slug>.ics`), plus `activites.ics` and `notes.ics` when given."""
    buffer = io.BytesIO()
    warnings: list[str] = []
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as archive:
        used: set[str] = set()

        def put(base: str, result: ExportResult) -> None:
            name, n = base, 2
            while name in used:
                name, n = f"{base}-{n}", n + 1
            used.add(name)
            warnings.extend(result.warnings)
            info = zipfile.ZipInfo(f"{name}.ics", date_time=now.astimezone(UTC).timetuple()[:6])
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, result.text.encode("utf-8"))

        for cal in settings.calendars:
            put(fmt.slug(cal.name), export_calendar(events, settings, cal.id, now))
        known = {c.id for c in settings.calendars}
        if not known:
            put("agenda", export_calendar(events, settings, None, now))
        if notes:
            put("notes", export_calendar([], settings, None, now, notes=notes, name="Notes"))
        if activities:
            put("activites", export_calendar([], settings, None, now, activities=activities, name="Activités"))
    return buffer.getvalue(), warnings
