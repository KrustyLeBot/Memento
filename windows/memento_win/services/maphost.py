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
from functools import partial
from urllib.parse import quote
from pathlib import Path
from typing import Callable

import shiboken6
from PySide6.QtCore import QBuffer, QByteArray, QCoreApplication, QIODevice, QObject, QStandardPaths, Qt, QUrl
from PySide6.QtNetwork import QNetworkAccessManager, QNetworkReply, QNetworkRequest
from PySide6.QtWebEngineCore import (QWebEngineProfile, QWebEngineUrlRequestJob, QWebEngineUrlScheme,
                                     QWebEngineUrlSchemeHandler)

from ..core import buildings, mapcache, peaks, tuileries
from .async_util import run_async

SCHEME = b"scr"
BASE_URL = "scr://app/"
PURGE_EVERY = 20 * 1024 * 1024  # bytes written between two purges
META_AGAIN_AFTER = 60.0  # s: a block's metatile is not asked again sooner; its missing tiles are asked for alone
META_OFF_FOR = 600.0  # s: a Tuileries without /meta (404) gets single-tile requests that long
TOUCH_AFTER = 3600  # seconds: a read refreshes the file's date (LRU) only past this age, the purge needs no finer
MIME = {"html": b"text/html", "js": b"text/javascript", "css": b"text/css", "png": b"image/png", "jpg": b"image/jpeg", "webp": b"image/webp",
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


def _request(url: str, source: tuileries.Tuileries | None = None) -> QNetworkRequest:
    """[source]: a request to Tuileries, which alone gets its credentials; a redirect (Cloudflare Access's login page)
    is not followed, so they never travel elsewhere."""
    request = QNetworkRequest(QUrl(url))
    headers = source.headers() if source else {"User-Agent": mapcache.USER_AGENT}
    for name, value in headers.items():
        request.setRawHeader(QByteArray(name.encode()), QByteArray(value.encode()))
    if source:
        request.setAttribute(QNetworkRequest.Attribute.RedirectPolicyAttribute,
                             QNetworkRequest.RedirectPolicy.ManualRedirectPolicy)
    request.setTransferTimeout(15000)
    return request


# What the settings chose: the Tuileries server (None: IGN, OpenStreetMap and AWS directly) and the photo's top zoom.
_source: tuileries.Tuileries | None = None
_photo_max_zoom = tuileries.PHOTO_MAX_ZOOM_DEFAULT
_attribution = ""  # Tuileries' own credit, from its /api/info (read in the background)


def configure(source: tuileries.Tuileries | None, photo_max_zoom: int, fetch_info: bool = True) -> None:
    """Applies the map settings: to the tile store at once, to the pages on their next load."""
    global _source, _photo_max_zoom, _attribution
    source = source if source is not None and source.configured else None
    changed = source != _source
    _source = source
    _photo_max_zoom = tuileries.clamp_photo_max_zoom(photo_max_zoom)
    if changed:
        _attribution = ""
    if _profile is not None and shiboken6.isValid(_profile):
        store = _profile.findChild(TileStore)
        if store is not None:
            store.source = _source
    if _source is not None and changed and fetch_info:
        wanted = _source

        def run() -> None:
            global _attribution
            try:
                info = tuileries.fetch_info(wanted)
            except tuileries.TuileriesError as error:
                _log(f"Tuileries: {error}")
                return
            if _source == wanted:
                _attribution = info.attribution
        threading.Thread(target=run, name="tuileries-info", daemon=True).start()


def set_attribution(text: str) -> None:
    """From a successful "Tester la connexion": shown by the next page load."""
    global _attribution
    _attribution = text


def photo_max_zoom() -> int:
    return _photo_max_zoom


def page_query() -> str:
    """What the page takes from the host's settings: the photo's top zoom and, with Tuileries, its trails and credit."""
    query = f"&photomax={_photo_max_zoom}"
    if _source is not None:
        query += "&trails=1" + (f"&attrib={quote(_attribution)}" if _attribution else "")
    return query


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
        self.source = _source  # Tuileries when configured: then the only server asked
        self._nam = QNetworkAccessManager(self)
        self._waiting: dict[str, list[Callable[[bytes | None], None]]] = {}
        self._peaks_waiting: dict[tuple, list[Callable[[list | None], None]]] = {}
        self._cells_waiting: dict[tuple[int, int], list[Callable[[bytes | None], None]]] = {}
        self._pending: dict[Path, bytes] = {}  # handed to a worker, not on disk yet: served from here meanwhile
        self._meta_waiting: dict[tuple, list[tuple[mapcache.TileRequest, Callable]]] = {}
        self._meta_asked: dict[tuple, float] = {}  # block -> when its metatile was last asked for
        self._meta_off_until = 0.0
        self._written = 0
        self._purge_lock = threading.Lock()
        self.purge_async()

    # ---- tiles ----

    def get(self, tile: mapcache.TileRequest, done: Callable[[tuple[str, bytes] | None], None]) -> None:
        for cached in tile.variants():
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
        if self.source is not None and tile.kind == "ortho" and self._meta(tile, done):
            return
        self._single(tile, done)

    def _single(self, tile: mapcache.TileRequest, done: Callable[[tuple[str, bytes] | None], None]) -> None:
        if tile.key in self._waiting:
            self._waiting[tile.key].append(done)
            return
        self._waiting[tile.key] = [done]
        if self.source is not None:
            reply = self._nam.get(_request(self.source.tile_url(tile), self.source))
            reply.finished.connect(partial(self._tuileries_fetched, reply, tile))
            return
        self._fetch(tile, mapcache.upstream_url(tile), primary=True)

    # ---- Tuileries metatiles: one request for a 4x4 photo block and the relief, buildings and trails under it ----

    def _meta(self, tile: mapcache.TileRequest, done: Callable) -> bool:
        """Asks for (or joins) the metatile of the tile's block; False when the tile is to be asked for alone."""
        block = tuileries.meta_block(tile.z, tile.x, tile.y)
        if block in self._meta_waiting:
            self._meta_waiting[block].append((tile, done))
            return True
        now = time.monotonic()
        if now < self._meta_off_until or now - self._meta_asked.get(block, -1e9) < META_AGAIN_AFTER:
            return False
        if len(self._meta_asked) > 4000:
            self._meta_asked = {k: v for k, v in self._meta_asked.items() if now - v < META_AGAIN_AFTER}
        self._meta_asked[block] = now
        self._meta_waiting[block] = [(tile, done)]
        reply = self._nam.get(_request(tuileries.meta_url(self.source, *block), self.source))
        reply.finished.connect(partial(self._meta_fetched, reply, block))
        return True

    def _meta_fetched(self, reply: QNetworkReply, block: tuple) -> None:
        try:
            error = reply.error()
            status = int(reply.attribute(QNetworkRequest.Attribute.HttpStatusCodeAttribute) or 0)
            data = bytes(reply.readAll())
        finally:
            reply.deleteLater()
        self._meta_answer(block, error in OFFLINE_ERRORS, status, data)

    def _meta_answer(self, block: tuple, offline: bool, status: int, data: bytes) -> None:
        """Every received entry goes to its cache (the page's next requests for that area are local); each waiting
        photo tile gets its entry, or is asked for alone when its entry is pending, failed or missing."""
        waiting = self._meta_waiting.pop(block, [])
        if offline:
            self.backoff.failed(time.monotonic())
            for _tile, done in waiting:
                done(None)
            return
        self.backoff.succeeded()
        entries = []
        if status == 200:
            try:
                entries = tuileries.parse_meta(data)
            except ValueError as error:
                _log(f"metatile {block}: {error}")
        else:
            if status == 404:  # a Tuileries from before metatiles
                self._meta_off_until = time.monotonic() + META_OFF_FOR
            _log(f"metatile {block}: Tuileries HTTP {status}")
        photos = {}
        for e in entries:
            target = tuileries.meta_cache_target(self.root, e)
            if target is not None:
                self._store(*target)
            if e.layer == "ortho":
                photos[(e.x, e.y)] = e
        for tile, done in waiting:
            e = photos.get((tile.x, tile.y))
            stored = tile.typed(e.mime) if e is not None and e.status == tuileries.META_OK and e.data else None
            if stored is not None:
                done((stored.mime, e.data))
            elif e is not None and e.status == tuileries.META_NONE:
                done(None)
            else:
                self._single(tile, done)

    def _tuileries_fetched(self, reply: QNetworkReply, tile: mapcache.TileRequest) -> None:
        """No stand-in here: Tuileries applies the OpenStreetMap rule itself, and a failure fails like offline."""
        try:
            error = reply.error()
            status = int(reply.attribute(QNetworkRequest.Attribute.HttpStatusCodeAttribute) or 0)
            kind = str(reply.header(QNetworkRequest.KnownHeaders.ContentTypeHeader) or "")
            data = bytes(reply.readAll())
        finally:
            reply.deleteLater()
        self._tuileries_answer(tile, error in OFFLINE_ERRORS, status, kind, data)

    def _tuileries_answer(self, tile: mapcache.TileRequest, offline: bool, status: int, kind: str, data: bytes) -> None:
        if offline:
            self.backoff.failed(time.monotonic())
            self._finish(tile.key, None)
            return
        self.backoff.succeeded()
        stored = tuileries.tile_answer(tile, status, kind, data)
        if stored is None:
            if status not in (204, 404):
                _log(f"{tile.key}: Tuileries HTTP {status} {kind}")
            self._finish(tile.key, None)
            return
        self._store(mapcache.tile_path(self.root, stored), data)
        self._finish(tile.key, (stored.mime, data))

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
        if self.source is not None:
            self._tuileries_peaks(req, done)
            return
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


    def _tuileries_peaks(self, req: mapcache.PeaksRequest, done: Callable[[list], None]) -> None:
        """Tuileries answers the selected list for the exact square: cached as such, in its own folder."""
        wanted = (req.w, req.s, req.e, req.n)
        file = tuileries_peaks_file(self.root, wanted)
        try:
            done(tuileries.parse_peaks(self._pending[file] if file in self._pending else file.read_bytes()))
            return
        except FileNotFoundError:
            pass
        except (OSError, ValueError, KeyError, TypeError) as error:  # damaged: fetched again
            _log(f"summits: damaged cache {file.name}: {error!r}")
        if self.backoff.blocked(time.monotonic()):
            done([])
            return
        key = ("tuileries",) + wanted
        if key in self._peaks_waiting:
            self._peaks_waiting[key].append(done)
            return
        self._peaks_waiting[key] = [done]
        reply = self._nam.get(_request(self.source.peaks_url(*wanted), self.source))
        reply.finished.connect(partial(self._tuileries_peaks_done, reply, key, file))

    def _tuileries_peaks_done(self, reply: QNetworkReply, key: tuple, file: Path) -> None:
        self._tuileries_peaks_text(self._text(reply), key, file)

    def _tuileries_peaks_text(self, text: str | None, key: tuple, file: Path) -> None:
        found = None
        if text is not None:
            try:
                found = tuileries.parse_peaks(text.encode())
            except (ValueError, KeyError, TypeError) as error:
                _log(f"summits: unreadable Tuileries answer: {error!r}")
        if found is not None:
            self._store(file, json.dumps(found).encode())
        for done in self._peaks_waiting.pop(key, []):
            done(found or [])

    # ---- trails (Tuileries only) ----

    def trails(self, req: mapcache.TrailsRequest, done: Callable[[bytes | None], None]) -> None:
        """The vector tile from the cache, else from Tuileries; b"" where it has none (never cached), None on error."""
        if self.source is None:
            done(None)
            return
        path = mapcache.trails_path(self.root, req)
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
        key = f"trails/{req.z}/{req.x}/{req.y}"
        if key in self._waiting:
            self._waiting[key].append(done)
            return
        self._waiting[key] = [done]
        reply = self._nam.get(_request(self.source.trails_url(req), self.source))
        reply.finished.connect(partial(self._trails_fetched, reply, req, key))

    def _trails_fetched(self, reply: QNetworkReply, req: mapcache.TrailsRequest, key: str) -> None:
        try:
            error = reply.error()
            status = int(reply.attribute(QNetworkRequest.Attribute.HttpStatusCodeAttribute) or 0)
            data = bytes(reply.readAll())
        finally:
            reply.deleteLater()
        self._trails_answer(req, key, error in OFFLINE_ERRORS, status, data)

    def _trails_answer(self, req: mapcache.TrailsRequest, key: str, offline: bool, status: int, data: bytes) -> None:
        if offline:
            self.backoff.failed(time.monotonic())
            self._finish(key, None)
            return
        self.backoff.succeeded()
        found = tuileries.trails_answer(status, data)
        if found is None:
            _log(f"{key}: Tuileries HTTP {status}")
        elif found:
            self._store(mapcache.trails_path(self.root, req), found)
        self._finish(key, found)

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
        if self.source is not None:
            reply = self._nam.get(_request(self.source.buildings_url(*key), self.source))
            reply.finished.connect(partial(self._tuileries_cell_done, reply, key))
            return
        self._cell_page(key, [])

    def _tuileries_cell_done(self, reply: QNetworkReply, key: tuple[int, int]) -> None:
        self._tuileries_cell_text(self._text(reply), key)

    def _tuileries_cell_text(self, text: str | None, key: tuple[int, int]) -> None:
        data = text.encode() if text is not None else None
        if data is not None and not tuileries.valid_cell(data, *key):
            _log(f"buildings {key}: unreadable Tuileries answer")
            data = None
        if data is not None:
            self._store(buildings.cache_path(self.root, *key), data)
        for done in self._cells_waiting.pop(key, []):
            done(data)

    def _cell_page(self, key: tuple[int, int], features: list) -> None:
        reply = self._nam.get(_request(buildings.wfs_url(*key, len(features))))
        reply.finished.connect(partial(self._cell_page_done, reply, key, features))

    def _cell_page_done(self, reply: QNetworkReply, key: tuple[int, int], features: list) -> None:
        text = self._text(reply)
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
        elif isinstance(req, mapcache.TrailsRequest):
            # an empty body is an empty vector tile: the page draws no trail there
            self.store.trails(req, lambda found, j=job: self._answer(j, mapcache.TRAILS_MIME.encode(), found))
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


def tuileries_peaks_file(root: Path, box: tuple) -> Path:
    return root / "peaks-tuileries" / ("_".join(f"{v:.5f}" for v in box) + ".json")


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
