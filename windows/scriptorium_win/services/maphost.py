"""Host side of the map page (web/map) on Windows: the `scr://app/` scheme serving the page, the tiles (fetched
once, then from a 300 MB disk cache) and the peaks. Everything runs on the GUI thread with asynchronous network
calls; only the cache purge runs in a worker thread. The rules themselves live in core/mapcache.py and core/peaks.py."""
from __future__ import annotations

import json
import os
import shutil
import sys
import threading
import time
import uuid
from functools import partial
from pathlib import Path
from typing import Callable

import shiboken6
from PySide6.QtCore import QBuffer, QByteArray, QCoreApplication, QIODevice, QObject, QStandardPaths, Qt, QUrl
from PySide6.QtNetwork import QNetworkAccessManager, QNetworkReply, QNetworkRequest
from PySide6.QtWebEngineCore import (QWebEngineProfile, QWebEngineUrlRequestJob, QWebEngineUrlScheme,
                                     QWebEngineUrlSchemeHandler)

from ..core import mapcache, peaks

SCHEME = b"scr"
BASE_URL = "scr://app/"
PURGE_EVERY = 20 * 1024 * 1024  # bytes written between two purges
MIME = {"html": b"text/html", "js": b"text/javascript", "css": b"text/css", "png": b"image/png", "json": b"application/json",
        "svg": b"image/svg+xml", "txt": b"text/plain"}
# Errors that mean "no network", as opposed to a server answering (404 and the like).
OFFLINE_ERRORS = {QNetworkReply.NetworkError.ConnectionRefusedError, QNetworkReply.NetworkError.HostNotFoundError,
                  QNetworkReply.NetworkError.TimeoutError, QNetworkReply.NetworkError.TemporaryNetworkFailureError,
                  QNetworkReply.NetworkError.NetworkSessionFailedError, QNetworkReply.NetworkError.UnknownNetworkError,
                  QNetworkReply.NetworkError.ProxyConnectionRefusedError, QNetworkReply.NetworkError.ProxyNotFoundError,
                  QNetworkReply.NetworkError.RemoteHostClosedError}


def register_scheme() -> None:
    """Must run before the QApplication is created (Qt's rule for custom schemes and for WebEngine)."""
    s = QWebEngineUrlScheme(SCHEME)
    s.setSyntax(QWebEngineUrlScheme.Syntax.Host)
    s.setDefaultPort(QWebEngineUrlScheme.SpecialPort.PortUnspecified.value)
    # secure + CORS-enabled + fetch allowed: fetch() and canvas reads of the tiles work like same-origin https
    # (without FetchApiAllowed, Chromium refuses fetch() on a custom scheme: "URL scheme is not supported")
    s.setFlags(QWebEngineUrlScheme.Flag.SecureScheme | QWebEngineUrlScheme.Flag.CorsEnabled
               | QWebEngineUrlScheme.Flag.LocalAccessAllowed | QWebEngineUrlScheme.Flag.FetchApiAllowed)
    QWebEngineUrlScheme.registerScheme(s)
    QCoreApplication.setAttribute(Qt.ApplicationAttribute.AA_ShareOpenGLContexts)


def web_root() -> Path:
    """web/ of the repository, or its copy inside the one-file exe."""
    if getattr(sys, "frozen", False):
        return Path(sys._MEIPASS) / "web"  # noqa: SLF001 — PyInstaller's extraction folder
    return Path(__file__).resolve().parents[3] / "web"


def cache_root() -> Path:
    return Path(QStandardPaths.writableLocation(QStandardPaths.StandardLocation.CacheLocation))


def _request(url: str) -> QNetworkRequest:
    request = QNetworkRequest(QUrl(url))
    request.setRawHeader(QByteArray(b"User-Agent"), QByteArray(mapcache.USER_AGENT.encode()))
    request.setTransferTimeout(15000)
    return request


def _log(text: str) -> None:
    print(f"maphost: {text}", file=sys.stderr)


