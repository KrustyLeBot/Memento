"""What every module works with: the data folder (through the Windows core's Store), one lock serialising the
writes, the undo stash and the change watcher."""
from __future__ import annotations

import threading
from pathlib import Path

from starlette.concurrency import run_in_threadpool

from .config import Config
from .core import Store
from .events import Watcher
from .files import resolve
from .undo import Undo


class Data:
    def __init__(self, config: Config):
        self.config = config
        self.root: Path = config.data
        self.store = Store(self.root)
        # One user, a few requests at a time: a single lock keeps every read-check-write sequence (the 409 checks)
        # free of races, which matters more than parallelism here.
        self.lock = threading.RLock()
        self.undo = Undo(config.config_dir / "undo")
        self.watcher = Watcher(self, config.poll_seconds)

    def path(self, relative: str) -> Path:
        return resolve(self.root, relative)

    async def run(self, fn, *args, **kwargs):
        """Runs blocking file work in the thread pool, under the lock; the watcher is told when it is done."""
        def locked():
            with self.lock:
                return fn(*args, **kwargs)
        try:
            return await run_in_threadpool(locked)
        finally:
            self.watcher.poke()

    async def read(self, fn, *args, **kwargs):
        """Same, for reads: nothing to tell the watcher."""
        def locked():
            with self.lock:
                return fn(*args, **kwargs)
        return await run_in_threadpool(locked)
