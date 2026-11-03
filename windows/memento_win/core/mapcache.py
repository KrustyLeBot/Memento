"""What the map host serves under `<base>`: request parsing, the disk tile cache and its purge, the upstream
URLs and when the IGN aerial photo is replaced by the OpenStreetMap tile. Pure Python, no Qt, so it is testable."""
from __future__ import annotations

import os
import shutil
import uuid
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import parse_qs

CACHE_LIMIT = 300 * 1024 * 1024
PURGE_TARGET = int(CACHE_LIMIT * 0.8)  # purge down to this so the next purge is not one tile away
MAX_ZOOM = {"ortho": 19, "dem": 15}
# "orthow" / "orthoa": an aerial photo Tuileries re-encoded as WebP / AVIF, each in its own folder so a file's type
# follows its folder
MIME = {"ortho": "image/jpeg", "orthow": "image/webp", "orthoa": "image/avif", "dem": "image/png", "osmfb2": "image/png"}
EXT = {"ortho": "jpg", "orthow": "webp", "orthoa": "avif", "dem": "png", "osmfb2": "png"}
OLD_KINDS = ("plan", "osm", "osmfb")  # deleted at startup: a former base layer; OSM stand-ins of IGN errors (build 73)
# and of white sea tiles (build 80)
BUILDINGS_Z = 15  # core/buildings.py's cells
TRAILS_MAX_Z = 15  # Tuileries' trails pyramid; the page overzooms past it
TRAILS_MIME = "application/x-protobuf"
DEM_VERSION_FILE = "dem-version.txt"  # next to the tile folders: the relief version the cached dem tiles come from
USER_AGENT = "Memento (https://github.com/KrustyLeBot/Memento)"

IGN_ORTHO = ("https://data.geopf.fr/wmts?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0&LAYER=ORTHOIMAGERY.ORTHOPHOTOS"
             "&STYLE=normal&FORMAT=image/jpeg&TILEMATRIXSET=PM&TILEMATRIX={z}&TILEROW={y}&TILECOL={x}")
OSM = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
TERRARIUM = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png"


@dataclass(frozen=True)
class TileRequest:
    kind: str  # "ortho" (IGN aerial photo, JPEG), "dem" (Terrarium PNG), or "osmfb2" (the photo's OpenStreetMap stand-in, PNG)
    z: int
    x: int
    y: int

    @property
    def key(self) -> str:
        return f"{self.kind}/{self.z}/{self.x}/{self.y}"

    @property
    def mime(self) -> str:
        return MIME[self.kind]

    def fallback(self) -> "TileRequest":
        """Where the photo's OpenStreetMap stand-in is cached: its own folder, so the file type follows the folder."""
        return TileRequest("osmfb2", self.z, self.x, self.y)

    def variants(self) -> tuple["TileRequest", ...]:
        """Every cache entry that answers this request: a photo is a JPEG, a WebP (Tuileries) or the PNG stand-in."""
        if self.kind != "ortho":
            return (self,)
        return (self, TileRequest("orthow", self.z, self.x, self.y), TileRequest("orthoa", self.z, self.x, self.y),
                self.fallback())

    def typed(self, content_type: str) -> "TileRequest | None":
        """Where an answer of this type is cached, None for a type this request never takes."""
        mime = (content_type or "").split(";")[0].strip().lower()
        for v in self.variants():
            if MIME[v.kind] == mime:
                return v
        return None


@dataclass(frozen=True)
class PeaksRequest:
    w: float
    s: float
    e: float
    n: float


@dataclass(frozen=True)
class BuildingsRequest:
    x: int
    y: int  # of the z15 cell (core/buildings.py)


@dataclass(frozen=True)
class TrailsRequest:
    z: int
    x: int
    y: int  # a hiking-trails vector tile (Tuileries only)


@dataclass(frozen=True)
class FileRequest:
    path: str  # relative to web/, e.g. "map/index.html"


def parse_request(path: str, query: str = "") -> TileRequest | PeaksRequest | BuildingsRequest | TrailsRequest | FileRequest | None:
    """`path` is the URL path under the base (`/t/ortho/12/2120/1460`, `/peaks`, `/buildings/15/16941/11670`,
    `/map/map.js`); None = not served."""
    parts = [p for p in path.split("/") if p]
    if len(parts) == 5 and parts[0] == "t" and parts[1] in MAX_ZOOM:
        try:
            z, x, y = int(parts[2]), int(parts[3]), int(parts[4].removesuffix("." + EXT[parts[1]]))
        except ValueError:
            return None
        if not 0 <= z <= MAX_ZOOM[parts[1]] or not (0 <= x < 2 ** z and 0 <= y < 2 ** z):
            return None
        return TileRequest(parts[1], z, x, y)
    if len(parts) == 4 and parts[0] == "buildings" and all(p.isdigit() and len(p) <= 7 for p in parts[1:]):
        z, x, y = (int(p) for p in parts[1:])
        return BuildingsRequest(x, y) if z == BUILDINGS_Z and x < 2 ** z and y < 2 ** z else None
    if len(parts) == 4 and parts[0] == "trails" and all(p.isdigit() and len(p) <= 7 for p in parts[1:]):
        z, x, y = (int(p) for p in parts[1:])
        return TrailsRequest(z, x, y) if z <= TRAILS_MAX_Z and x < 2 ** z and y < 2 ** z else None
    if parts == ["peaks"]:
        q = parse_qs(query)
        try:
            w, s, e, n = (float(q[k][0]) for k in ("w", "s", "e", "n"))
        except (KeyError, ValueError):
            return None
        if not (-180 <= w < e <= 180 and -85 <= s < n <= 85):
            return None
        return PeaksRequest(w, s, e, n)
    if len(parts) >= 2 and parts[0] == "map" and all(p not in ("..", ".") and "\\" not in p for p in parts):
        return FileRequest("/".join(parts))
    return None


