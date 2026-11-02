"""The activity map's host logic (synthetic data only) and a smoke test of the page served by the scheme handler."""
import json
import math
import os
import struct
import time
import zlib

import pytest

from scriptorium_win.core import mapcache, mapdata, peaks


def png(width: int, height: int, pixel: bytes) -> bytes:
    def chunk(kind: bytes, body: bytes) -> bytes:
        return struct.pack(">I", len(body)) + kind + body + struct.pack(">I", zlib.crc32(kind + body))
    raw = b"".join(b"\x00" + pixel * width for _ in range(height))
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 6 if len(pixel) == 4 else 2, 0, 0, 0)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b"")


# ---- request parsing ----

def test_tile_paths_are_parsed_and_checked():
    assert mapcache.parse_request("/t/ortho/12/2120/1460") == mapcache.TileRequest("ortho", 12, 2120, 1460)
    assert mapcache.parse_request("/t/ortho/12/2120/1460.jpg") == mapcache.TileRequest("ortho", 12, 2120, 1460)
    assert mapcache.parse_request("/t/dem/13/4253/2915.png") == mapcache.TileRequest("dem", 13, 4253, 2915)
    assert mapcache.parse_request("/t/ortho/19/1/1") == mapcache.TileRequest("ortho", 19, 1, 1)
    assert mapcache.parse_request("/t/ortho/20/1/1") is None      # past the orthophotos' zoom
    assert mapcache.parse_request("/t/dem/2/4/0") is None         # x outside the 4 columns of zoom 2
    assert mapcache.parse_request("/t/plan/2/1/1") is None        # the former base layer
    assert mapcache.parse_request("/t/osm/2/1/1") is None         # the fallback is a cache folder, not a URL
    assert mapcache.parse_request("/t/ortho/a/1/1") is None


def test_peaks_and_files_are_parsed():
    assert mapcache.parse_request("/peaks", "w=6.8&s=45.9&e=6.9&n=46.0") == mapcache.PeaksRequest(6.8, 45.9, 6.9, 46.0)
    assert mapcache.parse_request("/peaks", "w=6.9&s=45.9&e=6.8&n=46.0") is None
    assert mapcache.parse_request("/peaks", "w=6.8") is None
    assert mapcache.parse_request("/map/index.html") == mapcache.FileRequest("map/index.html")
    assert mapcache.parse_request("/map/../secret.txt") is None
    assert mapcache.parse_request("/other.html") is None


def test_cache_path_and_urls(tmp_path):
    t = mapcache.TileRequest("ortho", 14, 8501, 5850)
    assert mapcache.tile_path(tmp_path, t) == tmp_path / "ortho" / "14" / "8501" / "5850.jpg"
    assert mapcache.tile_path(tmp_path, t.fallback()) == tmp_path / "osm" / "14" / "8501" / "5850.png"
    assert (t.mime, t.fallback().mime, mapcache.TileRequest("dem", 1, 0, 0).mime) == ("image/jpeg", "image/png", "image/png")
    assert "LAYER=ORTHOIMAGERY.ORTHOPHOTOS" in mapcache.upstream_url(t)
    assert "FORMAT=image/jpeg" in mapcache.upstream_url(t)
    assert "TILEMATRIX=14&TILEROW=5850&TILECOL=8501" in mapcache.upstream_url(t)
    assert mapcache.fallback_url(t) == "https://tile.openstreetmap.org/14/8501/5850.png"
    assert mapcache.upstream_url(mapcache.TileRequest("dem", 3, 4, 2)).endswith("/terrarium/3/4/2.png")


# ---- IGN -> OpenStreetMap ----

TESTDATA = __import__("pathlib").Path(__file__).resolve().parents[2] / "testdata" / "map"


def test_ortho_falls_back_to_osm_outside_ign_coverage():
    # real IGN answers (testdata/map): a photo, the white tile of the coverage's edge, the sea at low zoom, abroad
    photo, white, sea = ((TESTDATA / f"ign-ortho-{n}.jpg").read_bytes() for n in ("alps", "white", "sea"))
    assert not mapcache.ortho_needs_fallback(200, "image/jpeg", photo)
    assert mapcache.ortho_needs_fallback(200, "image/jpeg", white)
    assert not mapcache.ortho_needs_fallback(200, "image/jpeg", sea)  # flat, but a real answer: the sea
    assert mapcache.ortho_needs_fallback(404, "text/xml; charset=UTF-8", (TESTDATA / "ign-ortho-nodata.xml").read_bytes())
    assert mapcache.ortho_needs_fallback(200, "text/xml", b"<x/>")
    assert mapcache.ortho_needs_fallback(200, "image/jpeg", b"")


