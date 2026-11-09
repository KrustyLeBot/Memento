"""ICS import (README, "Agenda", import): read -> preview -> apply -> report, and undo.

1. `read_sources` parses `.ics` files and `.zip`s of them into `ParsedCalendar`s (one per `.ics`), no write.
   Each VEVENT is mapped as below; a malformed one is skipped and counted with its reason.
2. `preview` gives, per calendar: events, series, exceptions, date range, ignored items, events whose zone
   could not be resolved, duplicates (same UID in the target calendar).
3. `apply_import` writes the files (`import: <id>` on each), the new calendars, and returns the report.
4. `undo_import` deletes every file of an import and removes the exdates its exception files added.

Mapping (RFC 5545 -> file), identical on Android (`IcsImport.kt`):
- SUMMARY -> title; LOCATION ("null", "undefined", "None", blank: none); UID -> uid; STATUS TENTATIVE/CONFIRMED -> status; CANCELLED: not imported
  (an overridden occurrence that is cancelled becomes an exdate of its series).
- DTSTART/DTEND/DURATION: VALUE=DATE -> all-day (exclusive DTEND -> inclusive end); TZID -> wall time + IANA
  zone (zones.resolve_tzid; unresolved -> device zone, counted); `...Z` -> wall time of the device zone + that
  zone; neither -> floating (no tz). A DTEND with another TZID than DTSTART's is kept as `end_tz` (its wall time
  there, no conversion); any other DTEND (UTC, or a zone on a start without one) is converted to the start's zone.
- RRULE verbatim; EXDATE (every line, comma lists) converted to the series' form; RDATE: one extra single event
  per date; RECURRENCE-ID overrides -> exception files (recurrence_of / recurrence_at) + exdate on the series.
- VALARM with a TRIGGER relative to the start (ACTION other than EMAIL) -> alerts; others ignored (counted). A new
  event also gets the default reminders (`CalendarSettings.default_alerts`, synced); updating an existing one keeps
  the reminders set in the app.
- DESCRIPTION -> body (HTML converted to text, links kept); X-GOOGLE-CONFERENCE not already in the description
  -> a first line "Visio : …"; without it, a meeting link of the description (`meeting_link`: Proton Meet, Meet,
  Teams, Zoom) not already on its first line -> "Visio : …"; else a URL not in the description -> "Lien : …";
  ORGANIZER/ATTENDEE -> "Participants :" list at the end.
- Target: a mail invitation goes to the default calendar; only a calendar export (`calendar_export`) proposes a
  calendar named after it (X-WR-CALNAME, else the file name).
- Every other property of the VEVENT is kept verbatim in `ical_extra` (DTSTAMP and X-ALT-DESC excepted:
  regenerated / redundant); other components (VTODO, VJOURNAL…) and sub-components are ignored, counted.
"""
from __future__ import annotations

import io
import zipfile
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from typing import BinaryIO, Callable

from .. import note as fmt
from ..note import Note
from . import frdates, ics, zones
from .links import meeting_link
from .calendars import Calendar, CalendarSettings, next_color, unique_calendar_id
from .model import Event, fnv32, format_stamp, is_all_day, now_ms, stamp_date
from .recurrence import MAX_ITERATIONS, series_starts
from .store import AgendaStore

UTC = timezone.utc
CONSUMED = {"UID", "SUMMARY", "DESCRIPTION", "LOCATION", "STATUS", "DTSTART", "DTEND", "DURATION", "RRULE",
            "EXDATE", "RDATE", "RECURRENCE-ID", "DTSTAMP", "X-ALT-DESC"}
DUPLICATES = ("skip", "update", "keep")
UNTITLED = "(Sans titre)"
EXPORT_MIN_EVENTS = 20   # more events than this make a file without X-WR-CALNAME a calendar export
LISTED_MAX = 5           # up to this many events, the preview lists each one
_NO_VALUE = {"null", "undefined", "none"}


def clean_location(raw: str | None) -> str | None:
    """A LOCATION as written by the sender; the placeholders some generators write for "none" are no location."""
    value = (raw or "").strip()
    return None if not value or value.lower() in _NO_VALUE else value


@dataclass
class IcsSource:
    name: str                          # file name, gives the calendar name when the file has none
    open: Callable[[], BinaryIO]
    from_archive: bool = False         # one .ics of a .zip (Google's export): a calendar export

    @staticmethod
    def from_bytes(name: str, data: bytes, from_archive: bool = False) -> "IcsSource":
        return IcsSource(name, lambda: io.BytesIO(data), from_archive)

    @staticmethod
    def from_path(path: Path) -> "IcsSource":
        return IcsSource(Path(path).name, lambda: open(path, "rb"))


