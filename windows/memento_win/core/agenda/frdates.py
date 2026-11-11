"""French wording of dates, times, durations and reminders, shared by every agenda screen (no Qt)."""
from __future__ import annotations

from datetime import date, datetime

from . import zones

MONTHS = ["janvier", "février", "mars", "avril", "mai", "juin", "juillet", "août", "septembre", "octobre",
          "novembre", "décembre"]
MONTHS_SHORT = ["janv.", "févr.", "mars", "avr.", "mai", "juin", "juil.", "août", "sept.", "oct.", "nov.", "déc."]
WEEKDAYS = ["lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi", "dimanche"]
WEEKDAYS_SHORT = ["lun.", "mar.", "mer.", "jeu.", "ven.", "sam.", "dim."]
ORDINALS = {1: "1er", 2: "2e", 3: "3e", 4: "4e", 5: "5e", -1: "dernier"}


def day_number(d: date) -> str:
    return "1er" if d.day == 1 else str(d.day)


def long_date(d: date, with_year: bool = True) -> str:
    """"lundi 2 novembre 2026"."""
    text = f"{WEEKDAYS[d.weekday()]} {day_number(d)} {MONTHS[d.month - 1]}"
    return f"{text} {d.year}" if with_year else text


def medium_date(d: date, today: date | None = None) -> str:
    """"lun. 2 nov." (the year only when it is not the current one)."""
    text = f"{WEEKDAYS_SHORT[d.weekday()]} {d.day} {MONTHS_SHORT[d.month - 1]}"
    return text if today is not None and d.year == today.year else f"{text} {d.year}"


def short_date(d: date, today: date | None = None) -> str:
    """"12 nov." or "12 nov. 2027"."""
    text = f"{d.day} {MONTHS_SHORT[d.month - 1]}"
    return text if today is not None and d.year == today.year else f"{text} {d.year}"


def month_title(d: date) -> str:
    return f"{MONTHS[d.month - 1]} {d.year}"


def week_title(first: date, last: date) -> str:
    """"2 – 8 nov. 2026", "30 nov. – 6 déc. 2026", "28 déc. 2026 – 3 janv. 2027"."""
    if first.year != last.year:
        return f"{short_date(first)} – {short_date(last)}"
    if first.month != last.month:
        return f"{first.day} {MONTHS_SHORT[first.month - 1]} – {last.day} {MONTHS_SHORT[last.month - 1]} {last.year}"
    return f"{first.day} – {last.day} {MONTHS_SHORT[last.month - 1]} {last.year}"


def clock(value: datetime, h24: bool = True) -> str:
    if h24:
        return f"{value.hour:02d}:{value.minute:02d}"
    hour = value.hour % 12 or 12
    return f"{hour}:{value.minute:02d} {'AM' if value.hour < 12 else 'PM'}"


def departure_arrival(start: date, end: date | None, tz: str | None, end_tz: str | None, h24: bool = True) -> str | None:
    """Detail card, an end in its own zone (a flight): "Départ 10:00 (Paris) → arrivée 13:00 (New York)", the
    arrival on another day "… → arrivée le 18 déc. à 08:00 (Paris)". `start`/`end`: the occurrence's walls (each
    in its own zone). None for any other event (same text as Android's `AgendaText.departureArrival`)."""
    if not tz or not end_tz or end_tz == tz or not zones.is_valid(end_tz):
        return None
    if not isinstance(start, datetime) or not isinstance(end, datetime):
        return None
    at = clock(end, h24)
    arrival = f"le {end.day} {MONTHS_SHORT[end.month - 1]} à {at}" if end.date() != start.date() else at
    return f"Départ {clock(start, h24)} ({_city(tz)}) → arrivée {arrival} ({_city(end_tz)})"


def _city(tz_name: str) -> str:
    return tz_name.rsplit("/", 1)[-1].replace("_", " ")


def day_span(first: date, last: date, today: date | None = None) -> str:
    """"Du 1 au 3 déc." for a multi-day entry."""
    if first.year == last.year and first.month == last.month:
        tail = short_date(last, today)
        return f"Du {first.day} au {tail}"
    return f"Du {short_date(first, today if first.year == last.year else None)} au {short_date(last, today)}"


def duration(minutes: int) -> str:
    """"15 min", "1 h", "1 h 30", "2 jours", "1 semaine"."""
    n = abs(minutes)
    if n and n % 10080 == 0:
        weeks = n // 10080
        return f"{weeks} semaine{'s' if weeks > 1 else ''}"
    if n and n % 1440 == 0:
        days = n // 1440
        return f"{days} jour{'s' if days > 1 else ''}"
    if n >= 60:
        return f"{n // 60} h" + (f" {n % 60:02d}" if n % 60 else "")
    return f"{n} min"


def alert_label(minutes: int) -> str:
    """-15 -> "15 min avant", 0 -> "Au début", 30 -> "30 min après"."""
    if minutes == 0:
        return "Au début"
    return f"{duration(minutes)} {'avant' if minutes < 0 else 'après'}"


def all_day_alert_label(minutes: int) -> str:
    """All-day reminder (minutes from 00:00 of the day): 540 -> "Le jour même à 09:00", -360 -> "La veille à 18:00",
    -9540 -> "1 semaine avant à 09:00" (as the phone's `AgendaText.alertLabel`)."""
    days, rest = divmod(minutes, 1440)
    at = f" à {rest // 60:02d}:{rest % 60:02d}"
    if days == 0:
        return f"Le jour même{at}"
    if days == -1:
        return f"La veille{at}"
    if days == -7:
        return f"1 semaine avant{at}"
    if days < 0:
        return f"{-days} jours avant{at}"
    return f"Le lendemain{at}" if days == 1 else f"{days} jours après{at}"
