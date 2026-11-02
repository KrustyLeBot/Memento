"""Dialogs around the calendars: the ICS import (preview, options, progress, report, undo), the list of past
imports, the export choice, and the management of calendars (create, rename, colour, default, delete).

They only collect choices and show results; reading, writing and undo go through callbacks of the screen,
which owns the store."""
from __future__ import annotations

from datetime import date, datetime, timedelta
from typing import Callable

from PySide6.QtCore import Qt
from PySide6.QtWidgets import (QApplication, QButtonGroup, QCheckBox, QComboBox, QDialog, QDialogButtonBox, QFrame,
                               QGridLayout, QHBoxLayout, QLabel, QLineEdit, QMenu, QMessageBox, QProgressBar,
                               QPushButton, QRadioButton, QScrollArea, QStackedWidget, QToolButton, QVBoxLayout, QWidget)

from ..core.agenda.calendars import PALETTE, CalendarSettings, next_color
from ..core.agenda.frdates import short_date
from ..core.agenda.ics_import import CalendarPreview, ImportOptions, ImportReport, ImportTarget, ParsedCalendar
from ..core.agenda.model import Event
from . import theme
from .agenda_editor import color_icon, date_edit, from_qdate, input_style

SKIP = "__skip__"
NEW = "__new__"


def plural(n: int, one: str, many: str) -> str:
    return f"{n} {one if n <= 1 else many}"


def palette_menu(parent, current: Callable[[], str | None], on_pick: Callable[[str], None]) -> QMenu:
    """The palette, filled when it opens (a side panel holds a menu per calendar)."""
    menu = QMenu(parent)

    def fill() -> None:
        menu.clear()
        now = (current() or "").upper()
        for color, name in PALETTE:
            action = menu.addAction(color_icon(color), name, lambda c=color: on_pick(c))
            action.setCheckable(True)
            action.setChecked(color.upper() == now)

    menu.aboutToShow.connect(fill)
    return menu


class ColorButton(QToolButton):
    def __init__(self, color: str, on_change: Callable[[str], None] | None = None):
        super().__init__()
        self.color = color
        self.on_change = on_change
        self.setToolTip("Couleur")
        self.setPopupMode(QToolButton.ToolButtonPopupMode.InstantPopup)
        self.setMenu(palette_menu(self, lambda: self.color, self._picked))
        self.set_color(color)

    def set_color(self, color: str) -> None:
        self.color = color
        self.setIcon(color_icon(color, 10))

    def _picked(self, color: str) -> None:
        self.set_color(color)
        if self.on_change:
            self.on_change(color)


def preview_line(pv: CalendarPreview, today: date) -> str:
    parts = [plural(pv.events, "événement", "événements")]
    if pv.series:
        parts.append(plural(pv.series, "série", "séries"))
    if pv.exceptions:
        parts.append(plural(pv.exceptions, "occurrence modifiée", "occurrences modifiées"))
    if pv.first and pv.last:
        parts.append(f"du {short_date(pv.first)} au {short_date(pv.last)}")
    return " · ".join(parts)


def warning_line(pv: CalendarPreview) -> str:
    parts = []
    if pv.ignored:
        parts.append(plural(pv.ignored, "élément ignoré", "éléments ignorés"))
    if pv.cancelled:
        parts.append(plural(pv.cancelled, "annulé", "annulés"))
    if pv.unresolved_zones:
        parts.append(plural(pv.unresolved_zones, "fuseau inconnu (celui de l'appareil est utilisé)",
                            "fuseaux inconnus (celui de l'appareil est utilisé)"))
    if pv.errors:
        parts.append(plural(len(pv.errors), "événement illisible", "événements illisibles"))
    return " · ".join(parts)


