"""Buildings drawn in 3D on the map: IGN BD TOPO V3 `batiment` (Géoplateforme WFS, Licence Ouverte Etalab 2.0), one
z15 tile ("cell", 850 m wide in the Alps) per request, slimmed down to footprints and heights. Pure Python: the hosts do the
network calls (Windows asynchronously, the web server and tools/mapdev.py with `fetch_cell`). The Android copy of
these rules is app/src/main/java/app/memento/map/Buildings.kt; both produce the same JSON.

The answer served to the page under `<base>buildings/15/{x}/{y}`:

    {"v": 1, "z": 15, "x": X, "y": Y, "o": [W, S], "b": [[h, ring, ring...], ...]}

`o` is the cell's south-west corner in microdegrees; each ring is a flat list of integers, microdegrees: the first
point relative to `o`, every next one relative to the previous (rings are not closed: the page closes them). The first
ring is the outline, the next ones holes. `h` is the height in metres. A building belongs to the cell holding the centre
of its outline's box, so cells never share one.
"""
from __future__ import annotations

import json
import math
import threading
import time
from pathlib import Path

Z = 15
FORMAT = 1
PAGE = 2000          # features per WFS request: a dense town cell takes 1 to 3 of them
MAX_PAGES = 10       # past 20 000 buildings the cell is cut short (Annecy's old town: 1 350)
MAX_BUILDINGS = 20000
DEFAULT_HEIGHT = 6.0
MAX_HEIGHT = 400.0
SIMPLIFY_UDEG = 1.5  # a vertex closer than this to the line through its neighbours goes (~0.15 m)

WFS = ("https://data.geopf.fr/wfs/ows?SERVICE=WFS&VERSION=2.0.0&REQUEST=GetFeature&TYPENAMES=BDTOPO_V3:batiment"
       "&OUTPUTFORMAT=application/json&SRSNAME=EPSG:4326&PROPERTYNAME=geometrie,hauteur,altitude_minimale_sol,"
       "altitude_maximale_toit,nombre_d_etages&COUNT={count}&STARTINDEX={start}"
       # the bare "EPSG:4326" suffix makes the server read the box in another axis order: 0 features
       "&BBOX={s},{w},{n},{e},urn:ogc:def:crs:EPSG::4326")

Box = tuple[float, float, float, float]  # w, s, e, n


class WfsError(Exception):
    """The WFS answered something that is not a feature collection (an exception report, a cut-off body)."""


# ---- cell math ----

def _lat(y: float, n: int) -> float:
    return math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * y / n))))


def cell_box(x: int, y: int) -> Box:
    n = 2 ** Z
    return x / n * 360 - 180, _lat(y + 1, n), (x + 1) / n * 360 - 180, _lat(y, n)


