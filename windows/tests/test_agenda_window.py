"""The Agenda screen in the main window (synthetic events only): the four views, the editor and its recurrence
editors, series scopes, delete + undo, calendars, the import dialog on the shared vectors, export, dated notes."""
import io
import zipfile
from datetime import date, datetime, timedelta
from pathlib import Path

import pytest
from PySide6.QtCore import QDate, QEvent, QTime
from PySide6.QtWidgets import QApplication, QDialog

from agenda_ui_data import ZONE, at, populate
from scriptorium_win.core import note as fmt
from scriptorium_win.core.agenda import rules
from scriptorium_win.core.agenda.calendars import CalendarSettings
from scriptorium_win.core.agenda.entries import EntryKind
from scriptorium_win.core.agenda.model import event_from_note, new_event
from scriptorium_win.core.note import Note
from scriptorium_win.core.store import Store
from scriptorium_win.services import updater
from scriptorium_win.ui import agenda_data, agenda_editor, theme
from scriptorium_win.ui.agenda_editor import EventDialog, RecurrenceDialog
from scriptorium_win.ui.main_window import MainWindow

VECTORS = Path(__file__).resolve().parents[2] / "testdata" / "agenda"


class FakeConfig:
    workdir = None
    geometry = None
    grid = True
    theme = "light"
    last_module = "agenda"
    hidden_modules: set[str] = set()
    contacts_first_name_first = False
    agenda_week_start = 0
    agenda_event_zone = False
    agenda_default_alerts = [-15]
    agenda_view = "agenda"


@pytest.fixture(scope="module")
def app():
    application = QApplication.instance() or QApplication([])
    application.setStyle("Fusion")
    theme.apply_theme(application, "light")
    return application


@pytest.fixture
def window(app, tmp_path, monkeypatch):
    monkeypatch.setattr(updater, "latest", lambda: None)
    monkeypatch.setattr(agenda_data, "device_zone", lambda: ZONE)
    w = MainWindow(FakeConfig(), Store(tmp_path))
    w.resize(1200, 800)
    w.show()
    yield w
    w.editor.save_timer.stop()
    w._update_timer.stop()
    # Windows left alive are restyled by every later theme change: the suite would slow down test after test.
    w.close()
    w.deleteLater()
    QApplication.sendPostedEvents(None, QEvent.Type.DeferredDelete)


@pytest.fixture
def filled(window):
    events = populate(window.store, window.agenda.data.today())
    window.refresh()
    return window, events


def titles(entries) -> list[str]:
    return [e.title for e in entries]


def agenda_files(root: Path) -> dict[str, str]:
    base = root / "agenda"
    return {str(p.relative_to(root)): p.read_text(encoding="utf-8") for p in base.rglob("*.md")} if base.is_dir() else {}


def entry(screen, title: str, day: date):
    return next(e for e in screen.data.by_day(day, day)[day] if e.title == title)


# ---- views ----

def test_every_view_renders_the_synthetic_agenda(filled, app):
    window, events = filled
    screen = window.agenda
    today = screen.data.today()
    assert window.stack.currentWidget() is screen
    for view in ("agenda", "month", "week", "day"):
        screen.set_view(view)
        app.processEvents()
        screen.grab()  # paints

    screen.set_view("agenda")
    rows = screen.schedule.model_.rows
    assert any(r.kind == "today" and "Aujourd'hui" in r.text for r in rows)
    assert {"Dentiste", "Déjeuner avec Paul", "Préparer la réunion", "Camille Martin"} - set(
        r.entry.title.replace("Anniversaire de ", "") for r in rows if r.entry) == set()

    screen.set_view("month")
    screen.month.grab()
    shown = {payload.title for _, kind, payload in screen.month.hits.items if kind == "entry"}
    assert "Vacances à la montagne" in shown or (today + timedelta(days=5)).month != today.month
    busy = today + timedelta(days=2)
    more = [payload for _, kind, payload in screen.month.hits.items if kind == "more"]
    assert busy in more  # five events and the morning meeting: "+N"

    screen.go_to(today, "day")
    blocks = {b.entry.title: b for b in screen.grid.blocks}
    assert blocks["Déjeuner avec Paul"].lanes == 2 and blocks["Appel banque"].lanes == 2
    assert blocks["Dentiste"].start == 14 * 60 + 30 and blocks["Dentiste"].end == 15 * 60 + 15


