"""The notes folder is the source of truth; this is a thin cache over it."""
from __future__ import annotations

import os
import re
import threading
import time
from collections import OrderedDict
from pathlib import Path

from . import atomic
from . import elevation, fit, gpx
from . import note as fmt
from .activity import ActivityInfo
from .agenda.model import AGENDA_DIR
from .agenda.store import AgendaStore, DirFs
from .contacts import DIR as CONTACTS_DIR, Contact, ContactBook
from .analysis import Analysis, analyze
from .note import Note
from .settings import PATH as SETTINGS_PATH, ActivitySettings
from .track import Sport, Track

ACTIVITIES_DIR = "activities"
# Where file_path() points a path that would leave the folder: never created, so reading, opening or deleting it
# finds nothing ("not synced yet") instead of touching a file outside.
REFUSED = ".memento-refused/refused"


# Above this, a file is refused: it is synced to every device (same limit as the Android app).
MAX_ATTACHMENT = 50_000_000
ANALYSES_KEPT = 4


def now_ms() -> int:
    return int(time.time() * 1000)


def inside(root: Path, relative: str) -> bool:
    """Whether `relative` (from a file's frontmatter: untrusted, it may arrive by sync) names a file inside root:
    no absolute path, drive, UNC share, `..`, NTFS stream or NUL, and no symlink leading out."""
    if not isinstance(relative, str) or not relative or "\x00" in relative:
        return False
    parts = re.split(r"[\\/]", relative)
    if any(p in ("", ".", "..") or ":" in p for p in parts):
        return False
    try:
        real_root = root.resolve()
        real = (root / relative).resolve()
    except (OSError, RuntimeError, ValueError):
        return False
    return real != real_root and real_root in real.parents


def default_title(sport: Sport, start: int, name: str | None = None) -> str:
    if name and name.strip():
        return name.strip()
    from datetime import datetime
    months = ["janv.", "févr.", "mars", "avr.", "mai", "juin", "juil.", "août", "sept.", "oct.", "nov.", "déc."]
    d = datetime.fromtimestamp(start / 1000)
    return f"{sport.label.split(' (')[0]} du {d.day} {months[d.month - 1]} {d.year}"


