"""`Memento.exe --selftest [report.txt]`: checks that the frozen app carries everything it loads at run time
(every module, the time zone database, the map page and its MapLibre, Qt WebEngine's process and resources, the
location plugin), then exits 0, or 1 with the failures. The CI runs it on the built exe: a file the spec filtered
out by mistake fails the build instead of the owner's first start."""
from __future__ import annotations

import importlib
import os
import pkgutil
import sys
import traceback
from pathlib import Path

PAGE_TIMEOUT_MS = 60_000


def _modules(lines: list[str]) -> None:
    import memento_win
    for info in pkgutil.walk_packages(memento_win.__path__, "memento_win."):
        if info.name.endswith(".__main__"):
            continue
        importlib.import_module(info.name)
    import dateutil.rrule  # noqa: F401 — the agenda's recurrence rules
    lines.append("modules: ok")


def _zones(lines: list[str]) -> None:
    import zoneinfo
    from datetime import datetime
    zoneinfo.reset_tzpath(to=[])  # the `tzdata` package only, as on Windows, which has no system database
    try:
        zoneinfo.ZoneInfo.clear_cache()
        paris = zoneinfo.ZoneInfo("Europe/Paris")
        if datetime(2026, 7, 1, 12, tzinfo=paris).utcoffset().total_seconds() != 7200:
            raise RuntimeError("Europe/Paris: wrong summer offset")
        if "America/New_York" not in zoneinfo.available_timezones():
            raise RuntimeError("tzdata: zone list incomplete")
    finally:
        zoneinfo.reset_tzpath()
        zoneinfo.ZoneInfo.clear_cache()
    lines.append("time zones (tzdata): ok")


def _files(lines: list[str]) -> None:
    from .services import maphost
    root = maphost.web_root() / "map"
    missing = [f for f in ("index.html", "map.js", "map.css", "maplibre-gl.js", "maplibre-gl.css") if not (root / f).is_file()]
    if not (Path(__file__).parent / "assets" / "icon.png").is_file():
        missing.append("assets/icon.png")
    if missing:
        raise RuntimeError(f"missing files: {', '.join(missing)}")
    lines.append(f"map page files: ok ({root})")


def _qt(lines: list[str]) -> None:
    import shiboken6
    from PySide6.QtCore import QEventLoop, QStandardPaths, QTimer, QUrl
    from PySide6.QtPositioning import QGeoPositionInfoSource
    from PySide6.QtWebEngineCore import QWebEnginePage
    from PySide6.QtWidgets import QApplication

    from .services import maphost

    if QApplication.instance() is None:
        maphost.register_scheme()  # before the QApplication, as in the app
    QStandardPaths.setTestModeEnabled(True)  # the map's caches go to a throwaway folder, not the user's
    app = QApplication.instance() or QApplication(sys.argv[:1])
    sources = QGeoPositionInfoSource.availableSources()
    if not sources:
        raise RuntimeError("no location plugin (plugins/position)")
    lines.append(f"location plugins: {', '.join(sources)}")

    page = QWebEnginePage(maphost.profile())
    loop = QEventLoop()
    outcome: dict = {}

    def loaded(ok: bool) -> None:
        outcome["loaded"] = ok
        page.runJavaScript("typeof maplibregl + ' ' + typeof window.SMap", 0, lambda r: (outcome.update(js=r), loop.quit()))

    page.loadFinished.connect(loaded)
    QTimer.singleShot(PAGE_TIMEOUT_MS, loop.quit)
    page.load(QUrl(f"{maphost.BASE_URL}map/index.html?mode=2d&theme=light"))
    loop.exec()
    shiboken6.delete(page)  # before the profile, as at the app's exit
    maphost.release_profile()
    app.processEvents()
    if not outcome.get("loaded"):
        raise RuntimeError(f"the map page did not load (QtWebEngineProcess, resources?): {outcome}")
    if outcome.get("js") != "object object":
        raise RuntimeError(f"the map page loaded without MapLibre or its script: {outcome.get('js')!r}")
    lines.append("map page in Qt WebEngine: ok")


def run(report: str | None) -> int:
    os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")  # no window: the CI machine may have no desktop
    lines: list[str] = []
    failed = False
    for check in (_modules, _zones, _files, _qt):
        try:
            check(lines)
        except Exception:  # every failure is reported, then the exit code says it
            failed = True
            lines.append(f"FAILED {check.__name__}:\n{traceback.format_exc()}")
    lines.append("selftest: FAILED" if failed else "selftest: ok")
    text = "\n".join(lines) + "\n"
    if report:
        Path(report).write_text(text, encoding="utf-8")
    if sys.stdout is not None:
        sys.stdout.write(text)
    return 1 if failed else 0