def test_series_exception_and_skipped_occurrence_show_as_edited(filled):
    window, events = filled
    screen = window.agenda
    monday = screen.data.today() - timedelta(days=screen.data.today().weekday())
    thursday = monday + timedelta(days=3)
    day = screen.data.by_day(thursday, thursday)[thursday]
    assert "Réunion d'équipe (déplacée)" in titles(day) and "Réunion d'équipe" not in titles(day)
    skipped = monday + timedelta(days=7)
    assert "Réunion d'équipe" not in titles(screen.data.by_day(skipped, skipped)[skipped])
    assert "Réunion d'équipe" in titles(screen.data.by_day(monday + timedelta(days=14), monday + timedelta(days=14))[monday + timedelta(days=14)])


def test_week_view_all_day_strip_and_multi_day(filled, app):
    window, events = filled
    screen = window.agenda
    start = screen.data.today() + timedelta(days=5)
    screen.go_to(start, "week")
    app.processEvents()
    assert "Vacances à la montagne" in [s.entry.title for s in screen.grid.allday]
    screen.go_to(screen.data.today() + timedelta(days=1), "day")
    concert = next(b for b in screen.grid.blocks if b.entry.title == "Concert au Transbordeur")
    assert (concert.start, concert.end) == (21 * 60, 1440)
    screen.go_to(screen.data.today() + timedelta(days=2), "day")
    tail = next(b for b in screen.grid.blocks if b.entry.title == "Concert au Transbordeur")
    assert (tail.start, tail.end) == (0, 30)


def test_dst_week_keeps_wall_times(window, app):
    store = window.store
    store.agenda.save(new_event("Yoga", datetime(2027, 3, 4, 14, 30), datetime(2027, 3, 4, 15, 30), tz=ZONE,
                                rrule="FREQ=WEEKLY;BYDAY=TH"))
    store.agenda.save(new_event("Nuit courte", datetime(2027, 3, 28, 2, 30), datetime(2027, 3, 28, 4, 0), tz=ZONE))
    store.agenda.save(new_event("Séminaire", date(2027, 3, 26), date(2027, 3, 29)))
    window.refresh()
    screen = window.agenda
    screen.go_to(date(2027, 3, 24), "week")
    app.processEvents()
    screen.grid.grab()
    blocks = {(b.entry.title, b.col): b for b in screen.grid.blocks}
    assert blocks[("Yoga", 3)].start == 14 * 60 + 30  # Thursday before the change
    assert blocks[("Nuit courte", 6)].start == 3 * 60 + 30  # 02:30 does not exist that night: 03:30
    assert [s.entry.title for s in screen.grid.allday] == ["Séminaire"]
    screen.go_to(date(2027, 4, 1), "day")
    assert screen.grid.blocks[0].start == 14 * 60 + 30  # after the change, still 14:30
    screen.go_to(date(2027, 3, 1), "month")
    screen.month.grab()


def test_search_lists_events_and_dated_notes(filled, app):
    window, _ = filled
    screen = window.agenda
    screen.search.setText("réunion")
    screen.render()
    assert window.agenda.stack.currentWidget() is screen.results
    found = [r.entry.title for r in screen.results.model_.rows if r.entry]
    assert "Réunion d'équipe" in found and "Préparer la réunion" in found and "Réunion d'équipe (déplacée)" in found
    screen.search.setText("lilas")  # location
    screen.render()
    assert [r.entry.title for r in screen.results.model_.rows if r.entry] == ["Dentiste"]
    screen.search.clear()
    screen.render()
    assert screen.stack.currentWidget() is screen.schedule


def test_no_rebuild_when_nothing_changed(filled):
    window, _ = filled
    screen = window.agenda
    version = screen.data.version
    assert screen.data.load() is False
    window.refresh()
    assert screen.data.version == version
    rendered = screen._rendered
    screen.render()
    assert screen._rendered is rendered


def test_navigation_keys_and_today(filled, app):
    window, _ = filled
    screen = window.agenda
    screen.set_view("month")
    today = screen.data.today()
    screen.step(1)
    assert screen.anchor.month != today.month
    screen.go_today()
    assert screen.anchor == today
    screen.set_view("week")
    screen.step(-1)
    assert screen.anchor == today - timedelta(days=7)


# ---- opening ----

