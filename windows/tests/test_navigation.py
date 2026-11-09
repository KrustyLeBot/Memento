"""Back from a detail returns where it was opened: the agenda (same view and period) for an activity, a birthday or
a note tapped there, and a list at the same place with the opened row in view."""
from datetime import datetime, time, timedelta, timezone

import pytest
from PySide6.QtCore import QEvent, QPoint
from PySide6.QtWidgets import QApplication, QLabel

from agenda_ui_data import ZONE, at, populate
from memento_win.core import contacts as ct
from memento_win.core import note as fmt
from memento_win.core.activity import ActivityInfo
from memento_win.core.agenda.entries import EntryKind
from memento_win.core.agenda.model import Event, event_to_note
from memento_win.core.note import Note
from memento_win.core.store import Store, now_ms
from memento_win.core.track import Sport
from memento_win.services import updater
from memento_win.ui import agenda_data, theme
from memento_win.ui.contacts_view import ID_ROLE
from memento_win.ui.main_window import MainWindow
from memento_win.ui.scroll_memory import fully_visible


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
    w.resize(1000, 700)
    w.show()
    yield w
    w.editor.save_timer.stop()
    w._update_timer.stop()
    w.close()
    w.deleteLater()
    QApplication.sendPostedEvents(None, QEvent.Type.DeferredDelete)


def settle() -> None:
    for _ in range(3):
        QApplication.processEvents()


def shown_in(area, widget) -> bool:
    top = widget.mapTo(area.viewport(), QPoint(0, 0)).y()
    return fully_visible(top, top + widget.height(), area.viewport().height())


def add_activity(store: Store, i: int, days_ago: int) -> Note:
    start = int(datetime.combine(datetime.now().date() - timedelta(days=days_ago), time(9, 0), tzinfo=timezone.utc).timestamp() * 1000)
    nid = f"act{i:03d}"
    return store.save(Note(path=f"activities/{fmt.file_name_for(f'Sortie {i}', nid)}", id=nid, title=f"Sortie {i}",
                           created=now_ms(), updated=now_ms(), activity=ActivityInfo(Sport.WALK, start, 3600, 3600, 5000.0)))


# ---- origin ----

def test_activity_and_birthday_from_the_agenda_go_back_to_the_agenda_on_the_same_period(window):
    populate(window.store, window.agenda.data.today())
    window.refresh()
    screen = window.agenda
    today = screen.data.today()
    screen.set_view("week")
    screen.step(-1)
    screen.step(1)  # back on this week, whatever the view keeps
    anchor = screen.anchor
    settle()
    hours = screen.grid.scroll.verticalScrollBar().value()

    ride = next(e for e in screen.data.entries(today - timedelta(days=1), today - timedelta(days=1)).entries
                if e.kind == EntryKind.ACTIVITY)
    screen.activate(ride)
    assert window.stack.currentWidget() is window.activities and window.activities.current is not None
    window._escape()
    assert window.stack.currentWidget() is screen and window.module == "agenda"
    assert screen.view == "week" and screen.anchor == anchor
    assert screen.grid.scroll.verticalScrollBar().value() == hours
    assert window.activities.current is None  # the module, opened later, shows its list

    # the detail's own back button and a birthday's contact
    bday = today + timedelta(days=4)
    screen.activate(next(e for e in screen.data.by_day(bday, bday)[bday] if e.kind == EntryKind.BIRTHDAY))
    assert window.stack.currentWidget() is window.contacts
    window.contacts.back.click()
    assert window.stack.currentWidget() is screen and screen.anchor == anchor and screen.view == "week"

    screen.activate(ride)
    window.activities.detail_back.emit()  # what the detail page's back arrow sends
    assert window.stack.currentWidget() is screen

    # a dated note opens the editor; closing it comes back too
    tomorrow = today + timedelta(days=1)
    screen.activate(next(e for e in screen.data.by_day(tomorrow, tomorrow)[tomorrow] if e.kind == EntryKind.NOTE))
    assert window.stack.currentWidget() is window.editor
    window.close_editor()
    assert window.stack.currentWidget() is screen and screen.anchor == anchor and screen.view == "week"


