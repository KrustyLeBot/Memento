"""Time zones (README, "Agenda", time zones): IANA names, wall time <-> instant, ICS zone names.

- Wall time -> instant: a time in a daylight-saving gap moves forward by the length of the gap (02:30 the
  night the clocks jump becomes 03:30), a repeated time takes its first instant: java.time's rules, which are
  also RFC 5545's and `zoneinfo`'s with fold=0.
- `resolve_tzid`: an ICS `TZID` -> IANA: an IANA name as is, an Outlook/Windows name through a CLDR table,
  a prefixed name (`/citadel.org/…/Europe/Paris`) by its tail, else the embedded VTIMEZONE recognised from its
  offsets and transition months. None when nothing matches (the import falls back to the device zone).
- `vtimezone_lines`: the VTIMEZONE block written on export (rules of a reference year, as yearly RRULEs).
"""
from __future__ import annotations

import calendar as _cal
from datetime import date, datetime, timedelta, timezone
from functools import lru_cache
from zoneinfo import ZoneInfo, available_timezones

UTC = timezone.utc

# CLDR windowsZones.xml, territory "001", trimmed to the zones still in use. Values are canonical IANA names
# present in every tz database the apps ship with (tested on both platforms). Order matters for the
# recognition of embedded VTIMEZONEs: the first zone with the same rules wins.
WINDOWS_ZONES: dict[str, str] = {
    "Romance Standard Time": "Europe/Paris",
    "W. Europe Standard Time": "Europe/Berlin",
    "Central Europe Standard Time": "Europe/Budapest",
    "Central European Standard Time": "Europe/Warsaw",
    "GMT Standard Time": "Europe/London",
    "Greenwich Standard Time": "Atlantic/Reykjavik",
    "UTC": "Etc/UTC",
    "GTB Standard Time": "Europe/Bucharest",
    "FLE Standard Time": "Europe/Helsinki",
    "E. Europe Standard Time": "Europe/Chisinau",
    "Kaliningrad Standard Time": "Europe/Kaliningrad",
    "Belarus Standard Time": "Europe/Minsk",
    "Russian Standard Time": "Europe/Moscow",
    "Russia Time Zone 3": "Europe/Samara",
    "Turkey Standard Time": "Europe/Istanbul",
    "Israel Standard Time": "Asia/Jerusalem",
    "Middle East Standard Time": "Asia/Beirut",
    "Jordan Standard Time": "Asia/Amman",
    "Syria Standard Time": "Asia/Damascus",
    "Egypt Standard Time": "Africa/Cairo",
    "Morocco Standard Time": "Africa/Casablanca",
    "W. Central Africa Standard Time": "Africa/Lagos",
    "South Africa Standard Time": "Africa/Johannesburg",
    "E. Africa Standard Time": "Africa/Nairobi",
    "Libya Standard Time": "Africa/Tripoli",
    "Sudan Standard Time": "Africa/Khartoum",
    "Namibia Standard Time": "Africa/Windhoek",
    "Sao Tome Standard Time": "Africa/Sao_Tome",
    "Azores Standard Time": "Atlantic/Azores",
    "Cape Verde Standard Time": "Atlantic/Cape_Verde",
    "Arabic Standard Time": "Asia/Baghdad",
    "Arab Standard Time": "Asia/Riyadh",
    "Iran Standard Time": "Asia/Tehran",
    "Arabian Standard Time": "Asia/Dubai",
    "Azerbaijan Standard Time": "Asia/Baku",
    "Georgian Standard Time": "Asia/Tbilisi",
    "Caucasus Standard Time": "Asia/Yerevan",
    "Afghanistan Standard Time": "Asia/Kabul",
    "Pakistan Standard Time": "Asia/Karachi",
    "West Asia Standard Time": "Asia/Tashkent",
    "Ekaterinburg Standard Time": "Asia/Yekaterinburg",
    "India Standard Time": "Asia/Kolkata",
    "Sri Lanka Standard Time": "Asia/Colombo",
    "Nepal Standard Time": "Asia/Kathmandu",
    "Central Asia Standard Time": "Asia/Almaty",
    "Bangladesh Standard Time": "Asia/Dhaka",
    "Myanmar Standard Time": "Asia/Yangon",
    "N. Central Asia Standard Time": "Asia/Novosibirsk",
    "SE Asia Standard Time": "Asia/Bangkok",
    "North Asia Standard Time": "Asia/Krasnoyarsk",
    "China Standard Time": "Asia/Shanghai",
    "North Asia East Standard Time": "Asia/Irkutsk",
    "Singapore Standard Time": "Asia/Singapore",
    "W. Australia Standard Time": "Australia/Perth",
    "Taipei Standard Time": "Asia/Taipei",
    "Tokyo Standard Time": "Asia/Tokyo",
    "Korea Standard Time": "Asia/Seoul",
    "Yakutsk Standard Time": "Asia/Yakutsk",
    "Cen. Australia Standard Time": "Australia/Adelaide",
    "AUS Central Standard Time": "Australia/Darwin",
    "E. Australia Standard Time": "Australia/Brisbane",
    "AUS Eastern Standard Time": "Australia/Sydney",
    "West Pacific Standard Time": "Pacific/Port_Moresby",
    "Tasmania Standard Time": "Australia/Hobart",
    "Vladivostok Standard Time": "Asia/Vladivostok",
    "New Zealand Standard Time": "Pacific/Auckland",
    "Fiji Standard Time": "Pacific/Fiji",
    "Tonga Standard Time": "Pacific/Tongatapu",
    "Samoa Standard Time": "Pacific/Apia",
    "Line Islands Standard Time": "Pacific/Kiritimati",
    "Mauritius Standard Time": "Indian/Mauritius",
    "UTC+12": "Etc/GMT-12",
    "UTC+13": "Etc/GMT-13",
    "Dateline Standard Time": "Etc/GMT+12",
    "UTC-11": "Etc/GMT+11",
    "UTC-09": "Etc/GMT+9",
    "UTC-08": "Etc/GMT+8",
    "UTC-02": "Etc/GMT+2",
    "Hawaiian Standard Time": "Pacific/Honolulu",
    "Aleutian Standard Time": "America/Adak",
    "Alaskan Standard Time": "America/Anchorage",
    "Pacific Standard Time": "America/Los_Angeles",
    "Pacific Standard Time (Mexico)": "America/Tijuana",
    "US Mountain Standard Time": "America/Phoenix",
    "Mountain Standard Time": "America/Denver",
    "Mountain Standard Time (Mexico)": "America/Mazatlan",
    "Yukon Standard Time": "America/Whitehorse",
    "Central Standard Time": "America/Chicago",
    "Central Standard Time (Mexico)": "America/Mexico_City",
    "Canada Central Standard Time": "America/Regina",
    "Central America Standard Time": "America/Guatemala",
    "Easter Island Standard Time": "Pacific/Easter",
    "Eastern Standard Time": "America/New_York",
    "US Eastern Standard Time": "America/Indiana/Indianapolis",
    "Eastern Standard Time (Mexico)": "America/Cancun",
    "SA Pacific Standard Time": "America/Bogota",
    "Haiti Standard Time": "America/Port-au-Prince",
    "Cuba Standard Time": "America/Havana",
    "Turks And Caicos Standard Time": "America/Grand_Turk",
    "Venezuela Standard Time": "America/Caracas",
    "Paraguay Standard Time": "America/Asuncion",
    "Atlantic Standard Time": "America/Halifax",
    "Central Brazilian Standard Time": "America/Cuiaba",
    "SA Western Standard Time": "America/La_Paz",
    "Pacific SA Standard Time": "America/Santiago",
    "Newfoundland Standard Time": "America/St_Johns",
    "Tocantins Standard Time": "America/Araguaina",
    "E. South America Standard Time": "America/Sao_Paulo",
    "SA Eastern Standard Time": "America/Cayenne",
    "Argentina Standard Time": "America/Argentina/Buenos_Aires",
    "Montevideo Standard Time": "America/Montevideo",
    "Magallanes Standard Time": "America/Punta_Arenas",
    "Saint Pierre Standard Time": "America/Miquelon",
    "Bahia Standard Time": "America/Bahia",
}
_WINDOWS_LOWER = {k.lower(): v for k, v in WINDOWS_ZONES.items()}
REFERENCE_YEAR = 2026  # rules compared when recognising an embedded VTIMEZONE


