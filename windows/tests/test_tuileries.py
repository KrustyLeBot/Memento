"""The optional Tuileries tile server on Windows: URLs and headers, where each answer type is cached, the connection
test, the settings, and the tile store's routing (Tuileries only when configured, IGN/OSM/AWS otherwise). Synthetic
data and a local fake server only."""
import base64
import json
import time

import pytest

from fake_tuileries import INFO, PASSWORD, USER, WEBP, FakeTuileries, build_meta
from memento_win.core import mapcache, tuileries
from memento_win.services import maphost

# conftest stubs these out in every test; the store tests below need the real ones (they only reach the fake server)
REAL_GET, REAL_PEAKS = maphost.TileStore.get, maphost.TileStore.peaks


@pytest.fixture
def real_store(monkeypatch):
    from PySide6.QtWidgets import QApplication
    app = QApplication.instance() or QApplication([])
    monkeypatch.setattr(maphost.TileStore, "get", REAL_GET)
    monkeypatch.setattr(maphost.TileStore, "peaks", REAL_PEAKS)
    return app


@pytest.fixture
def server():
    fake = FakeTuileries()
    yield fake
    fake.close()


def source(url="https://tuileries.example.org", **kw):
    return tuileries.Tuileries(url, kw.get("user", USER), kw.get("password", PASSWORD), kw.get("cf_id", ""), kw.get("cf_secret", ""))


# ---- pure rules ----

def test_urls_and_headers():
    t = source(cf_id="id.access", cf_secret="s3cret")
    assert t.base == "https://tuileries.example.org/"
    assert t.tile_url(mapcache.TileRequest("ortho", 15, 16941, 11670)) == "https://tuileries.example.org/t/ortho/15/16941/11670"
    assert t.tile_url(mapcache.TileRequest("dem", 12, 2120, 1460)) == "https://tuileries.example.org/t/dem/12/2120/1460"
    assert t.buildings_url(16941, 11670) == "https://tuileries.example.org/buildings/15/16941/11670"
    assert t.peaks_url(6.8, 45.9, 6.9, 46.0) == "https://tuileries.example.org/peaks?w=6.80000&s=45.90000&e=6.90000&n=46.00000"
    assert t.info_url == "https://tuileries.example.org/api/info"
    h = t.headers()
    assert h["Authorization"] == "Basic " + base64.b64encode(f"{USER}:{PASSWORD}".encode()).decode()
    assert (h["CF-Access-Client-Id"], h["CF-Access-Client-Secret"]) == ("id.access", "s3cret")
    assert h["User-Agent"] == mapcache.USER_AGENT
    bare = tuileries.Tuileries("https://t.example.org/sub/", "", "").headers()
    assert "Authorization" not in bare and "CF-Access-Client-Id" not in bare


def test_url_normalisation_and_zoom_clamp():
    assert tuileries.normalize_url(" https://t.example.org/tiles ") == "https://t.example.org/tiles/"
    for bad in ("", "t.example.org", "ftp://t.example.org/", "https://", "https://t.example.org/?a=1"):
        assert tuileries.normalize_url(bad) == ""
    assert not tuileries.Tuileries("").configured and tuileries.Tuileries("http://nas.local:8080").configured
    assert [tuileries.clamp_photo_max_zoom(v) for v in (None, "x", 3, 14, "18", 25)] == [17, 17, 14, 14, 18, 19]


