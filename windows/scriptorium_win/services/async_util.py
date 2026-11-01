from __future__ import annotations

from typing import Callable

from PySide6.QtCore import QObject, QRunnable, QThreadPool, Qt, Signal, Slot


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