def test_a_module_chosen_in_between_forgets_the_agenda(window):
    populate(window.store, window.agenda.data.today())
    window.refresh()
    screen = window.agenda
    today = screen.data.today()
    ride = next(e for e in screen.data.entries(today - timedelta(days=1), today - timedelta(days=1)).entries
                if e.kind == EntryKind.ACTIVITY)
    screen.activate(ride)
    window.show_module("notes")  # the rail
    window.show_module("activities")
    window.activities.open_activity(window.activities.activities[0])
    window._escape()
    assert window.stack.currentWidget() is window.activities and window.activities.current is None


def test_the_agenda_list_keeps_its_place_after_a_note(window):
    store = window.store
    today = window.agenda.data.today()
    populate(store, today)
    for i in range(40):  # one dated note a day: a list longer than the window
        n = Note(path="", id=fmt.generate_id(), title=f"Rappel {i}", created=now_ms(), updated=now_ms())
        store.save(event_to_note(Event(note=n, start=at(today + timedelta(days=i + 1), 9))))
    window.refresh()
    screen = window.agenda
    screen.set_view("agenda")
    settle()
    view = screen.schedule
    bar = view.verticalScrollBar()
    assert bar.maximum() > 0
    at_row = next(i for i, r in enumerate(view.model_.rows) if r.entry is not None and r.entry.title == "Rappel 20")
    view.scrollTo(view.model_.index(at_row), view.ScrollHint.PositionAtCenter)
    settle()
    row = view.model_.rows[at_row]
    value = bar.value()
    assert value > 0
    screen.activate(row.entry)
    assert window.stack.currentWidget() is window.editor
    window.close_editor()
    settle()
    assert window.stack.currentWidget() is screen
    assert bar.value() == value
    rect = view.visualRect(view.model_.index(next(i for i, r in enumerate(view.model_.rows) if r.entry and r.entry.key == row.entry.key)))
    assert fully_visible(rect.top(), rect.bottom() + 1, view.viewport().height())


# ---- lists ----

