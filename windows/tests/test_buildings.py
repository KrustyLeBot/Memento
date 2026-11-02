"""The buildings the 3D map extrudes (core/buildings.py): cell maths, the WFS answer slimmed to footprints and heights,
the compact JSON, the cache path, and the Windows host's page-by-page fetch. Synthetic data only."""
import json
import os

from memento_win.core import buildings, mapcache

X, Y = 16941, 11670  # the z15 cell of Annecy's old town


def feature(fid, rings, **props):
    return {"type": "Feature", "id": fid, "properties": props,
            "geometry": {"type": "MultiPolygon", "coordinates": [rings]}}


def square(lon, lat, side=0.0002, z=450.0):
    return [[lon, lat, z], [lon + side, lat, z], [lon + side, lat + side, z], [lon, lat + side, z], [lon, lat, z]]


def inside_cell(fx=0.5, fy=0.5):
    w, s, e, n = buildings.cell_box(X, Y)
    return w + (e - w) * fx, s + (n - s) * fy


# ---- cell maths ----

def test_cell_box_and_cell_of_agree():
    w, s, e, n = buildings.cell_box(X, Y)
    assert 6.119 < w < 6.129 < e < 6.137 and 45.89 < s < 45.899 < n < 45.91
    assert abs((e - w) * 111320 * 0.696 - 851) < 5           # 850 m wide at Annecy (1.2 km at the equator)
    assert buildings.cell_of(6.129, 45.899) == (X, Y)
    assert buildings.cell_of(w, n - 1e-9) == (X, Y) and buildings.cell_of(e, n - 1e-9) == (X + 1, Y)
    assert buildings.cells((w, s, e, n)) == [(X, Y)]          # edges on the borders pull in no neighbour
    assert len(buildings.cells((w - 1e-4, s - 1e-4, e + 1e-4, n + 1e-4))) == 9
    assert buildings.valid_cell(15, X, Y) and not buildings.valid_cell(14, X, Y) and not buildings.valid_cell(15, 2 ** 15, 0)


def test_requests_and_cache_path(tmp_path):
    assert mapcache.parse_request(f"/buildings/15/{X}/{Y}") == mapcache.BuildingsRequest(X, Y)
    assert mapcache.parse_request("/buildings/14/1/1") is None
    assert mapcache.parse_request(f"/buildings/15/{2 ** 15}/1") is None
    assert mapcache.parse_request("/buildings/15/a/1") is None
    assert mapcache.parse_request("/buildings/15/1") is None
    assert buildings.cache_path(tmp_path, X, Y) == tmp_path / "buildings" / "15" / str(X) / f"{Y}.json"


def test_wfs_url_pages_and_axis_order():
    url = buildings.wfs_url(X, Y, 2000)
    w, s, e, n = buildings.cell_box(X, Y)
    assert "TYPENAMES=BDTOPO_V3:batiment" in url and "STARTINDEX=2000" in url and f"COUNT={buildings.PAGE}" in url
    assert url.endswith(f"&BBOX={s:.7f},{w:.7f},{n:.7f},{e:.7f},urn:ogc:def:crs:EPSG::4326")  # lat,lon in the URN form
    assert "PROPERTYNAME=geometrie,hauteur" in url


# ---- heights ----

def test_height_falls_back_from_hauteur_to_altitudes_to_floors_to_default():
    assert buildings.height({"hauteur": 21.5, "altitude_maximale_toit": 474, "altitude_minimale_sol": 450.6}) == 21.5
    assert buildings.height({"hauteur": None, "altitude_maximale_toit": 474.04, "altitude_minimale_sol": 450.6}) == 23.4
    assert buildings.height({"hauteur": 0, "altitude_maximale_toit": 451, "altitude_minimale_sol": 450.6}) == 3.0  # at least 3 m
    assert buildings.height({"altitude_maximale_toit": 440, "altitude_minimale_sol": 450.6, "nombre_d_etages": 4}) == 13
    assert buildings.height({"nombre_d_etages": 2}) == 7
    assert buildings.height({"hauteur": "12"}) == buildings.DEFAULT_HEIGHT  # not a number
    assert buildings.height({}) == buildings.DEFAULT_HEIGHT
    assert buildings.height({"hauteur": 9999}) == buildings.MAX_HEIGHT


