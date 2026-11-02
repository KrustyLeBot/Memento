"""The Contacts module: list (favourites, letters, search, count), detail with copy buttons, editor, vCard import /
export, duplicates. Same files and algorithms as the phone (core/contacts.py, core/vcard.py); no call or SMS here."""
from __future__ import annotations

from pathlib import Path

from PySide6.QtCore import Qt, Signal
from PySide6.QtGui import QColor, QFont
from PySide6.QtWidgets import (QApplication, QButtonGroup, QCheckBox, QComboBox, QDialog, QDialogButtonBox, QFileDialog,
                               QFormLayout, QFrame, QGridLayout, QHBoxLayout, QLabel, QLineEdit, QListWidget,
                               QListWidgetItem, QMessageBox, QPlainTextEdit, QPushButton, QRadioButton, QScrollArea,
                               QSpinBox, QStackedWidget, QToolButton, QVBoxLayout, QWidget)

from ..core import contacts as ct
from ..core import vcard
from . import icons, theme

MONTHS = ["janvier", "février", "mars", "avril", "mai", "juin", "juillet", "août", "septembre", "octobre", "novembre",
          "décembre"]
ID_ROLE = Qt.ItemDataRole.UserRole


def birthday_text(b: ct.Birthday) -> str:
    return f"{b.day}{'er' if b.day == 1 else ''} {MONTHS[b.month - 1]}" + (f" {b.year}" if b.year else "")


def plural(n: int, one: str, many: str) -> str:
    return f"{n} {many if n > 1 else one}"


def ignored_text(ignored: dict[str, int]) -> str:
    return ", ".join(f"{k} ({v})" for k, v in sorted(ignored.items(), key=lambda kv: -kv[1]))


def report_text(o: ct.ImportOutcome) -> str:
    lines = [plural(len(o.created), "contact créé", "contacts créés")]
    if o.updated:
        lines.append(plural(o.updated, "contact mis à jour", "contacts mis à jour"))
    if o.unchanged:
        lines.append(plural(o.unchanged, "déjà à jour", "déjà à jour"))
    if o.skipped:
        lines.append(plural(o.skipped, "doublon non importé", "doublons non importés"))
    if o.bad:
        lines.append(plural(o.bad, "fiche illisible ignorée", "fiches illisibles ignorées"))
    if o.empty:
        lines.append(plural(o.empty, "fiche vide ignorée", "fiches vides ignorées"))
    if o.ignored:
        lines.append("Non repris : " + ignored_text(o.ignored))
    return "\n".join(lines)


