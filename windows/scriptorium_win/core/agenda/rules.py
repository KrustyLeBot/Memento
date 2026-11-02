"""The "Répétition" field of the event editor: presets, the custom rule editor's model, and their RRULE text.

A rule the editor can express is a `RuleSpec` (every N days / weeks / months / years, weekdays, day of the
month or n-th weekday including "dernier", end never / until a date / after N occurrences). Anything else
(BYHOUR, several BYMONTHDAY, WKST that changes the meaning…) is "imported": shown read-only and written back
untouched. A rule is only regenerated when the user changes it, so an untouched imported rule never moves.

UNTIL is written the way RFC 5545 wants it for the start's form: a date for all-day events, UTC (`…Z`) at
23:59:59 of the last day in the event's zone for zoned events, a floating local time for floating ones.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date, datetime, timedelta, timezone
from itertools import islice

from ..note import Note
from . import zones
from .frdates import MONTHS, ORDINALS, WEEKDAYS, day_number, short_date
from .model import Event, stamp_date
from .recurrence import series_starts

FREQS = ("DAILY", "WEEKLY", "MONTHLY", "YEARLY")
DAY_CODES = ["MO", "TU", "WE", "TH", "FR", "SA", "SU"]
_ALLOWED = {"FREQ", "INTERVAL", "BYDAY", "BYMONTHDAY", "BYSETPOS", "BYMONTH", "UNTIL", "COUNT", "WKST"}
UTC = timezone.utc


@dataclass
class RuleSpec:
    freq: str = "WEEKLY"
    interval: int = 1
    weekdays: list[int] = field(default_factory=list)  # WEEKLY, 0 = Monday, sorted
    monthly: str = "day"            # MONTHLY: "day" (BYMONTHDAY) or "nth" (BYDAY=2TH, -1TH)
    month_day: int = 1              # MONTHLY "day": 1..31
    nth: int = 1                    # MONTHLY "nth": 1..5 or -1 ("dernier")
    nth_weekday: int = 0
    until: date | None = None
    count: int | None = None


def nth_of(d: date) -> int:
    """The rank of d's weekday in its month (1..5)."""
    return (d.day - 1) // 7 + 1


def is_last_of_month(d: date) -> bool:
    return (d + timedelta(days=7)).month != d.month


def default_spec(freq: str, start: date) -> RuleSpec:
    """What the custom editor shows when the user switches to `freq` from nothing."""
    d = stamp_date(start)
    return RuleSpec(freq=freq, weekdays=[d.weekday()], month_day=d.day, nth=nth_of(d) if nth_of(d) < 5 else -1,
                    nth_weekday=d.weekday())


def _parts(rrule: str) -> dict[str, str] | None:
    text = rrule.strip()
    if text.upper().startswith("RRULE:"):
        text = text[6:]
    out: dict[str, str] = {}
    for part in text.split(";"):
        if not part.strip():
            continue
        key, sep, value = part.partition("=")
        key, value = key.strip().upper(), value.strip().upper()
        if not sep or key in out or key not in _ALLOWED:
            return None
        out[key] = value
    return out


def _until_date(value: str, tz) -> date | None:
    try:
        if len(value) == 8:
            return date(int(value[:4]), int(value[4:6]), int(value[6:8]))
        if len(value) in (15, 16) and value[8] == "T":
            wall = datetime(int(value[:4]), int(value[4:6]), int(value[6:8]),
                            int(value[9:11]), int(value[11:13]), int(value[13:15]))
            if value.endswith("Z"):
                return zones.to_wall(wall.replace(tzinfo=UTC), tz).date()
            return wall.date()
    except ValueError:
        return None
    return None


def _int(value: str) -> int | None:
    try:
        return int(value)
    except ValueError:
        return None


