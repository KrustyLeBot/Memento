import pytest
from PySide6.QtCore import Qt
from PySide6.QtTest import QTest
from PySide6.QtWidgets import QApplication

from scriptorium_win.core import note as fmt
from scriptorium_win.core.store import Store, now_ms
from scriptorium_win.services import updater
from scriptorium_win.ui import theme
from scriptorium_win.ui.main_window import MainWindow
from scriptorium_win.ui.update_toast import UpdateToast


class FakeConfig:
    workdir = None
    geometry = None
    grid = True
    theme = "light"
    last_module = "notes"
    hidden_modules: set[str] = set()


@pytest.fixture(scope="module")
def app():
    application = QApplication.instance() or QApplication([])
    application.setStyle("Fusion")
    theme.apply_theme(application, "light")
    return application


@pytest.fixture
def window(app, tmp_path, monkeypatch):
    monkeypatch.setattr(updater, "latest", lambda: None)  # never touch the network
    w = MainWindow(FakeConfig(), Store(tmp_path))
    w.resize(1000, 700)
    w.show()
    yield w
    w.editor.save_timer.stop()
    w._update_timer.stop()


def files(tmp_path):
    return sorted(p.name for p in tmp_path.glob("*.md"))


def read(tmp_path, name=None):
    path = tmp_path / (name or files(tmp_path)[0])
    return fmt.parse(path.name, path.read_text(encoding="utf-8"), 0)


def add(store, title, body="", **kw):
    return store.save(fmt.Note(path="", id=fmt.generate_id(), title=title, body=body,
                               created=now_ms(), updated=now_ms(), **kw))


def test_new_note_is_saved_with_a_fixed_file_name(window, tmp_path):
    window.new_note()
    ed = window.editor
    ed.title.setText("Courses")
    ed.save()
    name = files(tmp_path)[0]
    assert name.startswith("courses-")
    ed.title.setText("Autre titre")
    ed.save()
    assert files(tmp_path) == [name]


def test_empty_new_notes_create_no_file(window, tmp_path):
    window.new_note()
    window.editor.save()
    window.new_note(checklist=True)
    window.editor.save()
    window.close_editor()
    assert files(tmp_path) == []


def test_checklist_items_check_fold_and_reorder(window, tmp_path):
    window.new_note(checklist=True)
    ed = window.editor
    ed.title.setText("Liste")
    ed.checklist.set_items([fmt.CheckItem("Lait"), fmt.CheckItem("Pain"), fmt.CheckItem("Oeufs")])
    ed._edited()
    # tick "Lait": it is shown in the folded section but keeps its place (as on Android)
    first = ed.checklist._rows(ed.checklist.open_box)[0]
    first.box.setChecked(True)
    assert [i.text for i in ed.checklist.items()] == ["Lait", "Pain", "Oeufs"]
    assert [r.item().text for r in ed.checklist._rows(ed.checklist.open_box)] == ["Pain", "Oeufs"]
    assert ed.checklist.fold_button.text().strip() == "1 élément coché"
    # reorder through the drag logic
    rows = ed.checklist._rows(ed.checklist.open_box)
    ed.checklist._drag_start(rows[1])
    y = rows[0].mapToGlobal(rows[0].rect().topLeft()).y() - 5
    ed.checklist._drag_move(rows[1], y)
    ed.checklist._drag_end(rows[1])
    assert [i.text for i in ed.checklist.items()] == ["Oeufs", "Pain", "Lait"]
    ed.save()
    body = read(tmp_path).body
    assert body == "- [ ] Oeufs\n- [ ] Pain\n- [x] Lait"


def test_unchecking_puts_an_item_back_where_it_was(window):
    window.new_note(checklist=True)
    cl = window.editor.checklist
    cl.set_items([fmt.CheckItem("Lait"), fmt.CheckItem("Pain"), fmt.CheckItem("Oeufs")])
    cl._rows(cl.open_box)[1].box.setChecked(True)  # Pain
    assert [r.item().text for r in cl._rows(cl.open_box)] == ["Lait", "Oeufs"]
    cl._rows(cl.done_box)[0].box.setChecked(False)
    assert [r.item().text for r in cl._rows(cl.open_box)] == ["Lait", "Pain", "Oeufs"]
    assert cl.done_box.count() == 0


def test_enter_adds_an_item_and_backspace_on_empty_removes_it(window):
    window.new_note(checklist=True)
    cl = window.editor.checklist
    cl.set_items([fmt.CheckItem("A")])
    row = cl._rows(cl.open_box)[0]
    row.enter_pressed.emit(row)
    assert cl.count() == 2
    new = cl._rows(cl.open_box)[1]
    QTest.keyClick(new.edit, Qt.Key.Key_Backspace)
    assert cl.count() == 1