class EditDialog(QDialog):
    """Create / edit a contact: Prénom, Nom, numbers with their type, e-mails, address, birthday (year optional), notes."""

    def __init__(self, parent, contact: ct.Contact | None):
        super().__init__(parent)
        self.original = contact
        c = contact or ct.Contact()
        self.setWindowTitle("Modifier le contact" if contact else "Nouveau contact")
        self.setMinimumWidth(520)
        outer = QVBoxLayout(self)
        form = QFormLayout()
        self.first = QLineEdit(c.first_name)
        self.last = QLineEdit(c.last_name)
        form.addRow("Prénom", self.first)
        form.addRow("Nom", self.last)
        outer.addLayout(form)

        outer.addWidget(QLabel("Téléphone"))
        self.phone_box = QVBoxLayout()
        outer.addLayout(self.phone_box)
        self.phone_rows: list[tuple[QWidget, QLineEdit, QComboBox]] = []
        for p in c.phones or [ct.Phone("", ct.MOBILE)]:
            self.add_phone(p)
        add_phone = QPushButton("Ajouter un numéro")
        add_phone.clicked.connect(lambda: self.add_phone(ct.Phone("", ct.MOBILE)))
        outer.addWidget(add_phone, 0, Qt.AlignmentFlag.AlignLeft)

        outer.addWidget(QLabel("E-mail"))
        self.email_box = QVBoxLayout()
        outer.addLayout(self.email_box)
        self.email_rows: list[tuple[QWidget, QLineEdit]] = []
        for e in c.emails or [""]:
            self.add_email(e)
        add_email = QPushButton("Ajouter un e-mail")
        add_email.clicked.connect(lambda: self.add_email(""))
        outer.addWidget(add_email, 0, Qt.AlignmentFlag.AlignLeft)

        form2 = QFormLayout()
        self.address = QLineEdit(c.address or "")
        form2.addRow("Adresse", self.address)
        bday = QHBoxLayout()
        self.day = QSpinBox()
        self.day.setRange(0, 31)
        self.day.setSpecialValueText("—")
        self.month = QComboBox()
        self.month.addItems(["—"] + MONTHS)
        self.year = QLineEdit()
        self.year.setPlaceholderText("Année (facultative)")
        self.year.setMaxLength(4)
        if c.birthday:
            self.day.setValue(c.birthday.day)
            self.month.setCurrentIndex(c.birthday.month)
            self.year.setText(str(c.birthday.year) if c.birthday.year else "")
        for w in (self.day, self.month, self.year):
            bday.addWidget(w)
        form2.addRow("Anniversaire", bday)
        self.favorite = QCheckBox("Favori")
        self.favorite.setChecked(c.favorite)
        form2.addRow("", self.favorite)
        outer.addLayout(form2)
        outer.addWidget(QLabel("Notes"))
        self.notes = QPlainTextEdit(c.notes)
        self.notes.setMinimumHeight(100)
        outer.addWidget(self.notes)
        self.error = QLabel()
        self.error.setStyleSheet(f"color: {theme.scheme().error};")
        outer.addWidget(self.error)
        buttons = QDialogButtonBox(QDialogButtonBox.StandardButton.Save | QDialogButtonBox.StandardButton.Cancel)
        buttons.button(QDialogButtonBox.StandardButton.Save).setText("Enregistrer")
        buttons.button(QDialogButtonBox.StandardButton.Cancel).setText("Annuler")
        buttons.accepted.connect(self._accept)
        buttons.rejected.connect(self.reject)
        outer.addWidget(buttons)

    def add_phone(self, p: ct.Phone) -> None:
        row = QWidget()
        h = QHBoxLayout(row)
        h.setContentsMargins(0, 0, 0, 0)
        number = QLineEdit(p.number)
        number.setPlaceholderText("06 12 34 56 78")
        kind = QComboBox()
        kind.setEditable(True)
        kind.addItems(ct.STANDARD_LABELS)
        kind.setCurrentText(p.label or "")
        remove = QToolButton()
        remove.setIcon(icons.icon("close", theme.scheme().on_surface, 18))
        remove.setToolTip("Retirer ce numéro")
        entry = (row, number, kind)
        remove.clicked.connect(lambda: self._remove(self.phone_rows, entry))
        for w in (number, kind, remove):
            h.addWidget(w)
        h.setStretch(0, 1)
        self.phone_rows.append(entry)
        self.phone_box.addWidget(row)

    def add_email(self, e: str) -> None:
        row = QWidget()
        h = QHBoxLayout(row)
        h.setContentsMargins(0, 0, 0, 0)
        edit = QLineEdit(e)
        remove = QToolButton()
        remove.setIcon(icons.icon("close", theme.scheme().on_surface, 18))
        remove.setToolTip("Retirer cet e-mail")
        entry = (row, edit)
        remove.clicked.connect(lambda: self._remove(self.email_rows, entry))
        h.addWidget(edit, 1)
        h.addWidget(remove)
        self.email_rows.append(entry)
        self.email_box.addWidget(row)

    @staticmethod
    def _remove(rows: list, entry) -> None:
        rows.remove(entry)
        entry[0].deleteLater()

    def birthday(self) -> ct.Birthday | None:
        """None when nothing is typed; raises ValueError when what is typed is not a date."""
        day, month, year = self.day.value(), self.month.currentIndex(), self.year.text().strip()
        if not day and not month and not year:
            return None
        if not day or not month:
            raise ValueError("Jour et mois de l'anniversaire")
        if year and not year.isdigit():
            raise ValueError("Année invalide")
        b = ct.Birthday.of(day, month, int(year) if year else None)
        if b is None:
            raise ValueError("Date d'anniversaire invalide")
        return b

    def contact(self) -> ct.Contact:
        base = self.original or ct.Contact()
        return base.copy(
            first_name=self.first.text().strip(), last_name=self.last.text().strip(),
            phones=[ct.Phone(n.text().strip(), k.currentText().strip() or None) for _, n, k in self.phone_rows if n.text().strip()],
            emails=[e.text().strip() for _, e in self.email_rows if e.text().strip()],
            address=self.address.text().strip() or None, birthday=self.birthday(),
            notes=self.notes.toPlainText().rstrip(), favorite=self.favorite.isChecked())

    def _accept(self) -> None:
        try:
            c = self.contact()
        except ValueError as error:
            self.error.setText(str(error))
            return
        if c.is_empty:
            self.error.setText("Le contact est vide")
            return
        self.accept()


