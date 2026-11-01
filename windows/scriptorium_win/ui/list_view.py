from __future__ import annotations

from PySide6.QtCore import QEvent, QPoint, Qt, Signal
from PySide6.QtWidgets import (QFrame, QHBoxLayout, QLabel, QLineEdit, QMenu, QPushButton, QScrollArea, QSizePolicy, QToolButton,
                               QVBoxLayout, QWidget)

from ..core import note as fmt
from ..core.note import Note
from . import icons, theme

CARD_MIN_WIDTH = 250
GAP = 10
MARGIN = 14


def preview_text(note: Note) -> str:
    lines = [ln for ln in note.plain_body().split("\n") if ln.strip() and not ln.lstrip().startswith("![")]
    cleaned = []
    for ln in lines[:8]:
        ln = ln.replace("**", "")
        if ln.startswith("#"):
            ln = ln.lstrip("#").strip()
        cleaned.append(ln)
    return "\n".join(cleaned)[:300]


class NoteCard(QFrame):
    clicked = Signal(object)
    open_pdf = Signal(object)  # Note
    reveal = Signal(object)  # Note: show its file (the PDF for a scan) in the Explorer

    def __init__(self, note: Note):
        super().__init__()
        self.note = note
        self.setObjectName("card")
        self.setAttribute(Qt.WidgetAttribute.WA_StyledBackground, True)
        self.setCursor(Qt.CursorShape.PointingHandCursor)
        s = theme.scheme()
        bg, fg = theme.card_background(note.color), theme.note_foreground(note.color)
        border = s.outline_variant if note.color not in theme.NOTE_COLORS else bg
        self.setStyleSheet(
            f"#card {{ background: {bg}; border: 1px solid {border}; border-radius: 16px; }} "
            f"#card:hover {{ border: 1px solid {s.primary}; }} "
            f"#card QLabel {{ color: {fg}; background: transparent; }} "
            f"#card QLabel#chip {{ background: rgba(128,128,128,0.25); border-radius: 9px; padding: 1px 8px; font-size: 8pt; }} "
            f"#card QLabel#badge {{ background: {s.error_container}; color: {s.on_error_container}; border-radius: 9px; "
            f"padding: 1px 8px; font-size: 8pt; }}"
        )
        layout = QVBoxLayout(self)
        layout.setContentsMargins(14, 12, 14, 12)
        layout.setSpacing(6)

        chips = (["PDF"] if note.pdf else []) + ([f"📎 {len(note.attachments)}"] if note.attachments else [])
        if chips:
            row = QHBoxLayout()
            for text in chips:
                chip = QLabel(text)
                chip.setObjectName("chip")
                chip.setSizePolicy(QSizePolicy.Policy.Fixed, QSizePolicy.Policy.Fixed)
                row.addWidget(chip)
            row.addStretch()
            layout.addLayout(row)
        if note.conflict:
            badge = QLabel("Conflit")
            badge.setObjectName("badge")
            badge.setSizePolicy(QSizePolicy.Policy.Fixed, QSizePolicy.Policy.Fixed)
            row = QHBoxLayout()
            row.addWidget(badge)
            row.addStretch()
            layout.addLayout(row)
        if note.title.strip():
            head = QHBoxLayout()
            title = QLabel(note.title)
            title.setWordWrap(True)
            title.setStyleSheet("font-size: 11.5pt; font-weight: 600;")
            title.setMaximumHeight(title.fontMetrics().lineSpacing() * 4)
            head.addWidget(title, 1)
            if note.pinned:
                pin = QLabel()
                pin.setPixmap(icons.icon("pin", fg, 16).pixmap(16, 16))
                pin.setAlignment(Qt.AlignmentFlag.AlignTop)
                head.addWidget(pin)
            layout.addLayout(head)
        text = preview_text(note)
        if text:
            body = QLabel(text)
            body.setWordWrap(True)
            body.setStyleSheet(f"color: {fg}; font-size: 10pt;")
            body.setMaximumHeight(body.fontMetrics().lineSpacing() * 11)
            body.setAlignment(Qt.AlignmentFlag.AlignTop | Qt.AlignmentFlag.AlignLeft)
            layout.addWidget(body)
        if note.labels:
            chips = QHBoxLayout()
            chips.setSpacing(4)
            for label in note.labels[:3]:
                chip = QLabel(label)
                chip.setObjectName("chip")
                chips.addWidget(chip)
            chips.addStretch()
            layout.addLayout(chips)

    def height_for(self, width: int) -> int:
        self.setFixedWidth(width)
        layout = self.layout()
        layout.activate()
        return max(layout.totalHeightForWidth(width), layout.sizeHint().height())

    def contextMenuEvent(self, event) -> None:
        menu = QMenu(self)
        if self.note.pdf:
            menu.addAction("Ouvrir le PDF", lambda: self.open_pdf.emit(self.note))
        menu.addAction("Afficher dans l'Explorateur", lambda: self.reveal.emit(self.note))
        menu.exec(event.globalPos())

    def mouseReleaseEvent(self, event) -> None:
        if event.button() == Qt.MouseButton.LeftButton and self.rect().contains(event.position().toPoint()):
            self.clicked.emit(self.note)
        super().mouseReleaseEvent(event)


