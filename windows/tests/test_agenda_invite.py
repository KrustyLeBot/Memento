"""A mail invitation (`testdata/agenda/interview_0.ics`) vs a calendar export: default target, the preview's
event list, LOCATION placeholders, the meeting link of the description, and the links of the detail."""
from __future__ import annotations

import io
import zipfile
from datetime import datetime

from memento_win.core.agenda import links
from memento_win.core.agenda.calendars import Calendar, CalendarSettings
from memento_win.core.agenda.ics_import import (IcsSource, ImportOptions, apply_import, clean_location, preview,
                                                 read_sources, undo_import, when_text)
from memento_win.core.agenda.store import AgendaStore, MemoryFs

from agenda_vectors import DEVICE_ZONE, NOW, VECTORS

MEET = "https://meet.proton.me/join/id-EXAMPLE001#pwd-testvector42"


def settings_with_default() -> CalendarSettings:
    return CalendarSettings([Calendar("travail", "Travail", "#1E88E5"), Calendar("perso", "Perso", "#43A047")], "perso")


def interview(zone: str = DEVICE_ZONE):
    return read_sources([IcsSource.from_path(VECTORS / "interview_0.ics")], zone, NOW)


def test_invitation_goes_to_the_default_calendar():
    store = AgendaStore(MemoryFs())
    settings = settings_with_default()
    store.save_calendars(settings)
    parsed = interview()
    pv = preview(parsed, [], settings)[0]
    assert not pv.calendar_export and pv.suggested_calendar == "perso" and pv.events == 1
    e = parsed[0].events[0]
    assert e.title == "Interview with Example Corp"
    assert (e.tz, e.start, e.end) == ("Europe/Zurich", datetime(2026, 10, 26, 17), datetime(2026, 10, 26, 18))
    assert e.location is None
    assert e.body.startswith(f"Visio : {MEET}\n\nHi Camille,")
    assert e.body.count(MEET) == 2
    assert [(i.title, i.when_text, i.location) for i in pv.items] == [
        ("Interview with Example Corp", "lun. 26 oct. 2026, 17:00–18:00 (Zurich)", None)]

    report = apply_import(store, parsed, ImportOptions(), [], settings, DEVICE_ZONE, "inv", NOW)
    assert report.new_calendars == [] and [c.id for c in settings.calendars] == ["travail", "perso"]
    events = store.scan().events
    assert [(ev.title, settings.calendar(ev.calendar).id) for ev in events] == [("Interview with Example Corp", "perso")]
    undo = undo_import(store, "inv", events, settings)
    assert undo.deleted == 1 and undo.calendars_removed == [] and store.scan().events == []


def test_invitation_target_can_still_be_a_new_calendar_named_after_the_file():
    from memento_win.core.agenda.ics_import import ImportTarget
    store = AgendaStore(MemoryFs())
    settings = settings_with_default()
    parsed = interview()
    report = apply_import(store, parsed, ImportOptions({0: ImportTarget()}), [], settings, DEVICE_ZONE, "inv", NOW)
    assert [settings.calendar(c).name for c in report.new_calendars] == ["interview_0"]


def test_preview_lists_device_time_when_it_differs():
    e = interview("America/New_York")[0].events[0]
    assert when_text(e, "America/New_York") == \
        "lun. 26 oct. 2026, 17:00–18:00 (Zurich) · soit lun. 26 oct. 2026, 12:00–13:00 (New York)"


def test_location_placeholders_are_no_location():
    for raw in ("null", "NULL", "undefined", "None", "", "   ", None):
        assert clean_location(raw) is None
    assert clean_location("  Salle 4 ") == "Salle 4"


def _ics(name: str | None, count: int, uid: str = "e") -> bytes:
    lines = ["BEGIN:VCALENDAR", "VERSION:2.0"] + ([f"X-WR-CALNAME:{name}"] if name else [])
    for i in range(count):
        lines += ["BEGIN:VEVENT", f"UID:{uid}{i}", f"DTSTART:202611{i % 28 + 1:02d}T090000Z", f"SUMMARY:E{i}",
                  "LOCATION:undefined", "END:VEVENT"]
    return ("\r\n".join(lines + ["END:VCALENDAR"]) + "\r\n").encode()