class TileStore(QObject):
    """Tiles on disk under `root` (tiles2/), fetched on a miss. Concurrent requests for one tile share a fetch."""

    def __init__(self, root: Path, parent: QObject | None = None):
        super().__init__(parent)
        self.root = root
        self.backoff = mapcache.Backoff()
        self._nam = QNetworkAccessManager(self)
        self._waiting: dict[str, list[Callable[[bytes | None], None]]] = {}
        self._peaks_waiting: dict[tuple, list[Callable[[list | None], None]]] = {}
        self._written = 0
        self._purge_lock = threading.Lock()
        self.purge_async()

    # ---- tiles ----

    def get(self, tile: mapcache.TileRequest, done: Callable[[bytes | None], None]) -> None:
        path = mapcache.tile_path(self.root, tile)
        try:
            data = path.read_bytes()
            os.utime(path)  # LRU: the purge removes the least recently read
            done(data)
            return
        except FileNotFoundError:
            pass
        except OSError as error:  # a file the purge is deleting, a locked file: fetch it again
            _log(f"{path}: {error}")
        if self.backoff.blocked(time.monotonic()):
            done(None)
            return
        if tile.key in self._waiting:
            self._waiting[tile.key].append(done)
            return
        self._waiting[tile.key] = [done]
        self._fetch(tile, mapcache.upstream_url(tile), primary=True)

    def _fetch(self, tile: mapcache.TileRequest, url: str, primary: bool) -> None:
        reply = self._nam.get(_request(url))
        reply.finished.connect(partial(self._fetched, reply, tile, primary))

    def _fetched(self, reply: QNetworkReply, tile: mapcache.TileRequest, primary: bool) -> None:
        try:
            error = reply.error()
            status = int(reply.attribute(QNetworkRequest.Attribute.HttpStatusCodeAttribute) or 0)
            kind = str(reply.header(QNetworkRequest.KnownHeaders.ContentTypeHeader) or "")
            data = bytes(reply.readAll())
        finally:
            reply.deleteLater()
        if error in OFFLINE_ERRORS:
            self.backoff.failed(time.monotonic())
            self._finish(tile.key, None)
            return
        self.backoff.succeeded()
        if tile.kind == "plan" and primary and mapcache.plan_needs_fallback(status, kind, data):
            self._fetch(tile, mapcache.fallback_url(tile), primary=False)  # outside IGN's coverage: OpenStreetMap
            return
        if status != 200 or not kind.startswith("image/") or not data:
            _log(f"{tile.key}: HTTP {status} {kind}")
            self._finish(tile.key, None)
            return
        self._store(mapcache.tile_path(self.root, tile), data)
        self._finish(tile.key, data)

    def _finish(self, key: str, data: bytes | None) -> None:
        for done in self._waiting.pop(key, []):
            done(data)

    def _store(self, path: Path, data: bytes) -> None:
        # atomic, so a reader (or a crash) never sees half a tile
        tmp = path.with_name(f".{path.name}.{uuid.uuid4().hex}.tmp")
        try:
            path.parent.mkdir(parents=True, exist_ok=True)
            tmp.write_bytes(data)
            os.replace(tmp, path)
        except OSError as error:
            _log(f"cannot cache {path}: {error}")
            tmp.unlink(missing_ok=True)
            return
        self._written += len(data)
        if self._written >= PURGE_EVERY:
            self._written = 0
            self.purge_async()

    def purge_async(self) -> None:
        if not self._purge_lock.acquire(blocking=False):
            return  # one purge at a time; the next write past the threshold starts another

        def run() -> None:
            try:
                left, deleted = mapcache.purge(self.root)
                if deleted:
                    _log(f"cache purged: {deleted} tiles deleted, {left // (1024 * 1024)} MB left")
            except OSError as error:
                _log(f"cache purge failed: {error}")
            finally:
                self._purge_lock.release()
        threading.Thread(target=run, name="tile-purge", daemon=True).start()

    # ---- peaks ----

    def peaks(self, req: mapcache.PeaksRequest, done: Callable[[list], None]) -> None:
        box = peaks.cache_box(req.w, req.s, req.e, req.n)
        wanted = (req.w, req.s, req.e, req.n)
        if peaks.too_large(box):
            done([])
            return
        directory = self.root / "peaks"
        cached = peaks.covering(directory, box)
        if cached is not None:
            done(peaks.select(cached, wanted))
            return
        if self.backoff.blocked(time.monotonic()):
            done([])
            return
        finish = lambda candidates: done(peaks.select(candidates, wanted) if candidates else [])  # noqa: E731
        if box in self._peaks_waiting:
            self._peaks_waiting[box].append(finish)
            return
        self._peaks_waiting[box] = [finish]
        reply = self._nam.get(_request(peaks.wfs_url(box)))
        reply.finished.connect(partial(self._wfs_done, reply, box, directory))

    def _text(self, reply: QNetworkReply) -> str | None:
        try:
            error = reply.error()
            text = bytes(reply.readAll()).decode("utf-8", "replace")
        finally:
            reply.deleteLater()
        if error == QNetworkReply.NetworkError.NoError:
            self.backoff.succeeded()
            return text
        if error in OFFLINE_ERRORS:
            self.backoff.failed(time.monotonic())
        _log(f"{reply.url().host()}: {reply.errorString()}")
        return None

    def _wfs_done(self, reply: QNetworkReply, box: tuple, directory: Path) -> None:
        text = self._text(reply)
        try:
            candidates = peaks.parse_wfs(text, box) if text is not None else None
        except (ValueError, TypeError) as error:
            _log(f"summits: unreadable answer: {error}")
            candidates = None
        if not candidates:
            self._peaks_finish(box, directory, candidates)
            return
        alti = self._nam.get(_request(peaks.alti_url(candidates)))
        alti.finished.connect(partial(self._alti_done, alti, box, directory, candidates))

    def _alti_done(self, reply: QNetworkReply, box: tuple, directory: Path, candidates: list) -> None:
        text = self._text(reply)
        if text is not None:
            try:
                candidates = peaks.with_elevations(candidates, text)
            except (ValueError, TypeError) as error:
                _log(f"summit heights: unreadable answer: {error}")
        self._peaks_finish(box, directory, candidates)

    def _peaks_finish(self, box: tuple, directory: Path, candidates: list | None) -> None:
        if candidates is not None:  # an empty list is an answer too: a box with no summit is cached as such
            try:
                directory.mkdir(parents=True, exist_ok=True)
                file = peaks.cache_file(directory, box)
                tmp = file.with_name(f".{file.name}.{uuid.uuid4().hex}.tmp")
                tmp.write_text(json.dumps({"box": box, "candidates": candidates}), encoding="utf-8")
                os.replace(tmp, file)
            except OSError as error:
                _log(f"cannot cache the summits: {error}")
        for done in self._peaks_waiting.pop(box, []):
            done(candidates or [])


