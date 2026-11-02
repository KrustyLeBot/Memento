"""Event editor, its custom recurrence editor, the event detail card, the series scope question and the
"Ajouter à l'agenda" dialog of dated notes.

The editor edits a copy of an `Event` and hands back a new one (`EventDialog.build`); writing, scopes and undo
are the screen's job. The description is the note body: edited in place with the notes' rich text when it is
plain text, kept untouched (and editable with "Ouvrir comme note") when it holds a checklist or attachments.
"""
from __future__ import annotations

from datetime import date, datetime, time, timedelta
from urllib.parse import quote
from zoneinfo import available_timezones

from PySide6.QtCore import QDate, QLocale, QStringListModel, QTime, Qt, QUrl, Signal
from PySide6.QtGui import QColor, QDesktopServices, QIcon, QPainter, QPixmap
from PySide6.QtWidgets import (QButtonGroup, QCheckBox, QComboBox, QCompleter, QDateEdit, QDialog, QDialogButtonBox,
                               QFormLayout, QFrame, QGridLayout, QHBoxLayout, QLabel, QLineEdit, QMenu, QMessageBox,
                               QPushButton, QRadioButton, QScrollArea, QSpinBox, QTextBrowser, QTimeEdit, QToolButton,
                               QVBoxLayout, QWidget)

from ..core import note as fmt
from ..core.agenda import rules, zones
from ..core.agenda.calendars import CalendarSettings
from ..core.agenda.entries import Entry, EntryKind
from ..core.agenda.frdates import (MONTHS, WEEKDAYS, alert_label, clock, day_number, departure_arrival, long_date,
                                   short_date)
from ..core.agenda.model import Event, is_all_day, stamp_date
from . import icons, theme
from .agenda_views import uses_24h, wall
from .editor_view import RichBody

ALERT_PRESETS = [-5, -10, -15, -30, -60, -1440, -10080]
FLOATING = "Celui de l'appareil (flottant)"


_ICONS: dict[tuple[str, int], QIcon] = {}


def color_icon(color: str, size: int = 14) -> QIcon:
    key = (color, size)
    if key not in _ICONS:
        _ICONS[key] = _dot(color, size)
    return _ICONS[key]


def _dot(color: str, size: int) -> QIcon:
    pixmap = QPixmap(size * 2, size * 2)
    pixmap.fill(Qt.GlobalColor.transparent)
    p = QPainter(pixmap)
    p.setRenderHint(QPainter.RenderHint.Antialiasing)
    p.setBrush(QColor(color))
    p.setPen(Qt.PenStyle.NoPen)
    p.drawEllipse(2, 2, size * 2 - 4, size * 2 - 4)
    p.end()
    pixmap.setDevicePixelRatio(2)
    return QIcon(pixmap)


def input_style() -> str:
    """Fields with a visible frame on both themes (the window palette makes them blend in the dark one)."""
    s = theme.scheme()
    return (f"QLineEdit, QComboBox, QDateEdit, QTimeEdit, QSpinBox {{ background: {s.surface_container}; "
            f"border: 1px solid {s.outline_variant}; border-radius: 6px; padding: 4px 6px; color: {s.on_surface}; }}"
            f"QLineEdit:focus, QComboBox:focus, QDateEdit:focus, QTimeEdit:focus, QSpinBox:focus {{ border-color: {s.primary}; }}"
            f"QCheckBox::indicator, QRadioButton::indicator {{ width: 16px; height: 16px; border: 2px solid {s.outline}; "
            f"background: transparent; }}"
            f"QCheckBox::indicator {{ border-radius: 4px; }} QRadioButton::indicator {{ border-radius: 10px; }}"
            f"QCheckBox::indicator:checked, QRadioButton::indicator:checked {{ background: {s.primary}; border-color: {s.primary}; }}")


def city(tz_name: str) -> str:
    return tz_name.rsplit("/", 1)[-1].replace("_", " ")


def to_qdate(d: date) -> QDate:
    return QDate(d.year, d.month, d.day)


def from_qdate(q: QDate) -> date:
    return date(q.year(), q.month(), q.day())


def ask_scope(parent, verb: str) -> str | None:
    """"Modifier" / "Supprimer" an occurrence of a series: "one", "all" or None (cancelled)."""
    box = QMessageBox(parent)
    box.setWindowTitle(f"{verb} un événement récurrent")
    box.setText(f"{verb} :")
    one = box.addButton("Cet événement", QMessageBox.ButtonRole.AcceptRole)
    every = box.addButton("Tous les événements", QMessageBox.ButtonRole.AcceptRole)
    box.addButton("Annuler", QMessageBox.ButtonRole.RejectRole)
    box.setDefaultButton(one)
    box.exec()
    return {one: "one", every: "all"}.get(box.clickedButton())


# ---- small inputs ----

class ZoneBox(QComboBox):
    """Searchable IANA zone picker; the first item is "floating" (no zone)."""

    def __init__(self):
        super().__init__()
        self.setEditable(True)
        self.setInsertPolicy(QComboBox.InsertPolicy.NoInsert)
        names = sorted(n for n in available_timezones() if "/" in n and not n.startswith(("Etc/", "SystemV/", "posix/", "right/")))
        names += ["UTC"]
        self.addItem(FLOATING, None)
        for n in names:
            self.addItem(n.replace("_", " "), n)
        completer = QCompleter(QStringListModel([FLOATING] + [n.replace("_", " ") for n in names]), self)
        completer.setCaseSensitivity(Qt.CaseSensitivity.CaseInsensitive)
        completer.setFilterMode(Qt.MatchFlag.MatchContains)
        self.setCompleter(completer)
        self.setMaxVisibleItems(16)

    def set_zone(self, name: str | None) -> None:
        index = self.findData(name) if name else 0
        if index < 0:
            self.addItem(name, name)
            index = self.count() - 1
        self.setCurrentIndex(index)

    def zone(self) -> str | None:
        text = self.currentText().strip()
        index = self.findText(text, Qt.MatchFlag.MatchFixedString)
        if index >= 0:
            return self.itemData(index)
        return self.currentData()


