"""Agenda data layer: shared vectors (testdata/agenda), model, recurrence, entries, alarms, import, export."""
import io
import json
import zipfile
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

import pytest

from scriptorium_win.core import note as fmt
from scriptorium_win.core.activity import ActivityInfo
from scriptorium_win.core.agenda import ics, zones
from scriptorium_win.core.agenda.calendars import PALETTE, Calendar, CalendarSettings, Layer
from scriptorium_win.core.agenda.entries import (AgendaSources, Birthday, EntryKind, activity_subtitle, alarms,
                                                 build_entries, parse_birthday)
from scriptorium_win.core.agenda.frdates import departure_arrival
from scriptorium_win.core.agenda.ics_export import export_all, export_calendar
from scriptorium_win.core.agenda.ics_import import (IcsSource, ImportOptions, ImportTarget, apply_import, preview,
                                                    read_sources, undo_import)
from scriptorium_win.core.agenda.model import (AgendaFormatError, event_from_note, event_to_note, format_alert,
                                               new_event, parse_alert, parse_stamp)
from scriptorium_win.core.agenda.recurrence import MAX_OCCURRENCES, expand
from scriptorium_win.core.agenda.store import AgendaStore, DirFs, MemoryFs
from scriptorium_win.core.note import Note
from scriptorium_win.core.track import Sport

from agenda_vectors import DEVICE_ZONE, IMPORT_ID, NOW, VECTORS, WINDOW, compute, files_of, import_vector, occurrences

UTC = timezone.utc
PARIS = "Europe/Paris"
VECTOR_NAMES = sorted(p.stem for p in (VECTORS / "expected").glob("*.json"))
EXPORT_NOW = datetime(2026, 11, 1, 9, 20, tzinfo=UTC)


def utc(*a) -> datetime:
    return datetime(*a, tzinfo=UTC)


def event_note(extra: str, title="Évènement", path="agenda/2026/x-abc123.md", body="") -> Note:
    text = f"---\nid: abc123\ntitle: {title}\ncreated: 2026-10-01T10:00:00+02:00\nupdated: 2026-10-01T10:00:00+02:00\n{extra}---\n{body}"
    return fmt.parse(path, text, 0)


def ev(extra: str, **kw):
    return event_from_note(event_note(extra, **kw))


# ---- shared vectors ----

@pytest.mark.parametrize("name", VECTOR_NAMES)
def test_vector_occurrences_and_report(name):
    expected = json.loads((VECTORS / "expected" / f"{name}.json").read_text(encoding="utf-8"))
    assert expected["device_zone"] == DEVICE_ZONE and expected["now_ms"] == NOW
    got = compute(name)
    assert got["preview"] == expected["preview"]
    assert got["created"] == expected["created"]
    assert got["occurrences"] == expected["occurrences"]


@pytest.mark.parametrize("name", VECTOR_NAMES)
def test_vector_files(name):
    expected = json.loads((VECTORS / "expected" / f"{name}.json").read_text(encoding="utf-8"))
    files = files_of(import_vector(name)[0])
    for title, needles in expected["files"].items():
        assert title in files, title
        for needle in needles:
            assert needle in files[title], (title, needle)


@pytest.mark.parametrize("name", VECTOR_NAMES)
def test_round_trip_export_import(name):
    store, _, _ = import_vector(name)
    before = occurrences(store)
    scan = store.scan()
    data, warnings = export_all(scan.events, store.calendars(), EXPORT_NOW)
    assert not warnings
    fresh = AgendaStore(MemoryFs())
    parsed = read_sources([IcsSource.from_bytes("export.zip", data)], DEVICE_ZONE, NOW)
    assert all(not p.preview.errors for p in parsed)
    apply_import(fresh, parsed, ImportOptions(), [], fresh.calendars(), DEVICE_ZONE, "again", NOW)
    assert occurrences(fresh) == before


def test_export_vector_is_byte_identical():
    store = AgendaStore(DirFs(VECTORS / "export"))
    scan = store.scan()
    assert not scan.problems
    result = export_calendar(scan.events, store.calendars(), "perso", EXPORT_NOW)
    assert result.text == (VECTORS / "export" / "expected_perso.ics").read_bytes().decode("utf-8")
    assert result.warnings == ["Yoga : ligne ical_extra DTSTAMP ignorée (écrite par l'export)"]
    assert all(len(line.encode()) <= 75 for line in result.text.split("\r\n"))


# ---- model ----

