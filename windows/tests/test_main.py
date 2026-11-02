import re
from pathlib import Path

import pytest

from scriptorium_win import __main__ as entry


def test_every_delayed_call_on_a_widget_names_it_as_context():
    """QTimer.singleShot(ms, self.method) has no receiver: it outlives a deleted widget, and PySide 6.12 then
    crashed (Signal::updateSourceObject). singleShot(ms, self, self.method) is cancelled with the widget."""
    root = Path(entry.__file__).parent
    bare = [f"{p.relative_to(root)}:{i}" for p in root.rglob("*.py")
            for i, line in enumerate(p.read_text(encoding="utf-8").splitlines(), 1)
            if re.search(r"QTimer\.singleShot\(\s*[\w.]+\s*,\s*self\.[\w.]+\s*\)", line)]
    assert bare == []


def test_the_notes_folder_is_asked_again_when_missing_empty_or_unreadable(tmp_path, monkeypatch):
    assert entry.folder_state(tmp_path / "moved") == "missing"
    assert entry.folder_state(tmp_path) == "empty"
    (tmp_path / "a.md").write_text("x", encoding="utf-8")
    assert entry.folder_state(tmp_path) == "ok"

    def refused(self):
        raise PermissionError(13, "Accès refusé", str(self))
    monkeypatch.setattr(Path, "iterdir", refused)  # a share gone offline: asked again, not a crash at start
    assert entry.folder_state(tmp_path) == "missing"


@pytest.fixture
def config(tmp_path):
    from PySide6.QtCore import QSettings
    from scriptorium_win.config import Config
    c = Config()
    c._settings = QSettings(str(tmp_path / "settings.ini"), QSettings.Format.IniFormat)  # never the user's
    return c


def test_an_empty_folder_chosen_on_purpose_is_not_asked_again(tmp_path, config):
    assert entry.must_ask(config)  # first run
    fresh = tmp_path / "Notes"
    fresh.mkdir()
    config.workdir = fresh  # chosen while empty
    assert not entry.must_ask(config)
    other = tmp_path / "Autre"
    other.mkdir()
    (other / "a.md").write_text("x", encoding="utf-8")
    config.workdir = other
    assert not entry.must_ask(config)
    (other / "a.md").unlink()  # the configured folder turns up empty: it probably moved
    assert entry.must_ask(config)
    config.workdir = fresh
    fresh.rmdir()  # gone
    assert entry.must_ask(config)
    fresh.mkdir()
    assert not entry.must_ask(config)  # the same empty folder as chosen
    (fresh / "a.md").write_text("x", encoding="utf-8")
    assert not entry.must_ask(config)
    (fresh / "a.md").unlink()  # it had notes, now it is empty: asked again
    assert entry.must_ask(config)