class ImportDialog(QDialog):
    """Preview -> options -> progress -> report (+ "Annuler l'import")."""

    def __init__(self, parent, parsed: list[ParsedCalendar], previews: list[CalendarPreview],
                 settings: CalendarSettings, existing: list[Event],
                 run: Callable[[ImportOptions, Callable[[int, int], None]], ImportReport],
                 undo: Callable[[str], str], today: date):
        super().__init__(parent)
        self.setStyleSheet(input_style())
        self.setWindowTitle("Importer un agenda (.ics)")
        self.setMinimumSize(640, 520)
        self.parsed, self.previews, self.settings, self.existing = parsed, previews, settings, existing
        self.run, self.undo, self.today = run, undo, today
        self.report: ImportReport | None = None
        self.undone = False
        s = theme.scheme()
        layout = QVBoxLayout(self)
        self.pages = QStackedWidget()
        layout.addWidget(self.pages, 1)

        # ---- preview + options ----
        page = QWidget()
        v = QVBoxLayout(page)
        v.setContentsMargins(0, 0, 0, 0)
        intro = QLabel("Rien n'est encore écrit. Choisis où va chaque agenda du fichier.")
        intro.setStyleSheet(f"color: {s.on_surface_variant};")
        v.addWidget(intro)
        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        host = QWidget()
        cards = QVBoxLayout(host)
        cards.setSpacing(10)
        self.rows: list[dict] = []
        used = [c.color for c in settings.calendars]
        for i, (p, pv) in enumerate(zip(parsed, previews)):
            card = QFrame()
            card.setObjectName("pill")
            card.setStyleSheet(f"QFrame#pill {{ background: {s.surface_container}; border-radius: 12px; }}")
            g = QGridLayout(card)
            g.setContentsMargins(14, 10, 14, 10)
            source = QLabel(p.source if p.source == p.name else f"{p.name}  ({p.source})")
            source.setStyleSheet("font-weight: 600;")
            g.addWidget(source, 0, 0, 1, 4)
            g.addWidget(QLabel(preview_line(pv, today)), 1, 0, 1, 4)
            warn = warning_line(pv)
            if warn:
                w = QLabel(warn)
                w.setWordWrap(True)
                w.setStyleSheet(f"color: {s.on_surface_variant};")
                if pv.errors:
                    w.setToolTip("\n".join(pv.errors[:30]))
                g.addWidget(w, 2, 0, 1, 4)
            target = QComboBox()
            target.addItem("Nouvel agenda", NEW)
            for cal in settings.calendars:
                target.addItem(color_icon(cal.color), cal.name, cal.id)
            target.addItem("Ne pas importer", SKIP)
            target.setCurrentIndex(max(0, target.findData(pv.suggested_calendar)) if pv.suggested_calendar else 0)
            name = QLineEdit(p.name)
            name.setPlaceholderText("Nom du nouvel agenda")
            color = next((c for c, _ in PALETTE if c not in used), next_color(settings))
            used.append(color)
            color_button = ColorButton(color)
            dup = QLabel()
            dup.setStyleSheet(f"color: {s.on_surface_variant};")
            g.addWidget(QLabel("Dans"), 3, 0)
            g.addWidget(target, 3, 1)
            g.addWidget(name, 3, 2)
            g.addWidget(color_button, 3, 3)
            g.addWidget(dup, 4, 0, 1, 4)
            g.setColumnStretch(2, 1)
            row = {"target": target, "name": name, "color": color_button, "dup": dup, "parsed": p}
            self.rows.append(row)
            target.currentIndexChanged.connect(lambda _=0, r=row: self._target_changed(r))
            self._target_changed(row)
            cards.addWidget(card)
        cards.addStretch()
        scroll.setWidget(host)
        v.addWidget(scroll, 1)

        options = QGridLayout()
        self.since_box = QCheckBox("Seulement depuis le")
        self.since = date_edit(today - timedelta(days=365))
        self.since.setEnabled(False)
        self.since_box.toggled.connect(self.since.setEnabled)
        options.addWidget(self.since_box, 0, 0)
        options.addWidget(self.since, 0, 1)
        options.addWidget(QLabel("Déjà présents (même identifiant)"), 1, 0)
        dup_row = QHBoxLayout()
        self.dup_group = QButtonGroup(self)
        self.dup_buttons = {}
        for key, label in (("update", "Mettre à jour"), ("skip", "Ignorer"), ("keep", "Garder les deux")):
            b = QRadioButton(label)
            self.dup_group.addButton(b)
            self.dup_buttons[key] = b
            dup_row.addWidget(b)
        self.dup_buttons["update"].setChecked(True)
        dup_row.addStretch()
        options.addLayout(dup_row, 1, 1)
        options.setColumnStretch(2, 1)
        v.addLayout(options)
        buttons = QDialogButtonBox(QDialogButtonBox.StandardButton.Ok | QDialogButtonBox.StandardButton.Cancel)
        self.import_button = buttons.button(QDialogButtonBox.StandardButton.Ok)
        self.import_button.setText("Importer")
        self.import_button.setObjectName("primary")
        buttons.button(QDialogButtonBox.StandardButton.Cancel).setText("Annuler")
        buttons.accepted.connect(self.start_import)
        buttons.rejected.connect(self.reject)
        v.addWidget(buttons)
        self.pages.addWidget(page)

        # ---- progress ----
        page = QWidget()
        v = QVBoxLayout(page)
        v.addStretch()
        self.progress_label = QLabel("Import en cours…")
        v.addWidget(self.progress_label)
        self.progress = QProgressBar()
        self.progress.setTextVisible(False)
        self.progress.setFixedHeight(6)
        v.addWidget(self.progress)
        v.addStretch()
        self.pages.addWidget(page)

        # ---- report ----
        page = QWidget()
        v = QVBoxLayout(page)
        self.report_title = QLabel()
        self.report_title.setStyleSheet("font-size: 13pt; font-weight: 600;")
        self.report_title.setWordWrap(True)
        v.addWidget(self.report_title)
        self.report_text = QLabel()
        self.report_text.setWordWrap(True)
        self.report_text.setTextInteractionFlags(Qt.TextInteractionFlag.TextSelectableByMouse)
        self.report_text.setAlignment(Qt.AlignmentFlag.AlignTop)
        report_scroll = QScrollArea()
        report_scroll.setWidgetResizable(True)
        report_scroll.setWidget(self.report_text)
        v.addWidget(report_scroll, 1)
        row = QHBoxLayout()
        self.undo_button = QPushButton("Annuler l'import")
        self.undo_button.setStyleSheet(f"color: {s.error};")
        self.undo_button.clicked.connect(self._undo)
        row.addWidget(self.undo_button)
        row.addStretch()
        close = QPushButton("Fermer")
        close.setObjectName("primary")
        close.clicked.connect(self.accept)
        row.addWidget(close)
        v.addLayout(row)
        self.pages.addWidget(page)

    def _target_changed(self, row: dict) -> None:
        data = row["target"].currentData()
        row["name"].setVisible(data == NEW)
        row["color"].setVisible(data == NEW)
        if data in (NEW, SKIP):
            row["dup"].setText("")
            row["dup"].hide()
            return
        uids = {e.uid for e in self.existing if e.uid and self.settings.calendar(e.calendar).id == data and not e.recurrence_of}
        n = sum(1 for e in row["parsed"].events if e.uid in uids)
        row["dup"].setText(plural(n, "événement déjà présent dans cet agenda", "événements déjà présents dans cet agenda")
                           if n else "Aucun événement déjà présent dans cet agenda")
        row["dup"].show()
        self._update_button()

    def _update_button(self) -> None:
        if hasattr(self, "import_button"):
            self.import_button.setEnabled(any(r["target"].currentData() != SKIP for r in self.rows))

    def options(self) -> ImportOptions:
        targets = {}
        for i, r in enumerate(self.rows):
            data = r["target"].currentData()
            if data == SKIP:
                targets[i] = ImportTarget(skip=True)
            elif data == NEW:
                targets[i] = ImportTarget(name=r["name"].text().strip() or None, color=r["color"].color)
            else:
                targets[i] = ImportTarget(calendar_id=data)
        duplicates = next(k for k, b in self.dup_buttons.items() if b.isChecked())
        since = from_qdate(self.since.date()) if self.since_box.isChecked() else None
        return ImportOptions(targets, since, duplicates)

    def start_import(self) -> None:
        self.pages.setCurrentIndex(1)

        def progress(done: int, total: int) -> None:
            self.progress.setMaximum(max(1, total))
            self.progress.setValue(done)
            self.progress_label.setText(f"Import en cours… {done} / {total}")
            QApplication.processEvents()

        try:
            self.report = self.run(self.options(), progress)
        except (OSError, ValueError) as error:
            QMessageBox.warning(self, "Import", f"Import interrompu : {error}\nLes fichiers déjà écrits portent "
                                                "l'identifiant de l'import : « Imports passés… » permet de les retirer.")
            self.reject()
            return
        self.show_report(self.report)

    def show_report(self, report: ImportReport) -> None:
        self.report_title.setText(f"Import terminé : {report.summary()}")
        lines = []
        for c in report.calendars:
            name = self.settings.calendar(c.calendar_id).name
            parts = [plural(c.created, "créé", "créés")]
            if c.updated:
                parts.append(plural(c.updated, "mis à jour", "mis à jour"))
            if c.skipped_duplicates:
                parts.append(plural(c.skipped_duplicates, "doublon ignoré", "doublons ignorés"))
            if c.before_cutoff:
                parts.append(plural(c.before_cutoff, "avant la date choisie", "avant la date choisie"))
            if c.unresolved_zones:
                parts.append(plural(c.unresolved_zones, "fuseau inconnu", "fuseaux inconnus"))
            lines.append(f"<b>{_html(name)}</b> : {', '.join(parts)}")
            for err in c.errors[:20]:
                lines.append(f"&nbsp;&nbsp;• {_html(err)}")
            if len(c.errors) > 20:
                lines.append(f"&nbsp;&nbsp;… et {len(c.errors) - 20} autres")
        self.report_text.setText("<br>".join(lines))
        self.undo_button.setEnabled(report.created > 0 or bool(report.new_calendars))
        self.pages.setCurrentIndex(2)

    def _undo(self) -> None:
        if self.report is None:
            return
        text = self.undo(self.report.import_id)
        self.undone = True
        self.undo_button.setEnabled(False)
        self.report_title.setText(f"Import annulé : {text}")