def tile_path(root: Path, tile: TileRequest) -> Path:
    return root / tile.kind / str(tile.z) / str(tile.x) / f"{tile.y}.{EXT[tile.kind]}"


def trails_path(root: Path, req: TrailsRequest) -> Path:
    return root / "trails" / str(req.z) / str(req.x) / f"{req.y}.pbf"


def drop_stale_dem(root: Path, version: int) -> bool:
    """Deletes every cached relief tile (single and metatile entries share `dem/`) when they come from another relief
    version than `version`, then records it. Blocking: run it off the UI thread. True when the version changed. A cache
    without the marker predates it and holds version 1 (AWS Terrarium, directly or through Tuileries)."""
    marker = root / DEM_VERSION_FILE
    try:
        recorded = marker.read_text(encoding="ascii").strip()
    except FileNotFoundError:
        recorded = "1"
    if recorded == str(version):
        return False
    dem = root / "dem"
    if dem.exists():
        doomed = root / f".dem-stale-{uuid.uuid4().hex}"
        try:
            os.replace(dem, doomed)  # at once: tiles of the new relief written meanwhile land in a fresh dem/
        except OSError:
            doomed = dem  # a tile open right now (Windows): deleted in place
        shutil.rmtree(doomed, ignore_errors=True)
        if dem.exists() and doomed == dem:
            return True  # not all gone: the marker is left as it was, so the next check tries again
    root.mkdir(parents=True, exist_ok=True)
    tmp = root / f".{DEM_VERSION_FILE}.{uuid.uuid4().hex}.tmp"
    tmp.write_text(str(version), encoding="ascii")
    os.replace(tmp, marker)
    return True


def upstream_url(tile: TileRequest) -> str:
    return (IGN_ORTHO if tile.kind == "ortho" else TERRARIUM).format(z=tile.z, x=tile.x, y=tile.y)


def fallback_url(tile: TileRequest) -> str:
    return OSM.format(z=tile.z, x=tile.x, y=tile.y)


def ortho_needs_fallback(status: int, content_type: str, data: bytes) -> bool:
    """IGN answers 404 "No data found" outside its photo coverage (abroad, from z13 or so) and 400 past the edge of its
    tile matrix: only those mean "no photo here". A 403, 408, 429, 5xx or a dropped connection is a refusal or a hiccup
    inside France, and an OSM tile cached for it would stand in for the photo for good. The white tiles off the coast
    are kept: the map page shows the parent tile through their white pixels."""
    if status in (400, 404, 204):
        return True
    return status == 200 and (not content_type.startswith("image/") or not data)


def purge(root: Path, limit: int = CACHE_LIMIT, target: int = PURGE_TARGET) -> tuple[int, int]:
    """Deletes the least recently used tiles and building cells (oldest mtime: a read refreshes it once over an
    hour old) once the cache is over `limit`, down to `target`. Returns (bytes left, files deleted). A file
    vanishing meanwhile is not an error. The summits' answers are not counted: a few hundred bytes each."""
    entries = list(_cached_files(os.fspath(root)))
    total = sum(e[1] for e in entries)
    deleted = 0
    if total <= limit:
        return total, 0
    for _mtime, size, path in sorted(entries):
        if total <= target:
            break
        try:
            os.remove(path)
        except FileNotFoundError:
            pass
        except PermissionError:
            continue  # being read right now (Windows refuses to delete an open file): kept, the purge goes on
        total -= size
        deleted += 1
    return total, deleted


def _cached_files(top: str):
    """(mtime, size, path) of the tiles and building cells under `top`, the files and paths os.walk would list. The
    dates and sizes come from the directory listings: on Windows no file is opened (19 times faster than os.stat)."""
    stack = [top]
    while stack:
        directory = stack.pop()
        try:
            listing = os.scandir(directory)
        except OSError:  # vanished meanwhile, or unreadable: skipped, as os.walk does
            continue
        cells = os.path.basename(os.path.dirname(os.path.dirname(directory))) == "buildings"
        with listing:
            for entry in listing:
                try:
                    is_dir = entry.is_dir()
                except OSError:
                    is_dir = False
                if is_dir:
                    if not entry.is_symlink():  # a linked folder is not walked into, as os.walk's default
                        stack.append(entry.path)
                    continue
                name = entry.name
                if not (name.endswith((".png", ".jpg", ".webp", ".avif", ".pbf")) or cells and name.endswith(".json")):
                    continue
                try:
                    st = entry.stat()  # follows a link like os.stat did; a plain file's date is the listing's
                except OSError:  # vanished, or locked (Windows): left for the next purge
                    continue
                yield st.st_mtime, st.st_size, entry.path


class Backoff:
    """After a connection failure, uncached tiles fail at once for a while instead of each waiting for its own
    timeout: offline, the page gets its blank tiles immediately."""

    def __init__(self, seconds: float = 20.0):
        self.seconds = seconds
        self.until = 0.0

    def failed(self, now: float) -> None:
        self.until = now + self.seconds

    def succeeded(self) -> None:
        self.until = 0.0

    def blocked(self, now: float) -> bool:
        return now < self.until