def test_wysiwyg_bold_and_bullets_are_stored_as_markdown(window, tmp_path):
    window.new_note()
    ed = window.editor
    ed.title.setText("Format")
    ed.body.set_markdown("Bonjour monde")
    cursor = ed.body.textCursor()
    cursor.setPosition(8)
    cursor.setPosition(13, cursor.MoveMode.KeepAnchor)
    ed.body.setTextCursor(cursor)
    ed.body.toggle_bold()
    ed.body.moveCursor(ed.body.textCursor().MoveOperation.End)
    ed.body.toggle_bullet()
    assert ed.body.markdown() == "- Bonjour **monde**"
    ed._edited()
    ed.save()
    assert read(tmp_path).body == "- Bonjour **monde**"
    # reopening shows styled text, not markdown symbols
    window.close_editor()
    window.open_note(window.notes[0])
    assert ed.body.document().toPlainText() == "Bonjour monde"


def test_heading_ends_at_enter(window):
    window.new_note()
    body = window.editor.body
    body.set_markdown("Titre")
    body.cycle_heading()
    assert body.markdown() == "# Titre"
    QTest.keyClick(body, Qt.Key.Key_Return)
    QTest.keyClicks(body, "suite")
    assert body.markdown() == "# Titre\nsuite"


def test_color_pin_and_labels_land_in_the_file(window, tmp_path):
    window.new_note()
    ed = window.editor
    ed.title.setText("Idées")
    ed.pin_button.setChecked(True)
    ed._color = "yellow"
    ed.current = ed.current.copy(labels=["perso", "dev"])
    ed._edited()
    ed.save()
    note = read(tmp_path)
    assert (note.pinned, note.color, note.labels) == (True, "yellow", ["perso", "dev"])


def test_delete_removes_the_file_and_undo_restores_it(window, tmp_path):
    note = add(window.store, "Bye", "texte")
    window.refresh()
    window.open_note(note)
    window.editor._request_delete()
    assert files(tmp_path) == [] and window.stack.currentWidget() is window.list_screen
    assert window.snack_button.isVisibleTo(window.snack)
    window.snack_button.click()
    assert files(tmp_path) == [note.path]
    assert len(window.list_screen.visible_notes()) == 1


def test_external_change_reloads_when_there_is_no_local_edit(window, tmp_path):
    note = add(window.store, "Partagée", "v1")
    window.refresh()
    window.open_note(note)
    path = tmp_path / note.path
    path.write_text(path.read_text().replace("v1", "v2 depuis le PC"))
    window._on_disk_changed()
    assert window.editor.body.document().toPlainText() == "v2 depuis le PC"


@pytest.mark.parametrize("choice", ["Recharger", "Garder ma version"])
def test_autosave_does_not_overwrite_a_version_the_sync_just_brought(window, tmp_path, monkeypatch, choice):
    """The autosave (700 ms) runs before the watcher (800 ms): it used to write over the synced file silently."""
    from PySide6.QtWidgets import QMessageBox
    note = add(window.store, "Partagée", "v1")
    window.refresh()
    window.open_note(note)
    path = tmp_path / note.path
    window.editor.title.setText("Partagée (PC)")  # a local edit, not saved yet
    path.write_text(path.read_text(encoding="utf-8").replace("v1", "v2 du téléphone"), encoding="utf-8")
    asked = []

    def answer(box):
        asked.append(box.text())
        next(b for b in box.buttons() if b.text() == choice).click()
        return 0
    monkeypatch.setattr(QMessageBox, "exec", answer)
    window.editor.save()  # the autosave timer, before any watcher event
    assert len(asked) == 1
    text = path.read_text(encoding="utf-8")
    if choice == "Recharger":
        assert "v2 du téléphone" in text and "Partagée (PC)" not in text
        assert window.editor.title.text() == "Partagée"
    else:
        assert "Partagée (PC)" in text and "v2 du téléphone" not in text  # the user chose this PC's version
    window._on_disk_changed()  # the watcher's tick afterwards asks nothing more
    assert len(asked) == 1


def test_external_note_appears_in_the_list(window, tmp_path):
    (tmp_path / "du-pc.md").write_text("Bonjour")
    window._on_disk_changed()
    assert len(window.list_screen.visible_notes()) == 1


def test_search_label_filter_and_pinned_sections(window):
    add(window.store, "Alpha", "x", labels=["a"], pinned=True)
    add(window.store, "Beta", "y", labels=["b"])
    window.refresh()
    ls = window.list_screen
    assert [n.title for n in ls.visible_notes()] == ["Alpha", "Beta"]  # pinned first
    ls.search.setText("bet")
    assert [n.title for n in ls.visible_notes()] == ["Beta"]
    ls.search.setText("")
    ls._pick_label("a")
    assert [n.title for n in ls.visible_notes()] == ["Alpha"]
    assert any(kind == "header" for kind, _ in ls._items)


def test_conflict_copy_can_replace_the_original(window, tmp_path):
    original = add(window.store, "Courses", "ancien")
    (tmp_path / f"courses-{original.id}.conflict-1.md").write_text(fmt.serialize(original.copy(body="nouveau")))
    window.refresh()
    copy = next(n for n in window.notes if n.conflict)
    window.open_note(copy)
    window.editor.keep_conflict()
    assert files(tmp_path) == [original.path]
    assert read(tmp_path).body == "nouveau"


