"""The Windows client's pure modules (windows/memento_win/core) are the source of truth for every file format: the
web server imports them, it never re-implements them. In the Docker image they are copied next to this package
(/app/memento_win); from a checkout, windows/ is put on the path."""
from __future__ import annotations

import sys
from pathlib import Path

try:
    import memento_win.core  # noqa: F401
except ImportError:
    sys.path.insert(0, str(Path(__file__).resolve().parents[3] / "windows"))
    import memento_win.core  # noqa: F401

from memento_win.core import atomic, drawing, mapcache, peaks  # noqa: E402,F401
from memento_win.core import note as fmt  # noqa: E402,F401
from memento_win.core.settings import PATH as SETTINGS_PATH, ActivitySettings  # noqa: E402,F401
from memento_win.core.store import ACTIVITIES_DIR, MAX_ATTACHMENT, Store, now_ms  # noqa: E402,F401
from memento_win.core.track import Sport  # noqa: E402,F401
