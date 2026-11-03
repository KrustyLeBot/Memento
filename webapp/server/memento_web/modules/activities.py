"""Activités module: the list (frontmatter `activity:` summaries only), one activity's analysis (track parsed and
analysed only when it is opened, then cached), edits, delete with undo, FIT/GPX import and the optional altitude
completion from the terrain model. Formats and algorithms are the Windows core's (activity, analysis, fit, gpx,
track, mapdata, elevation, settings, store); this file only shapes them for the UI.

An activity is addressed by its note id (the agenda links `#/activities/<id>`), or by its file name
(`sortie-a1b2c3.md`) for a sync conflict copy, which shares its original's id.

Memory: an analysis holds every sample of the track (tens of MB for a long day), so it is never kept. What is kept
is the JSON the UI gets (charts and map thinned, a few hundred KB, kept gzipped), in an in-memory LRU (MEMORY_ITEMS /
MEMORY_BYTES) and on disk under /config/cache/activities so a reopened activity costs one file read. The RECENT most
recent activities' analyses are computed at warm-up (warm.py) and stay in memory when the server goes idle; the
others go. Reading and analysing a file (trackwork.py) happens outside the data lock, in a child process for a long
track; only the light commit (the note, the copy of the file) holds the lock. An import fills the analysis cache, so
the activity opens at once afterwards.

Imports and altitude completions run in a background thread (`/api/activities/jobs/<id>` reports progress): the
terrain service allows one request per second, so a batch can take minutes, longer than a proxied request may last.
An analysis that takes longer than ANALYSIS_WAIT answers 202 `{"pending": true}` and goes on: the page asks again.
"""
from __future__ import annotations

import asyncio
import gzip
import hashlib
import json
import os
import secrets
import threading
import time
import uuid
from collections import OrderedDict
from dataclasses import asdict
from pathlib import Path

from starlette.requests import Request
from starlette.responses import JSONResponse, Response
from starlette.routing import Route
from starlette.concurrency import run_in_threadpool

from .. import trackwork
from ..core import ACTIVITIES_DIR, MAX_ATTACHMENT, SETTINGS_PATH, ActivitySettings, Sport, atomic, fmt, now_ms
from ..data import _malloc_trim
from ..errors import ApiError, bad_request, json_body, not_found
from ..files import gunzip, owned, read_versioned, require_version, unlink_quiet, write_stream
from ..memo import Answers, json_response

from memento_win.core import activity as act  # noqa: E402  (..core put memento_win on the path)
from memento_win.core import elevation, mapdata  # noqa: E402
from memento_win.core.analysis import Analysis  # noqa: E402
from memento_win.core.store import default_title  # noqa: E402
from memento_win.core.track import TrackPoint  # noqa: E402

# Bump when the analysis JSON changes shape: cached files of another version are never read.
ANALYSIS_VERSION = 2
CHART_POINTS = 400   # charts: enough for a smooth curve and a precise hover on a wide screen
MAP_POINTS = 5000    # the map: as the Android and Windows hosts send it
GPS_SPORTS = [Sport.WALK, Sport.BIKE, Sport.EBIKE, Sport.SKI, Sport.GPS]
UPLOAD_TTL = 3600
# Above this size a track file is read in a child process (trackwork.py); 1 MB is ~5000 GPX points.
SUBPROCESS_BYTES = 1_000_000
TRACK_TIMEOUT = 900  # s: only stops a runaway child; a slow NAS takes ~30 s for a 200 000-point race
ANALYSIS_WAIT = 25.0  # s: an analysis request answers "pending" after this, well under Cloudflare's 100 s
JOB_TTL = 600
MEMORY_ITEMS = 40
MEMORY_BYTES = 24_000_000
RECENT = 10  # analyses prepared at warm-up and kept through idle periods
IMAGES = {"jpg", "jpeg", "png", "gif", "webp", "bmp"}


def _log(text: str) -> None:
    print(f"memento-web activities: {text}", flush=True)


# ---------------------------------------------------------------- shapes


def summary(note: fmt.Note) -> dict:
    i = note.activity
    return {
        "path": note.path, "id": note.id, "title": note.title, "conflict": note.conflict,
        "sport": i.sport.key, "start": i.start, "elapsed": i.elapsed, "moving": i.moving, "distance": round(i.distance, 1),
        "ascent": i.ascent, "descent": i.descent, "hr": i.hr, "vmax": i.vmax, "runs": i.runs, "drop": i.drop,
        "climbs": i.climbs, "has_file": bool(i.file),
    }