def test_each_answer_type_is_cached_where_its_type_says(tmp_path):
    t = mapcache.TileRequest("ortho", 15, 16941, 11670)
    webp = tuileries.tile_answer(t, 200, "image/webp", WEBP)
    assert webp.kind == "orthow" and webp.mime == "image/webp"
    assert mapcache.tile_path(tmp_path, webp) == tmp_path / "orthow" / "15" / "16941" / "11670.webp"
    assert tuileries.tile_answer(t, 200, "image/jpeg", b"\xff\xd8").kind == "ortho"
    assert tuileries.tile_answer(t, 200, "image/png", b"\x89PNG").kind == "osmfb2"  # Tuileries' OpenStreetMap stand-in
    assert tuileries.tile_answer(t, 404, "text/plain", b"none") is None
    assert tuileries.tile_answer(t, 502, "text/plain", b"bad gateway") is None
    assert tuileries.tile_answer(t, 200, "text/html", b"<html>login</html>") is None  # Cloudflare's login page
    assert tuileries.tile_answer(t, 200, "image/webp", b"") is None
    dem = mapcache.TileRequest("dem", 12, 2120, 1460)
    assert tuileries.tile_answer(dem, 200, "image/png; charset=binary", b"\x89PNG") == dem
    assert tuileries.tile_answer(dem, 200, "image/webp", WEBP) is None
    assert [v.kind for v in t.variants()] == ["ortho", "orthow", "orthoa", "osmfb2"] and dem.variants() == (dem,)
    assert tuileries.tile_answer(t, 200, "image/avif", b"avif").kind == "orthoa" and t.typed("image/avif").mime == "image/avif"


def test_purge_counts_webp_tiles(tmp_path):
    for i in range(3):
        f = tmp_path / "orthow" / "15" / str(i) / "1.webp"
        f.parent.mkdir(parents=True)
        f.write_bytes(b"x" * 1000)
    assert mapcache.purge(tmp_path, limit=1500, target=1000) == (1000, 2)


def test_cells_peaks_and_info_are_checked():
    cell = json.dumps({"v": 1, "z": 15, "x": 3, "y": 4, "o": [0, 0], "b": []}).encode()
    assert tuileries.valid_cell(cell, 3, 4) and not tuileries.valid_cell(cell, 3, 5) and not tuileries.valid_cell(b"<html>", 3, 4)
    found = tuileries.parse_peaks(b'[{"name":"Pic Test","ele":2500,"lon":6.85,"lat":45.95,"importance":2}]')
    assert found == [{"name": "Pic Test", "ele": 2500, "lon": 6.85, "lat": 45.95, "importance": 2}]
    for bad in (b'{"a":1}', b'[{"lon":1}]', b"nope"):
        with pytest.raises(ValueError):
            tuileries.parse_peaks(bad)
    info = tuileries.parse_info(INFO)
    assert (info.version, info.max_zoom, "ortho" in info.layers) == ("0.1.0", 17, True)
    with pytest.raises(tuileries.TuileriesError):
        tuileries.parse_info(b"<html></html>")


def test_connection_test_against_a_fake_server(server):
    info = tuileries.fetch_info(source(server.url, cf_id="cid", cf_secret="csec"))
    assert info.max_zoom == 17
    path, headers = server.requests[-1]
    assert path == "/api/info" and headers["cf-access-client-id"] == "cid" and headers["cf-access-client-secret"] == "csec"
    with pytest.raises(tuileries.TuileriesError, match="mot de passe refusé"):
        tuileries.fetch_info(source(server.url, password="faux"))
    with pytest.raises(tuileries.TuileriesError, match="Adresse invalide"):
        tuileries.fetch_info(source("pas une adresse"))


# ---- settings ----

def test_settings_are_kept(tmp_path):
    from PySide6.QtCore import QSettings
    from memento_win.config import Config
    ini = str(tmp_path / "settings.ini")
    config = Config(QSettings(ini, QSettings.Format.IniFormat))
    assert not config.tuileries.configured and config.photo_max_zoom == 17
    config.tuileries = source(cf_id="cid", cf_secret="csec")
    config.photo_max_zoom = 30
    again = Config(QSettings(ini, QSettings.Format.IniFormat))
    assert again.tuileries == source(cf_id="cid", cf_secret="csec")
    assert again.photo_max_zoom == 19