class ImportDialog(QDialog):
    """Preview of a .vcf (cards, likely duplicates, cards without name or number) and the duplicate policy."""

    def __init__(self, parent, name: str, p: ct.ImportPreview):
        super().__init__(parent)
        self.setWindowTitle("Importer des contacts")
        layout = QVBoxLayout(self)
        layout.addWidget(QLabel(name))
        title = QLabel(plural(p.cards, "contact à importer", "contacts à importer"))
        title.setStyleSheet("font-weight: 600;")
        layout.addWidget(title)
        for n, one, many in ((p.duplicates, "ressemble à un contact déjà présent", "ressemblent à des contacts déjà présents"),
                             (p.blank, "fiche sans nom ni numéro", "fiches sans nom ni numéro"),
                             (p.bad, "fiche illisible (ignorée)", "fiches illisibles (ignorées)"),
                             (p.empty, "fiche vide (ignorée)", "fiches vides (ignorées)")):
            if n:
                layout.addWidget(QLabel(plural(n, one, many)))
        if p.ignored:
            note = QLabel("Non repris : " + ignored_text(p.ignored))
            note.setWordWrap(True)
            layout.addWidget(note)
        layout.addWidget(QLabel("Doublons"))
        self.group = QButtonGroup(self)
        self.radios: dict[ct.DuplicatePolicy, QRadioButton] = {}
        for policy in ct.DuplicatePolicy:
            radio = QRadioButton(policy.value)
            self.group.addButton(radio)
            self.radios[policy] = radio
            layout.addWidget(radio)
        self.radios[ct.DuplicatePolicy.KEEP_BOTH].setChecked(True)
        buttons = QDialogButtonBox(QDialogButtonBox.StandardButton.Ok | QDialogButtonBox.StandardButton.Cancel)
        buttons.button(QDialogButtonBox.StandardButton.Ok).setText("Importer")
        buttons.button(QDialogButtonBox.StandardButton.Ok).setEnabled(p.cards > 0)
        buttons.button(QDialogButtonBox.StandardButton.Cancel).setText("Annuler")
        buttons.accepted.connect(self.accept)
        buttons.rejected.connect(self.reject)
        layout.addWidget(buttons)

    def policy(self) -> ct.DuplicatePolicy:
        return next(p for p, r in self.radios.items() if r.isChecked())