def expand_sources(sources: list[IcsSource]) -> list[IcsSource]:
    """A `.zip` becomes one source per `.ics` inside (Google's export); other files pass through."""
    out: list[IcsSource] = []
    for src in sources:
        if not src.name.lower().endswith(".zip"):
            out.append(src)
            continue
        with src.open() as handle:
            data = handle.read()
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            names = sorted(n for n in archive.namelist() if n.lower().endswith(".ics") and not n.endswith("/"))
        for name in names:
            out.append(IcsSource(name.rsplit("/", 1)[-1],
                                 lambda name=name: io.BytesIO(zipfile.ZipFile(io.BytesIO(data)).read(name)), True))
    return out


@dataclass
class TimeValue:
    kind: str           # "date" | "floating" | "zoned" | "utc"
    value: date         # date, naive wall datetime (floating, zoned) or aware UTC datetime
    tz: str | None = None
    unresolved: bool = False


@dataclass
class Override:
    uid: str | None
    recurrence_id: TimeValue
    event: Event | None       # None: the occurrence is cancelled


@dataclass
class CalendarPreview:
    name: str
    source: str
    events: int = 0            # files that would be created (singles, series, rdates), cancelled ones excluded
    series: int = 0
    exceptions: int = 0
    cancelled: int = 0
    ignored: int = 0           # other components, unusable alarms, RDATE periods
    unresolved_zones: int = 0  # events whose TZID could not be resolved (device zone used)
    first: date | None = None
    last: date | None = None
    duplicates: int = 0        # same UID already in the target calendar
    errors: list[str] = field(default_factory=list)  # malformed events and lines, skipped (French)
    # where the events go by default (`suggested_calendar`): the default calendar, or for a calendar export the
    # existing calendar of the same name, else None (a new one)
    suggested_calendar: str | None = None
    calendar_export: bool = False  # X-WR-CALNAME, more than EXPORT_MIN_EVENTS events, or from a .zip
    items: list[ImportItem] | None = None  # each event when there are at most LISTED_MAX


@dataclass
class ImportItem:
    """One event of a small import as the preview lists it (`when_text`)."""
    title: str
    when_text: str
    location: str | None


@dataclass
class ParsedCalendar:
    name: str
    source: str
    events: list[Event] = field(default_factory=list)        # singles and series, uid kept, no id/path yet
    overrides: list[Override] = field(default_factory=list)
    preview: CalendarPreview | None = None


# ---- times ----

def _parse_value(text: str, is_date: bool) -> tuple[date, bool]:
    t = text.strip()
    if is_date or len(t) == 8:
        return date(int(t[0:4]), int(t[4:6]), int(t[6:8])), False
    if len(t) not in (15, 16) or t[8] != "T":
        raise ValueError(f"date-heure illisible : {text!r}")
    dt = datetime(int(t[0:4]), int(t[4:6]), int(t[6:8]), int(t[9:11]), int(t[11:13]), int(t[13:15]))
    utc = t.endswith("Z")
    if len(t) == 16 and not utc:
        raise ValueError(f"date-heure illisible : {text!r}")
    return dt, utc


class _Context:
    def __init__(self, device_zone: str, vtimezones: dict[str, dict]):
        self.device_zone = device_zone
        self.vtimezones = vtimezones
        self._cache: dict[str, str | None] = {}

    def resolve(self, tzid: str) -> str | None:
        if tzid not in self._cache:
            self._cache[tzid] = zones.resolve_tzid(tzid, self.vtimezones.get(tzid))
        return self._cache[tzid]

    def times(self, line: ics.ContentLine) -> list[TimeValue]:
        is_date = (line.param("VALUE") or "").upper() == "DATE"
        tzid = line.param("TZID")
        out = []
        for part in ics.split_list(line.value):
            if "/" in part:
                raise ValueError("période (RDATE)")
            value, utc = _parse_value(part, is_date)
            if not isinstance(value, datetime):
                out.append(TimeValue("date", value))
            elif utc:
                out.append(TimeValue("utc", value.replace(tzinfo=UTC)))
            elif tzid:
                resolved = self.resolve(tzid)
                out.append(TimeValue("zoned", value, resolved or self.device_zone, resolved is None))
            else:
                out.append(TimeValue("floating", value))
        return out


