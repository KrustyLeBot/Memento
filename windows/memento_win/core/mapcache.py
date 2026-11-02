"""What the map host serves under `<base>`: request parsing, the disk tile cache and its purge, the upstream
URLs and when the IGN aerial photo is replaced by the OpenStreetMap tile. Pure Python, no Qt, so it is testable."""
from __future__ import annotations

import math
import os
import struct
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import parse_qs

CACHE_LIMIT = 300 * 1024 * 1024
PURGE_TARGET = int(CACHE_LIMIT * 0.8)  # purge down to this so the next purge is not one tile away
MAX_ZOOM = {"ortho": 19, "dem": 15}
MIME = {"ortho": "image/jpeg", "dem": "image/png", "osmfb": "image/png"}
EXT = {"ortho": "jpg", "dem": "png", "osmfb": "png"}
OLD_KINDS = ("plan", "osm")  # deleted at startup: a former base layer; OSM stand-ins cached for IGN errors (build 73)
BUILDINGS_Z = 15  # core/buildings.py's cells
USER_AGENT = "Memento (https://github.com/KrustyLeBot/Memento)"

IGN_ORTHO = ("https://data.geopf.fr/wmts?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0&LAYER=ORTHOIMAGERY.ORTHOPHOTOS"
             "&STYLE=normal&FORMAT=image/jpeg&TILEMATRIXSET=PM&TILEMATRIX={z}&TILEROW={y}&TILECOL={x}")
OSM = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
TERRARIUM = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png"


@dataclass(frozen=True)
class TileRequest:
    kind: str  # "ortho" (IGN aerial photo, JPEG), "dem" (Terrarium PNG), or "osmfb" (the photo's OpenStreetMap stand-in, PNG)
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
        return TileRequest("osmfb", self.z, self.x, self.y)


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
    """IGN answers 404 "No data found" outside its photo coverage (abroad, at sea, from z13 or so), 400 past the edge
    of its tile matrix, and a uniform white JPEG at the edge of the coverage. Only those mean "no photo here": a 403,
    408, 429, 5xx or a dropped connection is a refusal or a hiccup inside France, and an OSM tile cached for it would
    stand in for the photo for good. A uniform non-white tile (the sea's dark blue at low zoom) is a real answer."""
    if status in (400, 404, 204):
        return True
    if status != 200:
        return False
    if not content_type.startswith("image/") or not data:
        return True
    colour = jpeg_uniform_colour(data)
    return colour is not None and colour[0] >= 248 and all(abs(c - 128) <= 4 for c in colour[1:])


def jpeg_uniform_colour(data: bytes) -> tuple[int, ...] | None:
    """The (Y, Cb, Cr) colour, 0..255, of a baseline JPEG whose every block is that one flat colour; None for
    anything else (a photo, a progressive or restart-marked file, a malformed one). Only small files are decoded:
    a tile with anything on it is several kilobytes. A flat image is exactly one where every block has no AC
    coefficient and, after the first, a DC difference of zero, so the Huffman codes are walked, never transformed."""
    if len(data) > 4096 or not data.startswith(b"\xff\xd8"):
        return None
    try:
        return _jpeg_flat(data)
    except (IndexError, KeyError, ValueError, struct.error):
        return None


def _jpeg_flat(data: bytes) -> tuple[int, ...] | None:
    quant, huff, comps, width, height, pos = {}, {}, [], 0, 0, 2
    while True:
        if data[pos] != 0xFF:
            return None
        marker = data[pos + 1]
        length = struct.unpack(">H", data[pos + 2:pos + 4])[0]
        body = data[pos + 4:pos + 2 + length]
        pos += 2 + length
        if marker == 0xDB:
            i = 0
            while i < len(body):
                precision, table = body[i] >> 4, body[i] & 15
                quant[table] = struct.unpack(">H", body[i + 1:i + 3])[0] if precision else body[i + 1]  # the DC step
                i += 1 + 64 * (precision + 1)
        elif marker in (0xC0, 0xC1):
            height, width = struct.unpack(">HH", body[1:5])
            comps = [(body[6 + 3 * k], body[7 + 3 * k] >> 4, body[7 + 3 * k] & 15, body[8 + 3 * k]) for k in range(body[5])]
        elif marker == 0xC4:
            i = 0
            while i < len(body):
                table, counts = body[i], body[i + 1:i + 17]
                symbols, codes, code, k = body[i + 17:i + 17 + sum(counts)], {}, 0, 0
                for bits in range(1, 17):
                    for _ in range(counts[bits - 1]):
                        codes[(bits, code)] = symbols[k]
                        code, k = code + 1, k + 1
                    code <<= 1
                huff[table] = codes
                i += 17 + sum(counts)
        elif marker in (0xDD, 0xC2) or 0xC3 <= marker <= 0xCF and marker != 0xC4:
            return None  # restart intervals, progressive or other codings: not what a flat IGN tile is
        elif marker == 0xDA:
            break
    scan = {body[1 + 2 * k]: (body[2 + 2 * k] >> 4, body[2 + 2 * k] & 15) for k in range(body[0])}
    if not comps or len(scan) != len(comps):
        return None
    bits = _Bits(data, pos)
    hmax, vmax = max(c[1] for c in comps), max(c[2] for c in comps)
    mcus = -(-width // (8 * hmax)) * -(-height // (8 * vmax)) if len(comps) > 1 else -(-width // 8) * -(-height // 8)
    dc = {}
    for m in range(mcus):
        for cid, h, v, _q in comps:
            dc_table, ac_table = scan[cid]
            for _ in range(h * v if len(comps) > 1 else 1):
                size = bits.decode(huff[dc_table])
                diff = bits.extend(size)
                if cid in dc and diff:
                    return None
                dc.setdefault(cid, diff)
                if bits.decode(huff[0x10 | ac_table]) != 0:  # anything but an immediate end of block
                    return None
    # rounded half up, as Kotlin's Math.round in the Android copy (Python's round() goes half to even)
    return tuple(max(0, min(255, math.floor(dc[cid] * quant[q] / 8 + 128.5))) for cid, _h, _v, q in comps)


class _Bits:
    """The entropy-coded bits, with the 0xFF00 byte stuffing undone."""

    def __init__(self, data: bytes, pos: int):
        self.data, self.pos, self.acc, self.n = data, pos, 0, 0

    def bit(self) -> int:
        if self.n == 0:
            byte = self.data[self.pos]
            if byte == 0xFF:
                if self.data[self.pos + 1] != 0:
                    raise ValueError("marker inside the scan")
                self.pos += 1
            self.pos += 1
            self.acc, self.n = byte, 8
        self.n -= 1
        return (self.acc >> self.n) & 1

    def decode(self, codes: dict) -> int:
        code = 0
        for length in range(1, 17):
            code = code << 1 | self.bit()
            if (length, code) in codes:
                return codes[(length, code)]
        raise ValueError("bad Huffman code")

    def extend(self, size: int) -> int:
        value = 0
        for _ in range(size):
            value = value << 1 | self.bit()
        return value - (1 << size) + 1 if size and value < 1 << (size - 1) else value


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
