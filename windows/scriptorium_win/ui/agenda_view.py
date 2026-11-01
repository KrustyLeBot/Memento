"""Placeholder replaced by the agenda module."""
from __future__ import annotations

from PySide6.QtCore import Qt
from PySide6.QtWidgets import QLabel, QVBoxLayout, QWidget


class AgendaScreen(QWidget):
    def __init__(self):
        super().__init__()
        layout = QVBoxLayout(self)
        layout.addWidget(QLabel("Agenda", alignment=Qt.AlignmentFlag.AlignCenter))

    def set_store(self, store) -> None:
        self.store = store

    def refresh(self) -> None:
        pass