def test_flat_jpeg_detection():
    assert mapcache.jpeg_uniform_colour((TESTDATA / "ign-ortho-white.jpg").read_bytes()) == (255, 128, 128)
    assert mapcache.jpeg_uniform_colour((TESTDATA / "ign-ortho-sea.jpg").read_bytes()) == (48, 151, 101)
    assert mapcache.jpeg_uniform_colour((TESTDATA / "ign-ortho-alps.jpg").read_bytes()) is None
    white = (TESTDATA / "ign-ortho-white.jpg").read_bytes()
    assert all(mapcache.jpeg_uniform_colour(white[:n]) is None for n in (2, 100, 700, 1640))  # truncated
    assert mapcache.jpeg_uniform_colour(png(4, 4, b"\xff\xff\xff")) is None


# ---- disk cache ----

def test_purge_removes_least_recently_used_tiles_first(tmp_path):
    files = []
    for i in range(10):
        f = tmp_path / "ortho" / "10" / str(i) / "1.jpg"
        f.parent.mkdir(parents=True)
        f.write_bytes(b"x" * 1000)
        os.utime(f, (1_700_000_000 + i, 1_700_000_000 + i))  # realistic dates: tile 0 is the oldest
        files.append(f)
    (tmp_path / "peaks").mkdir()
    (tmp_path / "peaks" / "a.json").write_text("{}")  # not a tile: never purged
    left, deleted = mapcache.purge(tmp_path, limit=10_000, target=6_000)
    assert (left, deleted) == (10_000, 0)
    left, deleted = mapcache.purge(tmp_path, limit=9_000, target=6_000)
    assert (left, deleted) == (6_000, 4)
    assert [f.exists() for f in files] == [False] * 4 + [True] * 6
    assert (tmp_path / "peaks" / "a.json").exists()


def test_purge_goes_on_past_a_tile_windows_refuses_to_delete(tmp_path, monkeypatch):
    files = []
    for i in range(4):
        f = tmp_path / "ortho" / "10" / str(i) / "1.jpg"
        f.parent.mkdir(parents=True)
        f.write_bytes(b"x" * 1000)
        os.utime(f, (1_700_000_000 + i, 1_700_000_000 + i))
        files.append(f)
    real_remove = os.remove

    def remove(path):
        if path == str(files[0]):
            raise PermissionError(13, "in use", path)  # open in the map host at that moment
        real_remove(path)
    monkeypatch.setattr(mapcache.os, "remove", remove)
    left, deleted = mapcache.purge(tmp_path, limit=3_000, target=2_000)
    assert (left, deleted) == (2_000, 2)
    assert [f.exists() for f in files] == [True, False, False, True]


def test_a_malformed_summit_answer_still_answers_every_waiting_request(tmp_path, monkeypatch):
    from PySide6.QtWidgets import QApplication
    QApplication.instance() or QApplication([])
    from scriptorium_win.services import maphost
    store = maphost.TileStore(tmp_path)
    box = (6.8, 45.9, 6.9, 46.0)
    answers = []
    store._peaks_waiting[box] = [answers.append, answers.append]
    monkeypatch.setattr(store, "_text", lambda reply: "[1, 2]")  # valid JSON, not the expected object
    store._wfs_done(None, box, tmp_path / "peaks")
    assert answers == [[], []] and box not in store._peaks_waiting
    (tmp_path / "peaks").mkdir(exist_ok=True)
    peaks.cache_file(tmp_path / "peaks", box).write_text('{"candidates": {"a": 1}}')
    assert peaks.covering(tmp_path / "peaks", box) is None  # a damaged entry is a miss, not an exception


def test_backoff_after_a_connection_failure():
    b = mapcache.Backoff(20)
    assert not b.blocked(100.0)
    b.failed(100.0)
    assert b.blocked(110.0) and not b.blocked(121.0)
    b.succeeded()
    assert not b.blocked(110.0)


# ---- peaks ----

BOX = (6.80, 45.90, 6.90, 45.98)


def wfs(features):
    return json.dumps({"features": [{"geometry": {"coordinates": [lon, lat]}, "properties": {"toponyme": name, "importance": imp}}
                                    for name, lon, lat, imp in features]})