# ---- slimming ----

def test_slim_keeps_buildings_whose_centre_is_in_the_cell_once():
    lon, lat = inside_cell()
    w, s, e, n = buildings.cell_box(X, Y)
    features = [
        feature("b.1", [square(lon, lat)], hauteur=12.0),
        feature("b.1", [square(lon, lat)], hauteur=12.0),                    # the same one again on the next page
        feature("b.2", [square(e - 0.00005, lat)], hauteur=8.0),            # straddles the east border, centre outside
        feature("b.3", [square(e - 0.00015, lat)], nombre_d_etages=3),      # straddles it, centre inside
        feature("b.4", [[[lon, lat], [lon, lat], [lon, lat]]]),               # degenerate
        {"id": "b.5", "properties": {}, "geometry": None},
    ]
    out = buildings.slim(features, X, Y)
    assert [h for h, _r in out] == [12.0, 10.0]
    assert all(len(rings[0]) == 4 for _h, rings in out)                     # closing point dropped


def test_multipolygon_parts_holes_and_simplification():
    lon, lat = inside_cell(0.3, 0.3)
    outer = [[lon, lat], [lon + 0.0001, lat], [lon + 0.0002, lat + 0.0000001], [lon + 0.0002, lat + 0.0002],
             [lon, lat + 0.0002], [lon, lat]]                                  # a vertex 0.01 m off the south edge
    hole = [[lon + 0.00005, lat + 0.00005], [lon + 0.00015, lat + 0.00005], [lon + 0.00015, lat + 0.00015],
            [lon + 0.00005, lat + 0.00005]]
    lon2, lat2 = inside_cell(0.6, 0.6)
    f = {"id": "m", "properties": {"hauteur": 5}, "geometry": {"type": "MultiPolygon", "coordinates": [
        [outer, hole], [square(lon2, lat2)]]}}
    out = buildings.slim([f], X, Y)
    assert len(out) == 2
    rings = out[0][1]
    assert len(rings) == 2 and len(rings[0]) == 4 and len(rings[1]) == 3   # the near-collinear vertex is gone
    poly = {"id": "p", "properties": {}, "geometry": {"type": "Polygon", "coordinates": [square(lon2, lat2)]}}
    assert len(buildings.slim([poly], X, Y)) == 1


def test_encode_is_compact_and_decodes_to_the_rounded_footprints():
    lon, lat = inside_cell()
    f = feature("b", [square(lon, lat)], hauteur=12.34)
    data = buildings.encode(X, Y, buildings.slim([f], X, Y))
    doc = json.loads(data)
    assert doc["v"] == 1 and doc["z"] == 15 and (doc["x"], doc["y"]) == (X, Y)
    w, s, _e, _n = buildings.cell_box(X, Y)
    assert doc["o"] == [round(w * 1e6), round(s * 1e6)]
    assert doc["b"][0][0] == 12.3 and all(isinstance(v, int) for v in doc["b"][0][1])
    assert b" " not in data
    ((h, rings),) = buildings.decode(data)
    assert h == 12.3
    for (a, b), c in zip(rings[0], square(lon, lat)):
        assert abs(a - c[0]) <= 5e-7 and abs(b - c[1]) <= 5e-7
    assert json.loads(buildings.encode(X, Y, [(6.0, [[(1, 2), (3, 4), (5, 7)]])]))["b"] == [[6, [1 - doc["o"][0], 2 - doc["o"][1], 2, 2, 2, 3]]]


def test_parse_page_refuses_what_is_not_a_feature_collection():
    assert buildings.parse_page('{"type":"FeatureCollection","features":[]}') == []
    for bad in ("<ows:ExceptionReport/>", "[1, 2]", '{"features": 3}'):
        try:
            buildings.parse_page(bad)
        except buildings.WfsError:
            continue
        raise AssertionError(bad)


