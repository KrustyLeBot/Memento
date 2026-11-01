from __future__ import annotations

from pathlib import Path

from PySide6.QtCore import QEvent, QFileSystemWatcher, QTimer, Qt
from PySide6.QtGui import QKeySequence, QShortcut
from PySide6.QtWidgets import (QApplication, QFileDialog, QFrame, QHBoxLayout, QLabel, QMainWindow, QMessageBox,
                               QPushButton, QStackedWidget)

from ..config import Config
from ..core import atomic
from ..core import note as fmt
from ..core.note import Note
from ..core.store import Store, now_ms
from ..services import updater
from ..services.async_util import run_async
from . import files, theme
from .activities_view import ActivitiesScreen, summary_line
from .editor_view import EditorScreen
from .list_view import ListScreen
from .settings_view import SettingsScreen
from .update_toast import UpdateToast


def ask_workdir(parent, first_run: bool, start: Path | None = None) -> Path | None:
    """Asks for the notes folder (the same one the phone syncs); returns None if the user cancels."""
    if first_run:
        QMessageBox.information(
            parent, "Scriptorium",
            "Choisis le dossier qui contient tes notes (celui synchronisé avec ton téléphone).\n"
            "Si le dossier est vide, Scriptorium y créera tes nouvelles notes.",
        )
    chosen = QFileDialog.getExistingDirectory(parent, "Dossier des notes", str(start or Path.home()))
    return Path(chosen) if chosen else None


