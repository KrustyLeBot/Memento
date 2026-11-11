from __future__ import annotations

from pathlib import Path

from PySide6.QtCore import QEvent, QFileSystemWatcher, QTimer, Qt
from PySide6.QtGui import QKeySequence, QShortcut
from PySide6.QtWidgets import (QApplication, QFileDialog, QFrame, QHBoxLayout, QLabel, QMainWindow, QMessageBox,
                               QPushButton, QStackedWidget, QWidget)

from ..config import Config
from ..core import atomic
from ..core import note as fmt
from ..core.note import Note
from ..core.store import Store, now_ms
from ..services import updater
from ..services.async_util import run_async
from . import files, mapview, theme
from .activities_view import ActivitiesScreen, summary_line
from .agenda_view import AgendaScreen
from .contacts_view import ContactsScreen
from .rail import Rail
from .editor_view import EditorScreen
from .list_view import ListScreen
from .settings_view import SettingsScreen
from .update_toast import UpdateToast


def ask_workdir(parent, first_run: bool, start: Path | None = None) -> Path | None:
    """Asks for the notes folder (the same one the phone syncs); returns None if the user cancels."""
    if first_run:
        QMessageBox.information(
            parent, "Mémento",
            "Choisis le dossier qui contient tes notes (celui synchronisé avec ton téléphone).\n"
            "Si le dossier est vide, Mémento y créera tes nouvelles notes.",
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
        self.activities.config = config
        self.agenda = AgendaScreen(config)
        self.contacts = ContactsScreen()
        for page in (self.list_screen, self.editor, self.settings, self.activities, self.agenda, self.contacts):
            self.stack.addWidget(page)
        self.rail = Rail()
        self.rail.set_hidden(config.hidden_modules)
        central = QWidget()
        row = QHBoxLayout(central)
        row.setContentsMargins(0, 0, 0, 0)
        row.setSpacing(0)
        row.addWidget(self.rail)
        row.addWidget(self.stack, 1)
        self.setCentralWidget(central)
        self.module = "notes"
        # Modules a detail was opened from in another module (an activity or a birthday tapped in the agenda): its
        # back (button, Escape, delete) returns there instead of to that module's own list.
        self._origins: list[str] = []

        # ---- snackbar (delete + undo, copy confirmation...) ----
        self.snack = QFrame(self)
        self.snack.setObjectName("snack")
        snack_row = QHBoxLayout(self.snack)
        snack_row.setContentsMargins(16, 6, 8, 6)
        self.snack_text = QLabel()
        self.snack_button = QPushButton("Annuler")
        self.snack_button.setStyleSheet("QPushButton { color: #FFB1C6; } QPushButton:hover { background: #4A453F; }")
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
        self.list_screen.activities_button.hide()  # the rail replaces the pinned entry
        self.rail.selected.connect(self.show_module)
        self.rail.open_settings.connect(self.show_settings)
        self.activities.back_requested.connect(self.show_list)
        self.activities.detail_back.connect(lambda: self.leave_detail(self.activities))
        self.contacts.detail_back.connect(lambda: self.leave_detail(self.contacts))
        self.activities.changed.connect(self.refresh)
        self.activities.open_file.connect(self.open_attachment)
        self.activities.reveal_file.connect(self.reveal_attachment)
        self.activities.message.connect(self.show_snack)
        self.contacts.message.connect(self.show_snack)
        self.agenda.message.connect(self.show_snack)
        self.agenda.changed.connect(self.refresh)
        self.agenda.open_note.connect(self.open_note)
        self.agenda.open_activity.connect(self.open_activity)
        self.agenda.open_contact.connect(self.open_contact)
        self.settings.agenda_changed.connect(self._agenda_settings_changed)
        self.contacts.set_first_name_first(bool(getattr(config, "contacts_first_name_first", False)))
        self.settings.contacts_order.connect(self._contacts_order_changed)
        self.settings.back_requested.connect(lambda: self.show_module(self.module))
        self.settings.choose_folder.connect(self.choose_workdir)
        self.settings.grid_changed.connect(self._grid_changed)
        self.settings.theme_changed.connect(self._theme_changed)
        self.settings.check_updates.connect(lambda: self.check_updates(manual=True))
        self.settings.activity_changed.connect(self._activity_settings_changed)
        self.settings.module_visibility.connect(self.set_module_visible)

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
        QTimer.singleShot(5000, self, self.check_updates)  # first check shortly after the start
        QTimer.singleShot(1500, self.activities, self.activities.preload_map)  # the map page loads while nothing else happens
        self._update_timer = QTimer(self)
        self._update_timer.timeout.connect(self.check_updates)
        self._update_timer.start(6 * 3600 * 1000)  # ... then every 6 hours

        self.set_store(store)
        if config.geometry:
            self.restoreGeometry(config.geometry)

    # ---- navigation ----

    def show_module(self, key: str) -> None:
        if self.activities.map_panel is not None:
            self.activities.map_panel.collapse()
        self._origins.clear()  # a module shown (the rail, back): nothing before it to come back to
        if key in self.config.hidden_modules:
            key = "notes"
        self.module = key
        self.config.last_module = key
        self.rail.set_current(key)
        if self.stack.currentWidget() is self.editor:
            self.editor.close_note()
            self.refresh()
        if key == "activities":
            self.activities.show_list()
        page = {"notes": self.list_screen, "agenda": self.agenda, "activities": self.activities,
                "contacts": self.contacts}[key]
        self.stack.setCurrentWidget(page)
        self.setWindowTitle(f"Mémento — {self.store.root}")

    def leave_detail(self, screen) -> None:
        """Back from an activity or a contact: to the module it was opened from, else to its list."""
        screen.show_list()
        if self._origins:
            origin = self._origins.pop()
            self.show_module(origin)

    def show_list(self) -> None:
        self.show_module("notes")

    def show_activities(self) -> None:
        self.show_module("activities")

    def set_module_visible(self, key: str, visible: bool) -> None:
        hidden = set(self.config.hidden_modules)
        hidden.discard(key) if visible else hidden.add(key)
        self.config.hidden_modules = hidden
        self.rail.set_hidden(hidden)

    def show_settings(self) -> None:
        self.settings.load(str(self.store.root), self.list_screen.grid, self.config.theme, self.store.activity_settings(),
                           self.config.hidden_modules)
        self.settings.set_contacts_first_name_first(self.contacts.first_name_first)
        self.settings.load_agenda(self.agenda.prefs, self.agenda.data.settings)
        self.rail.set_current(None)
        self.stack.setCurrentWidget(self.settings)

    def _escape(self) -> None:
        current = self.stack.currentWidget()
        panel = self.activities.map_panel
        if panel is not None and panel.expanded:
            panel.collapse()
        elif current is self.editor:
            self.close_editor()
        elif current is self.settings:
            self.show_module(self.module)
        elif current is self.activities:
            if self.activities.current is not None:
                self.leave_detail(self.activities)
        elif current is self.contacts:
            if self.contacts.current is not None:
                self.leave_detail(self.contacts)
            elif self.contacts.search.text():
                self.contacts.search.clear()
        elif current is self.agenda and self.agenda.search.text():
            self.agenda.search.clear()
        elif current is self.list_screen and self.list_screen.search.text():
            self.list_screen.search.clear()

    def _focus_search(self) -> None:
        if self.stack.currentWidget() is self.list_screen:
            self.list_screen.focus_search()
        elif self.stack.currentWidget() is self.contacts:
            self.contacts.focus_search()
        elif self.stack.currentWidget() is self.agenda:
            self.agenda.focus_search()

    # ---- store / list ----

    def set_store(self, store: Store) -> None:
        self.editor.close_note()
        self.store = store
        self.activities.set_store(store)
        self.agenda.set_store(store)
        self.contacts.set_store(store)
        if self.watcher.directories():
            self.watcher.removePaths(self.watcher.directories())
        self._arm_watcher()
        self.refresh()
        self.show_module(self.config.last_module)

    def _arm_watcher(self) -> None:
        # Re-add the path after every event: an atomically replaced file loses its watch.
        root = str(self.store.root)
        if root not in self.watcher.directories():
            self.watcher.addPath(root)
        subs = ["activities", "contacts", "agenda"]
        agenda = self.store.root / "agenda"
        if agenda.is_dir():
            subs += [f"agenda/{p.name}" for p in agenda.iterdir() if p.is_dir() and not p.name.startswith(".")]
        for sub in subs:
            path = str(self.store.root / sub)
            if (self.store.root / sub).is_dir() and path not in self.watcher.directories():
                self.watcher.addPath(path)

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
        activity = self.store.activity_settings()
        included = set(activity.totals)
        if included != self.activities.included:  # changed by the phone (settings.json synced)
            self.activities.set_included(included)
        mapview.set_relief(activity.relief, activity.buildings)
        self.activities.set_activities(acts)
        latest = max((n for n in acts if n.activity), key=lambda n: n.activity.start, default=None)
        self.list_screen.set_activity_summary(
            f"Dernière : {latest.title} · {summary_line(latest.activity)}" if latest else "Aucune activité pour l'instant")
        self.contacts.refresh()
        self.agenda.refresh(self.notes, acts)

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
        mapview.set_relief(settings.relief, settings.buildings)

    def _open_in(self, key: str) -> None:
        origin = self.module if self.stack.currentWidget() is not self.settings else None
        self.show_module(key)
        if origin is not None and origin != key:
            self._origins.append(origin)

    def open_activity(self, note: Note) -> None:
        self._open_in("activities")
        self.activities.open_activity(note)

    def open_contact(self, contact_id: str) -> None:
        self.store.scan_contacts()
        contact = self.store.contacts.by_id(contact_id)
        if contact is None:
            self.show_snack("Contact introuvable (supprimé ?)")
            return
        self._open_in("contacts")
        self.contacts.show_contact(contact)

    def _agenda_settings_changed(self, change: dict) -> None:
        """From the settings page: this PC's preferences (QSettings) or the synced ones (calendars.json)."""
        prefs = self.agenda.prefs
        if "week_start" in change:
            self.config.agenda_week_start = prefs.week_start = change["week_start"]
        if "event_zone_times" in change:
            self.config.agenda_event_zone = prefs.event_zone_times = change["event_zone_times"]
        if "default_alerts" in change:
            # the chips replace the values they show; another one set on the phone (e.g. 2 h) is kept
            shown = set(self.settings.alert_buttons)
            kept = [m for m in self.agenda.data.settings.default_alerts(False) if m not in shown]
            self.agenda.set_default_alerts(kept + list(change["default_alerts"]))
        if "birthday_alerts" in change:
            shown = set(self.settings.birthday_alert_buttons)
            kept = [m for m in self.agenda.data.settings.birthday_alerts() if m not in shown]
            self.agenda.set_birthday_alerts(kept + list(change["birthday_alerts"]))
        if "default_calendar" in change:
            self.agenda.set_default_calendar(change["default_calendar"])
        if "birthdays" in change:
            self.agenda.set_layer("birthdays", visible=change["birthdays"])
        self.agenda.apply_prefs(prefs)

    def _contacts_order_changed(self, first_name_first: bool) -> None:
        self.config.contacts_first_name_first = first_name_first
        self.contacts.set_first_name_first(first_name_first)

    def _grid_changed(self, grid: bool) -> None:
        self.config.grid = grid
        self.list_screen.set_grid(grid)

    def _theme_changed(self, mode: str) -> None:
        self.config.theme = mode
        theme.apply_theme(QApplication.instance(), mode)
        for screen in (self.list_screen, self.settings, self.activities, self.contacts, self.agenda, self.rail):
            screen.restyle()
        self.list_screen.rebuild()

    # ---- editing ----

    def open_note(self, note: Note) -> None:
        self.editor.open(note, self.store, self.store.labels())
        self.stack.setCurrentWidget(self.editor)
        self.setWindowTitle(f"Mémento — {note.title or 'Sans titre'}")

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
        self.show_module(self.module)

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
        self.show_module(self.module)

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
