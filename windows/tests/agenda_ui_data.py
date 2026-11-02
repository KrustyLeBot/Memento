"""Synthetic agenda for the UI tests and screenshots (invented people and places only), relative to a given day."""
from __future__ import annotations

from datetime import date, datetime, time, timedelta, timezone

from memento_win.core import contacts as ct
from memento_win.core import note as fmt
from memento_win.core.activity import ActivityInfo
from memento_win.core.agenda.calendars import Calendar, CalendarSettings
from memento_win.core.agenda.model import Event, event_to_note, new_event
from memento_win.core.note import Note
from memento_win.core.track import Sport

ZONE = "Europe/Paris"
NOW_MS = 1_793_000_000_000


def at(d: date, hour: int, minute: int = 0) -> datetime:
    return datetime.combine(d, time(hour, minute))


def populate(store, today: date) -> dict[str, Event]:
    """Calendars, a series with an edited and a skipped occurrence, all-day and multi-day events, overlaps, a
    busy day, a dated note, an activity and a birthday. Returns the events by short name."""
    agenda = store.agenda
    agenda.save_calendars(CalendarSettings(
        calendars=[Calendar("perso", "Perso", "#4F8BF5"), Calendar("travail", "Travail", "#B0851A"),
                   Calendar("famille", "Famille", "#C2549C")], default="perso"))
    monday = today - timedelta(days=today.weekday())
    out: dict[str, Event] = {}

    def add(key: str, title: str, start, end=None, **kw) -> Event:
        kw.setdefault("tz", ZONE if isinstance(start, datetime) else None)
        ev = agenda.save(new_event(title, start, end, now=NOW_MS, **kw))
        out[key] = ev
        return ev

    add("dentist", "Dentiste", at(today, 14, 30), at(today, 15, 15), calendar="perso", location="12 rue des Lilas, Lyon",
        alerts=[-60], body="Apporter la carte vitale.")
    add("lunch", "Déjeuner avec Paul", at(today, 12, 30), at(today, 14, 0), calendar="perso")
    add("bank", "Appel banque", at(today, 13, 0), at(today, 13, 30), calendar="travail")
    series = add("team", "Réunion d'équipe", at(monday - timedelta(days=21), 10), at(monday - timedelta(days=21), 11),
                 calendar="travail", rrule="FREQ=WEEKLY;BYDAY=MO,TH", location="Salle Jura")
    moved_from = at(monday + timedelta(days=3), 10)
    exception = agenda.make_exception(series, moved_from, at(monday + timedelta(days=3), 11))
    exception = exception.copy(start=at(monday + timedelta(days=3), 15), end=at(monday + timedelta(days=3), 16, 30),
                               note=exception.note.copy(title="Réunion d'équipe (déplacée)"))
    series, exception = agenda.save_exception(series, exception)
    series = agenda.delete_occurrence(series, at(monday + timedelta(days=7), 10))
    out["team"], out["team_moved"] = series, exception
    add("standup", "Point du matin", at(monday - timedelta(days=7), 9), at(monday - timedelta(days=7), 9, 15),
        calendar="travail", rrule="FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR")
    add("holiday", "Vacances à la montagne", today + timedelta(days=5), today + timedelta(days=9), calendar="famille")
    add("anniversary", "Anniversaire de mariage", date(2015, today.month, min(today.day + 1, 28)), None,
        calendar="famille", rrule="FREQ=YEARLY")
    add("concert", "Concert au Transbordeur", at(today + timedelta(days=1), 21), at(today + timedelta(days=2), 0, 30),
        calendar="perso")
    add("congress", "Congrès (Annecy)", at(today + timedelta(days=3), 9), at(today + timedelta(days=4), 17),
        calendar="travail")
    busy = today + timedelta(days=2)
    for i, (title, cal) in enumerate([("Garage", "perso"), ("Pharmacie", "perso"), ("Revue de code", "travail"),
                                      ("Cours de piano", "famille"), ("Courses", "perso")]):
        add(f"busy{i}", title, at(busy, 8 + 2 * i), at(busy, 9 + 2 * i), calendar=cal)

    note = Note(path="", id=fmt.generate_id(), title="Préparer la réunion", body="- [x] Ordre du jour\n- [ ] Chiffres\n- [ ] Salle",
                created=NOW_MS, updated=NOW_MS)
    planned = Event(note=note, start=at(today + timedelta(days=1), 8, 30), alerts=[-15])
    store.save(event_to_note(planned))

    ride = int(datetime.combine(today - timedelta(days=1), time(16, 0), tzinfo=timezone.utc).timestamp() * 1000)
    store.save(Note(path=f"activities/{fmt.file_name_for('Sortie vélo', 'act001')}", id="act001", title="Sortie vélo",
                    created=NOW_MS, updated=NOW_MS, activity=ActivityInfo(Sport.BIKE, ride, 3900, 3600, 25300.0)))

    bday = today + timedelta(days=4)
    store.contacts.save(ct.Contact(first_name="Camille", last_name="Martin",
                                   birthday=ct.Birthday(1992, bday.month, bday.day)))
    return out
