"""`agenda/` on a small file-system interface (`AgendaFs`), so the agenda logic and the ICS import are
tested on memory. `DirFs` is the real folder (atomic writes), `MemoryFs` the test double.

Layout: `agenda/<year of the start>/<slug>-<id6>.md` (a series lives in the year of its first occurrence),
`agenda/calendars.json`. Files directly in `agenda/` are read too (made by hand). The path is fixed at
creation and never changes, even when the start moves to another year.
"""
from __future__ import annotations

import os
from dataclasses import dataclass, field
from datetime import date
from pathlib import Path
from typing import Protocol

from .. import atomic
from .. import note as fmt
from .calendars import PATH as CALENDARS_PATH, CalendarSettings
from .model import AGENDA_DIR, AgendaFormatError, Event, event_from_note, event_to_note, stamp_date


class AgendaFs(Protocol):
    """Paths are relative to the notes folder and use "/"."""

    def list(self, directory: str) -> list[tuple[str, bool]]: ...  # (name, is_dir); [] when missing
    def read_text(self, path: str) -> str | None: ...               # None when missing
    def write_text(self, path: str, text: str) -> None: ...         # atomic, creates the folders
    def delete(self, path: str) -> None: ...                        # no error when missing
    def mtime(self, path: str) -> int: ...                          # epoch millis, 0 when missing


class DirFs:
    def __init__(self, root: Path):
        self.root = Path(root)

    def list(self, directory: str) -> list[tuple[str, bool]]:
        try:
            return [(e.name, e.is_dir()) for e in os.scandir(self.root / directory)]
        except OSError:
            return []

    def read_text(self, path: str) -> str | None:
        try:
            return (self.root / path).read_text(encoding="utf-8")
        except FileNotFoundError:
            return None

    def write_text(self, path: str, text: str) -> None:
        atomic.write_text(self.root / path, text)

    def delete(self, path: str) -> None:
        try:
            (self.root / path).unlink()
        except FileNotFoundError:
            pass

    def mtime(self, path: str) -> int:
        try:
            return (self.root / path).stat().st_mtime_ns // 1_000_000
        except OSError:
            return 0


class MemoryFs:
    def __init__(self, files: dict[str, str] | None = None):
        self.files: dict[str, str] = dict(files or {})

    def list(self, directory: str) -> list[tuple[str, bool]]:
        prefix = f"{directory}/" if directory else ""
        out: dict[str, bool] = {}
        for path in self.files:
            if path.startswith(prefix):
                rest = path[len(prefix):]
                name, sep, _ = rest.partition("/")
                out[name] = out.get(name, False) or bool(sep)
        return list(out.items())

    def read_text(self, path: str) -> str | None:
        return self.files.get(path)

    def write_text(self, path: str, text: str) -> None:
        self.files[path] = text

    def delete(self, path: str) -> None:
        self.files.pop(path, None)

    def mtime(self, path: str) -> int:
        return 1_790_000_000_000 if path in self.files else 0


@dataclass
class AgendaScan:
    events: list[Event] = field(default_factory=list)
    problems: list[str] = field(default_factory=list)  # unreadable files (French), never silently dropped


def _is_event_file(name: str) -> bool:
    return name.endswith(".md") and not name.startswith(".") and not atomic.is_temp(name)


