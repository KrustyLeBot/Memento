"""The map page's files: ETag and 304, the image's .gz siblings, and the tile cache's hourly LRU touch."""
from __future__ import annotations

import gzip
import os
import time

from starlette.applications import Starlette
from starlette.testclient import TestClient

from memento_web.errors import ApiError
from memento_web.modules.maphost import MapHost, TileStore

SCRIPT = b"console.log('maplibre');\n" * 200


def _host(tmp_path, siblings: bool) -> TestClient:
    page = tmp_path / "web" / "map"
    page.mkdir(parents=True)
    (page / "index.html").write_bytes(b"<html>maplibre</html>")
    (page / "maplibre-gl.js").write_bytes(SCRIPT)
    if siblings:  # as the Dockerfile's gzip -9 -k: same mtime as the file
        gz = page / "maplibre-gl.js.gz"
        gz.write_bytes(gzip.compress(SCRIPT, 9))
        st = (page / "maplibre-gl.js").stat()
        os.utime(gz, ns=(st.st_atime_ns, st.st_mtime_ns))
    host = MapHost(tmp_path / "tiles", page, fetch=lambda url: (404, "", b""))
    return TestClient(Starlette(routes=host.routes(), exception_handlers={ApiError: lambda _r, e: e.response()}))


def test_the_gz_sibling_goes_to_a_browser_that_accepts_gzip_only(tmp_path):
    client = _host(tmp_path, siblings=True)
    zipped = client.get("/map/maplibre-gl.js", headers={"Accept-Encoding": "gzip, deflate"})
    assert zipped.headers["content-encoding"] == "gzip" and zipped.headers["vary"] == "Accept-Encoding"
    assert zipped.content == SCRIPT  # decoded by the client
    assert "javascript" in zipped.headers["content-type"]  # the type of the file, not of its .gz
    plain = client.get("/map/maplibre-gl.js", headers={"Accept-Encoding": "identity"})
    assert "content-encoding" not in plain.headers and plain.headers["vary"] == "Accept-Encoding"
    assert plain.content == SCRIPT and plain.headers["etag"] != zipped.headers["etag"]
    assert client.get("/map/maplibre-gl.js.gz").status_code == 404


def test_without_a_sibling_the_file_goes_plain(tmp_path):
    client = _host(tmp_path, siblings=False)
    response = client.get("/map/maplibre-gl.js", headers={"Accept-Encoding": "gzip"})
    assert response.content == SCRIPT and "content-encoding" not in response.headers and "vary" not in response.headers
    assert response.headers["cache-control"] == "private, max-age=3600"


def test_a_sibling_older_than_its_file_is_stale_and_never_served(tmp_path):
    client = _host(tmp_path, siblings=True)
    gz = tmp_path / "web" / "map" / "maplibre-gl.js.gz"
    os.utime(gz, (time.time() - 3600, time.time() - 3600))
    response = client.get("/map/maplibre-gl.js", headers={"Accept-Encoding": "gzip"})
    assert "content-encoding" not in response.headers and response.content == SCRIPT


def test_an_unchanged_file_answers_304_to_its_etag(tmp_path):
    client = _host(tmp_path, siblings=True)
    for encoding in ("gzip", "identity"):
        first = client.get("/map/maplibre-gl.js", headers={"Accept-Encoding": encoding})
        tag = first.headers["etag"]
        again = client.get("/map/maplibre-gl.js", headers={"Accept-Encoding": encoding, "If-None-Match": tag})
        assert again.status_code == 304 and again.content == b"" and again.headers["etag"] == tag
        weak = client.get("/map/maplibre-gl.js", headers={"Accept-Encoding": encoding, "If-None-Match": f'"x", W/{tag}'})
        assert weak.status_code == 304
    (tmp_path / "web" / "map" / "index.html").write_bytes(b"<html>v1</html>")
    tag = client.get("/map/index.html").headers["etag"]
    (tmp_path / "web" / "map" / "index.html").write_bytes(b"<html>v2, longer</html>")
    changed = client.get("/map/index.html", headers={"If-None-Match": tag})
    assert changed.status_code == 200 and changed.content == b"<html>v2, longer</html>"


def test_a_cache_hit_touches_the_tile_once_an_hour_only(tmp_path):
    from memento_web.core import mapcache
    store = TileStore(tmp_path, lambda url: (200, "image/png", b"png"))
    tile = mapcache.TileRequest("dem", 3, 1, 1)
    assert store.get(tile) == ("image/png", b"png")
    path = mapcache.tile_path(tmp_path, tile)
    recent = time.time() - 600
    os.utime(path, (recent, recent))
    assert store.get(tile) == ("image/png", b"png")
    assert path.stat().st_mtime == recent  # read only: no write on every hit
    old = time.time() - 2 * 3600
    os.utime(path, (old, old))
    store.get(tile)
    assert path.stat().st_mtime > time.time() - 60  # older than an hour: moved to the front of the LRU


def test_a_cached_buildings_cell_is_touched_once_an_hour_only(tmp_path):
    from memento_web.core import mapcache
    from memento_win.core import buildings
    store = TileStore(tmp_path, lambda url: (500, "", b""))
    path = buildings.cache_path(tmp_path, 1, 2)
    path.parent.mkdir(parents=True)
    path.write_bytes(b"[]")
    recent = time.time() - 600
    os.utime(path, (recent, recent))
    assert store.buildings(mapcache.BuildingsRequest(1, 2)) == b"[]"
    assert path.stat().st_mtime == recent
    old = time.time() - 2 * 3600
    os.utime(path, (old, old))
    assert store.buildings(mapcache.BuildingsRequest(1, 2)) == b"[]"
    assert path.stat().st_mtime > time.time() - 60