class CustomAlertDialog(QDialog):
    def __init__(self, parent):
        super().__init__(parent)
        self.setStyleSheet(input_style())
        self.setWindowTitle("Rappel personnalisé")
        row = QHBoxLayout()
        self.amount = QSpinBox()
        self.amount.setRange(0, 999)
        self.amount.setValue(2)
        self.unit = QComboBox()
        for label, size in (("minutes", 1), ("heures", 60), ("jours", 1440), ("semaines", 10080)):
            self.unit.addItem(label, size)
        self.unit.setCurrentIndex(1)
        self.when = QComboBox()
        self.when.addItem("avant", -1)
        self.when.addItem("après", 1)
        for w in (self.amount, self.unit, self.when):
            row.addWidget(w)
        layout = QVBoxLayout(self)
        layout.addLayout(row)
        buttons = QDialogButtonBox(QDialogButtonBox.StandardButton.Ok | QDialogButtonBox.StandardButton.Cancel)
        buttons.button(QDialogButtonBox.StandardButton.Ok).setText("Ajouter")
        buttons.button(QDialogButtonBox.StandardButton.Cancel).setText("Annuler")
        buttons.accepted.connect(self.accept)
        buttons.rejected.connect(self.reject)
        layout.addWidget(buttons)

    def minutes(self) -> int:
        return self.amount.value() * self.unit.currentData() * self.when.currentData()


class AlertsEditor(QWidget):
    """The reminders of an event as removable chips, and "Ajouter un rappel"."""
    changed = Signal()

    def __init__(self, values: list[int]):
        super().__init__()
        self.values = sorted(set(values))
        self.row = QHBoxLayout(self)
        self.row.setContentsMargins(0, 0, 0, 0)
        self.row.setSpacing(6)
        self.add = QToolButton()
        self.add.setText("Ajouter un rappel")
        self.add.setToolButtonStyle(Qt.ToolButtonStyle.ToolButtonTextBesideIcon)
        self.add.setPopupMode(QToolButton.ToolButtonPopupMode.InstantPopup)
        self.add.setIcon(icons.icon("add", theme.scheme().primary, 18))
        self.add.setStyleSheet(f"QToolButton {{ color: {theme.scheme().primary}; padding: 4px 10px; border-radius: 14px; }}"
                               "QToolButton::menu-indicator { image: none; width: 0; }")
        menu = QMenu(self.add)
        for minutes in ALERT_PRESETS:
            menu.addAction(alert_label(minutes), lambda m=minutes: self.add_value(m))
        menu.addSeparator()
        menu.addAction("Personnalisé…", self._custom)
        self.add.setMenu(menu)
        self.rebuild()

    def _custom(self) -> None:
        dialog = CustomAlertDialog(self)
        if dialog.exec() == QDialog.DialogCode.Accepted:
            self.add_value(dialog.minutes())

    def add_value(self, minutes: int) -> None:
        if minutes not in self.values:
            self.values = sorted(self.values + [minutes])
            self.rebuild()
            self.changed.emit()

    def remove_value(self, minutes: int) -> None:
        self.values = [v for v in self.values if v != minutes]
        self.rebuild()
        self.changed.emit()

    def rebuild(self) -> None:
        while self.row.count():
            item = self.row.takeAt(0)
            if item.widget() and item.widget() is not self.add:
                item.widget().deleteLater()
        s = theme.scheme()
        for minutes in self.values:
            chip = QPushButton(alert_label(minutes))
            chip.setObjectName("chip")
            chip.setIcon(icons.icon("close", s.on_surface, 14))
            chip.setLayoutDirection(Qt.LayoutDirection.RightToLeft)
            chip.setToolTip("Retirer ce rappel")
            chip.clicked.connect(lambda _=False, m=minutes: self.remove_value(m))
            self.row.addWidget(chip)
        self.row.addWidget(self.add)
        self.row.addStretch()


def date_edit(d: date) -> QDateEdit:
    edit = QDateEdit(to_qdate(d))
    edit.setCalendarPopup(True)
    edit.setDisplayFormat("ddd d MMM yyyy")
    edit.setLocale(french_locale())
    return edit


def time_edit(t: time) -> QTimeEdit:
    edit = QTimeEdit(QTime(t.hour, t.minute))
    edit.setDisplayFormat("HH:mm" if uses_24h() else "h:mm AP")
    return edit


def french_locale() -> QLocale:
    return QLocale(QLocale.Language.French, QLocale.Country.France)


# ---- custom recurrence ----