def _instant(tv: TimeValue, device_zone: str) -> datetime:
    if tv.kind == "utc":
        return tv.value
    if tv.kind == "zoned":
        return zones.to_instant(tv.value, zones.zone(tv.tz))
    return zones.to_instant(tv.value, zones.zone(device_zone))


def to_event_stamp(tv: TimeValue, all_day: bool, tz: str | None, device_zone: str) -> date:
    """A time of the ICS in the frame of an event: a date for an all-day event (or a date value), else the
    wall time of the event's zone (floating events: floating values as they are, others in the device zone)."""
    if tv.kind == "date":
        return tv.value
    if all_day:
        if tv.kind == "utc":
            return zones.to_wall(tv.value, zones.zone(device_zone)).date()
        return tv.value.date()
    if tv.kind == "floating":
        return tv.value
    if tv.kind == "zoned" and tv.tz == (tz or device_zone):
        return tv.value
    return zones.to_wall(_instant(tv, device_zone), zones.zone(tz or device_zone))


# ---- one VEVENT ----

def _vtimezone_signature(comp: ics.Component) -> dict:
    std = dst = dst_month = std_month = None
    for child in comp.children:
        to = child.first("TZOFFSETTO")
        if to is None:
            continue
        v = to.value.strip()
        sign = -1 if v.startswith("-") else 1
        digits = v.lstrip("+-")
        minutes = sign * (int(digits[0:2]) * 60 + int(digits[2:4] or 0))
        rule = child.first("RRULE")
        month = None
        if rule is not None:
            for part in rule.value.upper().split(";"):
                if part.startswith("BYMONTH="):
                    month = int(part[8:].split(",")[0])
        if child.name == "STANDARD":
            std, std_month = minutes, month
        elif child.name == "DAYLIGHT":
            dst, dst_month = minutes, month
    if std is None:
        std = dst
        dst = dst_month = None
    return {"std": std, "dst": dst, "dst_month": dst_month, "std_month": std_month}


def _people(comp: ics.Component) -> list[str]:
    out, seen = [], set()
    for line in comp.all("ORGANIZER") + comp.all("ATTENDEE"):
        value = line.value.strip()
        email = value[7:] if value.lower().startswith("mailto:") else value
        name = (line.param("CN") or "").strip()
        key = email.lower()
        if key in seen:
            continue
        seen.add(key)
        text = f"{name} <{email}>" if name and name != email else email
        if line.name == "ORGANIZER":
            text += " (organisateur)"
        out.append(f"- {text}")
    return out


def build_body(comp: ics.Component) -> str:
    desc_line = comp.first("DESCRIPTION")
    desc = ics.unescape_text(desc_line.value) if desc_line else ""
    if not desc.strip():
        alt = comp.first("X-ALT-DESC")
        if alt is not None and "html" in (alt.param("FMTTYPE") or "").lower():
            desc = ics.html_to_text(ics.unescape_text(alt.value))
    elif ics.looks_html(desc):
        desc = ics.html_to_text(desc)
    desc = desc.replace("\r\n", "\n").strip()
    parts = []
    conference = comp.first("X-GOOGLE-CONFERENCE")
    url = comp.first("URL")
    described = meeting_link(desc) if conference is None or not conference.value.strip() else None
    if conference is not None and conference.value.strip() and conference.value.strip() not in desc:
        parts.append(f"Visio : {conference.value.strip()}")
    elif described is not None and described not in desc.split("\n", 1)[0]:
        parts.append(f"Visio : {described}")
    elif url is not None and url.value.strip() and url.value.strip() not in desc:
        parts.append(f"Lien : {url.value.strip()}")
    if desc:
        parts.append(desc)
    people = _people(comp)
    if people and "Participants :" not in desc:
        parts.append("Participants :\n" + "\n".join(people))
    return "\n\n".join(parts)


