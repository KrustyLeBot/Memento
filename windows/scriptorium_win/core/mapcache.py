"""What the map host serves under `<base>`: request parsing, the disk tile cache and its purge, the upstream
URLs and when the Plan IGN tile is replaced by the OpenStreetMap one. Pure Python, no Qt, so it is testable."""
from __future__ import annotations

import os
import struct
import zlib
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import parse_qs

CACHE_LIMIT = 300 * 1024 * 1024
PURGE_TARGET = int(CACHE_LIMIT * 0.8)  # purge down to this so the next purge is not one tile away
MAX_ZOOM = {"plan": 18, "dem": 15}
USER_AGENT = "Scriptorium (https://github.com/KrustyLeBot/Griffonote)"

IGN_PLAN = ("https://data.geopf.fr/wmts?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0&LAYER=GEOGRAPHICALGRIDSYSTEMS.PLANIGNV2"
            "&STYLE=normal&FORMAT=image/png&TILEMATRIXSET=PM&TILEMATRIX={z}&TILEROW={y}&TILECOL={x}")
OSM = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
TERRARIUM = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png"


@dataclass(frozen=True)
class TileRequest:
    kind: str  # "plan" or "dem"
    z: int
    x: int
    y: int

    @property
    def key(self) -> str:
        return f"{self.kind}/{self.z}/{self.x}/{self.y}"


@dataclass(frozen=True)
class PeaksRequest:
    w: float
    s: float
    e: float
    n: float


@dataclass(frozen=True)
class FileRequest:
    path: str  # relative to web/, e.g. "map/index.html"


def parse_request(path: str, query: str = "") -> TileRequest | PeaksRequest | FileRequest | None:
    """`path` is the URL path under the base (`/t/plan/12/2120/1460`, `/peaks`, `/map/map.js`); None = not served."""
    parts = [p for p in path.split("/") if p]
    if len(parts) == 5 and parts[0] == "t" and parts[1] in MAX_ZOOM:
        try:
            z, x, y = int(parts[2]), int(parts[3]), int(parts[4].removesuffix(".png"))
        except ValueError:
            return None
        if not 0 <= z <= MAX_ZOOM[parts[1]] or not (0 <= x < 2 ** z and 0 <= y < 2 ** z):
            return None
        return TileRequest(parts[1], z, x, y)
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
    return root / tile.kind / str(tile.z) / str(tile.x) / f"{tile.y}.png"


def upstream_url(tile: TileRequest) -> str:
    return (IGN_PLAN if tile.kind == "plan" else TERRARIUM).format(z=tile.z, x=tile.x, y=tile.y)


def fallback_url(tile: TileRequest) -> str:
    return OSM.format(z=tile.z, x=tile.x, y=tile.y)


def plan_needs_fallback(status: int, content_type: str, data: bytes) -> bool:
    """IGN answers 404 "No data found" outside its coverage (abroad, at sea); a blank image is the same thing."""
    if status != 200 or not content_type.startswith("image/") or not data:
        return True
    return png_is_blank(data)


def png_is_blank(data: bytes) -> bool:
    """True for a PNG whose pixels are all zero (fully transparent black). Only small files are decoded: a tile
    with anything drawn on it compresses to several kilobytes."""
    if len(data) > 4096 or not data.startswith(b"\x89PNG\r\n\x1a\n"):
        return False
    pos, idat, width, bpp = 8, b"", 0, 0
    try:
        while pos + 8 <= len(data):
            length, kind = struct.unpack(">I4s", data[pos:pos + 8])
            body = data[pos + 8:pos + 8 + length]
            if kind == b"IHDR":
                width, _h, depth, color = struct.unpack(">IIBB", body[:10])
                channels = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}.get(color, 0)
                if depth != 8 or channels == 0 or color == 3:
                    return False  # palette and odd depths: never blank IGN tiles, not worth decoding
                bpp = channels
            elif kind == b"IDAT":
                idat += body
            pos += 12 + length
        raw = zlib.decompress(idat)
    except (struct.error, zlib.error):
        return False
    stride = width * bpp + 1
    if width == 0 or len(raw) % stride:
        return False
    # an all-zero image stays all zero under every PNG filter, and vice versa: no need to unfilter
    return not any(raw[i + 1:i + stride].strip(b"\x00") for i in range(0, len(raw), stride))


def purge(root: Path, limit: int = CACHE_LIMIT, target: int = PURGE_TARGET) -> tuple[int, int]:
    """Deletes the least recently used tiles (oldest mtime: reads touch the file) once the cache is over
    `limit`, down to `target`. Returns (bytes left, files deleted). A file vanishing meanwhile is not an error."""
    entries = []
    for dirpath, _dirs, names in os.walk(root):
        for name in names:
            if not name.endswith(".png"):
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