def test_clicks_open_activity_contact_and_note(filled, app):
    window, _ = filled
    screen = window.agenda
    today = screen.data.today()
    acts = screen.data.entries(today - timedelta(days=1), today - timedelta(days=1)).entries
    ride = next(e for e in acts if e.kind == EntryKind.ACTIVITY)
    screen.activate(ride)
    assert window.stack.currentWidget() is window.activities and window.activities.current is not None
    window.show_module("agenda")
    bday = today + timedelta(days=4)
    screen.activate(next(e for e in screen.data.by_day(bday, bday)[bday] if e.kind == EntryKind.BIRTHDAY))
    assert window.stack.currentWidget() is window.contacts and window.contacts.current.first_name == "Camille"
    window.show_module("agenda")
    tomorrow = today + timedelta(days=1)
    screen.activate(next(e for e in screen.data.by_day(tomorrow, tomorrow)[tomorrow] if e.kind == EntryKind.NOTE))
    assert window.stack.currentWidget() is window.editor and window.editor.current.title == "Préparer la réunion"
    window.close_editor()
    assert window.stack.currentWidget() is screen


# ---- editor ----

def test_presets_round_trip_through_the_rule(window):
    start = datetime(2026, 11, 26, 18, 0)  # the last Thursday of November
    for key, _ in rules.presets(start):
        ev = new_event("Cours", start, start + timedelta(hours=1), tz=ZONE)
        dialog = EventDialog(window, ev, CalendarSettings(), ZONE, date(2026, 11, 2), is_new=True)
        dialog.choose_preset(key)
        built = dialog.build()
        assert rules.preset_of(built.rrule, built.start, built.tz, ZONE) == key
        again = EventDialog(window, built, CalendarSettings(), ZONE, date(2026, 11, 2), is_new=False)
        assert again.repeat.currentData() == key
        if key != "none":
            assert again.repeat_preview.text().startswith("Prochaines : ")


@pytest.mark.parametrize("rule", [
    "FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,TH;COUNT=10",
    "FREQ=MONTHLY;BYDAY=-1FR;UNTIL=20271231T225959Z",
    "FREQ=MONTHLY;INTERVAL=3;BYMONTHDAY=15",
    "FREQ=MONTHLY;BYDAY=2TU",
    "FREQ=DAILY;INTERVAL=3",
    "FREQ=YEARLY;INTERVAL=2;COUNT=5",
])
def test_custom_editor_round_trips_the_rule(window, rule):
    start = datetime(2026, 11, 12, 9, 0)
    spec = rules.parse_spec(rule, start, ZONE, ZONE)
    dialog = RecurrenceDialog(window, spec, start, ZONE, ZONE, date(2026, 11, 2))
    assert dialog.rrule() == rule
    assert dialog.preview.text().startswith("Prochaines : ")
    ev = new_event("Atelier", start, start + timedelta(hours=1), tz=ZONE, rrule=rule)
    editor = EventDialog(window, ev, CalendarSettings(), ZONE, date(2026, 11, 2), is_new=False)
    assert editor.repeat.currentData() in ("custom", "weekly", "monthly_nth", "monthly_last", "monthly_day", "daily", "yearly")
    assert editor.build().rrule == rule


def test_custom_editor_builds_until_and_nth_weekday(window):
    start = datetime(2026, 11, 12, 9, 0)
    dialog = RecurrenceDialog(window, rules.default_spec("MONTHLY", start), start, ZONE, ZONE, date(2026, 11, 2))
    dialog.by_nth.setChecked(True)
    dialog.nth.setCurrentIndex(dialog.nth.findData(-1))
    dialog.nth_weekday.setCurrentIndex(4)
    dialog.end_until.setChecked(True)
    dialog.until.setDate(QDate(2027, 6, 30))
    assert dialog.rrule() == "FREQ=MONTHLY;BYDAY=-1FR;UNTIL=20270630T215959Z"
    assert dialog.summary.text() == "Tous les mois le dernier vendredi, jusqu'au 30 juin 2027"
    all_day = RecurrenceDialog(window, rules.default_spec("WEEKLY", date(2026, 11, 12)), date(2026, 11, 12), None, ZONE,
                               date(2026, 11, 2))
    all_day.day_buttons[0].setChecked(True)
    all_day.end_count.setChecked(True)
    all_day.count.setValue(4)
    assert all_day.rrule() == "FREQ=WEEKLY;BYDAY=MO,TH;COUNT=4"