def _html(text: str) -> str:
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def import_date(import_id: str) -> str:
    """The date an import id was made ("20261102T101500-k3f9a2" -> "2 nov. 2026, 10:15 UTC")."""
    try:
        stamp = datetime.strptime(import_id.split("-", 1)[0], "%Y%m%dT%H%M%S")
    except ValueError:
        return import_id
    return f"{short_date(stamp.date())}, {stamp:%H:%M} UTC"


class PastImportsDialog(QDialog):
    """The imports whose files are still there, each with "Annuler cet import"."""

    def __init__(self, parent, imports: Callable[[], list[tuple[str, int, list[str]]]], undo: Callable[[str], str]):
        super().__init__(parent)
        self.setStyleSheet(input_style())
        self.setWindowTitle("Imports passés")
        self.setMinimumWidth(520)
        self.imports, self.undo = imports, undo
        layout = QVBoxLayout(self)
        self.box = QVBoxLayout()
        layout.addLayout(self.box)
        layout.addStretch()
        close = QDialogButtonBox(QDialogButtonBox.StandardButton.Close)
        close.button(QDialogButtonBox.StandardButton.Close).setText("Fermer")
        close.rejected.connect(self.reject)
        layout.addWidget(close)
        self.rebuild()

    def rebuild(self) -> None:
        while self.box.count():
            item = self.box.takeAt(0)
            if item.widget():
                item.widget().deleteLater()
        items = self.imports()
        if not items:
            self.box.addWidget(QLabel("Aucun import : les fichiers importés ont tous été retirés ou modifiés à la main."))
        for import_id, count, names in items:
            row = QFrame()
            h = QHBoxLayout(row)
            h.setContentsMargins(0, 4, 0, 4)
            text = QLabel(f"<b>{_html(import_date(import_id))}</b><br>{plural(count, 'fichier', 'fichiers')} · "
                          f"{_html(', '.join(names))}")
            h.addWidget(text, 1)
            b = QPushButton("Annuler cet import")
            b.clicked.connect(lambda _=False, i=import_id: self._undo(i))
            h.addWidget(b)
            self.box.addWidget(row)

    def _undo(self, import_id: str) -> None:
        answer = QMessageBox.question(self, "Annuler l'import",
                                      f"Supprimer les événements de l'import du {import_date(import_id)} ?")
        if answer == QMessageBox.StandardButton.Yes:
            self.undo(import_id)
            self.rebuild()


