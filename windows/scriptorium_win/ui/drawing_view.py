"""The sketch editor: pen, eraser, colours, undo, zoom and pan (the same tools as the phone)."""
from __future__ import annotations

import math

from PySide6.QtCore import QPointF, QRectF, Qt
from PySide6.QtGui import QColor, QKeySequence, QPainter, QPainterPath, QPen, QPixmap, QShortcut
from PySide6.QtWidgets import QDialog, QHBoxLayout, QPushButton, QVBoxLayout, QWidget

from ..core import drawing as dr

PALETTE = [0x1A1A1A, 0xE53935, 0x1E88E5, 0x43A047, 0xFB8C00, 0x8E24AA]
WIDTHS = [("Fin", 2.5), ("Moyen", 5.0), ("Épais", 10.0)]


def _color(rgb: int) -> QColor:
    return QColor((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255)


def paint_strokes(p: QPainter, strokes, live=None) -> None:
    p.setRenderHint(QPainter.RenderHint.Antialiasing)
    for s in list(strokes) + ([live] if live else []):
        if not s.points:
            continue
        pen = QPen(_color(s.rgb), s.width, Qt.PenStyle.SolidLine, Qt.PenCapStyle.RoundCap, Qt.PenJoinStyle.RoundJoin)
        p.setPen(pen)
        if len(s.points) == 1:
            p.setBrush(_color(s.rgb))
            p.setPen(Qt.PenStyle.NoPen)
            p.drawEllipse(QPointF(*s.points[0]), s.width / 2, s.width / 2)
            continue
        path = QPainterPath(QPointF(*s.points[0]))
        for x, y in s.points[1:]:
            path.lineTo(x, y)
        p.setBrush(Qt.BrushStyle.NoBrush)
        p.drawPath(path)


def render_pixmap(strokes, max_px: int) -> QPixmap:
    """A drawing as a picture (thumbnails), white page included."""
    b = dr.bounds(strokes)
    if b is None:
        return QPixmap()
    margin = 24.0
    w, h = b[2] - b[0] + 2 * margin, b[3] - b[1] + 2 * margin
    scale = max_px / max(w, h)
    pixmap = QPixmap(max(1, int(w * scale)), max(1, int(h * scale)))
    pixmap.fill(Qt.GlobalColor.white)
    p = QPainter(pixmap)
    p.scale(scale, scale)
    p.translate(-b[0] + margin, -b[1] + margin)
    paint_strokes(p, strokes)
    p.end()
    return pixmap


class Canvas(QWidget):
    def __init__(self, strokes: list[dr.Stroke]):
        super().__init__()
        self.strokes = list(strokes)
        self.undo_stack: list[list[dr.Stroke]] = []
        self.redo_stack: list[list[dr.Stroke]] = []
        self.rgb, self.width_units, self.eraser = PALETTE[0], WIDTHS[1][1], False
        self.scale, self.offset = 1.0, QPointF(0, 0)
        self.live: list[tuple[float, float]] = []
        self.changed = False
        self._pan = None
        self._erase_start = None
        self.setMinimumSize(520, 380)
        self.setCursor(Qt.CursorShape.CrossCursor)
        self.setMouseTracking(False)

    # ---- coordinates ----

    def to_world(self, p: QPointF) -> tuple[float, float]:
        return (p.x() - self.offset.x()) / self.scale, (p.y() - self.offset.y()) / self.scale

    def fit(self) -> None:
        b = dr.bounds(self.strokes)
        if b is None or self.width() <= 0:
            self.scale, self.offset = 1.0, QPointF(0, 0)
        else:
            bw, bh = max(b[2] - b[0], 1.0), max(b[3] - b[1], 1.0)
            self.scale = max(0.15, min(4.0, min(self.width() * 0.9 / bw, self.height() * 0.9 / bh)))
            self.offset = QPointF(self.width() / 2 - (b[0] + bw / 2) * self.scale, self.height() / 2 - (b[1] + bh / 2) * self.scale)
        self.update()

    # ---- history ----

    def _push(self) -> None:
        self.undo_stack.append(self.strokes)
        self.redo_stack.clear()
        self.changed = True

    def undo(self) -> None:
        if self.undo_stack:
            self.redo_stack.append(self.strokes)
            self.strokes = self.undo_stack.pop()
            self.changed = True
            self.update()

    def redo(self) -> None:
        if self.redo_stack:
            self.undo_stack.append(self.strokes)
            self.strokes = self.redo_stack.pop()
            self.changed = True
            self.update()

    def clear_all(self) -> None:
        if self.strokes:
            self._push()
            self.strokes = []
            self.update()

    # ---- drawing ----

    def paintEvent(self, event) -> None:
        p = QPainter(self)
        p.fillRect(self.rect(), Qt.GlobalColor.white)
        p.translate(self.offset)
        p.scale(self.scale, self.scale)
        live = dr.Stroke(self.rgb, self.width_units, tuple(self.live)) if self.live else None
        paint_strokes(p, self.strokes, live)

    def _erase_at(self, x: float, y: float) -> None:
        radius = 14.0 / self.scale
        keep = [s for s in self.strokes if s.distance_to(x, y) > radius + s.width / 2]
        if len(keep) != len(self.strokes):
            self.strokes = keep
            self.update()

    def mousePressEvent(self, event) -> None:
        if event.button() in (Qt.MouseButton.MiddleButton, Qt.MouseButton.RightButton):
            self._pan = event.position()
            self.setCursor(Qt.CursorShape.ClosedHandCursor)
            return
        if event.button() != Qt.MouseButton.LeftButton:
            return
        x, y = self.to_world(event.position())
        if self.eraser:
            self._erase_start = self.strokes
            self._erase_at(x, y)
        else:
            self.live = [(x, y)]
            self.update()

    def mouseMoveEvent(self, event) -> None:
        if self._pan is not None:
            self.offset += event.position() - self._pan
            self._pan = event.position()
            self.update()
            return
        if not (event.buttons() & Qt.MouseButton.LeftButton):
            return
        x, y = self.to_world(event.position())
        if self.eraser:
            self._erase_at(x, y)
        elif self.live:
            lx, ly = self.live[-1]
            if math.hypot(x - lx, y - ly) >= 1.2 / self.scale:
                self.live.append((x, y))
                self.update()

    def mouseReleaseEvent(self, event) -> None:
        if self._pan is not None and event.button() in (Qt.MouseButton.MiddleButton, Qt.MouseButton.RightButton):
            self._pan = None
            self.setCursor(Qt.CursorShape.CrossCursor)
            return
        if event.button() != Qt.MouseButton.LeftButton:
            return
        if self.eraser:
            before, self._erase_start = self._erase_start, None
            if before is not None and len(before) != len(self.strokes):
                self.undo_stack.append(before)
                self.redo_stack.clear()
                self.changed = True
        elif self.live:
            self._push()
            self.strokes = self.strokes + [dr.Stroke(self.rgb, self.width_units, tuple(self.live))]
            self.live = []
            self.update()

    def wheelEvent(self, event) -> None:
        cursor = event.position()
        wx, wy = self.to_world(cursor)
        self.scale = max(0.15, min(10.0, self.scale * (1.15 ** (event.angleDelta().y() / 120))))
        self.offset = QPointF(cursor.x() - wx * self.scale, cursor.y() - wy * self.scale)
        self.update()


class DrawingDialog(QDialog):
    def __init__(self, parent, strokes: list[dr.Stroke]):
        super().__init__(parent)
        self.setWindowTitle("Dessin")
        self.resize(900, 640)
        self.canvas = Canvas(strokes)
        layout = QVBoxLayout(self)
        bar = QHBoxLayout()

        def button(text: str, slot, checkable: bool = False) -> QPushButton:
            b = QPushButton(text)
            b.setCheckable(checkable)
            b.clicked.connect(slot)
            bar.addWidget(b)
            return b

        self.pen = button("Stylo", lambda: self._tool(False), True)
        self.rubber = button("Gomme", lambda: self._tool(True), True)
        self.pen.setChecked(True)
        self.width_buttons = []
        for label, w in WIDTHS:
            b = button(label, lambda _=False, v=w: self._width(v), True)
            b.setChecked(w == self.canvas.width_units)
            self.width_buttons.append((b, w))
        for rgb in PALETTE:
            c = QPushButton()
            c.setFixedSize(26, 26)
            c.setStyleSheet(f"background: #{rgb:06x}; border: 2px solid #888; border-radius: 13px;")
            c.clicked.connect(lambda _=False, v=rgb: self._color(v))
            bar.addWidget(c)
        bar.addStretch()
        button("Annuler (Ctrl+Z)", self.canvas.undo)
        button("Rétablir", self.canvas.redo)
        button("Recentrer", self.canvas.fit)
        button("Tout effacer", self.canvas.clear_all)
        layout.addLayout(bar)
        layout.addWidget(self.canvas, 1)
        hint = QPushButton("Molette : zoom · clic droit ou milieu + glisser : déplacer la page")
        hint.setFlat(True)
        hint.setEnabled(False)
        layout.addWidget(hint)
        done = QHBoxLayout()
        done.addStretch()
        cancel = QPushButton("Annuler le dessin")
        cancel.clicked.connect(self.reject)
        ok = QPushButton("Terminer")
        ok.setDefault(True)
        ok.clicked.connect(self.accept)
        done.addWidget(cancel)
        done.addWidget(ok)
        layout.addLayout(done)
        QShortcut(QKeySequence("Ctrl+Z"), self, activated=self.canvas.undo)
        QShortcut(QKeySequence("Ctrl+Y"), self, activated=self.canvas.redo)
        if strokes:
            self.canvas.fit()

    def showEvent(self, event) -> None:
        super().showEvent(event)
        if self.canvas.strokes:
            self.canvas.fit()

    def _tool(self, eraser: bool) -> None:
        self.canvas.eraser = eraser
        self.pen.setChecked(not eraser)
        self.rubber.setChecked(eraser)

    def _width(self, value: float) -> None:
        self.canvas.width_units = value
        self._tool(False)
        for b, w in self.width_buttons:
            b.setChecked(w == value)

    def _color(self, rgb: int) -> None:
        self.canvas.rgb = rgb
        self._tool(False)
