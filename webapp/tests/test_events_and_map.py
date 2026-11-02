from __future__ import annotations

import asyncio
import json

import pytest

from memento_web.events import AREAS, HEARTBEAT, Watcher, changed_areas
from memento_web.modules.maphost import TileStore


class FakeData:
    def __init__(self, store):
        self.store = store

    def trim_if_idle(self):
        return False


def test_changed_areas_names_each_part_of_the_snapshot():
    before = ((("a.md", 1, 1),), (), (), ())
    after = ((("a.md", 2, 1),), (), (("2026/x.md", 1, 1),), ())
    assert changed_areas(before, after) == ["notes", "agenda"]
    assert changed_areas(None, after) == []
    assert AREAS == ("notes", "activities", "agenda", "contacts")


def test_watcher_publishes_a_change_when_a_sync_writes_a_file(data_dir):
    from memento_web.core import Store

    async def scenario():
        watcher = Watcher(FakeData(Store(data_dir)), interval=60)
        await watcher.start()
        queue: asyncio.Queue = asyncio.Queue()
        watcher._subscribers.add(queue)
        (data_dir / "contacts").mkdir()
        (data_dir / "contacts" / "dupont-marie-p4x8q1.md").write_text("---\nid: p4x8q1\n---\n")
        (data_dir / "nouvelle-zzz999.md").write_text("---\nid: zzz999\n---\n")
        watcher.poke()
        message = await asyncio.wait_for(queue.get(), 5)
        await watcher.stop()
        return message
    message = asyncio.run(scenario())
    assert message.startswith("event: change\n")
    assert json.loads(message.split("data: ", 1)[1]) == {"areas": ["notes", "contacts"]}


def test_sse_stream_says_hello_and_relays_changes(client, data_dir):
    with client.stream("GET", "/api/events") as stream:
        assert stream.headers["content-type"].startswith("text/event-stream")
        lines = stream.iter_lines()
        assert next(lines) == "retry: 3000"
        assert next(lines) == "event: hello"
        next(lines), next(lines)
        (data_dir / "depuis-le-telephone-qqq111.md").write_text("---\nid: qqq111\ntitle: Du téléphone\n---\n")
        assert next(lines) == "event: change"
        assert json.loads(next(lines).removeprefix("data: ")) == {"areas": ["notes"]}


def test_heartbeat_is_well_under_cloudflare_s_100_seconds():
    assert HEARTBEAT <= 25


# ---- map host ----

def test_dem_tile_is_fetched_once_then_served_from_the_cache(client, net, folders):
    first = client.get("/t/dem/10/530/360")
    assert first.status_code == 200 and first.headers["content-type"] == "image/png"
    assert net.calls == ["https://s3.amazonaws.com/elevation-tiles-prod/terrarium/10/530/360.png"]
    assert client.get("/t/dem/10/530/360").content == first.content
    assert len(net.calls) == 1
    assert (folders[1] / "tiles" / "dem" / "10" / "530" / "360.png").is_file()


def test_ortho_falls_back_to_openstreetmap_where_ign_has_no_photo(client, net):
    net.answers["https://data.geopf.fr/wmts"] = (404, "application/xml", b"<ExceptionReport/>")
    response = client.get("/t/ortho/14/8000/5000")
    assert response.status_code == 200 and response.headers["content-type"] == "image/png"
    assert net.calls[-1] == "https://tile.openstreetmap.org/14/8000/5000.png"
    client.get("/t/ortho/14/8000/5000")
    assert len(net.calls) == 2  # the stand-in is cached too


def test_ign_photo_is_served_as_jpeg(client, net):
    net.answers["https://data.geopf.fr/wmts"] = (200, "image/jpeg", b"\xff\xd8" + b"x" * 5000)
    response = client.get("/t/ortho/12/2120/1460")
    assert response.headers["content-type"] == "image/jpeg"


def test_offline_tiles_fail_fast_with_backoff(client, net):
    net.offline = True
    assert client.get("/t/dem/5/1/1").status_code == 404
    assert client.get("/t/dem/5/1/2").status_code == 404
    assert len(net.calls) == 1  # the second did not wait for its own timeout


@pytest.mark.parametrize("path", ["/t/dem/16/0/0", "/t/ortho/3/8/0", "/t/plan/1/0/0", "/t/dem/a/b/c"])
def test_invalid_tiles_are_refused(client, path, net):
    assert client.get(path).status_code == 404
    assert net.calls == []


def test_peaks_are_fetched_with_heights_cached_and_selected(client, net):
    features = {"features": [
        {"properties": {"toponyme": "Pic Synthétique", "importance": "1"}, "geometry": {"coordinates": [6.05, 45.05]}},
        {"properties": {"toponyme": "Dent Imaginaire", "importance": "3"}, "geometry": {"coordinates": [6.02, 45.08]}},
    ]}
    net.answers["https://data.geopf.fr/wfs"] = (200, "application/json", json.dumps(features).encode())
    net.answers["https://data.geopf.fr/altimetrie"] = (200, "application/json", b'{"elevations": [2100.4, 1890.0]}')
    found = client.get("/peaks", params={"w": 6.0, "s": 45.0, "e": 6.1, "n": 45.1}).json()
    assert {p["name"] for p in found} == {"Pic Synthétique", "Dent Imaginaire"}
    calls = len(net.calls)
    client.get("/peaks", params={"w": 6.0, "s": 45.0, "e": 6.1, "n": 45.1})
    assert len(net.calls) == calls


