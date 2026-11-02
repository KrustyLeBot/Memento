"""A dialog edits a file the sync can replace while it is open: before saving over it, the user is asked."""
from __future__ import annotations

import os
from pathlib import Path

from PySide6.QtWidgets import QMessageBox


def stamp(path: Path) -> tuple[int, int] | None:
    """What tells that a file changed: (mtime, size); None when it does not exist."""
    try:
        st = os.stat(path)
    except FileNotFoundError:
        return None
    return st.st_mtime_ns, st.st_size


def may_overwrite(parent, path: Path, before: tuple[int, int] | None, name: str) -> bool:
    """True when the file is as it was when editing began, or the user chooses to replace the other version."""
    now = stamp(path)
    if now == before:
        return True
    what = "supprimé" if now is None else "modifié"
    answer = QMessageBox.question(
        parent, "Modifié ailleurs",
        f"« {name} » a été {what} ailleurs (synchronisation, autre appareil) pendant que tu l'éditais.\n\n"
        "Enregistrer ta version quand même ? L'autre version sera remplacée.",
        QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No, QMessageBox.StandardButton.No)
    return answer == QMessageBox.StandardButton.Yes