def test_calendar_exports_still_propose_new_calendars():
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        z.writestr("Takeout/perso@gmail.com.ics", _ics("Perso Google", 3, "a"))
        z.writestr("Takeout/sport.ics", _ics(None, 2, "b"))
    settings = settings_with_default()
    parsed = read_sources([IcsSource.from_bytes("takeout.zip", buf.getvalue()),
                           IcsSource.from_bytes("big.ics", _ics(None, 21, "c")),
                           IcsSource.from_bytes("small.ics", _ics(None, 2, "d"))], DEVICE_ZONE, NOW)
    pvs = preview(parsed, [], settings)
    assert [(p.name, pv.calendar_export, pv.suggested_calendar) for p, pv in zip(parsed, pvs)] == [
        ("Perso Google", True, None), ("sport", True, None), ("big", True, None), ("small", False, "perso")]
    assert pvs[2].items is None and len(pvs[3].items) == 2
    assert all(e.location is None for p in parsed for e in p.events)
    store = AgendaStore(MemoryFs())
    report = apply_import(store, parsed, ImportOptions(), [], settings, DEVICE_ZONE, "zip", NOW)
    assert [settings.calendar(c).name for c in report.new_calendars] == ["Perso Google", "sport", "big"]


def test_meeting_link_line_is_not_duplicated():
    from memento_win.core.agenda import ics
    from memento_win.core.agenda.ics_import import build_body

    def body(desc: str, extra: str = "") -> str:
        text = f"BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nDESCRIPTION:{desc}\r\n{extra}END:VEVENT\r\nEND:VCALENDAR\r\n"
        return build_body(list(ics.read_calendars(io.BytesIO(text.encode()), []))[0].children[0])

    assert body("Rejoindre : https://teams.microsoft.com/l/meetup-join/abc\\nOrdre du jour") == \
        "Rejoindre : https://teams.microsoft.com/l/meetup-join/abc\nOrdre du jour"
    assert body("Ordre du jour\\nZoom : https://us02web.zoom.us/j/123.") == \
        "Visio : https://us02web.zoom.us/j/123\n\nOrdre du jour\nZoom : https://us02web.zoom.us/j/123."
    assert body("Voir https://exemple.fr/doc") == "Voir https://exemple.fr/doc"
    assert body("Voir https://meet.google.com/abc", "X-GOOGLE-CONFERENCE:https://meet.google.com/abc\r\n") == \
        "Voir https://meet.google.com/abc"


def test_links_of_a_body():
    text = ("Ordre du jour : <https://exemple.fr/agenda/2026/reunion-du-conseil-annuel?id=42>, puis "
            "(https://fr.wikipedia.org/wiki/Foo_(bar)). Doc : https://exemple.fr/doc. "
            f"Rejoindre : {MEET} ; encore https://exemple.fr/doc !")
    got = links.extract(text)
    assert [(l.url, l.label) for l in got] == [
        (MEET, "Rejoindre la visio (Proton Meet)"),
        ("https://exemple.fr/agenda/2026/reunion-du-conseil-annuel?id=42", "exemple.fr/agenda/2026/reunion-du-conse…"),
        ("https://fr.wikipedia.org/wiki/Foo_(bar)", "fr.wikipedia.org/wiki/Foo_(bar)"),
        ("https://exemple.fr/doc", "exemple.fr/doc"),
    ]
    many = " ".join(f"https://x.org/{i}" for i in range(15))
    assert len(links.extract(many)) == links.MAX
    assert links.extract("rien ici, http:// ni ftp://x.org") == []
    spans = links.spans("a <https://www.x.org/> b")
    assert spans == [(3, 21, "https://www.x.org/")] and links.label("https://www.x.org/") == "x.org"


def test_markdown_autolinks():
    assert links.markdown_autolinks("Voir https://x.org/a. Et [doc](https://x.org/b), <https://x.org/c>") == \
        "Voir <https://x.org/a>. Et [doc](https://x.org/b), <https://x.org/c>"
