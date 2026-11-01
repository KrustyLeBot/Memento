"""The notes folder is the source of truth; this is a thin cache over it."""
from __future__ import annotations

import os
import re
import time
from pathlib import Path

from . import atomic
from . import elevation, fit, gpx
from . import note as fmt
from .activity import ActivityInfo
from .analysis import analyze
from .note import Note
from .settings import PATH as SETTINGS_PATH, ActivitySettings
from .track import Sport, Track

ACTIVITIES_DIR = "activities"


# Above this, a file is refused: it is synced to every device (same limit as the Android app).
MAX_ATTACHMENT = 50_000_000


def now_ms() -> int:
    return int(time.time() * 1000)


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
        for stale in set(self._cache) - seen:
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
        return tuple(files), tuple(acts)

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
        return self.root / note.pdf if note.pdf else None

    def file_path(self, relative: str) -> Path:
        return self.root / relative

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
        info = ActivityInfo.from_analysis(sport, analyze(track, sport), track, relative)
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
        new_info = ActivityInfo.from_analysis(info.sport, analyze(filled, info.sport), filled, info.file, relative)
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
                info = ActivityInfo.from_analysis(sport, analyze(track, sport), track, info.file, info.alt)
            else:
                info = ActivityInfo(**{**info.__dict__, "sport": sport})
        return self.save(note.copy(title=title.strip() or note.title, body=description.strip(), activity=info, updated=now_ms()))

    def delete(self, note: Note) -> None:
        for path in (self.root / note.path, self.pdf_path(note), *(self.file_path(a) for a in note.attachments)):
            try:
                if path is not None:
                    path.unlink()
            except FileNotFoundError:
                pass
        self._cache.pop(note.path, None)

    def keep_conflict_copy(self, copy: Note) -> Note:
        """This copy replaces the original (same id, no "conflict" in its name)."""
        original = next((n for n in self.scan() if n.id == copy.id and not n.conflict and n.path != copy.path), None)
        target = original.path if original else re.sub(r"(?i)[ ._-]*conflict[^.]*", "", copy.path)
        kept = copy.copy(path=target, conflict=False, updated=now_ms())
        self._write(target, fmt.serialize(kept))
        if target != copy.path:
            self.delete(copy)
        self._cache.pop(target, None)
        return kept