def detail(note: fmt.Note, version: str, analysis_key: str | None) -> dict:
    i = note.activity
    own = {i.file, i.alt}
    others = [a for a in note.attachments if a not in own]
    return {"activity": {**summary(note), "description": note.body, "file": i.file, "alt": i.alt,
                         "photos": [a for a in others if a.rsplit(".", 1)[-1].lower() in IMAGES],
                         "files": [a for a in others if a.rsplit(".", 1)[-1].lower() not in IMAGES] + ([note.pdf] if note.pdf else []),
                         "analysis_key": analysis_key},
            "version": version}


def _r(values, digits):
    return [None if v is None else round(v, digits) for v in values]


def _thin(n: int, limit: int) -> list[int]:
    return mapdata.thin_indices(n, limit)


def dumps(value) -> bytes:
    return json.dumps(value, separators=(",", ":")).encode()


def gzipped_response(request: Request, gz: bytes) -> Response:
    """Gzipped JSON as is to a browser that accepts it (all do), decompressed for anything else."""
    headers = {"Cache-Control": "no-store", "Vary": "Accept-Encoding"}
    if "gzip" in request.headers.get("accept-encoding", "").lower():
        return Response(gz, media_type="application/json", headers={**headers, "Content-Encoding": "gzip"})
    return Response(gzip.decompress(gz), media_type="application/json", headers=headers)


def _pos(v: float) -> float:
    return round(v, 6)  # 0.1 m: the core's floats carry 17 digits nobody draws


