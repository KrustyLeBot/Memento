"""The optional Tuileries tile server (the owner's own, self-hosted): when it is configured, every tile, buildings
cell and summits list the map page asks for comes from it instead of IGN / OpenStreetMap / AWS, with HTTP Basic auth
and the optional Cloudflare Access service-token headers added by the host (the page never sees them). Pure Python
(URLs, headers, answer rules) plus a blocking `fetch_info` for the settings' "Tester la connexion"; shared by the
Windows host and the web server."""
from __future__ import annotations

import base64
import gzip
import json
import math
import urllib.error
import urllib.request
from dataclasses import dataclass
from urllib.parse import urlsplit

from . import buildings, mapcache

PHOTO_MAX_ZOOM_DEFAULT = 17
PHOTO_MAX_ZOOM_RANGE = (14, 19)  # 19: IGN's top level, also the page's former fixed maxzoom
INFO_TIMEOUT = 10


class TuileriesError(Exception):
    """The connection test failed; the message is shown to the user as is (French)."""


@dataclass(frozen=True)
class Tuileries:
    url: str
    user: str = ""
    password: str = ""
    cf_id: str = ""
    cf_secret: str = ""

    @property
    def configured(self) -> bool:
        return bool(normalize_url(self.url))

    @property
    def base(self) -> str:
        return normalize_url(self.url)

    def headers(self) -> dict[str, str]:
        """Only ever sent to `base`: the credentials never reach IGN, OpenStreetMap or AWS."""
        out = {"User-Agent": mapcache.USER_AGENT}
        if self.user or self.password:
            token = base64.b64encode(f"{self.user}:{self.password}".encode()).decode("ascii")
            out["Authorization"] = f"Basic {token}"
        if self.cf_id:
            out["CF-Access-Client-Id"] = self.cf_id
        if self.cf_secret:
            out["CF-Access-Client-Secret"] = self.cf_secret
        return out

    def tile_url(self, tile: mapcache.TileRequest) -> str:
        return f"{self.base}t/{tile.kind}/{tile.z}/{tile.x}/{tile.y}"

    def buildings_url(self, x: int, y: int) -> str:
        return f"{self.base}buildings/{mapcache.BUILDINGS_Z}/{x}/{y}"

    def trails_url(self, req: mapcache.TrailsRequest) -> str:
        return f"{self.base}trails/{req.z}/{req.x}/{req.y}"

    def peaks_url(self, w: float, s: float, e: float, n: float) -> str:
        return f"{self.base}peaks?w={w:.5f}&s={s:.5f}&e={e:.5f}&n={n:.5f}"

    @property
    def info_url(self) -> str:
        return f"{self.base}api/info"


def normalize_url(url: str) -> str:
    """`https://host/path/` with its trailing slash, or "" when it is not an http(s) URL with a host."""
    url = (url or "").strip()
    if not url:
        return ""
    parts = urlsplit(url)
    if parts.scheme not in ("http", "https") or not parts.hostname or parts.query or parts.fragment:
        return ""
    return url if url.endswith("/") else url + "/"


def clamp_photo_max_zoom(value) -> int:
    try:
        z = int(value)
    except (TypeError, ValueError):
        return PHOTO_MAX_ZOOM_DEFAULT
    return min(max(z, PHOTO_MAX_ZOOM_RANGE[0]), PHOTO_MAX_ZOOM_RANGE[1])


# ---- answers ----

def tile_answer(tile: mapcache.TileRequest, status: int, content_type: str, data: bytes) -> mapcache.TileRequest | None:
    """Where an answer of Tuileries is cached (its type decides: a WebP photo, a JPEG one, the PNG OpenStreetMap
    stand-in), or None when it is not a tile: a 404 (no source has one), an error, an unexpected type."""
    if status != 200 or not data:
        return None
    return tile.typed(content_type)


def trails_answer(status: int, data: bytes) -> bytes | None:
    """A trails tile as the page takes it: the (un-gzipped) vector tile, b"" for "no trail here" (204, and 404 while
    Tuileries has not built its trails: never cached, asked again later), None for an error."""
    if status in (204, 404):
        return b""
    if status != 200:
        return None
    if data[:2] == b"\x1f\x8b":  # stored gzipped by Tuileries, sent with Content-Encoding the hosts do not ask for
        try:
            data = gzip.decompress(data)
        except (OSError, EOFError):
            return None
    return data