class ExportDialog(QDialog):
    """One calendar as .ics, or every calendar as a .zip; optionally activities and dated notes."""

    def __init__(self, parent, settings: CalendarSettings, current: str | None = None):
        super().__init__(parent)
        self.setStyleSheet(input_style())
        self.setWindowTitle("Exporter")
        layout = QVBoxLayout(self)
        self.one = QRadioButton("Un agenda (.ics)")
        self.calendar = QComboBox()
        for cal in settings.calendars:
            self.calendar.addItem(color_icon(cal.color), cal.name, cal.id)
        if current:
            self.calendar.setCurrentIndex(max(0, self.calendar.findData(current)))
        self.all = QRadioButton("Tous les agendas (.zip, un .ics par agenda)")
        self.all.setChecked(True)
        row = QHBoxLayout()
        row.addWidget(self.one)
        row.addWidget(self.calendar, 1)
        layout.addLayout(row)
        layout.addWidget(self.all)
        self.include = QCheckBox("Inclure les activités et les notes datées")
        layout.addWidget(self.include)
        self.one.toggled.connect(self.calendar.setEnabled)
        self.calendar.setEnabled(False)
        buttons = QDialogButtonBox(QDialogButtonBox.StandardButton.Ok | QDialogButtonBox.StandardButton.Cancel)
        buttons.button(QDialogButtonBox.StandardButton.Ok).setText("Exporter…")
        buttons.button(QDialogButtonBox.StandardButton.Cancel).setText("Annuler")
        buttons.accepted.connect(self.accept)
        buttons.rejected.connect(self.reject)
        layout.addWidget(buttons)

    def choice(self) -> tuple[str | None, bool]:
        """(calendar id, or None for every calendar as a zip; include activities and dated notes)."""
        return (self.calendar.currentData() if self.one.isChecked() else None), self.include.isChecked()