def analysis_json(a: Analysis, sport: Sport, alt_source: str | None) -> dict:
    """What the detail page draws: stats, stops, ski runs and climbs, charts and the map's data."""
    s = a.samples
    st = a.stats
    stats = {**asdict(st), "avg_speed": st.avg_speed}
    quality = {**asdict(a.quality), "alt_source": alt_source}
    stops = [{"start": x.start, "end": x.end, "duration": x.duration_ms // 1000, "manual": x.manual,
              "lat": _pos(x.lat), "lon": _pos(x.lon)} for x in sorted(a.pauses + a.stops, key=lambda x: x.start)]
    runs = [{"start": s[r.first].time, "end": s[r.last].time, "distance": round(r.distance, 1), "drop": round(r.drop),
             "max_speed": round(r.max_speed, 2), "avg_speed": round(r.avg_speed, 2), "duration": r.duration_ms // 1000,
             "lat": _pos(s[r.first].lat), "lon": _pos(s[r.first].lon)} for r in a.runs]
    climbs = [{"start": s[c.first].time, "end": s[c.last].time, "distance": round(c.distance, 1), "gain": round(c.gain),
               "duration": c.duration_ms // 1000, "lat": _pos(s[c.first].lat), "lon": _pos(s[c.first].lon)} for c in a.climbs]
    out = {"sport": sport.key, "stats": stats, "quality": quality, "stops": stops, "runs": runs, "climbs": climbs,
           "chart": None, "map": None,
           # a GPX without any <time>: its points have stand-in times, the analysis keeps no duration
           "untimed": len(s) >= 2 and st.elapsed_ms == 0}
    if len(s) < 2:
        return out
    idx = _thin(len(s), CHART_POINTS)
    pts = [s[i] for i in idx]
    walk = sport == Sport.WALK
    out["chart"] = {
        "dist": _r((p.dist for p in pts), 1),
        "lon": _r((p.lon for p in pts), 6), "lat": _r((p.lat for p in pts), 6),
        "alt": _r((p.alt for p in pts), 1) if a.quality.has_altitude and all(p.alt is not None for p in pts) else None,
        "speed": _r((p.speed for p in pts), 2) if st.max_speed > 0 else None,
        # walking: pace in s/km, capped (core activity.chart_pace) so a stop does not flatten the curve
        "pace": _r((act.chart_pace(p.speed) for p in pts), 1) if walk and st.max_speed > 0 else None,
        "hr": [p.hr for p in pts] if a.quality.has_hr and sum(p.hr is not None for p in pts) >= 2 else None,
    }
    idx = _thin(len(s), MAP_POINTS)
    pts = [s[i] for i in idx]
    ski = sport == Sport.SKI
    m = {"lon": _r((p.lon for p in pts), 6), "lat": _r((p.lat for p in pts), 6),
         "dots": [{"lon": _pos(s[0].lon), "lat": _pos(s[0].lat), "color": "#00C853"},
                  {"lon": _pos(s[-1].lon), "lat": _pos(s[-1].lat), "color": "#FF1744"}]}
    if ski:
        # coloured lines are built in the page from these (descents by speed, climbs in blue), as Windows' map_lines
        lats, lons = [p.lat for p in s], [p.lon for p in s]
        m.update({"square": [round(v, 7) for v in mapdata.square_of(min(lons), min(lats), max(lons), max(lats))],
                  "t": [(p.time - pts[0].time) // 1000 for p in pts], "speed": _r((p.speed for p in pts), 2),
                  "kind": "".join("d" if p.down else "u" if p.up else "o" for p in pts),
                  "max_gap": max(a.quality.median_interval_sec * 6, 30.0)})
    else:
        speeds, avg = mapdata.track_speeds([p.time for p in pts], [p.lat for p in pts], [p.lon for p in pts],
                                           [p.speed for p in pts])
        m.update({"speeds": _r(speeds, 2) if speeds else None, "avg": avg})
    out["map"] = m
    return out


# ---------------------------------------------------------------- analysis cache


SUFFIX = ".json.gz"


class AnalysisCache:
    """The analyses' JSON, gzipped (what most answers send as is; a fifth of the size), by key: a small LRU in memory
    (capped by count and by size), backed by files in `directory` (capped too, least recently used removed first).
    Concurrent requests for one key compute it once. `compute` returns the plain JSON bytes."""

    def __init__(self, directory: Path, max_items: int = MEMORY_ITEMS, max_bytes: int = MEMORY_BYTES,
                 disk_items: int = 400, disk_bytes: int = 64_000_000):
        self.directory = directory
        self.max_items, self.max_bytes = max_items, max_bytes
        self.disk_items, self.disk_bytes = disk_items, disk_bytes
        self.memory: OrderedDict[str, bytes] = OrderedDict()
        self.pinned: set[str] = set()  # kept by drop_memory (the recent activities')
        self._guard = threading.Lock()
        self._locks: dict[str, threading.Lock] = {}
        self.computed = 0  # for the tests

    @property
    def size(self) -> int:
        return sum(len(v) for v in self.memory.values())

    def drop_memory(self) -> None:
        """Idle hook: everything but the pinned analyses leaves memory (the disk copies stay)."""
        with self._guard:
            for key in [k for k in self.memory if k not in self.pinned]:
                del self.memory[key]

    def pin(self, keys) -> None:
        with self._guard:
            self.pinned = set(keys)

    def _remember(self, key: str, value: bytes) -> None:
        with self._guard:
            self.memory[key] = value
            self.memory.move_to_end(key)
            while self.memory and (len(self.memory) > self.max_items or self.size > self.max_bytes):
                self.memory.popitem(last=False)

    def _file(self, key: str) -> Path:
        return self.directory / f"{key}{SUFFIX}"

    def put(self, key: str, plain: bytes) -> None:
        """An analysis computed elsewhere (by an import, an edit): the next open reads it from here."""
        value = gzip.compress(plain, 6, mtime=0)
        with self._guard:
            lock = self._locks.setdefault(key, threading.Lock())
        with lock:
            self._write_disk(key, value)
            self._remember(key, value)
        with self._guard:
            self._locks.pop(key, None)

    def get(self, key: str, compute) -> bytes:
        with self._guard:
            if key in self.memory:
                self.memory.move_to_end(key)
                return self.memory[key]
            lock = self._locks.setdefault(key, threading.Lock())
        with lock:
            with self._guard:
                if key in self.memory:
                    return self.memory[key]
            value = self._read_disk(key)
            if value is None:
                value = gzip.compress(compute(), 6, mtime=0)
                self.computed += 1
                self._write_disk(key, value)
            self._remember(key, value)
        with self._guard:
            self._locks.pop(key, None)
        return value

    def _read_disk(self, key: str) -> bytes | None:
        try:
            value = self._file(key).read_bytes()
            os.utime(self._file(key))
            return value
        except OSError:
            return None

    def _write_disk(self, key: str, value: bytes) -> None:
        try:
            self.directory.mkdir(parents=True, exist_ok=True)
            tmp = self.directory / f".{key}.{uuid.uuid4().hex}.tmp"
            tmp.write_bytes(value)
            os.replace(tmp, self._file(key))
            self.purge_disk()
        except OSError as error:  # the cache is an optimisation: the answer still goes out
            _log(f"cannot cache an analysis: {error}")

    def purge_disk(self) -> None:
        try:
            entries = list(os.scandir(self.directory))
            for e in entries:  # another version's files (ANALYSIS_VERSION is in the key) would never be read
                if e.name.endswith(".json") and not e.name.startswith("."):
                    Path(e.path).unlink(missing_ok=True)
            files = sorted((e.stat().st_mtime, e.stat().st_size, e.path) for e in entries if e.name.endswith(SUFFIX))
        except OSError:
            return
        total = sum(f[1] for f in files)
        while files and (len(files) > self.disk_items or total > self.disk_bytes):
            _mtime, size, path = files.pop(0)
            Path(path).unlink(missing_ok=True)
            total -= size


# ---------------------------------------------------------------- background jobs


class Job:
    def __init__(self, kind: str, total: int):
        self.id = secrets.token_urlsafe(9)
        self.kind = kind
        self.total = total
        self.done = 0
        self.text = ""
        self.results: list[dict] = []
        self.finished = False
        self.error: str | None = None
        self.ended = 0.0

    def json(self) -> dict:
        return {"id": self.id, "kind": self.kind, "total": self.total, "done": self.done, "text": self.text,
                "results": self.results, "finished": self.finished, "error": self.error}


# ---------------------------------------------------------------- the module


class Activities:
    def __init__(self, data, elevation_get=None, elevation_pause=None):
        self.data = data
        cache_dir = data.config.config_dir / "cache" / "activities"
        self.cache = AnalysisCache(cache_dir)
        self.uploads = data.config.config_dir / "uploads"
        self.jobs: dict[str, Job] = {}
        self.subprocess_bytes = SUBPROCESS_BYTES
        self.elevation_get = elevation_get or elevation.http_get
        self.elevation_pause = elevation_pause or (lambda: time.sleep(1.1))
        data.idle_hooks.append(self.cache.drop_memory)
        self.recent = RECENT
        self.answers = Answers(2)
        self.analysis_wait = ANALYSIS_WAIT
        self._inflight: dict[str, asyncio.Future] = {}  # analyses being computed, by cache key

    def routes(self) -> list[Route]:
        return [
            Route("/api/activities", self.list, methods=["GET"]),
            Route("/api/activities/uploads", self.upload, methods=["POST"]),
            Route("/api/activities/imports", self.import_uploads, methods=["POST"]),
            Route("/api/activities/altitude", self.complete_altitude, methods=["POST"]),
            Route("/api/activities/jobs/{job}", self.job, methods=["GET"]),
            Route("/api/activities/{key}/analysis", self.analysis, methods=["GET"]),
            Route("/api/activities/{key}", self.get, methods=["GET"]),
            Route("/api/activities/{key}", self.update, methods=["PUT"]),
            Route("/api/activities/{key}", self.delete, methods=["DELETE"]),
        ]

    # ---- helpers (under the data lock) ----

    def _resolve(self, key: str) -> str:
        """The note path of `key`: a file name of activities/, or an id (the original before a conflict copy)."""
        if key.endswith(".md"):
            if "/" in key:
                raise bad_request("Activité invalide")
            return f"{ACTIVITIES_DIR}/{key}"
        found = sorted((n for n in self.data.store.scan_activities() if n.id == key and n.activity),
                       key=lambda n: (n.conflict, n.path))
        if not found:
            raise not_found("Activité introuvable (supprimée ou pas encore synchronisée ?)")
        return found[0].path

    def _load(self, key: str) -> tuple[fmt.Note, str]:
        relative = self._resolve(key)
        target = self.data.path(relative)
        found = read_versioned(target)
        if found is None:
            raise not_found("Activité introuvable (supprimée ou pas encore synchronisée ?)")
        raw, version = found
        note = fmt.parse(relative, raw.decode("utf-8", "replace"), target.stat().st_mtime_ns // 1_000_000)
        if note.activity is None:
            raise ApiError(422, "Cette note n'est pas une activité lisible (en-tête activity absent ou abîmé).")
        return note, version

    def _load_expected(self, key: str, expected: str) -> fmt.Note:
        try:
            note, version = self._load(key)
        except ApiError as error:
            if error.status == 404:
                raise ApiError(409, "Cette activité a été supprimée ailleurs (synchronisation, autre appareil).",
                               current=None) from None
            raise
        if version != expected:
            raise ApiError(409, "Cette activité a été modifiée ailleurs (synchronisation, autre appareil).",
                           current=detail(note, version, self._analysis_key(note)))
        return note

    def _analysis_key(self, note: fmt.Note) -> str | None:
        """Changes whenever what the analysis reads changes: the track file, the altitude file, the sport."""
        i = note.activity
        if not i or not i.file:
            return None
        parts = [str(ANALYSIS_VERSION), i.file, i.alt or "", i.sport.key]
        for f in (i.file, i.alt):
            if f:
                try:
                    st = self.data.path(f).stat()
                    parts.append(f"{st.st_size}:{st.st_mtime_ns}")
                except (OSError, ApiError):
                    parts.append("missing")
        return hashlib.sha256("|".join(parts).encode()).hexdigest()[:32]

    def _detail(self, key: str) -> dict:
        note, version = self._load(key)
        return detail(note, version, self._analysis_key(note))

    def _referenced_elsewhere(self, note: fmt.Note) -> set[str]:
        others = self.data.store.scan() + self.data.store.scan_activities()
        return {a for n in others if n.path != note.path for a in ([n.pdf] if n.pdf else []) + n.attachments
                + ([n.activity.file, n.activity.alt] if n.activity else []) if a}

    def _locked(self, fn, *args):
        """For code already in a worker thread (data.read is for the event loop)."""
        with self.data.lock:
            return fn(*args)

    def _poke(self, loop) -> None:
        loop.call_soon_threadsafe(self.data.watcher.poke)

    # ---- list and detail ----

    async def list(self, request: Request) -> JSONResponse:
        def run():
            scanned = self.data.store.scan_activities()
            text = self.data.store.read_raw(SETTINGS_PATH)

            def answer():
                notes = [n for n in scanned if n.activity]
                notes.sort(key=lambda n: -n.activity.start)
                settings = ActivitySettings.parse(text) if text else ActivitySettings()
                return {"activities": [summary(n) for n in notes],
                        "totals": [s.key for s in Sport if s in settings.totals],
                        "sports": [{"key": s.key, "label": s.label, "has_gps": s.has_gps} for s in Sport]}
            return self.answers.body(text, scanned, answer)
        return json_response(await self.data.read(run))

    async def get(self, request: Request) -> JSONResponse:
        return JSONResponse(await self.data.read(self._detail, request.path_params["key"]))

    async def analysis(self, request: Request) -> Response:
        key = request.path_params["key"]

        def locate():
            note, _version = self._load(key)
            if not note.activity.file:
                raise not_found("Cette activité n'a pas de tracé")
            return note, self._analysis_key(note)
        note, cache_key = await self.data.read(locate)
        task = self._inflight.get(cache_key)
        if task is None:
            # a task of its own: a request that gives up waiting (or a closed tab) does not stop the computation
            task = asyncio.ensure_future(run_in_threadpool(self.cache.get, cache_key, lambda: self._compute(note.activity)))
            self._inflight[cache_key] = task

            def finished(t, k=cache_key):
                if self._inflight.get(k) is t:
                    del self._inflight[k]
                if not t.cancelled():
                    t.exception()  # retrieved: a failure nobody waits for any more is not logged as unhandled
            task.add_done_callback(finished)
        try:
            body = await asyncio.wait_for(asyncio.shield(task), self.analysis_wait)
        except asyncio.TimeoutError:
            return JSONResponse({"pending": True}, status_code=202, headers={"Cache-Control": "no-store"})
        return gzipped_response(request, body)

    def _size(self, relative: str) -> int:
        try:
            return self.data.path(relative).stat().st_size
        except OSError:
            raise not_found("Le fichier du tracé est introuvable (pas encore synchronisé ?)") from None

    def _track(self, operation: str, size: int, *args: str) -> tuple[dict, bytes]:
        """A trackwork operation, in a child process for a long file; its failure as the page's error."""
        try:
            return trackwork.run(operation, *args, apart=size > self.subprocess_bytes, timeout=TRACK_TIMEOUT)
        except trackwork.TrackError as error:
            raise ApiError(404 if error.missing else 422, error.message) from None

    def _compute(self, info: act.ActivityInfo) -> bytes:
        return self._track("analyse", self._size(info.file), str(self.data.root), info.to_json())[1]

    def prepare_recent(self, stop=lambda: False) -> int:
        """Computes (or reads back from the disk cache) the analyses of the most recent activities with a track, and
        pins them in memory. Runs in the background (warm.py): a request for one of them meanwhile waits for it."""
        def recent():
            notes = [n for n in self.data.store.scan_activities() if n.activity and n.activity.file and not n.conflict]
            notes.sort(key=lambda n: -n.activity.start)
            return [(n.activity, self._analysis_key(n)) for n in notes[:self.recent]]
        wanted = self._locked(recent)
        self.cache.pin(key for _info, key in wanted)
        done = 0
        for info, key in wanted:
            if stop():
                break
            try:
                self.cache.get(key, lambda: self._compute(info))
                done += 1
            except ApiError:  # a missing or unreadable track: the page says so when it is opened
                pass
        return done

    # ---- edits ----

    async def update(self, request: Request) -> JSONResponse:
        """{title?, description?, sport?, distance? (metres; activities without a track only)}"""
        key = request.path_params["key"]
        expected = require_version(request.headers.get("if-match"))
        body = await json_body(request)
        unknown = set(body) - {"title", "description", "sport", "distance"}
        if unknown:
            raise bad_request(f"Champs inconnus : {', '.join(sorted(unknown))}")

        def check():
            note = self._load_expected(key, expected)
            info = note.activity
            sport = info.sport
            if "sport" in body:
                sport = next((s for s in Sport if s.key == body["sport"]), None)
                allowed = GPS_SPORTS if info.file else [Sport.PLAIN, info.sport]
                if sport not in allowed:
                    raise bad_request("Sport invalide pour cette activité")
            return info, sport
        info, sport = await self.data.read(check)
        resummarised = None
        if info.file and sport != info.sport:
            # another sport means another analysis: computed outside the lock, committed below if nothing changed
            def resummarise():
                return self._track("resummarise", self._size(info.file), str(self.data.root), info.to_json(),
                                   sport.key, info.alt or "")
            resummarised = await run_in_threadpool(resummarise)

        def run():
            note = self._load_expected(key, expected)
            if resummarised is not None:
                note = note.copy(activity=act.ActivityInfo.parse(resummarised[0]["info"]))
            title = str(body.get("title", note.title))
            description = str(body.get("description", note.body))
            try:
                saved = self.data.store.update_activity(note, title, description, sport)
            except OSError as error:
                raise ApiError(422, f"Le tracé n'a pas pu être relu : {error}") from None
            if "distance" in body:
                if saved.activity.file:
                    raise bad_request("La distance d'une activité avec tracé vient du fichier")
                try:
                    metres = float(body["distance"])
                except (TypeError, ValueError):
                    raise bad_request("Distance invalide") from None
                if not 0 <= metres <= 100_000_000:
                    raise bad_request("Distance invalide")
                info = act.ActivityInfo(**{**saved.activity.__dict__, "distance": metres})
                saved = self.data.store.save(saved.copy(activity=info, updated=now_ms()))
            if resummarised is not None:
                self.cache.put(self._analysis_key(saved), resummarised[1])
            return self._detail(saved.path.split("/", 1)[1])
        return JSONResponse(await self.data.run(run))

    async def delete(self, request: Request) -> JSONResponse:
        key = request.path_params["key"]
        expected = require_version(request.headers.get("if-match"))

        def run():
            note = self._load_expected(key, expected)
            keep = self._referenced_elsewhere(note)
            own = ([note.pdf] if note.pdf else []) + note.attachments + [note.activity.file, note.activity.alt]
            files = [f for f in dict.fromkeys(own) if owned(f) and f not in keep]
            token = self.data.undo.stash(self.data.root, [note.path] + files)
            for f in [note.path] + files:
                unlink_quiet(self.data.path(f))
            return {"undo": token}
        return JSONResponse(await self.data.run(run))

    # ---- import ----

    def _purge_uploads(self) -> None:
        if not self.uploads.is_dir():
            return
        limit = time.time() - UPLOAD_TTL
        for entry in os.scandir(self.uploads):
            try:
                if entry.stat().st_mtime < limit:
                    os.unlink(entry.path)
            except OSError:
                pass

    def _staged(self, token: str) -> Path:
        if not token.replace("-", "").replace("_", "").isalnum() or len(token) > 64:
            raise bad_request("Envoi inconnu")
        path = self.uploads / f"{token}.upload"
        if not path.is_file():
            raise ApiError(410, "Fichier envoyé expiré : envoyez-le à nouveau.")
        return path

    async def upload(self, request: Request) -> JSONResponse:
        """The file's bytes (?name=<original name>): kept aside in /config/uploads and read, never written to the
        folder yet. Answers what the import dialog shows: title, sport, an activity it would replace."""
        name = request.query_params.get("name", "").strip() or "activité"
        length = request.headers.get("content-length")
        if length and length.isdigit() and int(length) > MAX_ATTACHMENT:
            raise ApiError(413, f"Fichier trop volumineux ({MAX_ATTACHMENT // 1_000_000} Mo au plus)")
        await run_in_threadpool(self._purge_uploads)
        token = secrets.token_urlsafe(12)
        target = self.uploads / f"{token}.upload"
        # the page gzips the file (a GPX shrinks 5 to 8 times): far less to send over a home connection's upload
        chunks = request.stream()
        if request.headers.get("content-encoding", "").strip().lower() == "gzip":
            chunks = gunzip(chunks, MAX_ATTACHMENT)
        size = await write_stream(target, chunks, MAX_ATTACHMENT)

        def inspect():
            try:
                head, _body = self._track("inspect", size, str(target))
            except ApiError as error:
                unlink_quiet(target)
                raise ApiError(422, f"{name} : {error.message}") from None
            if head["empty"]:
                unlink_quiet(target)
                raise ApiError(422, f"{name} : aucune position dans ce fichier")
            existing = self._locked(self.data.store.find_activity, head["start"])
            sport = Sport.guess(head["fit_sport"])
            if existing is not None and sport == Sport.GPS and existing.activity:
                sport = existing.activity.sport
            title = existing.title if existing else default_title(sport, head["start"] or 0, head["name"])
            return {"token": token, "name": name, "title": title, "sport": sport.key, "start": head["start"],
                    "points": head["points"], "needs_altitude": head["needs_altitude"],
                    "existing": {"id": existing.id, "path": existing.path, "title": existing.title} if existing else None}
        return JSONResponse(await run_in_threadpool(inspect))

    async def import_uploads(self, request: Request) -> JSONResponse:
        """{"items": [{"token", "title", "sport", "altitude": bool}]}: imported one after the other in the
        background (an activity with the same start is replaced, keeping its name, description and photos)."""
        body = await json_body(request)
        items = body.get("items")
        if not isinstance(items, list) or not items:
            raise bad_request("Rien à importer")
        known = {s.key: s for s in GPS_SPORTS}
        plan = []
        for item in items:
            if not isinstance(item, dict) or item.get("sport") not in known:
                raise bad_request("Sport invalide")
            plan.append((self._staged(str(item.get("token", ""))), str(item.get("name") or "activité"),
                         str(item.get("title") or ""), known[item["sport"]], bool(item.get("altitude"))))
        job = self._start(Job("import", len(plan)), self._run_import, plan, asyncio.get_running_loop())
        return JSONResponse(job.json(), status_code=202)

    def _run_import(self, job: Job, plan, loop) -> None:
        for n, (source, name, title, sport, altitude) in enumerate(plan):
            prefix = f"{n + 1}/{len(plan)} · {name}" if len(plan) > 1 else name
            job.text = f"Import {prefix} · lecture et analyse du tracé…"
            try:
                head, analysis = self._track("prepare", source.stat().st_size, str(source), sport.key)
                needs = head["needs_altitude"]
                job.text = f"Import {prefix} · enregistrement…"
                with self.data.lock:
                    existing = self.data.store.find_activity(head["start"])
                    note = self._commit_import(source, head, title, sport, existing)
                    key = self._analysis_key(note)
                self.cache.put(key, analysis)
                del analysis
                self._poke(loop)
                result = {"name": name, "id": note.id, "path": note.path, "title": note.title,
                          "replaced": existing is not None}
                if altitude and needs and sport.has_gps:
                    try:
                        self._fill_altitude(note.path, lambda i, total: setattr(
                            job, "text", f"Altitude {prefix} · relief {i + 1}/{total}…"))
                        self._poke(loop)
                    except (elevation.ElevationError, ApiError, OSError) as error:
                        result["warning"] = f"importée sans altitude : {error}"
                job.results.append(result)
            except Exception as error:  # noqa: BLE001 — reported in the job's results
                job.results.append({"name": name, "error": str(error) or type(error).__name__})
            finally:
                unlink_quiet(source)
            job.done = n + 1

    def _commit_import(self, source: Path, head: dict, title: str, sport: Sport, replace: fmt.Note | None) -> fmt.Note:
        """The light end of the core's Store.import_activity, under the data lock, with the summary computed by
        trackwork.prepare: copies the file to attachments/ and writes the note (or replaces `replace`'s track,
        keeping its name, description and photos)."""
        store = self.data.store
        start = head["start"] or now_ms()
        if replace is not None:
            note_id = replace.id
            title = (title or "").strip() or replace.title
        else:
            title = (title or "").strip() or default_title(sport, start, head["name"])
            taken = {n.id for n in store.scan()} | {n.id for n in store.scan_activities()}
            note_id = fmt.generate_id()
            while note_id in taken:
                note_id = fmt.generate_id()
        relative = f"attachments/{fmt.slug(title)}-{note_id}.{head['ext']}"
        atomic.write_bytes(store.file_path(relative), source.read_bytes())
        info = act.ActivityInfo(**{**act.ActivityInfo.parse(head["info"]).__dict__, "file": relative})
        now = now_ms()
        if replace is None:
            return store.save(fmt.Note(path=f"{ACTIVITIES_DIR}/{fmt.file_name_for(title, note_id)}", id=note_id,
                                       title=title, created=now, updated=now, activity=info, attachments=[relative]))
        old = [replace.activity.file, replace.activity.alt] if replace.activity else []
        for path in old:
            if path and path != relative:
                try:
                    store.file_path(path).unlink()
                except OSError:
                    pass
        kept = [a for a in replace.attachments if a not in old]
        return store.save(replace.copy(title=title, activity=info, attachments=[relative] + kept, updated=now))

    # ---- altitude from the terrain model ----

    def _fill_altitude(self, relative: str, progress) -> fmt.Note:
        """store.fetch_altitude in steps, so the data lock is held for none of the slow ones: read the track (outside
        the lock, a child process for a long one); ask the service (a request a second); write the .alt file;
        analyse again with it; then put the new summary onto the note as it is by then (an edit made meanwhile is
        kept)."""
        note, _version = self._locked(self._load, relative.split("/", 1)[1])
        info = note.activity
        if not info.file or not info.sport.has_gps:
            raise elevation.ElevationError("Pas de tracé")
        if info.alt:
            raise elevation.ElevationError("Ce fichier a déjà une altitude")
        size = self._size(info.file)
        root = str(self.data.root)
        head, _body = self._track("altitude-samples", size, root, info.to_json())
        if not head["samples"]:
            raise elevation.ElevationError("Ce fichier a déjà une altitude")
        samples = [TrackPoint(time=t, lat=lat, lon=lon) for t, lat, lon in head["samples"]]
        alts = elevation.query(samples, get=self.elevation_get, pause=self.elevation_pause, progress=progress)
        alt_path = info.file.rsplit(".", 1)[0] + ".alt"
        with self.data.lock:
            fresh, _version = self._load(relative.split("/", 1)[1])
            if fresh.activity.file != info.file or fresh.activity.alt:
                raise ApiError(409, "L'activité a changé pendant la recherche d'altitude")
            atomic.write_text(self.data.path(alt_path), elevation.encode(alts))
            sport = fresh.activity.sport
        while True:
            head, analysis = self._track("resummarise", size, root, info.to_json(), sport.key, alt_path)
            with self.data.lock:
                fresh, _version = self._load(relative.split("/", 1)[1])
                if fresh.activity.file != info.file:
                    raise ApiError(409, "L'activité a changé pendant la recherche d'altitude")
                if fresh.activity.sport != sport:  # changed meanwhile: its summary is for the new sport
                    sport = fresh.activity.sport
                    continue
                attachments = fresh.attachments if alt_path in fresh.attachments else fresh.attachments + [alt_path]
                saved = self.data.store.save(fresh.copy(activity=act.ActivityInfo.parse(head["info"]),
                                                        attachments=attachments, updated=now_ms()))
                key = self._analysis_key(saved)
            self.cache.put(key, analysis)
            return saved

    async def complete_altitude(self, request: Request) -> JSONResponse:
        """{"ids": [...]} or {"all": true}: activities whose file has no altitude get it from the terrain model
        (positions sent to opentopodata.org). The others are skipped; the first service failure stops the run."""
        body = await json_body(request)
        ids = body.get("ids")
        if not body.get("all") and not (isinstance(ids, list) and ids):
            raise bad_request("Aucune activité choisie")

        def choose():
            notes = [n for n in self.data.store.scan_activities() if n.activity and not n.conflict]
            if not body.get("all"):
                wanted = {str(i) for i in ids}
                notes = [n for n in notes if n.id in wanted]
            notes.sort(key=lambda n: -n.activity.start)
            return [n.path for n in notes]
        paths = await self.data.read(choose)
        if not paths:
            raise not_found("Activité introuvable")
        job = self._start(Job("altitude", len(paths)), self._run_altitude, paths, asyncio.get_running_loop())
        return JSONResponse(job.json(), status_code=202)

    def _run_altitude(self, job: Job, paths: list[str], loop) -> None:
        skipped = 0
        for n, relative in enumerate(paths):
            job.text = f"Activité {n + 1}/{len(paths)}…"
            try:
                saved = self._fill_altitude(relative, lambda i, total, n=n: setattr(
                    job, "text", f"Activité {n + 1}/{len(paths)} · relief {i + 1}/{total}…"))
                job.results.append({"id": saved.id, "path": saved.path, "title": saved.title})
                self._poke(loop)
            except elevation.ElevationError as error:
                if "déjà une altitude" in str(error) or str(error) == "Pas de tracé":
                    skipped += 1
                else:
                    job.error = str(error)
                    job.done = n + 1
                    break
            except (ApiError, OSError) as error:
                job.error = getattr(error, "message", None) or str(error)
                job.done = n + 1
                break
            job.done = n + 1
        job.text = f"{len(job.results)} complétée(s), {skipped} ignorée(s)"

    # ---- jobs ----

    def _start(self, job: Job, fn, *args) -> Job:
        now = time.monotonic()
        for old in [j for j in self.jobs.values() if j.finished and now - j.ended > JOB_TTL]:
            del self.jobs[old.id]
        self.jobs[job.id] = job

        def run():
            try:
                fn(job, *args)
            except Exception as error:  # noqa: BLE001 — the job reports it
                _log(f"job {job.kind} failed: {error!r}")
                job.error = str(error) or type(error).__name__
            finally:
                job.finished = True
                job.ended = time.monotonic()
                _malloc_trim()
        threading.Thread(target=run, name=f"activities-{job.kind}", daemon=True).start()
        return job

    async def job(self, request: Request) -> JSONResponse:
        job = self.jobs.get(request.path_params["job"])
        if job is None:
            raise not_found("Tâche inconnue (terminée depuis longtemps ?)")
        return JSONResponse(job.json())
