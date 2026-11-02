"""Host side of the shared map page (web/map), with the same contract as the Android and Windows hosts:
`/map/*` serves the page, `/t/ortho|dem/{z}/{x}/{y}` the tiles (fetched once with an identifying User-Agent, then
from a 300 MB least-recently-used disk cache in /config/tiles, IGN's missing photo replaced by OpenStreetMap), and
`/peaks?w&s&e&n` the labelled summits, `/buildings/15/{x}/{y}` the IGN buildings of one cell. The rules (URLs,
fallback, purge, peaks selection, buildings slimming) are core/mapcache.py, core/peaks.py and core/buildings.py; this
file only does the I/O. The page itself asks for everything relative to `<base>` = the site root.
"""
from __future__ import annotations

import json
import mimetypes
import os
import shutil
import threading
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path
from urllib.parse import urlsplit

from starlette.concurrency import run_in_threadpool
from starlette.requests import Request
from starlette.responses import JSONResponse, Response
from starlette.routing import Route

from ..core import mapcache, peaks
from memento_win.core import buildings  # noqa: E402 (..core put memento_win on the path)
from ..errors import not_found

PURGE_EVERY = 20 * 1024 * 1024


class Offline(Exception):
    """No answer at all (DNS, refused, timeout), as opposed to a server answering an error."""


MAX_REPLY = 8_000_000  # a tile is tens of kB, a summits answer a few hundred, a WFS page of buildings ~1.2 MB


class SameHostRedirects(urllib.request.HTTPRedirectHandler):
    """The upstream URLs are fixed (core mapcache/peaks); a redirect is followed only over https to the same host, so
    a compromised or spoofed upstream cannot point the server at the LAN or at the metadata of the machine."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        old, new = urlsplit(req.full_url), urlsplit(newurl)
        if new.scheme != "https" or new.hostname != old.hostname:
            raise urllib.error.HTTPError(newurl, code, f"redirect to another host refused ({new.hostname})", headers, fp)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


_OPENER = urllib.request.build_opener(SameHostRedirects)


def _read(reply) -> bytes:
    data = reply.read(MAX_REPLY + 1)
    if len(data) > MAX_REPLY:
        raise Offline(f"answer over {MAX_REPLY} bytes")
    return data


def http_get(url: str) -> tuple[int, str, bytes]:
    request = urllib.request.Request(url, headers={"User-Agent": mapcache.USER_AGENT})
    try:
        with _OPENER.open(request, timeout=15) as reply:
            return reply.status, reply.headers.get("Content-Type", ""), _read(reply)
    except urllib.error.HTTPError as error:
        return error.code, (error.headers or {}).get("Content-Type", ""), _read(error) if error.fp else b""
    except (urllib.error.URLError, TimeoutError, OSError) as error:
        raise Offline(str(error)) from None


def _log(text: str) -> None:
    print(f"memento-web maphost: {text}", flush=True)


class TileStore:
    """Thread-safe: concurrent requests for one tile wait for a single fetch."""

    def __init__(self, root: Path, fetch=http_get, clock=time.monotonic):
        self.root = root
        self.fetch = fetch
        self.clock = clock
        self.backoff = mapcache.Backoff()
        self._locks: dict[str, threading.Lock] = {}
        self._locks_guard = threading.Lock()
        self._written = 0
        self._purge_lock = threading.Lock()
        for kind in mapcache.OLD_KINDS:
            shutil.rmtree(root / kind, ignore_errors=True)

    def _lock_for(self, key: str) -> threading.Lock:
        with self._locks_guard:
            if len(self._locks) > 2000:
                self._locks = {k: v for k, v in self._locks.items() if v.locked()}
            return self._locks.setdefault(key, threading.Lock())

    def _cached(self, tile: mapcache.TileRequest) -> tuple[str, bytes] | None:
        for cached in (tile, tile.fallback()) if tile.kind == "ortho" else (tile,):
            path = mapcache.tile_path(self.root, cached)
            try:
                data = path.read_bytes()
            except FileNotFoundError:
                continue
            except OSError as error:  # being purged right now: fetched again
                _log(f"{path}: {error}")
                continue
            try:
                os.utime(path)  # LRU: the purge removes the least recently read
            except OSError:
                pass
            return cached.mime, data
        return None

    def get(self, tile: mapcache.TileRequest) -> tuple[str, bytes] | None:
        found = self._cached(tile)
        if found is not None:
            return found
        with self._lock_for(tile.key):
            found = self._cached(tile)  # fetched by the request we waited for
            if found is not None:
                return found
            if self.backoff.blocked(self.clock()):
                return None
            try:
                status, kind, data = self.fetch(mapcache.upstream_url(tile))
                stored = tile
                if tile.kind == "ortho" and mapcache.ortho_needs_fallback(status, kind, data):
                    status, kind, data = self.fetch(mapcache.fallback_url(tile))  # outside IGN's coverage
                    stored = tile.fallback()
            except Offline as error:
                self.backoff.failed(self.clock())
                _log(f"{tile.key}: offline ({error})")
                return None
            self.backoff.succeeded()
            if status != 200 or not kind.startswith("image/") or not data:
                _log(f"{tile.key}: HTTP {status} {kind}")
                return None
            self._store(mapcache.tile_path(self.root, stored), data)
            return stored.mime, data

    def _store(self, path: Path, data: bytes) -> None:
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
            return

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

    def peaks(self, req: mapcache.PeaksRequest) -> list[dict]:
        box = peaks.cache_box(req.w, req.s, req.e, req.n)
        wanted = (req.w, req.s, req.e, req.n)
        if peaks.too_large(box):
            return []
        directory = self.root / "peaks"
        with self._lock_for(f"peaks/{box}"):
            cached = peaks.covering(directory, box)
            if cached is not None:
                try:
                    return peaks.select(cached, wanted)
                except (KeyError, TypeError, ValueError) as error:
                    _log(f"summits: damaged cache for {box}: {error!r}")
            if self.backoff.blocked(self.clock()):
                return []
            try:
                status, _kind, data = self.fetch(peaks.wfs_url(box))
                if status != 200:
                    _log(f"summits: HTTP {status}")
                    return []
                candidates = peaks.parse_wfs(data.decode("utf-8", "replace"), box)
                if candidates:
                    status, _kind, data = self.fetch(peaks.alti_url(candidates))
                    if status == 200:
                        candidates = peaks.with_elevations(candidates, data.decode("utf-8", "replace"))
                    else:
                        _log(f"summit heights: HTTP {status}")
            except Offline as error:
                self.backoff.failed(self.clock())
                _log(f"summits: offline ({error})")
                return []
            except (ValueError, KeyError, TypeError) as error:  # a malformed answer: logged, the page gets none
                _log(f"summits: unreadable answer: {error!r}")
                return []
            self.backoff.succeeded()
            try:
                directory.mkdir(parents=True, exist_ok=True)
                file = peaks.cache_file(directory, box)
                tmp = file.with_name(f".{file.name}.{uuid.uuid4().hex}.tmp")
                tmp.write_text(json.dumps({"box": box, "candidates": candidates}), encoding="utf-8")
                os.replace(tmp, file)
            except OSError as error:
                _log(f"cannot cache the summits: {error}")
            return peaks.select(candidates, wanted) if candidates else []


    def buildings(self, req: mapcache.BuildingsRequest) -> bytes | None:
        """The cell's slim JSON, from the cache or fetched page by page; None offline or on a WFS error."""
        path = buildings.cache_path(self.root, req.x, req.y)
        with self._lock_for(f"buildings/{req.x}/{req.y}"):
            try:
                data = path.read_bytes()
                os.utime(path)
                return data
            except FileNotFoundError:
                pass
            except OSError as error:
                _log(f"{path}: {error}")
            if self.backoff.blocked(self.clock()):
                return None
            try:
                data = buildings.fetch_cell(req.x, req.y, self.fetch)
            except Offline as error:
                self.backoff.failed(self.clock())
                _log(f"buildings: offline ({error})")
                return None
            except buildings.WfsError as error:
                _log(f"buildings {req.x}/{req.y}: {error}")
                return None
            self.backoff.succeeded()
            self._store(path, data)
            return data