class MainWindow(QMainWindow):
    def __init__(self, config: Config, store: Store):
        super().__init__()
        self.config = config
        self.store = store
        self.notes: list[Note] = []
        self.toast: UpdateToast | None = None
        self._cleanup_tries = 0
        self.resize(1100, 740)

        self.stack = QStackedWidget()
        self.list_screen = ListScreen(config.grid)
        self.editor = EditorScreen()
        self.settings = SettingsScreen()
        self.activities = ActivitiesScreen()
        for page in (self.list_screen, self.editor, self.settings, self.activities):
            self.stack.addWidget(page)
        self.setCentralWidget(self.stack)

        # ---- snackbar (delete + undo, copy confirmation...) ----
        self.snack = QFrame(self)
        self.snack.setObjectName("snack")
        snack_row = QHBoxLayout(self.snack)
        snack_row.setContentsMargins(16, 6, 8, 6)
        self.snack_text = QLabel()
        self.snack_button = QPushButton("Annuler")
        self.snack_button.setStyleSheet("QPushButton { color: #FFB950; } QPushButton:hover { background: #4A453F; }")
        snack_row.addWidget(self.snack_text)
        snack_row.addWidget(self.snack_button)
        self.snack.hide()
        self._snack_undo = None
        self.snack_timer = QTimer(self, singleShot=True, interval=6000)
        self.snack_timer.timeout.connect(self.snack.hide)
        self.snack_button.clicked.connect(self._run_undo)

        # ---- wiring ----
        self.list_screen.open_note.connect(self.open_note)
        self.list_screen.new_note.connect(self.new_note)
        self.list_screen.open_settings.connect(self.show_settings)
        self.list_screen.grid_toggled.connect(self._grid_changed)
        self.editor.back_requested.connect(self.close_editor)
        self.editor.delete_requested.connect(self.delete_note)
        self.editor.open_pdf.connect(self.open_pdf)
        self.editor.open_file.connect(self.open_attachment)
        self.editor.reveal_file.connect(self.reveal_attachment)
        self.list_screen.open_pdf.connect(self.open_pdf)
        self.list_screen.reveal.connect(self.reveal_note)
        self.editor.message.connect(self.show_snack)
        self.editor.saved.connect(lambda _: self.refresh())
        self.list_screen.open_activities.connect(self.show_activities)
        self.activities.back_requested.connect(self.show_list)
        self.activities.changed.connect(self.refresh)
        self.activities.open_file.connect(self.open_attachment)
        self.activities.reveal_file.connect(self.reveal_attachment)
        self.activities.message.connect(self.show_snack)
        self.settings.back_requested.connect(self.show_list)
        self.settings.choose_folder.connect(self.choose_workdir)
        self.settings.grid_changed.connect(self._grid_changed)
        self.settings.theme_changed.connect(self._theme_changed)
        self.settings.check_updates.connect(lambda: self.check_updates(manual=True))
        self.settings.activity_changed.connect(self._activity_settings_changed)

        QShortcut(QKeySequence("Ctrl+N"), self, activated=lambda: self.new_note(False))
        QShortcut(QKeySequence("Ctrl+Shift+N"), self, activated=lambda: self.new_note(True))
        QShortcut(QKeySequence("Ctrl+F"), self, activated=self._focus_search)
        QShortcut(QKeySequence("Escape"), self, activated=self._escape)

        # The sync tool can drop files in the folder at any time: debounce, then rescan.
        self.watcher = QFileSystemWatcher(self)
        self.watch_timer = QTimer(self, singleShot=True, interval=800)
        self.watch_timer.timeout.connect(self._on_disk_changed)
        self.watcher.directoryChanged.connect(lambda _: self.watch_timer.start())
        self.watcher.fileChanged.connect(lambda _: self.watch_timer.start())
        # Over the server's SMB share Windows does not always report changes: also look every 30 s and
        # whenever the window comes back to the front.
        self._snapshot = None
        self.poll_timer = QTimer(self, interval=30_000)
        self.poll_timer.timeout.connect(self._poll_disk)
        self.poll_timer.start()

        # ---- self-update ----
        self._cleanup_timer = QTimer(self)
        self._cleanup_timer.timeout.connect(self._cleanup_old_exe)
        if not updater.cleanup_previous():
            self._cleanup_timer.start(2000)  # retry every 2 s ...
        QTimer.singleShot(5000, self.check_updates)  # first check shortly after the start
        self._update_timer = QTimer(self)
        self._update_timer.timeout.connect(self.check_updates)
        self._update_timer.start(6 * 3600 * 1000)  # ... then every 6 hours

        self.set_store(store)
        if config.geometry:
            self.restoreGeometry(config.geometry)

    # ---- navigation ----

    def show_list(self) -> None:
        self.stack.setCurrentWidget(self.list_screen)
        self.setWindowTitle(f"Scriptorium — {self.store.root}")

    def show_activities(self) -> None:
        self.activities.show_list()
        self.stack.setCurrentWidget(self.activities)

    def show_settings(self) -> None:
        self.settings.load(str(self.store.root), self.list_screen.grid, self.config.theme, self.store.activity_settings())
        self.stack.setCurrentWidget(self.settings)

    def _escape(self) -> None:
        current = self.stack.currentWidget()
        if current is self.settings:
            self.show_list()
        elif current is self.activities:
            if self.activities.current is not None:
                self.activities.show_list()
            else:
                self.show_list()
        elif current is self.list_screen and self.list_screen.search.text():
            self.list_screen.search.clear()

    def _focus_search(self) -> None:
        if self.stack.currentWidget() is self.list_screen:
            self.list_screen.focus_search()

    # ---- store / list ----

    def set_store(self, store: Store) -> None:
        self.editor.close_note()
        self.store = store
        self.activities.set_store(store)
        if self.watcher.directories():
            self.watcher.removePaths(self.watcher.directories())
        self._arm_watcher()
        self.refresh()
        self.show_list()

    def _arm_watcher(self) -> None:
        # Re-add the path after every event: an atomically replaced file loses its watch.
        root = str(self.store.root)
        if root not in self.watcher.directories():
            self.watcher.addPath(root)
        acts = str(self.store.root / "activities")
        if (self.store.root / "activities").is_dir() and acts not in self.watcher.directories():
            self.watcher.addPath(acts)

    def choose_workdir(self) -> None:
        path = ask_workdir(self, first_run=False, start=self.store.root)
        if path is not None:
            self.config.workdir = path
            self.set_store(Store(path))
            self.show_settings()

    def refresh(self) -> None:
        self._snapshot = self.store.snapshot()
        self.notes = self.store.scan()
        self.list_screen.set_notes(self.notes)
        acts = self.store.scan_activities()
        self.activities.included = set(self.store.activity_settings().totals)
        self.activities.set_activities(acts)
        latest = max((n for n in acts if n.activity), key=lambda n: n.activity.start, default=None)
        self.list_screen.set_activity_summary(
            f"Dernière : {latest.title} · {summary_line(latest.activity)}" if latest else "Aucune activité pour l'instant")

    def _poll_disk(self) -> None:
        snapshot = self.store.snapshot()
        if snapshot is not None and snapshot != self._snapshot:
            self.watch_timer.start()

    def changeEvent(self, event) -> None:
        super().changeEvent(event)
        if event.type() == QEvent.Type.ActivationChange and self.isActiveWindow():
            self._poll_disk()

    def _activity_settings_changed(self, settings) -> None:
        self.store.save_activity_settings(settings)
        self.activities.set_included(settings.totals)

    def _grid_changed(self, grid: bool) -> None:
        self.config.grid = grid
        self.list_screen.set_grid(grid)

    def _theme_changed(self, mode: str) -> None:
        self.config.theme = mode
        theme.apply_theme(QApplication.instance(), mode)
        for screen in (self.list_screen, self.settings, self.activities):
            screen.restyle()
        self.list_screen.rebuild()

    # ---- editing ----

    def open_note(self, note: Note) -> None:
        self.editor.open(note, self.store, self.store.labels())
        self.stack.setCurrentWidget(self.editor)
        self.setWindowTitle(f"Scriptorium — {note.title or 'Sans titre'}")

    def new_note(self, checklist: bool = False) -> None:
        if self.stack.currentWidget() is self.editor:
            self.editor.close_note()
        note = Note(path="", id=fmt.generate_id(), created=now_ms(), updated=now_ms(),
                    body="- [ ] " if checklist else "")
        self.editor.open(note, self.store, self.store.labels(), focus="list" if checklist else "title")
        self.stack.setCurrentWidget(self.editor)

    def close_editor(self) -> None:
        self.editor.close_note()
        self.refresh()
        self.show_list()

    def open_pdf(self, note: Note) -> None:
        path = self.store.pdf_path(note)
        if path is None or not files.open_file(path):
            self.show_snack("PDF introuvable (pas encore synchronisé ?)")

    def open_attachment(self, path: str) -> None:
        if not files.open_file(self.store.file_path(path)):
            self.show_snack("Fichier introuvable (pas encore synchronisé ?)")

    def reveal_attachment(self, path: str) -> None:
        if not files.reveal(self.store.file_path(path)):
            self.show_snack("Fichier introuvable")

    def reveal_note(self, note: Note) -> None:
        path = self.store.pdf_path(note) or self.store.root / note.path
        if not files.reveal(path):
            self.show_snack("Fichier introuvable")

    def delete_note(self, note: Note) -> None:
        """Deletes for good; the snackbar offers a short-lived undo (like on the phone)."""
        if note.path:
            kept = [p for p in [self.store.pdf_path(note), *(self.store.file_path(a) for a in note.attachments)]
                    if p is not None and p.exists()]
            contents = [(p, p.read_bytes()) for p in kept]
            self.store.delete(note)

            def undo() -> None:
                for p, data in contents:
                    atomic.write_bytes(p, data)
                self.store.save(note)
                self.refresh()

            self.show_snack("Note supprimée", undo)
        self.refresh()
        self.show_list()

    # ---- snackbar ----

    def show_snack(self, text: str, undo=None) -> None:
        self.snack_text.setText(text)
        self.snack_button.setVisible(undo is not None)
        self._snack_undo = undo
        self.snack.adjustSize()
        self._place_snack()
        self.snack.show()
        self.snack.raise_()
        self.snack_timer.start()

    def _run_undo(self) -> None:
        undo, self._snack_undo = self._snack_undo, None
        self.snack.hide()
        if undo:
            undo()

    def _place_snack(self) -> None:
        self.snack.move((self.width() - self.snack.width()) // 2, self.height() - self.snack.height() - 32)

    # ---- external changes (sync, edits on another tool) ----

    def _on_disk_changed(self) -> None:
        self._arm_watcher()
        self.refresh()
        if self.stack.currentWidget() is self.editor:
            self.editor.on_disk_changed(self.notes)

    # ---- self-update ----

    def _cleanup_old_exe(self) -> None:
        self._cleanup_tries += 1
        if updater.cleanup_previous() or self._cleanup_tries >= 60:  # ... for 2 minutes
            self._cleanup_timer.stop()

    def check_updates(self, manual: bool = False) -> None:
        def done(result):
            if isinstance(result, Exception):
                self.settings.update_status.setText(f"Vérification impossible : {result}")
                if manual:
                    QMessageBox.warning(self, "Mise à jour", f"Vérification impossible : {result}")
                return
            if result is None or not updater.is_newer(result):
                self.settings.update_status.setText("Vous avez la dernière version")
                if manual:
                    QMessageBox.information(self, "Mise à jour",
                                            f"Tu as la dernière version (build {updater.current_build()}).")
                return
            self.settings.update_status.setText(f"Build {result.build} disponible")
            if self.toast is None or self.toast.release.build != result.build:
                self.toast = UpdateToast(self, result)
            self.toast.place()
            self.toast.show()

        self.settings.update_status.setText("Vérification…")
        run_async(updater.latest, done, done)

    def resizeEvent(self, event) -> None:
        super().resizeEvent(event)
        if self.toast is not None and self.toast.isVisible():
            self.toast.place()  # stays pinned to the top-right corner
        if self.snack.isVisible():
            self._place_snack()

    def closeEvent(self, event) -> None:
        self.editor.close_note()
        self.config.geometry = self.saveGeometry()
        super().closeEvent(event)