class RecurrenceDialog(QDialog):
    """"Personnalisé…": every N days/weeks/months/years, weekdays, day of month or n-th weekday, end."""
    UNITS = [("DAILY", "jour(s)"), ("WEEKLY", "semaine(s)"), ("MONTHLY", "mois"), ("YEARLY", "an(s)")]

    def __init__(self, parent, spec: rules.RuleSpec, start: date, tz_name: str | None, device_zone: str,
                 today: date, exdate: list[date] | None = None):
        super().__init__(parent)
        self.setStyleSheet(input_style())
        self.setWindowTitle("Répétition personnalisée")
        self.setMinimumWidth(460)
        self.start, self.tz_name, self.device_zone, self.today = start, tz_name, device_zone, today
        self.exdate = exdate or []
        d = stamp_date(start)
        layout = QVBoxLayout(self)

        every = QHBoxLayout()
        every.addWidget(QLabel("Tous les"))
        self.interval = QSpinBox()
        self.interval.setRange(1, 999)
        self.interval.setMinimumWidth(64)
        every.addWidget(self.interval)
        self.unit = QComboBox()
        for freq, label in self.UNITS:
            self.unit.addItem(label, freq)
        every.addWidget(self.unit)
        every.addStretch()
        layout.addLayout(every)

        self.week_row = QWidget()
        wr = QHBoxLayout(self.week_row)
        wr.setContentsMargins(0, 0, 0, 0)
        wr.addWidget(QLabel("Le"))
        self.day_buttons: list[QPushButton] = []
        for i, name in enumerate(WEEKDAYS):
            b = QPushButton(name[0].upper())
            b.setObjectName("chip")
            b.setCheckable(True)
            b.setToolTip(name)
            b.setFixedWidth(40)
            b.toggled.connect(self._update)
            self.day_buttons.append(b)
            wr.addWidget(b)
        wr.addStretch()
        layout.addWidget(self.week_row)

        self.month_box = QWidget()
        mb = QGridLayout(self.month_box)
        mb.setContentsMargins(0, 0, 0, 0)
        self.by_day = QRadioButton("Le")
        self.month_day = QSpinBox()
        self.month_day.setRange(1, 31)
        self.by_nth = QRadioButton("Le")
        self.nth = QComboBox()
        for n in (1, 2, 3, 4, 5, -1):
            self.nth.addItem({1: "1er", 2: "2e", 3: "3e", 4: "4e", 5: "5e", -1: "dernier"}[n], n)
        self.nth_weekday = QComboBox()
        self.nth_weekday.addItems(WEEKDAYS)
        mb.addWidget(self.by_day, 0, 0)
        mb.addWidget(self.month_day, 0, 1)
        mb.addWidget(QLabel("du mois"), 0, 2)
        mb.addWidget(self.by_nth, 1, 0)
        mb.addWidget(self.nth, 1, 1)
        mb.addWidget(self.nth_weekday, 1, 2)
        mb.setColumnStretch(3, 1)
        layout.addWidget(self.month_box)
        self.year_label = QLabel(f"Le {day_number(d)} {MONTHS[d.month - 1]} (date du début)")
        layout.addWidget(self.year_label)

        end_title = QLabel("Fin")
        end_title.setStyleSheet("font-weight: 600;")
        layout.addSpacing(6)
        layout.addWidget(end_title)
        self.end_never = QRadioButton("Jamais")
        self.end_until = QRadioButton("Le")
        self.until = date_edit(d + timedelta(days=90))
        self.end_count = QRadioButton("Après")
        self.count = QSpinBox()
        self.count.setRange(1, 9999)
        self.count.setValue(10)
        group = QButtonGroup(self)
        for b in (self.end_never, self.end_until, self.end_count):
            group.addButton(b)
        end = QGridLayout()
        end.addWidget(self.end_never, 0, 0)
        end.addWidget(self.end_until, 1, 0)
        end.addWidget(self.until, 1, 1)
        end.addWidget(self.end_count, 2, 0)
        count_row = QHBoxLayout()
        count_row.addWidget(self.count)
        count_row.addWidget(QLabel("occurrences"))
        count_row.addStretch()
        end.addLayout(count_row, 2, 1)
        end.setColumnStretch(2, 1)
        layout.addLayout(end)

        self.summary = QLabel()
        self.summary.setWordWrap(True)
        self.preview = QLabel()
        self.preview.setWordWrap(True)
        self.preview.setStyleSheet(f"color: {theme.scheme().on_surface_variant};")
        layout.addSpacing(6)
        layout.addWidget(self.summary)
        layout.addWidget(self.preview)
        buttons = QDialogButtonBox(QDialogButtonBox.StandardButton.Ok | QDialogButtonBox.StandardButton.Cancel)
        buttons.button(QDialogButtonBox.StandardButton.Ok).setText("OK")
        buttons.button(QDialogButtonBox.StandardButton.Cancel).setText("Annuler")
        buttons.accepted.connect(self.accept)
        buttons.rejected.connect(self.reject)
        layout.addWidget(buttons)

        self.set_spec(spec)
        for w in (self.interval, self.month_day, self.count):
            w.valueChanged.connect(self._update)
        for w in (self.unit, self.nth, self.nth_weekday):
            w.currentIndexChanged.connect(self._update)
        for w in (self.by_day, self.by_nth, self.end_never, self.end_until, self.end_count):
            w.toggled.connect(self._update)
        self.until.dateChanged.connect(self._update)
        self._update()

    def set_spec(self, spec: rules.RuleSpec) -> None:
        self.interval.setValue(spec.interval)
        self.unit.setCurrentIndex([f for f, _ in self.UNITS].index(spec.freq))
        for i, b in enumerate(self.day_buttons):
            b.setChecked(i in spec.weekdays)
        self.month_day.setValue(spec.month_day)
        (self.by_nth if spec.monthly == "nth" else self.by_day).setChecked(True)
        self.nth.setCurrentIndex(max(0, self.nth.findData(spec.nth)))
        self.nth_weekday.setCurrentIndex(spec.nth_weekday)
        if spec.count is not None:
            self.end_count.setChecked(True)
            self.count.setValue(spec.count)
        elif spec.until is not None:
            self.end_until.setChecked(True)
            self.until.setDate(to_qdate(spec.until))
        else:
            self.end_never.setChecked(True)

    def spec(self) -> rules.RuleSpec:
        freq = self.unit.currentData()
        days = [i for i, b in enumerate(self.day_buttons) if b.isChecked()] or [stamp_date(self.start).weekday()]
        return rules.RuleSpec(
            freq=freq, interval=self.interval.value(), weekdays=days,
            monthly="nth" if self.by_nth.isChecked() else "day", month_day=self.month_day.value(),
            nth=self.nth.currentData(), nth_weekday=self.nth_weekday.currentIndex(),
            until=from_qdate(self.until.date()) if self.end_until.isChecked() else None,
            count=self.count.value() if self.end_count.isChecked() else None)

    def rrule(self) -> str:
        return rules.to_rrule(self.spec(), self.start, self.tz_name, self.device_zone)

    def _update(self, *_) -> None:
        freq = self.unit.currentData()
        self.week_row.setVisible(freq == "WEEKLY")
        self.month_box.setVisible(freq == "MONTHLY")
        self.year_label.setVisible(freq == "YEARLY")
        self.until.setEnabled(self.end_until.isChecked())
        self.count.setEnabled(self.end_count.isChecked())
        spec = self.spec()
        self.summary.setText(rules.describe(spec, self.start, self.today))
        self.preview.setText(preview_text(self.rrule(), self.start, self.tz_name, self.device_zone, self.today, self.exdate))