def test_imported_rule_is_shown_read_only_and_kept(window):
    rule = "FREQ=WEEKLY;BYDAY=MO;BYHOUR=9,17"
    ev = new_event("Relevé", datetime(2026, 11, 2, 9, 0), datetime(2026, 11, 2, 9, 30), tz=ZONE, rrule=rule)
    dialog = EventDialog(window, ev, CalendarSettings(), ZONE, date(2026, 11, 2), is_new=False)
    assert dialog.repeat.currentData() == "imported"
    assert "Règle importée" in dialog.repeat.currentText()
    dialog.title.setText("Relevé du compteur")
    assert dialog.build().rrule == rule


def test_editor_time_zone_row_and_end_follows_start(window):
    ev = new_event("Vol", datetime(2026, 12, 1, 14, 0), datetime(2026, 12, 1, 16, 30), tz="America/Montreal")
    dialog = EventDialog(window, ev, CalendarSettings(), ZONE, date(2026, 11, 2), is_new=False)
    dialog.show()
    assert dialog.zone_row.isVisible()  # differs from this PC
    dialog.start_time.setTime(QTime(15, 0))
    built = dialog.build()
    assert built.end == datetime(2026, 12, 1, 17, 30) and built.tz == "America/Montreal"
    same = EventDialog(window, new_event("Ici", datetime(2026, 12, 1, 9), datetime(2026, 12, 1, 10), tz=ZONE),
                       CalendarSettings(), ZONE, date(2026, 11, 2), is_new=True)
    same.show()
    assert not same.zone_row.isVisible() and same.zone_link.isVisible()
    same.all_day.setChecked(True)
    built = same.build()
    assert built.start == date(2026, 12, 1) and built.end is None and built.tz is None


# ---- writing ----

def test_create_edit_one_and_all(filled):
    window, events = filled
    screen = window.agenda
    today = screen.data.today()

    def fill(dialog):
        dialog.title.setText("Rendez-vous notaire")
        dialog.location.setText("Place Bellecour")
        return True

    saved = screen.new_event(datetime.combine(today, datetime.min.time()).replace(hour=16), dialog_hook=fill)
    assert saved.title == "Rendez-vous notaire" and saved.alerts == [-15] and saved.calendar == "perso"
    assert saved.path.startswith(f"agenda/{today.year}/")

    monday = today - timedelta(days=today.weekday())
    occurrence_day = monday + timedelta(days=14)
    occ = entry(screen, "Réunion d'équipe", occurrence_day)

    def one(dialog):
        dialog.title.setText("Réunion d'équipe (client)")
        return True

    screen.edit_entry(occ, scope="one", dialog_hook=one)
    day = screen.data.by_day(occurrence_day, occurrence_day)[occurrence_day]
    assert "Réunion d'équipe (client)" in titles(day) and "Réunion d'équipe" not in titles(day)
    series = screen.data.event(events["team"].id)
    assert at(occurrence_day, 10) in series.exdate

    def later(dialog):
        dialog.start_time.setTime(QTime(11, 0))
        return True

    screen.edit_entry(entry(screen, "Réunion d'équipe", monday + timedelta(days=21)), scope="all", dialog_hook=later)
    series = screen.data.event(events["team"].id)
    assert series.start.hour == 11 and series.end.hour == 12
    assert at(occurrence_day, 11) in series.exdate  # skipped and edited occurrences moved with the series
    moved = next(e for e in screen.data.exceptions_of(series.id) if e.title == "Réunion d'équipe (client)")
    assert moved.recurrence_at == at(occurrence_day, 11)
    assert "Réunion d'équipe" not in titles(screen.data.by_day(occurrence_day, occurrence_day)[occurrence_day])


def test_delete_scopes_with_undo(filled):
    window, events = filled
    screen = window.agenda
    root = window.store.root
    before = agenda_files(root)
    monday = screen.data.today() - timedelta(days=screen.data.today().weekday())
    day = monday + timedelta(days=14)

    screen.delete_entry(entry(screen, "Réunion d'équipe", day), "one")
    assert "Réunion d'équipe" not in titles(screen.data.by_day(day, day)[day])
    assert window.snack.isVisible() and window.snack_button.isVisible()
    window._run_undo()
    assert agenda_files(root) == before

    screen.delete_entry(entry(screen, "Réunion d'équipe", day), "all")
    assert screen.data.event(events["team"].id) is None and not screen.data.exceptions_of(events["team"].id)
    window._run_undo()
    assert agenda_files(root) == before

    dentist_day = screen.data.today()
    screen.delete_entry(entry(screen, "Dentiste", dentist_day))
    assert "Dentiste" not in titles(screen.data.by_day(dentist_day, dentist_day)[dentist_day])
    window._run_undo()
    assert agenda_files(root) == before


