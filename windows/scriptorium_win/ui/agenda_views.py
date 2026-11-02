"""The four agenda views, painted rather than built from child widgets (a month of entries is a few hundred
rectangles: one paint pass, no widget churn when the data changes).

- `ScheduleView` (Agenda): days grouped with their entries, endless downward (the screen adds days on demand).
- `MonthView` (Mois): 6 weeks, at most three lanes per day and "+N"; multi-day entries span their days.
- `TimeGridView` (Semaine / Jour): an all-day strip and a 24 h grid, overlapping entries side by side, the
  current-time line, double-click on an empty slot to create.
Positions follow the wall clock of the device zone, so a 23 h or 25 h day (daylight saving) keeps its hours.
"""
from __future__ import annotations

from dataclasses import dataclass
from functools import lru_cache
from datetime import date, datetime, timedelta

from PySide6.QtCore import QAbstractListModel, QLocale, QModelIndex, QPoint, QPointF, QRectF, QSize, Qt, QTimer, Signal
from PySide6.QtGui import QColor, QFont, QFontMetrics, QMouseEvent, QPainter, QPainterPath, QPen
from PySide6.QtWidgets import (QAbstractItemView, QFrame, QListView, QScrollArea, QStyle, QStyledItemDelegate,
                               QVBoxLayout, QWidget)

from ..core.agenda.entries import Entry, EntryKind
from ..core.agenda.frdates import MONTHS_SHORT, WEEKDAYS_SHORT, clock, day_span, long_date, month_title, short_date
from . import icons, theme

LAYER_ICONS = {EntryKind.ACTIVITY: "run", EntryKind.NOTE: "edit_note", EntryKind.BIRTHDAY: "cake"}
MINUTES = 1440


@lru_cache(maxsize=1)
def uses_24h() -> bool:
    return "a" not in QLocale.system().timeFormat(QLocale.FormatType.ShortFormat).lower()


def ink_on(color: str) -> QColor:
    """Text colour readable on a bar of `color` (the palette is checked for white text; others are guessed)."""
    c = QColor(color)
    luminance = 0.2126 * c.redF() + 0.7152 * c.greenF() + 0.0722 * c.blueF()
    return QColor("#1B1B1B") if luminance > 0.6 else QColor("#FFFFFF")


def local(entry: Entry, tz) -> tuple[datetime, datetime]:
    return entry.start.astimezone(tz).replace(tzinfo=None), entry.end.astimezone(tz).replace(tzinfo=None)


def wall(entry: Entry, tz, event_zone: bool) -> tuple[datetime, datetime]:
    """Start and end as shown: the device's wall clock, or the event's own with "heures du fuseau de l'événement"."""
    o = entry.occurrence
    if event_zone and o is not None and not entry.all_day and isinstance(o.start, datetime):
        end = o.end if isinstance(o.end, datetime) else o.start
        return o.start, end
    return local(entry, tz)


def time_text(entry: Entry, day: date, tz, event_zone: bool = False, today: date | None = None) -> str:
    """The time line of an entry on `day`: "14:30 – 15:15", "Toute la journée", "Du 1 au 3 déc."…"""
    h24 = uses_24h()
    if entry.all_day:
        return "Toute la journée" if entry.start_date == entry.end_date else day_span(entry.start_date, entry.end_date, today)
    start, end = wall(entry, tz, event_zone)
    if entry.end == entry.start:
        return clock(start, h24)
    if entry.start_date == entry.end_date:
        return f"{clock(start, h24)} – {clock(end, h24)}"
    if day == entry.start_date:
        return f"{clock(start, h24)} → {short_date(entry.end_date, today)} {clock(end, h24)}"
    if day == entry.end_date:
        return f"→ {clock(end, h24)}"
    return "Toute la journée"


def _font(base: QFont, size: float, weight: QFont.Weight = QFont.Weight.Normal) -> QFont:
    f = QFont(base)
    f.setPointSizeF(size)
    f.setWeight(weight)
    return f


def _elide(painter: QPainter, text: str, width: float) -> str:
    return QFontMetrics(painter.font()).elidedText(text, Qt.TextElideMode.ElideRight, max(0, int(width)))


def _icon(painter: QPainter, name: str, color: QColor | str, rect: QRectF) -> None:
    painter.drawPixmap(rect.toRect(), icons.icon(name, color, int(rect.width())).pixmap(int(rect.width()), int(rect.height())))


# ---- lanes (month weeks and the all-day strip) ----

@dataclass
class Segment:
    entry: Entry
    c0: int   # first column (day index) covered
    c1: int   # last column covered
    lane: int = 0

    @property
    def spans(self) -> bool:
        return self.entry.all_day or self.c1 > self.c0 or self.entry.start_date != self.entry.end_date


def lay_lanes(entries: list[Entry], first: date, columns: int) -> tuple[list[Segment], list[int]]:
    """Segments of the entries over `columns` days from `first`, each in the first free lane; and the number
    of entries per column. Multi-day entries first (longest first), then by time."""
    last = first + timedelta(days=columns - 1)
    segs = [Segment(e, (max(e.start_date, first) - first).days, (min(e.end_date, last) - first).days)
            for e in entries if e.start_date <= last and e.end_date >= first]
    segs.sort(key=lambda s: (s.c0, 0 if s.spans else 1, -(s.c1 - s.c0), s.entry.start, s.entry.title))
    occupied: list[set[int]] = []
    counts = [0] * columns
    for s in segs:
        cols = set(range(s.c0, s.c1 + 1))
        lane = next((i for i, used in enumerate(occupied) if not used & cols), len(occupied))
        if lane == len(occupied):
            occupied.append(set())
        occupied[lane] |= cols
        s.lane = lane
        for c in cols:
            counts[c] += 1
    return segs, counts


