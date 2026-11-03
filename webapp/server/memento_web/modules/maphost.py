"""Host side of the shared map page (web/map), with the same contract as the Android and Windows hosts:
`/map/*` serves the page, `/t/ortho|dem/{z}/{x}/{y}` the tiles (fetched once with an identifying User-Agent, then
from a 300 MB least-recently-used disk cache in /config/tiles, IGN's missing photo replaced by OpenStreetMap), and
`/peaks?w&s&e&n` the labelled summits, `/buildings/15/{x}/{y}` the IGN buildings of one cell. With MEMENTO_TILES_URL
set, all of these come from the Tuileries server instead (credentials added here, never seen by the page), still
through the same disk cache, and `/api/map/tiles` says which source is in use. The rules (URLs,
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

from ..core import mapcache, peaks, tuileries
from memento_win.core import buildings  # noqa: E402 (..core put memento_win on the path)
from ..errors import not_found

PURGE_EVERY = 20 * 1024 * 1024
TOUCH_AFTER = 3600  # s: the LRU order is kept to the hour (as mapcache.purge says), so a hit is a read, not a write


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


def http_get(url: str, headers: dict[str, str] | None = None) -> tuple[int, str, bytes]:
    """[headers]: Tuileries' credentials, given only for its URLs; a redirect elsewhere is refused (SameHostRedirects)."""
    request = urllib.request.Request(url, headers=headers or {"User-Agent": mapcache.USER_AGENT})
    try:
        with _OPENER.open(request, timeout=15) as reply:
            return reply.status, reply.headers.get("Content-Type", ""), _read(reply)
    except urllib.error.HTTPError as error:
        return error.code, (error.headers or {}).get("Content-Type", ""), _read(error) if error.fp else b""
    except (urllib.error.URLError, TimeoutError, OSError) as error:
        raise Offline(str(error)) from None


def _log(text: str) -> None:
    print(f"memento-web maphost: {text}", flush=True)


def _touch(path: Path) -> None:
    try:
        if time.time() - path.stat().st_mtime > TOUCH_AFTER:
            os.utime(path)
    except OSError:
        pass


