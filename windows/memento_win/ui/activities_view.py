"""The "Activités" folder: figures and the list, then one activity with its map, figures and charts."""
from __future__ import annotations

from datetime import datetime
from pathlib import Path

from PySide6.QtCore import QPointF, Qt, Signal
from PySide6.QtGui import QColor, QLinearGradient, QPainter, QPainterPath, QPen
from PySide6.QtWidgets import (QApplication, QCheckBox, QComboBox, QDialog, QDialogButtonBox, QFileDialog, QFormLayout, QFrame, QGridLayout, QHBoxLayout,
                               QLabel, QLineEdit, QMessageBox, QPlainTextEdit, QPushButton, QScrollArea, QStackedWidget,
                               QToolButton, QVBoxLayout, QWidget)

from ..core import activity as act
from ..core import mapdata
from ..core.analysis import Analysis, analyze
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

    def mouseMoveEvent(self, event) -> None:
        xs, coords = self.chart.xs, self.chart.coords
        if not coords or len(xs) < 2 or self.width() <= 0:
            return
        x = xs[0] + max(0.0, min(1.0, event.position().x() / self.width())) * (xs[-1] - xs[0])
        i = min(range(len(xs)), key=lambda k: abs(xs[k] - x))
        self.chart.hovered.emit(coords[i])

    def leaveEvent(self, event) -> None:
        if self.chart.coords:
            self.chart.hovered.emit(None)

    def paintEvent(self, event) -> None:
        xs, ys = self.chart.xs, self.chart.ys
        if len(xs) < 2:
            return
        p = QPainter(self)
        p.setRenderHint(QPainter.RenderHint.Antialiasing)
        lo, hi = min(ys), max(ys)
        span_y, min_x = max(hi - lo, 1e-6), xs[0]
        span_x = max(xs[-1] - min_x, 1e-6)
        w, h = self.width(), self.height()
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


