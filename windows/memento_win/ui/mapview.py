"""The activity map: the shared web page (web/map, MapLibre) in a QWebEngineView, a native 3D/2D switch, and the
PC's live location while the map is on screen. One view is kept and reused, so opening an activity only sends data."""
from __future__ import annotations

import json
import sys
import threading

from PySide6.QtCore import QCoreApplication, QEvent, QObject, Qt, QTimer, QUrl, Signal, Slot
from PySide6.QtPositioning import QGeoPositionInfo, QGeoPositionInfoSource
from PySide6.QtWebChannel import QWebChannel
from PySide6.QtWebEngineCore import QWebEnginePage
from PySide6.QtWebEngineWidgets import QWebEngineView
from PySide6.QtWidgets import QButtonGroup, QFrame, QHBoxLayout, QPushButton, QVBoxLayout, QWidget

from ..services import maphost
from . import theme

# Setters whose last value is replayed after the page reloads (renderer crash), in this order.
STATE_ORDER = ("setTheme", "setMode", "setExag", "setSquare", "setRoute", "setLines", "setTrack", "setDots",
               "setLive", "setLiveSquare", "setFollow", "setPos", "setCursor")


class _Bridge(QObject):
    """The page's `SMapHost` (QWebChannel): it posts JSON events here."""
    received = Signal(dict)

    @Slot(str)
    def post(self, text: str) -> None:
        try:
            event = json.loads(text)
        except ValueError as error:
            print(f"map: unreadable event {text!r}: {error}", file=sys.stderr)
            return
        self.received.emit(event)


class _Page(QWebEnginePage):
    def javaScriptConsoleMessage(self, level, message: str, line: int, source: str) -> None:
        if level == QWebEnginePage.JavaScriptConsoleMessageLevel.ErrorMessageLevel:
            print(f"map: {message} ({source}:{line})", file=sys.stderr)


class MapView(QWebEngineView):
    """Calls `window.SMap.<name>(...)` on the page. Until the page is loaded nothing is lost: the last value of
    each setter is kept and sent (again) whenever the page (re)loads."""
    event = Signal(dict)

    def __init__(self, mode: str = "3d", parent: QWidget | None = None):
        super().__init__(parent)
        self.loaded = False
        self.state: dict[str, tuple] = {"setMode": (mode,), "setTheme": (_theme(),)}
        page = _Page(maphost.profile(), self)
        self.setPage(page)
        self.bridge = _Bridge(self)
        self.bridge.received.connect(self.event)
        self.channel = QWebChannel(page)
        self.channel.registerObject("SMapHost", self.bridge)
        page.setWebChannel(self.channel)
        page.loadFinished.connect(self._load_finished)
        page.renderProcessTerminated.connect(self._crashed)
        self.setMinimumHeight(260)
        self.load(QUrl(page_url(mode, _theme())))

    def reload_page(self) -> None:
        """Loads the page again with the current map settings; the kept state is replayed once it is loaded."""
        self.loaded = False
        self.load(QUrl(page_url(self.state["setMode"][0], self.state["setTheme"][0])))

    def call(self, name: str, *args) -> None:
        if name in STATE_ORDER:
            self.state[name] = args
        if self.loaded:
            self._run(name, args)

    def _run(self, name: str, args: tuple) -> None:
        text = ", ".join(getattr(a, "page_json", None) or json.dumps(a) for a in args)
        self.page().runJavaScript(f"window.SMap && window.SMap.{name}({text})")

    def _load_finished(self, ok: bool) -> None:
        self.loaded = ok
        if not ok:
            print("map: the page did not load", file=sys.stderr)
            return
        for name in STATE_ORDER:
            if name in self.state:
                self._run(name, self.state[name])

    def _crashed(self, status, code: int) -> None:
        if status == QWebEnginePage.RenderProcessTerminationStatus.NormalTerminationStatus:
            return
        print(f"map: renderer ended ({status}, {code}), reloading", file=sys.stderr)
        self.loaded = False
        QTimer.singleShot(500, self, self.reload)


def page_url(mode: str, theme_name: str) -> str:
    # photomax: the photo source's maxzoom (a setting); the page enlarges that level beyond it
    return (f"{maphost.BASE_URL}map/index.html?mode={mode}&theme={theme_name}&interactive=1"
            f"&photomax={maphost.photo_max_zoom()}")


