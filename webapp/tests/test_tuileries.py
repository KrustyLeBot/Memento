"""The optional Tuileries tile server on the web host: settings from the environment, routing (Tuileries only when
configured), credentials only toward it, content types passed through and cached, the settings' status line.
Synthetic data only; FakeNet stands in for every server, a real local fake for http_get."""
from __future__ import annotations

import base64
import json
import sys
from pathlib import Path

import pytest

from conftest import make_env
from memento_web.config import ConfigError, from_env

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "windows" / "tests"))
from fake_tuileries import PASSWORD, USER, WEBP, FakeTuileries  # noqa: E402

TILES = {"MEMENTO_TILES_URL": "https://tuileries.example.org", "MEMENTO_TILES_USER": USER,
         "MEMENTO_TILES_PASSWORD": PASSWORD, "MEMENTO_TILES_CF_ID": "cid.access", "MEMENTO_TILES_CF_SECRET": "csecret"}
BASIC = "Basic " + base64.b64encode(f"{USER}:{PASSWORD}".encode()).decode()
CELL = json.dumps({"v": 1, "z": 15, "x": 16941, "y": 11670, "o": [6800000, 45900000], "b": [[6, [0, 0, 10, 0, 0, 10]]]}).encode()


@pytest.fixture
def tiles_client(make_client):
    from conftest import login
    c = make_client(**TILES)
    assert login(c).status_code == 200
    return c


def test_settings_from_the_environment(folders):
    data, config = folders
    assert from_env(make_env(data, config)).tiles is None
    tiles = from_env(make_env(data, config, **TILES)).tiles
    assert tiles.base == "https://tuileries.example.org/" and tiles.cf_secret == "csecret"
    assert "csecret" not in repr(from_env(make_env(data, config, **TILES)))
    for bad in ({"MEMENTO_TILES_URL": "tuileries.example.org"}, {"MEMENTO_TILES_USER": USER},
                {"MEMENTO_TILES_URL": "https://t.example.org/", "MEMENTO_TILES_CF_ID": "only-the-id"}):
        with pytest.raises(ConfigError):
            from_env(make_env(data, config, **bad))


def test_without_tuileries_nothing_changes(client, net):
    client.get("/t/dem/10/530/360")
    client.get("/peaks", params={"w": 6.0, "s": 45.0, "e": 6.1, "n": 45.1})
    assert net.calls[0].startswith("https://s3.amazonaws.com/") and net.calls[1].startswith("https://data.geopf.fr/wfs")
    assert net.headers == [None] * len(net.calls)  # no credentials toward IGN, OSM or AWS
    assert client.get("/api/map/tiles").json() == {"source": "direct"}


def test_with_tuileries_every_request_goes_there_with_its_credentials(tiles_client, net, folders):
    net.answers["https://tuileries.example.org/t/ortho/"] = (200, "image/webp", WEBP)
    net.answers["https://tuileries.example.org/t/dem/"] = (200, "image/png", b"\x89PNG dem")
    net.answers["https://tuileries.example.org/buildings/"] = (200, "application/json", CELL)
    net.answers["https://tuileries.example.org/peaks"] = (200, "application/json",
                                                         b'[{"name":"Pic Test","ele":2500,"lon":6.05,"lat":45.05,"importance":1}]')
    photo = tiles_client.get("/t/ortho/15/16941/11670")
    assert photo.status_code == 200 and photo.headers["content-type"] == "image/webp" and photo.content == WEBP
    assert "max-age=86400" in photo.headers["cache-control"]
    assert tiles_client.get("/t/dem/12/2120/1460").headers["content-type"] == "image/png"
    assert tiles_client.get("/buildings/15/16941/11670").content == CELL
    peaks = tiles_client.get("/peaks", params={"w": 6.0, "s": 45.0, "e": 6.1, "n": 45.1}).json()
    assert peaks[0]["name"] == "Pic Test"
    # the metatile first (this fake answers it with garbage: the photo is then asked for alone)
    assert net.calls == ["https://tuileries.example.org/meta/15/16940/11668?layers=ortho,dem,buildings,trails",
                         "https://tuileries.example.org/t/ortho/15/16941/11670", "https://tuileries.example.org/t/dem/12/2120/1460",
                         "https://tuileries.example.org/buildings/15/16941/11670",
                         "https://tuileries.example.org/peaks?w=6.00000&s=45.00000&e=6.10000&n=45.10000"]
    for headers in net.headers:
        assert headers["Authorization"] == BASIC and headers["CF-Access-Client-Id"] == "cid.access"
        assert headers["CF-Access-Client-Secret"] == "csecret"
    tiles_dir = folders[1] / "tiles"
    assert (tiles_dir / "orthow" / "15" / "16941" / "11670.webp").read_bytes() == WEBP
    # all from the cache now
    for path in ("/t/ortho/15/16941/11670", "/t/dem/12/2120/1460", "/buildings/15/16941/11670"):
        assert tiles_client.get(path).status_code == 200
    tiles_client.get("/peaks", params={"w": 6.0, "s": 45.0, "e": 6.1, "n": 45.1})
    assert len(net.calls) == 5