@lru_cache(maxsize=1)
def _available() -> frozenset[str]:
    return frozenset(available_timezones())


def is_valid(name: str | None) -> bool:
    return bool(name) and name in _available()


@lru_cache(maxsize=512)
def zone(name: str) -> ZoneInfo:
    return ZoneInfo(name)


def zone_or(name: str | None, fallback: str) -> ZoneInfo:
    """The event's zone, or the fallback (device zone) for a floating event or an unknown name."""
    return zone(name) if is_valid(name) else zone(fallback)


def end_zone_or(event, fallback: str) -> ZoneInfo:
    """The zone of the event's end: its `end_tz` when known (timed events with `tz` only), else the start's."""
    if not event.all_day and event.tz and is_valid(event.end_tz):
        return zone(event.end_tz)
    return zone_or(event.tz, fallback)


def to_instant(wall: datetime, tz: ZoneInfo) -> datetime:
    """Wall time of `tz` -> aware UTC instant (gap: moved forward by the gap; fold: the first instant)."""
    return wall.replace(tzinfo=tz, fold=0).astimezone(UTC)


def to_wall(instant: datetime, tz: ZoneInfo) -> datetime:
    return instant.astimezone(tz).replace(tzinfo=None, fold=0)


def day_start(d: date, tz: ZoneInfo) -> datetime:
    return to_instant(datetime(d.year, d.month, d.day), tz)


