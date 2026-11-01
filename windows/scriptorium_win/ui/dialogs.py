from __future__ import annotations

from PySide6.QtCore import Qt
from PySide6.QtWidgets import (QCheckBox, QDialog, QDialogButtonBox, QGridLayout, QLineEdit, QPushButton,
                               QScrollArea, QVBoxLayout, QWidget)

from ..core.note import COLORS, DEFAULT_COLOR
from . import icons, theme


class ColorDialog(QDialog):
    def __init__(self, parent, current: str, on_pick):
        super().__init__(parent)
        self.setWindowTitle("Couleur")
        self.on_pick = on_pick
        layout = QVBoxLayout(self)
        grid = QGridLayout()
        grid.setSpacing(12)
        for index, (color_id, label) in enumerate(COLORS.items()):
            fill = theme.card_background(color_id)
            button = QPushButton()
            button.setFixedSize(44, 44)
            button.setToolTip(label)
            selected = color_id == current
            border = theme.scheme().primary if selected else theme.scheme().outline
            button.setStyleSheet(
                f"QPushButton {{ background: {fill}; border: {3 if selected else 1}px solid {border}; border-radius: 22px; padding: 0; }}"
            )
            if selected:
                button.setIcon(icons.icon("check", "#202124" if not theme.is_dark() else fill, 22))
            button.clicked.connect(lambda _=False, cid=color_id: self._pick(cid))
            grid.addWidget(button, index // 4, index % 4)
        layout.addLayout(grid)
        close = QDialogButtonBox(QDialogButtonBox.StandardButton.Ok)
        close.accepted.connect(self.accept)
        layout.addWidget(close)

    def _pick(self, color_id: str) -> None:
        self.on_pick(color_id)
        self.accept()


class LabelDialog(QDialog):
    def __init__(self, parent, all_labels: list[str], selected: list[str]):
        super().__init__(parent)
        self.setWindowTitle("Libellés")
        self.setMinimumWidth(320)
        self._boxes: dict[str, QCheckBox] = {}
        layout = QVBoxLayout(self)
        holder = QWidget()
        self._list = QVBoxLayout(holder)
        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        scroll.setWidget(holder)
        scroll.setMinimumHeight(160)
        layout.addWidget(scroll)
        for label in dict.fromkeys(all_labels + selected):
            self._add(label, label in selected)
        self._list.addStretch()
        self.new = QLineEdit()
        self.new.setPlaceholderText("Nouveau libellé")
        self.new.returnPressed.connect(self._add_new)
        layout.addWidget(self.new)
        buttons = QDialogButtonBox(QDialogButtonBox.StandardButton.Ok)
        buttons.accepted.connect(self._finish)
        layout.addWidget(buttons)

    def _add(self, label: str, checked: bool) -> None:
        box = QCheckBox(label)
        box.setChecked(checked)
        self._boxes[label] = box
        self._list.insertWidget(max(self._list.count() - 1, 0), box)

    def _add_new(self) -> None:
        label = self.new.text().strip().replace(",", " ")
        if label and label not in self._boxes:
            self._add(label, True)
        elif label in self._boxes:
            self._boxes[label].setChecked(True)
        self.new.clear()

    def _finish(self) -> None:
        self._add_new()
        self.accept()

    def selected(self) -> list[str]:
        return [label for label, box in self._boxes.items() if box.isChecked()]