def preview_text(rrule: str | None, start: date, tz_name: str | None, device_zone: str, today: date,
                 exdate: list[date] | None = None) -> str:
    if not rrule:
        return ""
    try:
        nxt = rules.next_starts(rrule, start, tz_name, device_zone, max(today, stamp_date(start)), exdate)
    except (ValueError, TypeError, OverflowError) as error:
        return f"Règle illisible : {error}"
    if not nxt:
        return "Aucune occurrence à venir"
    return "Prochaines : " + ", ".join(short_date(stamp_date(x), today) for x in nxt)


# ---- the event editor ----

class EventDialog(QDialog):
    """Create or edit an event. `scope`: None (a single event or a new one), "all" (the whole series: dates
    are those of its first occurrence) or "one" (an occurrence: no repetition)."""

    def __init__(self, parent, event: Event, settings: CalendarSettings, device_zone: str, today: date,
                 is_new: bool, scope: str | None = None):
        super().__init__(parent)
        self.setStyleSheet(input_style())
        self.source_event = event
        self.settings = settings
        self.device_zone = device_zone
        self.today = today
        self.scope = scope
        self.delete_requested = False
        self.setWindowTitle("Nouvel événement" if is_new else "Modifier l'événement")
        self.setMinimumWidth(620)
        s = theme.scheme()

        outer = QVBoxLayout(self)
        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        scroll.setFrameShape(QFrame.Shape.NoFrame)
        host = QWidget()
        column = QVBoxLayout(host)
        form = QFormLayout()
        column.addLayout(form)
        column.addStretch()  # rows keep their height when the dialog is taller than the form
        form.setLabelAlignment(Qt.AlignmentFlag.AlignRight | Qt.AlignmentFlag.AlignVCenter)
        form.setFieldGrowthPolicy(QFormLayout.FieldGrowthPolicy.AllNonFixedFieldsGrow)
        form.setVerticalSpacing(10)
        scroll.setWidget(host)
        outer.addWidget(scroll, 1)

        self.title = QLineEdit(event.title)
        self.title.setPlaceholderText("Ajouter un titre")
        self.title.setStyleSheet("font-size: 14pt; padding: 6px;")
        title_box = QVBoxLayout()
        title_box.setSpacing(2)
        title_box.addWidget(self.title)
        hint_text = ("Série : les dates sont celles de la première occurrence." if scope == "all" else
                     "Occurrence modifiée d'une série." if event.recurrence_of else
                     "Cette occurrence seulement." if scope == "one" else "")
        if hint_text:
            hint = QLabel(hint_text)
            hint.setStyleSheet(f"color: {s.on_surface_variant};")
            title_box.addWidget(hint)
        form.addRow("Titre", title_box)

        self.all_day = QCheckBox("Toute la journée")
        self.all_day.setChecked(event.all_day)
        form.addRow("", self.all_day)

        start, end = self._initial_range(event)
        self.start_date = date_edit(start.date())
        self.start_time = time_edit(start.time())
        self.end_date = date_edit(end.date())
        self.end_time = time_edit(end.time())
        self.start_date.setObjectName("startDate")
        start_row = QHBoxLayout()
        start_row.addWidget(self.start_date, 1)
        start_row.addWidget(self.start_time)
        end_row = QHBoxLayout()
        end_row.addWidget(self.end_date, 1)
        end_row.addWidget(self.end_time)
        form.addRow("Début", start_row)
        form.addRow("Fin", end_row)

        self.zone = ZoneBox()
        self.zone.set_zone(event.tz if event.tz or not is_new else device_zone)
        self.zone_row = QWidget()
        zr = QHBoxLayout(self.zone_row)
        zr.setContentsMargins(0, 0, 0, 0)
        zr.addWidget(self.zone, 1)
        self.zone_link = QPushButton("Fuseau horaire…")
        self.zone_link.setStyleSheet("padding: 2px 8px;")
        self.zone_link.clicked.connect(self._show_zone)
        form.addRow("Fuseau horaire", self.zone_row)
        self.zone_label = form.labelForField(self.zone_row)
        self.zone_link_row = QWidget()
        link_row = QHBoxLayout(self.zone_link_row)
        link_row.setContentsMargins(0, 0, 0, 0)
        link_row.addWidget(self.zone_link)
        link_row.addStretch()
        form.addRow("", self.zone_link_row)
        # a flight: the end is a wall time of its own zone (end_tz), which needs a start zone too
        self.end_zone_check = QCheckBox("Fuseau d'arrivée différent")
        self.end_zone_check.setChecked(bool(event.end_tz) and not event.all_day)
        form.addRow("", self.end_zone_check)
        self.end_zone = ZoneBox()
        self.end_zone.set_zone(event.end_tz or event.tz or device_zone)
        form.addRow("Fuseau d'arrivée", self.end_zone)
        self.end_zone_label = form.labelForField(self.end_zone)
        differs = bool(event.tz) and event.tz != device_zone
        self._zone_visible = differs
        self._form = form

        self.calendar = QComboBox()
        for cal in settings.calendars:
            self.calendar.addItem(color_icon(cal.color), cal.name, cal.id)
        current = settings.calendar(event.calendar).id
        self.calendar.setCurrentIndex(max(0, self.calendar.findData(current)))
        form.addRow("Agenda", self.calendar)

        self.location = QLineEdit(event.location or "")
        self.location.setPlaceholderText("Ajouter un lieu")
        loc_row = QHBoxLayout()
        loc_row.addWidget(self.location, 1)
        self.map_button = QToolButton()
        self.map_button.setIcon(icons.icon("place", s.primary, 20))
        self.map_button.setToolTip("Ouvrir dans la carte (navigateur)")
        self.map_button.clicked.connect(lambda: open_map(self.location.text()))
        loc_row.addWidget(self.map_button)
        form.addRow("Lieu", loc_row)

        self.repeat = QComboBox()
        self.repeat.setObjectName("repeat")
        self.rrule = event.rrule
        self.repeat_preview = QLabel()
        self.repeat_preview.setStyleSheet(f"color: {s.on_surface_variant};")
        self.repeat_preview.setWordWrap(True)
        repeat_box = QVBoxLayout()
        repeat_box.setSpacing(4)
        repeat_box.addWidget(self.repeat)
        repeat_box.addWidget(self.repeat_preview)
        form.addRow("Répétition", repeat_box)
        self.repeat_label = form.labelForField(repeat_box)
        if scope == "one" or event.recurrence_of:
            self.repeat.setEnabled(False)

        self.alerts = AlertsEditor(event.alerts)
        alerts_box = QVBoxLayout()
        alerts_box.setSpacing(2)
        alerts_box.addWidget(self.alerts)
        note = QLabel("Les rappels sonnent sur le téléphone ; Windows n'envoie pas de notification.")
        note.setStyleSheet(f"color: {s.on_surface_variant}; font-size: 8.5pt;")
        alerts_box.addWidget(note)
        form.addRow("Rappels", alerts_box)

        parts = fmt.parse_body(event.body)
        self.rich_description = not parts.items and not event.note.attachments and not event.note.pdf
        if self.rich_description:
            self.description = RichBody()
            self.description.setMinimumHeight(120)
            self.description.set_markdown(event.body)
            self.description.setStyleSheet(
                f"QTextEdit {{ background: {s.surface_container}; border: 1px solid {s.outline_variant}; border-radius: 8px; padding: 6px; }}")
            desc_box = QVBoxLayout()
            tools = QHBoxLayout()
            tools.setSpacing(2)
            for name, tip, action in (("bold", "Gras", self.description.toggle_bold),
                                      ("italic", "Italique", self.description.toggle_italic),
                                      ("title", "Titre", self.description.cycle_heading),
                                      ("bullets", "Puces", self.description.toggle_bullet)):
                b = QToolButton()
                b.setIcon(icons.icon(name, s.on_surface_variant, 18))
                b.setToolTip(tip)
                b.setFocusPolicy(Qt.FocusPolicy.NoFocus)
                b.clicked.connect(lambda _=False, a=action: a())
                tools.addWidget(b)
            tools.addStretch()
            desc_box.addLayout(tools)
            desc_box.addWidget(self.description)
            form.addRow("Description", desc_box)
            self._body_touched = False
            self.description.textChanged.connect(self._touch_body)
        else:
            text = QLabel("La description contient une liste à cocher ou des pièces jointes : elle reste telle quelle "
                          "ici, modifie-la avec « Ouvrir comme note ».")
            text.setWordWrap(True)
            text.setStyleSheet(f"color: {s.on_surface_variant};")
            form.addRow("Description", text)

        self.error = QLabel()
        self.error.setStyleSheet(f"color: {s.error};")
        outer.addWidget(self.error)
        buttons = QDialogButtonBox(QDialogButtonBox.StandardButton.Save | QDialogButtonBox.StandardButton.Cancel)
        buttons.button(QDialogButtonBox.StandardButton.Save).setText("Enregistrer")
        buttons.button(QDialogButtonBox.StandardButton.Save).setObjectName("primary")
        buttons.button(QDialogButtonBox.StandardButton.Cancel).setText("Annuler")
        if not is_new:
            delete = buttons.addButton("Supprimer", QDialogButtonBox.ButtonRole.DestructiveRole)
            delete.setStyleSheet(f"color: {s.error};")
            delete.clicked.connect(self._delete)
        buttons.accepted.connect(self._accept)
        buttons.rejected.connect(self.reject)
        outer.addWidget(buttons)

        self.all_day.toggled.connect(self._all_day_changed)
        self.start_date.dateChanged.connect(self._start_changed)
        self.start_time.timeChanged.connect(self._start_changed)
        self.end_date.dateChanged.connect(self._end_changed)
        self.end_time.timeChanged.connect(self._end_changed)
        self.end_zone_check.toggled.connect(self._end_zone_toggled)
        self.repeat.activated.connect(self._repeat_chosen)
        self._duration = self._end_value() - self._start_value()
        self._fill_repeat()
        self._all_day_changed(event.all_day, initial=True)
        self.resize(660, 760)
        if is_new:
            self.title.setFocus()

    def _touch_body(self) -> None:
        self._body_touched = True

    # ---- dates ----

    @staticmethod
    def _initial_range(event: Event) -> tuple[datetime, datetime]:
        if event.all_day:
            start = datetime.combine(event.start, time(9, 0))
            last = event.end or event.start
            return start, datetime.combine(last, time(10, 0))
        end = event.end if event.end is not None else event.start + timedelta(hours=1)
        return event.start, end

    def _start_value(self) -> date:
        d = from_qdate(self.start_date.date())
        if self.all_day.isChecked():
            return d
        t = self.start_time.time()
        return datetime(d.year, d.month, d.day, t.hour(), t.minute())

    def _end_value(self) -> date:
        d = from_qdate(self.end_date.date())
        if self.all_day.isChecked():
            return d
        t = self.end_time.time()
        return datetime(d.year, d.month, d.day, t.hour(), t.minute())

    def _start_changed(self, *_) -> None:
        """The end follows the start, keeping the duration (whole days for all-day events)."""
        start = self._start_value()
        if self.all_day.isChecked():
            end = start + timedelta(days=max(0, self._duration.days))
        else:
            end = start + self._duration
        for w in (self.end_date, self.end_time):
            w.blockSignals(True)
        self.end_date.setDate(to_qdate(stamp_date(end)))
        if isinstance(end, datetime):
            self.end_time.setTime(QTime(end.hour, end.minute))
        for w in (self.end_date, self.end_time):
            w.blockSignals(False)
        self._fill_repeat(follow_start=True)

    def _end_changed(self, *_) -> None:
        delta = self._end_value() - self._start_value()
        if delta >= timedelta(0):
            self._duration = delta
        self.error.clear()

    def _all_day_changed(self, on: bool, initial: bool = False) -> None:
        self.start_time.setVisible(not on)
        self.end_time.setVisible(not on)
        self._duration = max(timedelta(0), self._end_value() - self._start_value())
        self._update_zone_row()
        if not initial:
            self._fill_repeat(follow_start=True)

    def _show_zone(self) -> None:
        self._zone_visible = True
        self._update_zone_row()

    def _end_zone_toggled(self, on: bool) -> None:
        if on and self.zone.zone() is None:
            self.zone.set_zone(self.device_zone)
        if on and self.end_zone.zone() is None:
            self.end_zone.set_zone(self.zone.zone())
        self.error.clear()
        self._update_zone_row()

    def _update_zone_row(self) -> None:
        timed = not self.all_day.isChecked()
        has_end = timed and self.end_zone_check.isChecked()
        show = timed and (self._zone_visible or has_end)
        self.zone_row.setVisible(show)
        if self.zone_label is not None:
            self.zone_label.setVisible(show)
            self.zone_label.setText("Fuseau de départ" if has_end else "Fuseau horaire")
        self.zone_link_row.setVisible(timed and not show)
        self.end_zone_check.setVisible(timed)
        self.end_zone.setVisible(has_end)
        if self.end_zone_label is not None:
            self.end_zone_label.setVisible(has_end)

    # ---- repetition ----

    def _tz(self) -> str | None:
        return None if self.all_day.isChecked() else self.zone.zone()

    def _end_tz(self) -> str | None:
        """The end zone written to the file: only on a timed event with a start zone, and when it differs from it."""
        tz, end_tz = self._tz(), self.end_zone.zone()
        if tz is None or not self.end_zone_check.isChecked() or end_tz in (None, tz):
            return None
        return end_tz

    def _fill_repeat(self, follow_start: bool = False) -> None:
        start = self._start_value()
        key = rules.preset_of(self.rrule, start, self._tz(), self.device_zone)
        if follow_start and getattr(self, "_preset", None) not in (None, "none", "custom", "imported"):
            keys = [k for k, _ in rules.presets(start)]
            wanted = self._preset if self._preset in keys else ("monthly_day" if self._preset.startswith("monthly") else self._preset)
            self.rrule = rules.preset_rule(wanted, start, self._tz(), self.device_zone)
            key = wanted
        self._preset = key
        self.repeat.clear()  # `activated` (user choices only) is what reacts, so refilling is silent
        for k, label in rules.presets(start):
            self.repeat.addItem(label, k)
        if key == "custom":
            self.repeat.addItem(rules.describe_rule(self.rrule, start, self._tz(), self.device_zone, self.today), "custom")
        elif key == "imported":
            self.repeat.addItem(f"Règle importée : {self.rrule}", "imported")
        self.repeat.addItem("Personnalisé…", "edit")
        index = self.repeat.findData(key)
        self.repeat.setCurrentIndex(max(0, index))
        self.repeat.setToolTip(self.rrule or "")
        self._update_preview()

    def _repeat_chosen(self, index: int) -> None:
        key = self.repeat.itemData(index)
        start = self._start_value()
        if key == "edit":
            spec = rules.parse_spec(self.rrule, start, self._tz(), self.device_zone) or \
                rules.default_spec("WEEKLY", start)
            dialog = RecurrenceDialog(self, spec, start, self._tz(), self.device_zone, self.today, self.source_event.exdate)
            if dialog.exec() == QDialog.DialogCode.Accepted:
                self.rrule = dialog.rrule()
        elif key in ("custom", "imported"):
            return
        else:
            self.rrule = rules.preset_rule(key, start, self._tz(), self.device_zone)
        self._fill_repeat()

    def _update_preview(self) -> None:
        text = preview_text(self.rrule, self._start_value(), self._tz(), self.device_zone, self.today, self.source_event.exdate)
        self.repeat_preview.setText(text)
        self.repeat_preview.setVisible(bool(text))

    def choose_preset(self, key: str) -> None:
        """Selects a preset as a click would (tests, keyboard)."""
        self._repeat_chosen(self.repeat.findData(key))

    # ---- result ----

    def _delete(self) -> None:
        self.delete_requested = True
        self.reject()

    def _accept(self) -> None:
        start, end = self._start_value(), self._end_value()
        tz, end_tz = self._tz(), self._end_tz()
        if end_tz and zones.is_valid(end_tz) and zones.is_valid(tz):
            if zones.to_instant(end, zones.zone(end_tz)) <= zones.to_instant(start, zones.zone(tz)):
                self.error.setText(f"L'arrivée (heure de {city(end_tz)}) doit être après le départ.")
                return
        elif end < start:
            self.error.setText("La fin est avant le début.")
            return
        self.accept()

    def build(self) -> Event:
        """The edited event (not saved)."""
        start, end = self._start_value(), self._end_value()
        if self.all_day.isChecked():
            end_value = end if end != start else None
        else:
            end_value = end
        body = self.source_event.body
        if self.rich_description and self._body_touched:
            body = self.description.markdown()
        note = self.source_event.note.copy(title=self.title.text().strip(), body=body)
        return self.source_event.copy(
            note=note, start=start, end=end_value, tz=self._tz(), end_tz=self._end_tz(), calendar=self.calendar.currentData(),
            location=self.location.text().strip() or None, alerts=list(self.alerts.values),
            rrule=None if self.scope == "one" or self.source_event.recurrence_of else self.rrule, warnings=[])