def test_restore_an_edited_occurrence(filled):
    window, events = filled
    screen = window.agenda
    monday = screen.data.today() - timedelta(days=screen.data.today().weekday())
    thursday = monday + timedelta(days=3)
    screen.restore_entry(entry(screen, "Réunion d'équipe (déplacée)", thursday))
    day = titles(screen.data.by_day(thursday, thursday)[thursday])
    assert "Réunion d'équipe" in day and "Réunion d'équipe (déplacée)" not in day


def test_calendars_create_rename_colour_delete(filled):
    window, _ = filled
    screen = window.agenda
    new_id = screen.create_calendar("Sport")
    screen.rename_calendar(new_id, "Sport & santé")
    screen.recolor_calendar(new_id, "#1E9A8A")
    cal = screen.data.settings.calendar(new_id)
    assert (cal.name, cal.color) == ("Sport & santé", "#1E9A8A")
    assert list(screen._calendar_rows) == ["perso", "travail", "famille", new_id]
    screen.set_calendar_visible("travail", False)
    today = screen.data.today()
    assert "Appel banque" not in titles(screen.data.by_day(today, today)[today])
    screen.set_calendar_visible("travail", True)
    count = screen.calendar_counts()["famille"]
    screen.delete_calendar("famille", move_to="perso")
    assert "famille" not in [c.id for c in screen.data.settings.calendars]
    assert screen.calendar_counts()["perso"] >= count
    screen.set_layer("birthdays", visible=False)
    bday = today + timedelta(days=4)
    assert not [e for e in screen.data.by_day(bday, bday)[bday] if e.kind == EntryKind.BIRTHDAY]


# ---- import / export ----

def test_import_dialog_preview_import_and_undo(window, tmp_path):
    screen = window.agenda
    window.refresh()
    seen = {}

    def drive(dialog):
        seen["rows"] = len(dialog.rows)
        seen["preview"] = dialog.rows[0]["name"].text()
        dialog.rows[0]["name"].setText("Perso Google")
        dialog.start_import()
        seen["title"] = dialog.report_title.text()
        seen["files"] = len(agenda_files(window.store.root))
        dialog._undo()

    screen.import_ics([VECTORS / "google_perso.ics"], dialog_hook=drive)
    assert seen["rows"] == 1 and seen["title"].startswith("Import terminé : ")
    assert seen["files"] > 0
    assert agenda_files(window.store.root) == {}
    assert [c.name for c in screen.data.settings.calendars] == ["Perso"]


def test_import_zip_into_existing_calendar_then_past_imports(window, tmp_path):
    screen = window.agenda
    archive = tmp_path / "export.zip"
    with zipfile.ZipFile(archive, "w") as z:
        for name in ("google_perso.ics", "proton_travail.ics", "outlook_calendrier.ics"):
            z.write(VECTORS / name, name)
    window.refresh()

    def drive(dialog):
        assert len(dialog.rows) == 3
        dialog.rows[0]["target"].setCurrentIndex(dialog.rows[0]["target"].findData("perso"))
        dialog.rows[2]["target"].setCurrentIndex(dialog.rows[2]["target"].findData("__skip__"))
        dialog.dup_buttons["skip"].setChecked(True)
        dialog.start_import()

    screen.import_ics([archive], dialog_hook=drive)
    imports = screen.data.import_ids()
    assert len(imports) == 1 and imports[0][1] == len(agenda_files(window.store.root))
    assert "Perso" in imports[0][2]
    text = screen.undo_import(imports[0][0])
    assert "supprimé" in text and agenda_files(window.store.root) == {}


def test_import_refuses_a_broken_zip(window, tmp_path, monkeypatch):
    bad = tmp_path / "cassé.zip"
    bad.write_bytes(b"pas une archive")
    shown = []
    monkeypatch.setattr(agenda_editor.QMessageBox, "warning", lambda *a: shown.append(a[2]))
    from scriptorium_win.ui import agenda_view
    monkeypatch.setattr(agenda_view.QMessageBox, "warning", lambda *a: shown.append(a[2]))
    window.agenda.import_ics([bad])
    assert shown and "Import impossible" in shown[0]


