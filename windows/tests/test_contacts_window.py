"""Smoke tests of the Contacts screen in the main window (synthetic contacts only)."""
from pathlib import Path

import pytest
from PySide6.QtWidgets import QApplication

from scriptorium_win.core import contacts as ct
from scriptorium_win.core import vcard
from scriptorium_win.core.store import Store
from scriptorium_win.services import updater
from scriptorium_win.ui import theme
from scriptorium_win.ui.contacts_view import DuplicatesDialog, EditDialog
from scriptorium_win.ui.main_window import MainWindow

VECTORS = Path(__file__).resolve().parents[2] / "testdata" / "contacts"


class FakeConfig:
    workdir = None
    geometry = None
    grid = True
    theme = "light"
    last_module = "contacts"
    hidden_modules: set[str] = set()
    contacts_first_name_first = False


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


def contact_files(root: Path) -> dict[str, str]:
    return {p.name: p.read_text(encoding="utf-8") for p in (root / "contacts").glob("*.md")} if (root / "contacts").is_dir() else {}


def add(window, **kw) -> ct.Contact:
    c = window.store.contacts.save(ct.Contact(**kw))
    window.refresh()
    return c


def test_list_sections_search_and_count(window):
    screen = window.contacts
    assert window.stack.currentWidget() is screen
    add(window, first_name="Marie", last_name="Dupont", phones=[ct.Phone("+33 6 12 34 56 78", "mobile")], favorite=True)
    add(window, first_name="Émile", last_name="Zola")
    assert screen.headers() == ["Favoris", "D", "Z"]
    assert screen.count.text() == "2 contacts"
    screen.search.setText("0612")
    assert [c.name for c in screen.visible_contacts()] == ["Marie Dupont"]
    screen.search.setText("zola")
    assert [c.name for c in screen.visible_contacts()] == ["Émile Zola"]
    window._escape()
    assert screen.search.text() == ""
    window._contacts_order_changed(True)
    assert screen.headers() == ["Favoris", "E", "M"]
    assert window.store.scan() == []  # not in the notes list


def test_detail_copy_delete_and_undo(window, tmp_path):
    marie = add(window, first_name="Marie", last_name="Dupont", phones=[ct.Phone("06 12 34 56 78", "mobile")],
                emails=["marie@example.org"], birthday=ct.Birthday(1990, 5, 14))
    screen = window.contacts
    screen.show_contact(screen.store.contacts.by_id(marie.id))
    assert screen.stack.currentIndex() == 1
    screen.copy("06 12 34 56 78", "Numéro copié")
    assert QApplication.clipboard().text() == "06 12 34 56 78"
    assert window.snack_text.text() == "Numéro copié"
    before = contact_files(tmp_path)
    screen.delete_current()
    assert contact_files(tmp_path) == {} and screen.stack.currentIndex() == 0
    assert window.snack_button.isVisibleTo(window.snack)
    window.snack_button.click()
    assert contact_files(tmp_path) == before
    assert len(screen.visible_contacts()) == 1


def test_edit_dialog_builds_the_contact(window):
    screen = window.contacts
    dialog = EditDialog(screen, None)
    dialog.first.setText("Luc")
    dialog.phone_rows[0][1].setText("06 00 00 00 01")
    dialog.add_email("luc@example.org")
    dialog.day.setValue(29)
    dialog.month.setCurrentIndex(2)
    c = dialog.contact()
    assert (c.first_name, c.phones, c.emails, c.birthday) == (
        "Luc", [ct.Phone("06 00 00 00 01", "mobile")], ["luc@example.org"], ct.Birthday(None, 2, 29))
    dialog.year.setText("2027")
    with pytest.raises(ValueError):
        dialog.birthday()
    dialog.year.setText("2028")
    saved = screen.save(dialog.contact())
    assert saved.path.startswith("contacts/luc-") and screen.current.id == saved.id
    screen.toggle_favorite()
    assert screen.store.contacts.by_id(saved.id).favorite


def test_import_report_undo_and_export(window, tmp_path):
    add(window, first_name="Marie", last_name="Dupont", phones=[ct.Phone("06 12 34 56 78")])
    before = contact_files(tmp_path)
    screen = window.contacts
    result = screen.read_vcard(VECTORS / "google.vcf")
    outcome = screen.run_import(result, ct.DuplicatePolicy.SKIP)
    assert (len(outcome.created), outcome.skipped) == (3, 1)
    assert len(screen.visible_contacts()) == 4
    screen.undo_import(outcome)
    assert contact_files(tmp_path) == before
    out = tmp_path / "export.vcf"
    screen.export_all(out)
    assert [c.fields() for c in vcard.parse(out.read_bytes()).contacts] == [c.fields() for c in screen.contacts()]
    assert "1 contact exporté" == window.snack_text.text()


def test_duplicates_merge_and_undo(window, tmp_path):
    add(window, first_name="Marie", last_name="Dupont", phones=[ct.Phone("06 12 34 56 78")])
    add(window, first_name="Marie", last_name="Dupont", emails=["marie@example.org"])
    before = contact_files(tmp_path)
    dialog = DuplicatesDialog(window, window.contacts)
    assert len(dialog.groups) == 1
    group, choice = dialog.groups[0]
    dialog.merge(group, group[choice.checkedId()])
    assert len(dialog.groups) == 0
    merged = window.store.contacts.contacts
    assert len(merged) == 1 and merged[0].emails == ["marie@example.org"] and len(merged[0].phones) == 1
    window.snack_button.click()
    assert contact_files(tmp_path) == before


def test_contact_written_by_the_phone_appears(window, tmp_path):
    (tmp_path / "contacts").mkdir()
    (tmp_path / "contacts" / "martin-luc-abc123.md").write_text(
        "---\nid: abc123\ntitle: Luc Martin\nfirst_name: Luc\nlast_name: Martin\n---\n", encoding="utf-8")
    window._on_disk_changed()
    assert [c.name for c in window.contacts.visible_contacts()] == ["Luc Martin"]
    assert str(tmp_path / "contacts") in window.watcher.directories()


@pytest.mark.parametrize("replace", [False, True])
def test_an_edit_over_a_file_the_sync_replaced_asks_first(window, tmp_path, monkeypatch, replace):
    from PySide6.QtWidgets import QDialog, QMessageBox
    marie = add(window, first_name="Marie", last_name="Dupont")
    screen = window.contacts
    screen.show_contact(screen.store.contacts.by_id(marie.id))
    path = tmp_path / marie.path
    asked = []
    monkeypatch.setattr(QMessageBox, "question", lambda *a, **k: asked.append(a) or (
        QMessageBox.StandardButton.Yes if replace else QMessageBox.StandardButton.No))

    class Dialog:  # the user edits while the phone's version lands
        def __init__(self, parent, c):
            self.c = c

        def exec(self):
            path.write_text(path.read_text(encoding="utf-8").replace("Dupont", "Durand") + "notes: du téléphone\n",
                            encoding="utf-8")
            return QDialog.DialogCode.Accepted

        def contact(self):
            return self.c.copy(first_name="Maria")

    screen.edit_contact(Dialog)
    assert len(asked) == 1
    text = path.read_text(encoding="utf-8")
    assert ("Maria" in text) is replace and ("Durand" in text) is not replace