class CalendarsDialog(QDialog):
    """Create, rename, recolour, choose the default, delete. Every change is written at once through `screen`."""

    def __init__(self, parent, screen):
        super().__init__(parent)
        self.setStyleSheet(input_style())
        self.screen = screen
        self.setWindowTitle("Agendas")
        self.setMinimumWidth(520)
        layout = QVBoxLayout(self)
        self.grid = QGridLayout()
        self.grid.setColumnStretch(1, 1)
        layout.addLayout(self.grid)
        add_row = QHBoxLayout()
        self.new_name = QLineEdit()
        self.new_name.setPlaceholderText("Nom du nouvel agenda")
        self.new_name.returnPressed.connect(self._add)
        add = QPushButton("Créer")
        add.clicked.connect(self._add)
        add_row.addWidget(self.new_name, 1)
        add_row.addWidget(add)
        layout.addSpacing(8)
        layout.addLayout(add_row)
        layout.addStretch()
        close = QDialogButtonBox(QDialogButtonBox.StandardButton.Close)
        close.button(QDialogButtonBox.StandardButton.Close).setText("Fermer")
        close.rejected.connect(self.reject)
        layout.addWidget(close)
        self.rebuild()

    def rebuild(self) -> None:
        while self.grid.count():
            item = self.grid.takeAt(0)
            if item.widget():
                item.widget().deleteLater()
        settings: CalendarSettings = self.screen.data.settings
        counts = self.screen.calendar_counts()
        self.grid.addWidget(QLabel("Par défaut"), 0, 3, Qt.AlignmentFlag.AlignCenter)
        group = QButtonGroup(self)
        for r, cal in enumerate(settings.calendars, start=1):
            color = ColorButton(cal.color, lambda c, i=cal.id: self.screen.recolor_calendar(i, c))
            name = QLineEdit(cal.name)
            name.editingFinished.connect(lambda i=cal.id, w=name: self._rename(i, w))
            count = QLabel(plural(counts.get(cal.id, 0), "événement", "événements"))
            count.setStyleSheet(f"color: {theme.scheme().on_surface_variant};")
            default = QRadioButton()
            default.setChecked(cal.id == settings.default_calendar().id)
            default.toggled.connect(lambda on, i=cal.id: on and self.screen.set_default_calendar(i))
            group.addButton(default)
            delete = QPushButton("Supprimer")
            delete.setEnabled(len(settings.calendars) > 1)
            delete.clicked.connect(lambda _=False, c=cal: self._delete(c))
            self.grid.addWidget(color, r, 0)
            self.grid.addWidget(name, r, 1)
            self.grid.addWidget(count, r, 2)
            self.grid.addWidget(default, r, 3, Qt.AlignmentFlag.AlignCenter)
            self.grid.addWidget(delete, r, 4)

    def _rename(self, calendar_id: str, edit: QLineEdit) -> None:
        name = edit.text().strip()
        if name:
            self.screen.rename_calendar(calendar_id, name)

    def _add(self) -> None:
        name = self.new_name.text().strip()
        if not name:
            return
        self.screen.create_calendar(name)
        self.new_name.clear()
        self.rebuild()

    def _delete(self, cal) -> None:
        settings = self.screen.data.settings
        n = self.screen.calendar_counts().get(cal.id, 0)
        others = [c for c in settings.calendars if c.id != cal.id]
        fallback = settings.default_calendar() if settings.default_calendar().id != cal.id else others[0]
        box = QMessageBox(self)
        box.setWindowTitle("Supprimer l'agenda")
        if n:
            box.setText(f"Supprimer l'agenda « {cal.name} » ? Il contient {plural(n, 'événement', 'événements')}.")
            move = box.addButton(f"Déplacer vers « {fallback.name} »", QMessageBox.ButtonRole.AcceptRole)
            drop = box.addButton("Supprimer les événements", QMessageBox.ButtonRole.DestructiveRole)
        else:
            box.setText(f"Supprimer l'agenda « {cal.name} » ?")
            move = box.addButton("Supprimer", QMessageBox.ButtonRole.AcceptRole)
            drop = None
        box.addButton("Annuler", QMessageBox.ButtonRole.RejectRole)
        box.exec()
        clicked = box.clickedButton()
        if clicked is move:
            self.screen.delete_calendar(cal.id, move_to=fallback.id)
        elif drop is not None and clicked is drop:
            self.screen.delete_calendar(cal.id, move_to=None)
        else:
            return
        self.rebuild()
