from __future__ import annotations

from pathlib import Path

from PySide6.QtCore import QTimer, Qt, QUrl, Signal
from PySide6.QtGui import QDesktopServices, QFont, QGuiApplication, QKeyEvent, QTextBlockFormat, QTextCharFormat, QTextCursor
from PySide6.QtWidgets import (QDialog, QFileDialog, QFrame, QHBoxLayout, QLabel, QLineEdit, QMessageBox, QPlainTextEdit, QPushButton,
                               QScrollArea, QSizePolicy, QTextEdit, QToolButton, QVBoxLayout, QWidget)

from ..core import atomic
from ..core import note as fmt
from ..core.drawing import is_drawing, new_path as new_drawing_path, parse as parse_drawing, to_svg
from ..core.note import BodyParts, CheckItem, Note
from ..core.store import Store, now_ms
from . import icons, richtext, theme
from .attachments import AttachmentStrip
from .checklist import ChecklistWidget
from .dialogs import ColorDialog, LabelDialog

BASE_PT = 11.5


class RichBody(QTextEdit):
    """WYSIWYG body: bold, italic, headings and bullets, stored as Markdown."""

    focus_changed = Signal(bool)

    def __init__(self):
        super().__init__()
        self.setAcceptRichText(False)
        self.setFrameShape(QFrame.Shape.NoFrame)
        self.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
        self.setVerticalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
        self.base_font = QFont(self.font())
        self.base_font.setPointSizeF(BASE_PT)
        self.setFont(self.base_font)
        self.document().setDefaultFont(self.base_font)
        self.document().documentLayout().documentSizeChanged.connect(self._fit)
        self._fit()

    def _fit(self, *_):
        height = int(self.document().size().height()) + 14
        self.setFixedHeight(max(height, 90))

    def set_markdown(self, text: str) -> None:
        richtext.set_markdown(self.document(), text, self.base_font)
        self.moveCursor(QTextCursor.MoveOperation.End)
        self._fit()

    def markdown(self) -> str:
        return richtext.to_markdown(self.document())

    def focusInEvent(self, event):
        super().focusInEvent(event)
        self.focus_changed.emit(True)

    def focusOutEvent(self, event):
        super().focusOutEvent(event)
        self.focus_changed.emit(False)

    def _plain_char_format(self) -> QTextCharFormat:
        fmt_ = QTextCharFormat()
        fmt_.setFontPointSize(self.base_font.pointSizeF())
        fmt_.setFontWeight(QFont.Weight.Normal)
        fmt_.setFontItalic(False)
        return fmt_

    def keyPressEvent(self, event: QKeyEvent) -> None:
        cursor = self.textCursor()
        if (event.key() in (Qt.Key.Key_Return, Qt.Key.Key_Enter) and cursor.blockFormat().headingLevel()
                and cursor.atBlockEnd()):
            cursor.insertBlock(QTextBlockFormat(), self._plain_char_format())  # a heading ends at Enter
            self.setTextCursor(cursor)
            return
        super().keyPressEvent(event)

    # ---- toolbar actions ----

    def toggle_bold(self) -> None:
        fmt_ = QTextCharFormat()
        fmt_.setFontWeight(QFont.Weight.Normal if self.fontWeight() >= 600 else QFont.Weight.Bold)
        self.mergeCurrentCharFormat(fmt_)

    def toggle_italic(self) -> None:
        fmt_ = QTextCharFormat()
        fmt_.setFontItalic(not self.fontItalic())
        self.mergeCurrentCharFormat(fmt_)

    def cycle_heading(self) -> None:
        """none -> big -> medium -> none."""
        cursor = self.textCursor()
        level = cursor.blockFormat().headingLevel()
        new = {0: 1, 1: 2, 2: 0}.get(level, 0)
        cursor.beginEditBlock()
        block = cursor.blockFormat()
        block.setHeadingLevel(new)
        cursor.setBlockFormat(block)
        char = QTextCharFormat()
        if new:
            char.setFontPointSize(self.base_font.pointSizeF() * richtext.HEADING_SCALE[new])
            char.setFontWeight(QFont.Weight.Bold)
        else:
            char.setFontPointSize(self.base_font.pointSizeF())
            char.setFontWeight(QFont.Weight.Normal)
        cursor.movePosition(QTextCursor.MoveOperation.StartOfBlock)
        cursor.movePosition(QTextCursor.MoveOperation.EndOfBlock, QTextCursor.MoveMode.KeepAnchor)
        cursor.mergeCharFormat(char)
        cursor.endEditBlock()
        self.mergeCurrentCharFormat(char)

    def toggle_bullet(self) -> None:
        from PySide6.QtGui import QTextListFormat
        cursor = self.textCursor()
        current = cursor.currentList()
        cursor.beginEditBlock()
        if current is not None:
            current.remove(cursor.block())
            block = cursor.blockFormat()
            block.setIndent(0)
            cursor.setBlockFormat(block)
        else:
            list_format = QTextListFormat()
            list_format.setStyle(QTextListFormat.Style.ListDisc)
            list_format.setIndent(1)
            cursor.createList(list_format)
        cursor.endEditBlock()