def default_map_dir(here: Path | None = None) -> Path:
    """web/map next to the package's parents: /app/web/map in the image, <repo>/web/map in a checkout."""
    here = (here or Path(__file__)).resolve()
    # the image is only 3 levels deep (/app/memento_web/modules): never index a parent that may not exist
    for base in here.parents:
        if (base / "web" / "map" / "index.html").is_file():
            return base / "web" / "map"
    raise RuntimeError("page de la carte introuvable (web/map) : définir MEMENTO_MAP")


class MapHost:
    def __init__(self, tiles_dir: Path, map_dir: Path | None = None, fetch=http_get):
        self.store = TileStore(tiles_dir, fetch)
        self.web = (map_dir or default_map_dir()).parent
        self.store.purge_async()

    def routes(self) -> list[Route]:
        return [
            Route("/map/{rest:path}", self.page, methods=["GET"]),
            Route("/t/{kind}/{z}/{x}/{y}", self.tile, methods=["GET"]),
            Route("/peaks", self.summits, methods=["GET"]),
            Route("/buildings/{z}/{x}/{y}", self.buildings, methods=["GET"]),
        ]

    async def page(self, request: Request) -> Response:
        req = mapcache.parse_request(request.url.path)
        if not isinstance(req, mapcache.FileRequest):
            raise not_found()
        path = self.web / req.path
        try:
            data = path.read_bytes()
        except OSError:
            raise not_found() from None
        mime = mimetypes.guess_type(path.name)[0] or "application/octet-stream"
        return Response(data, media_type=mime, headers={"Cache-Control": "private, max-age=3600"})

    async def tile(self, request: Request) -> Response:
        req = mapcache.parse_request(request.url.path)
        if not isinstance(req, mapcache.TileRequest):
            raise not_found("Tuile inconnue")
        found = await run_in_threadpool(self.store.get, req)
        if found is None:
            return Response(status_code=404, headers={"Cache-Control": "no-store"})
        mime, data = found
        # a stand-in is only as good as the IGN answer that caused it: let the browser ask again soon
        age = 86400 if mime == mapcache.MIME[req.kind] else 3600
        return Response(data, media_type=mime, headers={"Cache-Control": f"private, max-age={age}"})

    async def summits(self, request: Request) -> JSONResponse:
        req = mapcache.parse_request("/peaks", request.url.query)
        if not isinstance(req, mapcache.PeaksRequest):
            return JSONResponse([], status_code=400)
        return JSONResponse(await run_in_threadpool(self.store.peaks, req))

    async def buildings(self, request: Request) -> Response:
        req = mapcache.parse_request(request.url.path)
        if not isinstance(req, mapcache.BuildingsRequest):
            raise not_found("Cellule inconnue")
        data = await run_in_threadpool(self.store.buildings, req)
        if data is None:
            return Response(status_code=404, headers={"Cache-Control": "no-store"})
        return Response(data, media_type="application/json", headers={"Cache-Control": "private, max-age=86400"})
