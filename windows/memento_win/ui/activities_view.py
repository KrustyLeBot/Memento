"""The "Activités" folder: figures and the list, then one activity with its map, figures and charts."""
from __future__ import annotations

import bisect
import threading
import weakref
from collections import OrderedDict
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path

from PySide6.QtCore import QEvent, QPointF, QRect, Qt, Signal
from PySide6.QtGui import QColor, QLinearGradient, QPainter, QPainterPath, QPen, QPixmap
from PySide6.QtWidgets import (QCheckBox, QComboBox, QDialog, QDialogButtonBox, QFileDialog, QFormLayout, QFrame, QGridLayout, QHBoxLayout,
                               QLabel, QLineEdit, QMessageBox, QPlainTextEdit, QProgressBar, QPushButton, QScrollArea, QSizePolicy,
                               QStackedWidget, QToolButton, QVBoxLayout, QWidget)

from ..core import activity as act
from ..core import elevation, mapdata
from ..core.analysis import Analysis
from ..core.note import Note
from ..core.store import Store
from ..core.track import Sport
from . import icons, theme
from .mapview import MapPanel
from .scroll_memory import ScrollMemory, restore_area

EMOJI = {Sport.WALK: "🚶", Sport.BIKE: "🚴", Sport.EBIKE: "⚡", Sport.SKI: "⛷️", Sport.GPS: "📍", Sport.PLAIN: "⏱️"}
GPS_SPORTS = (Sport.WALK, Sport.BIKE, Sport.EBIKE, Sport.SKI, Sport.GPS)
# slow to fast: violet, magenta, red-orange (stands out on the pale map)
STOPS = [(0.0, (106, 0, 244)), (0.5, (229, 0, 164)), (1.0, (255, 61, 0))]


def speed_color(f: float) -> QColor:
    f = max(0.0, min(1.0, f))
    for (a, ca), (b, cb) in zip(STOPS, STOPS[1:]):
        if f <= b:
            t = (f - a) / (b - a)
            return QColor(*(round(x + (y - x) * t) for x, y in zip(ca, cb)))
    return QColor(*STOPS[-1][1])
DAYS = ["lun.", "mar.", "mer.", "jeu.", "ven.", "sam.", "dim."]
MONTHS = ["janv.", "févr.", "mars", "avr.", "mai", "juin", "juil.", "août", "sept.", "oct.", "nov.", "déc."]


def short_label(sport: Sport) -> str:
    return sport.label.split(" (")[0]


def fmt_date(ms: int) -> str:
    d = datetime.fromtimestamp(ms / 1000)
    return f"{DAYS[d.weekday()]} {d.day} {MONTHS[d.month - 1]} {d.year}, {d:%H:%M}"


def fmt_hour(ms: int) -> str:
    return f"{datetime.fromtimestamp(ms / 1000):%H:%M}"


def summary_line(i: act.ActivityInfo) -> str:
    parts = []
    if i.sport == Sport.SKI:
        # the whole track includes the lifts: what counts for skiing is the descents
        parts.append(act.fmt_duration(i.moving if i.moving > 0 else i.elapsed))
        if i.runs > 0:
            parts.append(f"{i.runs} descentes" + (f" · {i.drop} m" if i.drop > 0 else ""))
        if i.climbs > 0:
            parts.append(f"{i.climbs} montées")
        return " · ".join(parts)
    if i.distance > 0:
        parts.append(act.fmt_distance(i.distance))
    if i.moving > 0 or i.elapsed > 0:  # an untimed GPX (no <time> in the file) has no duration to show
        parts.append(act.fmt_duration(i.moving if i.moving > 0 else i.elapsed))
    if i.sport.has_gps and i.moving > 0 and i.distance > 0:
        parts.append(act.fmt_speed_for(i.sport, i.distance / i.moving))
    if i.ascent > 0:
        parts.append(f"↑ {i.ascent} m")
    return " · ".join(parts)


CLIMB = QColor("#00B0FF")


def page_lines(chunks) -> list[dict]:
    """map_lines' chunks in the map page's shape (setLines)."""
    return [{"coords": [[lon, lat] for lat, lon in points], "color": color.name()} for points, color in chunks]


def page_track(a: Analysis) -> dict:
    """The whole track with its speeds (setTrack), thinned to 5000 points like the Android host does."""
    s = [a.samples[i] for i in mapdata.thin_indices(len(a.samples), 5000)]
    speeds, avg = mapdata.track_speeds([p.time for p in s], [p.lat for p in s], [p.lon for p in s], [p.speed for p in s])
    return {"coords": [[p.lon, p.lat] for p in s], "speeds": speeds, "avg": avg}


def map_lines(a: Analysis, ski: bool, mode: str = "both"):
    """Chunks of the track coloured from violet (slow) to red (fast). Skiing: descents in that gradient and climbs
    in blue ("both"), descents only ("down"), or everything with the rest in grey ("all")."""
    s = a.samples
    if len(s) < 2:
        return []
    speeds = sorted(x.speed for x in s)
    top = max(speeds[min(int(len(speeds) * 0.9), len(speeds) - 1)], 0.5)
    max_gap = max(a.quality.median_interval_sec * 6, 30.0) * 1000

    def kind(i: int) -> int:  # 0 descent (or any point of another sport), 1 climb, 2 the rest of a ski day
        return 0 if not ski or s[i].down else (1 if s[i].up else 2)

    def shown(i: int) -> bool:
        return not ski or mode == "all" or s[i].down or (mode == "both" and s[i].up)

    out, i = [], 0
    while i < len(s) - 1:
        if not shown(i):
            i += 1
            continue
        first, j, total, k = i, i, 0.0, kind(i)
        while j + 1 < len(s) and j - first < 8 and s[j + 1].time - s[j].time <= max_gap and shown(j + 1) and kind(j + 1) == k:
            j += 1
            total += s[j].speed
        if j == first:
            i += 1
            continue
        color = CLIMB if k == 1 else QColor("#9AA0A6") if k == 2 else speed_color(total / (j - first) / top)
        out.append(([(p.lat, p.lon) for p in s[first:j + 1]], color))
        i = j
    return out


