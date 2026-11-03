"""What the map host serves under `<base>`: request parsing, the disk tile cache and its purge, the upstream
URLs and when the IGN aerial photo is replaced by the OpenStreetMap tile. Pure Python, no Qt, so it is testable."""
from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import parse_qs

CACHE_LIMIT = 300 * 1024 * 1024
PURGE_TARGET = int(CACHE_LIMIT * 0.8)  # purge down to this so the next purge is not one tile away
MAX_ZOOM = {"ortho": 19, "dem": 15}
MIME = {"ortho": "image/jpeg", "dem": "image/png", "osmfb2": "image/png"}
EXT = {"ortho": "jpg", "dem": "png", "osmfb2": "png"}
OLD_KINDS = ("plan", "osm", "osmfb")  # deleted at startup: a former base layer; OSM stand-ins of IGN errors (build 73)
# and of white sea tiles (build 80)
BUILDINGS_Z = 15  # core/buildings.py's cells
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
class FileRequest:
    path: str  # relative to web/, e.g. "map/index.html"


def parse_request(path: str, query: str = "") -> TileRequest | PeaksRequest | BuildingsRequest | FileRequest | None:
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
    """Deletes the least recently used tiles and building cells (oldest mtime: reads touch the file) once the cache
    is over `limit`, down to `target`. Returns (bytes left, files deleted). A file vanishing meanwhile is not an
    error. The summits' answers are not counted: a few hundred bytes each."""
    entries = []
    for dirpath, _dirs, names in os.walk(root):
        cells = os.path.basename(os.path.dirname(os.path.dirname(dirpath))) == "buildings"
        for name in names:
            if not (name.endswith((".png", ".jpg")) or cells and name.endswith(".json")):
                continue
            path = os.path.join(dirpath, name)
            try:
                st = os.stat(path)
            except OSError:  # vanished, or locked (Windows): left for the next purge
                continue
            entries.append((st.st_mtime, st.st_size, path))
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