def visible_runs(seg: Segment, limits: list[int]) -> list[tuple[int, int]]:
    """Runs of consecutive columns where the segment's lane is shown (its lane is under the column's limit)."""
    runs, start = [], None
    for c in range(seg.c0, seg.c1 + 2):
        shown = c <= seg.c1 and seg.lane < limits[c]
        if shown and start is None:
            start = c
        elif not shown and start is not None:
            runs.append((start, c - 1))
            start = None
    return runs


class _Hits:
    """Clickable rectangles of the last paint, topmost last."""

    def __init__(self):
        self.items: list[tuple[QRectF, str, object]] = []

    def clear(self) -> None:
        self.items.clear()

    def add(self, rect: QRectF, kind: str, payload) -> None:
        self.items.append((QRectF(rect), kind, payload))

    def at(self, point: QPointF) -> tuple[str, object] | None:
        for rect, kind, payload in reversed(self.items):
            if rect.contains(point):
                return kind, payload
        return None


def paint_bar(painter: QPainter, rect: QRectF, entry: Entry, label: str, filled: bool, s: theme.Scheme,
              cont_left: bool = False, cont_right: bool = False) -> None:
    """One lane item: a filled bar (all-day, multi-day) or a dot and its text (timed)."""
    color = QColor(entry.color)
    painter.save()
    if filled:
        path = QPainterPath()
        path.addRoundedRect(rect, 4, 4)
        painter.fillPath(path, color)
        ink = ink_on(entry.color)
        x = rect.left() + (2 if cont_left else 6)
        name = LAYER_ICONS.get(entry.kind)
        if name:
            _icon(painter, name, ink, QRectF(x, rect.center().y() - 6, 12, 12))
            x += 15
        painter.setPen(ink)
        painter.drawText(QRectF(x, rect.top(), rect.right() - x - 4, rect.height()),
                         Qt.AlignmentFlag.AlignVCenter | Qt.AlignmentFlag.AlignLeft,
                         _elide(painter, label, rect.right() - x - 4))
    else:
        painter.setRenderHint(QPainter.RenderHint.Antialiasing)
        x = rect.left() + 4
        name = LAYER_ICONS.get(entry.kind)
        if name:
            _icon(painter, name, color, QRectF(x - 1, rect.center().y() - 6, 12, 12))
            x += 14
        else:
            painter.setBrush(color)
            painter.setPen(Qt.PenStyle.NoPen)
            painter.drawEllipse(QPointF(x + 3, rect.center().y()), 3.5, 3.5)
            x += 11
        painter.setPen(QColor(s.on_surface))
        painter.drawText(QRectF(x, rect.top(), rect.right() - x - 2, rect.height()),
                         Qt.AlignmentFlag.AlignVCenter | Qt.AlignmentFlag.AlignLeft,
                         _elide(painter, label, rect.right() - x - 2))
    painter.restore()


# ---- Mois ----