class Store:
    def __init__(self, root: Path):
        self.root = Path(root)
        self._cache: dict[str, tuple[int, Note]] = {}  # path -> (mtime_ns, note)
        self.agenda = AgendaStore(DirFs(self.root))  # agenda/: events, calendars.json (core/agenda)
        self.contacts = ContactBook(self.root)  # contacts/, kept out of the regular list
        # the last activities analysed, (track, analysis) by _analysis_key: reopening one, or opening it right after
        # its import, reads and analyses nothing (seconds for a 200 000-point race)
        self._analyses: OrderedDict[tuple, tuple[Track, Analysis]] = OrderedDict()
        self._analyses_lock = threading.Lock()

    # ---- reading ----

    def scan(self) -> list[Note]:
        """Reads only the files whose modification time changed."""
        notes: list[Note] = []
        seen: set[str] = set()
        try:
            entries = list(os.scandir(self.root))
        except OSError:
            entries = []
        for entry in entries:
            name = entry.name
            if not entry.is_file() or not name.endswith(".md") or name.startswith("."):
                continue
            seen.add(name)
            mtime = entry.stat().st_mtime_ns
            cached = self._cache.get(name)
            if cached and cached[0] == mtime:
                notes.append(cached[1])
                continue
            try:
                text = (self.root / name).read_text(encoding="utf-8")
            except (OSError, UnicodeDecodeError):
                continue
            note = fmt.parse(name, text, mtime // 1_000_000)
            self._cache[name] = (mtime, note)
            notes.append(note)
        for stale in [k for k in self._cache if "/" not in k and k not in seen]:  # activities/ is scan_activities'
            del self._cache[stale]
        return notes

    def scan_activities(self) -> list[Note]:
        """The notes of the "Activités" folder (activities/*.md), kept out of the regular list."""
        notes: list[Note] = []
        seen: set[str] = set()
        try:
            entries = list(os.scandir(self.root / ACTIVITIES_DIR))
        except OSError:
            entries = []
        for entry in entries:
            if not entry.is_file() or not entry.name.endswith(".md") or entry.name.startswith("."):
                continue
            path = f"{ACTIVITIES_DIR}/{entry.name}"
            seen.add(path)
            mtime = entry.stat().st_mtime_ns
            cached = self._cache.get(path)
            if cached and cached[0] == mtime:
                notes.append(cached[1])
                continue
            try:
                text = (self.root / path).read_text(encoding="utf-8")
            except (OSError, UnicodeDecodeError):
                continue
            note = fmt.parse(path, text, mtime // 1_000_000)
            self._cache[path] = (mtime, note)
            notes.append(note)
        for stale in [k for k in self._cache if k.startswith(f"{ACTIVITIES_DIR}/")] :
            if stale not in seen:
                del self._cache[stale]
        return notes

    def cached(self) -> dict[str, tuple[int, Note]]:
        """The parsed notes and activities kept by scan()/scan_activities(): path -> (mtime_ns, note)."""
        return dict(self._cache)

    def seed(self, path: str, mtime_ns: int, note: Note) -> None:
        """Hands scan() a note parsed elsewhere (a background read, a saved index): used while the file's
        modification time is still mtime_ns, re-read otherwise."""
        cached = self._cache.get(path)
        if cached is None or cached[0] != mtime_ns:
            self._cache[path] = (mtime_ns, note)

    def scan_contacts(self) -> list[Contact]:
        """The contacts (contacts/*.md); also what the agenda reads for the birthdays."""
        return self.contacts.scan()

    def activity_settings(self) -> ActivitySettings:
        text = self.read_raw(SETTINGS_PATH)
        return ActivitySettings.parse(text) if text else ActivitySettings()

    def save_activity_settings(self, settings: ActivitySettings) -> None:
        self._write(SETTINGS_PATH, settings.to_json())

    def read_raw(self, path: str) -> str | None:
        try:
            return (self.root / path).read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError):
            return None

    def snapshot(self):
        """Cheap fingerprint of the folder (names, times, sizes): tells whether another device changed it.
        None when the folder cannot be read right now (share offline)."""
        try:
            files = sorted((e.name, e.stat().st_mtime_ns, e.stat().st_size)
                           for e in os.scandir(self.root) if e.is_file() and not e.name.startswith("."))
        except OSError:
            return None
        try:
            acts = sorted((e.name, e.stat().st_mtime_ns, e.stat().st_size)
                          for e in os.scandir(self.root / ACTIVITIES_DIR) if e.is_file() and not e.name.startswith("."))
        except OSError:
            acts = []
        agenda = []
        try:
            for sub in os.scandir(self.root / AGENDA_DIR):
                if sub.is_dir() and not sub.name.startswith("."):
                    agenda += [(f"{sub.name}/{e.name}", e.stat().st_mtime_ns, e.stat().st_size)
                               for e in os.scandir(sub.path) if e.is_file() and not e.name.startswith(".")]
                elif sub.is_file() and not sub.name.startswith("."):
                    agenda.append((sub.name, sub.stat().st_mtime_ns, sub.stat().st_size))
        except OSError:
            pass
        try:
            people = sorted((e.name, e.stat().st_mtime_ns, e.stat().st_size)
                            for e in os.scandir(self.root / CONTACTS_DIR) if e.is_file() and not e.name.startswith("."))
        except OSError:
            people = []
        return tuple(files), tuple(acts), tuple(sorted(agenda)), tuple(people)

    def labels(self) -> list[str]:
        return sorted({lbl for n in self.scan() for lbl in n.labels}, key=fmt.fold)

    # ---- writing ----

    def _write(self, path: str, text: str) -> None:
        atomic.write_text(self.root / path, text)

    def save(self, note: Note) -> Note:
        """Writes the note; the file is created (and its name fixed) on the first save."""
        if not note.path:
            taken = {n.id for n in self.scan()} | {n.id for n in self.scan_activities()}
            note_id = note.id
            while not note_id or note_id in taken:
                note_id = fmt.generate_id()
            note = note.copy(id=note_id, path=fmt.file_name_for(note.title, note_id))
        note = note.copy(has_id=True)
        self._write(note.path, fmt.serialize(note))
        mtime = (self.root / note.path).stat().st_mtime_ns
        self._cache[note.path] = (mtime, note)
        return note

    def pdf_path(self, note: Note) -> Path | None:
        """Absolute path of the note's scanned PDF, if it has one."""
        return self.file_path(note.pdf) if note.pdf else None

    def file_path(self, relative: str) -> Path:
        """A file a note lists (attachment, scan, track). The name comes from the note's frontmatter, so it is
        checked: one leading out of the folder gives a path that never exists (see REFUSED)."""
        return self.root / relative if inside(self.root, relative) else self.root / REFUSED

    def add_attachment(self, source: Path) -> str:
        """Copies the file into attachments/ and returns its path relative to the notes folder."""
        size = source.stat().st_size
        if size > MAX_ATTACHMENT:
            raise OSError(f"{source.name} dépasse {MAX_ATTACHMENT // 1_000_000} Mo")
        relative = fmt.attachment_path(source.name)
        while self.file_path(relative).exists():
            relative = fmt.attachment_path(source.name)
        atomic.write_bytes(self.file_path(relative), source.read_bytes())
        return relative

    # ---- activities ----

    def read_track(self, info: ActivityInfo) -> Track:
        """The FIT/GPX file of an activity."""
        if not info.file:
            raise OSError("Pas de tracé")
        data = self.file_path(info.file).read_bytes()
        track = fit.read(data) if fit.looks_like_fit(data) else gpx.read(data.decode("utf-8", "replace"))
        if info.alt and elevation.missing(track):
            try:
                track = elevation.apply(track, elevation.parse(self.file_path(info.alt).read_text(encoding="utf-8")))
            except OSError:
                pass  # not synced yet: the activity opens without altitude
        return track

    def _analysis_key(self, info: ActivityInfo) -> tuple:
        st = self.file_path(info.file).stat()
        return info.file, info.alt, info.sport, st.st_size, st.st_mtime_ns

    def cached_analysis(self, info: ActivityInfo) -> Analysis | None:
        """The analysis when it is in the cache, without computing anything (None also when the file is missing)."""
        try:
            key = self._analysis_key(info)
        except OSError:
            return None
        with self._analyses_lock:
            hit = self._analyses.get(key)
        return hit[1] if hit else None

    def track_and_analysis(self, info: ActivityInfo) -> tuple[Track, Analysis]:
        """The track of an activity and its analysis, from a small cache (thread-safe; the analysis is the slow part)."""
        key = self._analysis_key(info)
        with self._analyses_lock:
            hit = self._analyses.get(key)
            if hit is not None:
                self._analyses.move_to_end(key)
                return hit
        track = self.read_track(info)
        result = track, analyze(track, info.sport)
        self._remember_analysis(key, result)
        return result

    def _remember_analysis(self, key: tuple, result: tuple[Track, Analysis]) -> None:
        with self._analyses_lock:
            self._analyses[key] = result
            self._analyses.move_to_end(key)
            while len(self._analyses) > ANALYSES_KEPT:
                self._analyses.popitem(last=False)

    def find_activity(self, start: int | None) -> Note | None:
        """The activity already imported from the same recording (same start time, to the second)."""
        if start is None:
            return None
        return next((n for n in self.scan_activities() if n.activity and abs(n.activity.start - start) < 2000), None)

    def read_source(self, source: Path) -> tuple[Track, str]:
        if source.stat().st_size > MAX_ATTACHMENT:
            raise OSError(f"{source.name} dépasse {MAX_ATTACHMENT // 1_000_000} Mo")
        data = source.read_bytes()
        if fit.looks_like_fit(data):
            return fit.read(data), "fit"
        return gpx.read(data.decode("utf-8", "replace")), "gpx"

    def import_activity(self, source: Path, title: str | None, sport: Sport | None, replace: Note | None = None) -> Note:
        """Reads a FIT or GPX file, copies it to attachments/ and creates the activity note.
        With [replace], that activity keeps its name, description and photos and gets the new track."""
        data = source.read_bytes()
        track, ext = self.read_source(source)
        if not track.points and track.session_distance is None:
            raise fit.FitError("Aucune position dans ce fichier")
        sport = sport or Sport.guess(track.fit_sport)
        start = track.start_time or now_ms()
        if replace is not None:
            note_id, base_title = replace.id, replace.title
            title = (title or "").strip() or base_title
        else:
            title = (title or "").strip() or default_title(sport, start, track.name)
            taken = {n.id for n in self.scan()} | {n.id for n in self.scan_activities()}
            note_id = fmt.generate_id()
            while note_id in taken:
                note_id = fmt.generate_id()
        relative = f"attachments/{fmt.slug(title)}-{note_id}.{ext}"
        atomic.write_bytes(self.file_path(relative), data)
        a = analyze(track, sport)
        info = ActivityInfo.from_analysis(sport, a, track, relative)
        self._remember_analysis(self._analysis_key(info), (track, a))
        now = now_ms()
        if replace is None:
            return self.save(Note(path=f"{ACTIVITIES_DIR}/{fmt.file_name_for(title, note_id)}", id=note_id, title=title,
                                  created=now, updated=now, activity=info, attachments=[relative]))
        # the old track and its altitude file go; photos and the description stay
        old = [replace.activity.file, replace.activity.alt] if replace.activity else []
        for path in old:
            if path and path != relative:
                try:
                    self.file_path(path).unlink()
                except OSError:
                    pass
        kept = [a for a in replace.attachments if a not in old]
        return self.save(replace.copy(title=title, activity=info, attachments=[relative] + kept, updated=now))

    def fetch_altitude(self, note: Note, progress=lambda n, total: None) -> Note:
        """Looks the altitude up in the terrain model (positions are sent to opentopodata.org)."""
        info = note.activity
        if info is None or not info.file:
            raise OSError("Pas de tracé")
        track = self.read_track(ActivityInfo(**{**info.__dict__, "alt": None}))
        if not elevation.missing(track):
            raise elevation.ElevationError("Ce fichier a déjà une altitude")
        from .analysis import clean
        alts = elevation.query(elevation.pick(clean(track)), progress=progress)
        relative = (info.file.rsplit(".", 1)[0]) + ".alt"
        atomic.write_text(self.file_path(relative), elevation.encode(alts))
        filled = elevation.apply(track, alts)
        new_info = ActivityInfo.from_analysis(info.sport, analyze(filled, info.sport), filled, info.file, relative,
                                              untimed_start=info.start)
        attachments = note.attachments if relative in note.attachments else note.attachments + [relative]
        return self.save(note.copy(activity=new_info, attachments=attachments, updated=now_ms()))

    def fill_altitudes(self, notes: list[Note], progress=lambda text: None) -> tuple[int, int, str | None]:
        """Completes the altitude of several activities, one after the other: (done, skipped, failure).
        Activities that already have an altitude (recorded with the barometer, or already completed) are skipped;
        the first service failure (quota, offline) stops the run."""
        done = skipped = 0
        for i, note in enumerate(notes):
            info = note.activity
            if info is None or not info.file or info.alt or not info.sport.has_gps:
                skipped += 1
                continue
            try:
                progress(f"Activité {i + 1}/{len(notes)}…")
                before = note.activity.alt
                saved = self.fetch_altitude(note, progress=lambda n, total, i=i: progress(f"Activité {i + 1}/{len(notes)} · relief {n + 1}/{total}…"))
                done += 1 if saved.activity.alt != before else 0
            except elevation.ElevationError as error:
                if "déjà une altitude" in str(error):
                    skipped += 1
                    continue
                return done, skipped, str(error)
            except OSError as error:
                return done, skipped, str(error)
        return done, skipped, None

    def update_activity(self, note: Note, title: str, description: str, sport: Sport) -> Note:
        info = note.activity
        if info is not None and info.sport != sport:
            if info.file:
                track = self.read_track(info)
                info = ActivityInfo.from_analysis(sport, analyze(track, sport), track, info.file, info.alt,
                                                  untimed_start=info.start)
            else:
                info = ActivityInfo(**{**info.__dict__, "sport": sport})
        return self.save(note.copy(title=title.strip() or note.title, body=description.strip(), activity=info, updated=now_ms()))

    def referenced_elsewhere(self, note: Note) -> set[str]:
        """Files (scans, attachments) another note still lists: a sync conflict copy shares its original's."""
        others = [n for n in self.scan() + self.scan_activities() if n.path != note.path]
        return {f for n in others for f in ([n.pdf] if n.pdf else []) + n.attachments}

    def delete(self, note: Note) -> None:
        """Deletes the note and the files only it uses."""
        shared = self.referenced_elsewhere(note)
        owned = [f for f in ([note.pdf] if note.pdf else []) + note.attachments if f not in shared]
        for path in (self.root / note.path, *(self.file_path(f) for f in owned)):
            try:
                path.unlink()
            except FileNotFoundError:
                pass
        self._cache.pop(note.path, None)

    def keep_conflict_copy(self, copy: Note) -> Note:
        """This copy replaces the original (same id, no "conflict" in its name). The copy's file goes, never the
        attachments it shares with the note it now is."""
        original = next((n for n in self.scan() if n.id == copy.id and not n.conflict and n.path != copy.path), None)
        target = original.path if original else re.sub(r"(?i)[ ._-]*conflict[^.]*", "", copy.path)
        kept = copy.copy(path=target, conflict=False, updated=now_ms())
        self._write(target, fmt.serialize(kept))
        if target != copy.path:
            self.delete(copy)
        self._cache.pop(target, None)
        return kept