def open_map(text: str) -> None:
    if text.strip():
        QDesktopServices.openUrl(QUrl(f"https://www.openstreetmap.org/search?query={quote(text.strip())}"))


# ---- detail card ----

class EventDetailDialog(QDialog):
    """What a click on an event shows; `action` tells the screen what to do next: "edit", "delete", "note"
    (open as a note), "restore" (an edited occurrence back to the series'), or None."""

    def __init__(self, parent, entry: Entry, settings: CalendarSettings, device_zone: str, today: date,
                 event_zone: bool, series: Event | None = None):
        super().__init__(parent)
        self.setStyleSheet(input_style())
        self.action: str | None = None
        ev = entry.event
        s = theme.scheme()
        self.setWindowTitle("Événement" if entry.kind == EntryKind.EVENT else "Note datée")
        self.setMinimumWidth(480)
        layout = QVBoxLayout(self)
        layout.setSpacing(8)
        top = QHBoxLayout()
        dot = QLabel()
        dot.setPixmap(color_icon(entry.color, 9).pixmap(18, 18))
        top.addWidget(dot, 0, Qt.AlignmentFlag.AlignTop)
        title = QLabel(entry.title or "(Sans titre)")
        title.setWordWrap(True)
        title.setStyleSheet("font-size: 16pt; font-weight: 600;")
        title.setTextInteractionFlags(Qt.TextInteractionFlag.TextSelectableByMouse)
        top.addWidget(title, 1)
        layout.addLayout(top)

        tz = zones.zone(device_zone)
        lines: list[tuple[str, str]] = [("schedule", when_text(entry, tz, today, event_zone))]
        o = entry.occurrence
        flight = departure_arrival(o.start, o.end, ev.tz, ev.end_tz, uses_24h()) \
            if ev is not None and o is not None and not entry.all_day else None
        if flight:
            lines.append(("schedule", flight))
        elif ev is not None and ev.tz and not entry.all_day and isinstance(ev.start, datetime) and ev.tz != device_zone:
            o = entry.occurrence
            start = o.start if o is not None else ev.start
            lines.append(("schedule", f"{clock(start, uses_24h())} à {city(ev.tz)} ({ev.tz})"))
        rule_source = series or ev
        if rule_source is not None and rule_source.rrule:
            lines.append(("repeat", rules.describe_rule(rule_source.rrule, rule_source.start, rule_source.tz, device_zone, today)))
        elif ev is not None and ev.recurrence_of:
            lines.append(("repeat", "Occurrence modifiée d'une série"))
        if entry.kind == EntryKind.EVENT:
            cal = settings.calendar(ev.calendar if ev else None)
            lines.append(("event", cal.name))
        if ev is not None and ev.location:
            lines.append(("place", ev.location))
        if ev is not None and ev.alerts:
            lines.append(("alarm", ", ".join(alert_label(a) for a in sorted(ev.alerts))))
        if ev is not None and ev.status == "tentative":
            lines.append(("event", "Provisoire"))
        grid = QGridLayout()
        grid.setColumnStretch(1, 1)
        grid.setHorizontalSpacing(10)
        for r, (icon_name, text) in enumerate(lines):
            ic = QLabel()
            ic.setPixmap(icons.icon(icon_name, s.on_surface_variant, 18).pixmap(18, 18))
            grid.addWidget(ic, r, 0, Qt.AlignmentFlag.AlignTop)
            if icon_name == "place":
                label = QLabel(f'<a href="map">{_html(text)}</a>')
                label.linkActivated.connect(lambda _=None, t=text: open_map(t))
                label.setToolTip("Ouvrir dans la carte (navigateur)")
            else:
                label = QLabel(text)
                label.setTextInteractionFlags(Qt.TextInteractionFlag.TextSelectableByMouse)
            label.setWordWrap(True)
            grid.addWidget(label, r, 1)
        layout.addLayout(grid)
        if ev is not None and ev.body.strip():
            body = QTextBrowser()
            body.setOpenExternalLinks(True)
            body.setMarkdown(ev.body)
            body.setMinimumHeight(80)
            body.setMaximumHeight(260)
            body.setStyleSheet(f"QTextBrowser {{ background: {s.surface_container}; border: none; border-radius: 8px; padding: 6px; }}")
            layout.addWidget(body)
        if ev is not None and ev.warnings:
            warn = QLabel("\n".join(ev.warnings))
            warn.setWordWrap(True)
            warn.setStyleSheet(f"color: {s.error};")
            layout.addWidget(warn)

        buttons = QHBoxLayout()
        if entry.kind == EntryKind.EVENT:
            if ev is not None and ev.recurrence_of:
                restore = QPushButton("Rétablir l'original")
                restore.setToolTip("Supprime cette modification : l'occurrence reprend l'heure et le contenu de la série")
                restore.clicked.connect(lambda: self._finish("restore"))
                buttons.addWidget(restore)
            delete = QPushButton("Supprimer")
            delete.setStyleSheet(f"color: {s.error};")
            delete.clicked.connect(lambda: self._finish("delete"))
            buttons.addWidget(delete)
            as_note = QPushButton("Ouvrir comme note")
            as_note.setToolTip("La description dans l'éditeur de notes : listes, pièces jointes, dessins")
            as_note.clicked.connect(lambda: self._finish("note"))
            buttons.addWidget(as_note)
        buttons.addStretch()
        close = QPushButton("Fermer")
        close.clicked.connect(self.reject)
        buttons.addWidget(close)
        if entry.kind == EntryKind.EVENT:
            edit = QPushButton("Modifier")
            edit.setObjectName("primary")
            edit.setDefault(True)
            edit.clicked.connect(lambda: self._finish("edit"))
            buttons.addWidget(edit)
        layout.addLayout(buttons)

    def _finish(self, action: str) -> None:
        self.action = action
        self.accept()


