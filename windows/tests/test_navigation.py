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