def test_event_round_trip_keeps_unknown_keys_and_order():
    note = event_note("start: 2026-11-12T14:30\nfoo: bar\nend: 2026-11-12T15:15\ntz: Europe/Paris\n"
                      "alerts: [-15m, -1d, 2h]\nexdate:\n  - 2026-11-19T14:30\nical_extra:\n  - \"X-A:b;c\"\n  - \"CATEGORIES:Santé\"\n")
    e = event_from_note(note)
    assert (e.start, e.end, e.tz, e.alerts) == (datetime(2026, 11, 12, 14, 30), datetime(2026, 11, 12, 15, 15), PARIS, [-15, -1440, 120])
    assert e.exdate == [datetime(2026, 11, 19, 14, 30)]
    assert e.ical_extra == ["X-A:b;c", "CATEGORIES:Santé"]
    assert e.note.extra == ["foo: bar"]
    text = fmt.serialize(event_to_note(e))
    assert "start: 2026-11-12T14:30\nend: 2026-11-12T15:15\ntz: Europe/Paris\nexdate: [2026-11-19T14:30]\nalerts: [-15m, -1d, 2h]\n" in text
    assert text.index("ical_extra:") < text.index("foo: bar")
    again = event_from_note(fmt.parse(e.path, text, 0))
    assert again == e


def test_event_problems_are_reported_not_swallowed():
    with pytest.raises(AgendaFormatError):
        ev("start: demain\n")
    e = ev("start: 2026-11-12\nend: 2026-11-12T10:00\nalerts: [-15x]\nexdate: [nope]\ntz: Mars/Olympus\n")
    assert e.end is None and e.alerts == [] and e.exdate == [] and e.tz == "Mars/Olympus"
    assert len(e.warnings) == 4
    assert ev("title2: x\n") is None


def test_stamps_and_alerts():
    assert parse_stamp("2026-11-12") == date(2026, 11, 12)
    assert parse_stamp("2026-11-12 08:05:09") == datetime(2026, 11, 12, 8, 5, 9)
    with pytest.raises(ValueError):
        parse_stamp("2026-13-12")
    assert [parse_alert(x) for x in ("0m", "-1w", "+2d", "-90m")] == [0, -10080, 2880, -90]
    assert [format_alert(x) for x in (0, -10080, 2880, -90, -60)] == ["0m", "-1w", "2d", "-90m", "-1h"]


# ---- recurrence ----

def occ(e, start=utc(2026, 1, 1), end=utc(2027, 1, 1), zone=PARIS, overridden=None):
    return expand(e, start, end, zone, overridden)


def test_dtstart_counts_even_when_off_rule():
    e = ev("start: 2026-11-10T09:00\ntz: Europe/Paris\nrrule: FREQ=WEEKLY;BYDAY=TH;COUNT=3\n")  # a Tuesday
    assert [o.start for o in occ(e).occurrences] == [datetime(2026, 11, 10, 9), datetime(2026, 11, 12, 9), datetime(2026, 11, 19, 9)]


def test_date_exdate_removes_the_whole_day_and_until_local():
    e = ev("start: 2026-11-02T09:00\nrrule: FREQ=DAILY;UNTIL=20261105T090000\nexdate: [2026-11-03]\n")
    assert [o.start.day for o in occ(e).occurrences] == [2, 4, 5]


def test_until_utc_compares_instants():
    # 09:00 Paris = 08:00Z; UNTIL 08:00Z on the 4th keeps the 4th, 07:59:59Z drops it.
    keep = ev("start: 2026-11-02T09:00\ntz: Europe/Paris\nrrule: FREQ=DAILY;UNTIL=20261104T080000Z\n")
    drop = ev("start: 2026-11-02T09:00\ntz: Europe/Paris\nrrule: FREQ=DAILY;UNTIL=20261104T075959Z\n")
    assert len(occ(keep).occurrences) == 3 and len(occ(drop).occurrences) == 2


def test_floating_follows_the_device_zone_and_zoned_does_not():
    floating = ev("start: 2026-11-05T08:00\n")
    zoned = ev("start: 2026-11-05T08:00\ntz: Europe/Paris\n")
    ny = "America/New_York"
    assert occ(floating, zone=ny).occurrences[0].start_instant == utc(2026, 11, 5, 13)
    assert occ(floating, zone=PARIS).occurrences[0].start_instant == utc(2026, 11, 5, 7)
    assert occ(zoned, zone=ny).occurrences[0].start_instant == utc(2026, 11, 5, 7)


def test_all_day_is_zone_free():
    e = ev("start: 2026-11-20\nend: 2026-11-21\ntz: Asia/Tokyo\n")
    o = occ(e, zone="America/Los_Angeles").occurrences[0]
    assert (o.start, o.end) == (date(2026, 11, 20), date(2026, 11, 21))
    assert o.start_instant == utc(2026, 11, 20, 8) and o.end_instant == utc(2026, 11, 22, 8)