class Chart(QWidget):
    """A filled line chart with its extremes written above. Hovering it emits the position under the mouse
    (`coords[i]` as (lon, lat), None when the mouse leaves), which the map shows as a cursor."""
    hovered = Signal(object)

    def __init__(self, title: str, xs: list[float], ys: list[float], low: str, high: str, color: str,
                 coords: list[tuple[float, float]] | None = None, prefixes: tuple[str, str] = ("min", "max")):
        super().__init__()
        self.xs, self.ys, self.color, self.coords = xs, ys, QColor(color), coords
        s = theme.scheme()
        layout = QVBoxLayout(self)
        layout.setContentsMargins(0, 0, 0, 0)
        head = QHBoxLayout()
        name = QLabel(title)
        name.setStyleSheet(f"color: {s.primary}; font-weight: 600;")
        extremes = QLabel(f"{prefixes[0]} {low}   {prefixes[1]} {high}")
        extremes.setStyleSheet(f"color: {s.on_surface_variant}; font-size: 8.5pt;")
        head.addWidget(name)
        head.addStretch()
        head.addWidget(extremes)
        layout.addLayout(head)
        self.canvas = _ChartCanvas(self)
        layout.addWidget(self.canvas)


class _ChartCanvas(QWidget):
    def __init__(self, chart: Chart):
        super().__init__()
        self.chart = chart
        self.setFixedHeight(110)
        self.setMouseTracking(chart.coords is not None)
        self._picture: QPixmap | None = None  # the drawn curve, redrawn only when the size changes
        self._hovered = -1  # the point last sent to the map: moving within it sends nothing

    def nearest(self, x_pos: float) -> int:
        """The point under the mouse (the first of equal distances, as a scan would find): xs ascend along the
        track, so it is a bisection, not a pass over every point on each mouse move."""
        xs = self.chart.xs
        x = xs[0] + max(0.0, min(1.0, x_pos / self.width())) * (xs[-1] - xs[0])
        i = bisect.bisect_left(xs, x)
        if i >= len(xs):
            i = len(xs) - 1
        if i > 0 and x - xs[i - 1] <= xs[i] - x:
            i = bisect.bisect_left(xs, xs[i - 1])
        return i

    def mouseMoveEvent(self, event) -> None:
        xs, coords = self.chart.xs, self.chart.coords
        if not coords or len(xs) < 2 or self.width() <= 0:
            return
        i = self.nearest(event.position().x())
        if i != self._hovered:
            self._hovered = i
            self.chart.hovered.emit(coords[i])

    def leaveEvent(self, event) -> None:
        self._hovered = -1
        if self.chart.coords:
            self.chart.hovered.emit(None)

    def resizeEvent(self, event) -> None:
        self._picture = None
        super().resizeEvent(event)

    def paintEvent(self, event) -> None:
        if len(self.chart.xs) < 2:
            return
        ratio = self.devicePixelRatioF()
        if self._picture is None or self._picture.devicePixelRatio() != ratio:
            self._picture = self._draw(ratio)
        QPainter(self).drawPixmap(0, 0, self._picture)

    def _draw(self, ratio: float) -> QPixmap:
        """The antialiased curve and its gradient: tens of milliseconds, once per size instead of on every repaint
        (scrolling the page, the map cursor moving over it)."""
        xs, ys = self.chart.xs, self.chart.ys
        w, h = self.width(), self.height()
        picture = QPixmap(max(1, round(w * ratio)), max(1, round(h * ratio)))
        picture.setDevicePixelRatio(ratio)
        picture.fill(Qt.GlobalColor.transparent)
        p = QPainter(picture)
        p.setRenderHint(QPainter.RenderHint.Antialiasing)
        lo, hi = min(ys), max(ys)
        span_y, min_x = max(hi - lo, 1e-6), xs[0]
        span_x = max(xs[-1] - min_x, 1e-6)
        path = QPainterPath()
        for i, (x, y) in enumerate(zip(xs, ys)):
            pt = QPointF((x - min_x) / span_x * w, h - (y - lo) / span_y * h * 0.9 - h * 0.05)
            path.moveTo(pt) if i == 0 else path.lineTo(pt)
        fill = QPainterPath(path)
        fill.lineTo(w, h)
        fill.lineTo(0, h)
        fill.closeSubpath()
        gradient = QLinearGradient(0, 0, 0, h)
        top = QColor(self.chart.color)
        top.setAlphaF(0.35)
        bottom = QColor(self.chart.color)
        bottom.setAlphaF(0.02)
        gradient.setColorAt(0, top)
        gradient.setColorAt(1, bottom)
        p.fillPath(fill, gradient)
        p.setPen(QPen(self.chart.color, 2))
        p.drawPath(path)
        p.end()
        return picture


