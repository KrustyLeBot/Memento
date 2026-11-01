"""Open a file with its default app, or show it in the Explorer."""
from __future__ import annotations

import subprocess
import sys
from pathlib import Path

from PySide6.QtCore import QUrl
from PySide6.QtGui import QDesktopServices


def open_file(path: Path) -> bool:
    """Opens the file with the system's default app (a PDF reader or the browser for PDFs)."""
    return path.exists() and QDesktopServices.openUrl(QUrl.fromLocalFile(str(path)))


def reveal(path: Path) -> bool:
    """Opens the folder in the Explorer with the file selected."""
    if not path.exists():
        return False
    if sys.platform == "win32":
        subprocess.Popen(f'explorer /select,"{path}"')
        return True
    return QDesktopServices.openUrl(QUrl.fromLocalFile(str(path.parent)))