def _theme() -> str:
    return "dark" if theme.is_dark() else "light"


class _EncodedDict(dict):
    page_json = ""


class _EncodedList(list):
    page_json = ""


def pre_encoded(value: dict | list) -> dict | list:
    """The same value, carrying its JSON for the page: encoded on the worker that prepares it, since a long race's
    track or ski lines take 15 to 110 ms to encode on the window's thread."""
    if threading.current_thread() is threading.main_thread():
        return value  # one json.dumps in MapView._run is faster, and this thread waits for it either way
    out = _EncodedDict(value) if isinstance(value, dict) else _EncodedList(value)
    out.page_json = _encode(value)
    return out


def _encode(value) -> str:
    """json.dumps(value), byte for byte, one element at a time: one call holds the GIL for all of it, the window's thread
    included (100 ms for a day of ski lines)."""
    if isinstance(value, list):
        return "[" + ", ".join(json.dumps(x) for x in value) + "]"
    if isinstance(value, dict):
        return "{" + ", ".join(f"{json.dumps(k)}: {_encode(v)}" for k, v in value.items()) + "}"
    return json.dumps(value)


class LiveLocation(QObject):
    """Windows location service → setPos; an error, a refusal or 15 s without a fix → locFail."""
    enabled = True  # tests switch it off: no location service on the CI machines

    def __init__(self, view: MapView):
        super().__init__(view)
        self.view = view
        self.source: QGeoPositionInfoSource | None = None
        self.timer = QTimer(self, singleShot=True, interval=15000)
        self.timer.timeout.connect(self._failed)
        self.running = False

    def start(self) -> None:
        if self.running or not self.enabled:
            return
        self.running = True
        try:
            from PySide6.QtCore import QLocationPermission
            permission = QLocationPermission()
            permission.setAccuracy(QLocationPermission.Accuracy.Precise)
            app = QCoreApplication.instance()
            status = app.checkPermission(permission)
            if status == Qt.PermissionStatus.Undetermined:
                app.requestPermission(permission, self, lambda p: self._begin() if p.status() == Qt.PermissionStatus.Granted else self._failed())
                return
            if status == Qt.PermissionStatus.Denied:
                self._failed()
                return
        except (ImportError, AttributeError, TypeError) as error:  # no usable permission API: the source itself will say
            print(f"map: location permission not checked: {error}", file=sys.stderr)
        self._begin()

    def _begin(self) -> None:
        if not self.running:
            return
        if self.source is None:
            self.source = QGeoPositionInfoSource.createDefaultSource(self)
            if self.source is None:
                self._failed()
                return
            self.source.positionUpdated.connect(self._position)
            self.source.errorOccurred.connect(lambda error: self._failed())
        self.timer.start()
        self.source.startUpdates()

    def stop(self) -> None:
        self.running = False
        self.timer.stop()
        if self.source is not None:
            self.source.stopUpdates()

    def _position(self, info: QGeoPositionInfo) -> None:
        self.timer.stop()
        c = info.coordinate()
        heading = info.attribute(QGeoPositionInfo.Attribute.Direction) if info.hasAttribute(QGeoPositionInfo.Attribute.Direction) else None
        accuracy = info.attribute(QGeoPositionInfo.Attribute.HorizontalAccuracy) if info.hasAttribute(QGeoPositionInfo.Attribute.HorizontalAccuracy) else None
        self.view.call("setPos", c.longitude(), c.latitude(), heading, accuracy)

    def _failed(self) -> None:
        self.timer.stop()
        if self.running:
            self.view.call("locFail")


