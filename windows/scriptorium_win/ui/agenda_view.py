"""The Agenda module: toolbar (views, Aujourd'hui, ◀ ▶, date, search, "+ Événement"), the calendars and layers
on the left, the four views, and every action on events (create, edit with series scopes, delete with undo,
restore an occurrence), calendars (create, rename, colour, delete), ICS import and export.

Same files and rules as the phone (core/agenda); Windows sends no reminders, they are only stored.
"""
from __future__ import annotations

import copy
import zipfile
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

from PySide6.QtCore import QDate, QPoint, QRectF, Qt, QTimer, Signal
from PySide6.QtGui import QColor, QKeySequence, QPainter, QPainterPath, QPen, QShortcut, QTextCharFormat
from PySide6.QtWidgets import (QApplication, QButtonGroup, QCalendarWidget, QDialog, QFileDialog, QFrame, QHBoxLayout,
                               QLabel, QLineEdit, QMenu, QMessageBox, QPushButton, QStackedWidget, QToolButton,
                               QVBoxLayout, QWidget, QWidgetAction)

from ..core import atomic
from ..core.agenda import calendars as cals
from ..core.agenda.entries import Entry, EntryKind
from ..core.agenda.frdates import long_date, month_title, week_title
from ..core.agenda.ics_export import export_all, export_calendar
from ..core.agenda.ics_import import IcsSource, apply_import, preview, read_sources, undo_import
from ..core.agenda.model import Event, event_to_note, new_event
from . import icons, overwrite, theme
from .agenda_data import AgendaData, AgendaPrefs
from .agenda_editor import EventDetailDialog, EventDialog, ask_scope, color_icon, french_locale
from .agenda_import import CalendarsDialog, ExportDialog, ImportDialog, PastImportsDialog, palette_menu, plural
from .agenda_views import (MonthView, Row, ScheduleView, TimeGridView, grid_start, schedule_rows, search_rows,
                           time_text, week_start_of)

VIEWS = {"agenda": "Agenda", "month": "Mois", "week": "Semaine", "day": "Jour"}
LAYERS = [(cals.LAYER_ACTIVITIES, "Activités", "run"), (cals.LAYER_NOTES, "Notes datées", "edit_note"),
          (cals.LAYER_BIRTHDAYS, "Anniversaires", "cake")]
CHUNK_DAYS = 60
UTC = timezone.utc


