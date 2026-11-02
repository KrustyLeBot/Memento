"""Summits shown on the map: IGN BD TOPO (WFS) for names and importance, the IGN altimetry service for heights,
then at most 12 of them, spread out. Pure Python: the host does the network calls."""
from __future__ import annotations

import json
import math
from pathlib import Path
from urllib.parse import quote

MAX_SHOWN = 12
MAX_CANDIDATES = 60
MAX_SIDE_DEG = 2.0  # a larger box would be a whole region: no summit list makes sense at that scale

WFS = ("https://data.geopf.fr/wfs/ows?SERVICE=WFS&VERSION=2.0.0&REQUEST=GetFeature&TYPENAMES=BDTOPO_V3:detail_orographique"
       "&OUTPUTFORMAT=application/json&COUNT=300&SRSNAME=EPSG:4326&SORTBY=importance%20ASC&CQL_FILTER=")
ALTI = "https://data.geopf.fr/altimetrie/1.0/calcul/alti/rest/elevation.json?resource=ign_rge_alti_wld&zonly=true&lon={lon}&lat={lat}"

Box = tuple[float, float, float, float]  # w, s, e, n


def cache_box(w: float, s: float, e: float, n: float) -> Box:
    """The box actually fetched and cached: rounded outwards to 0.01°, so a slightly moved square reuses it."""
    return (math.floor(w * 100) / 100, math.floor(s * 100) / 100, math.ceil(e * 100) / 100, math.ceil(n * 100) / 100)


def too_large(box: Box) -> bool:
    return box[2] - box[0] > MAX_SIDE_DEG or box[3] - box[1] > MAX_SIDE_DEG


def wfs_url(box: Box) -> str:
    w, s, e, n = box
    return WFS + quote(f"nature='Sommet' AND BBOX(geometrie,{w},{s},{e},{n},'EPSG:4326')", safe="")


def parse_wfs(text: str, box: Box) -> list[dict]:
    """Named summits inside the box, most important first, cut to MAX_CANDIDATES (their heights come next)."""
    out = []
    for f in json.loads(text).get("features") or []:
        props, geom = f.get("properties") or {}, f.get("geometry") or {}
        name = (props.get("toponyme") or "").strip()
        coords = geom.get("coordinates") or []
        if not name or len(coords) < 2:
            continue
        lon, lat = float(coords[0]), float(coords[1])
        if not _inside(lon, lat, box) and _inside(lat, lon, box):
            lon, lat = lat, lon  # some WFS servers answer EPSG:4326 as lat,lon
        if not _inside(lon, lat, box):
            continue
        out.append({"name": name, "ele": None, "lon": lon, "lat": lat, "importance": _importance(props.get("importance"))})
    out.sort(key=lambda p: p["importance"])
    return out[:MAX_CANDIDATES]


def alti_url(candidates: list[dict]) -> str:
    return ALTI.format(lon="|".join(f"{p['lon']:.6f}" for p in candidates), lat="|".join(f"{p['lat']:.6f}" for p in candidates))


def with_elevations(candidates: list[dict], text: str) -> list[dict]:
    values = json.loads(text).get("elevations") or []
    out = []
    for p, v in zip(candidates, values):
        ele = v.get("z") if isinstance(v, dict) else v
        # the service answers -99999 where it has no data
        out.append({**p, "ele": round(float(ele)) if isinstance(ele, (int, float)) and ele > -1000 else None})
    return out + [dict(p) for p in candidates[len(out):]]


def select(candidates: list[dict], box: Box) -> list[dict]:
    """Most important (then highest) first, each at least side/7 from those already kept, at most 12."""
    w, s, e, n = box
    inside = [p for p in candidates if _inside(p["lon"], p["lat"], box)]
    inside.sort(key=lambda p: (p["importance"], -(p["ele"] if p["ele"] is not None else -1e9)))
    lat_c = (s + n) / 2
    kx, ky = 111320 * math.cos(math.radians(lat_c)), 110540
    spacing = max((e - w) * kx, (n - s) * ky) / 7
    kept: list[dict] = []
    for p in inside:
        if all(math.hypot((p["lon"] - q["lon"]) * kx, (p["lat"] - q["lat"]) * ky) >= spacing for q in kept):
            kept.append(p)
            if len(kept) == MAX_SHOWN:
                break
    return kept


def cache_file(directory: Path, box: Box) -> Path:
    return directory / ("_".join(f"{v:.2f}" for v in box) + ".json")


def covering(directory: Path, box: Box) -> list[dict] | None:
    """Candidates of a cached box that contains `box` (exact one first), or None."""
    exact = cache_file(directory, box)
    files = [exact] if exact.is_file() else []
    if not files and directory.is_dir():
        for f in directory.glob("*.json"):
            try:
                cw, cs, ce, cn = (float(v) for v in f.stem.split("_"))
            except ValueError:
                continue
            if cw <= box[0] and cs <= box[1] and ce >= box[2] and cn >= box[3]:
                files.append(f)
                break
    for f in files:
        try:
            candidates = json.loads(f.read_text(encoding="utf-8"))["candidates"]
            if isinstance(candidates, list) and all(isinstance(p, dict) for p in candidates):
                return candidates
        except (OSError, ValueError, KeyError, TypeError):
            continue  # a damaged entry is just a miss: it is refetched and rewritten
    return None


def _inside(lon: float, lat: float, box: Box) -> bool:
    return box[0] <= lon <= box[2] and box[1] <= lat <= box[3]


def _importance(value) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return 9  # "NC" (not classified) and missing: after every ranked summit