class Chips(QWidget):
    """Clickable links found in the note."""

    def __init__(self):
        super().__init__()
        self._layout = QVBoxLayout(self)
        self._layout.setContentsMargins(0, 8, 0, 0)
        self._layout.setSpacing(6)
        self._urls: list[str] = []

    def set_urls(self, urls: list[str], fg: str) -> None:
        if urls == self._urls:
            return
        self._urls = urls
        while self._layout.count():
            w = self._layout.takeAt(0).widget()
            if w:
                w.deleteLater()
        for url in urls:
            button = QPushButton(url.removeprefix("https://").removeprefix("http://"))
            s = theme.scheme()
            button.setStyleSheet(
                f"QPushButton {{ background: rgba(128,128,128,0.16); color: {s.primary}; border-radius: 16px; "
                f"padding: 6px 14px; text-align: left; }}"
            )
            button.setCursor(Qt.CursorShape.PointingHandCursor)
            button.clicked.connect(lambda _=False, u=url: QDesktopServices.openUrl(QUrl(u)))
            row = QHBoxLayout()
            row.addWidget(button)
            row.addStretch()
            holder = QWidget()
            holder.setLayout(row)
            row.setContentsMargins(0, 0, 0, 0)
            self._layout.addWidget(holder)


class EditorScreen(QWidget):
    back_requested = Signal()
    delete_requested = Signal(object)  # Note
    open_pdf = Signal(object)  # Note
    open_file = Signal(str)  # path relative to the notes folder
    reveal_file = Signal(str)
    message = Signal(str)
    saved = Signal(str)

    def __init__(self):
        super().__init__()
        self.setObjectName("editorRoot")
        self.setAttribute(Qt.WidgetAttribute.WA_StyledBackground, True)
        self.store: Store | None = None
        self.current: Note | None = None
        self.loaded_raw: str | None = None
        self.dirty = False
        self._loading = False
        self._color = fmt.DEFAULT_COLOR
        self._fg = theme.scheme().on_surface
        self._all_labels: list[str] = []

        root = QVBoxLayout(self)
        root.setContentsMargins(0, 0, 0, 0)
        root.setSpacing(0)

        # ---- top bar ----
        top = QHBoxLayout()
        top.setContentsMargins(8, 8, 8, 0)
        self.back_button = QToolButton()
        self.back_button.setToolTip("Retour (Échap)")
        self.back_button.clicked.connect(self.back_requested)
        self.pin_button = QToolButton()
        self.pin_button.setCheckable(True)
        self.pin_button.toggled.connect(self._on_pin)
        top.addWidget(self.back_button)
        top.addStretch()
        top.addWidget(self.pin_button)
        root.addLayout(top)

        # ---- scrolling content, centred like a page ----
        self.scroll = QScrollArea()
        self.scroll.setWidgetResizable(True)
        self.scroll.setFrameShape(QFrame.Shape.NoFrame)
        page = QWidget()
        page.setAttribute(Qt.WidgetAttribute.WA_StyledBackground, False)
        page_row = QHBoxLayout(page)
        page_row.setContentsMargins(24, 0, 24, 0)
        column = QWidget()
        column.setMaximumWidth(860)
        column.setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Preferred)
        self.column = QVBoxLayout(column)
        self.column.setContentsMargins(0, 0, 0, 60)
        self.column.setSpacing(0)
        page_row.addStretch(1)
        page_row.addWidget(column, 100)
        page_row.addStretch(1)
        self.scroll.setWidget(page)
        self.scroll.viewport().setAutoFillBackground(False)  # let the note colour show through
        page.setAutoFillBackground(False)
        root.addWidget(self.scroll, 1)

        self.banner = QFrame()
        self.banner.setObjectName("banner")
        banner_row = QHBoxLayout(self.banner)
        self.banner_label = QLabel("Copie de conflit créée par la synchronisation.")
        banner_row.addWidget(self.banner_label, 1)
        keep = QPushButton("Garder celle-ci")
        keep.clicked.connect(self.keep_conflict)
        remove = QPushButton("Supprimer")
        remove.clicked.connect(lambda: self._request_delete())
        banner_row.addWidget(keep)
        banner_row.addWidget(remove)
        self.banner.hide()
        self.column.addWidget(self.banner)

        self.pdf_banner = QFrame()
        self.pdf_banner.setObjectName("banner")
        pdf_row = QHBoxLayout(self.pdf_banner)
        pdf_row.addWidget(QLabel("Document scanné"), 1)
        open_pdf = QPushButton("Ouvrir le PDF")
        open_pdf.clicked.connect(lambda: self.current is not None and self.open_pdf.emit(self.current))
        pdf_row.addWidget(open_pdf)
        self.pdf_banner.hide()
        self.column.addWidget(self.pdf_banner)

        self.attachments = AttachmentStrip()
        self.attachments.open_requested.connect(self._open_attachment)
        self.attachments.reveal_requested.connect(self.reveal_file)
        self.attachments.remove_requested.connect(self.remove_attachment)
        self.attachments.hide()
        self.column.addWidget(self.attachments)

        self.title = QLineEdit()
        self.title.setPlaceholderText("Titre")
        self.title.setFrame(False)
        title_font = QFont(self.title.font())
        title_font.setPointSizeF(20)
        title_font.setWeight(QFont.Weight.DemiBold)
        self.title.setFont(title_font)
        self.title.setStyleSheet("QLineEdit { background: transparent; border: none; padding: 8px 0; }")
        self.column.addWidget(self.title)

        self.body = RichBody()
        self.body.setStyleSheet("QTextEdit { background: transparent; border: none; }")
        self.column.addWidget(self.body)
        self.checklist = ChecklistWidget(self._fg)
        self.column.addWidget(self.checklist)
        self.after = QPlainTextEdit()
        self.after.setFrameShape(QFrame.Shape.NoFrame)
        self.after.setMaximumHeight(110)
        self.after.setStyleSheet("QPlainTextEdit { background: transparent; border: none; color: gray; }")
        self.column.addWidget(self.after)
        self.chips = Chips()
        self.column.addWidget(self.chips)
        self.column.addStretch()

        # ---- bottom bar: formatting row (only while writing text) + tools row ----
        self.bottom = QFrame()
        bottom = QVBoxLayout(self.bottom)
        bottom.setContentsMargins(8, 4, 8, 8)
        bottom.setSpacing(2)
        self.format_row = QWidget()
        format_layout = QHBoxLayout(self.format_row)
        format_layout.setContentsMargins(0, 0, 0, 0)
        self.format_buttons: list[tuple[QToolButton, str]] = []
        for name, tip, action in [
            ("bold", "Gras (Ctrl+B)", self.body.toggle_bold),
            ("italic", "Italique (Ctrl+I)", self.body.toggle_italic),
            ("title", "Titre", self.body.cycle_heading),
            ("bullets", "Puces", self.body.toggle_bullet),
        ]:
            format_layout.addWidget(self._tool(name, tip, action, self.format_buttons))
        format_layout.addStretch()
        bottom.addWidget(self.format_row)

        tools = QHBoxLayout()
        self.tool_buttons: list[tuple[QToolButton, str]] = []
        tools.addWidget(self._tool("checkbox", "Liste à cocher", self.convert_to_checklist, self.tool_buttons))
        tools.addWidget(self._tool("palette", "Couleur", self.pick_color, self.tool_buttons))
        tools.addWidget(self._tool("label", "Libellés", self.pick_labels, self.tool_buttons))
        tools.addWidget(self._tool("attach", "Joindre des fichiers", self.add_attachments, self.tool_buttons))
        tools.addWidget(self._tool("brush", "Dessiner", self.new_drawing, self.tool_buttons))
        tools.addWidget(self._tool("share", "Copier la note", self.copy_note, self.tool_buttons))
        tools.addStretch()
        self.delete_button = self._tool("delete", "Supprimer la note", self._request_delete, None)
        tools.addWidget(self.delete_button)  # far right, like on the phone
        bottom.addLayout(tools)
        root.addWidget(self.bottom)
        self.format_row.hide()

        # ---- wiring ----
        self.title.textChanged.connect(self._edited)
        self.body.textChanged.connect(self._edited)
        self.checklist.changed.connect(self._edited)
        self.after.textChanged.connect(self._edited)
        self.body.focus_changed.connect(self.format_row.setVisible)
        self.save_timer = QTimer(self, singleShot=True, interval=700)
        self.save_timer.timeout.connect(self.save)
        from PySide6.QtGui import QKeySequence, QShortcut
        QShortcut(QKeySequence("Ctrl+B"), self.body, activated=self.body.toggle_bold)
        QShortcut(QKeySequence("Ctrl+I"), self.body, activated=self.body.toggle_italic)
        QShortcut(QKeySequence("Escape"), self, activated=self.back_requested.emit)

    def _tool(self, name: str, tip: str, action, bucket) -> QToolButton:
        button = QToolButton()
        button.setToolTip(tip)
        button.setFocusPolicy(Qt.FocusPolicy.NoFocus)
        button.clicked.connect(lambda _=False: action())
        button.setProperty("icon_name", name)
        if bucket is not None:
            bucket.append((button, name))
        return button

    # ---- colours ----

    def _apply_color(self) -> None:
        bg, fg = theme.note_background(self._color), theme.note_foreground(self._color)
        self._fg = fg
        s = theme.scheme()
        self.setStyleSheet(
            f"#editorRoot {{ background: {bg}; }} "
            f"#editorRoot QLabel, #editorRoot QLineEdit, #editorRoot QTextEdit, #editorRoot QPlainTextEdit {{ color: {fg}; }} "
            f"#editorRoot QCheckBox {{ color: {fg}; }}"
        )
        self.back_button.setIcon(icons.icon("back", fg, 24))
        self.pin_button.setIcon(icons.icon("pin", s.primary if self.pin_button.isChecked() else fg, 24))
        for button, name in self.format_buttons + self.tool_buttons:
            button.setIcon(icons.icon(name, fg, 24))
        self.delete_button.setIcon(icons.icon("delete", s.error, 24))
        self.checklist.fg = fg
        self.chips._urls = []  # force a restyle
        self._show_attachments()

    # ---- load / save ----

    def open(self, note: Note, store: Store, all_labels: list[str], focus: str | None = None) -> None:
        self.store = store
        self._all_labels = all_labels
        self.current = note
        self.loaded_raw = store.read_raw(note.path) if note.path else None
        self._load(note)
        if focus == "title":
            self.title.setFocus()
        elif focus == "body":
            self.body.setFocus()
        elif focus == "list" and self.checklist.count():
            row = self.checklist.open_box.itemAt(self.checklist.open_box.count() - 1).widget()
            row.edit.setFocus()
        self.scroll.verticalScrollBar().setValue(0)

    def _load(self, note: Note) -> None:
        self._loading = True
        self._color = note.color if note.color in fmt.COLORS else fmt.DEFAULT_COLOR
        self.pin_button.blockSignals(True)
        self.pin_button.setChecked(note.pinned)
        self.pin_button.blockSignals(False)
        self._apply_color()
        parts = fmt.parse_body(note.body)
        self.title.setText(note.title)
        self.body.set_markdown(parts.before)
        self.checklist.fg = self._fg
        self.checklist.set_items(parts.items)
        self.after.setPlainText(parts.after)
        self.banner.setVisible(note.conflict)
        self.pdf_banner.setVisible(bool(note.pdf))
        self._show_attachments()
        self._refresh_visibility()
        self.dirty = False
        self._loading = False

    def _refresh_visibility(self) -> None:
        has_items = self.checklist.count() > 0
        self.body.setVisible(not has_items or bool(self.body.document().toPlainText().strip()))
        self.checklist.setVisible(has_items)
        self.after.setVisible(bool(self.after.toPlainText().strip()))
        body_text = self.body.document().toPlainText() + "\n" + "\n".join(i.text for i in self.checklist.items()) + "\n" + self.after.toPlainText()
        self.chips.set_urls(fmt.find_urls(body_text), self._fg)

    def _edited(self, *_) -> None:
        if self._loading or self.current is None:
            return
        self.dirty = True
        self._refresh_visibility()
        self.save_timer.start()

    def _on_pin(self, checked: bool) -> None:
        self._apply_color()
        self._edited()

    def collect(self) -> Note:
        parts = BodyParts(self.body.markdown(), self.checklist.items(), self.after.toPlainText())
        return self.current.copy(title=self.title.text().strip(), body=fmt.build_body(parts), color=self._color,
                                 pinned=self.pin_button.isChecked(), labels=self.current.labels)

    @staticmethod
    def _is_blank(note: Note) -> bool:
        return not note.title and note.body.strip() in ("", "- [ ]") and not note.attachments

    def save(self) -> None:
        self.save_timer.stop()
        if self.current is None or self.store is None or not self.dirty:
            return
        edited = self.collect()
        if not edited.path and self._is_blank(edited):
            return  # nothing worth a file yet
        edited = edited.copy(updated=now_ms())
        try:
            saved = self.store.save(edited)
        except OSError as exc:
            self.message.emit(f"Échec de l'enregistrement : {exc}")
            return
        self.current = saved
        self.loaded_raw = self.store.read_raw(saved.path)
        self.dirty = False
        self.saved.emit(saved.path)

    def close_note(self) -> None:
        self.save()
        self.current = None

    # ---- actions ----

    def convert_to_checklist(self) -> None:
        if self.current is None:
            return
        if self.checklist.count() == 0:
            lines = [ln.strip().removeprefix("- ").strip() for ln in self.body.markdown().split("\n")]
            lines = [ln for ln in lines if ln]
            self._loading = True
            self.body.set_markdown("")
            self.checklist.set_items([CheckItem(ln) for ln in lines] or [CheckItem("")])
            self._loading = False
            self._edited()
            if not lines:
                self.checklist.open_box.itemAt(0).widget().edit.setFocus()
        else:
            as_text = "\n".join(f"- {i.text}" for i in self.checklist.items())
            merged = "\n\n".join(p for p in (self.body.markdown().rstrip(), as_text) if p)
            self._loading = True
            self.checklist.set_items([])
            self.body.set_markdown(merged)
            self._loading = False
            self._edited()

    def pick_color(self) -> None:
        def pick(color_id: str) -> None:
            self._color = color_id
            self._apply_color()
            self._edited()
        ColorDialog(self, self._color, pick).exec()

    def pick_labels(self) -> None:
        if self.current is None:
            return
        dialog = LabelDialog(self, self._all_labels, self.current.labels)
        if dialog.exec():
            self.current = self.current.copy(labels=dialog.selected())
            self._all_labels = sorted(set(self._all_labels) | set(self.current.labels), key=fmt.fold)
            self._edited()

    def _show_attachments(self) -> None:
        if self.current is not None and self.store is not None:
            self.attachments.set_files(self.store.root, self.current.attachments, self._fg)

    def add_attachments(self) -> None:
        if self.current is None or self.store is None:
            return
        chosen, _ = QFileDialog.getOpenFileNames(self, "Joindre des fichiers")
        added: list[str] = []
        for name in chosen:
            try:
                added.append(self.store.add_attachment(Path(name)))
            except OSError as exc:
                self.message.emit(f"Pièce jointe impossible : {exc}")
        if added:
            self.current = self.current.copy(attachments=self.current.attachments + added)
            self._show_attachments()
            self._edited()

    # ---- drawings ----

    def _open_attachment(self, path: str) -> None:
        if is_drawing(path):
            self.edit_drawing(path)
        else:
            self.open_file.emit(path)

    def new_drawing(self) -> None:
        self._draw(None)

    def edit_drawing(self, path: str) -> None:
        self._draw(path)

    def _draw(self, path: str | None) -> None:
        from .drawing_view import DrawingDialog
        strokes = []
        if path:
            try:
                strokes = parse_drawing((self.store.root / path).read_text(encoding="utf-8"))
            except OSError:
                self.message.emit("Dessin introuvable (pas encore synchronisé ?)")
                return
        dialog = DrawingDialog(self, strokes)
        if dialog.exec() != QDialog.DialogCode.Accepted or not dialog.canvas.changed:
            return
        result = dialog.canvas.strokes
        attachments = list(self.current.attachments)
        if not result:
            if path:  # everything erased: the drawing goes
                try:
                    (self.store.root / path).unlink()
                except OSError:
                    pass
                attachments.remove(path)
        else:
            target = path
            while target is None or (path is None and (self.store.root / target).exists()):
                target = new_drawing_path(fmt.generate_id)
            atomic.write_text(self.store.root / target, to_svg(result))
            if target not in attachments:
                attachments.append(target)
        self.current = self.current.copy(attachments=attachments)
        self._show_attachments()
        self._edited()

    def remove_attachment(self, path: str) -> None:
        if self.current is None or self.store is None:
            return
        answer = QMessageBox.question(self, "Retirer la pièce jointe",
                                      f"Retirer « {fmt.attachment_name(path)} » de la note ? Le fichier sera supprimé.")
        if answer != QMessageBox.StandardButton.Yes:
            return
        self.current = self.current.copy(attachments=[a for a in self.current.attachments if a != path])
        self._show_attachments()
        self.dirty = True
        self.save()
        try:
            self.store.file_path(path).unlink()
        except FileNotFoundError:
            pass

    def copy_note(self) -> None:
        if self.current is None:
            return
        text = self.collect().copy(title=self.title.text()).plain_body()
        title = self.title.text().strip()
        QGuiApplication.clipboard().setText("\n\n".join(p for p in (title, text) if p))
        self.message.emit("Note copiée dans le presse-papiers")

    def _request_delete(self) -> None:
        if self.current is None:
            return
        self.save_timer.stop()
        note, self.current, self.dirty = self.current, None, False
        self.delete_requested.emit(note)

    def keep_conflict(self) -> None:
        if self.current is None or not self.current.conflict or self.store is None:
            return
        self.save()
        kept = self.store.keep_conflict_copy(self.current)
        self.current = kept
        self.loaded_raw = self.store.read_raw(kept.path)
        self._load(kept)
        self.message.emit("Cette version a remplacé l'originale")
        self.saved.emit(kept.path)

    # ---- external changes (sync, edits on another tool) ----

    def on_disk_changed(self, notes: list[Note]) -> None:
        if self.current is None or not self.current.path or self.store is None:
            return
        raw = self.store.read_raw(self.current.path)
        if raw == self.loaded_raw:
            return
        if raw is None:  # deleted elsewhere
            if not self.dirty:
                self.current = None
                self.back_requested.emit()
            return
        disk = next((n for n in notes if n.path == self.current.path), None)
        if disk is None:
            return
        if not self.dirty:
            self.current, self.loaded_raw = disk, raw
            self._load(disk)
            return
        box = QMessageBox(self)
        box.setWindowTitle("Note modifiée ailleurs")
        box.setText("Ce fichier a changé sur le disque (synchronisation ou autre éditeur) pendant que tu le modifiais.")
        mine = box.addButton("Garder ma version", QMessageBox.ButtonRole.AcceptRole)
        box.addButton("Recharger", QMessageBox.ButtonRole.DestructiveRole)
        box.exec()
        if box.clickedButton() is mine:
            self.loaded_raw = raw
            self.current = self.current.copy(extra=disk.extra)
            self.save()
        else:
            self.current, self.loaded_raw = disk, raw
            self._load(disk)