class StopList(QWidget):
    """The pauses and stops, one painted line each: a 60-hour race has hundreds, and a row of labels for each
    took about a second to build and lay out."""
    GAP = 8  # the card's spacing between the rows it used to hold

    def __init__(self, rows: list[tuple[str, str]]):
        super().__init__()
        self.rows = rows
        self.setSizePolicy(QSizePolicy.Policy.Preferred, QSizePolicy.Policy.Fixed)
        self._fit()

    def _pitch(self) -> int:
        return self.fontMetrics().height() + self.GAP

    def _fit(self) -> None:
        self.setFixedHeight(max(0, self._pitch() * len(self.rows) - self.GAP))

    def changeEvent(self, event) -> None:
        if event.type() == QEvent.Type.FontChange:
            self._fit()
        super().changeEvent(event)

    def paintEvent(self, event) -> None:
        s = theme.scheme()
        p = QPainter(self)
        pitch, line = self._pitch(), self.fontMetrics().height()
        area = event.rect()
        first = max(0, area.top() // pitch)
        last = min(len(self.rows), area.bottom() // pitch + 1)
        left, right = Qt.AlignmentFlag.AlignLeft | Qt.AlignmentFlag.AlignVCenter, Qt.AlignmentFlag.AlignRight | Qt.AlignmentFlag.AlignVCenter
        for k in range(first, last):
            r = QRect(0, k * pitch, self.width(), line)
            p.setPen(QColor(s.on_surface))
            p.drawText(r, left, self.rows[k][0])
            p.setPen(QColor(s.on_surface_variant))
            p.drawText(r, right, self.rows[k][1])


@dataclass
class ChartData:
    title: str
    xs: list[float]
    ys: list[float]
    low: str
    high: str
    color: str | None  # None: the theme's primary colour, read when the chart is built
    coords: list[tuple[float, float]]
    prefixes: tuple[str, str] = ("min", "max")


@dataclass
class DetailData:
    """What the detail page draws from an analysis, computed once (off the window's thread when it loads): every
    figure here is a pass over all the samples, and a 60-hour race has 200 000."""
    track: dict | None
    lines: list | None
    square: list | None
    dots: list
    stops: list[tuple[str, str]]
    charts: list[ChartData]


def detail_data(analysis: Analysis, sport: Sport) -> DetailData:
    samples = analysis.samples
    ski = sport == Sport.SKI
    track = lines = square = None
    dots = []
    if len(samples) >= 2:
        dots = [{"lon": samples[0].lon, "lat": samples[0].lat, "color": "#00C853"},
                {"lon": samples[-1].lon, "lat": samples[-1].lat, "color": "#FF1744"}]
        if ski:
            # the square is the whole day's, whichever lines the selector shows
            lats, lons = [p.lat for p in samples], [p.lon for p in samples]
            square = list(mapdata.square_of(min(lons), min(lats), max(lons), max(lats)))
            lines = page_lines(map_lines(analysis, True))
        else:
            track = page_track(analysis)
    stops = [("Pause" if stop.manual else "Arrêt détecté", f"{act.fmt_duration(stop.duration_ms // 1000)} · {fmt_hour(stop.start)}")
             for stop in sorted(analysis.pauses + analysis.stops, key=lambda x: x.start)]
    charts = []
    idx = thin(len(samples))
    at = lambda ids: [(samples[i].lon, samples[i].lat) for i in ids]  # noqa: E731
    if analysis.quality.has_altitude and len(idx) >= 2 and all(samples[i].alt is not None for i in idx):
        charts.append(ChartData("Altitude", [samples[i].dist for i in idx], [samples[i].alt for i in idx],
                                f"{round(analysis.stats.min_alt or 0)} m", f"{round(analysis.stats.max_alt or 0)} m", None, at(idx)))
    if len(idx) >= 2 and analysis.stats.max_speed > 0:
        xs = [samples[i].dist for i in idx]
        if sport == Sport.WALK:
            # pace drawn upside down (minus seconds per km): faster stays higher, like a speed curve
            pace = [-act.chart_pace(samples[i].speed) for i in idx]
            charts.append(ChartData("Allure", xs, pace, act.fmt_pace(1000 / -min(pace)), act.fmt_pace(1000 / -max(pace)),
                                    "#2E7DF6", at(idx), ("plus lente", "plus rapide")))
        else:
            charts.append(ChartData("Vitesse", xs, [samples[i].speed * 3.6 for i in idx], "0 km/h",
                                    act.fmt_speed(analysis.stats.max_speed), "#2E7DF6", at(idx)))
    hr_idx = [i for i in idx if samples[i].hr is not None]
    if analysis.quality.has_hr and len(hr_idx) >= 2:
        hr_min = min([x.hr for x in samples if x.hr is not None])
        charts.append(ChartData("Fréquence cardiaque", [samples[i].dist for i in hr_idx], [float(samples[i].hr) for i in hr_idx],
                                f"{hr_min} bpm", f"{analysis.stats.hr_max or 0} bpm", "#E5484D", at(hr_idx)))
    return DetailData(track, lines, square, dots, stops, charts)


def thin(n: int, limit: int = 300) -> list[int]:
    return list(range(n)) if n <= limit else [i * (n - 1) // (limit - 1) for i in range(limit)]


def card(title: str | None = None) -> tuple[QFrame, QVBoxLayout]:
    s = theme.scheme()
    frame = QFrame()
    frame.setObjectName("sec")
    frame.setStyleSheet(f"#sec {{ background: {s.surface_container}; border-radius: 16px; }}")
    layout = QVBoxLayout(frame)
    layout.setContentsMargins(16, 12, 16, 14)
    layout.setSpacing(8)
    if title:
        label = QLabel(title)
        label.setStyleSheet(f"color: {s.primary}; font-weight: 600;")
        layout.addWidget(label)
    return frame, layout


def stat_grid(items: list[tuple[str, str]], columns: int = 3) -> QWidget:
    s = theme.scheme()
    host = QWidget()
    grid = QGridLayout(host)
    grid.setContentsMargins(0, 0, 0, 0)
    grid.setHorizontalSpacing(24)
    grid.setVerticalSpacing(10)
    for i, (label, value) in enumerate(items):
        cell = QVBoxLayout()
        cell.setSpacing(0)
        l = QLabel(label)
        l.setStyleSheet(f"color: {s.on_surface_variant}; font-size: 8.5pt;")
        v = QLabel(value)
        v.setStyleSheet("font-size: 13pt; font-weight: 600;")
        cell.addWidget(l)
        cell.addWidget(v)
        grid.addLayout(cell, i // columns, i % columns)
    return host


class ImportDialog(QDialog):
    def __init__(self, parent, name: str, title: str, sport: Sport, existing: Note | None, needs_altitude: bool):
        super().__init__(parent)
        self.setWindowTitle("Importer l'activité")
        form = QFormLayout(self)
        form.addRow(QLabel(name))
        if existing is not None:
            warn = QLabel(f"Déjà importée : « {existing.title} ». Elle sera remplacée (nom, description et photos conservés).")
            warn.setWordWrap(True)
            warn.setStyleSheet("color: #E5A100; font-weight: 600;")
            form.addRow(warn)
        self.title = QLineEdit(title)
        self.sport = QComboBox()
        for s in GPS_SPORTS:
            self.sport.addItem(f"{EMOJI[s]} {short_label(s)}", s)
        self.sport.setCurrentIndex(max(0, self.sport.findData(sport)))
        form.addRow("Nom", self.title)
        form.addRow("Sport", self.sport)
        self.relief = QCheckBox("Compléter l'altitude avec le relief (envoie les positions à opentopodata.org)")
        self.relief.setChecked(True)
        self.relief.setVisible(needs_altitude)
        form.addRow(self.relief)
        buttons = QDialogButtonBox(QDialogButtonBox.StandardButton.Ok | QDialogButtonBox.StandardButton.Cancel)
        buttons.button(QDialogButtonBox.StandardButton.Ok).setText("Remplacer" if existing is not None else "Importer")
        buttons.accepted.connect(self.accept)
        buttons.rejected.connect(self.reject)
        form.addRow(buttons)


class EditDialog(QDialog):
    def __init__(self, parent, note: Note, has_file: bool):
        super().__init__(parent)
        self.setWindowTitle("Modifier l'activité")
        self.resize(420, 300)
        form = QFormLayout(self)
        self.title = QLineEdit(note.title)
        self.description = QPlainTextEdit(note.body)
        self.sport = QComboBox()
        options = GPS_SPORTS if has_file else (Sport.PLAIN,)
        for s in options:
            self.sport.addItem(f"{EMOJI[s]} {s.label}", s)
        if note.activity:
            self.sport.setCurrentIndex(max(0, self.sport.findData(note.activity.sport)))
        form.addRow("Nom", self.title)
        form.addRow("Description", self.description)
        form.addRow("Sport", self.sport)
        buttons = QDialogButtonBox(QDialogButtonBox.StandardButton.Ok | QDialogButtonBox.StandardButton.Cancel)
        buttons.accepted.connect(self.accept)
        buttons.rejected.connect(self.reject)
        form.addRow(buttons)


def rows_style() -> str:
    """The look of every ActivityRow, set once on the list that holds them: a style sheet on each row and label
    was most of the time it took to build hundreds of rows."""
    s = theme.scheme()
    return (f"#row {{ background: {s.surface_container}; border-radius: 14px; }} "
            f"#row:hover {{ background: {s.surface_high}; }} #row QLabel {{ background: transparent; }} "
            "#row QLabel#rowEmoji { font-size: 20pt; } #row QLabel#rowTitle { font-weight: 600; font-size: 11pt; } "
            f"#row QLabel#rowWhen {{ color: {s.on_surface_variant}; font-size: 9pt; }}")


class ActivityRow(QFrame):
    """One activity in the list, styled by rows_style() on its parent."""
    clicked = Signal(object)
    toggled = Signal(object, bool)

    def __init__(self, note: Note, selecting: bool = False, checked: bool = False):
        super().__init__()
        self.note = note
        self.selecting = selecting
        self.setObjectName("row")
        self.setCursor(Qt.CursorShape.PointingHandCursor)
        row = QHBoxLayout(self)
        row.setContentsMargins(14, 10, 14, 10)
        if selecting:
            box = QCheckBox()
            box.setChecked(checked)
            box.toggled.connect(lambda on: self.toggled.emit(self.note, on))
            row.addWidget(box)
        emoji = QLabel(EMOJI.get(note.activity.sport, "📍"))
        emoji.setObjectName("rowEmoji")
        row.addWidget(emoji)
        col = QVBoxLayout()
        col.setSpacing(0)
        title = QLabel(note.title)
        title.setObjectName("rowTitle")
        when = QLabel(fmt_date(note.activity.start))
        when.setObjectName("rowWhen")
        col.addWidget(title)
        col.addWidget(when)
        col.addWidget(QLabel(summary_line(note.activity)))
        row.addLayout(col, 1)

    def mouseReleaseEvent(self, event) -> None:
        if event.button() == Qt.MouseButton.LeftButton and self.rect().contains(event.position().toPoint()) and not self.selecting:
            self.clicked.emit(self.note)
        super().mouseReleaseEvent(event)


class ActivitiesScreen(QWidget):
    back_requested = Signal()
    detail_back = Signal()  # back (or delete) on an activity: the window returns where it was opened from
    message = Signal(str)
    changed = Signal()  # something was written: the window rescans
    open_file = Signal(str)
    reveal_file = Signal(str)
    _track_loaded = Signal(str, object, object)  # note path, (analysis, DetailData) or None, error message or None (from the worker)
    _job_progress = Signal(str, int, int)  # text, step, steps (0: no count), from a worker
    _job_done = Signal(object, object, object)  # continuation, result, error, from a worker

    def __init__(self):
        super().__init__()
        self.store: Store | None = None
        self.activities: list[Note] = []
        self.sport_filter: Sport | None = None
        self.selecting = False
        self.selected: set[str] = set()
        self.included: set[Sport] = set(Sport)  # sports counted in the totals (settings)
        self.current: Note | None = None
        self.memory = ScrollMemory()  # where the list was when an activity was opened from it
        self.row_widgets: dict[str, ActivityRow] = {}  # by note id
        self.config = None  # set by the window: remembers the map's 3D/2D choice
        self.map_panel: MapPanel | None = None
        self.prepared: OrderedDict[tuple, tuple] = OrderedDict()  # (id(analysis), sport) -> (weakref, DetailData)
        self.busy = False  # an import, an altitude lookup or a new analysis runs on a worker
        self._imports: list[str] = []
        self._imported: Note | None = None
        self.stack = QStackedWidget()
        outer = QVBoxLayout(self)
        outer.setContentsMargins(0, 0, 0, 0)
        outer.addWidget(self.stack)
        # long work runs off the window's thread; this line says what it is doing, on the list and on an activity
        self.footer = QWidget()
        foot = QHBoxLayout(self.footer)
        foot.setContentsMargins(14, 4, 14, 8)
        self.status = QLabel()
        self.progress = QProgressBar()
        self.progress.setTextVisible(False)
        self.progress.setFixedWidth(180)
        self.progress.setFixedHeight(8)
        foot.addWidget(self.status, 1)
        foot.addWidget(self.progress)
        self.footer.hide()
        outer.addWidget(self.footer)
        self.list_page = QWidget()
        self.detail_page = QWidget()
        self.stack.addWidget(self.list_page)
        self.stack.addWidget(self.detail_page)
        self._build_list()
        self.detail_layout = QVBoxLayout(self.detail_page)
        self.detail_layout.setContentsMargins(0, 0, 0, 0)
        self._track_loaded.connect(self._on_track_loaded)
        self._job_progress.connect(self._show_progress)
        self._job_done.connect(lambda then, result, error: then(result, error))

    def set_store(self, store: Store) -> None:
        self.store = store

    # ---- list ----

    def _build_list(self) -> None:
        layout = QVBoxLayout(self.list_page)
        layout.setContentsMargins(14, 12, 14, 0)
        head = QHBoxLayout()
        self.back = QToolButton()
        self.back.clicked.connect(self.back_requested)
        title = QLabel("Activités")
        title.setStyleSheet("font-size: 16pt; font-weight: 700;")
        self.import_button = QPushButton("Importer FIT / GPX…")
        self.import_button.clicked.connect(self.import_files)
        self.select_button = QPushButton("Sélectionner")
        self.select_button.setCheckable(True)
        self.select_button.toggled.connect(self._select_mode)
        self.relief_button = QPushButton("Compléter l'altitude (relief)")
        self.relief_button.clicked.connect(self.complete_altitudes)
        head.addWidget(self.back)
        head.addWidget(title)
        head.addStretch()
        head.addWidget(self.select_button)
        head.addWidget(self.relief_button)
        head.addWidget(self.import_button)
        layout.addLayout(head)
        self.totals = QHBoxLayout()
        layout.addLayout(self.totals)
        self.filter = QComboBox()
        self.filter.addItem("Tous les sports", None)
        for s in Sport:
            self.filter.addItem(f"{EMOJI[s]} {short_label(s)}", s)
        self.filter.currentIndexChanged.connect(self._filter_changed)
        layout.addWidget(self.filter)
        self.scroll = QScrollArea()
        self.scroll.setWidgetResizable(True)
        self.scroll.setFrameShape(QFrame.Shape.NoFrame)
        self.rows_host = QWidget()
        self.rows = QVBoxLayout(self.rows_host)
        self.rows.setSpacing(8)
        self.rows.setAlignment(Qt.AlignmentFlag.AlignTop)
        self.scroll.setWidget(self.rows_host)
        layout.addWidget(self.scroll, 1)
        self.restyle()

    def restyle(self) -> None:
        self.back.setIcon(icons.icon("back", theme.scheme().on_surface, 22))
        if self.map_panel is not None:
            self.map_panel.restyle()
        self.rebuild()

    # ---- map (one page kept for every activity) ----

    def preload_map(self) -> None:
        self._map()

    def _map(self) -> MapPanel:
        if self.map_panel is None:
            self.map_panel = MapPanel(getattr(self.config, "map_mode", "3d"), self)
            self.map_panel.hide()
            self.map_panel.mode_changed.connect(self._map_mode_changed)
        return self.map_panel

    def _map_mode_changed(self, mode: str) -> None:
        if self.config is not None:
            self.config.map_mode = mode

    def _park_map(self) -> None:
        """Takes the map out of the detail page before that page is deleted, so the loaded view survives."""
        if self.map_panel is not None:
            self.map_panel.collapse()  # back in the detail page, which is about to go
        if self.map_panel is not None and self.map_panel.parent() is not self:
            self.map_panel.hide()
            self.map_panel.setParent(self)

    def set_included(self, sports: set[Sport]) -> None:
        self.included = set(sports)
        self.rebuild()

    def set_activities(self, notes: list[Note]) -> None:
        activities = sorted((n for n in notes if n.activity), key=lambda n: -n.activity.start)
        if len(activities) == len(self.activities) and all(a is b for a, b in zip(activities, self.activities)):
            return  # unchanged (the store hands back the same objects): rebuilding hundreds of rows freezes the window
        self.activities = activities
        self.rebuild()

    def _select_mode(self, on: bool) -> None:
        self.selecting = on
        if not on:
            self.selected.clear()
        self.select_button.setText("Terminer la sélection" if on else "Sélectionner")
        self._update_relief_button()
        self.rebuild()

    def _toggle(self, note: Note, checked: bool) -> None:
        (self.selected.add if checked else self.selected.discard)(note.path)
        self._update_relief_button()

    def _update_relief_button(self) -> None:
        self.relief_button.setText(f"Compléter l'altitude ({len(self.selected)})" if self.selected else "Compléter l'altitude (toutes)")

    def complete_altitudes(self) -> None:
        if self.store is None or not self.activities:
            return
        targets = [n for n in self.activities if not self.selected or n.path in self.selected]
        answer = QMessageBox.question(
            self, "Compléter l'altitude",
            f"{len(targets)} activité(s). Pour celles dont le fichier n'a pas d'altitude, les positions (environ une par 30 m) "
            "sont envoyées à opentopodata.org, à raison d'une requête par seconde.\n"
            "Celles qui ont déjà une altitude (enregistrées avec le baromètre, ou déjà complétées) sont ignorées.")
        if answer != QMessageBox.StandardButton.Yes:
            return

        def finished(result, error) -> None:
            self.selected.clear()
            self.select_button.setChecked(False)
            if error is not None:
                QMessageBox.warning(self, "Altitude", str(error))
            else:
                done, skipped, failure = result
                QMessageBox.information(self, "Altitude", f"{done} complétée(s), {skipped} ignorée(s)." + (f"\nArrêté : {failure}" if failure else ""))
            self.changed.emit()
        self._run("Compléter l'altitude…", lambda progress: self.store.fill_altitudes(targets, progress), finished)

    # ---- work off the window's thread ----

    def _run(self, text: str, work, then) -> bool:
        """work(progress) on a worker thread (reading and analysing a long race takes seconds), then(result, error)
        back on the window's thread. One at a time: False, with a word to the user, while another runs."""
        if self.busy:
            QMessageBox.information(self, "Patiente", "Une opération est déjà en cours sur les activités.")
            return False
        self.busy = True
        self.import_button.setEnabled(False)
        self.relief_button.setEnabled(False)
        self._show_progress(text, 0, 0)

        def progress(text: str, step: int = 0, steps: int = 0) -> None:
            self._job_progress.emit(text, step, steps)

        def run() -> None:
            try:
                result, error = work(progress), None
            except Exception as failure:  # noqa: BLE001 — handed to `then`, which tells the user
                result, error = None, failure
            try:
                self._job_done.emit(lambda r, e: self._finish(then, r, e), result, error)
            except RuntimeError:
                pass  # the window is already gone
        threading.Thread(target=run, daemon=True).start()
        return True

    def _finish(self, then, result, error) -> None:
        self.busy = False
        self.import_button.setEnabled(True)
        self.relief_button.setEnabled(True)
        self.footer.hide()
        then(result, error)

    def _show_progress(self, text: str, step: int, steps: int) -> None:
        if not self.busy:
            return
        self.status.setText(text)
        self.progress.setRange(0, steps)  # 0..0: moving without a count
        self.progress.setValue(step)
        self.footer.show()

    def _filter_changed(self) -> None:
        self.sport_filter = self.filter.currentData()
        self.rebuild()

    def rebuild(self) -> None:
        s = theme.scheme()
        while self.totals.count():
            item = self.totals.takeAt(0)
            if item.widget():
                item.widget().deleteLater()
        now = datetime.now()
        week_start = datetime(now.year, now.month, now.day).timestamp() * 1000 - now.weekday() * 86_400_000
        month_start = datetime(now.year, now.month, 1).timestamp() * 1000
        for name, since in (("Semaine", week_start), ("Mois", month_start), ("Total", 0)):
            items = [n.activity for n in self.activities if n.activity.start >= since and n.activity.sport in self.included]
            ski = [i for i in items if i.sport == Sport.SKI]
            frame, lay = card(name)
            lay.addWidget(QLabel(f"{len(items)} activité{'s' if len(items) > 1 else ''}"))
            big = QLabel(act.fmt_distance(sum(i.distance for i in items)))
            big.setStyleSheet("font-size: 13pt; font-weight: 600;")
            lay.addWidget(big)
            detail = act.fmt_duration(sum(i.moving for i in items))
            ascent = sum(i.ascent for i in items)
            lay.addWidget(QLabel(detail + (f" · ↑ {ascent} m" if ascent else "")))
            if ski:
                lay.addWidget(QLabel(f"⛷️ {sum(i.runs for i in ski)} desc. · {sum(i.drop for i in ski)} m"))
            self.totals.addWidget(frame)
        # a row whose note is the very same object is kept: an import or a sync tick builds one row, not hundreds
        reusable: dict[str, ActivityRow] = {}
        while self.rows.count():
            widget = self.rows.takeAt(0).widget()
            if widget is None:
                continue
            if isinstance(widget, ActivityRow) and not widget.selecting and not self.selecting:
                reusable[widget.note.path] = widget
            else:
                widget.deleteLater()
        shown = [n for n in self.activities if self.sport_filter is None or n.activity.sport == self.sport_filter]
        self.row_widgets = {}
        style = rows_style()
        if self.rows_host.styleSheet() != style:
            self.rows_host.setStyleSheet(style)
        if not shown:
            hint = QLabel("Aucune activité. Importe un fichier FIT ou GPX (montre, Strava).")
            hint.setStyleSheet(f"color: {s.on_surface_variant};")
            self.rows.addWidget(hint)
        for n in shown:
            row = reusable.pop(n.path, None)
            if row is None or row.note is not n:
                if row is not None:
                    row.deleteLater()
                row = ActivityRow(n, self.selecting, n.path in self.selected)
                row.clicked.connect(self._row_clicked)
                row.toggled.connect(self._toggle)
            self.rows.addWidget(row)
            self.row_widgets[n.id] = row
        for row in reusable.values():
            row.deleteLater()

    # ---- import ----

    def import_files(self) -> None:
        if self.store is None:
            return
        if self.busy:
            QMessageBox.information(self, "Patiente", "Une opération est déjà en cours sur les activités.")
            return
        paths, _ = QFileDialog.getOpenFileNames(self, "Importer des activités", str(Path.home()),
                                                "Activités (*.fit *.gpx);;Tous les fichiers (*)")
        if paths:
            self._imports, self._imported = list(paths), None
            self._import_next()

    def _import_next(self) -> None:
        """One file after the other: read it (worker), ask its name and sport, then copy and analyse it (worker)."""
        if not self._imports:
            if self._imported is not None:
                self.changed.emit()
                self.open_activity(self._imported)
            return
        source = Path(self._imports.pop(0))

        def read(progress):
            parsed = self.store.read_source(source)
            return parsed, elevation.missing(parsed[0])
        if not self._run(f"Lecture de {source.name}…", read, lambda result, error: self._import_ask(source, result, error)):
            self._imports = []

    def _import_ask(self, source: Path, result, error) -> None:
        if error is not None:
            QMessageBox.warning(self, "Import impossible", f"{source.name} : {error}")
            self._import_next()
            return
        from ..core.store import default_title
        parsed, needs_altitude = result
        track = parsed[0]
        existing = self.store.find_activity(track.start_time)
        sport = Sport.guess(track.fit_sport)
        if existing is not None and sport == Sport.GPS and existing.activity:
            sport = existing.activity.sport
        title = existing.title if existing else default_title(sport, track.start_time or 0, track.name)
        dialog = ImportDialog(self, source.name, title, sport, existing, needs_altitude)
        if dialog.exec() != QDialog.DialogCode.Accepted:
            self._import_next()
            return
        title, sport = dialog.title.text(), dialog.sport.currentData()
        relief = dialog.relief.isVisible() and dialog.relief.isChecked()

        def work(progress):
            note = self.store.import_activity(source, title, sport, existing, parsed=parsed)
            altitude_error = None
            if relief:
                try:
                    note = self.store.fetch_altitude(note, progress=lambda n, total: progress(
                        f"Altitude de {source.name} : relief {n + 1}/{total}…", n, total))
                except Exception as failure:  # noqa: BLE001 — the activity stays, imported without altitude
                    altitude_error = failure
            analysis = self.store.cached_analysis(note.activity) if note.activity and note.activity.file else None
            data = detail_data(analysis, note.activity.sport) if analysis is not None else None
            return note, altitude_error, analysis, data

        def done(result, failure) -> None:
            if failure is not None:
                QMessageBox.warning(self, "Import impossible", f"{source.name} : {failure}")
            else:
                note, altitude_error, analysis, data = result
                if data is not None:
                    self._keep_prepared(analysis, note.activity.sport, data)
                self._imported = note
                if altitude_error is not None:
                    QMessageBox.warning(self, "Altitude indisponible", f"{altitude_error}\nL'activité est importée sans altitude.")
            self._import_next()
        if not self._run(f"Import de {source.name} : copie et analyse du tracé…", work, done):
            self._imports = []

    def _fetch_altitude(self, note: Note) -> None:
        """Looks the altitude up (a few seconds: the service allows one request per second), then reopens."""
        def done(result, error) -> None:
            if error is not None:
                QMessageBox.warning(self, "Altitude indisponible", f"{error}\nL'activité reste sans altitude.")
            else:
                self._reopen(result)
        self._run("Altitude : relief…", lambda progress: self.store.fetch_altitude(
            note, progress=lambda n, total: progress(f"Altitude : relief {n + 1}/{total}…", n, total)), done)

    # ---- detail ----

    def show_list(self) -> None:
        self.current = None
        self._park_map()
        self.stack.setCurrentWidget(self.list_page)
        if self.memory.pending:
            value, key = self.memory.take()
            restore_area(self.scroll, value, self.row_widgets.get(key))

    def _row_clicked(self, note: Note) -> None:
        self.memory.remember(self.scroll.verticalScrollBar().value(), note.id)
        self.open_activity(note)

    def open_activity(self, note: Note) -> None:
        if self.store is None or note.activity is None:
            return
        self.current = note
        info = note.activity
        cached = self.store.cached_analysis(info) if info.file else None
        if not info.file or cached is not None:
            # the figures of an analysis just made (an import) take a tenth of a second for a long race: here
            self._show_detail(note, cached, self._prepared_for(cached, info.sport) if cached is not None else None)
        else:
            # reading and analysing a long track takes seconds: off the window's thread, the detail fills in after
            self._show_detail(note, None, loading=True)
            threading.Thread(target=self._load_track, args=(note,), daemon=True).start()
        self.stack.setCurrentWidget(self.detail_page)

    def _load_track(self, note: Note) -> None:
        try:
            analysis = self.store.analysis(note.activity)
            loaded = analysis, detail_data(analysis, note.activity.sport)
            self._track_loaded.emit(note.path, loaded, None)
        except Exception as error:  # noqa: BLE001 (shown on the page, as before)
            self._track_loaded.emit(note.path, None, str(error))

    def _on_track_loaded(self, path: str, loaded: tuple | None, error: str | None) -> None:
        analysis, data = loaded or (None, None)
        if analysis is not None and self.current is not None:
            self._keep_prepared(analysis, self.current.activity.sport, data)
        if self.current is not None and self.current.path == path and self.stack.currentWidget() is self.detail_page:
            self._show_detail(self.current, analysis, data, error=error)

    def _prepared_for(self, analysis: Analysis, sport: Sport) -> DetailData | None:
        hit = self.prepared.get((id(analysis), sport))
        return hit[1] if hit is not None and hit[0]() is analysis else None

    def _keep_prepared(self, analysis: Analysis, sport: Sport, data: DetailData) -> None:
        """Remembered as long as the store keeps the analysis: reopening a long race rebuilds no figure."""
        key = (id(analysis), sport)
        self.prepared[key] = (weakref.ref(analysis), data)
        self.prepared.move_to_end(key)
        for stale in [k for k, (ref, _d) in self.prepared.items() if ref() is None]:
            del self.prepared[stale]
        while len(self.prepared) > 4:
            self.prepared.popitem(last=False)

    def _show_detail(self, note: Note, analysis: Analysis | None, data: DetailData | None = None, loading: bool = False,
                     error: str | None = None) -> None:
        self._park_map()
        while self.detail_layout.count():
            item = self.detail_layout.takeAt(0)
            if item.widget():
                item.widget().deleteLater()
        if analysis is not None and data is None:
            data = detail_data(analysis, note.activity.sport)
            self._keep_prepared(analysis, note.activity.sport, data)
        self.detail_layout.addWidget(self._detail(note, analysis, data, loading, error))

    def _detail(self, note: Note, analysis: Analysis | None, data: DetailData | None = None, loading: bool = False,
                error: str | None = None) -> QWidget:
        s = theme.scheme()
        info = note.activity
        host = QWidget()
        outer = QVBoxLayout(host)
        outer.setContentsMargins(14, 12, 14, 0)
        head = QHBoxLayout()
        back = QToolButton()
        back.setIcon(icons.icon("back", s.on_surface, 22))
        back.clicked.connect(self.detail_back)
        title = QLabel(note.title)
        title.setStyleSheet("font-size: 15pt; font-weight: 700;")
        edit = QPushButton("Modifier")
        edit.clicked.connect(lambda: self._edit(note))
        delete = QToolButton()
        delete.setIcon(icons.icon("delete", s.on_surface, 22))
        delete.setToolTip("Supprimer")
        delete.clicked.connect(lambda: self._delete(note))
        for w in (back, title):
            head.addWidget(w)
        head.addStretch()
        head.addWidget(edit)
        head.addWidget(delete)
        outer.addLayout(head)

        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        scroll.setFrameShape(QFrame.Shape.NoFrame)
        body = QWidget()
        col = QVBoxLayout(body)
        col.setSpacing(10)
        col.setAlignment(Qt.AlignmentFlag.AlignTop)
        sub = QLabel(f"{EMOJI[info.sport]} {info.sport.label} · {fmt_date(info.start)}")
        sub.setStyleSheet(f"color: {s.on_surface_variant};")
        col.addWidget(sub)
        if note.body.strip():
            description = QLabel(note.body)
            description.setWordWrap(True)
            col.addWidget(description)

        if loading:
            col.addWidget(QLabel("Chargement du tracé…"))
        if error is not None:
            frame, lay = card("Fichier")
            lay.addWidget(QLabel(error))
            col.addWidget(frame)

        ski = info.sport == Sport.SKI
        panel = None
        if analysis is not None and len(analysis.samples) >= 2:
            panel = self._map()
            if ski:
                panel.show_activity(lines=data.lines, dots=data.dots, square=data.square)
            else:
                panel.show_activity(track=data.track, dots=data.dots)
            col.addWidget(panel)
            panel.show()
            if ski:
                modes = QComboBox()
                for label, key in (("Descentes + montées (en bleu)", "both"), ("Descentes seulement", "down"), ("Tout le tracé", "all")):
                    modes.addItem(label, key)
                modes.currentIndexChanged.connect(
                    lambda _i, p=panel, a=analysis, box=modes: p.set_lines(page_lines(map_lines(a, True, box.currentData()))))
                col.addWidget(modes)

        frame, lay = card("Résumé")
        items = []
        if info.distance > 0 or info.sport.has_gps:
            items.append(("Distance", act.fmt_distance(info.distance)))
        # a GPX without any <time>: its points have stand-in times, the analysis keeps no duration
        untimed = analysis is not None and len(analysis.samples) >= 2 and analysis.stats.elapsed_ms == 0
        items.append(("Durée", "inconnue (fichier sans heures)" if untimed else act.fmt_duration(info.elapsed)))
        if info.sport.has_gps:
            if not untimed:
                items.append(("En mouvement", act.fmt_duration(info.moving)))
            if info.moving > 0 and info.distance > 0:
                items.append(("Allure moyenne" if info.sport == Sport.WALK else "Vitesse moyenne",
                              act.fmt_speed_for(info.sport, info.distance / info.moving)))
            if info.vmax and not ski:
                items.append(("Allure max" if info.sport == Sport.WALK else "Vitesse max", act.fmt_speed_for(info.sport, info.vmax)))
            if (analysis is None or analysis.quality.has_altitude) and not ski:
                items.append(("Dénivelé +", f"{info.ascent} m"))
                items.append(("Dénivelé −", f"{info.descent} m"))
        elif info.distance > 0 and info.elapsed > 0:
            items.append(("Vitesse moyenne", act.fmt_speed(info.distance / info.elapsed)))
        if info.hr is not None:
            items.append(("FC moyenne", f"{info.hr} bpm"))
        if analysis is not None and analysis.stats.hr_max is not None:
            items.append(("FC max", f"{analysis.stats.hr_max} bpm"))
        lay.addWidget(stat_grid(items))
        col.addWidget(frame)

        if analysis is not None and ski:
            st = analysis.stats
            frame, lay = card("Ski")
            if st.runs == 0:
                lay.addWidget(QLabel("Aucune descente détectée."))
            else:
                lay.addWidget(stat_grid([
                    ("Descentes", str(st.runs)),
                    ("Dénivelé descendu", f"{round(st.run_drop)} m" if analysis.quality.has_altitude else "–"),
                    ("Vitesse max", act.fmt_speed(st.run_max_speed)),
                    ("Vitesse moyenne", act.fmt_speed(st.run_avg_speed)),
                    ("Plus longue", act.fmt_distance(st.run_longest)),
                    ("Montées", f"{st.climbs} · +{round(st.climb_gain)} m" if analysis.quality.has_altitude else "–"),
                ]))
                note_label = QLabel("Sur la carte : descentes du violet au rouge selon la vitesse, montées en bleu.")
                note_label.setStyleSheet(f"color: {s.on_surface_variant}; font-size: 8.5pt;")
                lay.addWidget(note_label)
            col.addWidget(frame)

        if analysis is not None:
            if data.stops:
                frame, lay = card("Pauses et arrêts")
                lay.addWidget(StopList(data.stops))
                col.addWidget(frame)
            for spec in data.charts:
                frame, lay = card()
                chart = Chart(spec.title, spec.xs, spec.ys, spec.low, spec.high, spec.color or s.primary, spec.coords, spec.prefixes)
                if panel is not None:
                    chart.hovered.connect(lambda c, p=panel: p.set_cursor(*(c if c else (None, None))))
                lay.addWidget(chart)
                col.addWidget(frame)

        frame, lay = card("Photos")
        photos = [a for a in note.attachments if a.lower().rsplit(".", 1)[-1] in {"jpg", "jpeg", "png", "gif", "webp", "bmp"}]
        if photos:
            from .attachments import AttachmentStrip
            strip = AttachmentStrip()
            strip.set_files(self.store.root, photos, s.on_surface)
            strip.open_requested.connect(self.open_file)
            strip.reveal_requested.connect(self.reveal_file)
            strip.remove_requested.connect(lambda p, n=note: self._remove_photo(n, p))
            lay.addWidget(strip)
        add = QPushButton("Ajouter une photo…")
        add.clicked.connect(lambda: self._add_photo(note))
        lay.addWidget(add)
        col.addWidget(frame)

        if info.file is None:
            distance = QPushButton("Modifier la distance…" if info.distance > 0 else "Ajouter une distance…")
            distance.clicked.connect(lambda: self._set_distance(note))
            col.addWidget(distance)

        if analysis is not None and not analysis.quality.has_altitude and info.sport.has_gps and not info.alt:
            relief = QPushButton("Compléter l'altitude avec le relief (en ligne)")
            relief.setToolTip("Envoie les positions à opentopodata.org et garde le résultat dans un petit fichier .alt")
            relief.clicked.connect(lambda: self._fetch_altitude(note))
            col.addWidget(relief)

        if analysis is not None:
            q = analysis.quality
            frame, lay = card("Qualité des données")
            text = QLabel(f"{q.points} points" + (f" ({q.dropped} écartés)" if q.dropped else "")
                          + f" · un point toutes les {round(q.median_interval_sec)} s\n"
                          + "Altitude : " + ((("baromètre corrigé avec le relief" if info.alt == "baro+relief" else "calculée d'après le relief") if info.alt else "oui") if q.has_altitude else "absente du fichier")
                          + f" · Fréquence cardiaque : {'oui' if q.has_hr else 'non'}")
            text.setStyleSheet(f"color: {s.on_surface_variant}; font-size: 9pt;")
            lay.addWidget(text)
            col.addWidget(frame)

        scroll.setWidget(body)
        outer.addWidget(scroll, 1)
        return host

    # ---- edits ----

    def _reopen(self, note: Note) -> None:
        self.changed.emit()
        fresh = next((n for n in self.store.scan_activities() if n.path == note.path), note)
        self.open_activity(fresh)

    def _edit(self, note: Note) -> None:
        dialog = EditDialog(self, note, note.activity.file is not None)
        if dialog.exec() != QDialog.DialogCode.Accepted:
            return
        title, description, sport = dialog.title.text(), dialog.description.toPlainText(), dialog.sport.currentData()

        def done(saved, error) -> None:
            if error is not None:
                QMessageBox.warning(self, "Modification impossible", str(error))
            else:
                self._reopen(saved)
        if note.activity.file and sport != note.activity.sport:
            # another sport is another analysis of the whole track: seconds for a long race
            self._run("Nouvelle analyse du tracé…", lambda progress: self.store.update_activity(note, title, description, sport), done)
            return
        try:
            saved = self.store.update_activity(note, title, description, sport)
        except Exception as error:  # noqa: BLE001
            done(None, error)
            return
        done(saved, None)

    def _delete(self, note: Note) -> None:
        answer = QMessageBox.question(self, "Supprimer cette activité ?",
                                      "Le fichier du tracé et les photos sont supprimés aussi.")
        if answer == QMessageBox.StandardButton.Yes:
            self.store.delete(note)
            self.changed.emit()
            self.detail_back.emit()

    def _add_photo(self, note: Note) -> None:
        path, _ = QFileDialog.getOpenFileName(self, "Ajouter une photo", str(Path.home()), "Images (*.jpg *.jpeg *.png *.gif *.webp *.bmp)")
        if path:
            try:
                relative = self.store.add_attachment(Path(path))
                self._reopen(self.store.save(note.copy(attachments=note.attachments + [relative], updated=_now())))
            except OSError as error:
                QMessageBox.warning(self, "Photo impossible", str(error))

    def _remove_photo(self, note: Note, path: str) -> None:
        try:
            self.store.file_path(path).unlink()
        except OSError:
            pass
        self._reopen(self.store.save(note.copy(attachments=[a for a in note.attachments if a != path], updated=_now())))

    def _set_distance(self, note: Note) -> None:
        from PySide6.QtWidgets import QInputDialog
        km, ok = QInputDialog.getDouble(self, "Distance", "Distance (km)", note.activity.distance / 1000, 0, 100000, 2)
        if ok:
            info = act.ActivityInfo(**{**note.activity.__dict__, "distance": km * 1000})
            self._reopen(self.store.save(note.copy(activity=info, updated=_now())))


def _now() -> int:
    from ..core.store import now_ms
    return now_ms()