class MapPanel(QWidget):
    """The map with its native 3D/2D switch, a "Vue par défaut" button and an "Agrandir" button that lays it over the whole
    Mémento window (not full screen) until "Réduire" or Escape. `mode_changed` lets the owner remember the mode."""
    mode_changed = Signal(str)

    def __init__(self, mode: str = "3d", parent: QWidget | None = None):
        super().__init__(parent)
        self.mode = mode if mode in ("3d", "2d") else "3d"
        layout = QVBoxLayout(self)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(6)
        bar = QHBoxLayout()
        # explicit surfaces and text colours (theme.py: mapSwitch, mapSegment, mapButton), as on Android: the selected
        # half in primary, the other in on_surface, legible in both themes whatever the map shows
        switch = QFrame()
        switch.setObjectName("mapSwitch")
        segments = QHBoxLayout(switch)
        segments.setContentsMargins(2, 2, 2, 2)
        segments.setSpacing(0)
        self.group = QButtonGroup(self)
        for key, label, tip in (("3d", "3D", "Relief en bloc"), ("2d", "2D", "Carte à plat")):
            button = QPushButton(label)
            button.setObjectName("mapSegment")
            button.setCheckable(True)
            button.setChecked(key == self.mode)
            button.setToolTip(tip)
            button.setFixedWidth(48)
            button.clicked.connect(lambda _=False, k=key: self.set_mode(k))
            self.group.addButton(button)
            segments.addWidget(button)
        bar.addWidget(switch)
        bar.addStretch()
        reset = QPushButton("Vue par défaut")
        reset.setObjectName("mapButton")
        reset.setToolTip("Revenir à la vue de départ, d'où que soit la caméra")
        reset.clicked.connect(lambda: self.view.call("reset"))
        bar.addWidget(reset)
        self.expand_button = QPushButton("Agrandir")
        self.expand_button.setObjectName("mapButton")
        self.expand_button.setToolTip("La carte occupe toute la fenêtre (Échap pour revenir)")
        self.expand_button.clicked.connect(self.toggle_expanded)
        bar.addWidget(self.expand_button)
        layout.addLayout(bar)
        # where the panel sat before it was expanded: (parent, its layout, index in it)
        self._home: tuple[QWidget, object, int] | None = None
        self.view = MapView(self.mode, self)
        self.view.setMinimumHeight(420)
        layout.addWidget(self.view)
        self.location = LiveLocation(self.view)

    @property
    def expanded(self) -> bool:
        return self._home is not None

    def toggle_expanded(self) -> None:
        if self.expanded:
            self.collapse()
        else:
            self.expand()

    def expand(self) -> None:
        host, parent = self.window(), self.parentWidget()
        if self.expanded or parent is None or host is self:
            return
        layout = parent.layout()
        self._home = (parent, layout, layout.indexOf(self) if layout is not None else -1)
        # reparenting keeps the loaded page: the view is moved, never rebuilt
        self.setParent(host)
        self.setAutoFillBackground(True)
        self.layout().setContentsMargins(12, 10, 12, 12)
        self.setGeometry(host.rect())
        host.installEventFilter(self)
        self.expand_button.setText("Réduire")
        self.show()
        self.raise_()

    def collapse(self) -> None:
        if not self.expanded:
            return
        parent, layout, index = self._home
        self._home = None
        self.window().removeEventFilter(self)
        self.setAutoFillBackground(False)
        self.layout().setContentsMargins(0, 0, 0, 0)
        self.expand_button.setText("Agrandir")
        if layout is not None and index >= 0:
            layout.insertWidget(index, self)
        else:
            self.setParent(parent)
        self.show()

    def eventFilter(self, watched, event) -> bool:
        if self.expanded and watched is self.parentWidget() and event.type() == QEvent.Type.Resize:
            self.setGeometry(watched.rect())
        return super().eventFilter(watched, event)

    def set_mode(self, mode: str) -> None:
        if mode == self.mode:
            return
        self.mode = mode
        for button in self.group.buttons():
            button.setChecked(button.text().lower() == mode)
        self.view.call("setMode", mode)
        self.mode_changed.emit(mode)

    def restyle(self) -> None:
        self.view.call("setTheme", _theme())

    def show_activity(self, *, track: dict | None = None, lines: list | None = None, dots: list | None = None,
                      square: list | None = None) -> None:
        """Replaces what the map shows. The square goes first so the 3D block is built once, for the new data."""
        self.view.call("setSquare", square)
        self.view.call("setLines", lines or [])
        self.view.call("setTrack", track)
        self.view.call("setDots", dots or [])
        self.view.call("setCursor", None)

    def set_lines(self, lines: list) -> None:
        self.view.call("setLines", lines)

    def set_cursor(self, lon: float | None, lat: float | None = None) -> None:
        self.view.call("setCursor", lon, lat)

    def showEvent(self, event) -> None:
        super().showEvent(event)
        self.location.start()  # only while a map is on screen

    def hideEvent(self, event) -> None:
        super().hideEvent(event)
        self.location.stop()
