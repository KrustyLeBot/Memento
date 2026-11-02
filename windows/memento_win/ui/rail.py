"""Left navigation rail between the modules (Notes, Agenda, Activités, Contacts) and the settings."""
from __future__ import annotations

from PySide6.QtCore import QSize, Qt, Signal
from PySide6.QtWidgets import QButtonGroup, QFrame, QToolButton, QVBoxLayout

from . import icons, theme

# key -> (label, icon); the same order as the bottom bar of the phone
MODULES = {
    "notes": ("Notes", "edit_note"),
    "agenda": ("Agenda", "calendar"),
    "activities": ("Activités", "run"),
    "contacts": ("Contacts", "person"),
}
HIDEABLE = ("agenda", "activities", "contacts")


class Rail(QFrame):
    selected = Signal(str)
    open_settings = Signal()

    def __init__(self):
        super().__init__()
        self.setObjectName("rail")
        self.setFixedWidth(84)
        layout = QVBoxLayout(self)
        layout.setContentsMargins(6, 12, 6, 12)
        layout.setSpacing(6)
        self.group = QButtonGroup(self)
        self.group.setExclusive(True)
        self.buttons: dict[str, QToolButton] = {}
        for key, (label, _) in MODULES.items():
            button = self._button(label)
            button.setCheckable(True)
            button.clicked.connect(lambda _=False, k=key: self.selected.emit(k))
            self.group.addButton(button)
            self.buttons[key] = button
            layout.addWidget(button)
        layout.addStretch(1)
        self.settings = self._button("Paramètres")
        self.settings.clicked.connect(self.open_settings)
        layout.addWidget(self.settings)
        self.restyle()

    def _button(self, label: str) -> QToolButton:
        button = QToolButton()
        button.setText(label)
        button.setToolButtonStyle(Qt.ToolButtonStyle.ToolButtonTextUnderIcon)
        button.setIconSize(QSize(24, 24))
        button.setFixedSize(72, 60)
        button.setCursor(Qt.CursorShape.PointingHandCursor)
        return button

    def set_current(self, key: str | None) -> None:
        """Highlights a module; None (settings, an open note) leaves the last one highlighted."""
        if key in self.buttons:
            self.buttons[key].setChecked(True)

    def set_hidden(self, hidden: set[str]) -> None:
        for key, button in self.buttons.items():
            button.setVisible(key not in hidden)

    def restyle(self) -> None:
        s = theme.scheme()
        for key, button in self.buttons.items():
            button.setIcon(icons.icon(MODULES[key][1], s.on_surface, 24))
        self.settings.setIcon(icons.icon("settings", s.on_surface, 24))
        self.setStyleSheet(
            f"QFrame#rail {{ background: {s.surface_container}; border: none; }}"
            f"QToolButton {{ border: none; border-radius: 16px; color: {s.on_surface}; font-size: 11px; padding: 4px; }}"
            f"QToolButton:hover {{ background: {s.surface_high}; }}"
            f"QToolButton:checked {{ background: {s.primary_container}; color: {s.on_primary_container}; }}")
