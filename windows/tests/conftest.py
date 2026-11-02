import os
import sys
from pathlib import Path

import pytest

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
os.environ.setdefault("QTWEBENGINE_DISABLE_SANDBOX", "1")  # CI containers run as root, where Chromium's sandbox cannot start
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from memento_win.services import maphost  # noqa: E402

maphost.register_scheme()  # before any test creates the QApplication, as in the app

from PySide6.QtCore import QStandardPaths  # noqa: E402

QStandardPaths.setTestModeEnabled(True)  # the map's caches go to a test folder, never the user's


@pytest.fixture(autouse=True)
def no_map_network(monkeypatch):
    """The map never reaches the network in tests: tiles and peaks answer "unavailable", no location service."""
    from memento_win.ui import mapview
    monkeypatch.setattr(maphost.TileStore, "get", lambda self, tile, done: done(None))
    monkeypatch.setattr(maphost.TileStore, "peaks", lambda self, req, done: done([]))
    monkeypatch.setattr(mapview.LiveLocation, "enabled", False)


@pytest.fixture(scope="session", autouse=True)
def release_web_pages():
    """At the end, as the app does at exit: every page (windows of the tests) before the map's profile."""
    yield
    import shiboken6
    from PySide6.QtWidgets import QApplication
    if QApplication.instance() is None:
        return
    for widget in QApplication.topLevelWidgets():
        if shiboken6.isValid(widget) and widget.parent() is None:
            shiboken6.delete(widget)
    maphost.release_profile()
