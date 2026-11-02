from __future__ import annotations

import json
import time
from pathlib import Path
from urllib.parse import unquote

import pytest

import tracks
from conftest import HEADERS

FIT = (Path(__file__).resolve().parents[2] / "windows" / "tests" / "data" / "synthetic.fit").read_bytes()
T0 = 1_790_000_000_000


def upload(client, data: bytes, name: str = "sortie.fit"):
    return client.post("/api/activities/uploads", params={"name": name}, content=data,
                       headers={**HEADERS, "Content-Type": "application/octet-stream"})


def wait(client, job: dict) -> dict:
    deadline = time.monotonic() + 20
    while not job["finished"]:
        assert time.monotonic() < deadline, job
        time.sleep(0.02)
        job = client.get(f"/api/activities/jobs/{job['id']}").json()
    return job


def import_file(client, data: bytes, name: str = "sortie.fit", **overrides) -> dict:
    staged = upload(client, data, name)
    assert staged.status_code == 200, staged.text
    item = {"token": staged.json()["token"], "name": name, "title": staged.json()["title"],
            "sport": staged.json()["sport"], "altitude": False, **overrides}
    started = client.post("/api/activities/imports", json={"items": [item]}, headers=HEADERS)
    assert started.status_code == 202, started.text
    job = wait(client, started.json())
    assert not job["error"] and "error" not in job["results"][0], job
    return job["results"][0]


def put(client, key, version, **fields):
    return client.put(f"/api/activities/{key}", json=fields, headers={**HEADERS, "If-Match": version})


def fake_elevation(calls):
    def get(url: str) -> str:
        calls.append(url)
        locations = unquote(url.split("locations=", 1)[1]).split("|")
        return json.dumps({"status": "OK", "results": [{"elevation": 900.0 + 3 * i} for i in range(len(locations))]})
    return get


def test_the_list_is_empty_without_the_folder(client):
    body = client.get("/api/activities").json()
    assert body["activities"] == [] and "walk" in body["totals"]
    assert {s["key"] for s in body["sports"]} >= {"walk", "ski", "plain"}


def test_an_upload_is_read_but_not_written_to_the_folder(client, data_dir):
    staged = upload(client, FIT).json()
    assert staged["sport"] == "bike" and staged["existing"] is None and not staged["needs_altitude"]
    assert staged["points"] == 90 and staged["title"].startswith("Vélo du ")
    assert not (data_dir / "activities").exists() and not (data_dir / "attachments").exists()


def test_an_unreadable_upload_is_refused_and_not_kept(client, folders):
    response = upload(client, "pas un tracé".encode(), "notes.txt")
    assert response.status_code == 422 and "notes.txt" in response.json()["error"]
    assert not list((folders[1] / "uploads").iterdir())


def test_import_creates_the_note_and_copies_the_track(client, data_dir):
    result = import_file(client, FIT, title="Tour à vélo")
    assert result["title"] == "Tour à vélo" and not result["replaced"]
    listed = client.get("/api/activities").json()["activities"]
    assert len(listed) == 1
    a = listed[0]
    assert a["id"] == result["id"] and a["sport"] == "bike" and a["has_file"] and a["distance"] > 0
    note = (data_dir / a["path"]).read_text(encoding="utf-8")
    assert "activity: {" in note and '"sport": "bike"' in note
    detail = client.get(f"/api/activities/{a['id']}").json()
    assert (data_dir / detail["activity"]["file"]).read_bytes() == FIT
    assert detail["activity"]["photos"] == [] and len(detail["version"]) == 24


def test_reimporting_the_same_recording_replaces_it_keeping_name_and_description(client, data_dir):
    first = import_file(client, FIT, title="Tour à vélo")
    version = client.get(f"/api/activities/{first['id']}").json()["version"]
    assert put(client, first["id"], version, description="Belle sortie").status_code == 200
    staged = upload(client, FIT).json()
    assert staged["existing"]["id"] == first["id"] and staged["title"] == "Tour à vélo"
    again = import_file(client, FIT, title="")
    assert again["replaced"] and again["id"] == first["id"]
    listed = client.get("/api/activities").json()["activities"]
    assert len(listed) == 1
    assert client.get(f"/api/activities/{first['id']}").json()["activity"]["description"] == "Belle sortie"


def test_several_files_import_in_one_background_job(client):
    items = []
    for n, text in enumerate((tracks.walk(T0, 1200), tracks.walk(T0 + 86_400_000, 900, name="Balade"))):
        staged = upload(client, text.encode(), f"t{n}.gpx").json()
        items.append({"token": staged["token"], "name": f"t{n}.gpx", "title": staged["title"], "sport": "walk"})
    job = wait(client, client.post("/api/activities/imports", json={"items": items}, headers=HEADERS).json())
    assert job["done"] == 2 and [r["title"] for r in job["results"]] == ["Tour du lac", "Balade"]
    assert len(client.get("/api/activities").json()["activities"]) == 2
    # the staged file is gone: importing it again is refused
    again = client.post("/api/activities/imports", json={"items": items[:1]}, headers=HEADERS)
    assert again.status_code == 410