class DuplicatesDialog(QDialog):
    """Groups of contacts sharing a number or a name; per group, "Fusionner" (keep the ticked one) or "Ignorer"."""

    def __init__(self, parent, screen: "ContactsScreen"):
        super().__init__(parent)
        self.screen = screen
        self.ignored: list[set[str]] = []
        self.setWindowTitle("Doublons")
        self.resize(560, 520)
        layout = QVBoxLayout(self)
        self.info = QLabel()
        self.info.setWordWrap(True)
        layout.addWidget(self.info)
        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        self.host = QWidget()
        self.box = QVBoxLayout(self.host)
        scroll.setWidget(self.host)
        layout.addWidget(scroll, 1)
        close = QDialogButtonBox(QDialogButtonBox.StandardButton.Close)
        close.button(QDialogButtonBox.StandardButton.Close).setText("Fermer")
        close.rejected.connect(self.reject)
        layout.addWidget(close)
        self.groups: list[tuple[list[ct.Contact], QButtonGroup]] = []
        self.rebuild()

    def rebuild(self) -> None:
        while self.box.count():
            item = self.box.takeAt(0)
            if item.widget():
                item.widget().deleteLater()
        live = [c for c in self.screen.store.contacts.contacts if not c.conflict]
        groups = [g for g in ct.duplicate_groups(live, self.screen.first_name_first)
                  if {c.id for c in g} not in self.ignored]
        self.info.setText("Aucun doublon : pas de numéro ni de nom en double." if not groups else
                          f"{len(groups)} groupe{'s' if len(groups) > 1 else ''} : même numéro ou même nom. Choisis la "
                          "fiche à garder ; elle reçoit les numéros, e-mails et notes des autres, qui sont supprimées.")
        self.groups = []
        for group in groups:
            frame = QFrame()
            frame.setObjectName("pill")
            v = QVBoxLayout(frame)
            choice = QButtonGroup(frame)
            keep = ct.best(group).id
            for i, c in enumerate(group):
                details = " · ".join([p.number for p in c.phones] + c.emails)
                radio = QRadioButton(c.display_name + (f"  —  {details}" if details else ""))
                radio.setChecked(c.id == keep)
                choice.addButton(radio, i)
                v.addWidget(radio)
            row = QHBoxLayout()
            row.addStretch()
            ignore = QPushButton("Ignorer")
            merge = QPushButton("Fusionner")
            merge.setObjectName("primary")
            ignore.clicked.connect(lambda _=False, g=group: self.ignore(g))
            merge.clicked.connect(lambda _=False, g=group, ch=choice: self.merge(g, g[ch.checkedId()]))
            row.addWidget(ignore)
            row.addWidget(merge)
            v.addLayout(row)
            self.box.addWidget(frame)
            self.groups.append((group, choice))
        self.box.addStretch()

    def ignore(self, group: list[ct.Contact]) -> None:
        self.ignored.append({c.id for c in group})
        self.rebuild()

    def merge(self, group: list[ct.Contact], keep: ct.Contact) -> None:
        self.screen.merge(keep, [c for c in group if c.id != keep.id])
        self.rebuild()