def cell_of(lon: float, lat: float) -> tuple[int, int]:
    n = 2 ** Z
    x = math.floor((lon + 180) / 360 * n)
    y = math.floor((1 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2 * n)
    return min(max(x, 0), n - 1), min(max(y, 0), n - 1)


def cells(box: Box) -> list[tuple[int, int]]:
    """Cells meeting the box, west to east then north to south; an edge on a cell border does not pull in the next."""
    x0, y0 = cell_of(box[0], box[3])
    x1, y1 = cell_of(box[2] - 1e-9, box[1] + 1e-9)
    return [(x, y) for x in range(x0, x1 + 1) for y in range(y0, y1 + 1)]


def valid_cell(z: int, x: int, y: int) -> bool:
    return z == Z and 0 <= x < 2 ** Z and 0 <= y < 2 ** Z


# ---- WFS ----

def wfs_url(x: int, y: int, start: int = 0) -> str:
    w, s, e, n = cell_box(x, y)
    return WFS.format(count=PAGE, start=start, w=f"{w:.7f}", s=f"{s:.7f}", e=f"{e:.7f}", n=f"{n:.7f}")


def parse_page(text: str) -> list[dict]:
    """The features of one WFS answer; WfsError when it is not a GeoJSON feature collection."""
    try:
        doc = json.loads(text)
    except ValueError as error:
        raise WfsError(f"réponse illisible ({error})") from None
    features = doc.get("features") if isinstance(doc, dict) else None
    if not isinstance(features, list):
        raise WfsError("réponse WFS inattendue")
    return features


def is_last_page(features: list) -> bool:
    return len(features) < PAGE


# IGN's WFS answers 429 to bursts (the map asks for up to 16 cells at once): two requests at a time, and a refusal
# waited out (1, 2, 4 s) before it fails the cell
_WFS_GATE = threading.Semaphore(2)
_RETRY_S = (1, 2, 4)


def _wfs_get(url: str, get):
    attempt = 0
    while True:
        with _WFS_GATE:
            reply = get(url)
        if reply[0] not in (429, 503) or attempt == len(_RETRY_S):
            return reply
        time.sleep(_RETRY_S[attempt])
        attempt += 1


def fetch_cell(x: int, y: int, get) -> bytes:
    """The slim JSON of a cell, `get(url) -> (status, content_type, bytes)` doing the requests (synchronous hosts).
    Raises WfsError on an HTTP error or a malformed answer; whatever `get` raises when offline goes through."""
    features: list = []
    for page in range(MAX_PAGES):
        status, _kind, data = _wfs_get(wfs_url(x, y, page * PAGE), get)
        if status != 200:
            raise WfsError(f"HTTP {status}")
        got = parse_page(data.decode("utf-8", "replace"))
        features += got
        if is_last_page(got):
            break
    return encode(x, y, slim(features, x, y))


# ---- slimming ----

def _num(value) -> float | None:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return None
    return float(value) if math.isfinite(value) else None


def height(props: dict) -> float:
    """`hauteur`, else roof top minus lowest ground, else floors × 3 + 1, else a default; at most MAX_HEIGHT."""
    h = _num(props.get("hauteur"))
    if h is None or h <= 0:
        top, ground = _num(props.get("altitude_maximale_toit")), _num(props.get("altitude_minimale_sol"))
        floors = _num(props.get("nombre_d_etages"))
        if top is not None and ground is not None and top - ground > 0:
            h = max(top - ground, 3.0)
        elif floors is not None and floors > 0:
            h = floors * 3 + 1
        else:
            h = DEFAULT_HEIGHT
    return math.floor(min(h, MAX_HEIGHT) * 10 + 0.5) / 10  # half up, as Kotlin's Math.round


def _udeg(v: float) -> int:
    return math.floor(v * 1e6 + 0.5)


def _ring(coords) -> list[tuple[int, int]] | None:
    """A ring in integer microdegrees, closing point, repeats and near-collinear vertices removed; None if degenerate."""
    if not isinstance(coords, list):
        return None
    pts: list[tuple[int, int]] = []
    for c in coords:
        if not isinstance(c, list) or len(c) < 2 or _num(c[0]) is None or _num(c[1]) is None:
            return None
        p = (_udeg(c[0]), _udeg(c[1]))
        if not pts or pts[-1] != p:
            pts.append(p)
    if len(pts) > 1 and pts[0] == pts[-1]:
        pts.pop()
    if len(pts) > 3:  # one pass: each vertex against its kept predecessor and its original successor
        out: list[tuple[int, int]] = []
        for i, p in enumerate(pts):
            a, b = out[-1] if out else pts[-1], pts[(i + 1) % len(pts)]
            length = math.hypot(b[0] - a[0], b[1] - a[1])
            cross = abs((p[0] - a[0]) * (b[1] - a[1]) - (p[1] - a[1]) * (b[0] - a[0]))
            if length > 0 and cross / length < SIMPLIFY_UDEG and len(out) + len(pts) - i > 3:
                continue
            out.append(p)
        pts = out
    return pts if len(pts) >= 3 else None


def _polygons(geom) -> list:
    if not isinstance(geom, dict):
        return []
    coords = geom.get("coordinates")
    if geom.get("type") == "Polygon":
        return [coords]
    if geom.get("type") == "MultiPolygon" and isinstance(coords, list):
        return coords
    return []


def slim(features: list, x: int, y: int) -> list[tuple[float, list[list[tuple[int, int]]]]]:
    """(height, rings) of each building whose outline's box centre lies in the cell; a multipolygon gives one entry
    per part. Duplicates (the same feature on two WFS pages) are dropped."""
    out, seen = [], set()
    w, s, e, n = cell_box(x, y)
    for f in features:
        if not isinstance(f, dict):
            continue
        fid = f.get("id")
        if fid is not None:
            if fid in seen:
                continue
            seen.add(fid)
        h = height(f.get("properties") or {})
        for poly in _polygons(f.get("geometry")):
            if not isinstance(poly, list) or not poly:
                continue
            outer = _ring(poly[0])
            if outer is None:
                continue
            xs, ys = [p[0] for p in outer], [p[1] for p in outer]
            cx, cy = (min(xs) + max(xs)) / 2e6, (min(ys) + max(ys)) / 2e6
            if not (w <= cx < e and s <= cy < n):
                continue
            rings = [outer] + [r for r in (_ring(c) for c in poly[1:]) if r is not None]
            out.append((h, rings))
            if len(out) >= MAX_BUILDINGS:
                return out
    return out


def encode(x: int, y: int, buildings: list) -> bytes:
    w, s, _e, _n = cell_box(x, y)
    ox, oy = _udeg(w), _udeg(s)
    items = []
    for h, rings in buildings:
        flat_rings = []
        for ring in rings:
            flat, px, py = [], ox, oy
            for qx, qy in ring:
                flat += [qx - px, qy - py]
                px, py = qx, qy
            flat_rings.append(flat)
        items.append([int(h) if h == int(h) else h, *flat_rings])
    doc = {"v": FORMAT, "z": Z, "x": x, "y": y, "o": [ox, oy], "b": items}
    return json.dumps(doc, separators=(",", ":")).encode()


def decode(data: bytes) -> list[tuple[float, list[list[tuple[float, float]]]]]:
    """(height, rings in degrees) of an encoded cell (tests, tools)."""
    doc = json.loads(data)
    ox, oy = doc["o"]
    out = []
    for item in doc["b"]:
        rings = []
        for flat in item[1:]:
            px, py, ring = ox, oy, []
            for i in range(0, len(flat), 2):
                px, py = px + flat[i], py + flat[i + 1]
                ring.append((px / 1e6, py / 1e6))
            rings.append(ring)
        out.append((item[0], rings))
    return out


# ---- disk cache ----

def cache_path(root: Path, x: int, y: int) -> Path:
    """Under the tile cache, so the least-recently-used purge counts and trims it with the tiles."""
    return root / "buildings" / str(Z) / str(x) / f"{y}.json"
