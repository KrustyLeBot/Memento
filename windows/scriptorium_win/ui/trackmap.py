"""Slippy map for the track of an activity: OpenStreetMap raster tiles drawn by hand, no web engine.

Tiles are cached on disk (oldest files purged past 100 MB) and fetched only when looked at, with an
identifying User-Agent, as the tile usage policy asks. Offline, the track stays on a plain background.
"""
from __future__ import annotations

import math
from collections import OrderedDict
from pathlib import Path

from PySide6.QtCore import QByteArray, QObject, QPointF, QStandardPaths, QUrl, Qt, Signal
from PySide6.QtGui import QColor, QPainter, QPen, QPixmap, QPolygonF
from PySide6.QtNetwork import QNetworkAccessManager, QNetworkReply, QNetworkRequest
from PySide6.QtWidgets import QWidget

from ..core import activity as geo
from . import theme

MAX_DISK = 100 * 1024 * 1024
USER_AGENT = b"Scriptorium (https://github.com/KrustyLeBot/Griffonote)"


class TileCache(QObject):
    changed = Signal()

    def __init__(self, directory: Path | None = None):
        super().__init__()
        base = directory or Path(QStandardPaths.writableLocation(QStandardPaths.StandardLocation.CacheLocation)) / "tiles"
        self.directory = base
        self._memory: OrderedDict[str, QPixmap] = OrderedDict()
        self._pending: set[str] = set()
        self._nam = QNetworkAccessManager(self)
        self._trim()

    def get(self, z: int, x: int, y: int) -> QPixmap | None:
        key = f"{z}/{x}/{y}"
        pixmap = self._memory.get(key)
        if pixmap is not None:
            self._memory.move_to_end(key)
            return pixmap
        file = self.directory / f"{key}.png"
        if file.is_file():
            loaded = QPixmap(str(file))
            if not loaded.isNull():
                self._remember(key, loaded)
                try:
                    file.touch()
                except OSError:
                    pass
                return loaded
        if key not in self._pending:
            self._pending.add(key)
            request = QNetworkRequest(QUrl(f"https://tile.openstreetmap.org/{key}.png"))
            request.setRawHeader(QByteArray(b"User-Agent"), QByteArray(USER_AGENT))
            reply = self._nam.get(request)
            reply.finished.connect(lambda r=reply, k=key, f=file: self._done(r, k, f))
        return None

    def _remember(self, key: str, pixmap: QPixmap) -> None:
        self._memory[key] = pixmap
        while len(self._memory) > 120:
            self._memory.popitem(last=False)

    def _done(self, reply: QNetworkReply, key: str, file: Path) -> None:
        self._pending.discard(key)
        try:
            if reply.error() == QNetworkReply.NetworkError.NoError:
                data = bytes(reply.readAll())
                pixmap = QPixmap()
                if pixmap.loadFromData(data):
                    file.parent.mkdir(parents=True, exist_ok=True)
                    file.write_bytes(data)
                    self._remember(key, pixmap)
                    self.changed.emit()
        except OSError:
            pass
        finally:
            reply.deleteLater()

    def _trim(self) -> None:
        try:
            files = [f for f in self.directory.rglob("*.png") if f.is_file()]
            total = sum(f.stat().st_size for f in files)
            if total <= MAX_DISK:
                return
            for f in sorted(files, key=lambda p: p.stat().st_mtime):
                if total <= MAX_DISK * 0.8:
                    break
                total -= f.stat().st_size
                f.unlink()
        except OSError:
            pass


_cache: TileCache | None = None


def tiles() -> TileCache:
    global _cache
    if _cache is None:
        _cache = TileCache()
    return _cache