def test_export_one_calendar_and_all(filled, tmp_path):
    window, _ = filled
    screen = window.agenda
    one = screen.export(tmp_path / "travail.ics", calendar_id="travail", include=False)
    text = one.read_text(encoding="utf-8")
    assert "SUMMARY:Réunion d'équipe" in text and "Dentiste" not in text
    archive = screen.export(tmp_path / "tout.zip", calendar_id=None, include=True)
    names = zipfile.ZipFile(io.BytesIO(archive.read_bytes())).namelist()
    assert {"perso.ics", "travail.ics", "famille.ics", "notes.ics", "activites.ics"} <= set(names)


# ---- dated notes and settings ----

def test_add_a_note_to_the_agenda_and_remove_it(window, monkeypatch):
    note = window.store.save(Note(path="", id=fmt.generate_id(), title="Facture eau", body="Payer avant le 20",
                                  created=1_793_000_000_000, updated=1_793_000_000_000))
    window.refresh()
    window.open_note(note)
    target = window.agenda.data.today() + timedelta(days=3)

    def accept(self):
        self.day.setDate(QDate(target.year, target.month, target.day))
        self.has_time.setChecked(True)
        self.time.setTime(QTime(8, 15))
        self.reminder.setCurrentIndex(self.reminder.findData(-60))
        return QDialog.DialogCode.Accepted

    monkeypatch.setattr(agenda_editor.DateNoteDialog, "exec", accept)
    window.editor.plan()
    saved = event_from_note(window.store.scan()[0])
    assert saved.start == datetime(target.year, target.month, target.day, 8, 15) and saved.alerts == [-60]
    assert window.editor.date_chip.isVisible()
    window.refresh()
    assert "Facture eau" in titles(window.agenda.data.by_day(target, target)[target])

    def remove(self):
        self.removed = True
        return QDialog.DialogCode.Accepted

    monkeypatch.setattr(agenda_editor.DateNoteDialog, "exec", remove)
    window.editor.plan()
    assert event_from_note(window.store.scan()[0]) is None
    assert window.store.scan()[0].body == "Payer avant le 20"
    window.refresh()
    assert "Facture eau" not in titles(window.agenda.data.by_day(target, target)[target])


def test_settings_section_drives_the_agenda(filled, app):
    window, _ = filled
    window.show_settings()
    window.settings.week_buttons[6].click()
    assert window.agenda.prefs.week_start == 6 and window.config.agenda_week_start == 6
    window.agenda.set_view("month")
    window.agenda.render(force=True)
    assert window.agenda.month.grid_start.weekday() == 6
    window.settings.alert_buttons[-60].setChecked(True)
    assert -60 in window.agenda.prefs.default_alerts
    window.settings.default_calendar.setCurrentIndex(window.settings.default_calendar.findData("travail"))
    window.settings.default_calendar.activated.emit(window.settings.default_calendar.currentIndex())
    assert window.agenda.data.settings.default == "travail"
    window.settings.birthdays_box.setChecked(False)
    assert window.agenda.data.settings.layer("birthdays").visible is False


def test_dark_theme_paints_every_view(filled, app):
    window, _ = filled
    theme.apply_theme(app, "dark")
    try:
        window._theme_changed("dark")
        for view in ("agenda", "month", "week", "day"):
            window.agenda.set_view(view)
            assert not window.grab().isNull()
    finally:
        theme.apply_theme(app, "light")


@pytest.mark.parametrize("replace", [False, True])
def test_an_edit_over_an_event_the_sync_replaced_asks_first(filled, monkeypatch, replace):
    from PySide6.QtWidgets import QMessageBox
    window, events = filled
    screen = window.agenda
    path = window.store.root / events["dentist"].path
    asked = []
    monkeypatch.setattr(QMessageBox, "question", lambda *a, **k: asked.append(a) or (
        QMessageBox.StandardButton.Yes if replace else QMessageBox.StandardButton.No))

    def edit(dialog):  # the phone's version lands while the dialog is open
        path.write_text(path.read_text(encoding="utf-8").replace("Dentiste", "Dentiste (déplacé)"), encoding="utf-8")
        dialog.title.setText("Dentiste Dr Martin")
        return True

    screen.edit_entry(entry(screen, "Dentiste", screen.data.today()), dialog_hook=edit)
    assert len(asked) == 1
    text = path.read_text(encoding="utf-8")
    assert ("Dr Martin" in text) is replace and ("(déplacé)" in text) is not replace
