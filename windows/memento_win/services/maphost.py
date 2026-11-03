"""Host side of the map page (web/map) on Windows: the `scr://app/` scheme serving the page, the tiles (fetched
once, then from a 300 MB disk cache), the peaks and the buildings. The requests are answered on the GUI thread with
asynchronous network calls; the cache writes, the buildings' parsing and the cache purge run in worker threads. The
rules themselves live in core/mapcache.py, core/peaks.py and core/buildings.py."""
from __future__ import annotations

import json
import os
import shutil
import sys
import threading
import time
import traceback
import uuid
from collections import deque
from functools import partial
from pathlib import Path
from typing import Callable

import shiboken6
from PySide6.QtCore import QBuffer, QByteArray, QCoreApplication, QIODevice, QObject, QStandardPaths, Qt, QTimer, QUrl
from PySide6.QtNetwork import QNetworkAccessManager, QNetworkReply, QNetworkRequest
from PySide6.QtWebEngineCore import (QWebEngineProfile, QWebEngineUrlRequestJob, QWebEngineUrlScheme,
                                     QWebEngineUrlSchemeHandler)

from ..core import buildings, mapcache, peaks
from .async_util import run_async

SCHEME = b"scr"
BASE_URL = "scr://app/"
PURGE_EVERY = 20 * 1024 * 1024  # bytes written between two purges
TOUCH_AFTER = 3600  # seconds: a read refreshes the file's date (LRU) only past this age, the purge needs no finer
WFS_IN_FLIGHT = 2  # IGN's WFS pages asked at a time (the buildings)
WFS_RETRY_MS = (1000, 2000, 4000)
MIME = {"html": b"text/html", "js": b"text/javascript", "css": b"text/css", "png": b"image/png", "jpg": b"image/jpeg",
        "json": b"application/json",
        "svg": b"image/svg+xml", "txt": b"text/plain"}
# Errors that mean "no network", as opposed to a server answering (404 and the like).
OFFLINE_ERRORS = {QNetworkReply.NetworkError.ConnectionRefusedError, QNetworkReply.NetworkError.HostNotFoundError,
                  QNetworkReply.NetworkError.TimeoutError, QNetworkReply.NetworkError.TemporaryNetworkFailureError,
                  QNetworkReply.NetworkError.NetworkSessionFailedError, QNetworkReply.NetworkError.UnknownNetworkError,
                  QNetworkReply.NetworkError.ProxyConnectionRefusedError, QNetworkReply.NetworkError.ProxyNotFoundError,
                  QNetworkReply.NetworkError.RemoteHostClosedError,
                  QNetworkReply.NetworkError.OperationCanceledError}  # what setTransferTimeout's expiry reports


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


def _read_cached(path: Path) -> bytes:
    """A cached file's bytes; OSError as open() raises it (FileNotFoundError on a miss). Its date, which the purge
    sorts on (least recently read first), is refreshed only once it is older than TOUCH_AFTER."""
    with open(path, "rb") as file:
        mtime = os.fstat(file.fileno()).st_mtime  # from the open handle: no second lookup of the path
        data = file.read()
    if time.time() - mtime > TOUCH_AFTER:
        os.utime(path)
    return data


def _write(path: Path, data: bytes) -> bool:
    """Worker thread: atomic, so a reader (or a crash) never sees half a file. False when it could not be written."""
    tmp = path.with_name(f".{path.name}.{uuid.uuid4().hex}.tmp")
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        tmp.write_bytes(data)
        os.replace(tmp, path)
    except OSError as error:
        _log(f"cannot cache {path}: {error}")
        tmp.unlink(missing_ok=True)
        return False
    return True


def _parse_cell(key: tuple[int, int], features: list, text: str) -> bytes | None:
    """Worker thread: adds one WFS page to the cell's features; the cell's slim JSON once it was the last page, None
    when another page is needed. Only this cell's fetch chain touches `features`, one step at a time."""
    page = buildings.parse_page(text)
    features += page
    if not buildings.is_last_page(page) and len(features) < buildings.PAGE * buildings.MAX_PAGES:
        return None
    return buildings.encode(*key, buildings.slim(features, *key))