def parse_spec(rrule: str | None, start: date, tz_name: str | None, device_zone: str) -> RuleSpec | None:
    """The editor's model of the rule, or None when the editor cannot express it ("Règle importée")."""
    if not rrule:
        return None
    p = _parts(rrule)
    if p is None or p.get("FREQ") not in FREQS:
        return None
    d = stamp_date(start)
    spec = RuleSpec(freq=p["FREQ"])
    interval = _int(p.get("INTERVAL", "1"))
    if interval is None or interval < 1:
        return None
    spec.interval = interval
    if "UNTIL" in p and "COUNT" in p:
        return None
    if "COUNT" in p:
        spec.count = _int(p["COUNT"])
        if spec.count is None or spec.count < 1:
            return None
    if "UNTIL" in p:
        spec.until = _until_date(p["UNTIL"], zones.zone_or(tz_name, device_zone))
        if spec.until is None:
            return None
    if p.get("WKST", "MO") != "MO" and spec.freq == "WEEKLY" and interval > 1:
        return None  # the week start changes which weeks are "every other"
    byday, bymonthday, bysetpos, bymonth = p.get("BYDAY"), p.get("BYMONTHDAY"), p.get("BYSETPOS"), p.get("BYMONTH")
    if spec.freq == "DAILY":
        if byday or bymonthday or bysetpos or bymonth:
            return None
    elif spec.freq == "WEEKLY":
        if bymonthday or bysetpos or bymonth:
            return None
        if byday:
            codes = byday.split(",")
            if any(c not in DAY_CODES for c in codes) or len(set(codes)) != len(codes):
                return None
            spec.weekdays = sorted(DAY_CODES.index(c) for c in codes)
        else:
            spec.weekdays = [d.weekday()]
    elif spec.freq == "MONTHLY":
        if bymonth or (byday and bymonthday):
            return None
        if bymonthday:
            n = _int(bymonthday)
            if n is None or not 1 <= n <= 31 or bysetpos:
                return None
            spec.monthly, spec.month_day = "day", n
        elif byday:
            if "," in byday:
                return None
            code, rank = byday[-2:], byday[:-2]
            if code not in DAY_CODES:
                return None
            if rank and bysetpos:
                return None
            n = _int(rank) if rank else (_int(bysetpos) if bysetpos else None)
            if n is None or n not in (1, 2, 3, 4, 5, -1):
                return None
            spec.monthly, spec.nth, spec.nth_weekday = "nth", n, DAY_CODES.index(code)
        else:
            if bysetpos:
                return None
            spec.monthly, spec.month_day = "day", d.day
    else:  # YEARLY: only "the start's date" is expressible
        if byday or bysetpos:
            return None
        if bymonth and _int(bymonth) != d.month or bymonthday and _int(bymonthday) != d.day:
            return None
    if spec.freq != "WEEKLY":
        spec.weekdays = [d.weekday()]
    if spec.freq != "MONTHLY":
        spec.month_day, spec.nth, spec.nth_weekday = d.day, nth_of(d) if nth_of(d) < 5 else -1, d.weekday()
    return spec


def to_rrule(spec: RuleSpec, start: date, tz_name: str | None, device_zone: str) -> str:
    parts = [f"FREQ={spec.freq}"]
    if spec.interval > 1:
        parts.append(f"INTERVAL={spec.interval}")
    if spec.freq == "WEEKLY":
        days = sorted(set(spec.weekdays)) or [stamp_date(start).weekday()]
        parts.append("BYDAY=" + ",".join(DAY_CODES[i] for i in days))
    elif spec.freq == "MONTHLY":
        if spec.monthly == "nth":
            parts.append(f"BYDAY={spec.nth}{DAY_CODES[spec.nth_weekday]}")
        else:
            parts.append(f"BYMONTHDAY={spec.month_day}")
    if spec.count is not None:
        parts.append(f"COUNT={spec.count}")
    elif spec.until is not None:
        u = spec.until
        if not isinstance(start, datetime):
            parts.append(f"UNTIL={u:%Y%m%d}")
        elif tz_name and zones.is_valid(tz_name):
            instant = zones.to_instant(datetime(u.year, u.month, u.day, 23, 59, 59), zones.zone(tz_name))
            parts.append(f"UNTIL={instant:%Y%m%dT%H%M%S}Z")
        else:
            parts.append(f"UNTIL={u:%Y%m%d}T235959")
    return ";".join(parts)


# ---- presets ----

def presets(start: date) -> list[tuple[str, str]]:
    """(key, label) of the presets for this start, in menu order ("custom" is added by the UI)."""
    d = stamp_date(start)
    wd = WEEKDAYS[d.weekday()]
    out = [("none", "Jamais"), ("daily", "Tous les jours"), ("weekdays", "Du lundi au vendredi"),
           ("weekly", f"Toutes les semaines le {wd}"), ("biweekly", f"Toutes les 2 semaines le {wd}"),
           ("monthly_day", f"Tous les mois le {day_number(d)}")]
    if nth_of(d) < 5:
        out.append(("monthly_nth", f"Tous les mois le {ORDINALS[nth_of(d)]} {wd}"))
    if is_last_of_month(d):
        out.append(("monthly_last", f"Tous les mois le dernier {wd}"))
    out.append(("yearly", f"Tous les ans le {day_number(d)} {MONTHS[d.month - 1]}"))
    return out