def test_fetch_cell_pages_until_a_short_page(monkeypatch):
    monkeypatch.setattr(buildings, "PAGE", 2)
    lon, lat = inside_cell()
    pages = [[feature(f"b.{i}", [square(lon + i * 0.0003, lat)], hauteur=4.0 + i) for i in range(k, k + n)]
             for k, n in ((0, 2), (2, 2), (4, 1))]
    calls = []

    def get(url):
        calls.append(url)
        return 200, "application/json", json.dumps({"features": pages[len(calls) - 1]}).encode()
    out = buildings.decode(buildings.fetch_cell(X, Y, get))
    assert [h for h, _ in out] == [4.0, 5.0, 6.0, 7.0, 8.0]
    assert [u.split("STARTINDEX=")[1].split("&")[0] for u in calls] == ["0", "2", "4"]
    try:
        buildings.fetch_cell(X, Y, lambda url: (503, "text/html", b"busy"))
    except buildings.WfsError as error:
        assert "503" in str(error)
    else:
        raise AssertionError("an HTTP error must not be cached as an empty cell")


def test_purge_counts_building_cells_but_not_summits(tmp_path):
    cell = buildings.cache_path(tmp_path, X, Y)
    cell.parent.mkdir(parents=True)
    cell.write_bytes(b"x" * 5000)
    os.utime(cell, (1_700_000_000, 1_700_000_000))
    tile = tmp_path / "ortho" / "17" / "1" / "1.jpg"
    tile.parent.mkdir(parents=True)
    tile.write_bytes(b"x" * 5000)
    (tmp_path / "peaks").mkdir()
    (tmp_path / "peaks" / "a.json").write_text("{}" * 2000)
    assert mapcache.purge(tmp_path, limit=9_000, target=6_000) == (5_000, 1)
    assert not cell.exists() and tile.exists() and (tmp_path / "peaks" / "a.json").exists()


# ---- the Windows host ----

def test_windows_host_fetches_page_by_page_caches_and_answers_every_waiting_request(tmp_path, monkeypatch):
    from PySide6.QtWidgets import QApplication
    QApplication.instance() or QApplication([])
    from memento_win.services import maphost
    monkeypatch.setattr(buildings, "PAGE", 1)
    store = maphost.TileStore(tmp_path)
    lon, lat = inside_cell()
    answers = iter([json.dumps({"features": [feature("a", [square(lon, lat)], hauteur=9.0)]}),
                    json.dumps({"features": []})])
    requested = []
    monkeypatch.setattr(store, "_text", lambda reply: next(answers))
    monkeypatch.setattr(store, "_cell_page", lambda key, features: (requested.append(len(features)),
                                                                      store._cell_page_done(None, key, features)))
    got = []
    req = mapcache.BuildingsRequest(X, Y)
    store._cells_waiting[(X, Y)] = [got.append]  # a second page asking while the first fetch runs
    store.buildings(req, got.append)
    assert requested == [] and got == []         # joined the fetch in flight
    del store._cells_waiting[(X, Y)]
    store.buildings(req, got.append)
    assert requested == [0, 1]
    assert len(got) == 1 and [h for h, _ in buildings.decode(got[0])] == [9.0]
    assert buildings.cache_path(tmp_path, X, Y).read_bytes() == got[0]
    store.buildings(req, got.append)             # from the cache now
    assert requested == [0, 1] and got[1] == got[0]
    monkeypatch.setattr(store, "_text", lambda reply: "<ExceptionReport/>")
    store.buildings(mapcache.BuildingsRequest(X + 1, Y), got.append)
    assert got[2] is None and not buildings.cache_path(tmp_path, X + 1, Y).exists()


def test_shared_fixture_gives_the_cell_the_android_host_serves_too():
    """testdata/map/buildings-*.json: a synthetic WFS page and the exact JSON both hosts make of it (BuildingsTest.kt)."""
    from pathlib import Path
    data = Path(__file__).resolve().parents[2] / "testdata" / "map"
    features = buildings.parse_page((data / "buildings-wfs.json").read_text(encoding="utf-8"))
    assert buildings.encode(X, Y, buildings.slim(features, X, Y)) == (data / "buildings-cell.json").read_bytes().strip()