def test_tuileries_png_stand_in_and_failures(tiles_client, net):
    net.answers["https://tuileries.example.org/t/ortho/14/1/"] = (200, "image/png", b"\x89PNG osm")
    net.answers["https://tuileries.example.org/t/ortho/14/2/"] = (404, "text/plain", b"none")
    net.answers["https://tuileries.example.org/t/ortho/14/3/"] = (502, "text/plain", b"upstream down")
    net.answers["https://tuileries.example.org/t/ortho/14/4/"] = (200, "text/html", b"<html>Cloudflare Access</html>")
    stand_in = tiles_client.get("/t/ortho/14/1/1")
    assert stand_in.headers["content-type"] == "image/png" and "max-age=3600" in stand_in.headers["cache-control"]
    for x in (2, 3, 4):
        assert tiles_client.get(f"/t/ortho/14/{x}/1").status_code == 404
    assert all(u.startswith("https://tuileries.example.org/") for u in net.calls)  # never a fallback elsewhere


def test_unreachable_tuileries_fails_like_offline(tiles_client, net):
    net.offline = True
    assert tiles_client.get("/t/ortho/14/1/1").status_code == 404
    assert tiles_client.get("/t/ortho/14/1/2").status_code == 404
    assert len(net.calls) == 1
    status = tiles_client.get("/api/map/tiles").json()
    assert status["source"] == "tuileries" and "injoignable" in status["error"]
    assert PASSWORD not in json.dumps(status) and "csecret" not in json.dumps(status)


def test_status_line_reads_api_info(tiles_client, net):
    net.answers["https://tuileries.example.org/api/info"] = (200, "application/json",
                                                            b'{"name":"Tuileries","version":"0.1.0","max_zoom":17,"layers":["ortho"]}')
    assert tiles_client.get("/api/map/tiles").json() == {
        "source": "tuileries", "url": "https://tuileries.example.org/", "name": "Tuileries", "version": "0.1.0", "max_zoom": 17,
        "attribution": ""}
    net.answers["https://tuileries.example.org/api/info"] = (401, "text/plain", b"")
    calls = len(net.calls)
    assert tiles_client.get("/api/map/tiles").json()["version"] == "0.1.0"  # a good answer is kept a while...
    assert len(net.calls) == calls
    assert "mot de passe" in tiles_client.get("/api/map/tiles?fresh=1").json()["error"]  # ...but not for the test


def test_trails_through_tuileries(tiles_client, net, folders):
    import gzip
    mvt = b"\x1a\x08trails"
    net.answers["https://tuileries.example.org/trails/12/2120/"] = (200, "application/vnd.mapbox-vector-tile", gzip.compress(mvt))
    net.answers["https://tuileries.example.org/trails/12/2121/"] = (204, "", b"")
    net.answers["https://tuileries.example.org/trails/12/2122/"] = (502, "text/plain", b"down")
    tile = tiles_client.get("/trails/12/2120/1460")
    assert tile.status_code == 200 and tile.content == mvt and tile.headers["content-type"] == "application/x-protobuf"
    assert (folders[1] / "tiles" / "trails" / "12" / "2120" / "1460.pbf").read_bytes() == mvt
    empty = tiles_client.get("/trails/12/2121/1460")
    assert empty.status_code == 204 and "max-age=300" in empty.headers["cache-control"]
    tiles_client.get("/trails/12/2121/1460")
    assert net.calls.count("https://tuileries.example.org/trails/12/2121/1460") == 2  # an empty answer is not kept
    assert tiles_client.get("/trails/12/2122/1460").status_code == 404
    tiles_client.get("/trails/12/2120/1460")
    assert net.calls.count("https://tuileries.example.org/trails/12/2120/1460") == 1
    assert all(h["Authorization"] == BASIC for h in net.headers)