def valid_cell(data: bytes, x: int, y: int) -> bool:
    """A buildings cell as core/buildings.py encodes it, for that cell (anything else is never cached)."""
    try:
        doc = json.loads(data)
    except ValueError:
        return False
    # v2: Tuileries' cells with their roofs' colours ("c"), which the page uses instead of sampling the photo
    return isinstance(doc, dict) and doc.get("v") in (1, 2) and doc.get("x") == x and doc.get("y") == y and isinstance(doc.get("b"), list)


def parse_peaks(data: bytes) -> list[dict]:
    """Tuileries' `/peaks` answer, the list core/peaks.py `select` makes; ValueError when it is anything else."""
    items = json.loads(data)
    if not isinstance(items, list):
        raise ValueError("summits: not a list")
    out = []
    for p in items:
        if not isinstance(p, dict) or not isinstance(p.get("name"), str):
            raise ValueError("summits: not a summit")
        lon, lat = float(p["lon"]), float(p["lat"])
        if not (math.isfinite(lon) and math.isfinite(lat)):
            raise ValueError("summits: bad position")
        ele = p.get("ele")
        out.append({"name": p["name"], "ele": ele if isinstance(ele, (int, float)) else None, "lon": lon, "lat": lat,
                    "importance": int(p.get("importance", 9))})
    return out


@dataclass(frozen=True)
class Info:
    name: str
    version: str
    max_zoom: int | None
    layers: tuple[str, ...]
    attribution: str = ""  # the server's own credit (the photo abroad), shown on the map

    def summary(self) -> str:
        zoom = f", zoom max {self.max_zoom}" if self.max_zoom is not None else ""
        return f"{self.name} {self.version}{zoom}".strip()


def parse_info(data: bytes) -> Info:
    try:
        doc = json.loads(data)
    except ValueError:
        raise TuileriesError("Réponse inattendue : ce n'est pas un serveur Tuileries ?") from None
    if not isinstance(doc, dict) or not isinstance(doc.get("version"), str):
        raise TuileriesError("Réponse inattendue : ce n'est pas un serveur Tuileries ?")
    zoom = doc.get("max_zoom")
    layers = doc.get("layers") if isinstance(doc.get("layers"), list) else []
    return Info(str(doc.get("name") or "Tuileries"), doc["version"],
                zoom if isinstance(zoom, int) and not isinstance(zoom, bool) else None,
                tuple(str(v) for v in layers), _attribution(doc))


def _attribution(doc: dict) -> str:
    """The photo's credits (`attributions.ortho`, e.g. Sentinel-2 by EOX abroad) and the trails', one line; the page
    already names IGN, OpenStreetMap and Terrarium. A plain `attribution` string is taken as is."""
    if isinstance(doc.get("attribution"), str):
        return doc["attribution"].strip()[:300]
    credits = doc.get("attributions") if isinstance(doc.get("attributions"), dict) else {}
    out: list[str] = []
    for layer in ("ortho", "trails"):
        for text in credits.get(layer) or []:
            if isinstance(text, str) and text.strip() and text.strip() not in out and "IGN" not in text:
                out.append(text.strip())
    return " · ".join(out)[:300]


# ---- v1.1 metatiles: `GET /meta/{z}/{x}/{y}?layers=...`, a 4x4 photo block with the relief, buildings and trails
# of the same area in one answer (contract "v1.1 metatiles") ----

META_SIDE = 4
META_LAYERS = ("ortho", "dem", "buildings", "trails")
META_FORMATS = {1: "image/webp", 2: "image/png", 3: "image/jpeg", 4: "application/json", 5: "application/vnd.mapbox-vector-tile",
                6: "image/avif"}
META_OK, META_NONE, META_ERROR, META_PENDING = 0, 1, 2, 3
_HEAD = 18
_ENTRY = 17


@dataclass(frozen=True)
class MetaEntry:
    layer: str
    z: int
    x: int
    y: int
    status: int  # META_OK, META_NONE (no tile there), META_ERROR / META_PENDING (ask that tile again, alone, later)
    mime: str  # "" when unknown (an unknown format is not used: the tile is asked for alone)
    data: bytes  # un-gzipped


def meta_block(z: int, x: int, y: int) -> tuple[int, int, int]:
    """The 4x4 block (z, x0, y0) holding a photo tile."""
    return z, x - x % META_SIDE, y - y % META_SIDE