def test_settings_screen_round_trip():
    from PySide6.QtWidgets import QApplication
    QApplication.instance() or QApplication([])
    from memento_win.ui.settings_view import SettingsScreen
    screen = SettingsScreen()
    changes = []
    screen.map_changed.connect(lambda t, z: changes.append((t, z)))
    screen.load_map(source(), 16)
    assert not changes and screen.photo_zoom.value() == 16 and screen.tiles_password.text() == PASSWORD
    screen.tiles_url.setText("https://autre.example.org/")
    screen.tiles_url.editingFinished.emit()
    assert changes[-1] == (tuileries.Tuileries("https://autre.example.org/", USER, PASSWORD), 16)
    screen.tiles_url.setText("pas une adresse")
    screen.tiles_url.editingFinished.emit()
    assert len(changes) == 1 and "invalide" in screen.tiles_status.text()
    screen.show_tiles_test(tuileries.parse_info(INFO))
    assert "0.1.0" in screen.tiles_status.text() and "zoom 17" in screen.tiles_status.text()


# ---- the tile store's routing ----

class _Reply:
    class _Signal:
        def connect(self, slot):
            pass
    finished = _Signal()


def _requests(store, monkeypatch):
    sent = []

    def get(request):
        sent.append((request.url().toString(), {bytes(k).decode().lower(): bytes(request.rawHeader(bytes(k).decode())).decode() for k in request.rawHeaderList()}))
        return _Reply()
    monkeypatch.setattr(store, "_nam", type("Nam", (), {"get": staticmethod(get)})())
    return sent


def test_routing_follows_the_settings(tmp_path, monkeypatch, real_store):
    store = maphost.TileStore(tmp_path)
    sent = _requests(store, monkeypatch)
    ortho, dem = mapcache.TileRequest("ortho", 15, 16941, 11670), mapcache.TileRequest("dem", 12, 2120, 1460)
    store.source = None
    store.get(ortho, lambda r: None)
    store.get(dem, lambda r: None)
    store.buildings(mapcache.BuildingsRequest(16941, 11670), lambda r: None)
    assert [u.split("/")[2] for u, _ in sent] == ["data.geopf.fr", "s3.amazonaws.com", "data.geopf.fr"]
    assert all("authorization" not in h and "cf-access-client-secret" not in h for _, h in sent)
    sent.clear()
    store._waiting.clear()
    store._cells_waiting.clear()
    store.source = source(cf_id="cid", cf_secret="csec")
    store.get(ortho, lambda r: None)
    store.get(dem, lambda r: None)
    store.buildings(mapcache.BuildingsRequest(16941, 11670), lambda r: None)
    store.peaks(mapcache.PeaksRequest(6.8, 45.9, 6.9, 46.0), lambda r: None)
    assert [u for u, _ in sent] == [
        "https://tuileries.example.org/meta/15/16940/11668?layers=ortho,dem,buildings,trails", "https://tuileries.example.org/t/dem/12/2120/1460",
        "https://tuileries.example.org/buildings/15/16941/11670",
        "https://tuileries.example.org/peaks?w=6.80000&s=45.90000&e=6.90000&n=46.00000"]
    assert all(h["authorization"].startswith("Basic ") and h["cf-access-client-id"] == "cid" for _, h in sent)


def test_tuileries_answers_are_cached_with_their_type(tmp_path, real_store):
    store = maphost.TileStore(tmp_path)
    store.source = source()
    t = mapcache.TileRequest("ortho", 15, 16941, 11670)
    got = []
    store._waiting[t.key] = [got.append]
    store._tuileries_answer(t, False, 200, "image/webp", WEBP)
    assert _wait(real_store, lambda: not store._pending)  # written by a worker
    assert got == [("image/webp", WEBP)]
    assert (tmp_path / "orthow" / "15" / "16941" / "11670.webp").read_bytes() == WEBP
    store.get(t, got.append)  # from the cache now, whatever the settings
    store.source = None
    store.get(t, got.append)
    assert got[1:] == [("image/webp", WEBP)] * 2
    # an error is no tile, never cached, and no stand-in is fetched elsewhere
    t2 = mapcache.TileRequest("ortho", 15, 1, 1)
    store._waiting[t2.key] = [got.append]
    store.source = source()
    store._tuileries_answer(t2, False, 502, "text/plain", b"upstream down")
    assert got[-1] is None and not list(tmp_path.glob("*/15/1/*"))
    # unreachable: the backoff answers the next ones at once, like offline
    store._waiting[t2.key] = [got.append]
    store._tuileries_answer(t2, True, 0, "", b"")
    assert store.backoff.blocked(time.monotonic())