def preset_spec(key: str, start: date) -> RuleSpec | None:
    d = stamp_date(start)
    base = default_spec("DAILY", d)
    if key == "daily":
        return base
    if key == "weekdays":
        return RuleSpec(**{**base.__dict__, "freq": "WEEKLY", "weekdays": [0, 1, 2, 3, 4]})
    if key == "weekly":
        return RuleSpec(**{**base.__dict__, "freq": "WEEKLY"})
    if key == "biweekly":
        return RuleSpec(**{**base.__dict__, "freq": "WEEKLY", "interval": 2})
    if key == "monthly_day":
        return RuleSpec(**{**base.__dict__, "freq": "MONTHLY", "monthly": "day"})
    if key == "monthly_nth":
        return RuleSpec(**{**base.__dict__, "freq": "MONTHLY", "monthly": "nth", "nth": nth_of(d)})
    if key == "monthly_last":
        return RuleSpec(**{**base.__dict__, "freq": "MONTHLY", "monthly": "nth", "nth": -1})
    if key == "yearly":
        return RuleSpec(**{**base.__dict__, "freq": "YEARLY"})
    return None


def preset_rule(key: str, start: date, tz_name: str | None, device_zone: str) -> str | None:
    spec = preset_spec(key, start)
    return to_rrule(spec, start, tz_name, device_zone) if spec else None


def _same(a: RuleSpec, b: RuleSpec) -> bool:
    if (a.freq, a.interval, a.until, a.count) != (b.freq, b.interval, b.until, b.count):
        return False
    if a.freq == "WEEKLY":
        return sorted(set(a.weekdays)) == sorted(set(b.weekdays))
    if a.freq == "MONTHLY":
        if a.monthly != b.monthly:
            return False
        return a.month_day == b.month_day if a.monthly == "day" else (a.nth, a.nth_weekday) == (b.nth, b.nth_weekday)
    return True


def preset_of(rrule: str | None, start: date, tz_name: str | None, device_zone: str) -> str:
    """The preset key of a rule, "custom" when the custom editor can express it, "imported" otherwise."""
    if not rrule:
        return "none"
    spec = parse_spec(rrule, start, tz_name, device_zone)
    if spec is None:
        return "imported"
    for key, _ in presets(start):
        other = preset_spec(key, start)
        if other is not None and _same(spec, other):
            return key
    return "custom"


# ---- wording ----

def _join(words: list[str]) -> str:
    return words[0] if len(words) == 1 else ", ".join(words[:-1]) + " et " + words[-1]


def describe(spec: RuleSpec, start: date, today: date | None = None) -> str:
    """"Toutes les 2 semaines le lundi et le jeudi, jusqu'au 31 déc. 2026"."""
    d = stamp_date(start)
    n = spec.interval
    if spec.freq == "DAILY":
        text = "Tous les jours" if n == 1 else f"Tous les {n} jours"
    elif spec.freq == "WEEKLY":
        days = sorted(set(spec.weekdays)) or [d.weekday()]
        if n == 1 and days == [0, 1, 2, 3, 4]:
            text = "Du lundi au vendredi"
        else:
            text = ("Toutes les semaines" if n == 1 else f"Toutes les {n} semaines") + " " + \
                   _join([f"le {WEEKDAYS[i]}" for i in days])
    elif spec.freq == "MONTHLY":
        text = "Tous les mois" if n == 1 else f"Tous les {n} mois"
        if spec.monthly == "nth":
            text += f" le {ORDINALS.get(spec.nth, str(spec.nth))} {WEEKDAYS[spec.nth_weekday]}"
        else:
            text += f" le {'1er' if spec.month_day == 1 else spec.month_day}"
    else:
        text = ("Tous les ans" if n == 1 else f"Tous les {n} ans") + f" le {day_number(d)} {MONTHS[d.month - 1]}"
    if spec.count is not None:
        text += f", {spec.count} fois"
    elif spec.until is not None:
        text += f", jusqu'au {short_date(spec.until, today)}"
    return text


def describe_rule(rrule: str | None, start: date, tz_name: str | None, device_zone: str,
                  today: date | None = None) -> str:
    if not rrule:
        return "Ne se répète pas"
    spec = parse_spec(rrule, start, tz_name, device_zone)
    return describe(spec, start, today) if spec is not None else f"Règle importée : {rrule}"


def next_starts(rrule: str, start: date, tz_name: str | None, device_zone: str, after: date,
                exdate: list[date] | None = None, count: int = 3) -> list[date]:
    """The first `count` occurrence starts on or after `after` ("Prochaines : …"). ValueError: bad rule."""
    probe = Event(note=Note(path="", id="preview"), start=start, tz=tz_name, rrule=rrule, exdate=list(exdate or []))
    skipped = set(exdate or [])
    skipped_days = {x for x in skipped if not isinstance(x, datetime)}

    def kept(stamp: date) -> bool:
        return stamp_date(stamp) >= after and stamp not in skipped and stamp_date(stamp) not in skipped_days

    # A bound on the walk: a rule whose occurrences all lie in the past ends on its own (COUNT/UNTIL);
    # an endless one yields in order, so the first matches after `after` come quickly.
    return list(islice((s for s in islice(series_starts(probe, device_zone), 50_000) if kept(s)), count))