def meta_url(t: Tuileries, z: int, x: int, y: int, layers: tuple[str, ...] = META_LAYERS) -> str:
    return f"{t.base}meta/{z}/{x}/{y}?layers={','.join(layers)}"


def parse_meta(data: bytes) -> list[MetaEntry]:
    """ValueError on anything but a well-formed metatile."""
    if len(data) < _HEAD or data[:4] != b"TUIM" or data[4] != 1:
        raise ValueError("metatile: bad header")
    count = int.from_bytes(data[16:18], "big")
    out, at = [], _HEAD
    for _ in range(count):
        if at + _ENTRY > len(data):
            raise ValueError("metatile: cut short")
        head = data[at:at + _ENTRY]
        layer, z, status, fmt, enc = head[0], head[1], head[10], head[11], head[12]
        x, y, length = (int.from_bytes(head[i:i + 4], "big") for i in (2, 6, 13))
        body = data[at + _ENTRY:at + _ENTRY + length]
        if len(body) != length or layer >= len(META_LAYERS):
            raise ValueError("metatile: bad entry")
        at += _ENTRY + length
        if status == META_OK and enc == 1:
            try:
                body = gzip.decompress(body)
            except (OSError, EOFError):
                status, body = META_ERROR, b""
        elif status == META_OK and enc != 0:
            status, body = META_ERROR, b""  # an encoding this client does not know: asked for alone
        out.append(MetaEntry(META_LAYERS[layer], z, x, y, status, META_FORMATS.get(fmt, ""), body))
    return out


def meta_cache_target(root, e: MetaEntry):
    """(path, bytes) where a received entry goes in the host's cache (the very files its single requests fill), or
    None when it is not to be cached (no tile, pending, an unknown type, an unreadable cell)."""
    if e.status != META_OK or not e.data:
        return None
    if e.layer in ("ortho", "dem"):
        stored = mapcache.TileRequest(e.layer, e.z, e.x, e.y).typed(e.mime)
        return (mapcache.tile_path(root, stored), e.data) if stored is not None else None
    if e.layer == "buildings":
        return (buildings.cache_path(root, e.x, e.y), e.data) if e.z == mapcache.BUILDINGS_Z and valid_cell(e.data, e.x, e.y) else None
    if e.layer == "trails" and e.mime == META_FORMATS[5]:
        return mapcache.trails_path(root, mapcache.TrailsRequest(e.z, e.x, e.y)), e.data
    return None


def info_answer(status: int, data: bytes) -> Info:
    """`/api/info` fetched by the caller's own HTTP code (the web server's)."""
    if status != 200:
        raise TuileriesError(status_message(status))
    return parse_info(data)


def status_message(status: int) -> str:
    """What a refusal of Tuileries (or of Cloudflare in front of it) means to the user."""
    if status == 401:
        return "Identifiant ou mot de passe refusé"
    if status == 403 or 300 <= status < 400:  # Cloudflare Access refuses, or sends to its login page
        return "Accès refusé (Cloudflare Access : vérifier l'identifiant client et le secret)"
    if status == 429:
        return "Trop d'essais refusés : réessayer plus tard"
    if status in (502, 503, 504):
        return f"Serveur injoignable derrière le tunnel (HTTP {status})"
    return f"Erreur HTTP {status}"


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    """Cloudflare Access sends a request without a valid service token to its login page: said as a refusal, and the
    credentials are never carried to another address."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise urllib.error.HTTPError(newurl, 403, "redirect refused", headers, fp)


def fetch_info(t: Tuileries, timeout: float = INFO_TIMEOUT) -> Info:
    """`GET /api/info` (blocking: run it off the UI thread). TuileriesError with a French message on any failure."""
    if not t.configured:
        raise TuileriesError("Adresse invalide : https://… attendu")
    request = urllib.request.Request(t.info_url, headers=t.headers())
    try:
        with urllib.request.build_opener(_NoRedirect).open(request, timeout=timeout) as reply:
            return parse_info(reply.read(1_000_000))
    except urllib.error.HTTPError as error:
        raise TuileriesError(status_message(error.code)) from None
    except (urllib.error.URLError, TimeoutError, OSError) as error:
        reason = getattr(error, "reason", error)
        raise TuileriesError(f"Serveur injoignable ({reason})") from None