class ActivityRow(QFrame):
    clicked = Signal(object)
    toggled = Signal(object, bool)

    def __init__(self, note: Note, selecting: bool = False, checked: bool = False):
        super().__init__()
        self.note = note
        self.selecting = selecting
        s = theme.scheme()
        self.setObjectName("row")
        self.setCursor(Qt.CursorShape.PointingHandCursor)
        self.setStyleSheet(f"#row {{ background: {s.surface_container}; border-radius: 14px; }} "
                           f"#row:hover {{ background: {s.surface_high}; }} #row QLabel {{ background: transparent; }}")
        row = QHBoxLayout(self)
        row.setContentsMargins(14, 10, 14, 10)
        if selecting:
            box = QCheckBox()
            box.setChecked(checked)
            box.toggled.connect(lambda on: self.toggled.emit(self.note, on))
            row.addWidget(box)
        emoji = QLabel(EMOJI.get(note.activity.sport, "📍"))
        emoji.setStyleSheet("font-size: 20pt;")
        row.addWidget(emoji)
        col = QVBoxLayout()
        col.setSpacing(0)
        title = QLabel(note.title)
        title.setStyleSheet("font-weight: 600; font-size: 11pt;")
        when = QLabel(fmt_date(note.activity.start))
        when.setStyleSheet(f"color: {s.on_surface_variant}; font-size: 9pt;")
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
        self.stack = QStackedWidget()
        outer = QVBoxLayout(self)
        outer.setContentsMargins(0, 0, 0, 0)
        outer.addWidget(self.stack)
        self.list_page = QWidget()
        self.detail_page = QWidget()
        self.stack.addWidget(self.list_page)
        self.stack.addWidget(self.detail_page)
        self._build_list()
        self.detail_layout = QVBoxLayout(self.detail_page)
        self.detail_layout.setContentsMargins(0, 0, 0, 0)

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
        self.status = QLabel()
        head.addWidget(self.back)
        head.addWidget(title)
        head.addStretch()
        head.addWidget(self.select_button)
        head.addWidget(self.relief_button)
        head.addWidget(self.import_button)
        layout.addLayout(head)
        layout.addWidget(self.status)
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
        QApplication.setOverrideCursor(Qt.CursorShape.WaitCursor)
        try:
            def progress(text: str) -> None:
                self.status.setText(text)
                QApplication.processEvents()
            done, skipped, failure = self.store.fill_altitudes(targets, progress)
        finally:
            QApplication.restoreOverrideCursor()
        self.status.setText("")
        self.selected.clear()
        self.select_button.setChecked(False)
        QMessageBox.information(self, "Altitude", f"{done} complétée(s), {skipped} ignorée(s)." + (f"\nArrêté : {failure}" if failure else ""))
        self.changed.emit()

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
        while self.rows.count():
            item = self.rows.takeAt(0)
            if item.widget():
                item.widget().deleteLater()
        shown = [n for n in self.activities if self.sport_filter is None or n.activity.sport == self.sport_filter]
        self.row_widgets = {}
        if not shown:
            hint = QLabel("Aucune activité. Importe un fichier FIT ou GPX (montre, Strava).")
            hint.setStyleSheet(f"color: {s.on_surface_variant};")
            self.rows.addWidget(hint)
        for n in shown:
            row = ActivityRow(n, self.selecting, n.path in self.selected)
            row.clicked.connect(self._row_clicked)
            row.toggled.connect(self._toggle)
            self.rows.addWidget(row)
            self.row_widgets[n.id] = row

    # ---- import ----

    def import_files(self) -> None:
        if self.store is None:
            return
        paths, _ = QFileDialog.getOpenFileNames(self, "Importer des activités", str(Path.home()),
                                                "Activités (*.fit *.gpx);;Tous les fichiers (*)")
        last = None
        for path in paths:
            try:
                from ..core import elevation
                from ..core.store import default_title
                source = Path(path)
                track, _ext = self.store.read_source(source)
                existing = self.store.find_activity(track.start_time)
                sport = Sport.guess(track.fit_sport)
                if existing is not None and sport == Sport.GPS and existing.activity:
                    sport = existing.activity.sport
                title = existing.title if existing else default_title(sport, track.start_time or 0, track.name)
                dialog = ImportDialog(self, source.name, title, sport, existing, elevation.missing(track))
                if dialog.exec() != QDialog.DialogCode.Accepted:
                    continue
                last = self.store.import_activity(source, dialog.title.text(), dialog.sport.currentData(), existing)
                if dialog.relief.isVisible() and dialog.relief.isChecked():
                    last = self._fetch_altitude(last, quiet=False)
            except Exception as error:  # noqa: BLE001 — the message goes to the user
                QMessageBox.warning(self, "Import impossible", f"{Path(path).name} : {error}")
        if last is not None:
            self.changed.emit()
            self.open_activity(last)

    def _fetch_altitude(self, note: Note, quiet: bool = True) -> Note:
        """Looks the altitude up (a few seconds: the service allows one request per second)."""
        QApplication.setOverrideCursor(Qt.CursorShape.WaitCursor)
        try:
            return self.store.fetch_altitude(note)
        except Exception as error:  # noqa: BLE001
            QApplication.restoreOverrideCursor()
            QMessageBox.warning(self, "Altitude indisponible", f"{error}\nL'activité est importée sans altitude.")
            return note
        finally:
            while QApplication.overrideCursor() is not None:
                QApplication.restoreOverrideCursor()

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
        self._park_map()
        while self.detail_layout.count():
            item = self.detail_layout.takeAt(0)
            if item.widget():
                item.widget().deleteLater()
        self.detail_layout.addWidget(self._detail(note))
        self.stack.setCurrentWidget(self.detail_page)

    def _detail(self, note: Note) -> QWidget:
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

        analysis = None
        try:
            if info.file:
                analysis = analyze(self.store.read_track(info), info.sport)
        except Exception as error:  # noqa: BLE001
            frame, lay = card("Fichier")
            lay.addWidget(QLabel(f"{error}"))
            col.addWidget(frame)

        ski = info.sport == Sport.SKI
        panel = None
        if analysis is not None and len(analysis.samples) >= 2:
            samples = analysis.samples
            panel = self._map()
            dots = [{"lon": samples[0].lon, "lat": samples[0].lat, "color": "#00C853"},
                    {"lon": samples[-1].lon, "lat": samples[-1].lat, "color": "#FF1744"}]
            if ski:
                # the square is the whole day's, whichever lines the selector shows
                lats, lons = [p.lat for p in samples], [p.lon for p in samples]
                square = list(mapdata.square_of(min(lons), min(lats), max(lons), max(lats)))
                panel.show_activity(lines=page_lines(map_lines(analysis, True)), dots=dots, square=square)
            else:
                panel.show_activity(track=page_track(analysis), dots=dots)
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
        items.append(("Durée", act.fmt_duration(info.elapsed)))
        if info.sport.has_gps:
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
            stops = sorted(analysis.pauses + analysis.stops, key=lambda x: x.start)
            if stops:
                frame, lay = card("Pauses et arrêts")
                for stop in stops:
                    row = QHBoxLayout()
                    row.addWidget(QLabel("Pause" if stop.manual else "Arrêt détecté"))
                    row.addStretch()
                    right = QLabel(f"{act.fmt_duration(stop.duration_ms // 1000)} · {fmt_hour(stop.start)}")
                    right.setStyleSheet(f"color: {s.on_surface_variant};")
                    row.addWidget(right)
                    lay.addLayout(row)
                col.addWidget(frame)
            idx = thin(len(analysis.samples))
            samples = analysis.samples

            def linked(chart: Chart) -> Chart:
                if panel is not None:
                    chart.hovered.connect(lambda c, p=panel: p.set_cursor(*(c if c else (None, None))))
                return chart
            at = lambda ids: [(samples[i].lon, samples[i].lat) for i in ids]  # noqa: E731
            if analysis.quality.has_altitude and len(idx) >= 2 and all(samples[i].alt is not None for i in idx):
                frame, lay = card()
                lay.addWidget(linked(Chart("Altitude", [samples[i].dist for i in idx], [samples[i].alt for i in idx],
                                           f"{round(analysis.stats.min_alt or 0)} m", f"{round(analysis.stats.max_alt or 0)} m", s.primary, at(idx))))
                col.addWidget(frame)
            if len(idx) >= 2 and analysis.stats.max_speed > 0:
                frame, lay = card()
                xs = [samples[i].dist for i in idx]
                if info.sport == Sport.WALK:
                    # pace drawn upside down (minus seconds per km): faster stays higher, like a speed curve
                    pace = [-act.chart_pace(samples[i].speed) for i in idx]
                    lay.addWidget(linked(Chart("Allure", xs, pace, act.fmt_pace(1000 / -min(pace)),
                                               act.fmt_pace(1000 / -max(pace)), "#2E7DF6", at(idx),
                                               prefixes=("plus lente", "plus rapide"))))
                else:
                    lay.addWidget(linked(Chart("Vitesse", xs, [samples[i].speed * 3.6 for i in idx], "0 km/h",
                                               act.fmt_speed(analysis.stats.max_speed), "#2E7DF6", at(idx))))
                col.addWidget(frame)
            hr_idx = [i for i in idx if samples[i].hr is not None]
            if analysis.quality.has_hr and len(hr_idx) >= 2:
                frame, lay = card()
                lay.addWidget(linked(Chart("Fréquence cardiaque", [samples[i].dist for i in hr_idx], [float(samples[i].hr) for i in hr_idx],
                                           f"{min(x.hr for x in samples if x.hr is not None)} bpm", f"{analysis.stats.hr_max or 0} bpm", "#E5484D",
                                           at(hr_idx))))
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
            relief.clicked.connect(lambda: self._reopen(self._fetch_altitude(note)))
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
        if dialog.exec() == QDialog.DialogCode.Accepted:
            try:
                saved = self.store.update_activity(note, dialog.title.text(), dialog.description.toPlainText(), dialog.sport.currentData())
                self._reopen(saved)
            except Exception as error:  # noqa: BLE001
                QMessageBox.warning(self, "Modification impossible", str(error))

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