class TileStore:
    """Thread-safe: concurrent requests for one tile wait for a single fetch."""

    def __init__(self, root: Path, fetch=http_get, clock=time.monotonic, source: tuileries.Tuileries | None = None):
        self.root = root
        self.fetch = fetch
        self.source = source  # Tuileries when configured: then the only server asked
        self._info: tuple[float, dict] | None = None  # (when, answer) of the last good /api/info
        self._meta_seen: dict[tuple, tuple[float, dict]] = {}  # block -> (when asked, status of each photo entry)
        self._meta_off_until = 0.0
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
        for cached in tile.variants():
            path = mapcache.tile_path(self.root, cached)
            try:
                data = path.read_bytes()
            except FileNotFoundError:
                continue
            except OSError as error:  # being purged right now: fetched again
                _log(f"{path}: {error}")
                continue
            _touch(path)  # LRU: the purge removes the least recently read
            return cached.mime, data
        return None

    def get(self, tile: mapcache.TileRequest) -> tuple[str, bytes] | None:
        found = self._cached(tile)
        if found is not None:
            return found
        if self.source is not None and tile.kind == "ortho":
            answered, found = self._meta(tile)
            if answered:
                return found
        with self._lock_for(tile.key):
            found = self._cached(tile)  # fetched by the request we waited for
            if found is not None:
                return found
            if self.backoff.blocked(self.clock()):
                return None
            if self.source is not None:
                return self._tuileries_tile(tile)
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

    # ---- Tuileries metatiles: one request for a 4x4 photo block and the relief, buildings and trails under it ----

    META_AGAIN_AFTER = 60.0  # a block's metatile is not asked again sooner; its missing tiles are asked for alone
    META_OFF_FOR = 600.0  # a Tuileries without /meta (404) gets single-tile requests that long

    def _meta(self, tile: mapcache.TileRequest) -> tuple[bool, tuple[str, bytes] | None]:
        """(True, answer) when the block's metatile answered for the tile, (False, None) when it is to be asked for
        alone (pending, failed, recently asked, no /meta). The block's other tiles wait for the same metatile and
        then find themselves in the cache."""
        block = tuileries.meta_block(tile.z, tile.x, tile.y)
        with self._lock_for(f"meta/{block}"):
            found = self._cached(tile)
            if found is not None:
                return True, found
            now = self.clock()
            seen = self._meta_seen.get(block)
            if seen is not None and now - seen[0] < self.META_AGAIN_AFTER:
                return seen[1].get((tile.x, tile.y)) == tuileries.META_NONE, None
            if now < self._meta_off_until:
                return False, None
            if self.backoff.blocked(now):
                return True, None
            try:
                status, _kind, data = self._tuileries(tuileries.meta_url(self.source, *block))
            except Offline as error:
                self.backoff.failed(self.clock())
                _log(f"metatile {block}: Tuileries unreachable ({error})")
                return True, None
            self.backoff.succeeded()
            entries = []
            if status == 200:
                try:
                    entries = tuileries.parse_meta(data)
                except ValueError as error:
                    _log(f"metatile {block}: {error}")
            else:
                if status == 404:  # a Tuileries from before metatiles
                    self._meta_off_until = now + self.META_OFF_FOR
                _log(f"metatile {block}: Tuileries HTTP {status}")
            statuses, answer = {}, None
            for e in entries:
                target = tuileries.meta_cache_target(self.root, e)
                if target is not None:
                    self._store(*target)
                if e.layer == "ortho":
                    statuses[(e.x, e.y)] = e.status
                    stored = tile.typed(e.mime) if (e.x, e.y) == (tile.x, tile.y) and target is not None else None
                    if stored is not None:
                        answer = (stored.mime, e.data)
            if len(self._meta_seen) > 4000:
                self._meta_seen = {k: v for k, v in self._meta_seen.items() if now - v[0] < self.META_AGAIN_AFTER}
            self._meta_seen[block] = (now, statuses)
            if answer is not None:
                return True, answer
            return statuses.get((tile.x, tile.y)) == tuileries.META_NONE, None

    def _tuileries(self, url: str) -> tuple[int, str, bytes]:
        return self.fetch(url, self.source.headers())

    def _tuileries_tile(self, tile: mapcache.TileRequest) -> tuple[str, bytes] | None:
        """No stand-in here: Tuileries applies the OpenStreetMap rule itself, and a failure fails like offline."""
        try:
            status, kind, data = self._tuileries(self.source.tile_url(tile))
        except Offline as error:
            self.backoff.failed(self.clock())
            _log(f"{tile.key}: Tuileries unreachable ({error})")
            return None
        self.backoff.succeeded()
        stored = tuileries.tile_answer(tile, status, kind, data)
        if stored is None:
            if status not in (204, 404):
                _log(f"{tile.key}: Tuileries HTTP {status} {kind}")
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
        if self.source is not None:
            return self._tuileries_peaks(req)
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


    def _tuileries_peaks(self, req: mapcache.PeaksRequest) -> list[dict]:
        """Tuileries answers the selected list for the exact square: cached as such, in its own folder."""
        wanted = (req.w, req.s, req.e, req.n)
        file = self.root / "peaks-tuileries" / ("_".join(f"{v:.5f}" for v in wanted) + ".json")
        with self._lock_for(f"peaks-t/{wanted}"):
            try:
                return tuileries.parse_peaks(file.read_bytes())
            except FileNotFoundError:
                pass
            except (OSError, ValueError, KeyError, TypeError) as error:
                _log(f"summits: damaged cache {file.name}: {error!r}")
            if self.backoff.blocked(self.clock()):
                return []
            try:
                status, _kind, data = self._tuileries(self.source.peaks_url(*wanted))
            except Offline as error:
                self.backoff.failed(self.clock())
                _log(f"summits: Tuileries unreachable ({error})")
                return []
            self.backoff.succeeded()
            if status != 200:
                _log(f"summits: Tuileries HTTP {status}")
                return []
            try:
                found = tuileries.parse_peaks(data)
            except (ValueError, KeyError, TypeError) as error:
                _log(f"summits: unreadable Tuileries answer: {error!r}")
                return []
            self._store(file, json.dumps(found).encode())
            return found

    INFO_TTL = 3600.0  # a good /api/info answer is kept this long (the map page asks for it at each load)

    def info(self, fresh: bool = False) -> dict:
        """The source in use and, for Tuileries, its /api/info or why it failed: the settings' status line, and the
        map page's trails and attribution."""
        if self.source is None:
            return {"source": "direct"}
        cached = self._info
        if not fresh and cached and self.clock() - cached[0] < self.INFO_TTL:
            return cached[1]
        out = {"source": "tuileries", "url": self.source.base}
        try:
            status, _kind, data = self._tuileries(self.source.info_url)
            info = tuileries.info_answer(status, data)
        except Offline as error:
            out["error"] = f"Serveur injoignable ({error})"
        except tuileries.TuileriesError as error:
            out["error"] = str(error)
        else:
            out.update(name=info.name, version=info.version, max_zoom=info.max_zoom, attribution=info.attribution)
            self._info = (self.clock(), out)
        return out

    def trails(self, req: mapcache.TrailsRequest) -> bytes | None:
        """The vector tile from the cache, else from Tuileries; b"" where it has none (never cached), None on error
        or without Tuileries."""
        if self.source is None:
            return None
        path = mapcache.trails_path(self.root, req)
        with self._lock_for(f"trails/{req.z}/{req.x}/{req.y}"):
            try:
                data = path.read_bytes()
                _touch(path)
                return data
            except FileNotFoundError:
                pass
            except OSError as error:
                _log(f"{path}: {error}")
            if self.backoff.blocked(self.clock()):
                return None
            try:
                status, _kind, data = self._tuileries(self.source.trails_url(req))
            except Offline as error:
                self.backoff.failed(self.clock())
                _log(f"trails: Tuileries unreachable ({error})")
                return None
            self.backoff.succeeded()
            found = tuileries.trails_answer(status, data)
            if found is None:
                _log(f"trails {req.z}/{req.x}/{req.y}: Tuileries HTTP {status}")
            elif found:
                self._store(path, found)
            return found

    def buildings(self, req: mapcache.BuildingsRequest) -> bytes | None:
        """The cell's slim JSON, from the cache or fetched page by page; None offline or on a WFS error."""
        path = buildings.cache_path(self.root, req.x, req.y)
        with self._lock_for(f"buildings/{req.x}/{req.y}"):
            try:
                data = path.read_bytes()
                _touch(path)
                return data
            except FileNotFoundError:
                pass
            except OSError as error:
                _log(f"{path}: {error}")
            if self.backoff.blocked(self.clock()):
                return None
            if self.source is not None:
                return self._tuileries_cell(req, path)
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


    def _tuileries_cell(self, req: mapcache.BuildingsRequest, path: Path) -> bytes | None:
        try:
            status, _kind, data = self._tuileries(self.source.buildings_url(req.x, req.y))
        except Offline as error:
            self.backoff.failed(self.clock())
            _log(f"buildings: Tuileries unreachable ({error})")
            return None
        self.backoff.succeeded()
        if status != 200 or not tuileries.valid_cell(data, req.x, req.y):
            if status not in (204, 404):
                _log(f"buildings {req.x}/{req.y}: Tuileries HTTP {status}")
            return None
        self._store(path, data)
        return data