def _alerts(comp: ics.Component, preview: CalendarPreview) -> list[int]:
    out: list[int] = []
    for alarm in comp.children:
        if alarm.name != "VALARM":
            preview.ignored += 1
            continue
        action = (alarm.first("ACTION").value.strip().upper() if alarm.first("ACTION") else "DISPLAY")
        trigger = alarm.first("TRIGGER")
        if action == "EMAIL" or trigger is None or (trigger.param("VALUE") or "").upper() == "DATE-TIME" \
                or (trigger.param("RELATED") or "START").upper() != "START":
            preview.ignored += 1
            continue
        try:
            days, seconds = ics.parse_duration(trigger.value)
        except ValueError:
            preview.ignored += 1
            continue
        total = days * 86400 + seconds
        minutes = -(abs(total) // 60) if total < 0 else total // 60
        if minutes not in out:
            out.append(minutes)
    return out


def _map_event(comp: ics.Component, ctx: _Context, preview: CalendarPreview, now: int):
    """-> ("event", Event, rdates) | ("override", Override) | ("cancelled", None). ValueError if malformed."""
    dtstart = comp.first("DTSTART")
    if dtstart is None:
        raise ValueError("DTSTART manquant")
    start_tv = ctx.times(dtstart)[0]
    unresolved = start_tv.unresolved
    if start_tv.kind == "date":
        all_day, tz, start = True, None, start_tv.value
    elif start_tv.kind == "utc":
        # A single UTC event takes the device zone (readable file); a UTC series stays in UTC, or its
        # occurrences would move by an hour across daylight saving and its UTC EXDATEs would stop matching.
        all_day, tz = False, ("UTC" if comp.first("RRULE") is not None else ctx.device_zone)
        start = zones.to_wall(start_tv.value, zones.zone(tz))
    elif start_tv.kind == "zoned":
        all_day, tz, start = False, start_tv.tz, start_tv.value
    else:
        all_day, tz, start = False, None, start_tv.value
    dz = ctx.device_zone

    end = None
    end_tz = None
    dtend = comp.first("DTEND")
    duration = comp.first("DURATION")
    if dtend is not None:
        end_tv = ctx.times(dtend)[0]
        unresolved = unresolved or end_tv.unresolved
        if all_day:
            last = to_event_stamp(end_tv, True, None, dz)
            last = last - timedelta(days=1) if end_tv.kind == "date" else last
            end = last if last > start else None
        elif start_tv.kind == "zoned" and end_tv.kind == "zoned" and end_tv.tz != tz:
            # a flight: DTEND in the arrival zone stays a wall time of that zone
            if _instant(end_tv, dz) > _instant(start_tv, dz):
                end, end_tz = end_tv.value, end_tv.tz
        elif end_tv.kind != "date":
            e = to_event_stamp(end_tv, False, tz, dz)
            end = e if e > start else None
    elif duration is not None:
        days, seconds = ics.parse_duration(duration.value)
        if all_day:
            end = start + timedelta(days=days - 1) if days > 1 else None
        else:
            e = start + timedelta(days=days, seconds=seconds)
            end = e if e > start else None

    exdate: list[date] = []
    for line in comp.all("EXDATE"):
        for tv in ctx.times(line):
            unresolved = unresolved or tv.unresolved
            s = to_event_stamp(tv, all_day, tz, dz)
            if s not in exdate:
                exdate.append(s)

    status_raw = (comp.first("STATUS").value.strip().upper() if comp.first("STATUS") else "")
    status = {"TENTATIVE": "tentative", "CONFIRMED": "confirmed"}.get(status_raw)
    summary = comp.first("SUMMARY")
    title = ics.unescape_text(summary.value).strip() if summary else ""
    location_line = comp.first("LOCATION")
    location = clean_location(ics.unescape_text(location_line.value) if location_line else None)
    uid_line = comp.first("UID")
    uid = uid_line.value.strip() if uid_line else None
    rrule_line = comp.first("RRULE")
    body = build_body(comp)
    note = Note(path="", id="", title=title or UNTITLED, body=body, created=now, updated=now)
    event = Event(
        note=note, start=start, end=end, tz=tz, end_tz=end_tz, rrule=rrule_line.value.strip() if rrule_line else None,
        exdate=exdate, alerts=_alerts(comp, preview), location=location, status=status, uid=uid or None,
        ical_hash=fnv32(body), ical_extra=[p.raw for p in comp.lines if p.name not in CONSUMED],
    )
    if unresolved:
        preview.unresolved_zones += 1

    rid = comp.first("RECURRENCE-ID")
    if rid is not None:
        rid_tv = ctx.times(rid)[0]
        return ("override", Override(uid, rid_tv, None if status_raw == "CANCELLED" else event))
    if status_raw == "CANCELLED":
        return ("cancelled", None)
    rdates: list[date] = []
    for line in comp.all("RDATE"):
        try:
            values = ctx.times(line)
        except ValueError:
            preview.ignored += 1
            continue
        for tv in values:
            rdates.append(to_event_stamp(tv, all_day, tz, dz))
    return ("event", event, rdates)


def _rdate_copy(event: Event, stamp: date) -> Event:
    if event.end is None:
        end = None
    elif event.end_tz and event.tz and isinstance(stamp, datetime):
        tz, end_tz = zones.zone(event.tz), zones.zone(event.end_tz)
        span = zones.to_instant(event.end, end_tz) - zones.to_instant(event.start, tz)
        end = zones.to_wall(zones.to_instant(stamp, tz) + span, end_tz)
    else:
        end = stamp + (event.end - event.start)
    uid = f"{event.uid}#{format_stamp(stamp)}" if event.uid else None
    return event.copy(start=stamp, end=end, rrule=None, exdate=[], uid=uid)


def parse_calendar(source: IcsSource, device_zone: str, now: int | None = None) -> list[ParsedCalendar]:
    """Every VCALENDAR of one `.ics` (usually one)."""
    now = now_ms() if now is None else now
    out = []
    errors: list[str] = []
    stem = source.name.rsplit("/", 1)[-1]
    stem = stem[:-4] if stem.lower().endswith(".ics") else stem
    with source.open() as stream:
        calendars = list(ics.read_calendars(stream, errors))
    if not calendars:
        p = ParsedCalendar(stem, source.name)
        p.preview = CalendarPreview(stem, source.name, errors=errors or ["aucun BEGIN:VCALENDAR"])
        return [p]
    for index, cal in enumerate(calendars):
        name_line = cal.first("X-WR-CALNAME")
        name = ics.unescape_text(name_line.value).strip() if name_line and name_line.value.strip() else stem
        parsed = ParsedCalendar(name, source.name)
        preview = CalendarPreview(name, source.name, errors=list(errors) if index == 0 else [])
        if cal.name != "VCALENDAR":
            preview.errors.append(f"composant {cal.name} hors de VCALENDAR ignoré")
            parsed.preview = preview
            out.append(parsed)
            continue
        vtz = {}
        for comp in cal.children:
            if comp.name == "VTIMEZONE" and comp.first("TZID") is not None:
                try:
                    vtz[comp.first("TZID").value.strip()] = _vtimezone_signature(comp)
                except (ValueError, IndexError):
                    preview.errors.append(f"ligne {comp.line_number} : VTIMEZONE illisible")
        ctx = _Context(device_zone, vtz)
        for comp in cal.children:
            if comp.name == "VTIMEZONE":
                continue
            if comp.name != "VEVENT":
                preview.ignored += 1
                continue
            try:
                mapped = _map_event(comp, ctx, preview, now)
            except (ValueError, IndexError) as error:
                uid = comp.first("UID")
                where = f"ligne {comp.line_number}" + (f" (UID {uid.value.strip()})" if uid else "")
                preview.errors.append(f"{where} : {error}")
                continue
            if mapped[0] == "override":
                parsed.overrides.append(mapped[1])
            elif mapped[0] == "cancelled":
                preview.cancelled += 1
            else:
                event, rdates = mapped[1], mapped[2]
                parsed.events.append(event)
                parsed.events.extend(_rdate_copy(event, s) for s in rdates if s != event.start)
        _summarise(parsed, preview, device_zone)
        preview.calendar_export = bool(name_line and name_line.value.strip()) or source.from_archive \
            or preview.events > EXPORT_MIN_EVENTS
        parsed.preview = preview
        out.append(parsed)
    return out


def _summarise(parsed: ParsedCalendar, preview: CalendarPreview, device_zone: str) -> None:
    preview.events = len(parsed.events)
    preview.series = sum(1 for e in parsed.events if e.rrule)
    series_uids = {e.uid for e in parsed.events if e.rrule and e.uid}
    preview.exceptions = sum(1 for o in parsed.overrides if o.event is not None and o.uid in series_uids)
    preview.events += sum(1 for o in parsed.overrides if o.event is not None and o.uid not in series_uids)
    preview.cancelled += sum(1 for o in parsed.overrides if o.event is None)
    days = [stamp_date(e.start) for e in parsed.events] + [stamp_date(e.end) for e in parsed.events if e.end]
    days += [stamp_date(o.event.start) for o in parsed.overrides if o.event is not None]
    if days:
        preview.first, preview.last = min(days), max(days)
    listed = parsed.events + [o.event for o in parsed.overrides if o.event is not None and o.uid not in series_uids]
    if not listed or len(listed) > LISTED_MAX:
        preview.items = None
    else:
        listed.sort(key=lambda e: (stamp_date(e.start), format_stamp(e.start)))
        preview.items = [ImportItem(e.title, when_text(e, device_zone), e.location) for e in listed]


def _day(d: date) -> str:
    return f"{frdates.WEEKDAYS_SHORT[d.weekday()]} {d.day} {frdates.MONTHS_SHORT[d.month - 1]} {d.year}"


def _city(tz_name: str) -> str:
    return tz_name.rsplit("/", 1)[-1].replace("_", " ")


def when_text(event: Event, device_zone: str) -> str:
    """"lun. 26 oct. 2026, 17:00–18:00 (Zurich)", in the event's zone, + " · soit lun. 26 oct. 2026, 12:00–13:00
    (New York)" when the device's clock reads otherwise; a flight "… 10:00 (Paris) → 13:00 (New York)"; all-day
    "du … au …"; " · série" for a series. Same text as Android's `IcsImport.whenText`."""
    start, end, tz, end_tz = event.start, event.end, event.tz, event.end_tz
    if is_all_day(start):
        last = stamp_date(end) if end is not None else None
        text = _day(start) if last is None or last == start else f"du {_day(start)} au {_day(last)}"
    else:
        def end_text(at: datetime, origin: datetime) -> str:
            return (f"{_day(at.date())}, " if at.date() != origin.date() else "") + frdates.clock(at)

        text = f"{_day(start.date())}, {frdates.clock(start)}"
        if tz and end_tz and end is not None:
            text += f" ({_city(tz)}) → {end_text(end, start)} ({_city(end_tz)})"
        else:
            if end is not None:
                text += "–" + end_text(end, start)
            if tz:
                text += f" ({_city(tz)})"
        if tz and zones.is_valid(tz) and tz != device_zone:
            device = zones.zone(device_zone)
            here = zones.to_wall(zones.to_instant(start, zones.zone(tz)), device)
            if here != start:
                text += f" · soit {_day(here.date())}, {frdates.clock(here)}"
                if end is not None:
                    end_zone = zones.zone(end_tz if end_tz and zones.is_valid(end_tz) else tz)
                    text += "–" + end_text(zones.to_wall(zones.to_instant(end, end_zone), device), here)
                text += f" ({_city(device_zone)})"
    if event.rrule:
        text += " · série"
    return text


def read_sources(sources: list[IcsSource], device_zone: str, now: int | None = None) -> list[ParsedCalendar]:
    """All calendars of the picked files (zips expanded), parsed, nothing written."""
    out = []
    for src in expand_sources(sources):
        out.extend(parse_calendar(src, device_zone, now))
    return out


def suggested_calendar(p: ParsedCalendar, settings: CalendarSettings) -> str | None:
    """Where `p` goes when the user does not choose: the default calendar (a mail invitation is not a calendar);
    for a calendar export, the existing calendar of the same name, else None (a new one named after it)."""
    if p.preview is not None and p.preview.calendar_export:
        match = next((c for c in settings.calendars if fmt.fold(c.name) == fmt.fold(p.name)), None)
        return match.id if match else None
    default = settings.default_calendar().id
    return default if any(c.id == default for c in settings.calendars) else None


def preview(parsed: list[ParsedCalendar], existing: list[Event], settings: CalendarSettings) -> list[CalendarPreview]:
    """Fills `suggested_calendar` (`suggested_calendar()`) and `duplicates` (same UID already there)."""
    out = []
    for p in parsed:
        pv = p.preview
        target = suggested_calendar(p, settings)
        pv.suggested_calendar = target
        pv.duplicates = 0
        if target is not None:
            uids = {e.uid for e in existing if e.uid and settings.calendar(e.calendar).id == target and not e.recurrence_of}
            pv.duplicates = sum(1 for e in p.events if e.uid in uids)
        out.append(pv)
    return out


# ---- writing ----

@dataclass
class ImportTarget:
    calendar_id: str | None = None   # an existing calendar; None = a new one
    name: str | None = None          # new calendar: its name (default: X-WR-CALNAME, else the file's name)
    color: str | None = None         # new calendar: its colour (default: the next free palette colour)
    skip: bool = False               # do not import this calendar


@dataclass
class ImportOptions:
    # by index in the parsed list; missing: suggested_calendar() (a new calendar when None)
    targets: dict[int, ImportTarget] = field(default_factory=dict)
    since: date | None = None        # "depuis le …": events ending before are left out
    duplicates: str = "update"       # "skip" | "update" | "keep" (matched by UID in the target calendar)


@dataclass
class CalendarReport:
    name: str
    calendar_id: str
    created: int = 0
    series: int = 0
    exceptions: int = 0
    updated: int = 0
    skipped_duplicates: int = 0
    before_cutoff: int = 0
    cancelled: int = 0
    ignored: int = 0
    unresolved_zones: int = 0
    errors: list[str] = field(default_factory=list)


@dataclass
class ImportReport:
    import_id: str
    calendars: list[CalendarReport] = field(default_factory=list)
    new_calendars: list[str] = field(default_factory=list)

    @property
    def created(self) -> int:
        return sum(c.created for c in self.calendars)

    @property
    def ignored(self) -> int:
        return sum(c.ignored + c.cancelled + len(c.errors) for c in self.calendars)

    def summary(self) -> str:
        """"812 événements, 37 séries, 3 ignorés" (+ updates and skipped duplicates when any)."""
        def plural(n: int, one: str, many: str) -> str:
            return f"{n} {one if n <= 1 else many}"
        parts = [plural(self.created, "événement", "événements"),
                 plural(sum(c.series for c in self.calendars), "série", "séries")]
        updated = sum(c.updated for c in self.calendars)
        if updated:
            parts.append(plural(updated, "mis à jour", "mis à jour"))
        dup = sum(c.skipped_duplicates for c in self.calendars)
        if dup:
            parts.append(plural(dup, "doublon ignoré", "doublons ignorés"))
        parts.append(plural(self.ignored, "ignoré", "ignorés"))
        return ", ".join(parts)


def new_import_id(now: int | None = None) -> str:
    now = now_ms() if now is None else now
    stamp = datetime.fromtimestamp(now / 1000, UTC).strftime("%Y%m%dT%H%M%S")
    return f"{stamp}-{fmt.generate_id()}"


def _has_occurrence_since(event: Event, since: date, device_zone: str) -> bool:
    last = event.end if event.end is not None else event.start
    if not event.rrule:
        return stamp_date(last) >= since
    span = stamp_date(last) - stamp_date(event.start)
    try:
        for steps, stamp in enumerate(series_starts(event, device_zone)):
            if stamp_date(stamp) + span >= since or steps > MAX_ITERATIONS:
                return True
    except (ValueError, TypeError, OverflowError):
        return True
    return False


def apply_import(store: AgendaStore, parsed: list[ParsedCalendar], options: ImportOptions, existing: list[Event],
                 settings: CalendarSettings, device_zone: str, import_id: str | None = None,
                 now: int | None = None, taken_ids: frozenset = frozenset(),
                 progress: Callable[[int, int], None] | None = None) -> ImportReport:
    """Writes the files of the import and `calendars.json` (when calendars are created). `existing`: the events
    of `agenda/` now (duplicates, series of overrides). Returns the report; `settings` is updated in place."""
    now = now_ms() if now is None else now
    import_id = import_id or new_import_id(now)
    if options.duplicates not in DUPLICATES:
        raise ValueError(f"politique de doublons inconnue : {options.duplicates}")
    report = ImportReport(import_id)
    original = CalendarSettings(list(settings.calendars), settings.default)
    total = sum(len(p.events) + len(p.overrides) for p in parsed)
    done = 0
    by_id = {e.id: e for e in existing}

    for index, p in enumerate(parsed):
        target = options.targets.get(index) or ImportTarget(calendar_id=suggested_calendar(p, original))
        if target.skip:
            continue
        if target.calendar_id and any(c.id == target.calendar_id for c in settings.calendars):
            cal_id = target.calendar_id
        else:
            name = (target.name or p.name).strip() or p.name
            cal = Calendar(unique_calendar_id(name, settings), name, target.color or next_color(settings), True, import_id)
            settings.calendars.append(cal)
            report.new_calendars.append(cal.id)
            cal_id = cal.id
        pv = p.preview or CalendarPreview(p.name, p.source)
        rep = CalendarReport(p.name, cal_id, cancelled=pv.cancelled, ignored=pv.ignored,
                             unresolved_zones=pv.unresolved_zones, errors=list(pv.errors))
        report.calendars.append(rep)
        in_target = [e for e in by_id.values() if settings.calendar(e.calendar).id == cal_id]
        existing_by_key = {(e.uid, e.recurrence_at): e for e in in_target if e.uid}
        series_by_uid: dict[str, Event] = {}

        def with_defaults(event: Event) -> Event:
            # its VALARMs plus the default reminders ("Rappels par défaut"), like an event made in the app
            return event.copy(alerts=sorted(set(settings.default_alerts(event.all_day)) | set(event.alerts)))

        def write(event: Event) -> Event:
            saved = store.save(event, taken_ids)
            by_id[saved.id] = saved
            return saved

        for event in p.events:
            done += 1
            if options.since is not None and not _has_occurrence_since(event, options.since, device_zone):
                rep.before_cutoff += 1
                continue
            event = event.copy(calendar=cal_id)
            old = existing_by_key.get((event.uid, None)) if event.uid else None
            if old is not None and options.duplicates == "skip":
                rep.skipped_duplicates += 1
                if event.rrule:
                    series_by_uid[event.uid] = old
            elif old is not None and options.duplicates == "update":
                keep_body = old.ical_hash is not None and old.ical_hash == event.ical_hash
                merged = event.copy(
                    note=old.note.copy(title=event.title, body=old.body if keep_body else event.body, updated=now),
                    calendar=old.calendar, import_id=old.import_id, alerts=old.alerts)
                saved = write(merged)
                rep.updated += 1
                if saved.rrule:
                    series_by_uid[saved.uid] = saved
            else:
                saved = write(with_defaults(event).copy(import_id=import_id))
                rep.created += 1
                rep.series += 1 if saved.rrule else 0
                if saved.rrule and saved.uid and saved.uid not in series_by_uid:
                    series_by_uid[saved.uid] = saved
            if progress:
                progress(done, total)

        for ov in p.overrides:
            done += 1
            series = series_by_uid.get(ov.uid) if ov.uid else None
            if series is None and ov.uid:
                series = next((e for e in in_target if e.uid == ov.uid and e.rrule and not e.recurrence_of), None)
            if series is None:
                if ov.event is not None:  # orphan override: a plain event
                    if options.since is None or _has_occurrence_since(ov.event, options.since, device_zone):
                        write(with_defaults(ov.event).copy(calendar=cal_id, import_id=import_id))
                        rep.created += 1
                    else:
                        rep.before_cutoff += 1
                continue
            series = by_id.get(series.id, series)
            at = to_event_stamp(ov.recurrence_id, series.all_day, series.tz, device_zone)
            if at not in series.exdate:
                series = write(series.copy(exdate=series.exdate + [at]))
                series_by_uid[ov.uid] = series
            if ov.event is None:
                continue
            exc = ov.event.copy(calendar=series.calendar, recurrence_of=series.id, recurrence_at=at)
            if options.since is not None and not _has_occurrence_since(exc, options.since, device_zone):
                rep.before_cutoff += 1
                continue
            old = existing_by_key.get((ov.uid, at))
            if old is not None and options.duplicates == "skip":
                rep.skipped_duplicates += 1
            elif old is not None and options.duplicates == "update":
                keep_body = old.ical_hash is not None and old.ical_hash == exc.ical_hash
                write(exc.copy(note=old.note.copy(title=exc.title, body=old.body if keep_body else exc.body, updated=now),
                               import_id=old.import_id, recurrence_of=series.id, alerts=old.alerts))
                rep.updated += 1
            else:
                write(with_defaults(exc).copy(import_id=import_id))
                rep.exceptions += 1
                rep.created += 1
            if progress:
                progress(done, total)

    if report.new_calendars:
        store.save_calendars(settings)
    return report


@dataclass
class UndoReport:
    deleted: int = 0
    exdates_removed: int = 0
    calendars_removed: list[str] = field(default_factory=list)


def undo_import(store: AgendaStore, import_id: str, events: list[Event], settings: CalendarSettings) -> UndoReport:
    """"Annuler l'import": deletes the files carrying `import: <id>`, removes from the surviving series the
    exdates of the deleted exception files, and the calendars the import created when nothing uses them."""
    report = UndoReport()
    doomed = [e for e in events if e.import_id == import_id]
    doomed_ids = {e.id for e in doomed}
    survivors = {e.id: e for e in events if e.id not in doomed_ids}
    for e in doomed:
        store.delete(e)
        report.deleted += 1
    for e in doomed:
        if not e.recurrence_of or e.recurrence_at is None:
            continue
        series = survivors.get(e.recurrence_of)
        if series is None or e.recurrence_at not in series.exdate:
            continue
        still = any(o.recurrence_of == series.id and o.recurrence_at == e.recurrence_at for o in survivors.values())
        if still:
            continue
        series = store.save(series.copy(exdate=[x for x in series.exdate if x != e.recurrence_at]))
        survivors[series.id] = series
        report.exdates_removed += 1
    used = {settings.calendar(e.calendar).id for e in survivors.values()}
    keep = [c for c in settings.calendars if c.import_id != import_id or c.id in used]
    if len(keep) != len(settings.calendars):
        report.calendars_removed = [c.id for c in settings.calendars if c not in keep]
        settings.calendars = keep or settings.calendars[:1]
        if settings.default in report.calendars_removed:
            settings.default = settings.calendars[0].id
        store.save_calendars(settings)
    return report