class ContactsScreen(QWidget):
    message = Signal(str, object)  # text, undo callback or None
    changed = Signal()

    def __init__(self):
        super().__init__()
        self.store = None
        self.first_name_first = False
        self.current: ct.Contact | None = None
        self.last_import: ct.ImportOutcome | None = None
        outer = QVBoxLayout(self)
        outer.setContentsMargins(8, 8, 8, 0)
        self.stack = QStackedWidget()
        outer.addWidget(self.stack)

        # ---- list ----
        page = QWidget()
        v = QVBoxLayout(page)
        top = QHBoxLayout()
        title = QLabel("Contacts")
        title.setStyleSheet("font-size: 16pt; font-weight: 600;")
        top.addWidget(title)
        self.count = QLabel()
        top.addWidget(self.count)
        top.addStretch()
        self.new_button = QPushButton("Nouveau contact")
        self.new_button.setObjectName("primary")
        self.new_button.clicked.connect(self.new_contact)
        self.import_button = QPushButton("Importer…")
        self.import_button.clicked.connect(lambda: self.import_file())
        self.export_button = QPushButton("Exporter…")
        self.export_button.clicked.connect(lambda: self.export_all())
        self.duplicates_button = QPushButton("Doublons…")
        self.duplicates_button.clicked.connect(self.show_duplicates)
        for b in (self.new_button, self.import_button, self.export_button, self.duplicates_button):
            top.addWidget(b)
        v.addLayout(top)
        self.search = QLineEdit()
        self.search.setPlaceholderText("Rechercher (nom, numéro, e-mail, notes)")
        self.search.setClearButtonEnabled(True)
        self.search.textChanged.connect(lambda _: self.rebuild())
        v.addWidget(self.search)
        self.list = QListWidget()
        self.list.itemActivated.connect(self._activated)
        self.list.itemClicked.connect(self._activated)
        v.addWidget(self.list, 1)
        self.stack.addWidget(page)

        # ---- detail ----
        self.detail = QWidget()
        d = QVBoxLayout(self.detail)
        bar = QHBoxLayout()
        self.back = QToolButton()
        self.back.setToolTip("Retour")
        self.back.clicked.connect(self.show_list)
        bar.addWidget(self.back)
        self.name = QLabel()
        self.name.setStyleSheet("font-size: 16pt; font-weight: 600;")
        self.name.setTextInteractionFlags(Qt.TextInteractionFlag.TextSelectableByMouse)
        bar.addWidget(self.name, 1)
        self.fav_button = QPushButton()
        self.fav_button.clicked.connect(lambda: self.toggle_favorite())
        edit = QPushButton("Modifier")
        edit.clicked.connect(lambda: self.edit_contact())
        share = QPushButton("Exporter (.vcf)")
        share.clicked.connect(lambda: self.export_one())
        delete = QPushButton("Supprimer")
        delete.clicked.connect(lambda: self.delete_current())
        for b in (self.fav_button, edit, share, delete):
            bar.addWidget(b)
        d.addLayout(bar)
        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        self.rows_host = QWidget()
        self.rows = QGridLayout(self.rows_host)
        self.rows.setColumnStretch(1, 1)
        self.rows.setAlignment(Qt.AlignmentFlag.AlignTop)
        scroll.setWidget(self.rows_host)
        d.addWidget(scroll, 1)
        self.stack.addWidget(self.detail)
        self.restyle()

    # ---- plumbing ----

    def set_store(self, store) -> None:
        self.store = store
        self.current = None
        self.stack.setCurrentIndex(0)

    def set_first_name_first(self, value: bool) -> None:
        self.first_name_first = value
        self.rebuild()

    def restyle(self) -> None:
        self.back.setIcon(icons.icon("back", theme.scheme().on_surface, 24))
        self.count.setStyleSheet(f"color: {theme.scheme().on_surface_variant};")

    def refresh(self) -> None:
        if self.store is None:
            return
        self.store.scan_contacts()
        self.rebuild()
        if self.current is not None:
            fresh = self.store.contacts.by_id(self.current.id)
            if fresh is None:
                self.show_list()  # deleted elsewhere
            else:
                self.show_contact(fresh)

    def contacts(self) -> list[ct.Contact]:
        return self.store.contacts.contacts if self.store else []

    def visible_contacts(self) -> list[ct.Contact]:
        """The contact rows of the list, in order (headers left out)."""
        out = []
        for i in range(self.list.count()):
            cid = self.list.item(i).data(ID_ROLE)
            if cid:
                out.append(next(c for c in self.contacts() if c.path == cid))
        return out

    def headers(self) -> list[str]:
        return [self.list.item(i).text() for i in range(self.list.count()) if not self.list.item(i).data(ID_ROLE)]

    def rebuild(self) -> None:
        self.list.clear()
        people = self.contacts()
        query = self.search.text()
        bold = QFont()
        bold.setBold(True)

        def add_contact(c: ct.Contact) -> None:
            second = c.phones[0].number if c.phones else (c.emails[0] if c.emails else "")
            text = ("★ " if c.favorite else "") + c.display_name + (" (copie en conflit)" if c.conflict else "")
            if second and second != c.display_name:
                text += f"    {second}"
            item = QListWidgetItem(text)
            item.setData(ID_ROLE, c.path)
            self.list.addItem(item)

        if query.strip():
            found = ct.search(people, query, self.first_name_first)
            self.count.setText(plural(len(found), "résultat", "résultats"))
            for c in found:
                add_contact(c)
            return
        self.count.setText(ct.count_label(sum(1 for c in people if not c.conflict)))
        if not people:
            item = QListWidgetItem("Aucun contact. Crée-en un, ou importe un fichier .vcf (Importer…).")
            item.setFlags(Qt.ItemFlag.NoItemFlags)
            self.list.addItem(item)
        for title, members in ct.sections(people, self.first_name_first):
            header = QListWidgetItem(title)
            header.setFont(bold)
            header.setForeground(QColor(theme.scheme().primary))
            header.setFlags(Qt.ItemFlag.NoItemFlags)
            self.list.addItem(header)
            for c in members:
                add_contact(c)

    def _activated(self, item: QListWidgetItem) -> None:
        path = item.data(ID_ROLE)
        c = next((x for x in self.contacts() if x.path == path), None) if path else None
        if c is not None:
            self.show_contact(c)

    def focus_search(self) -> None:
        self.search.setFocus()
        self.search.selectAll()

    # ---- detail ----

    def show_list(self) -> None:
        self.current = None
        self.stack.setCurrentIndex(0)

    def show_contact(self, c: ct.Contact) -> None:
        self.current = c
        self.name.setText(c.display_name)
        self.fav_button.setText("Retirer des favoris" if c.favorite else "Ajouter aux favoris")
        while self.rows.count():
            w = self.rows.takeAt(0).widget()
            if w:
                w.deleteLater()
        r = 0

        def row(kind: str, text: str, what: str | None) -> None:
            nonlocal r
            label = QLabel(kind)
            label.setStyleSheet(f"color: {theme.scheme().primary};")
            value = QLabel(text)
            value.setWordWrap(True)
            value.setTextInteractionFlags(Qt.TextInteractionFlag.TextSelectableByMouse)
            self.rows.addWidget(label, r, 0, Qt.AlignmentFlag.AlignTop)
            self.rows.addWidget(value, r, 1)
            if what:
                copy = QPushButton("Copier")
                copy.clicked.connect(lambda _=False, t=text, w=what: self.copy(t, w))
                self.rows.addWidget(copy, r, 2)
            r += 1

        for p in c.phones:
            row(f"Téléphone{f' ({p.label})' if p.label else ''}", p.number, "Numéro copié")
        for e in c.emails:
            row("E-mail", e, "E-mail copié")
        if c.address:
            row("Adresse", c.address, "Adresse copiée")
        if c.birthday:
            row("Anniversaire", birthday_text(c.birthday), None)
        if c.notes.strip():
            row("Notes", c.notes, None)
        if c.conflict:
            row("Synchro", "Copie en conflit : supprime celle qui est en trop", None)
        self.stack.setCurrentIndex(1)

    def copy(self, text: str, what: str) -> None:
        QApplication.clipboard().setText(text)
        self.message.emit(what, None)

    # ---- editing ----

    def new_contact(self) -> None:
        dialog = EditDialog(self, None)
        if dialog.exec() == QDialog.DialogCode.Accepted:
            self.save(dialog.contact())

    def edit_contact(self, dialog_factory=None) -> None:
        if self.current is None:
            return
        dialog = (dialog_factory or EditDialog)(self, self.current)
        if dialog.exec() == QDialog.DialogCode.Accepted:
            self.save(dialog.contact())

    def save(self, c: ct.Contact) -> ct.Contact:
        saved = self.store.contacts.save(c)
        self.changed.emit()
        self.rebuild()
        self.show_contact(saved)
        return saved

    def toggle_favorite(self) -> None:
        if self.current is not None:
            self.save(self.current.copy(favorite=not self.current.favorite))

    def delete_current(self) -> None:
        """Deletes for good; the snackbar offers to put the file back exactly as it was."""
        c = self.current
        if c is None:
            return
        raw = self.store.contacts.delete(c)
        self.show_list()
        self.rebuild()
        self.changed.emit()

        def undo() -> None:
            self.store.contacts.restore(c.path, raw)
            self.rebuild()
            self.changed.emit()

        self.message.emit("Contact supprimé", undo if raw is not None else None)

    def merge(self, keep: ct.Contact, others: list[ct.Contact]) -> None:
        texts = self.store.contacts.merge(keep, others)
        self.rebuild()
        self.changed.emit()

        def undo() -> None:
            self.store.contacts.restore_all(texts)
            self.rebuild()
            self.changed.emit()

        self.message.emit(f"{len(others) + 1} contacts fusionnés", undo)

    def show_duplicates(self) -> None:
        DuplicatesDialog(self, self).exec()

    # ---- import / export ----

    def read_vcard(self, path: Path) -> vcard.VCardResult:
        if path.stat().st_size > 20_000_000:
            raise OSError("Fichier trop gros")
        result = vcard.parse(path.read_bytes())
        if not result.contacts and not result.bad and not result.empty:
            raise OSError("Aucune fiche vCard dans ce fichier")
        return result

    def run_import(self, result: vcard.VCardResult, policy: ct.DuplicatePolicy) -> ct.ImportOutcome:
        now = ct.now_ms()
        import_id = ct.new_import_id(now)
        self.store.scan_contacts()
        outcome = self.store.contacts.apply(ct.plan(result, self.store.contacts.contacts, policy, import_id, now), import_id, result)
        self.last_import = outcome
        self.rebuild()
        self.changed.emit()
        return outcome

    def undo_import(self, outcome: ct.ImportOutcome) -> int:
        n = self.store.contacts.undo(outcome)
        self.last_import = None
        self.rebuild()
        self.changed.emit()
        return n

    def import_file(self, path: Path | None = None) -> None:
        if path is None:
            chosen, _ = QFileDialog.getOpenFileName(self, "Importer des contacts", str(Path.home()),
                                                    "vCard (*.vcf *.vcard);;Tous les fichiers (*)")
            if not chosen:
                return
            path = Path(chosen)
        try:
            result = self.read_vcard(path)
        except (OSError, ValueError) as error:
            QMessageBox.warning(self, "Import", f"Import impossible : {error}")
            return
        self.store.scan_contacts()
        dialog = ImportDialog(self, path.name, ct.preview(result, self.store.contacts.contacts))
        if dialog.exec() != QDialog.DialogCode.Accepted:
            return
        outcome = self.run_import(result, dialog.policy())
        box = QMessageBox(self)
        box.setWindowTitle("Import terminé")
        box.setText(report_text(outcome))
        ok = box.addButton("OK", QMessageBox.ButtonRole.AcceptRole)
        cancel = box.addButton("Annuler l'import", QMessageBox.ButtonRole.DestructiveRole)
        box.setDefaultButton(ok)
        box.exec()
        if box.clickedButton() is cancel:
            n = self.undo_import(outcome)
            self.message.emit(f"Import annulé ({plural(n, 'fichier', 'fichiers')})", None)

    def export_to(self, path: Path, people: list[ct.Contact]) -> None:
        path.write_text(vcard.write(people), encoding="utf-8", newline="")

    def export_all(self, path: Path | None = None) -> None:
        if path is None:
            chosen, _ = QFileDialog.getSaveFileName(self, "Exporter les contacts", str(Path.home() / "contacts.vcf"),
                                                    "vCard (*.vcf)")
            if not chosen:
                return
            path = Path(chosen)
        people = ct.sorted_contacts([c for c in self.contacts() if not c.conflict], self.first_name_first)
        try:
            self.export_to(path, people)
        except OSError as error:
            QMessageBox.warning(self, "Export", f"Export impossible : {error}")
            return
        self.message.emit(plural(len(people), "contact exporté", "contacts exportés"), None)

    def export_one(self, path: Path | None = None) -> None:
        c = self.current
        if c is None:
            return
        if path is None:
            from ..core import note as fmt
            chosen, _ = QFileDialog.getSaveFileName(self, "Exporter le contact",
                                                    str(Path.home() / f"{fmt.slug(c.display_name)}.vcf"), "vCard (*.vcf)")
            if not chosen:
                return
            path = Path(chosen)
        try:
            self.export_to(path, [c])
        except OSError as error:
            QMessageBox.warning(self, "Export", f"Export impossible : {error}")
            return
        self.message.emit("Contact exporté", None)