class TrackMap(QWidget):
    """Lines are (points, color) with points a list of (lat, lon); dots are (lat, lon, color)."""

    def __init__(self):
        super().__init__()
        self.lines: list[tuple[list[tuple[float, float]], QColor]] = []
        self.dots: list[tuple[float, float, QColor]] = []
        self.center = (45.0, 6.0)
        self.zoom = 12.0
        self._drag = None
        self.setMinimumHeight(300)
        self.setCursor(Qt.CursorShape.OpenHandCursor)
        tiles().changed.connect(self.update)

    def set_data(self, lines, dots) -> None:
        self.lines, self.dots = lines, dots
        self.fit()
        self.update()

    def fit(self) -> None:
        pts = [p for line, _ in self.lines for p in line] + [(la, lo) for la, lo, _ in self.dots]
        if not pts or self.width() <= 0:
            return
        lats, lons = [p[0] for p in pts], [p[1] for p in pts]
        self.center = ((min(lats) + max(lats)) / 2, (min(lons) + max(lons)) / 2)
        self.zoom = geo.fit_zoom(min(lats), min(lons), max(lats), max(lons), self.width(), self.height())

    def resizeEvent(self, event) -> None:
        super().resizeEvent(event)
        if not self._drag:
            self.fit()

    def _project(self, lat: float, lon: float, tz: float) -> QPointF:
        tile_px = geo.TILE * 2 ** (self.zoom - tz)
        x = self.width() / 2 + (geo.world_x(lon, tz) - geo.world_x(self.center[1], tz)) * tile_px
        y = self.height() / 2 + (geo.world_y(lat, tz) - geo.world_y(self.center[0], tz)) * tile_px
        return QPointF(x, y)

    def paintEvent(self, event) -> None:
        s = theme.scheme()
        painter = QPainter(self)
        painter.setRenderHint(QPainter.RenderHint.Antialiasing)
        painter.fillRect(self.rect(), QColor(s.surface_high))
        tz = max(geo.MIN_ZOOM, min(geo.MAX_ZOOM, math.floor(self.zoom)))
        tile_px = geo.TILE * 2 ** (self.zoom - tz)
        cx, cy = geo.world_x(self.center[1], tz), geo.world_y(self.center[0], tz)
        w, h = self.width(), self.height()
        count = 2 ** tz
        for ty in range(math.floor(cy - h / 2 / tile_px), math.floor(cy + h / 2 / tile_px) + 1):
            if ty < 0 or ty >= count:
                continue
            for tx in range(math.floor(cx - w / 2 / tile_px), math.floor(cx + w / 2 / tile_px) + 1):
                pixmap = tiles().get(tz, tx % count, ty)
                if pixmap is None:
                    continue
                left = math.floor(w / 2 + (tx - cx) * tile_px)
                top = math.floor(h / 2 + (ty - cy) * tile_px)
                right = math.floor(w / 2 + (tx + 1 - cx) * tile_px)
                bottom = math.floor(h / 2 + (ty + 1 - cy) * tile_px)
                painter.drawPixmap(left, top, right - left, bottom - top, pixmap)
        painter.fillRect(self.rect(), QColor(255, 255, 255, 70))  # calms the map so the track stands out
        for points, color in self.lines:
            if len(points) < 2:
                continue
            polygon = QPolygonF([self._project(la, lo, tz) for la, lo in points])
            painter.setPen(QPen(QColor(0, 0, 0, 150), 9, Qt.PenStyle.SolidLine, Qt.PenCapStyle.RoundCap, Qt.PenJoinStyle.RoundJoin))
            painter.drawPolyline(polygon)
            painter.setPen(QPen(color, 6, Qt.PenStyle.SolidLine, Qt.PenCapStyle.RoundCap, Qt.PenJoinStyle.RoundJoin))
            painter.drawPolyline(polygon)
        for lat, lon, color in self.dots:
            p = self._project(lat, lon, tz)
            painter.setPen(Qt.PenStyle.NoPen)
            painter.setBrush(QColor("white"))
            painter.drawEllipse(p, 9, 9)
            painter.setBrush(color)
            painter.drawEllipse(p, 6, 6)
        painter.setPen(QColor(s.on_surface_variant))
        painter.drawText(self.rect().adjusted(0, 0, -6, -4), Qt.AlignmentFlag.AlignBottom | Qt.AlignmentFlag.AlignRight,
                         "© OpenStreetMap")

    def mousePressEvent(self, event) -> None:
        if event.button() == Qt.MouseButton.LeftButton:
            self._drag = event.position()
            self.setCursor(Qt.CursorShape.ClosedHandCursor)

    def mouseMoveEvent(self, event) -> None:
        if self._drag is None:
            return
        delta = event.position() - self._drag
        self._drag = event.position()
        tz = max(geo.MIN_ZOOM, min(geo.MAX_ZOOM, math.floor(self.zoom)))
        tile_px = geo.TILE * 2 ** (self.zoom - tz)
        x = geo.world_x(self.center[1], tz) - delta.x() / tile_px
        y = geo.world_y(self.center[0], tz) - delta.y() / tile_px
        self.center = (max(-85.0, min(85.0, geo.lat_of(y, tz))), max(-180.0, min(180.0, geo.lon_of(x, tz))))
        self.update()

    def mouseReleaseEvent(self, event) -> None:
        self._drag = None
        self.setCursor(Qt.CursorShape.OpenHandCursor)

    def wheelEvent(self, event) -> None:
        steps = event.angleDelta().y() / 120
        self.zoom = max(geo.MIN_ZOOM, min(geo.MAX_ZOOM, self.zoom + 0.5 * steps))
        self.update()