def test_peaks_of_a_huge_box_are_not_fetched(client, net):
    assert client.get("/peaks", params={"w": 0, "s": 40, "e": 5, "n": 45}).json() == []
    assert net.calls == []


def test_map_page_is_served_and_may_be_framed_by_the_site_only(client):
    response = client.get("/map/index.html")
    assert response.status_code == 200 and "maplibre" in response.text
    assert "frame-ancestors 'self'" in response.headers["content-security-policy"]
    assert response.headers["x-frame-options"] == "SAMEORIGIN"
    assert client.get("/map/..%2F..%2Fsecret").status_code == 404


def test_concurrent_requests_for_one_tile_share_a_fetch(tmp_path):
    import threading
    calls = []
    gate = threading.Event()

    def slow(url):
        calls.append(url)
        gate.wait(2)
        return 200, "image/png", b"png"
    store = TileStore(tmp_path, slow)
    from memento_web.core import mapcache
    tile = mapcache.TileRequest("dem", 3, 1, 1)
    results = []
    threads = [threading.Thread(target=lambda: results.append(store.get(tile))) for _ in range(4)]
    for t in threads:
        t.start()
    gate.set()
    for t in threads:
        t.join()
    assert len(calls) == 1 and all(r == ("image/png", b"png") for r in results)


def _cell_page(n: int) -> bytes:
    from memento_web.core import mapcache  # noqa: F401 (puts memento_win on the path)
    from memento_win.core import buildings
    w, s, e, nn = buildings.cell_box(16941, 11670)
    lon, lat = (w + e) / 2, (s + nn) / 2
    ring = [[lon, lat], [lon + 1e-4, lat], [lon + 1e-4, lat + 1e-4], [lon, lat + 1e-4], [lon, lat]]
    return json.dumps({"features": [{"id": f"b{i}", "properties": {"hauteur": 9.5},
                                     "geometry": {"type": "Polygon", "coordinates": [ring]}} for i in range(n)]}).encode()


def test_buildings_cell_is_fetched_slimmed_cached_and_served(client, net, folders):
    net.answers["https://data.geopf.fr/wfs"] = (200, "application/json", _cell_page(2))
    response = client.get("/buildings/15/16941/11670")
    assert response.status_code == 200 and response.headers["content-type"] == "application/json"
    doc = response.json()
    assert (doc["v"], doc["z"], doc["x"], doc["y"]) == (1, 15, 16941, 11670) and [b[0] for b in doc["b"]] == [9.5, 9.5]
    assert len(net.calls) == 1 and "TYPENAMES=BDTOPO_V3:batiment" in net.calls[0]
    assert client.get("/buildings/15/16941/11670").content == response.content and len(net.calls) == 1
    assert (folders[1] / "tiles" / "buildings" / "15" / "16941" / "11670.json").is_file()


def test_buildings_errors_are_not_cached_and_offline_is_a_404(client, net):
    net.answers["https://data.geopf.fr/wfs"] = (200, "text/xml", b"<ows:ExceptionReport/>")
    assert client.get("/buildings/15/16941/11670").status_code == 404
    net.answers["https://data.geopf.fr/wfs"] = (200, "application/json", _cell_page(1))
    assert client.get("/buildings/15/16941/11670").status_code == 200  # fetched again: the error was not kept
    net.offline = True
    assert client.get("/buildings/15/16941/11671").status_code == 404


@pytest.mark.parametrize("path", ["/buildings/14/1/1", "/buildings/15/32768/0", "/buildings/15/a/1"])
def test_invalid_building_cells_are_refused(client, path, net):
    assert client.get(path).status_code == 404
    assert net.calls == []


def test_an_ign_refusal_is_not_replaced_by_a_cached_osm_tile(tmp_path):
    from memento_web.core import mapcache
    (tmp_path / "osm" / "17").mkdir(parents=True)  # stand-ins cached by build 73 for IGN refusals
    answers = {"ign": (429, "text/html", b"slow down")}
    calls = []

    def fetch(url):
        calls.append(url)
        return answers["ign"] if "geopf" in url else (200, "image/png", b"osm")
    store = TileStore(tmp_path, fetch)
    assert not (tmp_path / "osm").exists()
    tile = mapcache.TileRequest("ortho", 17, 67764, 46682)
    assert store.get(tile) is None and len(calls) == 1
    answers["ign"] = (200, "image/jpeg", b"\xff\xd8photo")
    assert store.get(tile) == ("image/jpeg", b"\xff\xd8photo")
