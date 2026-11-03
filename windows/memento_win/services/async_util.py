from __future__ import annotations

import gc
import sys
import threading
from contextlib import contextmanager
from typing import Callable

from PySide6.QtCore import QObject, QRunnable, QThreadPool, Qt, Signal, Slot

_responsive_lock = threading.Lock()
_responsive_depth = 0
_responsive_saved: tuple[float, bool] = (0.005, True)  # the switch interval and gc state before the first entry


@contextmanager
def responsive_worker():
    """Around pure-Python work on a worker (reading and analysing a long race): the window's thread keeps getting the
    GIL. A 1 ms switch interval (5 ms by default) hands it back sooner, and no collection runs while hundreds of
    thousands of samples are being built (each collection holds the GIL for tens of ms). Nesting and concurrent workers are
    fine: the last one out restores both."""
    global _responsive_depth, _responsive_saved
    with _responsive_lock:
        _responsive_depth += 1
        if _responsive_depth == 1:
            _responsive_saved = (sys.getswitchinterval(), gc.isenabled())
            sys.setswitchinterval(0.001)
            gc.disable()
    try:
        yield
    finally:
        with _responsive_lock:
            _responsive_depth -= 1
            if _responsive_depth == 0:
                interval, enabled = _responsive_saved
                if enabled:
                    gc.enable()
                sys.setswitchinterval(interval)


class _Dispatcher(QObject):
    _done = Signal(object, object, object)

    def __init__(self):
        super().__init__()
        self._done.connect(self._deliver, Qt.ConnectionType.QueuedConnection)

    @Slot(object, object, object)
    def _deliver(self, callback, value, error):
        if callback:
            callback(error if error is not None else value)

    def emit(self, callback, value, error):
        self._done.emit(callback, value, error)


_dispatcher: _Dispatcher | None = None


class _Task(QRunnable):
    def __init__(self, fn, on_done, on_error):
        super().__init__()
        self.fn, self.on_done, self.on_error = fn, on_done, on_error

    def run(self):
        try:
            result = self.fn()
            outcome = (self.on_done, result, None)
        except Exception as exc:  # reported to the UI thread
            outcome = (self.on_error, None, exc)
        try:
            _dispatcher.emit(*outcome)
        except RuntimeError:
            pass  # the application is already shutting down


def run_async(fn: Callable, on_done: Callable | None = None, on_error: Callable | None = None) -> None:
    """Call from the UI thread. on_done(result) / on_error(exception) run on the UI thread."""
    global _dispatcher
    if _dispatcher is None:
        _dispatcher = _Dispatcher()
    QThreadPool.globalInstance().start(_Task(fn, on_done, on_error))