def _html(text: str) -> str:
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def when_text(entry: Entry, tz, today: date, event_zone: bool) -> str:
    """"lundi 2 novembre 2026 · 14:30 – 15:15" (the date span for multi-day entries)."""
    h24 = uses_24h()
    if entry.all_day:
        if entry.start_date == entry.end_date:
            return f"{long_date(entry.start_date)} · Toute la journée"
        return f"Du {long_date(entry.start_date)} au {long_date(entry.end_date)}"
    start, end = wall(entry, tz, event_zone)
    if entry.end == entry.start:
        return f"{long_date(start.date())} · {clock(start, h24)}"
    if start.date() == end.date():
        return f"{long_date(start.date())} · {clock(start, h24)} – {clock(end, h24)}"
    return f"Du {long_date(start.date())}, {clock(start, h24)} au {long_date(end.date())}, {clock(end, h24)}"


# ---- dated notes ----

class DateNoteDialog(QDialog):
    """"Ajouter à l'agenda" from the note editor: a date, an optional time, an optional reminder. On an already
    dated note it also offers "Retirer de l'agenda" (`removed`)."""
    REMINDERS = [(None, "Aucun rappel"), (0, "À l'heure"), (-15, "15 min avant"), (-60, "1 h avant"), (-1440, "1 jour avant")]

    def __init__(self, parent, current: Event | None, today: date):
        super().__init__(parent)
        self.setStyleSheet(input_style())
        self.removed = False
        self.setWindowTitle("Ajouter à l'agenda" if current is None else "Date de la note")
        layout = QVBoxLayout(self)
        form = QFormLayout()
        start = current.start if current is not None else today
        self.day = date_edit(stamp_date(start))
        form.addRow("Date", self.day)
        self.has_time = QCheckBox("À une heure précise")
        self.has_time.setChecked(current is not None and not is_all_day(current.start))
        self.time = time_edit(start.time() if isinstance(start, datetime) else time(9, 0))
        time_row = QHBoxLayout()
        time_row.addWidget(self.has_time)
        time_row.addWidget(self.time)
        time_row.addStretch()
        form.addRow("Heure", time_row)
        self.reminder = QComboBox()
        for value, label in self.REMINDERS:
            self.reminder.addItem(label, value)
        if current is not None and current.alerts:
            index = self.reminder.findData(current.alerts[0])
            if index < 0:
                self.reminder.addItem(alert_label(current.alerts[0]), current.alerts[0])
                index = self.reminder.count() - 1
            self.reminder.setCurrentIndex(index)
        form.addRow("Rappel", self.reminder)
        layout.addLayout(form)
        hint = QLabel("La note apparaît dans l'agenda (calque Notes) ; le rappel sonne sur le téléphone.")
        hint.setWordWrap(True)
        hint.setStyleSheet(f"color: {theme.scheme().on_surface_variant};")
        layout.addWidget(hint)
        buttons = QDialogButtonBox(QDialogButtonBox.StandardButton.Ok | QDialogButtonBox.StandardButton.Cancel)
        buttons.button(QDialogButtonBox.StandardButton.Ok).setText("Enregistrer")
        buttons.button(QDialogButtonBox.StandardButton.Cancel).setText("Annuler")
        if current is not None:
            remove = buttons.addButton("Retirer de l'agenda", QDialogButtonBox.ButtonRole.DestructiveRole)
            remove.clicked.connect(self._remove)
        buttons.accepted.connect(self.accept)
        buttons.rejected.connect(self.reject)
        layout.addWidget(buttons)
        self.has_time.toggled.connect(self.time.setEnabled)
        self.time.setEnabled(self.has_time.isChecked())

    def _remove(self) -> None:
        self.removed = True
        self.accept()

    def start(self) -> date:
        d = from_qdate(self.day.date())
        if not self.has_time.isChecked():
            return d
        t = self.time.time()
        return datetime(d.year, d.month, d.day, t.hour(), t.minute())

    def alerts(self) -> list[int]:
        value = self.reminder.currentData()
        return [] if value is None else [value]