class TileStore(QObject):
    """Tiles on disk under `root` (tiles2/), fetched on a miss. Concurrent requests for one tile share a fetch. Each
    answer is (MIME type, bytes): an aerial-photo request answered by its OpenStreetMap stand-in is a PNG."""

    def __init__(self, root: Path, parent: QObject | None = None):
        super().__init__(parent)
        self.root = root
        self.backoff = mapcache.Backoff()
        self._nam = QNetworkAccessManager(self)
        self._waiting: dict[str, list[Callable[[bytes | None], None]]] = {}
        self._peaks_waiting: dict[tuple, list[Callable[[list | None], None]]] = {}
        self._cells_waiting: dict[tuple[int, int], list[Callable[[bytes | None], None]]] = {}
        # IGN's WFS answers 429 to bursts (the page asks for up to 16 cells at once): WFS_IN_FLIGHT pages at a time
        self._wfs_queue: deque[tuple[tuple[int, int], list, int]] = deque()
        self._wfs_in_flight = 0
        self._pending: dict[Path, bytes] = {}  # handed to a worker, not on disk yet: served from here meanwhile
        self._written = 0
        self._purge_lock = threading.Lock()
        self.purge_async()

    # ---- tiles ----

    def get(self, tile: mapcache.TileRequest, done: Callable[[tuple[str, bytes] | None], None]) -> None:
        for cached in (tile, tile.fallback()) if tile.kind == "ortho" else (tile,):
            path = mapcache.tile_path(self.root, cached)
            if path in self._pending:
                done((cached.mime, self._pending[path]))
                return
            try:
                data = _read_cached(path)
                done((cached.mime, data))
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
        if tile.kind == "ortho" and primary and mapcache.ortho_needs_fallback(status, kind, data):
            self._fetch(tile, mapcache.fallback_url(tile), primary=False)  # outside IGN's coverage: OpenStreetMap
            return
        if status != 200 or not kind.startswith("image/") or not data:
            _log(f"{tile.key}: HTTP {status} {kind}")
            self._finish(tile.key, None)
            return
        stored = tile if primary else tile.fallback()
        self._finish(tile.key, (stored.mime, data))  # the page first: the disk write follows in a worker
        self._store(mapcache.tile_path(self.root, stored), data)

    def _finish(self, key: str, data: tuple[str, bytes] | None) -> None:
        for done in self._waiting.pop(key, []):
            done(data)

    def _store(self, path: Path, data: bytes) -> None:
        if path in self._pending:  # an ortho fallback and a plain OSM tile share a file: one write, no os.replace race
            return
        self._pending[path] = data
        run_async(partial(_write, path, data), partial(self._stored, path, data),
                  lambda error: self._stored(path, data, False))

    def _stored(self, path: Path, data: bytes, written: bool) -> None:
        if self._pending.get(path) is data:  # a later write of the same file keeps its own entry
            del self._pending[path]
        if not written:
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
            try:
                done(peaks.select(cached, wanted))
                return
            except (KeyError, TypeError, ValueError) as error:  # a damaged cache entry: fetched again
                _log(f"summits: damaged cache for {box}: {error!r}")
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
        except Exception:  # any malformed answer: logged, and the waiting pages still get their (empty) answer
            _log(f"summits: unreadable answer: {traceback.format_exc()}")
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
            except Exception:  # as above: the summits are kept without heights
                _log(f"summit heights: unreadable answer: {traceback.format_exc()}")
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


    # ---- buildings ----

    def buildings(self, req: mapcache.BuildingsRequest, done: Callable[[bytes | None], None]) -> None:
        """The cell's slim JSON: from the cache, else from the WFS page by page; None offline or on a WFS error."""
        path = buildings.cache_path(self.root, req.x, req.y)
        if path in self._pending:
            done(self._pending[path])
            return
        try:
            data = _read_cached(path)
            done(data)
            return
        except FileNotFoundError:
            pass
        except OSError as error:
            _log(f"{path}: {error}")
        if self.backoff.blocked(time.monotonic()):
            done(None)
            return
        key = (req.x, req.y)
        if key in self._cells_waiting:
            self._cells_waiting[key].append(done)
            return
        self._cells_waiting[key] = [done]
        self._cell_page(key, [])

    def _cell_page(self, key: tuple[int, int], features: list, attempt: int = 0) -> None:
        self._wfs_queue.append((key, features, attempt))
        self._wfs_next()

    def _wfs_next(self) -> None:
        while self._wfs_in_flight < WFS_IN_FLIGHT and self._wfs_queue and shiboken6.isValid(self._nam):
            key, features, attempt = self._wfs_queue.popleft()
            self._wfs_in_flight += 1
            reply = self._nam.get(_request(buildings.wfs_url(*key, len(features))))
            reply.finished.connect(partial(self._cell_page_done, reply, key, features, attempt))

    def _cell_page_done(self, reply: QNetworkReply, key: tuple[int, int], features: list, attempt: int = 0) -> None:
        self._wfs_in_flight = max(0, self._wfs_in_flight - 1)
        status = reply.attribute(QNetworkRequest.Attribute.HttpStatusCodeAttribute) if reply is not None else None
        if status in (429, 503) and attempt < len(WFS_RETRY_MS):
            # a refusal is waited out (1, 2, 4 s) before it fails the cell
            reply.deleteLater()
            QTimer.singleShot(WFS_RETRY_MS[attempt], partial(self._cell_page, key, features, attempt + 1))
            self._wfs_next()
            return
        text = self._text(reply)
        self._wfs_next()
        if text is None:
            self._cell_finish(key, None)
            return
        # a dense cell takes 45-76 ms to parse, slim and encode: off the GUI thread, the job is answered back on it
        run_async(partial(_parse_cell, key, features, text), partial(self._cell_parsed, key, features),
                  partial(self._cell_unreadable, key))

    def _cell_parsed(self, key: tuple[int, int], features: list, data: bytes | None) -> None:
        if data is not None:
            self._cell_finish(key, data)
            self._store(buildings.cache_path(self.root, *key), data)
        elif shiboken6.isValid(self._nam):  # the profile may have been released while the page was parsed
            self._cell_page(key, features)
        else:
            self._cell_finish(key, None)

    def _cell_unreadable(self, key: tuple[int, int], error: Exception) -> None:
        # any malformed answer: logged, the waiting pages get a 404
        _log(f"buildings {key}: unreadable answer: {''.join(traceback.format_exception(error))}")
        self._cell_finish(key, None)

    def _cell_finish(self, key: tuple[int, int], data: bytes | None) -> None:
        for done in self._cells_waiting.pop(key, []):
            done(data)


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
            self.store.get(req, partial(self._answer_tile, job))
        elif isinstance(req, mapcache.PeaksRequest):
            self.store.peaks(req, lambda found, j=job: self._answer(j, b"application/json", json.dumps(found).encode()))
        elif isinstance(req, mapcache.BuildingsRequest):
            self.store.buildings(req, lambda found, j=job: self._answer(j, b"application/json", found))
        else:
            job.fail(QWebEngineUrlRequestJob.Error.UrlNotFound)

    def _answer_tile(self, job: QWebEngineUrlRequestJob, tile: tuple[str, bytes] | None) -> None:
        self._answer(job, tile[0].encode() if tile else b"", tile[1] if tile else None)

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
        for kind in mapcache.OLD_KINDS:  # tiles of a former base layer, never read again
            _remove_old_cache(root / "tiles2" / kind)
        prof = QWebEngineProfile("memento-map", QCoreApplication.instance())
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
    """A cache no longer read (the hand-drawn map's OpenStreetMap tiles, a former base layer's): deleted in the
    background. Once gone, later startups find nothing to do."""
    if not old.is_dir():
        return

    def run() -> None:
        try:
            shutil.rmtree(old)
        except OSError as error:
            _log(f"cannot delete the old tile cache {old}: {error}")
    threading.Thread(target=run, name="old-tiles", daemon=True).start()