def test_wfs_answer_is_read_and_sorted():
    text = wfs([("Pointe Est", 6.85, 45.95, "4"), ("", 6.81, 45.91, "1"), ("Mont Synthétique", 6.86, 45.94, "1"),
                ("Hors cadre", 7.5, 45.95, "1"), ("Sans rang", 6.82, 45.93, "NC")])
    out = peaks.parse_wfs(text, BOX)
    assert [p["name"] for p in out] == ["Mont Synthétique", "Pointe Est", "Sans rang"]
    assert out[-1]["importance"] == 9
    swapped = json.dumps({"features": [{"geometry": {"coordinates": [45.95, 6.85]}, "properties": {"toponyme": "Inversé", "importance": "2"}}]})
    assert peaks.parse_wfs(swapped, BOX)[0]["lon"] == 6.85


def test_elevations_are_attached():
    cands = [{"name": "A", "ele": None, "lon": 6.85, "lat": 45.95, "importance": 1},
             {"name": "B", "ele": None, "lon": 6.86, "lat": 45.96, "importance": 2}]
    out = peaks.with_elevations(cands, json.dumps({"elevations": [2400.6, -99999]}))
    assert [p["ele"] for p in out] == [2401, None]
    assert "lon=6.850000|6.860000" in peaks.alti_url(cands)


