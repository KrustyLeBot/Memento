"""Keeps the indexes (parsed notes, activities, contacts, agenda events) ready, so that even the first visit after a
restart or a long idle period answers at once.

- At startup, in a low-priority background thread: the saved indexes (snapshot.py) are loaded and checked against the
  files, the agenda core is imported, every file not covered is read and parsed (outside the data lock, in a private
  Store, then handed to the shared one), the agenda's wide window is built, the recent activities' analyses are
  prepared, and the indexes are saved for the next start.
- On a change seen by the watcher (events.py): `refresh(areas)` re-reads the changed files of those areas (the core's
  scans read only files whose time changed) before the change is pushed to the pages; then, in the background, the
  agenda's wide window is rebuilt and the new state saved once the folder has been quiet for SAVE_AFTER seconds.

A request never waits for the warm-up as a whole: it takes the data lock and scans as it always did, finding in the
caches whatever the warm-up has already handed over and reading the rest itself.
"""
from __future__ import annotations

import gc
import os
import threading
import time
from concurrent.futures import ThreadPoolExecutor

from .core import Store
from .data import _malloc_trim
from .modules.agenda_index import core as agenda_core, scan_files
from .snapshot import Snapshots, stamp_of

SAVE_AFTER = 10.0  # s of quiet after a change before the indexes are saved again


def _log(text: str) -> None:
    print(f"memento-web warm-up: {text}", flush=True)


def _low_priority() -> None:
    """The worker thread runs niced (Linux: a thread is a schedulable task of its own), so requests come first."""
    try:
        os.setpriority(os.PRIO_PROCESS, threading.get_native_id(), 10)
    except (AttributeError, OSError):
        pass


