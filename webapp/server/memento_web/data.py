"""What every module works with: the data folder (through the Windows core's Store), one lock serialising the
writes, the undo stash and the change watcher."""
from __future__ import annotations

import ctypes
import gc
import threading
import time
from pathlib import Path

from starlette.concurrency import run_in_threadpool

from .config import Config
from .core import Store
from .events import Watcher
from .files import resolve
from .undo import Undo

# After this long without a request the caches go and the freed memory is handed back to the system: the NAS
# running this has little RAM, and an open tab only holds the event stream.
IDLE_TRIM_SECONDS = 120


def _malloc_trim() -> None:
    """glibc keeps freed memory in its arenas; malloc_trim returns it (no-op elsewhere, e.g. musl)."""
    try:
        ctypes.CDLL("libc.so.6").malloc_trim(0)
    except (OSError, AttributeError):
        pass


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
        self.last_request = time.monotonic()
        self.trimmed = False
        self.idle_hooks: list = []  # callables run on an idle trim, under the lock

    def path(self, relative: str) -> Path:
        return resolve(self.root, relative)

    def touch(self) -> None:
        self.last_request = time.monotonic()
        self.trimmed = False

    def trim_if_idle(self, now: float | None = None) -> bool:
        """Drops the parsed-notes cache (rebuilt on the next request) and trims the heap once per idle period.
        Modules keeping caches of their own register them in `idle_hooks`."""
        now = time.monotonic() if now is None else now
        if self.trimmed or now - self.last_request < IDLE_TRIM_SECONDS:
            return False
        with self.lock:
            self.store = Store(self.root)
            for hook in self.idle_hooks:
                hook()
        gc.collect()
        _malloc_trim()
        self.trimmed = True
        return True

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