def test_window_overlap_and_zero_length():
    e = ev("start: 2026-11-05T23:00\nend: 2026-11-06T01:00\ntz: Europe/Paris\n")
    assert len(occ(e, utc(2026, 11, 5, 23), utc(2026, 11, 6)).occurrences) == 1
    assert occ(e, utc(2026, 11, 6), utc(2026, 11, 7)).occurrences == []
    point = ev("start: 2026-11-05T10:00\ntz: Europe/Paris\n")
    assert len(occ(point, utc(2026, 11, 5, 9), utc(2026, 11, 5, 9, 0, 1)).occurrences) == 1
    assert occ(point, utc(2026, 11, 5, 8), utc(2026, 11, 5, 9)).occurrences == []


def test_cap_and_bad_rule():
    e = ev("start: 2026-01-01T00:00\nrrule: FREQ=MINUTELY\n")
    x = occ(e, utc(2026, 1, 1), utc(2026, 2, 1))
    assert x.truncated and len(x.occurrences) == MAX_OCCURRENCES
    bad = occ(ev("start: 2026-11-01T10:00\nrrule: FREQ=SOMETIMES\n"))
    assert bad.error and len(bad.occurrences) == 1
    assert occ(ev("start: 2026-11-01T10:00\nstatus: cancelled\n")).occurrences == []


def test_exception_hides_its_occurrence_even_without_exdate():
    series = ev("start: 2026-11-02T09:00\nrrule: FREQ=DAILY;COUNT=3\n")
    assert [o.start.day for o in occ(series, overridden=[datetime(2026, 11, 3, 9)]).occurrences] == [2, 4]


# ---- entries ----

def settings_with(**layers) -> CalendarSettings:
    s = CalendarSettings(calendars=[Calendar("perso", "Perso", "#4F8BF5"), Calendar("cache", "Caché", "#D9534F", False)])
    for k, v in layers.items():
        s.layers[k] = v
    return s


def test_entries_kinds_colours_and_visibility():
    events = [ev("start: 2026-11-10T10:00\ncalendar: inconnu\nlocation: Ici\n", title="A"),
              ev("start: 2026-11-10T11:00\ncalendar: cache\n", title="Caché")]
    notes = [fmt.parse("liste-n00001.md", "---\nid: n00001\ntitle: Courses\nstart: 2026-11-10\n---\n- [x] a\n- [ ] b\n- [x] c\n", 0),
             fmt.parse("rappel-n00002.md", "---\nid: n00002\ntitle: Rappel\nreminder: 2026-11-10T09:00:00+01:00\n---\n", 0)]
    act = Note(path="activities/sortie-a00001.md", id="a00001", title="Sortie",
               activity=ActivityInfo(Sport.BIKE, int(utc(2026, 11, 10, 22, 30).timestamp() * 1000), 3900, 3600, 12345.0))
    sources = AgendaSources(events, notes, [act], [Birthday("c1", "Marie Dupont", "1992-11-10", "contacts/dupont-marie-c00001.md")],
                            settings_with())
    r = build_entries(sources, utc(2026, 11, 9, 23), utc(2026, 11, 11, 23), PARIS)
    assert r.problems == []
    got = [(e.kind, e.title, e.subtitle, e.color, e.all_day) for e in r.entries]
    assert got == [
        (EntryKind.BIRTHDAY, "Anniversaire de Marie Dupont", "(34 ans)", "#D64D6F", True),
        (EntryKind.NOTE, "Courses", "2/3", "#8E63CE", True),
        (EntryKind.NOTE, "Rappel", None, "#8E63CE", False),
        (EntryKind.EVENT, "A", "Ici", "#4F8BF5", False),
        (EntryKind.ACTIVITY, "Sortie", "12,3 km · 1 h 05", "#2E9E5B", False),
    ]
    sortie = r.entries[-1]
    assert sortie.start_date == sortie.end_date == date(2026, 11, 10)  # crosses midnight: start day only
    hidden = build_entries(AgendaSources(events, notes, [act], [], settings_with(
        notes=Layer(False, "#8E63CE"), activities=Layer(True, "#2E9E5B", sports=["walk"]))),
        utc(2026, 11, 9, 23), utc(2026, 11, 11, 23), PARIS)
    assert [e.title for e in hidden.entries] == ["A"]


