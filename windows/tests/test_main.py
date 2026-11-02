import re
from pathlib import Path

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
    assert not entry._usable(tmp_path / "moved")
    assert not entry._usable(tmp_path)
    (tmp_path / "a.md").write_text("x", encoding="utf-8")
    assert entry._usable(tmp_path)

    def refused(self):
        raise PermissionError(13, "Accès refusé", str(self))
    monkeypatch.setattr(Path, "iterdir", refused)  # a share gone offline: asked again, not a crash at start
    assert not entry._usable(tmp_path)