def add_months(d: date, n: int) -> date:
    month = d.month - 1 + n
    return date(d.year + month // 12, month % 12 + 1, 1)


class LayerRow(QWidget):
    """A calendar or a layer in the side panel: coloured check box, name, colour menu."""
    toggled = Signal(bool)
    color_changed = Signal(str)

    def __init__(self, name: str, color: str, visible: bool, icon_name: str | None = None):
        super().__init__()
        self.name, self.color, self.visible, self.icon_name = name, color, visible, icon_name
        self.setFixedHeight(32)
        self.setCursor(Qt.CursorShape.PointingHandCursor)
        self.setToolTip("Afficher / masquer")
        row = QHBoxLayout(self)
        row.setContentsMargins(34, 0, 2, 0)
        row.addStretch()
        self.palette = QToolButton()
        self.palette.setToolTip("Couleur")
        self.palette.setPopupMode(QToolButton.ToolButtonPopupMode.InstantPopup)
        self.palette.setFixedSize(26, 26)
        self.palette.setStyleSheet("QToolButton { padding: 3px; border-radius: 13px; } QToolButton::menu-indicator { image: none; }")
        row.addWidget(self.palette)
        self.palette.setMenu(palette_menu(self, lambda: self.color, self.color_changed.emit))
        self._hover = False
        self.restyle()

    def restyle(self) -> None:
        self.palette.setIcon(icons.icon("palette", theme.scheme().on_surface_variant, 16))

    def set_state(self, name: str, color: str, visible: bool) -> None:
        changed = (name, color, visible) != (self.name, self.color, self.visible)
        self.name, self.visible = name, visible
        self.color = color
        if changed:
            self.update()

    def paintEvent(self, event) -> None:
        s = theme.scheme()
        p = QPainter(self)
        p.setRenderHint(QPainter.RenderHint.Antialiasing)
        if self._hover:
            path = QPainterPath()
            path.addRoundedRect(QRectF(self.rect()).adjusted(2, 1, -2, -1), 8, 8)
            p.fillPath(path, QColor(s.surface_high))
        box = QRectF(10, (self.height() - 16) / 2, 16, 16)
        color = QColor(self.color)
        path = QPainterPath()
        path.addRoundedRect(box, 4, 4)
        if self.visible:
            p.fillPath(path, color)
            p.setPen(QPen(QColor("#FFFFFF"), 2))
            p.drawPolyline([box.topLeft() + QPoint(3, 8), box.topLeft() + QPoint(7, 12), box.topLeft() + QPoint(13, 4)])
        else:
            p.setPen(QPen(color, 2))
            p.drawPath(path)
        x = 34
        if self.icon_name:
            p.drawPixmap(x, (self.height() - 16) // 2, icons.icon(self.icon_name, s.on_surface_variant, 16).pixmap(16, 16))
            x += 22
        p.setPen(QColor(s.on_surface if self.visible else s.on_surface_variant))
        p.drawText(QRectF(x, 0, self.width() - x - 32, self.height()), Qt.AlignmentFlag.AlignVCenter,
                   p.fontMetrics().elidedText(self.name, Qt.TextElideMode.ElideRight, self.width() - x - 32))
        p.end()

    def enterEvent(self, event) -> None:
        self._hover = True
        self.update()

    def leaveEvent(self, event) -> None:
        self._hover = False
        self.update()

    def mouseReleaseEvent(self, event) -> None:
        if event.button() == Qt.MouseButton.LeftButton:
            self.visible = not self.visible
            self.update()
            self.toggled.emit(self.visible)


class DayPopup(QFrame):
    """The entries of a day, popped up from the month grid ("+N" or a click on the day)."""

    def __init__(self, screen: "AgendaScreen", day: date, entries: list[Entry]):
        super().__init__(screen, Qt.WindowType.Popup)
        s = theme.scheme()
        self.setObjectName("dayPopup")
        self.setStyleSheet(f"QFrame#dayPopup {{ background: {s.surface_high}; border: 1px solid {s.outline_variant}; border-radius: 12px; }}")
        layout = QVBoxLayout(self)
        layout.setContentsMargins(12, 10, 12, 10)
        layout.setSpacing(2)
        title = QLabel(long_date(day).capitalize())
        title.setStyleSheet("font-weight: 600; font-size: 11pt;")
        layout.addWidget(title)
        if not entries:
            empty = QLabel("Rien de prévu")
            empty.setStyleSheet(f"color: {s.on_surface_variant};")
            layout.addWidget(empty)
        for e in entries[:30]:
            b = QPushButton(f"{time_text(e, day, screen.data.tz, screen.prefs.event_zone_times, screen.data.today())}   "
                            f"{e.title or '(Sans titre)'}")
            b.setIcon(color_icon(e.color, 6))
            b.setStyleSheet(f"QPushButton {{ text-align: left; color: {s.on_surface}; padding: 6px 10px; border-radius: 8px; }}")
            b.clicked.connect(lambda _=False, x=e: (self.close(), screen.activate(x)))
            layout.addWidget(b)
        if len(entries) > 30:
            layout.addWidget(QLabel(f"… et {len(entries) - 30} autres"))
        row = QHBoxLayout()
        new = QPushButton("Nouvel événement")
        new.clicked.connect(lambda: (self.close(), screen.new_event(day)))
        see = QPushButton("Voir la journée")
        see.clicked.connect(lambda: (self.close(), screen.go_to(day, "day")))
        row.addWidget(new)
        row.addStretch()
        row.addWidget(see)
        layout.addLayout(row)
        self.setMinimumWidth(320)


class AgendaScreen(QWidget):
    open_note = Signal(object)      # Note (a dated note, or an event opened as a note)
    open_activity = Signal(object)  # Note of activities/
    open_contact = Signal(str)      # contact id
    message = Signal(str, object)   # text, undo callback or None
    changed = Signal()              # files written: the window rescans

    def __init__(self, config=None):
        super().__init__()
        self.config = config
        self.prefs = AgendaPrefs.from_config(config)
        self.data = AgendaData()
        self.store = None
        self.view = self.prefs.view
        self.anchor = date.today()
        self._schedule_end: date | None = None
        self._schedule_anchor: date | None = None
        self._problems: list[str] = []
        self._rendered: tuple | None = None
        self._calendar_rows: dict[str, LayerRow] = {}
        self._layer_rows: dict[str, LayerRow] = {}

        outer = QVBoxLayout(self)
        outer.setContentsMargins(8, 8, 8, 0)
        outer.setSpacing(6)

        # ---- toolbar ----
        bar = QHBoxLayout()
        bar.setSpacing(6)
        self.today_button = QPushButton("Aujourd'hui")
        self.today_button.setObjectName("chip")
        self.today_button.setToolTip("Aujourd'hui (T)")
        self.today_button.clicked.connect(self.go_today)
        self.prev_button = QToolButton()
        self.prev_button.setToolTip("Précédent (←)")
        self.prev_button.clicked.connect(lambda: self.step(-1))
        self.next_button = QToolButton()
        self.next_button.setToolTip("Suivant (→)")
        self.next_button.clicked.connect(lambda: self.step(1))
        self.period = QToolButton()
        self.period.setToolTip("Aller à une date")
        self.period.setPopupMode(QToolButton.ToolButtonPopupMode.InstantPopup)
        self.period.setToolButtonStyle(Qt.ToolButtonStyle.ToolButtonTextOnly)
        self.period.setStyleSheet("QToolButton { font-size: 15pt; font-weight: 600; padding: 4px 10px; border-radius: 12px; }"
                                  "QToolButton::menu-indicator { image: none; }")
        self._build_jump_menu()
        for w in (self.today_button, self.prev_button, self.next_button, self.period):
            bar.addWidget(w)
        bar.addStretch()
        self.search = QLineEdit()
        self.search.setPlaceholderText("Rechercher (titre, lieu, description)")
        self.search.setClearButtonEnabled(True)
        self.search.setFixedWidth(260)
        self.search_timer = QTimer(self, singleShot=True, interval=250)
        self.search_timer.timeout.connect(self.render)
        self.search.textChanged.connect(lambda _: self.search_timer.start())
        bar.addWidget(self.search)
        self.view_buttons: dict[str, QPushButton] = {}
        group = QButtonGroup(self)
        group.setExclusive(True)
        for key, label in VIEWS.items():
            b = QPushButton(label)
            b.setObjectName("chip")
            b.setCheckable(True)
            b.clicked.connect(lambda _=False, k=key: self.set_view(k))
            group.addButton(b)
            self.view_buttons[key] = b
            bar.addWidget(b)
        self.menu_button = QToolButton()
        self.menu_button.setToolTip("Agendas, import, export")
        self.menu_button.setPopupMode(QToolButton.ToolButtonPopupMode.InstantPopup)
        self.menu_button.setStyleSheet("QToolButton::menu-indicator { image: none; }")
        menu = QMenu(self.menu_button)
        menu.addAction("Gérer les agendas…", self.manage_calendars)
        menu.addSeparator()
        menu.addAction("Importer un agenda (.ics, .zip)…", lambda: self.import_ics())
        menu.addAction("Imports passés…", self.past_imports)
        menu.addAction("Exporter…", lambda: self.export())
        self.menu_button.setMenu(menu)
        bar.addWidget(self.menu_button)
        self.new_button = QPushButton("Événement")
        self.new_button.setObjectName("primary")
        self.new_button.setToolTip("Nouvel événement sur le jour affiché")
        self.new_button.clicked.connect(lambda: self.new_event())
        bar.addWidget(self.new_button)
        outer.addLayout(bar)

        # ---- side panel + views ----
        body = QHBoxLayout()
        body.setSpacing(8)
        self.side = QWidget()
        self.side.setFixedWidth(210)
        side = QVBoxLayout(self.side)
        side.setContentsMargins(0, 4, 0, 8)
        side.setSpacing(0)
        self.cal_title = QLabel("Agendas")
        side.addWidget(self.cal_title)
        self.cal_box = QVBoxLayout()
        self.cal_box.setSpacing(0)
        side.addLayout(self.cal_box)
        manage = QPushButton("Gérer…")
        manage.clicked.connect(self.manage_calendars)
        side.addWidget(manage, 0, Qt.AlignmentFlag.AlignLeft)
        side.addSpacing(12)
        self.layer_title = QLabel("Calques")
        side.addWidget(self.layer_title)
        for key, label, icon_name in LAYERS:
            row = LayerRow(label, cals.DEFAULT_LAYER_COLORS[key], True, icon_name)
            row.toggled.connect(lambda on, k=key: self.set_layer(k, visible=on))
            row.color_changed.connect(lambda c, k=key: self.set_layer(k, color=c))
            self._layer_rows[key] = row
            side.addWidget(row)
        side.addStretch()
        self.problems = QPushButton()
        self.problems.setStyleSheet("text-align: left;")
        self.problems.clicked.connect(self.show_problems)
        self.problems.hide()
        side.addWidget(self.problems)
        body.addWidget(self.side)

        self.stack = QStackedWidget()
        self.schedule = ScheduleView()
        self.schedule.entry_clicked.connect(self.activate)
        self.schedule.needs_more.connect(self._extend_schedule)
        self.month = MonthView()
        self.month.entry_clicked.connect(self.activate)
        self.month.day_clicked.connect(self._month_day)
        self.month.day_double_clicked.connect(lambda d: self.new_event(d))
        self.grid = TimeGridView()
        self.grid.entry_clicked.connect(self.activate)
        self.grid.slot_double_clicked.connect(lambda at: self.new_event(at))
        self.grid.day_clicked.connect(lambda d: self.go_to(d, "day"))
        self.grid.more_clicked.connect(self._month_day)
        self.results = ScheduleView()
        self.results.entry_clicked.connect(self.activate)
        for w in (self.schedule, self.month, self.grid, self.results):
            self.stack.addWidget(w)
        body.addWidget(self.stack, 1)
        outer.addLayout(body, 1)

        for keys, action in (("T", self.go_today), ("Left", lambda: self.step(-1)), ("Right", lambda: self.step(1)),
                             ("PgUp", lambda: self.step(-1)), ("PgDown", lambda: self.step(1))):
            QShortcut(QKeySequence(keys), self, activated=action,
                      context=Qt.ShortcutContext.WidgetWithChildrenShortcut)
        # Today moves at midnight even when no file changes: the key of `render` includes the date.
        self.day_timer = QTimer(self, interval=60_000)
        self.day_timer.timeout.connect(self.render)
        self.day_timer.start()
        self.restyle()
        self._show_view_buttons()

    # ---- plumbing ----

    def set_store(self, store) -> None:
        self.store = store
        self.data.reset(store)
        self._rendered = None

    def restyle(self) -> None:
        s = theme.scheme()
        self.prev_button.setIcon(icons.icon("chevron_left", s.on_surface, 24))
        self.next_button.setIcon(icons.icon("chevron_right", s.on_surface, 24))
        self.menu_button.setIcon(icons.icon("more_vert", s.on_surface, 22))
        self.new_button.setIcon(icons.icon("add", s.on_primary, 20))
        for label in (self.cal_title, self.layer_title):
            label.setStyleSheet(f"color: {s.primary}; font-weight: 600; padding: 4px 10px;")
        self.problems.setStyleSheet(f"text-align: left; color: {s.error};")
        self.search.setStyleSheet(f"QLineEdit {{ background: {s.surface_high}; border: 1px solid {s.outline_variant}; "
                                  f"border-radius: 12px; padding: 4px 12px; color: {s.on_surface}; }}"
                                  f"QLineEdit:focus {{ border-color: {s.primary}; }}")
        for row in list(self._calendar_rows.values()) + list(self._layer_rows.values()):
            row.restyle()
            row.update()
        self._rendered = None
        self.render()

    def refresh(self, notes=None, activities=None) -> None:
        """Rescans (only changed files are read) and redraws when something the agenda shows changed."""
        if self.store is None:
            return
        if self.data.load(notes, activities):
            self._sync_side()
            self._rendered = None
        self.render()

    def showEvent(self, event) -> None:
        super().showEvent(event)
        if not self.isAncestorOf(QApplication.focusWidget() or self):
            self.stack.currentWidget().setFocus()  # so T and the arrows work right after opening the module

    def focus_search(self) -> None:
        self.search.setFocus()
        self.search.selectAll()

    def _show_view_buttons(self) -> None:
        for key, b in self.view_buttons.items():
            b.setChecked(key == self.view)

    # ---- navigation ----

    def set_view(self, view: str) -> None:
        self.view = view
        if self.config is not None and hasattr(self.config, "agenda_view"):
            self.config.agenda_view = view
        self._show_view_buttons()
        self.render()

    def go_today(self) -> None:
        self.anchor = self.data.today()
        self.render(force=True)
        if self.view == "agenda":
            self.schedule.scroll_to_day(self.anchor)

    def go_to(self, d: date, view: str | None = None) -> None:
        self.anchor = d
        if view:
            self.view = view
            self._show_view_buttons()
        self.render(force=True)

    def step(self, direction: int) -> None:
        a = self.anchor
        if self.view == "month":
            self.anchor = add_months(a.replace(day=1), direction)
        elif self.view == "week":
            self.anchor = a + timedelta(days=7 * direction)
        elif self.view == "day":
            self.anchor = a + timedelta(days=direction)
        else:
            self.anchor = a + timedelta(days=7 * direction)
        self.render()

    def _build_jump_menu(self) -> None:
        menu = QMenu(self.period)
        self.jump = QCalendarWidget()
        self.jump.setGridVisible(False)
        self.jump.setVerticalHeaderFormat(QCalendarWidget.VerticalHeaderFormat.NoVerticalHeader)
        self.jump.setLocale(french_locale())
        self.jump.clicked.connect(lambda q: (menu.close(), self.go_to(date(q.year(), q.month(), q.day()))))
        action = QWidgetAction(menu)
        action.setDefaultWidget(self.jump)
        menu.addAction(action)
        menu.aboutToShow.connect(self._prepare_jump)
        self.period.setMenu(menu)

    def _prepare_jump(self) -> None:
        self.jump.setFirstDayOfWeek(Qt.DayOfWeek.Sunday if self.prefs.week_start == 6 else Qt.DayOfWeek.Monday)
        self.jump.setSelectedDate(QDate(self.anchor.year, self.anchor.month, self.anchor.day))
        weekend = QTextCharFormat()
        weekend.setForeground(QColor(theme.scheme().on_surface_variant))
        for day in (Qt.DayOfWeek.Saturday, Qt.DayOfWeek.Sunday):
            self.jump.setWeekdayTextFormat(day, weekend)

    # ---- side panel ----

    def _sync_side(self) -> None:
        settings = self.data.settings
        wanted = [c.id for c in settings.calendars]
        if list(self._calendar_rows) != wanted:
            for row in self._calendar_rows.values():
                row.deleteLater()
            self._calendar_rows = {}
            for cal in settings.calendars:
                row = LayerRow(cal.name, cal.color, cal.visible)
                row.toggled.connect(lambda on, i=cal.id: self.set_calendar_visible(i, on))
                row.color_changed.connect(lambda c, i=cal.id: self.recolor_calendar(i, c))
                self._calendar_rows[cal.id] = row
                self.cal_box.addWidget(row)
        for cal in settings.calendars:
            self._calendar_rows[cal.id].set_state(cal.name, cal.color, cal.visible)
        for key, row in self._layer_rows.items():
            layer = settings.layer(key)
            row.set_state(row.name, layer.color, layer.visible)

    def show_problems(self) -> None:
        box = QMessageBox(self)
        box.setWindowTitle("Problèmes de lecture")
        box.setText("Ces fichiers ou ces règles n'ont pas pu être lus entièrement. Rien n'a été modifié ; "
                    "ce qui est lisible est affiché.")
        box.setDetailedText("\n".join(self._problems))
        box.exec()

    # ---- rendering ----

    def _period_text(self) -> str:
        a = self.anchor
        if self.view == "month":
            return month_title(a).capitalize()
        if self.view == "week":
            first = week_start_of(a, self.prefs.week_start)
            return week_title(first, first + timedelta(days=6))
        if self.view == "day":
            return long_date(a).capitalize()
        return month_title(a).capitalize()

    def render(self, force: bool = False) -> None:
        if self.store is None:
            return
        query = self.search.text().strip()
        key = (self.view, self.anchor, self.data.version, query, self.prefs.week_start, self.prefs.event_zone_times,
               self.data.today())
        if key == self._rendered and not force:
            return
        self._rendered = key
        self.period.setText(self._period_text())
        today = self.data.today()
        tz = self.data.tz
        if query:
            hits = self.data.search(query)
            rows = [Row("header", today, text=plural(len(hits), "résultat", "résultats") + f" pour « {query} »")]
            rows += search_rows(hits)
            self.results.set_rows(rows, today, tz, self.prefs.event_zone_times, search_mode=True)
            self.stack.setCurrentWidget(self.results)
            return
        if self.view == "month":
            start = grid_start(self.anchor, self.prefs.week_start)
            result = self.data.entries(start, start + timedelta(days=41))
            self.month.set_month(self.anchor, self.prefs.week_start, result.entries, today, tz)
            self.stack.setCurrentWidget(self.month)
        elif self.view in ("week", "day"):
            if self.view == "week":
                first = week_start_of(self.anchor, self.prefs.week_start)
                days = [first + timedelta(days=i) for i in range(7)]
            else:
                days = [self.anchor]
            result = self.data.entries(days[0], days[-1])
            self.grid.set_days(days, result.entries, today, tz, self.data.now)
            self.stack.setCurrentWidget(self.grid)
        else:
            result = self._render_schedule(today, keep_scroll=self.stack.currentWidget() is self.schedule)
            self.stack.setCurrentWidget(self.schedule)
        self._show_problems(result.problems)

    def _render_schedule(self, today: date, keep_scroll: bool):
        """Days from the anchor until 25 entries or 400 days; a redraw after a sync keeps the days already
        loaded and the scroll position when the anchor did not move."""
        first = self.anchor
        last = first + timedelta(days=CHUNK_DAYS - 1)
        same = keep_scroll and self._schedule_anchor == first and self._schedule_end is not None
        while True:
            result = self.data.entries(first, last)
            if (len(result.entries) >= 25 or (last - first).days >= 400) and (not same or last >= self._schedule_end):
                break
            last += timedelta(days=CHUNK_DAYS)
        by_day = self.data.by_day(first, last)
        if first > today:
            by_day.pop(today, None)
        position = self.schedule.verticalScrollBar().value() if same else 0
        self._schedule_anchor, self._schedule_end = first, last
        self.schedule.set_rows(schedule_rows(by_day, today), today, self.data.tz, self.prefs.event_zone_times)
        self.schedule.verticalScrollBar().setValue(position)
        return result

    def _show_problems(self, window_problems: list[str]) -> None:
        """Unreadable files, and what the visible window could not show (bad rules, cut series): never hidden."""
        problems = list(dict.fromkeys(self.data.problems + window_problems))
        self._problems = problems
        self.problems.setVisible(bool(problems))
        self.problems.setText(plural(len(problems), "problème de lecture", "problèmes de lecture") if problems else "")

    def _extend_schedule(self) -> None:
        if self._schedule_end is None or self.view != "agenda" or self.search.text().strip():
            return
        if self._schedule_end > self.anchor + timedelta(days=3650):
            return
        first = self._schedule_end + timedelta(days=1)
        last = first + timedelta(days=CHUNK_DAYS - 1)
        self._schedule_end = last
        rows = schedule_rows(self.data.by_day(first, last), self.data.today())
        existing = self.schedule.model_.rows
        last_month = next((r for r in reversed(existing) if r.kind == "month"), None)
        if rows and last_month is not None and rows[0].kind == "month" and rows[0].day == last_month.day:
            rows = rows[1:]
        self.schedule.append_rows(rows)

    def _month_day(self, day: date, where: QPoint) -> None:
        entries = self.data.by_day(day, day)[day]
        popup = DayPopup(self, day, entries)
        popup.adjustSize()
        screen = QApplication.screenAt(where) or QApplication.primaryScreen()
        area = screen.availableGeometry()
        x = min(max(area.left(), where.x() - popup.width() // 2), area.right() - popup.width())
        y = min(where.y(), area.bottom() - popup.height())
        popup.move(x, y)
        popup.show()

    # ---- opening entries ----

    def activate(self, entry: Entry) -> None:
        if entry.kind == EntryKind.ACTIVITY:
            note = self.data.activity(entry.target_id)
            if note is not None:
                self.open_activity.emit(note)
        elif entry.kind == EntryKind.BIRTHDAY:
            self.open_contact.emit(entry.target_id)
        elif entry.kind == EntryKind.NOTE:
            note = self.data.note(entry.target_id)
            if note is not None:
                self.open_note.emit(note)
        else:
            self.show_event(entry)

    def show_event(self, entry: Entry) -> None:
        ev = self.data.event(entry.target_id)
        if ev is None:
            return
        series = self.data.event(ev.recurrence_of) if ev.recurrence_of else None
        dialog = EventDetailDialog(self, entry, self.data.settings, self.data.zone, self.data.today(),
                                   self.prefs.event_zone_times, series)
        dialog.exec()
        if dialog.action == "edit":
            self.edit_entry(entry)
        elif dialog.action == "delete":
            self.delete_entry(entry)
        elif dialog.action == "note":
            self.open_note.emit(event_to_note(ev))
        elif dialog.action == "restore":
            self.restore_entry(entry)

    # ---- writing ----

    def _snapshot(self, events: list[Event]) -> dict[str, bytes | None]:
        """The current bytes of every file of these events (None: absent), for an undo."""
        out: dict[str, bytes | None] = {}
        for ev in events:
            for path in [ev.path, ev.note.pdf, *ev.note.attachments]:
                if path and path not in out:
                    try:
                        out[path] = self.store.file_path(path).read_bytes()
                    except FileNotFoundError:
                        out[path] = None
        return out

    def _restore(self, snapshot: dict[str, bytes | None]) -> None:
        for path, data in snapshot.items():
            target = self.store.file_path(path)
            if data is None:
                target.unlink(missing_ok=True)
            else:
                atomic.write_bytes(target, data)
        self.refresh()
        self.changed.emit()

    def _after_write(self) -> None:
        self.refresh()
        self.changed.emit()

    def new_event(self, at: date | None = None, dialog_hook=None) -> Event | None:
        """The editor on a new event: at a slot (datetime), on a day (date: the next hour today, 9:00 otherwise)."""
        today, now = self.data.today(), self.data.now()
        if isinstance(at, datetime):
            start = at
        else:
            day = at or (self.anchor if self.view != "agenda" else today)
            hour = now.hour + 1 if day == today and now.hour < 23 else 9
            start = datetime(day.year, day.month, day.day, hour)
        settings = self.data.settings
        ev = new_event("", start, start + timedelta(hours=1), tz=self.data.zone,
                       calendar=settings.default_calendar().id, alerts=list(self.prefs.default_alerts))
        dialog = EventDialog(self, ev, settings, self.data.zone, today, is_new=True)
        accepted = dialog_hook(dialog) if dialog_hook is not None else dialog.exec() == QDialog.DialogCode.Accepted
        if not accepted:
            return None
        saved = self.store.agenda.save(dialog.build(), taken_ids=self.data.taken_ids())
        self._after_write()
        self.message.emit("Événement créé", None)
        return saved

    def edit_entry(self, entry: Entry, scope: str | None = None, dialog_hook=None) -> None:
        """`dialog_hook(dialog)` stands in for the user in tests: it fills the dialog and returns accept/reject."""
        ev = self.data.event(entry.target_id)
        if ev is None:
            return
        settings, today, zone = self.data.settings, self.data.today(), self.data.zone
        agenda = self.store.agenda
        before = self.data.file_stamp(ev.path)  # the version on screen, not the one on disk right now

        def run(dialog: EventDialog) -> bool:
            accepted = dialog_hook(dialog) if dialog_hook is not None else dialog.exec() == QDialog.DialogCode.Accepted
            # the dialog is modal but the sync is not: the file may have been replaced (or deleted) meanwhile
            if accepted and not overwrite.may_overwrite(self, self.store.root / ev.path, before, ev.title or "Sans titre"):
                self._after_write()
                return False
            return accepted

        if ev.rrule:
            scope = scope or ask_scope(self, "Modifier")
            if scope is None:
                return
            o = entry.occurrence
            if scope == "one":
                draft = agenda.make_exception(ev, o.start, o.end)
                dialog = EventDialog(self, draft, settings, zone, today, is_new=False, scope="one")
                if not run(dialog):
                    if dialog.delete_requested:
                        self.delete_entry(entry, "one")
                    return
                agenda.save_exception(ev, dialog.build())
            else:
                dialog = EventDialog(self, ev, settings, zone, today, is_new=False, scope="all")
                if not run(dialog):
                    if dialog.delete_requested:
                        self.delete_entry(entry, "all")
                    return
                self._save_series(ev, dialog.build())
        else:
            dialog = EventDialog(self, ev, settings, zone, today, is_new=False)
            if not run(dialog):
                if dialog.delete_requested:
                    self.delete_entry(entry)
                return
            agenda.save(dialog.build().with_note(updated=_now_ms()))
        self._after_write()
        self.message.emit("Événement enregistré", None)

    def _save_series(self, old: Event, new: Event) -> None:
        """"Tous les événements": when the first start moves, the skipped and edited occurrences move with it, or
        they would stop matching the series."""
        agenda = self.store.agenda
        delta = None
        if type(old.start) is type(new.start) and new.start != old.start:
            delta = new.start - old.start
        if delta is not None:
            new = new.copy(exdate=[x + delta for x in old.exdate if type(x) is type(old.start)] +
                           [x for x in old.exdate if type(x) is not type(old.start)])
            for exc in self.data.exceptions_of(old.id):
                if exc.recurrence_at is not None and type(exc.recurrence_at) is type(old.start):
                    agenda.save(exc.copy(recurrence_at=exc.recurrence_at + delta))
        elif type(old.start) is not type(new.start):
            new = new.copy(exdate=[])  # all-day <-> timed: the old stamps can no longer match
        agenda.save(new.with_note(updated=_now_ms()))

    def delete_entry(self, entry: Entry, scope: str | None = None) -> None:
        ev = self.data.event(entry.target_id)
        if ev is None:
            return
        agenda = self.store.agenda
        series = ev if ev.rrule else (self.data.event(ev.recurrence_of) if ev.recurrence_of else None)
        if series is not None:
            scope = scope or ask_scope(self, "Supprimer")
            if scope is None:
                return
        if series is not None and scope == "all":
            touched = [series] + self.data.exceptions_of(series.id)
            snapshot = self._snapshot(touched)
            agenda.delete_series(series, self.data.sources.events)
            text = "Série supprimée"
        elif ev.rrule:
            snapshot = self._snapshot([ev])
            agenda.delete_occurrence(ev, entry.occurrence.start)
            text = "Occurrence supprimée"
        else:
            # An edited occurrence: its file goes and the series keeps the occurrence in exdate (3.2).
            snapshot = self._snapshot([ev])
            agenda.delete(ev)
            text = "Événement supprimé"
        self._after_write()
        self.message.emit(text, lambda: self._restore(snapshot))

    def restore_entry(self, entry: Entry) -> None:
        """"Rétablir l'original": the edited occurrence goes, the series' own occurrence comes back."""
        ev = self.data.event(entry.target_id)
        series = self.data.event(ev.recurrence_of) if ev is not None and ev.recurrence_of else None
        if ev is None or series is None or ev.recurrence_at is None:
            return
        snapshot = self._snapshot([ev, series])
        self.store.agenda.restore_occurrence(series, ev, ev.recurrence_at)
        self._after_write()
        self.message.emit("Occurrence rétablie", lambda: self._restore(snapshot))

    # ---- calendars and layers (agenda/calendars.json, synced) ----

    def _edit_settings(self, change) -> bool:
        """Applies `change(settings)` to a copy and writes it. An unreadable calendars.json is only replaced
        after the user agreed."""
        settings = copy.deepcopy(self.data.settings)
        if settings.problem:
            answer = QMessageBox.question(
                self, "Agendas", f"{settings.problem}\n\nL'enregistrer remplacera ce fichier par les réglages affichés. Continuer ?")
            if answer != QMessageBox.StandardButton.Yes:
                return False
            settings.problem = None
        change(settings)
        self.store.agenda.save_calendars(settings)
        self._after_write()
        return True

    def set_calendar_visible(self, calendar_id: str, visible: bool) -> None:
        def change(s):
            s.calendar(calendar_id).visible = visible
        if not self._edit_settings(change):
            self._sync_side()

    def recolor_calendar(self, calendar_id: str, color: str) -> None:
        def change(s):
            s.calendar(calendar_id).color = color
        self._edit_settings(change)

    def rename_calendar(self, calendar_id: str, name: str) -> None:
        if self.data.settings.calendar(calendar_id).name == name:
            return

        def change(s):
            s.calendar(calendar_id).name = name
        self._edit_settings(change)

    def set_default_calendar(self, calendar_id: str) -> None:
        def change(s):
            s.default = calendar_id
        self._edit_settings(change)

    def create_calendar(self, name: str) -> str:
        new_id = cals.unique_calendar_id(name, self.data.settings)

        def change(s):
            s.calendars.append(cals.Calendar(new_id, name, cals.next_color(s), True))
        self._edit_settings(change)
        return new_id

    def calendar_counts(self) -> dict[str, int]:
        counts: dict[str, int] = {}
        for e in self.data.sources.events:
            cid = self.data.settings.calendar(e.calendar).id
            counts[cid] = counts.get(cid, 0) + 1
        return counts

    def delete_calendar(self, calendar_id: str, move_to: str | None) -> None:
        """Removes the calendar; its events go to `move_to`, or are deleted when None."""
        settings = self.data.settings
        victims = [e for e in self.data.sources.events if settings.calendar(e.calendar).id == calendar_id]

        def change(s):
            s.calendars = [c for c in s.calendars if c.id != calendar_id]
            if s.default == calendar_id:
                s.default = move_to or s.calendars[0].id
        if not self._edit_settings(change):
            return
        for ev in victims:
            if move_to:
                self.store.agenda.save(ev.copy(calendar=move_to))
            else:
                self.store.agenda.delete(ev)
        self._after_write()
        self.message.emit("Agenda supprimé", None)

    def set_layer(self, key: str, visible: bool | None = None, color: str | None = None) -> None:
        def change(s):
            layer = s.layers.get(key) or s.layer(key)
            if visible is not None:
                layer.visible = visible
            if color is not None:
                layer.color = color
            s.layers[key] = layer
        if not self._edit_settings(change):
            self._sync_side()

    def manage_calendars(self) -> None:
        CalendarsDialog(self, self).exec()

    # ---- import / export ----

    def read_ics(self, paths: list[Path]):
        """Parses the files (zips expanded). Raises OSError / ValueError with a French message."""
        try:
            return read_sources([IcsSource.from_path(p) for p in paths], self.data.zone)
        except zipfile.BadZipFile as error:
            raise ValueError(f"archive .zip illisible ({error})") from error
        except UnicodeDecodeError as error:
            raise ValueError(f"encodage illisible ({error})") from error

    def import_ics(self, paths: list[Path] | None = None, dialog_hook=None) -> None:
        """`dialog_hook(dialog)` drives the dialog in tests instead of `exec`."""
        if paths is None:
            chosen, _ = QFileDialog.getOpenFileNames(self, "Importer un agenda", str(Path.home()),
                                                     "Agendas (*.ics *.zip);;Tous les fichiers (*)")
            if not chosen:
                return
            paths = [Path(c) for c in chosen]
        self.refresh()
        QApplication.setOverrideCursor(Qt.CursorShape.WaitCursor)
        try:
            parsed = self.read_ics(paths)
        except (OSError, ValueError) as error:
            QApplication.restoreOverrideCursor()
            QMessageBox.warning(self, "Import", f"Import impossible : {error}")
            return
        QApplication.restoreOverrideCursor()
        if not parsed:
            QMessageBox.information(self, "Import", "Aucun agenda trouvé dans ce fichier.")
            return
        existing = list(self.data.sources.events)
        settings = copy.deepcopy(self.data.settings)
        if settings.problem:
            answer = QMessageBox.question(self, "Import", f"{settings.problem}\n\nL'import peut devoir réécrire ce fichier "
                                                          "(nouveaux agendas). Continuer ?")
            if answer != QMessageBox.StandardButton.Yes:
                return
            settings.problem = None
        previews = preview(parsed, existing, settings)
        zone = self.data.zone

        def run(options, progress):
            report = apply_import(self.store.agenda, parsed, options, existing, settings, zone,
                                  taken_ids=frozenset(self.data.taken_ids()), progress=progress)
            self._after_write()
            return report

        dialog = ImportDialog(self, parsed, previews, settings, existing, run, self.undo_import, self.data.today())
        if dialog_hook is not None:
            dialog_hook(dialog)
        else:
            dialog.exec()
        if dialog.report is not None and not dialog.undone:
            self.message.emit(f"Import : {dialog.report.summary()}", None)

    def undo_import(self, import_id: str) -> str:
        self.refresh()
        report = undo_import(self.store.agenda, import_id, list(self.data.sources.events), copy.deepcopy(self.data.settings))
        self._after_write()
        text = plural(report.deleted, "fichier supprimé", "fichiers supprimés")
        if report.calendars_removed:
            text += ", " + plural(len(report.calendars_removed), "agenda retiré", "agendas retirés")
        return text

    def past_imports(self) -> None:
        def imports():
            self.refresh()
            return self.data.import_ids()
        PastImportsDialog(self, imports, self.undo_import).exec()

    def export(self, target: Path | None = None, calendar_id: str | None = None, include: bool | None = None) -> Path | None:
        if include is None:
            dialog = ExportDialog(self, self.data.settings)
            if dialog.exec() != QDialog.DialogCode.Accepted:
                return None
            calendar_id, include = dialog.choice()
        settings = self.data.settings
        now = datetime.now(UTC)
        notes = self.data.sources.notes if include else None
        acts = self.data.sources.activities if include else None
        if target is None:
            from ..core import note as fmt
            name = f"{fmt.slug(settings.calendar(calendar_id).name)}.ics" if calendar_id else "agendas.zip"
            chosen, _ = QFileDialog.getSaveFileName(self, "Exporter", str(Path.home() / name),
                                                    "iCalendar (*.ics)" if calendar_id else "Archive (*.zip)")
            if not chosen:
                return None
            target = Path(chosen)
        try:
            if calendar_id:
                result = export_calendar(self.data.sources.events, settings, calendar_id, now, notes=notes, activities=acts)
                target.write_text(result.text, encoding="utf-8", newline="")
                warnings = result.warnings
                text = plural(result.events, "événement exporté", "événements exportés")
            else:
                data, warnings = export_all(self.data.sources.events, settings, now, notes=notes, activities=acts)
                target.write_bytes(data)
                text = "Agendas exportés"
        except OSError as error:
            QMessageBox.warning(self, "Export", f"Export impossible : {error}")
            return None
        if warnings:
            text += " · " + plural(len(warnings), "avertissement", "avertissements")
        self.message.emit(text, None)
        return target

    # ---- settings ----

    def apply_prefs(self, prefs: AgendaPrefs) -> None:
        self.prefs = prefs
        self._rendered = None
        self.render()


def _now_ms() -> int:
    return int(datetime.now(UTC).timestamp() * 1000)