def test_multi_day_all_day_and_timed_end_dates():
    e = ev("start: 2026-12-01\nend: 2026-12-03\n")
    t = ev("start: 2026-12-01T22:00\nend: 2026-12-02T00:00\ntz: Europe/Paris\n", title="T")
    r = build_entries(AgendaSources([e, t], settings=settings_with()), utc(2026, 11, 1), utc(2027, 1, 1), PARIS)
    assert [(x.start_date, x.end_date) for x in r.entries] == [(date(2026, 12, 1), date(2026, 12, 3)),
                                                               (date(2026, 12, 1), date(2026, 12, 1))]


def test_birthdays():
    assert parse_birthday("--02-29") == (None, 2, 29)
    assert parse_birthday("19900514") == (1990, 5, 14)
    with pytest.raises(ValueError):
        parse_birthday("1990-02-30")
    bdays = [Birthday("a", "Léap", "2000-02-29"), Birthday("b", "Sans année", "--03-01"), Birthday("c", "Bébé", "2027-02-01"),
             Birthday("d", "Cassé", "hier")]
    r = build_entries(AgendaSources(birthdays=bdays, settings=settings_with()), utc(2027, 1, 1), utc(2027, 3, 31), PARIS)
    assert [(e.title, e.start_date, e.subtitle) for e in r.entries] == [
        ("Anniversaire de Bébé", date(2027, 2, 1), None),
        ("Anniversaire de Léap", date(2027, 2, 28), "(27 ans)"),
        ("Anniversaire de Sans année", date(2027, 3, 1), None),
    ]
    assert r.problems == ["Cassé : anniversaire illisible ('hier')"]


def test_activity_subtitle():
    assert activity_subtitle(12345, 3900) == "12,3 km · 1 h 05"
    assert activity_subtitle(12250, 2700) == "12,3 km · 45 min"
    assert activity_subtitle(0, 600) == "10 min"
    assert activity_subtitle(950, 0) == "1,0 km"


# ---- alarms ----

def test_alarms_horizon_keys_and_reminders():
    series = ev("start: 2026-11-02T09:00\ntz: Europe/Paris\nrrule: FREQ=DAILY\nalerts: [-15m, -1d]\n", title="Daily")
    allday = ev("start: 2026-11-03\nalerts: [-1h]\n", title="Jour")
    note = fmt.parse("n-n00003.md", "---\nid: n00003\ntitle: Note\nreminder: 2026-11-03T12:00:00+01:00\n---\n", 0)
    now = utc(2026, 11, 2, 8, 0)  # 09:00 Paris
    got = alarms(AgendaSources([series, allday], [note]), now, PARIS)
    assert [(a.key.split("|", 1)[1], a.at) for a in got] == [
        ("2026-11-03T09:00|-1440", utc(2026, 11, 2, 8)),
        ("2026-11-03|-60", utc(2026, 11, 2, 22)),
        ("2026-11-03T09:00|-15", utc(2026, 11, 3, 7, 45)),
        ("2026-11-04T09:00|-1440", utc(2026, 11, 3, 8)),
        (f"reminder|{note.reminder}", utc(2026, 11, 3, 11)),
        ("2026-11-04T09:00|-15", utc(2026, 11, 4, 7, 45)),
    ]
    assert got[0].key == f"{series.id}|2026-11-03T09:00|-1440"


# ---- import ----

def test_duplicates_policies_and_body_preservation():
    store, _, _ = import_vector("google_perso")
    events = store.scan().events
    dentiste = next(e for e in events if e.title == "Dentiste")
    store.save(dentiste.with_note(body="Mes notes à moi"))
    settings = store.calendars()
    for policy, created, updated, skipped in (("skip", 0, 0, 6), ("update", 0, 6, 0)):
        parsed = read_sources([IcsSource.from_path(VECTORS / "google_perso.ics")], DEVICE_ZONE, NOW)
        pv = preview(parsed, store.scan().events, settings)
        assert pv[0].suggested_calendar == "perso-google" and pv[0].duplicates == 5
        rep = apply_import(store, parsed, ImportOptions(duplicates=policy), store.scan().events, settings, DEVICE_ZONE, f"i-{policy}", NOW)
        c = rep.calendars[0]
        assert (c.created, c.updated, c.skipped_duplicates) == (created, updated, skipped)
    events = store.scan().events
    assert len(events) == 6
    assert next(e for e in events if e.title == "Dentiste").body == "Mes notes à moi"  # ICS description unchanged
    assert all(e.import_id == IMPORT_ID for e in events)
    parsed = read_sources([IcsSource.from_path(VECTORS / "google_perso.ics")], DEVICE_ZONE, NOW)
    rep = apply_import(store, parsed, ImportOptions(duplicates="keep"), events, settings, DEVICE_ZONE, "i-keep", NOW)
    assert rep.created == 6 and len(store.scan().events) == 12