class AgendaStore:
    def __init__(self, fs: AgendaFs):
        self.fs = fs
        self._ids: set[str] = set()

    # ---- reading ----

    def paths(self) -> list[str]:
        out = []
        for name, is_dir in sorted(self.fs.list(AGENDA_DIR)):
            if is_dir and not name.startswith("."):
                out += [f"{AGENDA_DIR}/{name}/{n}" for n, d in sorted(self.fs.list(f"{AGENDA_DIR}/{name}"))
                        if not d and _is_event_file(n)]
            elif not is_dir and _is_event_file(name):
                out.append(f"{AGENDA_DIR}/{name}")
        return out

    def read(self, path: str) -> Event:
        """Raises AgendaFormatError (unreadable start, or no start at all) or OSError."""
        text = self.fs.read_text(path)
        if text is None:
            raise AgendaFormatError(f"{path} : fichier introuvable")
        note = fmt.parse(path, text, self.fs.mtime(path))
        event = event_from_note(note)
        if event is None:
            raise AgendaFormatError(f"{path} : pas de date de début (start)")
        return event

    def scan(self) -> AgendaScan:
        result = AgendaScan()
        for path in self.paths():
            try:
                result.events.append(self.read(path))
            except (AgendaFormatError, OSError, UnicodeDecodeError) as error:
                result.problems.append(str(error) if isinstance(error, AgendaFormatError) else f"{path} : {error}")
        self._ids = {e.id for e in result.events}
        return result

    def remember_ids(self, ids: set[str]) -> None:
        """The ids found by a scan made elsewhere (the window's incremental one): `save` keeps avoiding them."""
        self._ids = set(ids)

    def calendars(self) -> CalendarSettings:
        text = self.fs.read_text(CALENDARS_PATH)
        return CalendarSettings.parse(text) if text is not None else CalendarSettings()

    def save_calendars(self, settings: CalendarSettings) -> None:
        self.fs.write_text(CALENDARS_PATH, settings.to_json())

    # ---- writing ----

    def path_for(self, title: str, event_id: str, start: date) -> str:
        return f"{AGENDA_DIR}/{stamp_date(start).year:04d}/{fmt.file_name_for(title, event_id)}"

    def save(self, event: Event, taken_ids: set[str] | frozenset = frozenset()) -> Event:
        """Writes the event; a new one (empty path) gets a free id and its path. `taken_ids`: other ids in
        use (notes, activities) the new id must avoid."""
        if not event.path:
            event_id = event.id
            while not event_id or event_id in self._ids or event_id in taken_ids:
                event_id = fmt.generate_id()
            uid = event.uid if event.uid and event.uid != f"{event.id}@memento" else f"{event_id}@memento"
            event = event.copy(uid=uid, note=event.note.copy(id=event_id, path=self.path_for(event.title, event_id, event.start)))
        event = event.with_note(has_id=True)
        self.fs.write_text(event.path, fmt.serialize(event_to_note(event)))
        self._ids.add(event.id)
        return event

    def delete(self, event: Event) -> None:
        """Removes the file and its attachments (like a note)."""
        for path in [event.path, event.note.pdf, *event.note.attachments]:
            if path:
                self.fs.delete(path)
        self._ids.discard(event.id)

    # ---- one occurrence of a series ----

    def delete_occurrence(self, series: Event, occurrence_start: date) -> Event:
        """"Supprimer cet événement" on a series: the occurrence goes into `exdate`."""
        if occurrence_start in series.exdate:
            return series
        return self.save(series.copy(exdate=series.exdate + [occurrence_start]))

    def make_exception(self, series: Event, occurrence_start: date, occurrence_end: date | None) -> Event:
        """An unsaved copy of the series for one occurrence ("Modifier cet événement"): no rule, own id,
        `recurrence_of`/`recurrence_at` set, same uid (ICS overrides share the series UID)."""
        return series.copy(
            note=series.note.copy(path="", id=fmt.generate_id(), attachments=[], pdf=None),
            start=occurrence_start, end=occurrence_end, rrule=None, exdate=[],
            recurrence_of=series.id, recurrence_at=occurrence_start, import_id=None, warnings=[],
        )

    def save_exception(self, series: Event, exception: Event) -> tuple[Event, Event]:
        """Writes the exception file and adds its occurrence to the series' `exdate` (both files)."""
        at = exception.recurrence_at
        if exception.recurrence_of != series.id or at is None:
            raise ValueError("exception d'une autre série")
        saved = self.save(exception)
        if at not in series.exdate:
            series = self.save(series.copy(exdate=series.exdate + [at]))
        return series, saved

    def restore_occurrence(self, series: Event, exception: Event | None, occurrence_start: date) -> Event:
        """"Rétablir": deletes the exception file (if any) and removes the occurrence from `exdate`."""
        if exception is not None:
            self.delete(exception)
        if occurrence_start in series.exdate:
            series = self.save(series.copy(exdate=[x for x in series.exdate if x != occurrence_start]))
        return series

    def delete_series(self, series: Event, events: list[Event]) -> None:
        """"Tous les événements": the series and its exception files."""
        for ev in events:
            if ev.recurrence_of == series.id:
                self.delete(ev)
        self.delete(series)