def test_cells_and_peaks_from_tuileries_are_cached(tmp_path, real_store):
    store = maphost.TileStore(tmp_path)
    store.source = source()
    cell = json.dumps({"v": 1, "z": 15, "x": 3, "y": 4, "o": [0, 0], "b": []})
    got = []
    store._cells_waiting[(3, 4)] = [got.append]
    store._tuileries_cell_text(cell, (3, 4))
    assert _wait(real_store, lambda: not store._pending)  # written by a worker
    assert got == [cell.encode()] and (tmp_path / "buildings" / "15" / "3" / "4.json").is_file()
    box = (6.8, 45.9, 6.9, 46.0)
    key = ("tuileries",) + box
    file = maphost.tuileries_peaks_file(tmp_path, box)
    store._peaks_waiting[key] = [got.append]
    store._tuileries_peaks_text('[{"name":"Pic Test","ele":2500,"lon":6.85,"lat":45.95,"importance":2}]', key, file)
    assert got[-1][0]["name"] == "Pic Test"
    store.peaks(mapcache.PeaksRequest(*box), got.append)  # from the cache, no request
    assert got[-1] == got[-2]


def _wait(app, predicate, seconds=10.0):
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        app.processEvents()
        if predicate():
            return True
        time.sleep(0.01)
    return False


def test_store_fetches_from_a_fake_tuileries(tmp_path, server, real_store):
    app = real_store
    server.answers["/t/ortho/15/16941/11670"] = (200, "image/webp", WEBP)
    store = maphost.TileStore(tmp_path)
    store.source = source(server.url, cf_id="cid", cf_secret="csec")
    got = []
    store.get(mapcache.TileRequest("ortho", 15, 16941, 11670), got.append)
    store.get(mapcache.TileRequest("ortho", 15, 1, 1), got.append)  # 404 there; /meta answers 404 too (an older Tuileries): single tiles
    assert _wait(app, lambda: len(got) == 2), got
    assert ("image/webp", WEBP) in got and None in got
    path, headers = next(r for r in server.requests if r[0] == "/t/ortho/15/16941/11670")
    assert headers["cf-access-client-secret"] == "csec" and headers["user-agent"] == mapcache.USER_AGENT
    from PySide6.QtCore import QCoreApplication, QEvent
    QCoreApplication.sendPostedEvents(None, QEvent.Type.DeferredDelete)  # the replies go before their manager
    _KEEP.append(store)  # as in the app, the store lives on: never collected under a network thread winding down


_KEEP = []


def test_configure_reaches_the_live_store_and_the_page_url():
    from PySide6.QtWidgets import QApplication
    QApplication.instance() or QApplication([])
    from memento_win.ui import mapview
    try:
        maphost.configure(source(), 15, fetch_info=False)
        assert maphost.tile_store().source == source()
        assert mapview.page_url("2d", "light").endswith("&photomax=15&trails=1")
        maphost.set_attribution("Sentinel-2 cloudless & EOX")
        assert mapview.page_url("2d", "light").endswith("&trails=1&attrib=Sentinel-2%20cloudless%20%26%20EOX")
        maphost.configure(tuileries.Tuileries(""), 99)
        assert maphost.tile_store().source is None and maphost.photo_max_zoom() == 19
        assert mapview.page_url("3d", "dark").endswith("&interactive=1&photomax=19")  # no trails, no credit
    finally:
        maphost.configure(None, tuileries.PHOTO_MAX_ZOOM_DEFAULT)