def test_since_cutoff_and_undo():
    fs = MemoryFs()
    store = AgendaStore(fs)
    settings = store.calendars()
    parsed = read_sources([IcsSource.from_path(VECTORS / "google_perso.ics")], DEVICE_ZONE, NOW)
    rep = apply_import(store, parsed, ImportOptions(since=date(2026, 11, 1)), [], settings, DEVICE_ZONE, "cut", NOW)
    assert rep.calendars[0].before_cutoff == 3  # the Yoga series (ends 29 Oct), its moved occurrence, the Standup
    assert sorted(e.title for e in store.scan().events) == ["Anniversaire de mariage", "Dentiste", "Vacances à la montagne"]
    assert "perso-google" in [c.id for c in store.calendars().calendars]
    undo = undo_import(store, "cut", store.scan().events, store.calendars())
    assert undo.deleted == 3 and undo.calendars_removed == ["perso-google"]
    assert store.scan().events == [] and [c.id for c in store.calendars().calendars] == ["perso"]


def test_undo_removes_the_exdates_of_its_exceptions_from_an_older_series():
    store, _, _ = import_vector("google_perso")
    yoga = next(e for e in store.scan().events if e.title == "Yoga")
    ics_text = (b"BEGIN:VCALENDAR\r\nX-WR-CALNAME:Perso Google\r\nBEGIN:VEVENT\r\nUID:yoga-004@google.com\r\n"
                b"RECURRENCE-ID;TZID=Europe/Paris:20261022T183000\r\nDTSTART;TZID=Europe/Paris:20261023T183000\r\n"
                b"SUMMARY:Yoga vendredi\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n")
    settings = store.calendars()
    parsed = read_sources([IcsSource.from_bytes("x.ics", ics_text)], DEVICE_ZONE, NOW)
    preview(parsed, store.scan().events, settings)
    rep = apply_import(store, parsed, ImportOptions(), store.scan().events, settings, DEVICE_ZONE, "late", NOW)
    assert rep.calendars[0].exceptions == 1
    yoga2 = next(e for e in store.scan().events if e.id == yoga.id)
    assert datetime(2026, 10, 22, 18, 30) in yoga2.exdate
    undo = undo_import(store, "late", store.scan().events, store.calendars())
    assert (undo.deleted, undo.exdates_removed) == (1, 1)
    assert next(e for e in store.scan().events if e.id == yoga.id).exdate == yoga.exdate


def test_zip_sources_and_malformed_input():
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as z:
        z.writestr("Takeout/Agenda/Perso.ics", (VECTORS / "proton_travail.ics").read_bytes())
        z.writestr("Takeout/Agenda/lisez-moi.txt", "x")
        z.writestr("Takeout/Agenda/vide.ics", b"pas un calendrier\r\n")
    parsed = read_sources([IcsSource.from_bytes("export.zip", buffer.getvalue())], DEVICE_ZONE, NOW)
    assert [p.name for p in parsed] == ["Proton Travail", "vide"]
    assert parsed[1].preview.errors and parsed[1].events == []
    assert parsed[0].preview.errors == ["ligne 50 : DTSTART manquant"] or "DTSTART manquant" in parsed[0].preview.errors[0]


def test_latin1_and_report_summary():
    data = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:x\r\nDTSTART;VALUE=DATE:20261105\r\nSUMMARY:Fête\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n".encode("latin-1")
    store = AgendaStore(MemoryFs())
    parsed = read_sources([IcsSource.from_bytes("vieux.ics", data)], DEVICE_ZONE, NOW)
    assert parsed[0].events[0].title == "Fête"
    rep = apply_import(store, parsed, ImportOptions(targets={0: ImportTarget(name="Anciens", color="#2B8FC4")}),
                       [], store.calendars(), DEVICE_ZONE, "l1", NOW)
    assert rep.summary() == "1 événement, 0 série, 0 ignoré"
    assert store.calendars().calendar("anciens").color == "#2B8FC4"


# ---- ICS helpers, zones, calendars ----

def test_fold_unescape_html():
    line = "DESCRIPTION:" + "é" * 80
    folded = ics.fold(line)
    assert all(len(p.encode()) <= 75 for p in folded.split("\r\n"))
    assert list(ics.logical_lines(io.BytesIO(folded.encode()))) == [(1, line)]
    assert ics.unescape_text(r"a\,b\;c\nd\\e") == "a,b;c\nd\\e"
    assert ics.escape_text("a,b;c\nd\\e") == r"a\,b\;c\nd\\e"
    assert ics.html_to_text('<p>Lien&nbsp;: <a href="https://x.org/a?b=1&amp;c=2">https://x.org/a?b=1&amp;c=2</a></p><p>&#233;t&eacute;</p>') == \
        "Lien : https://x.org/a?b=1&c=2\n\nét&eacute;"
    p = ics.parse_line('ATTENDEE;CN="Dupont, Marie";ROLE=REQ:mailto:m@x.org')
    assert (p.name, p.param("CN"), p.value) == ("ATTENDEE", "Dupont, Marie", "mailto:m@x.org")