class ListScreen(QWidget):
    open_pdf = Signal(object)  # Note
    reveal = Signal(object)  # Note
    open_note = Signal(object)
    new_note = Signal(bool)  # checklist?
    open_settings = Signal()
    grid_toggled = Signal(bool)
    open_activities = Signal()

    def __init__(self, grid: bool):
        super().__init__()
        self.grid = grid
        self.notes: list[Note] = []
        self.label: str | None = None
        self._items: list[tuple[str, QWidget]] = []  # ("card" | "header", widget)

        root = QVBoxLayout(self)
        root.setContentsMargins(MARGIN, 12, MARGIN, 0)
        root.setSpacing(8)

        # search pill: search, view toggle and settings in one place
        self.pill = QFrame()
        self.pill.setObjectName("pill")
        self.pill.setFixedHeight(52)
        pill = QHBoxLayout(self.pill)
        pill.setContentsMargins(16, 0, 6, 0)
        pill.setSpacing(6)
        self.search_icon = QLabel()
        self.search = QLineEdit()
        self.search.setPlaceholderText("Rechercher dans vos notes")
        self.clear_button = QToolButton()
        self.clear_button.setToolTip("Effacer")
        self.clear_button.clicked.connect(lambda: self.search.clear())
        self.clear_button.hide()
        self.view_button = QToolButton()
        self.view_button.clicked.connect(self._toggle_grid)
        self.settings_button = QToolButton()
        self.settings_button.setToolTip("Paramètres")
        self.settings_button.clicked.connect(self.open_settings)
        for w in (self.search_icon,):
            pill.addWidget(w)
        pill.addWidget(self.search, 1)
        pill.addWidget(self.clear_button)
        pill.addWidget(self.view_button)
        pill.addWidget(self.settings_button)
        root.addWidget(self.pill)

        # the pinned "Activités" folder
        self.activities_button = QPushButton()
        self.activities_button.setObjectName("activities")
        self.activities_button.setCursor(Qt.CursorShape.PointingHandCursor)
        self.activities_button.clicked.connect(self.open_activities)
        root.addWidget(self.activities_button)

        # label filter chips
        self.chip_scroll = QScrollArea()
        self.chip_scroll.setFixedHeight(46)
        self.chip_scroll.setWidgetResizable(True)
        self.chip_scroll.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
        self.chip_scroll.setVerticalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
        chip_host = QWidget()
        self.chip_row = QHBoxLayout(chip_host)
        self.chip_row.setContentsMargins(0, 4, 0, 4)
        self.chip_row.setSpacing(8)
        self.chip_scroll.setWidget(chip_host)
        root.addWidget(self.chip_scroll)
        self.chip_scroll.hide()

        # masonry canvas
        self.scroll = QScrollArea()
        self.scroll.setWidgetResizable(False)
        self.scroll.setFrameShape(QFrame.Shape.NoFrame)
        self.canvas = QWidget()
        self.scroll.setWidget(self.canvas)
        self.scroll.viewport().installEventFilter(self)
        root.addWidget(self.scroll, 1)

        self.empty = QWidget(self)
        empty_layout = QVBoxLayout(self.empty)
        empty_layout.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.empty_icon = QLabel()
        self.empty_icon.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.empty_title = QLabel()
        self.empty_title.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.empty_title.setStyleSheet("font-size: 13pt; font-weight: 600;")
        self.empty_hint = QLabel()
        self.empty_hint.setAlignment(Qt.AlignmentFlag.AlignCenter)
        for w in (self.empty_icon, self.empty_title, self.empty_hint):
            empty_layout.addWidget(w)
        self.empty.hide()

        # floating action buttons
        self.fab_list = QPushButton(self)
        self.fab_list.setObjectName("fabSmall")
        self.fab_list.setToolTip("Nouvelle liste (Ctrl+Maj+N)")
        self.fab_list.clicked.connect(lambda: self.new_note.emit(True))
        self.fab = QPushButton("  Note", self)
        self.fab.setObjectName("fab")
        self.fab.setToolTip("Nouvelle note (Ctrl+N)")
        self.fab.clicked.connect(lambda: self.new_note.emit(False))
        self.fab_list.setCursor(Qt.CursorShape.PointingHandCursor)
        self.fab.setCursor(Qt.CursorShape.PointingHandCursor)

        self.search.textChanged.connect(self._on_search)
        self.restyle()

    # ---- theme ----

    def restyle(self) -> None:
        s = theme.scheme()
        self.search_icon.setPixmap(icons.icon("search", s.on_surface_variant, 22).pixmap(22, 22))
        self.clear_button.setIcon(icons.icon("close", s.on_surface_variant, 20))
        self.settings_button.setIcon(icons.icon("settings", s.on_surface_variant, 22))
        self.empty_icon.setPixmap(icons.icon("note", s.outline, 72).pixmap(72, 72))
        self.empty_hint.setStyleSheet(f"color: {s.on_surface_variant};")
        self.fab.setIcon(icons.icon("add", s.on_primary_container, 22))
        self.fab_list.setIcon(icons.icon("checklist", s.on_secondary_container, 22))
        self._sync_view_button()

    def set_activity_summary(self, text: str) -> None:
        s = theme.scheme()
        self.activities_button.setText(f"🏅  Activités   {text}")
        self.activities_button.setStyleSheet(
            f"QPushButton#activities {{ background: {s.primary_container}; color: {s.on_primary_container}; border: none; "
            f"border-radius: 16px; padding: 12px 16px; text-align: left; font-size: 10.5pt; }} "
            f"QPushButton#activities:hover {{ background: {s.primary}; color: {s.on_primary}; }}")

    def _sync_view_button(self) -> None:
        s = theme.scheme()
        self.view_button.setIcon(icons.icon("agenda" if self.grid else "module", s.on_surface_variant, 22))
        self.view_button.setToolTip("Vue liste" if self.grid else "Vue grille")

    def _toggle_grid(self) -> None:
        self.grid = not self.grid
        self._sync_view_button()
        self.grid_toggled.emit(self.grid)
        self._layout_cards()

    def set_grid(self, grid: bool) -> None:
        self.grid = grid
        self._sync_view_button()
        self._layout_cards()

    # ---- content ----

    def _on_search(self, text: str) -> None:
        self.clear_button.setVisible(bool(text))
        self.activities_button.setVisible(not text and not self.label)
        self.rebuild()

    def set_notes(self, notes: list[Note]) -> None:
        self.notes = notes
        labels = sorted({x for n in notes for x in n.labels}, key=fmt.fold)
        if self.label not in labels:
            self.label = None
        self._build_chips(labels)
        self.rebuild()

    def _build_chips(self, labels: list[str]) -> None:
        while self.chip_row.count():
            w = self.chip_row.takeAt(0).widget()
            if w:
                w.deleteLater()
        for label in labels:
            chip = QPushButton(label)
            chip.setObjectName("chip")
            chip.setCheckable(True)
            chip.setChecked(label == self.label)
            chip.setCursor(Qt.CursorShape.PointingHandCursor)
            chip.clicked.connect(lambda _=False, lb=label: self._pick_label(lb))
            self.chip_row.addWidget(chip)
        self.chip_row.addStretch()
        self.chip_scroll.setVisible(bool(labels))

    def _pick_label(self, label: str) -> None:
        self.label = None if self.label == label else label
        self.activities_button.setVisible(not self.search.text() and not self.label)
        for i in range(self.chip_row.count()):
            w = self.chip_row.itemAt(i).widget()
            if isinstance(w, QPushButton):
                w.setChecked(w.text() == self.label)
        self.rebuild()

    def visible_notes(self) -> list[Note]:
        query = fmt.fold(self.search.text().strip())
        out = []
        for n in self.notes:
            if self.label and self.label not in n.labels:
                continue
            if query and not (query in fmt.fold(n.title) or query in fmt.fold(n.body)
                              or any(query in fmt.fold(x) for x in n.labels)):
                continue
            out.append(n)
        out.sort(key=lambda n: (not n.pinned, -n.updated))
        return out

    def rebuild(self) -> None:
        for _kind, widget in self._items:
            widget.setParent(None)
            widget.deleteLater()
        self._items = []
        notes = self.visible_notes()
        pinned = [n for n in notes if n.pinned]
        others = [n for n in notes if not n.pinned]

        def header(text: str) -> None:
            label = QLabel(text, self.canvas)
            label.setStyleSheet(f"color: {theme.scheme().on_surface_variant}; font-size: 8.5pt; font-weight: 600; "
                                "letter-spacing: 1px; background: transparent;")
            label.show()
            self._items.append(("header", label))

        def card(note: Note) -> None:
            c = NoteCard(note)
            c.setParent(self.canvas)
            c.clicked.connect(self.open_note)
            c.open_pdf.connect(self.open_pdf)
            c.reveal.connect(self.reveal)
            c.show()
            self._items.append(("card", c))

        if pinned:
            header("ÉPINGLÉES")
            for n in pinned:
                card(n)
            if others:
                header("AUTRES")
        for n in others:
            card(n)

        filtered = bool(self.search.text().strip() or self.label)
        self.empty.setVisible(not notes)
        self.scroll.setVisible(bool(notes))
        if not notes:
            self.empty_title.setText("Aucun résultat" if filtered else "Aucune note pour l'instant")
            self.empty_hint.setText("" if filtered else "Appuyez sur « Note » pour commencer")
        self._layout_cards()

    def _layout_cards(self) -> None:
        width = self.scroll.viewport().width()
        if width <= 0:
            return
        if self.grid:
            cols = max(2, (width - 2 * 2 + GAP) // (CARD_MIN_WIDTH + GAP))
            avail, offset = width - 4, 2
        else:
            cols = 1
            avail = min(width - 4, 780)
            offset = max(2, (width - avail) // 2)
        col_w = (avail - GAP * (cols - 1)) // cols
        heights = [4] * cols
        for kind, widget in self._items:
            if kind == "header":
                y = max(heights)
                widget.setGeometry(offset + 4, y, avail, 24)
                heights = [y + 28] * cols
                continue
            column = heights.index(min(heights))
            h = widget.height_for(col_w)
            widget.setGeometry(offset + column * (col_w + GAP), heights[column], col_w, h)
            widget.setFixedHeight(h)
            heights[column] += h + GAP
        self.canvas.resize(width, max(heights) + 150)

    # ---- layout plumbing ----

    def eventFilter(self, obj, event) -> bool:
        if obj is self.scroll.viewport() and event.type() == QEvent.Type.Resize:
            self._layout_cards()
        return super().eventFilter(obj, event)

    def resizeEvent(self, event) -> None:
        super().resizeEvent(event)
        w, h = self.width(), self.height()
        self.empty.setGeometry(0, 120, w, max(h - 160, 100))
        big = self.fab.sizeHint()
        small = self.fab_list.sizeHint()
        self.fab.setGeometry(w - big.width() - 28, h - big.height() - 28, big.width(), big.height())
        self.fab_list.setGeometry(w - small.width() - 28 - (big.width() - small.width()) // 2 + 0,
                                  h - big.height() - 28 - small.height() - 12, small.width(), small.height())
        self.fab.raise_()
        self.fab_list.raise_()
        self._layout_cards()

    def focus_search(self) -> None:
        self.search.setFocus()
        self.search.selectAll()