def test_trails_requests_and_answers(tmp_path):
    import gzip
    assert mapcache.parse_request("/trails/12/2120/1460") == mapcache.TrailsRequest(12, 2120, 1460)
    assert mapcache.parse_request("/trails/16/1/1") is None and mapcache.parse_request("/trails/2/4/0") is None
    assert mapcache.trails_path(tmp_path, mapcache.TrailsRequest(12, 2120, 1460)) == tmp_path / "trails" / "12" / "2120" / "1460.pbf"
    assert source().trails_url(mapcache.TrailsRequest(12, 2120, 1460)) == "https://tuileries.example.org/trails/12/2120/1460"
    mvt = b"\x1a\x08trails"
    assert tuileries.trails_answer(200, mvt) == mvt
    assert tuileries.trails_answer(200, gzip.compress(mvt)) == mvt
    assert tuileries.trails_answer(204, b"") == b"" and tuileries.trails_answer(404, b"none") == b""
    assert tuileries.trails_answer(502, b"down") is None
    info = tuileries.parse_info(b'{"version":"1","max_zoom":17,"attribution":"Sentinel-2 cloudless by EOX"}')
    assert info.attribution == "Sentinel-2 cloudless by EOX" and tuileries.parse_info(INFO).attribution == ""


def test_trails_are_cached_but_not_their_absence(tmp_path, real_store, monkeypatch):
    store = maphost.TileStore(tmp_path)
    got = []
    store.trails(mapcache.TrailsRequest(12, 1, 1), got.append)
    assert got == [None]  # no Tuileries: no trails at all
    sent = _requests(store, monkeypatch)
    store.source = source()
    req, empty = mapcache.TrailsRequest(12, 2120, 1460), mapcache.TrailsRequest(12, 2121, 1460)
    store.trails(req, got.append)
    assert sent[-1][0] == "https://tuileries.example.org/trails/12/2120/1460" and sent[-1][1]["authorization"].startswith("Basic ")
    store._trails_answer(req, "trails/12/2120/1460", False, 200, b"\x1a\x08trails")
    store.trails(empty, got.append)
    store._trails_answer(empty, "trails/12/2121/1460", False, 204, b"")
    assert _wait(real_store, lambda: not store._pending)
    assert got[1:] == [b"\x1a\x08trails", b""]
    assert mapcache.trails_path(tmp_path, req).is_file() and not mapcache.trails_path(tmp_path, empty).exists()
    store.trails(req, got.append)  # from the cache
    assert got[-1] == b"\x1a\x08trails" and len(sent) == 2


# ---- metatiles (contract v1.1) ----

CELL15 = json.dumps({"v": 2, "z": 15, "x": 16941, "y": 11670, "o": [0, 0], "b": [], "c": []}).encode()


def _meta_17():
    """The z17 block holding tile (67764, 46680): its 16 photos (one pending, one with no tile), the z13 relief, the
    z15 buildings cell and the z15 trails tile, gzipped where the contract says."""
    import gzip
    x0, y0 = 67764, 46680
    photos = []
    for y in range(y0, y0 + 4):
        for x in range(x0, x0 + 4):
            if (x, y) == (x0 + 1, y0):
                photos.append((0, 17, x, y, 3, 0, 0, b""))  # pending
            elif (x, y) == (x0 + 2, y0):
                photos.append((0, 17, x, y, 1, 0, 0, b""))  # no tile
            else:
                photos.append((0, 17, x, y, 0, 1, 0, WEBP + bytes([x % 256])))
    return x0, y0, build_meta(17, x0, y0, photos + [
        (1, 13, 4235, 2917, 0, 2, 0, b"\x89PNG dem"),
        (2, 15, 16941, 11670, 0, 4, 1, gzip.compress(CELL15)),
        (3, 15, 16941, 11670, 0, 5, 1, gzip.compress(b"\x1a\x08trails")),
    ])