def test_windows_table_and_vtimezone():
    assert all(zones.is_valid(v) for v in zones.WINDOWS_ZONES.values())
    assert zones.resolve_tzid("Romance Standard Time") == PARIS
    assert zones.resolve_tzid("/citadel.org/20190405_1/Europe/Paris") == PARIS
    assert zones.resolve_tzid("Nowhere") is None
    assert zones.recognise(330, None, None, None) is None and zones.recognise(60, None, None, None) == "Etc/GMT-1"
    assert zones.vtimezone_lines("Asia/Tokyo", 2026) == ["BEGIN:VTIMEZONE", "TZID:Asia/Tokyo", "BEGIN:STANDARD",
                                                          "TZOFFSETFROM:+0900", "TZOFFSETTO:+0900", "DTSTART:19700101T000000",
                                                          "END:STANDARD", "END:VTIMEZONE"]


def contrast(a: str, b: str) -> float:
    def lum(h):
        def f(c):
            c = int(h[c:c + 2], 16) / 255
            return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4
        return 0.2126 * f(1) + 0.7152 * f(3) + 0.0722 * f(5)
    hi, lo = sorted((lum(a), lum(b)), reverse=True)
    return (hi + 0.05) / (lo + 0.05)


def test_palette_contrast_and_calendars_json():
    assert len(PALETTE) == 12
    assert all(contrast(c, "#FFFFFF") >= 3 and contrast(c, "#121212") >= 3 for c, _ in PALETTE)
    text = (VECTORS / "export" / "agenda" / "calendars.json").read_text(encoding="utf-8")
    s = CalendarSettings.parse(text)
    assert s.to_json() == text
    assert s.calendar("nope").id == "perso" and s.problem is None
    bad = CalendarSettings.parse("{oups")
    assert bad.problem and bad.calendars[0].id == "perso"
    keep = CalendarSettings.parse('{"calendars": [{"id": "a", "name": "A", "color": "#4F8BF5", "x": 1}], "y": [1]}')
    assert '"x": 1' in keep.to_json() and '"y": [' in keep.to_json()


# ---- store ----

def test_store_paths_exceptions_and_restore(tmp_path: Path):
    store = AgendaStore(DirFs(tmp_path))
    series = store.save(new_event("Cours", datetime(2027, 1, 4, 18), datetime(2027, 1, 4, 19), tz=PARIS,
                                  rrule="FREQ=WEEKLY", now=NOW))
    assert series.path.startswith("agenda/2027/cours-") and series.uid == f"{series.id}@scriptorium"
    exc = store.make_exception(series, datetime(2027, 1, 11, 18), datetime(2027, 1, 11, 19))
    exc = exc.copy(start=datetime(2027, 1, 12, 18), end=datetime(2027, 1, 12, 19))
    series, exc = store.save_exception(series, exc)
    scan = store.scan()
    assert len(scan.events) == 2 and scan.problems == []
    r = build_entries(AgendaSources(scan.events, settings=store.calendars()), utc(2027, 1, 1), utc(2027, 1, 20), PARIS)
    assert [e.start_date.day for e in r.entries] == [4, 12, 18]
    series = store.restore_occurrence(series, exc, datetime(2027, 1, 11, 18))
    assert series.exdate == [] and len(store.scan().events) == 1
    (tmp_path / "agenda" / "2027" / "casse-zzzzzz.md").write_text("---\nid: zzzzzz\ntitle: x\nstart: hier\n---\n", encoding="utf-8")
    (tmp_path / "agenda" / "2027" / ".x.md.synctmp-1").write_text("partial", encoding="utf-8")
    scan = store.scan()
    assert len(scan.events) == 1 and len(scan.problems) == 1 and "début illisible" in scan.problems[0]


# ---- review fixes (same as AgendaReviewTest.kt) ----