class Warmer:
    def __init__(self, data, index, activities, enabled: bool = True):
        self.data = data
        self.index = index  # the agenda's AgendaIndex
        self.activities = activities
        self.enabled = enabled
        self.snapshots = Snapshots(data.config.config_dir / "cache", data.config.session_secret)
        self.ready = threading.Event()
        self.timings: dict[str, float] = {}
        self._executor: ThreadPoolExecutor | None = None
        self._stopping = False
        self._dirty: set[str] = set()
        self._changed_at = 0.0
        self._save_pending = False
        self._wide_pending = False
        self._guard = threading.Lock()
        self._restored: dict[str, int] = {}  # areas whose saved index was used whole

    # ---- lifecycle ----

    def start(self) -> None:
        self._executor = ThreadPoolExecutor(1, thread_name_prefix="memento-warm", initializer=_low_priority)
        if self.enabled:
            self._submit(self.warm_up)
        else:
            self.ready.set()

    def stop(self) -> None:
        self._stopping = True
        if self._executor is not None:
            self._executor.shutdown(wait=False, cancel_futures=True)

    def _submit(self, fn, *args) -> None:
        def run():
            try:
                fn(*args)
            except Exception as error:  # noqa: BLE001 — an optimisation failing must not stop the server
                _log(f"{fn.__name__} failed: {type(error).__name__}: {error}")
        if self._executor is not None and not self._stopping:
            try:
                self._executor.submit(run)
            except RuntimeError:  # shutting down
                pass

    def _step(self, name: str, fn, *args):
        t = time.perf_counter()
        out = fn(*args)
        self.timings[name] = round((time.perf_counter() - t) * 1000)
        return out

    # ---- the warm-up ----

    def warm_up(self) -> None:
        t = time.perf_counter()
        try:
            restored = self._step("snapshot", self.load_snapshots)
            self._step("agenda core", agenda_core)
            self._step("read files", self.read_files)
            self._step("indexes", self._locked, self._finish)
            self._step("agenda window", self.build_wide)
            self.save([a for a, n in self._counts().items() if n != self._restored.get(a)])
            if self.activities is not None:
                self._step("analyses", self.activities.prepare_recent, lambda: self._stopping)
        finally:
            gc.collect()
            _malloc_trim()
            self.ready.set()
        _log(f"ready in {(time.perf_counter() - t) * 1000:.0f} ms ({restored} entries from the saved index; "
             f"{', '.join(f'{k} {v} ms' for k, v in self.timings.items())})")

    def _locked(self, fn, *args):
        with self.data.lock:
            return fn(*args)

    def _finish(self) -> None:
        store = self.data.store
        store.scan()
        store.scan_activities()
        store.scan_contacts()
        self.index.load()

    def build_wide(self) -> None:
        """The agenda's wide window, built without holding the lock (a request meanwhile builds its own window)."""
        with self.data.lock:
            if self.index.sources is None:
                return
            self.index.load()
            job = self.index.wide_job()
        if job is not None:
            wide = job()
            with self.data.lock:
                self.index.install_wide(wide)

    def _counts(self) -> dict[str, int]:
        with self.data.lock:
            cached = self.data.store.cached()
            return {"notes": sum("/" not in p for p in cached), "activities": sum("/" in p for p in cached),
                    "contacts": len(self.data.store.contacts.cached()), "agenda": len(self.index.files())}

    def load_snapshots(self) -> int:
        """Hands the saved indexes' still valid entries to the caches; the number of entries used. An area whose
        saved index was entirely valid and complete is not saved again after the warm-up."""
        root = self.data.root
        count = 0
        self._restored = {}
        for area in ("notes", "activities", "contacts", "agenda"):
            found = self.snapshots.load(area)
            if found is None:
                continue
            entries, meta = found
            valid = self.snapshots.valid(root, entries)
            count += len(valid)
            if len(valid) == len(entries):
                self._restored[area] = len(valid)
            with self.data.lock:
                store = self.data.store
                if area == "agenda":
                    self.index.seed({p: (stamp, *value) for p, (stamp, value) in valid.items()})
                    if meta.get("zone"):
                        self.index.zone = meta["zone"]
                elif area == "contacts":
                    for path, (stamp, contact) in valid.items():
                        store.contacts.seed(path, stamp[0], contact)
                else:
                    for path, (stamp, note) in valid.items():
                        store.seed(path, stamp[0], note)
        return count

    def read_files(self) -> None:
        """Reads every file the caches lack, area by area (notes first: the landing page), in a private Store with no
        lock held while reading, handing each area over when it is read. A request meanwhile reads what it needs."""
        with self.data.lock:
            notes = self.data.store.cached()
            people = self.data.store.contacts.cached()
            files = self.index.files()
        private = Store(self.data.root)
        for path, (mtime, note) in notes.items():
            private.seed(path, mtime, note)
        for path, (mtime, contact) in people.items():
            private.contacts.seed(path, mtime, contact)
        for scan in (private.scan, private.scan_activities):
            if self._stopping:
                return
            scan()
            with self.data.lock:
                for path, (mtime, note) in private.cached().items():
                    self.data.store.seed(path, mtime, note)
        private.contacts.scan()
        with self.data.lock:
            for path, (mtime, contact) in private.contacts.cached().items():
                self.data.store.contacts.seed(path, mtime, contact)
        if self._stopping:
            return
        scan_files(private.agenda, self.data.root, files)
        with self.data.lock:
            self.index.seed(files)

    # ---- changes ----

    def refresh(self, areas: list[str]) -> None:
        """Called by the watcher (in a worker thread) before it pushes `areas` to the pages: the changed files are
        read now, so the pages' reloads find them parsed."""
        with self.data.lock:
            store = self.data.store
            if "notes" in areas:
                store.scan()
            if "activities" in areas:
                store.scan_activities()
            if "contacts" in areas:
                store.scan_contacts()
            if self.index.sources is not None:
                self.index.load()
        with self._guard:
            self._dirty.update(areas)
            self._changed_at = time.monotonic()
        self.want_wide()
        if "activities" in areas and self.enabled and self.activities is not None:
            self._submit(self.activities.prepare_recent, lambda: self._stopping)

    def want_wide(self) -> None:
        """Rebuilds the agenda's wide window in the background (after a change, or a request in another zone)."""
        with self._guard:
            if self._wide_pending:
                return
            self._wide_pending = True

        def build_wide():
            with self._guard:
                self._wide_pending = False
            self.build_wide()
        self._submit(build_wide)

    def tick(self) -> None:
        """Called by the watcher's loop: saves the indexes once the folder has been quiet for SAVE_AFTER seconds."""
        with self._guard:
            if not self._dirty or self._save_pending or time.monotonic() - self._changed_at < SAVE_AFTER:
                return
            self._save_pending = True
        self._submit(self._save_dirty)

    def _save_dirty(self) -> None:
        with self._guard:
            areas, self._dirty = self._dirty, set()
            self._save_pending = False
        self.save(areas)

    # ---- saving ----

    def save(self, areas=("notes", "activities", "contacts", "agenda")) -> None:
        """Writes the indexes of `areas` (copied under the lock, checked and pickled outside it)."""
        with self.data.lock:
            notes = self.data.store.cached()
            people = self.data.store.contacts.cached() if "contacts" in areas else {}
            files = self.index.files() if "agenda" in areas else {}
            zone = self.index.zone
        base = str(self.data.root)

        def stamped(items):
            out = {}
            for path, (mtime, value) in items:
                stamp = stamp_of(f"{base}/{path}")
                if stamp is not None and stamp[0] == mtime:
                    out[path] = (stamp, value)
            return out
        if "notes" in areas:
            self.snapshots.save("notes", stamped((p, v) for p, v in notes.items() if "/" not in p))
        if "activities" in areas:
            self.snapshots.save("activities", stamped((p, v) for p, v in notes.items() if "/" in p))
        if "contacts" in areas:
            self.snapshots.save("contacts", stamped(people.items()))
        if "agenda" in areas:
            self.snapshots.save("agenda", {p: (stamp, (event, problem)) for p, (stamp, event, problem) in files.items()},
                                {"zone": zone})