def test_walk_analysis_has_a_capped_pace_chart_stops_and_a_coloured_track(client):
    result = import_file(client, tracks.walk(T0, 3000).encode(), "walk.gpx", sport="walk")
    a = client.get(f"/api/activities/{result['id']}/analysis").json()
    chart = a["chart"]
    assert a["sport"] == "walk" and len(chart["dist"]) == 400 and chart["alt"] and chart["hr"]
    assert chart["pace"] and max(chart["pace"]) == 1200.0  # stops are capped at 20 min/km
    assert min(chart["pace"]) < 900
    assert [s["manual"] for s in a["stops"]] == [False, True, False]  # two stops, the watch's pause between
    m = a["map"]
    assert len(m["lon"]) == len(m["speeds"]) == 3000 and m["avg"] > 1 and "kind" not in m
    assert m["dots"][0]["color"] == "#00C853"
    assert a["stats"]["distance"] > 3000 and a["quality"]["has_altitude"]


def test_ski_analysis_has_runs_climbs_and_the_day_square(client):
    result = import_file(client, tracks.ski(T0).encode(), "ski.gpx", sport="ski")
    summary = next(x for x in client.get("/api/activities").json()["activities"] if x["id"] == result["id"])
    assert summary["runs"] == 6 and summary["climbs"] == 6 and summary["drop"] > 3000
    a = client.get(f"/api/activities/{result['id']}/analysis").json()
    assert len(a["runs"]) == 6 and len(a["climbs"]) == 6 and a["chart"]["pace"] is None
    m = a["map"]
    assert set(m["kind"]) == {"d", "u", "o"} and len(m["kind"]) == len(m["lon"]) == len(m["t"])
    assert len(m["square"]) == 4 and "speeds" not in m


def test_the_analysis_is_computed_once_then_cached_until_its_inputs_change(client):
    cache = client.app.state.activities.cache
    result = import_file(client, tracks.walk(T0, 600).encode(), "w.gpx", sport="walk")
    url = f"/api/activities/{result['id']}/analysis"
    first = client.get(url).json()
    assert cache.computed == 1
    assert client.get(url).json() == first and cache.computed == 1
    cache.drop_memory()  # the idle hook: read back from /config
    assert client.get(url).json() == first and cache.computed == 1
    detail = client.get(f"/api/activities/{result['id']}").json()
    key = detail["activity"]["analysis_key"]
    changed = put(client, result["id"], detail["version"], title="Autre nom").json()
    assert changed["activity"]["analysis_key"] == key  # a new name changes nothing in the analysis
    changed = put(client, result["id"], changed["version"], sport="bike").json()
    assert changed["activity"]["analysis_key"] != key
    assert client.get(url).json()["sport"] == "bike" and cache.computed == 2


def test_the_analysis_cache_is_bounded_in_memory_and_on_disk(client, folders):
    cache = client.app.state.activities.cache
    cache.max_items, cache.max_bytes, cache.disk_items = 2, 10**9, 3
    ids = [import_file(client, tracks.walk(T0 + n * 86_400_000, 300 + n).encode(), f"{n}.gpx", sport="walk")["id"]
           for n in range(5)]
    for i in ids:
        assert client.get(f"/api/activities/{i}/analysis").status_code == 200
    assert len(cache.memory) == 2
    assert len(list((folders[1] / "cache" / "activities").glob("*.json"))) == 3
    cache.max_bytes = 1
    client.get(f"/api/activities/{ids[0]}/analysis")
    assert len(cache.memory) == 0  # one analysis larger than the budget is answered, not kept
    data = client.app.state.data
    client.get(f"/api/activities/{ids[1]}/analysis")
    cache.max_bytes = 10**9
    client.get(f"/api/activities/{ids[1]}/analysis")
    assert cache.memory and data.trim_if_idle(now=data.last_request + 121) and not cache.memory


def test_edits_need_the_version_and_refuse_a_stale_one(client):
    result = import_file(client, FIT)
    version = client.get(f"/api/activities/{result['id']}").json()["version"]
    no_version = client.put(f"/api/activities/{result['id']}", json={"title": "x"}, headers=HEADERS)
    assert no_version.status_code == 428
    ok = put(client, result["id"], version, title="Nouveau nom", description="Avec du vent")
    assert ok.status_code == 200 and ok.json()["activity"]["title"] == "Nouveau nom"
    stale = put(client, result["id"], version, title="Encore")
    assert stale.status_code == 409 and stale.json()["current"]["activity"]["title"] == "Nouveau nom"
    assert put(client, result["id"], ok.json()["version"], sport="plain").status_code == 400
    assert put(client, result["id"], ok.json()["version"], distance=1000).status_code == 400
    assert put(client, result["id"], ok.json()["version"], colour="red").status_code == 400