class SchemeHandler(QWebEngineUrlSchemeHandler):
    """Answers every `scr://app/...` request: each job is replied to or failed, never left hanging."""

    def __init__(self, store: TileStore, parent: QObject | None = None):
        super().__init__(parent)
        self.store = store
        self.web = web_root()

    def requestStarted(self, job: QWebEngineUrlRequestJob) -> None:
        url = job.requestUrl()
        req = mapcache.parse_request(url.path(), url.query()) if url.host() == "app" else None
        if isinstance(req, mapcache.FileRequest):
            path = self.web / req.path
            try:
                data = path.read_bytes()
            except OSError:
                job.fail(QWebEngineUrlRequestJob.Error.UrlNotFound)
                return
            self._reply(job, MIME.get(path.suffix.lstrip(".").lower(), b"application/octet-stream"), data)
        elif isinstance(req, mapcache.TileRequest):
            self.store.get(req, partial(self._answer, job, b"image/png"))
        elif isinstance(req, mapcache.PeaksRequest):
            self.store.peaks(req, lambda found, j=job: self._answer(j, b"application/json", json.dumps(found).encode()))
        else:
            job.fail(QWebEngineUrlRequestJob.Error.UrlNotFound)

    def _answer(self, job: QWebEngineUrlRequestJob, mime: bytes, data: bytes | None) -> None:
        if not shiboken6.isValid(job):
            return  # the page gave up on it (a tile scrolled away): WebEngine already deleted the job
        if data is None:
            job.fail(QWebEngineUrlRequestJob.Error.UrlNotFound)
        else:
            self._reply(job, mime, data)

    @staticmethod
    def _reply(job: QWebEngineUrlRequestJob, mime: bytes, data: bytes) -> None:
        buffer = QBuffer(job)  # owned by the job: freed with it
        buffer.setData(QByteArray(data))
        buffer.open(QIODevice.OpenModeFlag.ReadOnly)
        job.reply(mime, buffer)


_profile: QWebEngineProfile | None = None


def profile() -> QWebEngineProfile:
    """The map's own persistent profile, with the scheme handler installed (created once, on first use)."""
    global _profile
    if _profile is None:
        root = cache_root()
        _remove_old_cache(root / "tiles")
        prof = QWebEngineProfile("scriptorium-map", QCoreApplication.instance())
        prof.setCachePath(str(root / "webengine"))
        prof.setPersistentStoragePath(str(root / "webengine-data"))
        prof.setHttpCacheType(QWebEngineProfile.HttpCacheType.DiskHttpCache)
        prof.setHttpCacheMaximumSize(64 * 1024 * 1024)
        store = TileStore(root / "tiles2", prof)
        prof.installUrlSchemeHandler(SCHEME, SchemeHandler(store, prof))
        _profile = prof
    return _profile


def release_profile() -> None:
    """Deletes the profile once every page using it is gone (Qt warns, and may crash, the other way round)."""
    global _profile
    if _profile is not None and shiboken6.isValid(_profile):
        shiboken6.delete(_profile)
    _profile = None


def tile_store() -> TileStore:
    return profile().findChild(TileStore)


def _remove_old_cache(old: Path) -> None:
    """The hand-drawn map's OpenStreetMap cache (up to 100 MB) is no longer read: deleted once, in the background."""
    if not old.is_dir():
        return

    def run() -> None:
        try:
            shutil.rmtree(old)
        except OSError as error:
            _log(f"cannot delete the old tile cache {old}: {error}")
    threading.Thread(target=run, name="old-tiles", daemon=True).start()