def _matches(if_none_match: str, etag: str) -> bool:
    """If-None-Match holds `etag` (compared weakly, as RFC 9110 asks for a GET) or `*`."""
    return any(tag.strip().removeprefix("W/") in (etag, "*") for tag in if_none_match.split(",") if tag.strip())


def default_map_dir(here: Path | None = None) -> Path:
    """web/map next to the package's parents: /app/web/map in the image, <repo>/web/map in a checkout."""
    here = (here or Path(__file__)).resolve()
    # the image is only 3 levels deep (/app/memento_web/modules): never index a parent that may not exist
    for base in here.parents:
        if (base / "web" / "map" / "index.html").is_file():
            return base / "web" / "map"
    raise RuntimeError("page de la carte introuvable (web/map) : définir MEMENTO_MAP")


class MapHost:
    def __init__(self, tiles_dir: Path, map_dir: Path | None = None, fetch=http_get, source: tuileries.Tuileries | None = None):
        self.store = TileStore(tiles_dir, fetch, source=source)
        self.web = (map_dir or default_map_dir()).parent
        self.store.purge_async()

    def routes(self) -> list[Route]:
        return [
            Route("/map/{rest:path}", self.page, methods=["GET"]),
            Route("/t/{kind}/{z}/{x}/{y}", self.tile, methods=["GET"]),
            Route("/peaks", self.summits, methods=["GET"]),
            Route("/buildings/{z}/{x}/{y}", self.buildings, methods=["GET"]),
            Route("/trails/{z}/{x}/{y}", self.trails, methods=["GET"]),
            Route("/api/map/tiles", self.tiles_status, methods=["GET"]),
        ]

    async def tiles_status(self, request: Request) -> JSONResponse:
        fresh = request.query_params.get("fresh") == "1"  # "Tester la connexion": never the kept answer
        return JSONResponse(await run_in_threadpool(self.store.info, fresh), headers={"Cache-Control": "no-store"})

    async def trails(self, request: Request) -> Response:
        req = mapcache.parse_request(request.url.path)
        if not isinstance(req, mapcache.TrailsRequest):
            raise not_found("Tuile inconnue")
        data = await run_in_threadpool(self.store.trails, req)
        if data is None:
            return Response(status_code=404, headers={"Cache-Control": "no-store"})
        if not data:  # no trail there yet (or still being built): asked again soon
            return Response(status_code=204, headers={"Cache-Control": "private, max-age=300"})
        return Response(data, media_type=mapcache.TRAILS_MIME, headers={"Cache-Control": "private, max-age=86400"})

    async def page(self, request: Request) -> Response:
        """The file, or its .gz sibling (built with the image) to a browser that accepts gzip; an ETag lets a
        browser whose copy expired get a 304 instead of the 800 kB of maplibre again."""
        req = mapcache.parse_request(request.url.path)
        if not isinstance(req, mapcache.FileRequest) or req.path.endswith(".gz"):
            raise not_found()
        path = self.web / req.path
        try:
            st = path.stat()
        except OSError:
            raise not_found() from None
        try:
            gz_st = path.with_name(path.name + ".gz").stat()
        except OSError:
            gz_st = None
        headers = {"Cache-Control": "private, max-age=3600"}
        sent, sent_st = path, st
        # an older sibling is stale (edited checkout); whole seconds, as a copy may not keep gzip's nanoseconds
        if gz_st is not None and gz_st.st_mtime_ns // 10**9 >= st.st_mtime_ns // 10**9:
            headers["Vary"] = "Accept-Encoding"
            if "gzip" in request.headers.get("accept-encoding", "").lower():
                sent, sent_st = path.with_name(path.name + ".gz"), gz_st
        headers["ETag"] = f'"{sent_st.st_size:x}-{sent_st.st_mtime_ns:x}"'
        if _matches(request.headers.get("if-none-match", ""), headers["ETag"]):
            return Response(status_code=304, headers=headers)
        try:
            data = sent.read_bytes()
        except OSError:
            raise not_found() from None
        if sent is not path:
            headers["Content-Encoding"] = "gzip"
        mime = mimetypes.guess_type(path.name)[0] or "application/octet-stream"
        return Response(data, media_type=mime, headers=headers)

    async def tile(self, request: Request) -> Response:
        req = mapcache.parse_request(request.url.path)
        if not isinstance(req, mapcache.TileRequest):
            raise not_found("Tuile inconnue")
        found = await run_in_threadpool(self.store.get, req)
        if found is None:
            return Response(status_code=404, headers={"Cache-Control": "no-store"})
        mime, data = found
        # a stand-in is only as good as the IGN answer that caused it: let the browser ask again soon
        age = 3600 if req.kind == "ortho" and mime == mapcache.MIME["osmfb2"] else 86400
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