def test_metatile_is_parsed_and_each_entry_finds_its_cache(tmp_path):
    x0, y0, data = _meta_17()
    entries = tuileries.parse_meta(data)
    assert len(entries) == 19 and [e.layer for e in entries[-3:]] == ["dem", "buildings", "trails"]
    assert entries[-2].data == CELL15 and entries[-1].data == b"\x1a\x08trails"  # un-gzipped
    targets = [tuileries.meta_cache_target(tmp_path, e) for e in entries]
    assert targets[0] == (tmp_path / "orthow" / "17" / str(x0) / f"{y0}.webp", WEBP + bytes([x0 % 256]))
    assert targets[1] is None and targets[2] is None  # pending, no tile
    assert targets[-3] == (tmp_path / "dem" / "13" / "4235" / "2917.png", b"\x89PNG dem")
    assert targets[-2][0] == tmp_path / "buildings" / "15" / "16941" / "11670.json"
    assert targets[-1][0] == tmp_path / "trails" / "15" / "16941" / "11670.pbf"
    assert tuileries.meta_block(17, 67765, 46683) == (17, 67764, 46680)
    assert tuileries.meta_url(source(), 17, 67764, 46680) == \
        "https://tuileries.example.org/meta/17/67764/46680?layers=ortho,dem,buildings,trails"
    for bad in (b"", b"XXXX" + data[4:], data[:-3]):
        with pytest.raises(ValueError):
            tuileries.parse_meta(bad)


def test_one_metatile_serves_the_block_and_fills_the_caches(tmp_path, real_store, monkeypatch):
    x0, y0, data = _meta_17()
    store = maphost.TileStore(tmp_path)
    sent = _requests(store, monkeypatch)
    store.source = source()
    got = {}
    for dx, dy in ((0, 0), (1, 0), (2, 0), (3, 3)):
        store.get(mapcache.TileRequest("ortho", 17, x0 + dx, y0 + dy), lambda r, k=(dx, dy): got.__setitem__(k, r))
    assert [u for u, _ in sent] == [f"https://tuileries.example.org/meta/17/{x0}/{y0}?layers=ortho,dem,buildings,trails"]
    store._meta_answer((17, x0, y0), False, 200, data)
    assert got[(0, 0)] == ("image/webp", WEBP + bytes([x0 % 256])) and got[(3, 3)][0] == "image/webp"
    assert got[(2, 0)] is None  # no tile there
    assert (1, 0) not in got and sent[-1][0] == f"https://tuileries.example.org/t/ortho/17/{x0 + 1}/{y0}"  # pending: alone
    assert _wait(real_store, lambda: not store._pending)
    assert (tmp_path / "dem" / "13" / "4235" / "2917.png").read_bytes() == b"\x89PNG dem"
    found = []
    store.buildings(mapcache.BuildingsRequest(16941, 11670), found.append)
    store.trails(mapcache.TrailsRequest(15, 16941, 11670), found.append)
    store.get(mapcache.TileRequest("ortho", 17, x0 + 1, y0 + 1), found.append)
    assert found == [CELL15, b"\x1a\x08trails", ("image/webp", WEBP + bytes([(x0 + 1) % 256]))]
    assert len(sent) == 2  # all three from the cache
    # within a minute, a block's missing tile is asked for alone, not by a second metatile
    store.get(mapcache.TileRequest("ortho", 17, x0 + 2, y0), found.append)
    assert sent[-1][0] == f"https://tuileries.example.org/t/ortho/17/{x0 + 2}/{y0}"


def test_a_tuileries_without_metatiles_gets_single_tiles(tmp_path, real_store, monkeypatch):
    store = maphost.TileStore(tmp_path)
    sent = _requests(store, monkeypatch)
    store.source = source()
    store.get(mapcache.TileRequest("ortho", 17, 4, 4), lambda r: None)
    store._meta_answer((17, 4, 4), False, 404, b"none")
    store.get(mapcache.TileRequest("ortho", 17, 8, 8), lambda r: None)  # another block: no /meta for a while
    assert [u.split("/")[3] for u, _ in sent] == ["meta", "t", "t"]