def test_no_trails_without_tuileries_and_none_without_login(client, net, make_client):
    assert client.get("/trails/12/2120/1460").status_code == 404 and net.calls == []
    assert make_client().get("/trails/12/2120/1460").status_code == 401


def test_http_get_carries_the_credentials_to_a_real_server():
    from memento_web.modules.maphost import http_get
    from memento_win.core import mapcache, tuileries
    fake = FakeTuileries()
    try:
        fake.answers["/t/ortho/15/1/1"] = (200, "image/webp", WEBP)
        source = tuileries.Tuileries(fake.url, USER, PASSWORD, "cid", "csec")
        assert http_get(source.tile_url(mapcache.TileRequest("ortho", 15, 1, 1)), source.headers()) == (200, "image/webp", WEBP)
        assert fake.requests[-1][1]["cf-access-client-secret"] == "csec"
        assert http_get(fake.url + "api/info")[0] == 401  # without them: refused
    finally:
        fake.close()


def test_one_metatile_fills_the_caches_of_its_area(tiles_client, net, folders):
    import gzip
    from fake_tuileries import build_meta
    x0, y0 = 67764, 46680
    cell = json.dumps({"v": 2, "z": 15, "x": 16941, "y": 11670, "o": [0, 0], "b": [], "c": []}).encode()
    photos = [(0, 17, x, y, 3 if (x, y) == (x0 + 1, y0) else 1 if (x, y) == (x0 + 2, y0) else 0,
               0 if (x, y) in ((x0 + 1, y0), (x0 + 2, y0)) else 1, 0,
               b"" if (x, y) in ((x0 + 1, y0), (x0 + 2, y0)) else WEBP + bytes([x % 256]))
              for y in range(y0, y0 + 4) for x in range(x0, x0 + 4)]
    meta = build_meta(17, x0, y0, photos + [(1, 13, 4235, 2917, 0, 2, 0, b"\x89PNG dem"),
                                            (2, 15, 16941, 11670, 0, 4, 1, gzip.compress(cell)),
                                            (3, 15, 16941, 11670, 0, 5, 1, gzip.compress(b"\x1a\x08trails"))])
    net.answers[f"https://tuileries.example.org/meta/17/{x0}/{y0}"] = (200, "application/x-tuileries-metatile", meta)
    net.answers["https://tuileries.example.org/t/ortho/"] = (200, "image/avif", b"avif photo")
    first = tiles_client.get(f"/t/ortho/17/{x0 + 3}/{y0 + 3}")
    assert first.headers["content-type"] == "image/webp" and first.content == WEBP + bytes([(x0 + 3) % 256])
    for path, body in ((f"/t/ortho/17/{x0}/{y0 + 1}", WEBP + bytes([x0 % 256])), ("/t/dem/13/4235/2917", b"\x89PNG dem"),
                       ("/buildings/15/16941/11670", cell), ("/trails/15/16941/11670", b"\x1a\x08trails")):
        assert tiles_client.get(path).content == body, path
    assert tiles_client.get(f"/t/ortho/17/{x0 + 2}/{y0}").status_code == 404  # no tile there: not asked again
    assert len(net.calls) == 1  # one request for all of it
    pending = tiles_client.get(f"/t/ortho/17/{x0 + 1}/{y0}")  # not ready in the metatile: asked for alone
    assert pending.headers["content-type"] == "image/avif" and net.calls[-1].endswith(f"/t/ortho/17/{x0 + 1}/{y0}")
    assert (folders[1] / "tiles" / "orthoa" / "17" / str(x0 + 1) / f"{y0}.avif").read_bytes() == b"avif photo"


def test_a_tuileries_without_metatiles_gets_single_tiles(tiles_client, net):
    net.answers["https://tuileries.example.org/meta/"] = (404, "text/plain", b"none")
    net.answers["https://tuileries.example.org/t/ortho/"] = (200, "image/webp", WEBP)
    tiles_client.get("/t/ortho/17/4/4")
    tiles_client.get("/t/ortho/17/8/8")
    assert [u.split("/")[3] for u in net.calls] == ["meta", "t", "t"]
