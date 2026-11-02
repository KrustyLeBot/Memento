from pathlib import Path

from scriptorium_win import __main__ as entry


def test_the_notes_folder_is_asked_again_when_missing_empty_or_unreadable(tmp_path, monkeypatch):
    assert not entry._usable(tmp_path / "moved")
    assert not entry._usable(tmp_path)
    (tmp_path / "a.md").write_text("x", encoding="utf-8")
    assert entry._usable(tmp_path)

    def refused(self):
        raise PermissionError(13, "Accès refusé", str(self))
    monkeypatch.setattr(Path, "iterdir", refused)  # a share gone offline: asked again, not a crash at start
    assert not entry._usable(tmp_path)