def test_theme_switch_and_settings_page(window):
    window.show_settings()
    window.settings.theme_buttons["dark"].click()
    assert theme.is_dark()
    window.settings.theme_buttons["light"].click()
    assert not theme.is_dark()
    window.settings.list_button.click()
    assert window.list_screen.grid is False


def test_update_toast_shows_when_a_newer_build_exists(window, monkeypatch):
    monkeypatch.setattr(updater._version, "BUILD", 1)
    window.toast = UpdateToast(window, updater.Release(2, "http://x/g.exe"))
    window.toast.place()
    window.toast.show()
    assert window.toast.isVisible()
    assert window.toast.x() > window.width() // 2


def test_change_on_the_share_is_picked_up_without_a_watcher_event(window, tmp_path):
    """Over SMB Windows may not report the phone's changes: the periodic check finds them."""
    window.watcher.blockSignals(True)
    window.watch_timer.stop()
    window._poll_disk()
    assert not window.watch_timer.isActive()  # nothing changed: no reload
    (tmp_path / "from-phone.md").write_text("---\nid: p1\n---\nNote du téléphone\n", encoding="utf-8")
    window._poll_disk()
    assert window.watch_timer.isActive()  # the change is seen, the usual debounced reload follows
    window.watch_timer.stop()
    window._on_disk_changed()
    assert "from-phone.md" in [n.path for n in window.notes]
    window._poll_disk()
    assert not window.watch_timer.isActive()
    window.watcher.blockSignals(False)


def test_activities_screen_lists_and_opens_an_activity(window, tmp_path, monkeypatch):
    from pathlib import Path
    note = window.store.import_activity(Path(__file__).parent / "data" / "synthetic.fit", "Sortie vélo", None)
    window.refresh()
    assert "Sortie vélo" in window.list_screen.activities_button.text()
    window.show_activities()
    assert window.stack.currentWidget() is window.activities
    assert len(window.activities.activities) == 1
    window.activities.open_activity(window.activities.activities[0])
    assert window.activities.current.path == note.path
    panel = window.activities.map_panel
    assert panel is not None and panel.isVisible()
    assert len(panel.view.state["setTrack"][0]["coords"]) > 1
    window.activities.open_activity(window.activities.activities[0])
    QApplication.processEvents()  # a widget added to a shown layout is shown on the next event loop pass
    assert window.activities.map_panel is panel and panel.isVisible()  # one map, reused: the page is not reloaded
    assert window.list_screen.notes == []  # activities stay out of the regular list
    window._escape()
    assert window.activities.current is None
    window._escape()  # the activities list is a module root: Escape stays there
    assert window.stack.currentWidget() is window.activities


def test_rail_switches_modules_and_hides_them(window):
    window.show_module("agenda")
    assert window.stack.currentWidget() is window.agenda
    window.set_module_visible("agenda", False)
    assert window.rail.buttons["agenda"].isHidden()
    window.show_module("agenda")  # a hidden module falls back to the notes
    assert window.stack.currentWidget() is window.list_screen
    window.set_module_visible("agenda", True)


def test_an_unchanged_refresh_does_not_rebuild_the_lists(window, tmp_path):
    """Every editor save and sync tick refreshes: rebuilding hundreds of cards each time froze the window."""
    add(window.store, "Une")
    window.refresh()
    cards = [w for kind, w in window.list_screen._items if kind == "card"]
    assert len(cards) == 1
    window.refresh()
    assert [w for kind, w in window.list_screen._items if kind == "card"] == cards


def test_notes_saved_while_the_list_is_hidden_show_when_it_comes_back(window, tmp_path):
    window.show_module("contacts")
    add(window.store, "Pendant")
    window.refresh()
    window.show_module("notes")
    QApplication.processEvents()
    assert [w.note.title for kind, w in window.list_screen._items if kind == "card"] == ["Pendant"]


def test_escape_key_leaves_the_editor_and_clears_searches(window):
    """Real key presses: two window-wide Escape shortcuts visible at once cancel each other (ambiguous)."""
    window.activateWindow()
    assert QTest.qWaitForWindowActive(window)
    note = add(window.store, "Clavier")
    window.refresh()
    window.open_note(note)
    window.editor.title.setFocus()
    QTest.keyClick(window.editor.title, Qt.Key.Key_Escape)
    assert window.stack.currentWidget() is window.list_screen
    window.list_screen.search.setFocus()
    QTest.keyClicks(window.list_screen.search, "cla")
    QTest.keyClick(window.list_screen.search, Qt.Key.Key_Escape)
    assert window.list_screen.search.text() == ""
    window.show_module("contacts")
    window.contacts.search.setFocus()
    QTest.keyClicks(window.contacts.search, "x")
    QTest.keyClick(window.contacts.search, Qt.Key.Key_Escape)
    assert window.contacts.search.text() == ""