def test_activities_list_keeps_its_scroll_and_the_opened_row(window):
    for i in range(40):
        add_activity(window.store, i, days_ago=i + 1)
    window.refresh()
    window.show_module("activities")
    screen = window.activities
    settle()
    bar = screen.scroll.verticalScrollBar()
    assert bar.maximum() > 0
    bar.setValue(bar.maximum() // 2)
    settle()
    row = next(r for r in screen.row_widgets.values() if shown_in(screen.scroll, r))
    value = bar.value()
    row.clicked.emit(row.note)
    assert screen.current is not None
    window.refresh()  # a rescan while the detail is shown
    window._escape()
    settle()
    assert screen.current is None and bar.value() == value
    assert shown_in(screen.scroll, screen.row_widgets[row.note.id])

    # the list changed meanwhile: newer activities above it, the opened row is brought back into view
    opened = screen.row_widgets[row.note.id].note
    screen.row_widgets[opened.id].clicked.emit(opened)
    for i in range(40, 70):
        add_activity(window.store, i, days_ago=0)
    window.refresh()
    window.activities.detail_back.emit()
    settle()
    assert shown_in(screen.scroll, screen.row_widgets[opened.id])


def test_notes_list_keeps_its_scroll_and_the_opened_card(window):
    for i in range(80):
        window.store.save(Note(path="", id=fmt.generate_id(), title=f"Note {i}", body="ligne\n" * (i % 4),
                               created=now_ms(), updated=now_ms() - i * 1000))
    window.refresh()
    window.show_module("notes")
    screen = window.list_screen
    screen.finish_building()
    settle()
    bar = screen.scroll.verticalScrollBar()
    assert bar.maximum() > 0
    bar.setValue(bar.maximum() * 2 // 3)
    settle()
    card = next(w for kind, w in screen._items if kind == "card" and shown_in(screen.scroll, w))
    value = bar.value()
    card.clicked.emit(card.note)
    assert window.stack.currentWidget() is window.editor
    # a note synced in meanwhile (the oldest, at the end): the list is rebuilt, its place kept
    window.store.save(Note(path="", id=fmt.generate_id(), title="Ancienne", created=1_600_000_000_000, updated=1_600_000_000_000))
    window.refresh()
    window.close_editor()
    settle()
    assert window.stack.currentWidget() is screen
    assert bar.value() == value
    fresh = screen.card_of(card.note.id)
    assert fresh is not None and shown_in(screen.scroll, fresh)

    # edited, the note moves to the top of the list: the list follows it
    fresh.clicked.emit(fresh.note)
    window.editor.title.setText(card.note.title + " modifiée")
    window.close_editor()
    settle()
    moved = screen.card_of(card.note.id)
    assert screen._items[0][1] is moved and shown_in(screen.scroll, moved)


def test_contacts_list_keeps_its_scroll_and_the_opened_contact(window):
    for i in range(80):
        window.store.contacts.save(ct.Contact(first_name=f"Prénom{i:02d}", last_name=chr(ord("A") + i % 26) + "nom"))
    window.refresh()
    window.show_module("contacts")
    screen = window.contacts
    settle()
    bar = screen.list.verticalScrollBar()
    assert bar.maximum() > 0
    bar.setValue(bar.maximum() // 2)
    settle()
    item = screen.list.itemAt(10, screen.list.viewport().height() // 2)
    while not item.data(ID_ROLE):  # a header: the next row is a contact
        item = screen.list.item(screen.list.row(item) + 1)
    row, path = screen.list.row(item), item.data(ID_ROLE)
    value = bar.value()
    screen._activated(item)
    assert screen.current is not None
    window.refresh()  # a rescan rebuilds the hidden list
    window._escape()
    settle()
    assert screen.current is None and bar.value() == value
    assert screen.list.item(row).data(ID_ROLE) == path
    rect = screen.list.visualItemRect(screen.list.item(row))
    assert fully_visible(rect.top(), rect.bottom() + 1, screen.list.viewport().height())

    # opened again, then contacts added above it: back, it is brought into view
    screen._activated(screen.list.item(row))
    for i in range(30):
        window.store.contacts.save(ct.Contact(first_name=f"Avant{i:02d}", last_name="Aaa"))
    window.refresh()
    window.contacts.back.click()
    settle()
    moved = next(i for i in range(screen.list.count()) if screen.list.item(i).data(ID_ROLE) == path)
    rect = screen.list.visualItemRect(screen.list.item(moved))
    assert fully_visible(rect.top(), rect.bottom() + 1, screen.list.viewport().height())


# ---- map expanded over the window ----

def test_the_map_expands_over_the_window_and_comes_back_in_place(window):
    window.show_module("activities")
    acts = window.activities
    panel = acts._map()
    acts.detail_layout.addWidget(QLabel("avant"))  # the map is not first in its page
    acts.detail_layout.addWidget(panel)
    acts.stack.setCurrentWidget(acts.detail_page)
    panel.show()
    settle()
    index = acts.detail_layout.indexOf(panel)
    panel.expand_button.click()
    settle()
    assert panel.expanded and panel.parentWidget() is window and panel.geometry() == window.rect()
    assert panel.expand_button.text() == "Réduire"
    window.resize(1100, 760)
    settle()
    assert panel.geometry() == window.rect()
    window._escape()  # Escape shrinks the map, it does not leave the activity
    settle()
    assert not panel.expanded and acts.detail_layout.indexOf(panel) == index and panel.isVisible()
    assert acts.stack.currentWidget() is acts.detail_page and panel.expand_button.text() == "Agrandir"
    panel.expand()
    window.show_module("notes")  # leaving the module puts it back first
    assert not panel.expanded


# ---- a long track opens without freezing the window ----

def test_an_activity_track_is_read_off_the_window_thread_then_cached(window, tmp_path):
    import time as _time
    gpx = tmp_path / "ride.gpx"
    pts = "".join(f'<trkpt lat="{45 + i * 3 / 111320:.7f}" lon="6.0"><ele>{1000 + i}</ele>'
                  f'<time>2024-01-01T10:{i // 60:02d}:{i % 60:02d}Z</time></trkpt>' for i in range(120))
    gpx.write_text(f'<?xml version="1.0"?><gpx><trk><name>Longue</name><trkseg>{pts}</trkseg></trk></gpx>', encoding="utf-8")
    note = window.store.import_activity(gpx, None, None)
    acts = window.activities
    window.store._analyses.clear()  # as after a restart: nothing analysed yet
    window.show_module("activities")
    acts.open_activity(note)
    texts = lambda: [w.text() for w in acts.detail_page.findChildren(QLabel)]  # noqa: E731
    assert "Chargement du tracé…" in texts()
    deadline = _time.time() + 10
    while "Chargement du tracé…" in texts() and _time.time() < deadline:
        QApplication.processEvents()
        _time.sleep(0.01)
    assert "Chargement du tracé…" not in texts() and acts.current is note
    acts.open_activity(note)  # cached now: filled at once
    assert "Chargement du tracé…" not in texts()


def _long_gpx(path, minutes: int = 3, stops: int = 0):
    """A walk at 1 Hz, standing still for the first 40 seconds of every [stops]th minute."""
    pts, lat, t = [], 45.0, 0
    for m in range(minutes):
        for s in range(60):
            if not (stops and m % stops == 0 and s < 40):
                lat += 1.4 / 111320
            pts.append(f'<trkpt lat="{lat:.7f}" lon="6.0"><ele>{1000 + t / 10:.1f}</ele>'
                       f'<time>2024-01-01T{10 + t // 3600:02d}:{t // 60 % 60:02d}:{t % 60:02d}Z</time></trkpt>')
            t += 1
    path.write_text(f'<?xml version="1.0"?><gpx><trk><name>Course</name><trkseg>{"".join(pts)}</trkseg></trk></gpx>',
                    encoding="utf-8")
    return path


def _until(condition, seconds: float = 10) -> None:
    import time as _time
    deadline = _time.time() + seconds
    while not condition() and _time.time() < deadline:
        QApplication.processEvents()
        _time.sleep(0.01)


def test_an_import_reads_and_analyses_off_the_window_thread(window, tmp_path, monkeypatch):
    from PySide6.QtWidgets import QDialog, QFileDialog
    from memento_win.ui import activities_view
    source = _long_gpx(tmp_path / "course.gpx")
    monkeypatch.setattr(QFileDialog, "getOpenFileNames", lambda *a, **k: ([str(source)], ""))
    monkeypatch.setattr(activities_view.ImportDialog, "exec", lambda self: QDialog.DialogCode.Accepted)
    acts = window.activities
    window.show_module("activities")
    acts.import_files()
    # the call returns at once: the file is read on a worker, the window says so and takes no second import
    assert acts.busy and acts.status.text() == "Lecture de course.gpx…" and not acts.import_button.isEnabled()
    _until(lambda: acts.current is not None)
    assert not acts.busy and acts.footer.isHidden() and acts.import_button.isEnabled()
    assert acts.current.title == "Course" and acts.current.activity.file
    assert [n.title for n in window.store.scan_activities()] == ["Course"]
    QApplication.processEvents()
    assert acts.map_panel is not None and acts.map_panel.isVisible()  # the analysis was kept: shown at once


def test_a_new_activity_adds_its_row_and_keeps_the_others(window):
    for i in range(3):
        add_activity(window.store, i, days_ago=i)
    window.refresh()
    acts = window.activities
    before = dict(acts.row_widgets)
    add_activity(window.store, 9, days_ago=9)
    window.refresh()
    assert len(acts.row_widgets) == 4
    assert all(acts.row_widgets[key] is row for key, row in before.items())
    acts.select_button.setChecked(True)  # the rows get a check box: all rebuilt
    assert not any(acts.row_widgets[key] is row for key, row in before.items())


def test_hundreds_of_stops_are_one_painted_list(window, tmp_path):
    from memento_win.ui.activities_view import StopList, fmt_hour
    note = window.store.import_activity(_long_gpx(tmp_path / "pauses.gpx", minutes=60, stops=2), None, None)
    window.show_module("activities")
    window.activities.open_activity(note)
    lists = window.activities.detail_page.findChildren(StopList)
    assert len(lists) == 1 and len(lists[0].rows) == 30
    assert lists[0].rows[0] == ("Arrêt détecté", f"0 min 44 · {fmt_hour(note.activity.start)}")
    assert lists[0].height() >= 30 * lists[0].fontMetrics().height()


def test_the_chart_finds_the_point_under_the_mouse_by_bisection(app):
    import random
    from memento_win.ui.activities_view import Chart
    rng = random.Random(3)
    xs, x = [], 0.0
    for _ in range(300):
        x += rng.choice([0.0, 0.0, 1.0, 2.5])  # stops: the same distance several times
        xs.append(x)
    chart = Chart("Altitude", xs, [float(i % 7) for i in range(300)], "0 m", "6 m", "#000000",
                  [(0.0, float(i)) for i in range(300)])
    canvas = chart.canvas
    canvas.resize(997, 110)
    for pos in [0.0, 0.4, 13.5, 250.25, 498.5, 996.0, 997.0, 1200.0, -5.0]:
        target = xs[0] + max(0.0, min(1.0, pos / canvas.width())) * (xs[-1] - xs[0])
        assert canvas.nearest(pos) == min(range(len(xs)), key=lambda k: abs(xs[k] - target))
    for x_target in xs[::7]:  # exactly on a point, and between two
        for pos in (x_target / xs[-1] * canvas.width(), (x_target + 0.5) / xs[-1] * canvas.width()):
            target = xs[0] + max(0.0, min(1.0, pos / canvas.width())) * (xs[-1] - xs[0])
            assert canvas.nearest(pos) == min(range(len(xs)), key=lambda k: abs(xs[k] - target))


# ---- background jobs and the page on screen ----

@pytest.fixture
def quiet(monkeypatch):
    """Message boxes answered at once; what they said is kept."""
    from PySide6.QtWidgets import QMessageBox
    said = []
    for kind in ("information", "warning"):
        monkeypatch.setattr(QMessageBox, kind, lambda parent, title, text, *a, **k: said.append(title))
    return said


def _blocked_job(acts, then=lambda r, e: None):
    """A job that runs until the returned event is set."""
    import threading
    gate = threading.Event()
    assert acts._run("Test…", lambda progress: gate.wait(10), then)
    return gate


def test_an_edit_on_a_page_built_before_a_job_saved_the_activity_keeps_the_job_result(window, tmp_path, monkeypatch, quiet):
    from PySide6.QtWidgets import QFileDialog
    acts = window.activities
    note = window.store.import_activity(_long_gpx(tmp_path / "a.gpx"), None, None)
    window.show_module("activities")
    acts.open_activity(note)
    alt = note.activity.file.rsplit(".", 1)[0] + ".alt"
    (window.store.root / alt).write_text("", encoding="utf-8")
    window.store.save(note.copy(activity=ActivityInfo(**{**note.activity.__dict__, "alt": alt}),
                                attachments=note.attachments + [alt]))  # what a background job saves meanwhile
    photo = tmp_path / "p.png"
    photo.write_bytes(b"png")
    monkeypatch.setattr(QFileDialog, "getOpenFileName", lambda *a, **k: (str(photo), ""))
    acts._add_photo(note)  # the page's own, older, copy
    saved = window.store.current(note)
    assert saved.activity.alt == alt and alt in saved.attachments and len(saved.attachments) == 3


def test_a_finished_job_does_not_pull_the_user_back_to_its_activity(window, quiet):
    acts = window.activities
    a, b = add_activity(window.store, 1, 1), add_activity(window.store, 2, 2)
    window.refresh()
    window.show_module("activities")
    acts.open_activity(a)
    gate = _blocked_job(acts, lambda result, error: acts._reopen_if_shown(a))
    acts.open_activity(b)  # the user moved on while it ran
    gate.set()
    _until(lambda: not acts.busy)
    assert acts.current.path == b.path
    acts.show_list()
    gate = _blocked_job(acts, lambda result, error: acts._reopen_if_shown(a))
    gate.set()
    _until(lambda: not acts.busy)
    assert acts.current is None and acts.stack.currentWidget() is acts.list_page


def test_a_sport_change_refused_while_a_job_runs_still_saves_the_text(window, tmp_path, monkeypatch, quiet):
    from PySide6.QtWidgets import QDialog
    from memento_win.ui import activities_view
    acts = window.activities
    note = window.store.import_activity(_long_gpx(tmp_path / "a.gpx"), None, None)
    window.show_module("activities")
    acts.open_activity(note)

    def typed(dialog):
        dialog.title.setText("Nouveau titre")
        dialog.description.setPlainText("Une longue description")
        dialog.sport.setCurrentIndex(dialog.sport.findData(Sport.BIKE))
        return QDialog.DialogCode.Accepted
    monkeypatch.setattr(activities_view.EditDialog, "exec", typed)
    gate = _blocked_job(acts)
    acts._edit(note)
    saved = window.store.current(note)
    assert saved.title == "Nouveau titre" and saved.body == "Une longue description"
    assert saved.activity.sport == note.activity.sport and "Sport inchangé" in quiet
    gate.set()
    _until(lambda: not acts.busy)


def test_a_job_finished_after_a_folder_change_is_dropped(window, tmp_path, quiet):
    acts = window.activities
    called = []
    gate = _blocked_job(acts, lambda result, error: called.append(result))
    other = tmp_path / "other"
    other.mkdir()
    window.set_store(Store(other))
    gate.set()
    _until(lambda: not acts.busy)
    QApplication.processEvents()
    assert called == [] and not acts.busy and acts.import_button.isEnabled()


def test_an_activity_reopened_while_its_track_loads_is_loaded_once(window, tmp_path, monkeypatch):
    import threading
    acts = window.activities
    note = window.store.import_activity(_long_gpx(tmp_path / "a.gpx"), None, None)
    window.store._analyses.clear()
    real, calls, gate = window.store.analysis, [], threading.Event()

    def slow(info):
        calls.append(info)
        gate.wait(10)
        return real(info)
    monkeypatch.setattr(window.store, "analysis", slow)
    window.show_module("activities")
    acts.open_activity(note)
    acts.show_list()
    acts.open_activity(note)
    gate.set()
    texts = lambda: [w.text() for w in acts.detail_layout.itemAt(0).widget().findChildren(QLabel)]  # noqa: E731
    _until(lambda: "Chargement du tracé…" not in texts())
    assert len(calls) == 1 and "Chargement du tracé…" not in texts() and not acts._loading


def test_a_late_track_load_does_not_replace_the_page_of_an_edited_activity(window, tmp_path, monkeypatch):
    import threading
    acts = window.activities
    note = window.store.import_activity(_long_gpx(tmp_path / "a.gpx"), None, None)
    window.store._analyses.clear()
    real, gate, done = window.store.analysis, threading.Event(), threading.Event()

    def slow(info):
        gate.wait(10)
        try:
            return real(info)
        finally:
            done.set()
    monkeypatch.setattr(window.store, "analysis", slow)
    window.show_module("activities")
    acts.open_activity(note)  # loads the walk
    edited = window.store.update_activity(note, note.title, "", Sport.BIKE)  # analysed and cached as a ride
    acts._reopen(edited)
    page = acts.detail_layout.itemAt(0).widget()
    gate.set()
    _until(done.is_set)
    for _ in range(20):
        QApplication.processEvents()
    assert acts.detail_layout.itemAt(0).widget() is page and acts.current.activity.sport == Sport.BIKE
