"""Development server for the map page (kept out of web/: everything there ships in the APK): serves web/map and answers the host endpoints (tiles, peaks) the way the
apps do, with the Windows host's own logic (windows/scriptorium_win/core). Open
http://127.0.0.1:8765/map/index.html?mode=3d and drive window.SMap from the console.

    python tools/mapdev.py [port]

Tiles are cached under $TMPDIR/scriptorium-map-dev. Standard library only."""
from __future__ import annotations

import json
import shutil
import sys
import tempfile
import urllib.error
import urllib.request
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "windows"))
from scriptorium_win.core import mapcache, peaks  # noqa: E402

WEB = ROOT / "web"
CACHE = Path(tempfile.gettempdir()) / "scriptorium-map-dev"


def get(url: str) -> tuple[int, str, bytes]:
    request = urllib.request.Request(url, headers={"User-Agent": mapcache.USER_AGENT})
    try:
        with urllib.request.urlopen(request, timeout=20) as r:
            return r.status, r.headers.get("Content-Type", ""), r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.headers.get("Content-Type", ""), e.read()


def tile(t: mapcache.TileRequest) -> tuple[str, bytes] | None:
    for cached in (t, t.fallback()) if t.kind == "ortho" else (t,):
        path = mapcache.tile_path(CACHE, cached)
        if path.is_file():
            return cached.mime, path.read_bytes()
    status, kind, data = get(mapcache.upstream_url(t))
    stored = t
    if t.kind == "ortho" and mapcache.ortho_needs_fallback(status, kind, data):
        status, kind, data = get(mapcache.fallback_url(t))
        stored = t.fallback()
    if status != 200 or not kind.startswith("image/"):
        return None
    path = mapcache.tile_path(CACHE, stored)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)
    return stored.mime, data


def peak_list(r: mapcache.PeaksRequest) -> list[dict]:
    box = peaks.cache_box(r.w, r.s, r.e, r.n)
    if peaks.too_large(box):
        return []
    directory = CACHE / "peaks"
    candidates = peaks.covering(directory, box)
    if candidates is None:
        status, _k, data = get(peaks.wfs_url(box))
        if status != 200:
            return []
        candidates = peaks.parse_wfs(data.decode("utf-8"), box)
        if candidates:
            status, _k, data = get(peaks.alti_url(candidates))
            if status == 200:
                candidates = peaks.with_elevations(candidates, data.decode("utf-8"))
        directory.mkdir(parents=True, exist_ok=True)
        peaks.cache_file(directory, box).write_text(json.dumps({"box": box, "candidates": candidates}), encoding="utf-8")
    return peaks.select(candidates, (r.w, r.s, r.e, r.n))


class Handler(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(WEB), **kwargs)

    def do_GET(self) -> None:
        url = urlsplit(self.path)
        req = mapcache.parse_request(url.path, url.query)
        if isinstance(req, mapcache.TileRequest):
            found = tile(req)
            return self._send(200, *found) if found else self.send_error(404)
        if isinstance(req, mapcache.PeaksRequest):
            return self._send(200, "application/json", json.dumps(peak_list(req)).encode())
        if isinstance(req, mapcache.FileRequest) or url.path.startswith("/map/"):
            return super().do_GET()
        self.send_error(404)

    def _send(self, code: int, kind: str, data: bytes) -> None:
        self.send_response(code)
        self.send_header("Content-Type", kind)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, fmt: str, *args) -> None:
        if "404" in (fmt % args):
            sys.stderr.write(fmt % args + "\n")


if __name__ == "__main__":
    for old in mapcache.OLD_KINDS:
        shutil.rmtree(CACHE / old, ignore_errors=True)
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8765
    print(f"http://127.0.0.1:{port}/map/index.html?mode=3d")
    ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
