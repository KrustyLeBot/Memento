from __future__ import annotations

from PySide6.QtCore import Qt, Signal
from PySide6.QtWidgets import (QButtonGroup, QCheckBox, QComboBox, QFrame, QHBoxLayout, QLabel, QPushButton, QScrollArea,
                               QToolButton, QVBoxLayout, QWidget)

from ..core.agenda.frdates import alert_label, all_day_alert_label
from ..core.settings import RELIEFS, ActivitySettings
from ..core.track import Sport
from ..services import updater
from . import icons, theme
from .rail import HIDEABLE, MODULES


class SettingsScreen(QWidget):
    back_requested = Signal()
    choose_folder = Signal()
    grid_changed = Signal(bool)
    theme_changed = Signal(str)
    check_updates = Signal()
    activity_changed = Signal(object)  # ActivitySettings
    module_visibility = Signal(str, bool)
    contacts_order = Signal(bool)  # True: first name first
    agenda_changed = Signal(dict)  # one key of: week_start, event_zone_times, default_alerts, default_calendar, birthdays, birthday_alerts

    def __init__(self):
        super().__init__()
        outer = QVBoxLayout(self)
        outer.setContentsMargins(8, 8, 8, 0)
        top = QHBoxLayout()
        self.back = QToolButton()
        self.back.clicked.connect(self.back_requested)
        title = QLabel("Paramètres")
        title.setStyleSheet("font-size: 16pt; font-weight: 600;")
        top.addWidget(self.back)
        top.addWidget(title)
        top.addStretch()
        outer.addLayout(top)

        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        host = QWidget()
        row = QHBoxLayout(host)
        column = QWidget()
        column.setMaximumWidth(720)
        body = QVBoxLayout(column)
        body.setContentsMargins(16, 8, 16, 24)
        body.setSpacing(6)
        row.addStretch(1)
        row.addWidget(column, 100)
        row.addStretch(1)
        scroll.setWidget(host)
        outer.addWidget(scroll, 1)

        self._sections: list[QLabel] = []
        self._loading = False

        def section(text: str) -> None:
            label = QLabel(text)
            body.addSpacing(10)
            body.addWidget(label)
            self._sections.append(label)

        section("Stockage")
        body.addWidget(QLabel("Dossier des notes"))
        self.folder = QLabel("—")
        self.folder.setWordWrap(True)
        self.folder.setTextInteractionFlags(Qt.TextInteractionFlag.TextSelectableByMouse)
        body.addWidget(self.folder)
        pick = QPushButton("Choisir un dossier")
        pick.clicked.connect(self.choose_folder)
        body.addWidget(pick, 0, Qt.AlignmentFlag.AlignLeft)

        section("Modules")
        body.addWidget(QLabel("Les modules masqués disparaissent du menu ; leurs fichiers restent dans le dossier."))
        self.module_boxes = {}
        modules_row = QHBoxLayout()
        for key in HIDEABLE:
            box = QCheckBox(MODULES[key][0])
            box.toggled.connect(lambda on, k=key: None if self._loading else self.module_visibility.emit(k, on))
            self.module_boxes[key] = box
            modules_row.addWidget(box)
        modules_row.addStretch()
        body.addLayout(modules_row)

        section("Affichage")
        self.grid_button = self._chip("Grille")
        self.list_button = self._chip("Liste")
        group = QButtonGroup(self)
        group.setExclusive(True)
        row1 = QHBoxLayout()
        for b in (self.grid_button, self.list_button):
            group.addButton(b)
            row1.addWidget(b)
        row1.addStretch()
        body.addLayout(row1)
        self.grid_button.clicked.connect(lambda: self.grid_changed.emit(True))
        self.list_button.clicked.connect(lambda: self.grid_changed.emit(False))

        body.addWidget(QLabel("Thème"))
        self.theme_buttons = {"system": self._chip("Système"), "light": self._chip("Clair"), "dark": self._chip("Sombre")}
        theme_group = QButtonGroup(self)
        theme_group.setExclusive(True)
        row2 = QHBoxLayout()
        for mode, b in self.theme_buttons.items():
            theme_group.addButton(b)
            row2.addWidget(b)
            b.clicked.connect(lambda _=False, m=mode: self.theme_changed.emit(m))
        row2.addStretch()
        body.addLayout(row2)

        section("Activités (synchronisé avec le téléphone)")
        body.addWidget(QLabel("Sports comptés dans les totaux"))
        self.total_boxes = {}
        sports_row = QHBoxLayout()
        for sport in Sport:
            box = QCheckBox(sport.label.split(" (")[0] + (" (GPS)" if sport == Sport.GPS else " (sans GPS)" if sport == Sport.PLAIN else ""))
            box.toggled.connect(lambda _=False: self._activity_edited())
            self.total_boxes[sport] = box
            sports_row.addWidget(box)
        sports_row.addStretch()
        body.addLayout(sports_row)
        self.refine_box = QCheckBox("Affiner l'altitude avec le relief à l'enregistrement (téléphone)")
        self.refine_box.toggled.connect(lambda _=False: self._activity_edited())
        body.addWidget(self.refine_box)
        body.addWidget(QLabel("Relief de la carte"))
        self.relief_buttons = {key: self._chip(label) for key, label in RELIEFS.items()}
        relief_group = QButtonGroup(self)
        relief_group.setExclusive(True)
        relief_row = QHBoxLayout()
        for b in self.relief_buttons.values():
            relief_group.addButton(b)
            relief_row.addWidget(b)
            b.clicked.connect(lambda _=False: self._activity_edited())
        relief_row.addStretch()
        body.addLayout(relief_row)
        relief_hint = QLabel("AWS : le relief actuel, environ 30 m en France. Mapterhorn : le RGE ALTI de l'IGN (5 m partout "
                             "en France, 0,5 à 1 m là où le LiDAR HD est passé), plus fin et plus lourd.")
        relief_hint.setWordWrap(True)
        body.addWidget(relief_hint)
        self.buildings_box = QCheckBox("Bâtiments en 3D (en ligne seulement, jamais dans les cartes hors ligne)")
        self.buildings_box.toggled.connect(lambda _=False: self._activity_edited())
        body.addWidget(self.buildings_box)

        section("Contacts")
        self.first_name_box = QCheckBox("Prénom d'abord (trie et regroupe la liste par prénom plutôt que par nom)")
        self.first_name_box.toggled.connect(lambda on: None if self._loading else self.contacts_order.emit(on))
        body.addWidget(self.first_name_box)

        section("Agenda")
        body.addWidget(QLabel("Premier jour de la semaine"))
        self.week_buttons = {0: self._chip("Lundi"), 6: self._chip("Dimanche")}
        week_group = QButtonGroup(self)
        week_group.setExclusive(True)
        week_row = QHBoxLayout()
        for value, b in self.week_buttons.items():
            week_group.addButton(b)
            week_row.addWidget(b)
            b.clicked.connect(lambda _=False, v=value: self._agenda({"week_start": v}))
        week_row.addStretch()
        body.addLayout(week_row)
        self.event_zone_box = QCheckBox("Afficher les heures dans le fuseau de l'événement (sinon celui de ce PC)")
        self.event_zone_box.toggled.connect(lambda on: self._agenda({"event_zone_times": on}))
        body.addWidget(self.event_zone_box)
        body.addWidget(QLabel("Agenda par défaut des nouveaux événements (synchronisé)"))
        self.default_calendar = QComboBox()
        self.default_calendar.activated.connect(
            lambda i: self._agenda({"default_calendar": self.default_calendar.itemData(i)}))
        body.addWidget(self.default_calendar, 0, Qt.AlignmentFlag.AlignLeft)
        body.addWidget(QLabel("Rappels par défaut des nouveaux événements et des imports .ics, avant le début (synchronisé, "
                              "ils sonnent sur le téléphone)"))
        self.alert_buttons: dict[int, QPushButton] = {}
        alerts_row = QHBoxLayout()
        for minutes in (0, -5, -10, -15, -30, -60, -1440, -10080):
            b = self._chip(alert_label(minutes).removesuffix(" avant"))
            b.setMinimumWidth(b.sizeHint().width())
            b.toggled.connect(lambda _=False: self._agenda({"default_alerts": self._alerts()}))
            self.alert_buttons[minutes] = b
            alerts_row.addWidget(b)
        alerts_row.addStretch()
        body.addLayout(alerts_row)
        self.birthdays_box = QCheckBox("Afficher les anniversaires des contacts (synchronisé)")
        self.birthdays_box.toggled.connect(lambda on: self._agenda({"birthdays": on}))
        body.addWidget(self.birthdays_box)
        body.addWidget(QLabel("Rappels des anniversaires de tous les contacts, déjà là ou futurs (synchronisé, ils sonnent "
                              "sur le téléphone ; aucun si les anniversaires sont masqués)"))
        self.birthday_alert_buttons: dict[int, QPushButton] = {}
        birthday_row = QHBoxLayout()
        for minutes in (540, -360, -900, -2340, -9540):
            b = self._chip(all_day_alert_label(minutes))
            b.setMinimumWidth(b.sizeHint().width())
            b.toggled.connect(lambda _=False: self._agenda({"birthday_alerts": self._birthday_alerts()}))
            self.birthday_alert_buttons[minutes] = b
            birthday_row.addWidget(b)
        birthday_row.addStretch()
        body.addLayout(birthday_row)

        section("À propos")
        self.version = QLabel(f"Mémento build {updater.current_build()}")
        body.addWidget(self.version)
        body.addWidget(QLabel("Mise à jour"))
        self.update_status = QLabel("Appuyez pour vérifier")
        body.addWidget(self.update_status)
        check = QPushButton("Vérifier")
        check.clicked.connect(self.check_updates)
        body.addWidget(check, 0, Qt.AlignmentFlag.AlignLeft)
        body.addStretch()
        self.restyle()

    @staticmethod
    def _chip(text: str) -> QPushButton:
        b = QPushButton(text)
        b.setObjectName("chip")
        b.setCheckable(True)
        b.setCursor(Qt.CursorShape.PointingHandCursor)
        return b

    def restyle(self) -> None:
        s = theme.scheme()
        self.back.setIcon(icons.icon("back", s.on_surface, 24))
        for label in self._sections:
            label.setStyleSheet(f"color: {s.primary}; font-weight: 600;")
        self.folder.setStyleSheet(f"color: {s.on_surface_variant};")
        self.update_status.setStyleSheet(f"color: {s.on_surface_variant};")

    def _activity_edited(self) -> None:
        if self._loading:
            return
        relief = next((k for k, b in self.relief_buttons.items() if b.isChecked()), "aws")
        self.activity_changed.emit(ActivitySettings(self.refine_box.isChecked(),
                                                    {s for s, b in self.total_boxes.items() if b.isChecked()}, relief,
                                                    self.buildings_box.isChecked()))

    def _alerts(self) -> list[int]:
        return sorted(m for m, b in self.alert_buttons.items() if b.isChecked())

    def _birthday_alerts(self) -> list[int]:
        return sorted(m for m, b in self.birthday_alert_buttons.items() if b.isChecked())

    def _agenda(self, change: dict) -> None:
        if not self._loading:
            self.agenda_changed.emit(change)

    def load_agenda(self, prefs, calendars) -> None:
        """prefs: AgendaPrefs (this PC); calendars: CalendarSettings (agenda/calendars.json)."""
        self._loading = True
        self.week_buttons.get(prefs.week_start, self.week_buttons[0]).setChecked(True)
        self.event_zone_box.setChecked(prefs.event_zone_times)
        for minutes, b in self.alert_buttons.items():
            b.setChecked(minutes in calendars.default_alerts(False))
        self.default_calendar.clear()
        for cal in calendars.calendars:
            self.default_calendar.addItem(cal.name, cal.id)
        self.default_calendar.setCurrentIndex(max(0, self.default_calendar.findData(calendars.default_calendar().id)))
        self.birthdays_box.setChecked(calendars.layer("birthdays").visible)
        for minutes, b in self.birthday_alert_buttons.items():
            b.setChecked(minutes in calendars.birthday_alerts())
        self._loading = False

    def set_contacts_first_name_first(self, value: bool) -> None:
        self._loading = True
        self.first_name_box.setChecked(value)
        self._loading = False

    def load(self, folder: str, grid: bool, theme_mode: str, activity: ActivitySettings | None = None,
             hidden: set[str] | None = None) -> None:
        self.folder.setText(folder)
        self._loading = True
        for key, box in self.module_boxes.items():
            box.setChecked(key not in (hidden or set()))
        activity = activity or ActivitySettings()
        for sport, box in self.total_boxes.items():
            box.setChecked(sport in activity.totals)
        self.refine_box.setChecked(activity.refine_altitude)
        self.relief_buttons.get(activity.relief, self.relief_buttons["aws"]).setChecked(True)
        self.buildings_box.setChecked(activity.buildings)
        self._loading = False
        (self.grid_button if grid else self.list_button).setChecked(True)
        self.theme_buttons.get(theme_mode, self.theme_buttons["system"]).setChecked(True)
        self.restyle()