def test_a_manual_distance_for_an_activity_without_track(client, data_dir):
    (data_dir / "activities").mkdir()
    (data_dir / "activities" / "yoga-p1p2p3.md").write_text(
        '---\nid: p1p2p3\ntitle: Yoga\nactivity: {"sport": "plain", "start": 1790000000000, "elapsed": 3600, '
        '"moving": 0, "distance": 0.0, "ascent": 0, "descent": 0}\ncreated: 2026-10-01T10:00:00+02:00\n'
        'updated: 2026-10-01T10:00:00+02:00\n---\n', encoding="utf-8")
    detail = client.get("/api/activities/p1p2p3").json()
    assert detail["activity"]["analysis_key"] is None
    assert client.get("/api/activities/p1p2p3/analysis").status_code == 404
    saved = put(client, "p1p2p3", detail["version"], distance=5250.5)
    assert saved.status_code == 200 and saved.json()["activity"]["distance"] == 5250.5
    assert put(client, "p1p2p3", saved.json()["version"], sport="ski").status_code == 400


def test_delete_removes_the_track_and_photos_and_undo_brings_them_back(client, data_dir):
    result = import_file(client, FIT)
    detail = client.get(f"/api/activities/{result['id']}").json()
    attached = client.post(f"/api/notes/{detail['activity']['path']}/attachments", params={"name": "vue.jpg"},
                           content=b"\xff\xd8 jpeg", headers={**HEADERS, "If-Match": detail["version"]})
    assert attached.status_code == 200
    detail = client.get(f"/api/activities/{result['id']}").json()
    photo = detail["activity"]["photos"][0]
    files = [detail["activity"]["path"], detail["activity"]["file"], photo]
    stale = client.delete(f"/api/activities/{result['id']}", headers={**HEADERS, "If-Match": "old"})
    assert stale.status_code == 409
    token = client.delete(f"/api/activities/{result['id']}", headers={**HEADERS, "If-Match": detail["version"]}).json()["undo"]
    assert not any((data_dir / f).exists() for f in files)
    assert client.get(f"/api/activities/{result['id']}").status_code == 404
    assert client.post(f"/api/undo/{token}", headers=HEADERS).status_code == 200
    assert all((data_dir / f).exists() for f in files)
    assert client.get(f"/api/activities/{result['id']}").json()["activity"]["photos"] == [photo]


def test_a_conflict_copy_is_opened_by_its_file_name(client, data_dir):
    result = import_file(client, FIT)
    path = data_dir / result["path"]
    copy = path.with_name(path.stem + "-conflict-2026.md")
    copy.write_text(path.read_text(encoding="utf-8").replace(f"title: {result['title']}", "title: Copie"), encoding="utf-8")
    assert client.get(f"/api/activities/{result['id']}").json()["activity"]["title"] == result["title"]
    by_name = client.get(f"/api/activities/{copy.name}").json()["activity"]
    assert by_name["title"] == "Copie" and by_name["conflict"]
    assert client.get("/api/activities/..%2Fcourses-abc123.md").status_code in (400, 404)


def test_altitude_completion_is_explicit_and_uses_the_terrain_model(client, data_dir):
    activities = client.app.state.activities
    calls = []
    activities.elevation_get, activities.elevation_pause = fake_elevation(calls), lambda: None
    flat = tracks.walk(T0, 1500, altitude=False).encode()
    staged = upload(client, flat, "flat.gpx").json()
    assert staged["needs_altitude"]
    plain = import_file(client, flat, "flat.gpx", sport="walk")
    assert calls == []  # not asked: nothing sent
    a = client.get(f"/api/activities/{plain['id']}/analysis").json()
    assert not a["quality"]["has_altitude"] and a["chart"]["alt"] is None
    job = client.post("/api/activities/altitude", json={"ids": [plain["id"]]}, headers=HEADERS)
    assert job.status_code == 202
    job = wait(client, job.json())
    assert job["error"] is None and len(job["results"]) == 1 and calls
    detail = client.get(f"/api/activities/{plain['id']}").json()["activity"]
    assert detail["alt"].endswith(".alt") and (data_dir / detail["alt"]).is_file() and detail["ascent"] > 0
    a = client.get(f"/api/activities/{plain['id']}/analysis").json()
    assert a["quality"]["has_altitude"] and a["quality"]["alt_source"] == detail["alt"] and a["chart"]["alt"]
    again = wait(client, client.post("/api/activities/altitude", json={"all": True}, headers=HEADERS).json())
    assert again["results"] == [] and again["text"].startswith("0 complétée")


def test_a_terrain_service_failure_imports_without_altitude(client):
    activities = client.app.state.activities

    def offline(url):
        from memento_win.core.elevation import ElevationError
        raise ElevationError("Service d'altitude indisponible : hors ligne")
    activities.elevation_get = offline
    result = import_file(client, tracks.walk(T0, 600, altitude=False).encode(), "f.gpx", sport="walk", altitude=True)
    assert "hors ligne" in result["warning"]
    assert client.get(f"/api/activities/{result['id']}").json()["activity"]["alt"] is None


@pytest.mark.parametrize("path", ["/api/activities/inconnu", "/api/activities/inconnu/analysis",
                                  "/api/activities/jobs/inconnu"])
def test_unknown_things_are_404(client, path):
    assert client.get(path).status_code == 404


def test_activities_need_a_session(make_client):
    assert make_client().get("/api/activities").status_code == 401