def test_selection_keeps_important_spread_out_summits():
    side = max((BOX[2] - BOX[0]) * 111320 * math.cos(math.radians(45.94)), (BOX[3] - BOX[1]) * 110540)
    cands = [{"name": f"S{i}", "ele": 2000 + i, "lon": 6.80 + (i % 10) * 0.01, "lat": 45.90 + (i // 10) * 0.01,
              "importance": 1 + i % 3} for i in range(60)]
    out = peaks.select(cands, BOX)
    assert 0 < len(out) <= peaks.MAX_SHOWN
    for i, a in enumerate(out):
        for b in out[i + 1:]:
            d = math.hypot((a["lon"] - b["lon"]) * 111320 * math.cos(math.radians(45.94)), (a["lat"] - b["lat"]) * 110540)
            assert d >= side / 7
    assert out[0]["importance"] == 1
    near = [{"name": "Bas", "ele": 1000, "lon": 6.85, "lat": 45.95, "importance": 1},
            {"name": "Haut", "ele": 3000, "lon": 6.8501, "lat": 45.9501, "importance": 1}]
    assert [p["name"] for p in peaks.select(near, BOX)] == ["Haut"]  # same rank: the higher one wins


def test_peaks_cache_box_and_covering(tmp_path):
    assert peaks.cache_box(6.8234, 45.9012, 6.8811, 45.9555) == (6.82, 45.9, 6.89, 45.96)
    assert peaks.too_large((5.0, 45.0, 7.5, 46.0))
    big = (6.70, 45.80, 7.00, 46.10)
    peaks.cache_file(tmp_path, big).write_text(json.dumps({"box": big, "candidates": [{"name": "X"}]}))
    assert peaks.covering(tmp_path, (6.80, 45.90, 6.90, 46.00)) == [{"name": "X"}]
    assert peaks.covering(tmp_path, (6.60, 45.90, 6.90, 46.00)) is None
    assert peaks.wfs_url(BOX).count("BBOX") == 1


# ---- track data ----

def test_square_rule():
    w, s, e, n = mapdata.square_of(6.85, 45.90, 6.87, 45.91)
    lat_c = 45.905
    side_x = (e - w) * 111320 * math.cos(math.radians(lat_c))
    side_y = (n - s) * 110540
    assert side_x == pytest.approx(side_y)
    assert side_x == pytest.approx(max(0.02 * 111320 * math.cos(math.radians(lat_c)), 0.01 * 110540) * 1.25)
    assert ((w + e) / 2, (s + n) / 2) == pytest.approx((6.86, 45.905))
    tiny = mapdata.square_of(6.86, 45.9, 6.86, 45.9)
    assert (tiny[3] - tiny[1]) * 110540 == pytest.approx(1000)  # at least 500 m each side


def test_track_speeds_from_positions_and_times():
    t0 = 1_760_000_000_000
    lats = [45.9 + i * 0.0001 for i in range(30)]  # ~11 m every 2 s, then a 10 s stop
    lons = [6.86] * 30
    times = [t0 + i * 2000 for i in range(30)]
    speeds, avg = mapdata.track_speeds(times, lats, lons)
    assert len(speeds) == 30
    assert avg == pytest.approx(5.53, abs=0.05)
    assert mapdata.track_speeds([None] * 30, lats, lons) == (None, None)
    device, _ = mapdata.track_speeds([None] * 30, lats, lons, [3.0] * 30)
    assert device == pytest.approx([3.0] * 30)
    assert mapdata.thin_indices(10, 5) == [0, 2, 4, 6, 9]


# ---- the page through the scheme handler ----

def wait_for(app, predicate, timeout=20.0):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        app.processEvents()
        if predicate():
            return True
        time.sleep(0.02)
    return False


def test_handler_serves_the_page_and_the_tiles(monkeypatch):
    from PySide6.QtWidgets import QApplication
    app = QApplication.instance() or QApplication([])
    from scriptorium_win.services import maphost
    from scriptorium_win.ui.mapview import MapView
    tile = png(4, 4, b"\x10\x20\x30")
    monkeypatch.setattr(maphost.TileStore, "get", lambda self, t, done: done(("image/jpeg", tile) if t.kind == "ortho" else None))
    events = []
    view = MapView("2d")
    view.event.connect(events.append)
    view.resize(400, 300)
    view.show()
    assert wait_for(app, lambda: view.loaded), "map/index.html was not served"
    results = {}
    view.page().runJavaScript("document.title + '|' + typeof window.SMap.setTrack", 0, lambda r: results.setdefault("title", r))
    view.page().runJavaScript(
        "fetch('../t/ortho/1/0/0').then(r => { window.__type = r.headers.get('content-type'); return r.arrayBuffer(); })"
        ".then(b => window.__len = b.byteLength);"
        "fetch('../t/dem/1/0/0').then(r => window.__dem = r.ok ? 'ok' : 'status ' + r.status, () => window.__dem = 'failed');", 0)
    assert wait_for(app, lambda: "title" in results)
    assert results["title"] == "Carte|function"
    probe = {}  # a tile the host cannot get must reach the page as a plain 404
    assert wait_for(app, lambda: view.page().runJavaScript("JSON.stringify([window.__len, window.__dem, window.__type])", 0,
                                                          lambda r: probe.update(r=r)) or probe.get("r") == f'[{len(tile)},"status 404","image/jpeg"]'), probe
    # the page reports through QWebChannel: "ready" where WebGL exists, otherwise an explained error
    assert wait_for(app, lambda: any(e.get("type") in ("ready", "error") for e in events)), events
    view.call("setTrack", {"coords": [[6.86, 45.9], [6.87, 45.91]], "speeds": None, "avg": None})
    assert view.state["setTrack"][0]["coords"][0] == [6.86, 45.9]
    view.close()
    view.deleteLater()


# ---- contract with the page (web/map/map.js, read as text) ----

def _page() -> str:
    from pathlib import Path
    return (Path(__file__).resolve().parents[2] / "web" / "map" / "map.js").read_text(encoding="utf-8")


def test_every_function_the_windows_host_calls_is_in_the_page_api():
    import re
    from pathlib import Path
    page = _page()
    api = {name.strip().strip("'") for name in re.search(r"const API = \[([^\]]+)]", page).group(1).split(",")}
    ui = Path(__file__).resolve().parents[1] / "scriptorium_win" / "ui"
    called = set()
    for file in ("mapview.py", "activities_view.py"):
        called |= set(re.findall(r"\.call\(\"(\w+)\"", (ui / file).read_text(encoding="utf-8")))
    from scriptorium_win.ui import mapview
    called |= set(mapview.STATE_ORDER)
    assert len(called) >= 8
    assert called - api == set()


def test_urls_the_page_requests_are_served():
    page = _page()
    assert "BASE + 't/ortho/{z}/{x}/{y}'" in page
    assert "maxzoom: 19" in page
    assert "`${BASE}t/dem/${z}/${x}/${y}`" in page
    assert "w=${sq[0].toFixed(5)}&s=${sq[1].toFixed(5)}&e=${sq[2].toFixed(5)}&n=${sq[3].toFixed(5)}" in page
    assert mapcache.parse_request("/t/ortho/14/8501/5850") == mapcache.TileRequest("ortho", 14, 8501, 5850)
    assert mapcache.parse_request("/t/dem/13/4253/2915") == mapcache.TileRequest("dem", 13, 4253, 2915)
    assert mapcache.parse_request("/peaks", "w=6.80000&s=45.90000&e=6.90000&n=46.00000") == mapcache.PeaksRequest(6.8, 45.9, 6.9, 46.0)
