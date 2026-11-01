"""The note's attached files, in a row at the top of the editor: images as thumbnails, other files by name."""
from __future__ import annotations

from pathlib import Path

from PySide6.QtCore import QSize, Qt, Signal
from PySide6.QtGui import QIcon, QPixmap
from PySide6.QtWidgets import QHBoxLayout, QMenu, QScrollArea, QToolButton, QWidget

from ..core.drawing import is_drawing, parse as parse_drawing
from ..core.note import attachment_name
from . import icons

TILE = 112
IMAGES = {"jpg", "jpeg", "png", "gif", "webp", "bmp"}


class AttachmentStrip(QScrollArea):
    open_requested = Signal(str)  # path relative to the notes folder
    reveal_requested = Signal(str)
    remove_requested = Signal(str)

    def __init__(self):
        super().__init__()
        self.setWidgetResizable(True)
        self.setFrameShape(QScrollArea.Shape.NoFrame)
        self.setVerticalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
        self.setFixedHeight(TILE + 24)
        self.setStyleSheet("QScrollArea, QScrollArea > QWidget > QWidget { background: transparent; }")
        inner = QWidget()
        self._row = QHBoxLayout(inner)
        self._row.setContentsMargins(0, 4, 0, 8)
        self._row.setSpacing(8)
        self.setWidget(inner)

    def set_files(self, root: Path, paths: list[str], fg: str) -> None:
        while self._row.count():
            item = self._row.takeAt(0)
            if item.widget() is not None:
                item.widget().deleteLater()
        for path in paths:
            self._row.addWidget(self._tile(root / path, path, fg))
        self._row.addStretch()
        self.setVisible(bool(paths))

    def _tile(self, file: Path, path: str, fg: str) -> QToolButton:
        tile = QToolButton()
        tile.setFixedSize(TILE, TILE)
        tile.setToolButtonStyle(Qt.ToolButtonStyle.ToolButtonTextUnderIcon)
        tile.setToolTip(f"{attachment_name(path)}\nClic : ouvrir · clic droit : plus d'options")
        tile.setStyleSheet(f"QToolButton {{ color: {fg}; background: rgba(128,128,128,0.15); border: none; border-radius: 12px; }}"
                           "QToolButton:hover { background: rgba(128,128,128,0.3); }")
        pixmap = QPixmap(str(file)) if file.suffix.lower().lstrip(".") in IMAGES else QPixmap()
        if is_drawing(path):
            try:
                from .drawing_view import render_pixmap
                pixmap = render_pixmap(parse_drawing(file.read_text(encoding="utf-8")), TILE * 2)
            except OSError:
                pixmap = QPixmap()
        if not pixmap.isNull():
            tile.setIcon(QIcon(pixmap.scaled(TILE - 12, TILE - 12, Qt.AspectRatioMode.KeepAspectRatio, Qt.TransformationMode.SmoothTransformation)))
            tile.setIconSize(QSize(TILE - 12, TILE - 12))
            tile.setToolButtonStyle(Qt.ToolButtonStyle.ToolButtonIconOnly)
        else:
            tile.setIcon(icons.icon("file", fg, 32))
            tile.setIconSize(QSize(32, 32))
            name = attachment_name(path)
            tile.setText(name if len(name) <= 16 else name[:14] + "…")
        tile.clicked.connect(lambda _=False: self.open_requested.emit(path))
        tile.setContextMenuPolicy(Qt.ContextMenuPolicy.CustomContextMenu)
        tile.customContextMenuRequested.connect(lambda pos: self._menu(tile, pos, path))
        return tile

    def _menu(self, tile: QToolButton, pos, path: str) -> None:
        menu = QMenu(tile)
        menu.addAction("Ouvrir", lambda: self.open_requested.emit(path))
        menu.addAction("Afficher dans l'Explorateur", lambda: self.reveal_requested.emit(path))
        menu.addSeparator()
        menu.addAction("Retirer de la note", lambda: self.remove_requested.emit(path))
        menu.exec(tile.mapToGlobal(pos))