def offset_minutes(tz: ZoneInfo, instant: datetime) -> int:
    return int(instant.astimezone(tz).utcoffset().total_seconds() // 60)


# ---- transitions of a year (shared by recognition and VTIMEZONE output) ----

def transitions(tz: ZoneInfo, year: int) -> list[tuple[datetime, int, int]]:
    """(instant, offset before, offset after) of each offset change in the year, to the minute."""
    out = []
    t = datetime(year, 1, 1, tzinfo=UTC)
    end = datetime(year + 1, 1, 1, tzinfo=UTC)
    prev = offset_minutes(tz, t)
    while t < end:
        nxt = t + timedelta(days=1)
        off = offset_minutes(tz, nxt)
        if off != prev:
            lo, hi = t, nxt  # offset(lo) == prev, offset(hi) == off
            while hi - lo > timedelta(minutes=1):
                mid = lo + timedelta(minutes=((hi - lo).total_seconds() // 60) // 2)
                if offset_minutes(tz, mid) == prev:
                    lo = mid
                else:
                    hi = mid
            out.append((hi, prev, off))
            prev = off
        t = nxt
    return out


def _signature(tz: ZoneInfo, year: int):
    trans = transitions(tz, year)
    if not trans:
        return (offset_minutes(tz, datetime(year, 1, 1, tzinfo=UTC)), None, None, None)
    if len(trans) != 2:
        return None
    std = min(trans[0][2], trans[1][2])
    dst = max(trans[0][2], trans[1][2])
    to_dst = next(t for t in trans if t[2] == dst)
    to_std = next(t for t in trans if t[2] == std)
    return (std, dst, to_dst[0].month, to_std[0].month)


def recognise(std: int, dst: int | None, dst_month: int | None, std_month: int | None) -> str | None:
    """The first zone of the table whose rules of the reference year match an embedded VTIMEZONE."""
    if dst is None:
        if std % 60 == 0 and abs(std) <= 14 * 60:
            hours = -std // 60
            return "Etc/UTC" if hours == 0 else f"Etc/GMT{'+' if hours > 0 else '-'}{abs(hours)}"
        return None
    for name in dict.fromkeys(WINDOWS_ZONES.values()):
        if not is_valid(name):
            continue
        sig = _signature(zone(name), REFERENCE_YEAR)
        if sig == (std, dst, dst_month, std_month):
            return name
    return None


def resolve_tzid(tzid: str, vtimezone: dict | None = None) -> str | None:
    """ICS TZID -> IANA name, or None. `vtimezone`: {"std": min, "dst": min|None, "dst_month", "std_month"}."""
    name = tzid.strip().strip('"')
    if is_valid(name):
        return name
    hit = _WINDOWS_LOWER.get(name.lower())
    if hit:
        return hit
    parts = [p for p in name.split("/") if p]
    for n in (3, 2):
        if len(parts) > n:
            tail = "/".join(parts[-n:])
            if is_valid(tail):
                return tail
    if vtimezone is not None and vtimezone.get("std") is not None:
        return recognise(vtimezone["std"], vtimezone.get("dst"), vtimezone.get("dst_month"), vtimezone.get("std_month"))
    return None


# ---- VTIMEZONE output ----

def _fmt_offset(minutes: int) -> str:
    sign = "+" if minutes >= 0 else "-"
    m = abs(minutes)
    return f"{sign}{m // 60:02d}{m % 60:02d}"


def _byday(d: date) -> tuple[int, str]:
    """(n, weekday) of d in its month: n = 1..4, or -1 for the last such weekday."""
    days = ["MO", "TU", "WE", "TH", "FR", "SA", "SU"]
    last = _cal.monthrange(d.year, d.month)[1]
    n = -1 if d.day + 7 > last else (d.day - 1) // 7 + 1
    return n, days[d.weekday()]


def _nth_weekday(year: int, month: int, n: int, weekday: int) -> date:
    if n > 0:
        first = date(year, month, 1)
        return first + timedelta(days=(weekday - first.weekday()) % 7 + 7 * (n - 1))
    last = date(year, month, _cal.monthrange(year, month)[1])
    return last - timedelta(days=(last.weekday() - weekday) % 7)


def vtimezone_lines(name: str, year: int) -> list[str]:
    """VTIMEZONE of an IANA zone: the yearly rules of `year` (DTSTART in 1970), or a fixed offset."""
    tz = zone(name)
    trans = transitions(tz, year)
    out = ["BEGIN:VTIMEZONE", f"TZID:{name}"]
    if len(trans) != 2:
        off = offset_minutes(tz, datetime(year, 1, 1, tzinfo=UTC))
        out += ["BEGIN:STANDARD", f"TZOFFSETFROM:{_fmt_offset(off)}", f"TZOFFSETTO:{_fmt_offset(off)}",
                "DTSTART:19700101T000000", "END:STANDARD", "END:VTIMEZONE"]
        return out
    dst_off = max(trans[0][2], trans[1][2])
    for instant, before, after in sorted(trans, key=lambda t: 0 if t[2] == dst_off else 1):
        kind = "DAYLIGHT" if after == dst_off else "STANDARD"
        local = (instant + timedelta(minutes=before)).replace(tzinfo=None)
        n, wd = _byday(local.date())
        d1970 = _nth_weekday(1970, local.month, n, local.weekday())
        out += [f"BEGIN:{kind}", f"TZOFFSETFROM:{_fmt_offset(before)}", f"TZOFFSETTO:{_fmt_offset(after)}",
                f"DTSTART:{d1970:%Y%m%d}T{local:%H%M%S}",
                f"RRULE:FREQ=YEARLY;BYMONTH={local.month};BYDAY={n}{wd}", f"END:{kind}"]
    out.append("END:VTIMEZONE")
    return out