def test_export_vector_round_trips_with_overrides_and_no_exdate_for_them():
    store = AgendaStore(DirFs(VECTORS / "export"))
    before = occurrences(store)
    yoga = [o["start"] for o in before if o["title"].startswith("Yoga")]
    assert "2026-10-15T16:30:00Z" not in yoga and "2026-10-20T16:30:00Z" not in yoga and len(yoga) == 7
    text = export_calendar(store.scan().events, store.calendars(), "perso", EXPORT_NOW).text
    assert "EXDATE;TZID=Europe/Paris:20261015T183000" in text  # a date alone on a timed series: its start time
    assert "EXDATE;TZID=Europe/Paris:20261020T183000" not in text  # overridden, not excluded
    assert "RECURRENCE-ID;TZID=Europe/Paris:20261020T183000" in text
    data, _ = export_all(store.scan().events, store.calendars(), EXPORT_NOW)
    fresh = AgendaStore(MemoryFs())
    parsed = read_sources([IcsSource.from_bytes("export.zip", data)], DEVICE_ZONE, NOW)
    apply_import(fresh, parsed, ImportOptions(), [], fresh.calendars(), DEVICE_ZONE, "again", NOW)
    assert occurrences(fresh) == before


def test_a_date_exdate_on_a_timed_series_removes_only_the_start_time_occurrence():
    e = ev("start: 2026-11-02T09:00\ntz: Europe/Paris\nrrule: FREQ=DAILY;BYHOUR=9,17;COUNT=4\nexdate: [2026-11-02]\n")
    got = [o.start for o in expand(e, utc(2026, 11, 1), utc(2026, 11, 10), PARIS).occurrences]
    assert got == [datetime(2026, 11, 2, 17), datetime(2026, 11, 3, 9), datetime(2026, 11, 3, 17)]


def test_all_day_alerts_keep_their_wall_time_across_daylight_saving():
    e = ev("start: 2026-03-31\nalerts: [-9540m, -2340m, -360m]\n")
    got = alarms(AgendaSources([e]), utc(2026, 3, 24), PARIS, timedelta(days=8))
    walls = [zones.to_wall(a.at, zones.zone(PARIS)) for a in got]
    assert walls == [datetime(2026, 3, 24, 9), datetime(2026, 3, 29, 9), datetime(2026, 3, 30, 18)]
    t = ev("start: 2026-03-30T09:00\ntz: Europe/Paris\nalerts: [-1d]\n")
    assert [a.at for a in alarms(AgendaSources([t]), utc(2026, 3, 28), PARIS)] == [utc(2026, 3, 29, 7)]


# ---- end_tz (an end in its own zone: flights) ----

NY = "America/New_York"


def test_end_tz_is_read_written_and_only_kept_on_zoned_timed_events():
    e = ev("start: 2026-12-10T10:00\nend: 2026-12-10T13:00\ntz: Europe/Paris\nend_tz: America/New_York\n")
    assert (e.tz, e.end_tz, e.warnings) == (PARIS, NY, [])
    assert "tz: Europe/Paris\nend_tz: America/New_York\n" in fmt.serialize(event_to_note(e))
    floating = ev("start: 2026-12-10T10:00\nend: 2026-12-10T13:00\nend_tz: America/New_York\n")
    assert floating.end_tz is None and "end_tz ignoré" in floating.warnings[0]
    all_day = ev("start: 2026-12-10\ntz: Europe/Paris\nend_tz: America/New_York\n")
    assert all_day.end_tz is None and "end_tz ignoré" in all_day.warnings[0]
    unknown = ev("start: 2026-12-10T10:00\nend: 2026-12-10T13:00\ntz: Europe/Paris\nend_tz: Mars/Olympus\n")
    assert unknown.end_tz == "Mars/Olympus" and "fuseau de fin inconnu" in unknown.warnings[0]
    assert occ(unknown).occurrences[0].end_instant == utc(2026, 12, 10, 12)  # the start's zone


def test_end_before_start_compares_instants_with_end_tz():
    # Paris 14:00 -> New York 12:00 ends after the start (18:00 Paris); New York 07:00 ends before it
    later = ev("start: 2026-12-10T14:00\nend: 2026-12-10T12:00\ntz: Europe/Paris\nend_tz: America/New_York\n")
    assert later.end == datetime(2026, 12, 10, 12) and later.warnings == []
    before = ev("start: 2026-12-10T14:00\nend: 2026-12-10T07:00\ntz: Europe/Paris\nend_tz: America/New_York\n")
    assert before.end is None and "fin avant le début" in before.warnings[0]


