from __future__ import annotations

from PySide6.QtCore import Qt, Signal
from PySide6.QtWidgets import (QButtonGroup, QCheckBox, QFrame, QHBoxLayout, QLabel, QPushButton, QScrollArea, QToolButton,
                               QVBoxLayout, QWidget)

from ..core.settings import ActivitySettings
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

        section("À propos")
        self.version = QLabel(f"Scriptorium build {updater.current_build()}")
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
        self.activity_changed.emit(ActivitySettings(self.refine_box.isChecked(),
                                                    {s for s, b in self.total_boxes.items() if b.isChecked()}))

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
        self._loading = False
        (self.grid_button if grid else self.list_button).setChecked(True)
        self.theme_buttons.get(theme_mode, self.theme_buttons["system"]).setChecked(True)
        self.restyle()