class MonthView(QWidget):
    entry_clicked = Signal(object)          # Entry
    day_clicked = Signal(object, QPoint)    # date, global position (the day's list pops up there)
    day_double_clicked = Signal(object)     # date: new event that day
    MAX_LANES = 3

    def __init__(self):
        super().__init__()
        self.setMouseTracking(True)
        self.setMinimumSize(420, 360)
        self.setFocusPolicy(Qt.FocusPolicy.StrongFocus)
        self.month = date.today().replace(day=1)
        self.grid_start = self.month
        self.week_start = 0
        self.today = date.today()
        self.tz = None
        self.entries: list[Entry] = []
        self.hits = _Hits()
        self._hover: object | None = None

    def set_month(self, month: date, week_start: int, entries: list[Entry], today: date, tz) -> None:
        self.month, self.week_start, self.entries, self.today, self.tz = month.replace(day=1), week_start, entries, today, tz
        self.grid_start = grid_start(self.month, week_start)
        self.update()

    def _geometry(self) -> tuple[float, float, float]:
        header = 28.0
        return header, self.width() / 7, (self.height() - header) / 6

    def day_at(self, point: QPointF) -> date | None:
        header, cw, ch = self._geometry()
        if point.y() < header:
            return None
        col, row = min(6, int(point.x() // cw)), min(5, int((point.y() - header) // ch))
        return self.grid_start + timedelta(days=row * 7 + col)

    def paintEvent(self, event) -> None:
        s = theme.scheme()
        p = QPainter(self)
        p.setRenderHint(QPainter.RenderHint.TextAntialiasing)
        p.fillRect(self.rect(), QColor(s.background))
        header, cw, ch = self._geometry()
        base = self.font()
        self.hits.clear()

        p.setFont(_font(base, 8.5, QFont.Weight.DemiBold))
        p.setPen(QColor(s.on_surface_variant))
        for c in range(7):
            name = WEEKDAYS_SHORT[(c + self.week_start) % 7].rstrip(".").upper()
            p.drawText(QRectF(c * cw, 0, cw, header), Qt.AlignmentFlag.AlignCenter, name)

        grid_pen = QPen(QColor(s.outline_variant))
        grid_pen.setWidthF(1)
        lanes_cap = max(1, min(self.MAX_LANES, int((ch - 30) // 20)))
        for r in range(6):
            week = self.grid_start + timedelta(days=r * 7)
            top = header + r * ch
            for c in range(7):
                d = week + timedelta(days=c)
                cell = QRectF(c * cw, top, cw, ch)
                if d.month != self.month.month:
                    p.fillRect(cell, QColor(s.surface_container))
                p.setPen(grid_pen)
                p.drawLine(QPointF(cell.left(), cell.top()), QPointF(cell.right(), cell.top()))
                if c:
                    p.drawLine(QPointF(cell.left(), cell.top()), QPointF(cell.left(), cell.bottom()))
                self._day_number(p, d, cell, s, base)
                self.hits.add(cell, "day", d)
            segs, counts = lay_lanes(self.entries, week, 7)
            limits = [lanes_cap if n <= lanes_cap else lanes_cap - 1 for n in counts]
            p.setFont(_font(base, 8.5))
            for seg in segs:
                for c0, c1 in visible_runs(seg, limits):
                    rect = QRectF(c0 * cw + 3, top + 28 + seg.lane * 20, (c1 - c0 + 1) * cw - 6, 18)
                    label = self._label(seg.entry)
                    paint_bar(p, rect, seg.entry, label, seg.spans, s, c0 > seg.c0, c1 < seg.c1)
                    if self._hover is seg.entry:
                        p.setPen(QPen(QColor(s.on_surface), 1))
                        p.setBrush(Qt.BrushStyle.NoBrush)
                        p.drawRoundedRect(rect.adjusted(0.5, 0.5, -0.5, -0.5), 4, 4)
                    self.hits.add(rect, "entry", seg.entry)
            p.setFont(_font(base, 8.5, QFont.Weight.DemiBold))
            for c, n in enumerate(counts):
                if n > lanes_cap:
                    rect = QRectF(c * cw + 3, top + 28 + (lanes_cap - 1) * 20, cw - 6, 18)
                    p.setPen(QColor(s.on_surface_variant))
                    p.drawText(rect.adjusted(6, 0, 0, 0), Qt.AlignmentFlag.AlignVCenter | Qt.AlignmentFlag.AlignLeft,
                               f"+{n - (lanes_cap - 1)}")
                    self.hits.add(rect, "more", week + timedelta(days=c))
        p.setPen(grid_pen)
        p.drawLine(QPointF(0, header), QPointF(self.width(), header))
        p.end()

    def _label(self, e: Entry) -> str:
        if e.all_day or e.start_date != e.end_date or self.tz is None:
            return e.title or "(Sans titre)"
        start = e.start.astimezone(self.tz)
        return f"{clock(start, uses_24h())} {e.title or '(Sans titre)'}"

    def _day_number(self, p: QPainter, d: date, cell: QRectF, s: theme.Scheme, base: QFont) -> None:
        text = f"1 {MONTHS_SHORT[d.month - 1]}" if d.day == 1 else str(d.day)
        p.setFont(_font(base, 9, QFont.Weight.DemiBold if d == self.today else QFont.Weight.Normal))
        fm = QFontMetrics(p.font())
        width = max(22, fm.horizontalAdvance(text) + 12)
        badge = QRectF(cell.center().x() - width / 2, cell.top() + 4, width, 22)
        if d == self.today:
            p.setRenderHint(QPainter.RenderHint.Antialiasing)
            path = QPainterPath()
            path.addRoundedRect(badge, 11, 11)
            p.fillPath(path, QColor(s.primary))
            p.setPen(QColor(s.on_primary))
        else:
            p.setPen(QColor(s.on_surface if d.month == self.month.month else s.on_surface_variant))
        p.drawText(badge, Qt.AlignmentFlag.AlignCenter, text)

    def mouseMoveEvent(self, event: QMouseEvent) -> None:
        hit = self.hits.at(event.position())
        hover = hit[1] if hit and hit[0] == "entry" else None
        self.setCursor(Qt.CursorShape.PointingHandCursor if hit and hit[0] in ("entry", "more") else Qt.CursorShape.ArrowCursor)
        if hover is not self._hover:
            self._hover = hover
            self.setToolTip(_tooltip(hover, self.tz) if hover else "")
            self.update()

    def leaveEvent(self, event) -> None:
        if self._hover is not None:
            self._hover = None
            self.update()

    def mouseReleaseEvent(self, event: QMouseEvent) -> None:
        if event.button() != Qt.MouseButton.LeftButton:
            return
        hit = self.hits.at(event.position())
        if hit is None:
            return
        kind, payload = hit
        if kind == "entry":
            self.entry_clicked.emit(payload)
        else:
            self.day_clicked.emit(payload, event.globalPosition().toPoint())

    def mouseDoubleClickEvent(self, event: QMouseEvent) -> None:
        hit = self.hits.at(event.position())
        if hit and hit[0] == "day":
            self.day_double_clicked.emit(hit[1])


def grid_start(month: date, week_start: int) -> date:
    first = month.replace(day=1)
    return first - timedelta(days=(first.weekday() - week_start) % 7)


def week_start_of(d: date, week_start: int) -> date:
    return d - timedelta(days=(d.weekday() - week_start) % 7)


def _alpha(color: str, a: float) -> QColor:
    c = QColor(color)
    c.setAlphaF(a)
    return c


def _tooltip(e: Entry, tz) -> str:
    if tz is None:
        return e.title
    text = time_text(e, e.start_date, tz)
    return "\n".join(x for x in (e.title or "(Sans titre)", text, e.subtitle or "") if x)


# ---- Semaine / Jour ----

@dataclass
class Block:
    entry: Entry
    col: int
    start: int       # minutes from the day's 00:00 (wall)
    end: int
    lane: int = 0
    lanes: int = 1


class TimeGridView(QWidget):
    entry_clicked = Signal(object)        # Entry
    slot_double_clicked = Signal(object)  # naive datetime (device wall), new event there
    day_clicked = Signal(object)          # date: a header day was clicked (opens the day view)
    more_clicked = Signal(object, QPoint)  # date with hidden all-day entries
    GUTTER = 56
    HOUR = 48

    def __init__(self):
        super().__init__()
        self.days: list[date] = [date.today()]
        self.today = date.today()
        self.tz = None
        self.now_fn = None
        self.blocks: list[Block] = []
        self.allday: list[Segment] = []
        self.allday_counts: list[int] = [0]
        self._scrolled_for: tuple | None = None
        layout = QVBoxLayout(self)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(0)
        self.header = _GridHeader(self)
        self.scroll = QScrollArea()
        self.scroll.setFrameShape(QFrame.Shape.NoFrame)
        self.scroll.setWidgetResizable(True)
        self.scroll.setVerticalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOn)
        self.scroll.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
        self.canvas = _HourCanvas(self)
        self.scroll.setWidget(self.canvas)
        layout.addWidget(self.header)
        layout.addWidget(self.scroll, 1)
        self.clock_timer = QTimer(self, interval=60_000)
        self.clock_timer.timeout.connect(self.canvas.update)
        self.clock_timer.start()
        self.setFocusPolicy(Qt.FocusPolicy.StrongFocus)

    def set_days(self, days: list[date], entries: list[Entry], today: date, tz, now_fn) -> None:
        self.days, self.today, self.tz, self.now_fn = days, today, tz, now_fn
        first, last = days[0], days[-1]
        strip, timed = [], []
        for e in entries:
            if e.end_date < first or e.start_date > last:
                continue
            long = e.all_day or (e.end - e.start) >= timedelta(hours=24)
            (strip if long else timed).append(e)
        self.allday, self.allday_counts = lay_lanes(strip, first, len(days))
        self.blocks = self._blocks(timed)
        self.header.relayout()
        self.header.update()
        self.canvas.update()
        key = (first, last)
        if self._scrolled_for != key:
            self._scrolled_for = key
            QTimer.singleShot(0, self.scroll_to_now)

    def _blocks(self, timed: list[Entry]) -> list[Block]:
        out: list[Block] = []
        min_len = 22 * 60 // self.HOUR
        for col, d in enumerate(self.days):
            day_blocks = []
            for e in timed:
                if not (e.start_date <= d <= e.end_date):
                    continue
                s, en = local(e, self.tz)
                start = s.hour * 60 + s.minute if s.date() == d else 0
                end = en.hour * 60 + en.minute if en.date() == d else MINUTES
                day_blocks.append(Block(e, col, start, max(end, start)))
            day_blocks.sort(key=lambda b: (b.start, -b.end, b.entry.title))
            cluster: list[Block] = []
            lane_ends: list[int] = []
            cluster_end = -1
            for b in day_blocks:
                shown_end = max(b.end, b.start + min_len)
                if cluster and b.start >= cluster_end:
                    for c in cluster:
                        c.lanes = len(lane_ends)
                    cluster, lane_ends = [], []
                lane = next((i for i, end in enumerate(lane_ends) if end <= b.start), len(lane_ends))
                if lane == len(lane_ends):
                    lane_ends.append(shown_end)
                else:
                    lane_ends[lane] = shown_end
                b.lane = lane
                cluster.append(b)
                cluster_end = max(cluster_end, shown_end) if len(cluster) > 1 else shown_end
            for c in cluster:
                c.lanes = len(lane_ends)
            out += day_blocks
        return out

    def column_width(self) -> float:
        return (self.canvas.width() - self.GUTTER) / max(1, len(self.days))

    def scroll_to_now(self) -> None:
        bar = self.scroll.verticalScrollBar()
        if self.today in self.days and self.now_fn is not None:
            now = self.now_fn()
            minutes = now.hour * 60 + now.minute
        else:
            minutes = 7 * 60 + 30
        bar.setValue(int(minutes / 60 * self.HOUR - self.scroll.viewport().height() / 3))

    def resizeEvent(self, event) -> None:
        super().resizeEvent(event)
        self.header.relayout()


class _GridHeader(QWidget):
    DAY_H = 52
    LANE = 20
    MAX_LANES = 3

    def __init__(self, grid: TimeGridView):
        super().__init__()
        self.grid = grid
        self.hits = _Hits()
        self.setMouseTracking(True)
        self.setFixedHeight(self.DAY_H + 8)

    def lanes_shown(self) -> int:
        lanes = max((s.lane + 1 for s in self.grid.allday), default=0)
        return min(lanes, self.MAX_LANES)

    def relayout(self) -> None:
        lanes = self.lanes_shown()
        self.setFixedHeight(self.DAY_H + (lanes * self.LANE + 6 if lanes else 8))

    def _right_pad(self) -> int:
        bar = self.grid.scroll.verticalScrollBar()
        return bar.width() if bar.isVisible() else 0

    def paintEvent(self, event) -> None:
        s = theme.scheme()
        g = self.grid
        p = QPainter(self)
        p.setRenderHint(QPainter.RenderHint.Antialiasing)
        p.fillRect(self.rect(), QColor(s.background))
        base = self.font()
        width = self.width() - g.GUTTER - self._right_pad()
        n = len(g.days)
        cw = width / max(1, n)
        self.hits.clear()
        for i, d in enumerate(g.days):
            x = g.GUTTER + i * cw
            is_today = d == g.today
            p.setFont(_font(base, 8.5, QFont.Weight.DemiBold))
            p.setPen(QColor(s.primary if is_today else s.on_surface_variant))
            name = WEEKDAYS_SHORT[d.weekday()].rstrip(".").upper()
            p.drawText(QRectF(x, 4, cw, 16), Qt.AlignmentFlag.AlignCenter, name)
            p.setFont(_font(base, 13 if n > 1 else 14, QFont.Weight.DemiBold if is_today else QFont.Weight.Normal))
            number = QRectF(x + cw / 2 - 16, 21, 32, 28)
            if is_today:
                path = QPainterPath()
                path.addEllipse(number.center(), 15, 15)
                p.fillPath(path, QColor(s.primary))
                p.setPen(QColor(s.on_primary))
            else:
                p.setPen(QColor(s.on_surface))
            p.drawText(number, Qt.AlignmentFlag.AlignCenter, str(d.day))
            self.hits.add(QRectF(x, 0, cw, self.DAY_H), "day", d)
        lanes = self.lanes_shown()
        top = self.DAY_H
        limits = [lanes if c <= lanes else max(0, lanes - 1) for c in g.allday_counts]
        p.setFont(_font(base, 8.5))
        for seg in g.allday:
            for c0, c1 in visible_runs(seg, limits):
                rect = QRectF(g.GUTTER + c0 * cw + 2, top + seg.lane * self.LANE, (c1 - c0 + 1) * cw - 4, self.LANE - 2)
                paint_bar(p, rect, seg.entry, seg.entry.title or "(Sans titre)", True, s, c0 > seg.c0, c1 < seg.c1)
                self.hits.add(rect, "entry", seg.entry)
        p.setFont(_font(base, 8.5, QFont.Weight.DemiBold))
        for c, count in enumerate(g.allday_counts):
            if count > lanes and lanes:
                rect = QRectF(g.GUTTER + c * cw + 2, top + (lanes - 1) * self.LANE, cw - 4, self.LANE - 2)
                p.setPen(QColor(s.on_surface_variant))
                p.drawText(rect.adjusted(6, 0, 0, 0), Qt.AlignmentFlag.AlignVCenter, f"+{count - lanes + 1}")
                self.hits.add(rect, "more", g.days[c])
        p.setPen(QPen(QColor(s.outline_variant), 1))
        p.drawLine(QPointF(0, self.height() - 0.5), QPointF(self.width(), self.height() - 0.5))
        if lanes:
            p.setFont(_font(base, 7.5))
            p.setPen(QColor(s.on_surface_variant))
            p.drawText(QRectF(0, top, g.GUTTER - 6, self.LANE), Qt.AlignmentFlag.AlignRight | Qt.AlignmentFlag.AlignVCenter,
                       "Journée")
        p.end()

    def mouseMoveEvent(self, event: QMouseEvent) -> None:
        hit = self.hits.at(event.position())
        self.setCursor(Qt.CursorShape.PointingHandCursor if hit else Qt.CursorShape.ArrowCursor)
        self.setToolTip(_tooltip(hit[1], self.grid.tz) if hit and hit[0] == "entry" else "")

    def mouseReleaseEvent(self, event: QMouseEvent) -> None:
        hit = self.hits.at(event.position())
        if hit is None or event.button() != Qt.MouseButton.LeftButton:
            return
        kind, payload = hit
        if kind == "entry":
            self.grid.entry_clicked.emit(payload)
        elif kind == "more":
            self.grid.more_clicked.emit(payload, event.globalPosition().toPoint())
        elif len(self.grid.days) > 1:
            self.grid.day_clicked.emit(payload)


class _HourCanvas(QWidget):
    def __init__(self, grid: TimeGridView):
        super().__init__()
        self.grid = grid
        self.hits = _Hits()
        self.setMouseTracking(True)
        self.setMinimumHeight(24 * grid.HOUR + 1)
        self._hover = None

    def sizeHint(self) -> QSize:
        return QSize(600, 24 * self.grid.HOUR + 1)

    def paintEvent(self, event) -> None:
        s = theme.scheme()
        g = self.grid
        p = QPainter(self)
        p.fillRect(self.rect(), QColor(s.background))
        base = self.font()
        cw = g.column_width()
        hour = g.HOUR
        self.hits.clear()
        if g.today in g.days and len(g.days) > 1:
            i = g.days.index(g.today)
            p.fillRect(QRectF(g.GUTTER + i * cw, 0, cw, self.height()), _alpha(s.primary, 0.05))
        p.setFont(_font(base, 8))
        line = QPen(QColor(s.outline_variant), 1)
        half = QPen(_alpha(s.outline_variant, 0.45), 1, Qt.PenStyle.DashLine)
        h24 = uses_24h()
        for h in range(24):
            y = h * hour + 0.5
            p.setPen(line)
            p.drawLine(QPointF(g.GUTTER - 6, y), QPointF(self.width(), y))
            p.setPen(half)
            p.drawLine(QPointF(g.GUTTER, y + hour / 2), QPointF(self.width(), y + hour / 2))
            if h:
                p.setPen(QColor(s.on_surface_variant))
                p.drawText(QRectF(0, y - 8, g.GUTTER - 10, 16), Qt.AlignmentFlag.AlignRight | Qt.AlignmentFlag.AlignVCenter,
                           clock(datetime(2026, 1, 1, h), h24))
        p.setPen(line)
        for i in range(len(g.days) + 1):
            x = g.GUTTER + i * cw + 0.5
            p.drawLine(QPointF(x, 0), QPointF(x, self.height()))
        p.setRenderHint(QPainter.RenderHint.Antialiasing)
        for b in g.blocks:
            self._block(p, b, cw, s, base)
        if g.today in g.days and g.now_fn is not None:
            now = g.now_fn()
            y = (now.hour * 60 + now.minute) / 60 * hour
            i = g.days.index(g.today)
            x0 = g.GUTTER + i * cw
            p.setPen(QPen(QColor(s.error), 2))
            p.drawLine(QPointF(x0, y), QPointF(x0 + cw, y))
            p.setBrush(QColor(s.error))
            p.setPen(Qt.PenStyle.NoPen)
            p.drawEllipse(QPointF(x0, y), 5, 5)
        p.end()

    def _block(self, p: QPainter, b: Block, cw: float, s: theme.Scheme, base: QFont) -> None:
        g = self.grid
        hour = g.HOUR
        lane_w = cw / b.lanes
        x = g.GUTTER + b.col * cw + b.lane * lane_w + 1.5
        y = b.start / 60 * hour + 1
        height = max(22.0, (b.end - b.start) / 60 * hour - 2)
        rect = QRectF(x, y, lane_w - 4 if b.lane == b.lanes - 1 else lane_w - 1.5, height)
        color = QColor(b.entry.color)
        path = QPainterPath()
        path.addRoundedRect(rect, 5, 5)
        p.fillPath(path, color)
        p.setPen(QPen(QColor(s.background), 1))
        p.setBrush(Qt.BrushStyle.NoBrush)
        p.drawPath(path)
        if self._hover is b.entry:
            p.setPen(QPen(QColor(s.on_surface), 1.5))
            p.drawPath(path)
        ink = ink_on(b.entry.color)
        inner = rect.adjusted(6, 3, -4, -2)
        name = LAYER_ICONS.get(b.entry.kind)
        title_x = inner.left()
        if name:
            _icon(p, name, ink, QRectF(inner.left(), inner.top() + 1, 13, 13))
            title_x += 16
        p.setPen(ink)
        p.setFont(_font(base, 8.5, QFont.Weight.DemiBold))
        title = b.entry.title or "(Sans titre)"
        fm = QFontMetrics(p.font())
        line_h = fm.height()
        when = time_text(b.entry, g.days[b.col], g.tz)
        if height < 2 * line_h + 6:
            p.drawText(QRectF(title_x, inner.top(), inner.right() - title_x, line_h),
                       Qt.AlignmentFlag.AlignLeft | Qt.AlignmentFlag.AlignVCenter,
                       _elide(p, f"{title}, {when}", inner.right() - title_x))
        else:
            p.drawText(QRectF(title_x, inner.top(), inner.right() - title_x, line_h),
                       Qt.AlignmentFlag.AlignLeft | Qt.AlignmentFlag.AlignVCenter, _elide(p, title, inner.right() - title_x))
            p.setFont(_font(base, 8))
            lines = [when] + ([b.entry.subtitle] if b.entry.subtitle else [])
            yy = inner.top() + line_h
            for text in lines:
                if yy + line_h > inner.bottom() + 2:
                    break
                p.drawText(QRectF(inner.left(), yy, inner.width(), line_h), Qt.AlignmentFlag.AlignLeft | Qt.AlignmentFlag.AlignVCenter,
                           _elide(p, text, inner.width()))
                yy += line_h
        self.hits.add(rect, "entry", b.entry)

    def mouseMoveEvent(self, event: QMouseEvent) -> None:
        hit = self.hits.at(event.position())
        hover = hit[1] if hit else None
        self.setCursor(Qt.CursorShape.PointingHandCursor if hover else Qt.CursorShape.ArrowCursor)
        if hover is not self._hover:
            self._hover = hover
            self.setToolTip(_tooltip(hover, self.grid.tz) if hover else "")
            self.update()

    def leaveEvent(self, event) -> None:
        if self._hover is not None:
            self._hover = None
            self.update()

    def mouseReleaseEvent(self, event: QMouseEvent) -> None:
        hit = self.hits.at(event.position())
        if hit and event.button() == Qt.MouseButton.LeftButton:
            self.grid.entry_clicked.emit(hit[1])

    def slot_at(self, point: QPointF) -> datetime | None:
        g = self.grid
        if point.x() < g.GUTTER:
            return None
        col = min(len(g.days) - 1, int((point.x() - g.GUTTER) // g.column_width()))
        minutes = int(point.y() / g.HOUR * 60) // 30 * 30
        d = g.days[col]
        return datetime(d.year, d.month, d.day) + timedelta(minutes=min(minutes, MINUTES - 30))

    def mouseDoubleClickEvent(self, event: QMouseEvent) -> None:
        if self.hits.at(event.position()):
            return
        slot = self.slot_at(event.position())
        if slot is not None:
            self.grid.slot_double_clicked.emit(slot)


# ---- Agenda (schedule) ----

@dataclass
class Row:
    kind: str                 # "month" | "today" | "entry" | "empty" | "header"
    day: date
    entry: Entry | None = None
    first_of_day: bool = False
    text: str = ""


def schedule_rows(by_day: dict[date, list[Entry]], today: date, show_year_months: bool = True) -> list[Row]:
    """Rows of the Agenda view: a month title when the month changes, then each day with entries (and today
    even when empty: "Aujourd'hui" is always there)."""
    rows: list[Row] = []
    month = None
    for d in sorted(by_day):
        items = by_day[d]
        if not items and d != today:
            continue
        if show_year_months and (d.year, d.month) != month:
            month = (d.year, d.month)
            rows.append(Row("month", d.replace(day=1), text=month_title(d).capitalize()))
        if d == today:
            rows.append(Row("today", d, text=f"Aujourd'hui · {long_date(d)}"))
        if not items:
            rows.append(Row("empty", d, first_of_day=True, text="Rien de prévu aujourd'hui"))
            continue
        for i, e in enumerate(items):
            rows.append(Row("entry", d, e, i == 0))
    return rows


def search_rows(hits: list[Entry]) -> list[Row]:
    """Search results in their order (upcoming first, then the past), with a month title when it changes."""
    rows: list[Row] = []
    month, day = None, None
    for e in hits:
        d = e.start_date
        if (d.year, d.month) != month:
            month = (d.year, d.month)
            rows.append(Row("month", d.replace(day=1), text=month_title(d).capitalize()))
            day = None
        rows.append(Row("entry", d, e, d != day))
        day = d
    return rows


class ScheduleModel(QAbstractListModel):
    def __init__(self):
        super().__init__()
        self.rows: list[Row] = []

    def rowCount(self, parent=QModelIndex()) -> int:
        return 0 if parent.isValid() else len(self.rows)

    def data(self, index: QModelIndex, role: int = Qt.ItemDataRole.DisplayRole):
        if not index.isValid():
            return None
        row = self.rows[index.row()]
        if role == Qt.ItemDataRole.UserRole:
            return row
        if role in (Qt.ItemDataRole.DisplayRole, Qt.ItemDataRole.AccessibleTextRole):
            return row.text or (row.entry.title if row.entry else "")
        return None

    def flags(self, index: QModelIndex):
        row = self.rows[index.row()] if index.isValid() else None
        if row is not None and row.kind == "entry":
            return Qt.ItemFlag.ItemIsEnabled | Qt.ItemFlag.ItemIsSelectable
        return Qt.ItemFlag.ItemIsEnabled

    def set_rows(self, rows: list[Row]) -> None:
        self.beginResetModel()
        self.rows = rows
        self.endResetModel()

    def append_rows(self, rows: list[Row]) -> None:
        if not rows:
            return
        self.beginInsertRows(QModelIndex(), len(self.rows), len(self.rows) + len(rows) - 1)
        self.rows.extend(rows)
        self.endInsertRows()


class ScheduleDelegate(QStyledItemDelegate):
    DATE_W = 72
    TIME_W = 150

    def __init__(self, view: "ScheduleView"):
        super().__init__(view)
        self.view = view

    def sizeHint(self, option, index) -> QSize:
        row: Row = index.data(Qt.ItemDataRole.UserRole)
        if row.kind == "month":
            return QSize(200, 48)
        if row.kind == "header":
            return QSize(200, 40)
        if row.kind == "today":
            return QSize(200, 34)
        top = self._gap(row, index)
        two = row.entry is not None and bool(row.entry.subtitle)
        return QSize(200, top + (50 if two else 38))

    @staticmethod
    def _gap(row: Row, index) -> int:
        """Space above the first row of a day, except right under a title."""
        if not row.first_of_day or index.row() == 0:
            return 0
        above: Row = index.model().index(index.row() - 1, 0).data(Qt.ItemDataRole.UserRole)
        return 0 if above.kind in ("month", "today", "header") else 10

    def paint(self, p: QPainter, option, index) -> None:
        row: Row = index.data(Qt.ItemDataRole.UserRole)
        s = theme.scheme()
        v = self.view
        rect = QRectF(option.rect)
        base = option.font
        p.save()
        p.setRenderHint(QPainter.RenderHint.Antialiasing)
        if row.kind in ("month", "header", "today"):
            size = {"month": 13, "header": 10.5, "today": 10}[row.kind]
            color = {"month": s.on_surface, "header": s.on_surface_variant, "today": s.primary}[row.kind]
            p.setFont(_font(base, size, QFont.Weight.DemiBold))
            p.setPen(QColor(color))
            area = rect.adjusted(16, 10, -8, 0) if row.kind != "today" else rect.adjusted(self.DATE_W + 10, 8, -8, 0)
            p.drawText(area, Qt.AlignmentFlag.AlignLeft | Qt.AlignmentFlag.AlignVCenter, row.text)
            if row.kind == "today":
                p.setPen(QPen(QColor(s.primary), 1))
                y = area.center().y()
                x = area.left() + p.fontMetrics().horizontalAdvance(row.text) + 12
                if x < rect.right() - 12:
                    p.drawLine(QPointF(x, y), QPointF(rect.right() - 12, y))
            p.restore()
            return
        top = self._gap(row, index)
        body = rect.adjusted(0, top, 0, 0)
        if row.first_of_day:
            self._date_block(p, row.day, QRectF(body.left(), body.top(), self.DATE_W, 44), s, base)
        content = body.adjusted(self.DATE_W, 2, -12, -2)
        if row.kind == "empty":
            p.setFont(_font(base, 10))
            p.setPen(QColor(s.on_surface_variant))
            p.drawText(content.adjusted(12, 0, 0, 0), Qt.AlignmentFlag.AlignVCenter | Qt.AlignmentFlag.AlignLeft, row.text)
            p.restore()
            return
        e = row.entry
        hovered = bool(option.state & QStyle.StateFlag.State_MouseOver)
        if hovered:
            path = QPainterPath()
            path.addRoundedRect(content, 8, 8)
            p.fillPath(path, QColor(s.surface_container))
        color = QColor(e.color)
        x = content.left() + 10
        name = LAYER_ICONS.get(e.kind)
        if name:
            _icon(p, name, color, QRectF(x - 2, content.center().y() - 8, 16, 16))
        else:
            p.setBrush(color)
            p.setPen(Qt.PenStyle.NoPen)
            p.drawEllipse(QPointF(x + 5, content.center().y()), 6, 6)
        x += 26
        p.setFont(_font(base, 9.5))
        p.setPen(QColor(s.on_surface_variant))
        when = time_text(e, row.day, v.tz, v.event_zone, v.today) if v.tz is not None else ""
        time_w = self.TIME_W
        p.drawText(QRectF(x, content.top(), time_w - 8, content.height()),
                   Qt.AlignmentFlag.AlignVCenter | Qt.AlignmentFlag.AlignLeft, _elide(p, when, time_w - 8))
        x += time_w
        width = content.right() - x
        title = e.title or "(Sans titre)"
        if e.recurring:
            title += "  ⟳"
        if e.subtitle:
            p.setFont(_font(base, 10.5, QFont.Weight.DemiBold))
            p.setPen(QColor(s.on_surface))
            p.drawText(QRectF(x, content.top() + 3, width, content.height() / 2), Qt.AlignmentFlag.AlignBottom,
                       _elide(p, title, width))
            p.setFont(_font(base, 9))
            p.setPen(QColor(s.on_surface_variant))
            p.drawText(QRectF(x, content.center().y() + 1, width, content.height() / 2 - 3), Qt.AlignmentFlag.AlignTop,
                       _elide(p, e.subtitle, width))
        else:
            p.setFont(_font(base, 10.5, QFont.Weight.DemiBold))
            p.setPen(QColor(s.on_surface))
            p.drawText(QRectF(x, content.top(), width, content.height()), Qt.AlignmentFlag.AlignVCenter,
                       _elide(p, title, width))
        p.restore()

    def _date_block(self, p: QPainter, d: date, rect: QRectF, s: theme.Scheme, base: QFont) -> None:
        today = d == self.view.today
        p.setFont(_font(base, 8, QFont.Weight.DemiBold))
        p.setPen(QColor(s.primary if today else s.on_surface_variant))
        label = "AUJ." if today else WEEKDAYS_SHORT[d.weekday()].rstrip(".").upper()
        p.drawText(QRectF(rect.left(), rect.top() + 2, rect.width(), 14), Qt.AlignmentFlag.AlignCenter, label)
        number = QRectF(rect.center().x() - 15, rect.top() + 16, 30, 28)
        p.setFont(_font(base, 13, QFont.Weight.DemiBold if today else QFont.Weight.Normal))
        if today:
            path = QPainterPath()
            path.addEllipse(number.center(), 14, 14)
            p.fillPath(path, QColor(s.primary))
            p.setPen(QColor(s.on_primary))
        else:
            p.setPen(QColor(s.on_surface))
        p.drawText(number, Qt.AlignmentFlag.AlignCenter, str(d.day))


class ScheduleView(QListView):
    entry_clicked = Signal(object)
    needs_more = Signal()  # scrolled near the end: the screen appends days

    def __init__(self):
        super().__init__()
        self.model_ = ScheduleModel()
        self.setModel(self.model_)
        self.setItemDelegate(ScheduleDelegate(self))
        self.setMouseTracking(True)
        self.setVerticalScrollMode(QAbstractItemView.ScrollMode.ScrollPerPixel)
        self.setSelectionMode(QAbstractItemView.SelectionMode.NoSelection)
        self.setFrameShape(QFrame.Shape.NoFrame)
        self.setUniformItemSizes(False)
        self.verticalScrollBar().setSingleStep(24)
        self.today = date.today()
        self.tz = None
        self.event_zone = False
        self.search_mode = False
        self.clicked.connect(self._clicked)
        self.verticalScrollBar().valueChanged.connect(self._scrolled)
        self.verticalScrollBar().rangeChanged.connect(lambda *_: self._scrolled(self.verticalScrollBar().value()))

    def set_rows(self, rows: list[Row], today: date, tz, event_zone: bool, search_mode: bool = False) -> None:
        self.today, self.tz, self.event_zone, self.search_mode = today, tz, event_zone, search_mode
        self.model_.set_rows(rows)

    def append_rows(self, rows: list[Row]) -> None:
        self.model_.append_rows(rows)

    def _clicked(self, index: QModelIndex) -> None:
        row: Row = index.data(Qt.ItemDataRole.UserRole)
        if row is not None and row.entry is not None:
            self.entry_clicked.emit(row.entry)

    def _scrolled(self, value: int) -> None:
        bar = self.verticalScrollBar()
        # Only when the list overflows: a short list already holds the screen's 400 days.
        if not self.search_mode and bar.maximum() > 0 and value >= bar.maximum() - self.viewport().height():
            self.needs_more.emit()

    def scroll_to_day(self, d: date) -> None:
        for i, row in enumerate(self.model_.rows):
            if row.kind != "month" and row.day >= d:
                self.scrollTo(self.model_.index(max(0, i - 1) if i and self.model_.rows[i - 1].kind == "month" else i),
                              QAbstractItemView.ScrollHint.PositionAtTop)
                return