def test_end_tz_expansion_keeps_the_first_duration_and_gives_the_end_in_its_zone():
    flight = ev("start: 2026-12-10T10:00\nend: 2026-12-10T13:00\ntz: Europe/Paris\nend_tz: America/New_York\n")
    o = occ(flight).occurrences[0]
    assert (o.start_instant, o.end_instant, o.end) == (utc(2026, 12, 10, 9), utc(2026, 12, 10, 18), datetime(2026, 12, 10, 13))
    # weekly, Paris 08:00 -> London 08:30 (1 h 30): still 1 h 30 once both zones left summer time
    series = ev("start: 2026-10-19T08:00\nend: 2026-10-19T08:30\ntz: Europe/Paris\nend_tz: Europe/London\n"
                "rrule: FREQ=WEEKLY;COUNT=2\n")
    got = [(o.start_instant, o.end_instant, o.end) for o in occ(series).occurrences]
    assert got == [(utc(2026, 10, 19, 6), utc(2026, 10, 19, 7, 30), datetime(2026, 10, 19, 8, 30)),
                   (utc(2026, 10, 26, 7), utc(2026, 10, 26, 8, 30), datetime(2026, 10, 26, 8, 30))]
    entries = build_entries(AgendaSources(events=[flight]), utc(2026, 12, 10), utc(2026, 12, 11), PARIS).entries
    assert (entries[0].start, entries[0].end, entries[0].end_date) == (utc(2026, 12, 10, 9), utc(2026, 12, 10, 18), date(2026, 12, 10))
    o = entries[0].occurrence
    assert departure_arrival(o.start, o.end, flight.tz, flight.end_tz) == "Départ 10:00 (Paris) → arrivée 13:00 (New York)"
    back = ev("start: 2026-12-17T18:30\nend: 2026-12-18T08:00\ntz: America/New_York\nend_tz: Europe/Paris\n")
    o = occ(back).occurrences[0]
    assert departure_arrival(o.start, o.end, back.tz, back.end_tz) == \
        "Départ 18:30 (New York) → arrivée le 18 déc. à 08:00 (Paris)"
    assert departure_arrival(o.start, o.end, back.tz, None) is None


def _ics(*vevent_lines: str) -> bytes:
    lines = ["BEGIN:VCALENDAR", "VERSION:2.0", "BEGIN:VEVENT", "UID:x@test", *vevent_lines, "SUMMARY:Vol", "END:VEVENT",
             "END:VCALENDAR"]
    return ("\r\n".join(lines) + "\r\n").encode()


def test_import_end_zone_rules():
    def one(*lines):
        parsed = read_sources([IcsSource.from_bytes("v.ics", _ics(*lines))], DEVICE_ZONE, NOW)
        return parsed[0].events[0], parsed[0].preview
    e, _ = one("DTSTART;TZID=Europe/Paris:20261210T100000", "DTEND;TZID=America/New_York:20261210T130000")
    assert (e.start, e.end, e.tz, e.end_tz) == (datetime(2026, 12, 10, 10), datetime(2026, 12, 10, 13), PARIS, NY)
    e, _ = one("DTSTART;TZID=Europe/Paris:20261210T100000", "DTEND;TZID=Europe/Paris:20261210T130000")
    assert e.end_tz is None
    e, _ = one("DTSTART;TZID=Europe/Paris:20261210T100000", "DTEND:20261210T180000Z")
    assert (e.end, e.tz, e.end_tz) == (datetime(2026, 12, 10, 19), PARIS, None)
    e, _ = one("DTSTART:20261210T090000Z", "DTEND:20261210T180000Z")
    assert (e.start, e.end, e.tz, e.end_tz) == (datetime(2026, 12, 10, 10), datetime(2026, 12, 10, 19), PARIS, None)
    # an unknown end TZID falls back to the device zone and is counted, like a start's
    e, pv = one("DTSTART;TZID=America/New_York:20261210T100000", "DTEND;TZID=Nowhere/Land:20261210T230000")
    assert (e.end, e.tz, e.end_tz, pv.unresolved_zones) == (datetime(2026, 12, 10, 23), NY, PARIS, 1)
    # an arrival before the departure (instants) is dropped
    e, _ = one("DTSTART;TZID=Europe/Paris:20261210T100000", "DTEND;TZID=America/New_York:20261210T030000")
    assert e.end is None and e.end_tz is None


def test_export_writes_dtend_in_end_tz_with_its_vtimezone():
    flight = new_event("Vol", datetime(2026, 12, 10, 10), datetime(2026, 12, 10, 13), tz=PARIS, end_tz=NY, now=NOW)
    text = export_calendar([flight], CalendarSettings(), None, EXPORT_NOW).text
    assert "DTSTART;TZID=Europe/Paris:20261210T100000\r\n" in text
    assert "DTEND;TZID=America/New_York:20261210T130000\r\n" in text
    assert "TZID:America/New_York\r\n" in text and "TZID:Europe/Paris\r\n" in text
    back = read_sources([IcsSource.from_bytes("v.ics", text.encode())], DEVICE_ZONE, NOW)[0].events[0]
    assert (back.start, back.end, back.tz, back.end_tz) == (flight.start, flight.end, PARIS, NY)
