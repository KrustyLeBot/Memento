from __future__ import annotations

import io
import subprocess
import sys
import time
import zipfile
from datetime import date
from pathlib import Path

import pytest

from conftest import HEADERS

TESTDATA = Path(__file__).resolve().parents[2] / "testdata" / "agenda"
TZ = {"tz": "Europe/Paris"}


def create(client, **fields):
    response = client.post("/api/agenda/events", params=TZ, json=fields, headers=HEADERS)
    assert response.status_code == 201, response.text
    return response.json()["event"]


def entries(client, first, last, tz="Europe/Paris"):
    response = client.get("/api/agenda/entries", params={"from": first, "to": last, "tz": tz})
    assert response.status_code == 200, response.text
    return response.json()["entries"]


def put(client, path, version, params=None, **fields):
    return client.put(f"/api/agenda/events/{path}", params={**TZ, **(params or {})}, json=fields,
                      headers={**HEADERS, "If-Match": version})


def no_temporaries(folder):
    return not [p for p in folder.rglob("*") if ".synctmp-" in p.name or p.name.endswith(".tmp")]


def test_create_read_and_list_an_event(client, data_dir):
    ev = create(client, title="Dentiste", start="2026-11-12T14:30", end="2026-11-12T15:15", tz="Europe/Paris",
                location="Lyon", alerts=[-15], body="Apporter la carte https://exemple.fr/rdv")
    assert ev["path"].startswith("agenda/2026/dentiste-") and ev["uid"] == f"{ev['id']}@memento"
    text = (data_dir / ev["path"]).read_text(encoding="utf-8")
    assert "start: 2026-11-12T14:30" in text and "tz: Europe/Paris" in text and "alerts: [-15m]" in text
    assert "calendar: perso" in text
    got = client.get(f"/api/agenda/events/{ev['path']}", params=TZ).json()["event"]
    assert got["links"] == [{"url": "https://exemple.fr/rdv", "label": "exemple.fr/rdv"}] and got["rich"]
    shown = entries(client, "2026-11-09", "2026-11-15")
    assert [(e["kind"], e["title"], e["subtitle"]) for e in shown] == [("event", "Dentiste", "Lyon")]
    # 13:30 UTC; seen from New York the same entry is at 08:30 local
    assert shown[0]["start"] == 1794490200000 and shown[0]["occurrence"]["start"] == "2026-11-12T14:30"
    assert entries(client, "2026-11-12", "2026-11-12", tz="America/New_York")[0]["start_date"] == "2026-11-12"
    assert no_temporaries(data_dir)


def test_links_put_the_meeting_first(client):
    ev = create(client, title="Visio", start="2026-11-12T10:00", end="2026-11-12T11:00", tz="Europe/Paris",
                body="Doc : https://exemple.fr/doc\nRejoindre : https://meet.google.com/abc-defg-hij")
    labels = [x["label"] for x in client.get(f"/api/agenda/events/{ev['path']}", params=TZ).json()["event"]["links"]]
    assert labels == ["Rejoindre la visio (Google Meet)", "exemple.fr/doc"]


def test_bad_input_is_refused_in_french(client):
    response = client.post("/api/agenda/events", params=TZ, headers=HEADERS,
                           json={"title": "x", "start": "2026-11-12T14:30", "end": "2026-11-12T13:00"})
    assert response.status_code == 400 and response.json()["error"] == "La fin est avant le début."
    response = client.post("/api/agenda/events", params=TZ, headers=HEADERS,
                           json={"title": "Vol", "start": "2026-12-17T10:00", "end": "2026-12-17T03:00",
                                 "tz": "Europe/Paris", "end_tz": "America/New_York"})
    assert response.status_code == 400 and "New York" in response.json()["error"]
    assert client.post("/api/agenda/events", params=TZ, headers=HEADERS,
                       json={"start": "demain"}).status_code == 400


def test_update_needs_the_version_and_refuses_a_stale_one(client, data_dir):
    ev = create(client, title="Réunion", start="2026-11-12T09:00", end="2026-11-12T10:00", tz="Europe/Paris")
    assert client.put(f"/api/agenda/events/{ev['path']}", json={"title": "x"}, headers=HEADERS).status_code == 428
    target = data_dir / ev["path"]
    target.write_text(target.read_text(encoding="utf-8").replace("Réunion", "Réunion (téléphone)"), encoding="utf-8")
    response = put(client, ev["path"], ev["version"], title="Ma version")
    assert response.status_code == 409 and response.json()["current"]["title"] == "Réunion (téléphone)"
    current = response.json()["current"]
    response = put(client, ev["path"], current["version"], title="Ma version", start="2026-11-13T09:00",
                   end="2026-11-13T10:30", tz="Europe/Paris")
    assert response.status_code == 200
    text = target.read_text(encoding="utf-8")
    assert "title: Ma version" in text and "end: 2026-11-13T10:30" in text


def test_series_expansion_scopes_delete_undo_and_restore(client, data_dir):
    ev = create(client, title="Yoga", start="2026-11-05T18:00", end="2026-11-05T19:00", tz="Europe/Paris",
                rrule="FREQ=WEEKLY;BYDAY=TH")
    occ = client.get(f"/api/agenda/occurrences/{ev['path']}", params={"from": "2026-11-01", "to": "2026-11-30", **TZ}).json()
    assert [o["start"] for o in occ["occurrences"]] == ["2026-11-05T18:00", "2026-11-12T18:00", "2026-11-19T18:00",
                                                        "2026-11-26T18:00"]
    assert put(client, ev["path"], ev["version"], title="x").status_code == 400  # a series needs a scope

    # "cet événement": an exception file, the occurrence skipped by the series
    response = put(client, ev["path"], ev["version"], {"scope": "one", "occurrence": "2026-11-12T18:00"},
                   title="Yoga (salle B)", start="2026-11-12T19:00", end="2026-11-12T20:00", tz="Europe/Paris")
    assert response.status_code == 200, response.text
    exc = response.json()["event"]
    assert exc["recurrence_of"] == ev["id"] and exc["recurrence_at"] == "2026-11-12T18:00" and not exc["rrule"]
    assert "exdate: [2026-11-12T18:00]" in (data_dir / ev["path"]).read_text(encoding="utf-8")
    titles = [(e["title"], e["occurrence"]["start"]) for e in entries(client, "2026-11-09", "2026-11-15")]
    assert titles == [("Yoga (salle B)", "2026-11-12T19:00")]

    # "Rétablir l'original", then its undo
    response = client.post(f"/api/agenda/restore/{exc['path']}", params=TZ, headers={**HEADERS, "If-Match": exc["version"]})
    assert response.status_code == 200
    assert not (data_dir / exc["path"]).exists()
    assert [e["title"] for e in entries(client, "2026-11-09", "2026-11-15")] == ["Yoga"]
    assert client.post(f"/api/undo/{response.json()['undo']}", headers=HEADERS).status_code == 200
    assert (data_dir / exc["path"]).exists()

    # delete one occurrence -> exdate; then the whole series, undone
    series = client.get(f"/api/agenda/events/{ev['path']}", params=TZ).json()["event"]
    response = client.delete(f"/api/agenda/events/{ev['path']}", params={"scope": "one", "occurrence": "2026-11-19T18:00", **TZ},
                             headers={**HEADERS, "If-Match": series["version"]})
    assert response.json()["message"] == "Occurrence supprimée"
    assert [e["occurrence"]["start"] for e in entries(client, "2026-11-16", "2026-11-22")] == []
    series = client.get(f"/api/agenda/events/{ev['path']}", params=TZ).json()["event"]
    response = client.delete(f"/api/agenda/events/{ev['path']}", params={"scope": "all", **TZ},
                             headers={**HEADERS, "If-Match": series["version"]})
    assert response.json()["message"] == "Série supprimée"
    assert not (data_dir / ev["path"]).exists() and not (data_dir / exc["path"]).exists()
    assert client.post(f"/api/undo/{response.json()['undo']}", headers=HEADERS).status_code == 200
    assert (data_dir / ev["path"]).exists() and (data_dir / exc["path"]).exists()


def test_moving_a_whole_series_moves_its_exceptions(client, data_dir):
    ev = create(client, title="Cours", start="2026-11-02T10:00", end="2026-11-02T11:00", tz="Europe/Paris",
                rrule="FREQ=DAILY;COUNT=5")
    exc = put(client, ev["path"], ev["version"], {"scope": "one", "occurrence": "2026-11-03T10:00"},
              title="Cours (déplacé)", start="2026-11-03T15:00", end="2026-11-03T16:00", tz="Europe/Paris").json()["event"]
    series = client.get(f"/api/agenda/events/{ev['path']}", params=TZ).json()["event"]
    response = put(client, ev["path"], series["version"], {"scope": "all"}, start="2026-11-02T11:00",
                   end="2026-11-02T12:00", tz="Europe/Paris")
    assert response.status_code == 200
    assert response.json()["event"]["exdate"] == ["2026-11-03T11:00"]
    assert "recurrence_at: 2026-11-03T11:00" in (data_dir / exc["path"]).read_text(encoding="utf-8")
    starts = [e["occurrence"]["start"] for e in entries(client, "2026-11-02", "2026-11-08")]
    assert starts == ["2026-11-02T11:00", "2026-11-03T15:00", "2026-11-04T11:00", "2026-11-05T11:00", "2026-11-06T11:00"]


def test_rule_field_presets_custom_and_imported(client):
    rule = client.post("/api/agenda/rule", params=TZ, headers=HEADERS,
                       json={"start": "2026-11-26T18:00", "tz": "Europe/Paris", "preset": "monthly_last"}).json()
    assert rule["rrule"] == "FREQ=MONTHLY;BYDAY=-1TH" and rule["preset"] == "monthly_last"
    assert "Tous les mois le dernier jeudi" in [p["label"] for p in rule["presets"]]
    assert rule["preview"].startswith("Prochaines : 26 nov.")
    # the start moves to a day that is no last Thursday: the preset follows as the day of the month
    moved = client.post("/api/agenda/rule", params=TZ, headers=HEADERS,
                        json={"start": "2026-11-12T18:00", "tz": "Europe/Paris", "preset": "monthly_last"}).json()
    assert moved["rrule"] == "FREQ=MONTHLY;BYMONTHDAY=12"
    custom = client.post("/api/agenda/rule", params=TZ, headers=HEADERS, json={
        "start": "2026-11-02T09:00", "tz": "Europe/Paris",
        "spec": {"freq": "WEEKLY", "interval": 2, "weekdays": [0, 3], "until": "2026-12-31"}}).json()
    assert custom["rrule"] == "FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,TH;UNTIL=20261231T225959Z"
    assert custom["preset"] == "custom" and custom["description"].startswith("Toutes les 2 semaines le lundi et le jeudi")
    imported = client.post("/api/agenda/rule", params=TZ, headers=HEADERS,
                           json={"start": "2026-11-02T09:00", "rrule": "FREQ=DAILY;BYHOUR=9,17"}).json()
    assert imported["preset"] == "imported" and imported["description"] == "Règle importée : FREQ=DAILY;BYHOUR=9,17"


def test_calendars_create_rename_default_layers_and_delete_with_move(client, data_dir):
    cal = client.get("/api/agenda/calendars").json()
    assert cal["version"] is None and [c["id"] for c in cal["calendars"]] == ["perso"]
    change = lambda version, **body: client.put("/api/agenda/calendars", json=body,  # noqa: E731
                                                headers={**HEADERS, "If-Match": version or "none"})
    cal = change(None, op="create", name="Travail").json()
    assert cal["created"] == "travail" and cal["calendars"][1]["color"] != cal["calendars"][0]["color"]
    assert change(None, op="default", id="travail").status_code == 409  # stale version
    cal = change(cal["version"], op="default", id="travail").json()
    cal = change(cal["version"], op="layer", key="birthdays", visible=False).json()
    assert cal["default"] == "travail" and not cal["layers"]["birthdays"]["visible"]
    assert cal["default_alerts"] == [-15] and cal["default_alerts_all_day"] == []
    cal = change(cal["version"], op="alerts", alerts=[-1440, -60]).json()
    assert cal["default_alerts"] == [-1440, -60]
    assert '"default_alerts": [\n    "-1d",\n    "-1h"\n  ]' in (data_dir / "agenda" / "calendars.json").read_text(encoding="utf-8")
    assert change(cal["version"], op="alerts", alerts="x").status_code == 400
    assert cal["birthday_alerts"] == [-9540]  # 1 week before at 09:00, unless set
    cal = change(cal["version"], op="birthday_alerts", alerts=[-360, -9540]).json()
    assert cal["birthday_alerts"] == [-9540, -360]
    assert '"birthday_alerts": [\n    "-159h",\n    "-6h"\n  ]' in (data_dir / "agenda" / "calendars.json").read_text(encoding="utf-8")
    assert change(cal["version"], op="birthday_alerts", alerts="x").status_code == 400
    ev = create(client, title="Point", start="2026-11-03T10:00", end="2026-11-03T11:00", tz="Europe/Paris")
    assert "calendar: travail" in (data_dir / ev["path"]).read_text(encoding="utf-8")
    cal = change(cal["version"], op="update", id="travail", visible=False).json()
    assert entries(client, "2026-11-03", "2026-11-03") == []
    response = client.delete("/api/agenda/calendars/travail", params={"move_to": "perso"},
                             headers={**HEADERS, "If-Match": cal["version"]})
    assert response.status_code == 200 and response.json()["moved"] == 1
    assert "calendar: perso" in (data_dir / ev["path"]).read_text(encoding="utf-8")
    assert [c["id"] for c in client.get("/api/agenda/calendars").json()["calendars"]] == ["perso"]
    client.post(f"/api/undo/{response.json()['undo']}", headers=HEADERS)
    assert "calendar: travail" in (data_dir / ev["path"]).read_text(encoding="utf-8")


def test_dated_note_appears_on_the_notes_layer_and_can_be_taken_off(client, data_dir):
    note = client.get("/api/notes/courses-abc123.md").json()
    response = client.put("/api/agenda/dated/courses-abc123.md", json={"start": "2026-11-14T09:30", "alerts": [-15]},
                          headers={**HEADERS, "If-Match": note["version"]})
    assert response.status_code == 200, response.text
    text = (data_dir / "courses-abc123.md").read_text(encoding="utf-8")
    assert "start: 2026-11-14T09:30" in text and "x-custom: gardé tel quel" in text
    shown = entries(client, "2026-11-14", "2026-11-14")
    assert [(e["kind"], e["title"], e["subtitle"], e["target_path"]) for e in shown] == \
        [("note", "Courses", "1/2", "courses-abc123.md")]
    response = client.put("/api/agenda/dated/courses-abc123.md", json={"start": None},
                          headers={**HEADERS, "If-Match": response.json()["version"]})
    assert "start:" not in (data_dir / "courses-abc123.md").read_text(encoding="utf-8")
    assert entries(client, "2026-11-14", "2026-11-14") == []


def test_birthdays_and_activities_layers(client, data_dir):
    (data_dir / "contacts").mkdir()
    (data_dir / "contacts" / "martin-marie-c1.md").write_text(
        "---\nid: c1\ntitle: Marie Martin\nfirst_name: Marie\nlast_name: Martin\nbirthday: 1990-11-20\n---\n", encoding="utf-8")
    shown = entries(client, "2026-11-20", "2026-11-20")
    assert [(e["kind"], e["title"], e["subtitle"]) for e in shown] == [("birthday", "Anniversaire de Marie Martin", "(36 ans)")]


def test_search_finds_the_next_occurrence(client):
    create(client, title="Piscine", start="2026-01-07T12:00", end="2026-01-07T13:00", tz="Europe/Paris",
           rrule="FREQ=WEEKLY", location="Centre nautique")
    create(client, title="Autre", start="2026-01-07T12:00", end="2026-01-07T13:00", tz="Europe/Paris")
    found = client.get("/api/agenda/search", params={"q": "NAUTIQUE", **TZ}).json()["entries"]
    assert [e["title"] for e in found] == ["Piscine"]
    assert found[0]["start"] > time.time() * 1000 - 7 * 86_400_000


def wait(client, token):
    for _ in range(200):
        status = client.get(f"/api/agenda/import/{token}").json()
        if status["state"] in ("done", "error"):
            return status
        time.sleep(0.02)
    raise AssertionError("import still running")


def upload(client, name, data):
    response = client.post("/api/agenda/import", params={"name": name, **TZ}, content=data,
                           headers={**HEADERS, "Content-Type": "application/octet-stream"})
    assert response.status_code == 200, response.text
    return response.json()


def test_import_a_mail_invitation_into_the_default_calendar_then_undo(client, data_dir):
    preview = upload(client, "interview_0.ics", (TESTDATA / "interview_0.ics").read_bytes())
    assert preview["compact"] and len(preview["calendars"]) == 1
    cal = preview["calendars"][0]
    assert cal["suggested_calendar"] == "perso" and not cal["calendar_export"] and cal["events"] == 1
    assert cal["items"][0]["title"] == "Interview with Example Corp" and "(Zurich)" in cal["items"][0]["when"]
    assert cal["items"][0]["location"] is None  # LOCATION:null
    response = client.post(f"/api/agenda/import/{preview['token']}/apply", json={"targets": {"0": {"calendar_id": "perso"}}},
                           headers=HEADERS)
    assert response.status_code == 202
    status = wait(client, preview["token"])
    assert status["state"] == "done" and status["report"]["calendars"][0]["created"] == 1, status
    shown = entries(client, "2026-10-26", "2026-10-26")
    assert [e["title"] for e in shown] == ["Interview with Example Corp"]
    ev = client.get(f"/api/agenda/events/{shown[0]['target_path']}", params=TZ).json()["event"]
    assert ev["links"][0]["label"] == "Rejoindre la visio (Proton Meet)" and ev["tz"] == "Europe/Zurich"
    past = client.get("/api/agenda/imports").json()["imports"]
    assert len(past) == 1 and past[0]["files"] == 1 and past[0]["calendars"] == ["Perso"]
    # the same file again: a duplicate in that calendar
    again = upload(client, "interview_0.ics", (TESTDATA / "interview_0.ics").read_bytes())
    assert again["calendars"][0]["duplicates"] == {"perso": 1}
    client.delete(f"/api/agenda/import/{again['token']}", headers=HEADERS)
    response = client.post(f"/api/agenda/imports/{past[0]['id']}/undo", headers=HEADERS)
    assert response.json()["message"] == "1 fichier supprimé"
    assert entries(client, "2026-10-26", "2026-10-26") == []
    assert client.get(f"/api/agenda/import/{preview['token']}").json()["state"] == "done"
    assert client.get("/api/agenda/import/inconnu").status_code == 410


@pytest.mark.parametrize("name", ["google_perso.ics", "proton_travail.ics", "outlook_calendrier.ics", "vols_paris_new_york.ics"])
def test_import_the_shared_vectors_in_a_zip_creates_named_calendars(client, name):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        archive.writestr(name, (TESTDATA / name).read_bytes())
    preview = upload(client, "export.zip", buffer.getvalue())
    cal = preview["calendars"][0]
    assert cal["calendar_export"] and cal["suggested_calendar"] is None and not preview["compact"]
    client.post(f"/api/agenda/import/{preview['token']}/apply", json={"duplicates": "skip"}, headers=HEADERS)
    status = wait(client, preview["token"])
    assert status["state"] == "done", status
    report = status["report"]
    assert report["new_calendars"] and report["calendars"][0]["created"] >= 1
    cals = client.get("/api/agenda/calendars").json()["calendars"]
    assert any(c["id"] == report["new_calendars"][0] and c["imported"] for c in cals)


def test_unreadable_upload_is_a_french_422(client):
    response = client.post("/api/agenda/import", params={"name": "x.zip"}, content=b"not a zip",
                           headers={**HEADERS, "Content-Type": "application/octet-stream"})
    assert response.status_code == 422 and "zip" in response.json()["error"]


def test_export_one_calendar_and_all_as_zip(client):
    create(client, title="Dentiste", start="2026-11-12T14:30", end="2026-11-12T15:15", tz="Europe/Paris",
           alerts=[-15])
    note = client.get("/api/notes/courses-abc123.md").json()
    client.put("/api/agenda/dated/courses-abc123.md", json={"start": "2026-11-14"},
               headers={**HEADERS, "If-Match": note["version"]})
    response = client.get("/api/agenda/export", params={"calendar": "perso"})
    assert response.status_code == 200 and response.headers["content-type"].startswith("text/calendar")
    assert "perso.ics" in response.headers["content-disposition"] and response.headers["x-memento-events"] == "1"
    text = response.text
    assert "SUMMARY:Dentiste" in text and "DTSTART;TZID=Europe/Paris:20261112T143000" in text and "BEGIN:VALARM" in text
    assert "Courses" not in text
    with_notes = client.get("/api/agenda/export", params={"calendar": "perso", "include": "1"}).text
    assert "SUMMARY:Courses" in with_notes
    archive = zipfile.ZipFile(io.BytesIO(client.get("/api/agenda/export", params={"include": "1"}).content))
    assert sorted(archive.namelist()) == ["notes.ics", "perso.ics"]


def test_agenda_core_is_not_imported_with_the_app_and_its_index_survives_idle(client):
    code = ("import sys; sys.path.insert(0, %r); from memento_web.app import create_app; "
            "print('dateutil' in sys.modules, 'memento_win.core.agenda.recurrence' in sys.modules)"
            % str(Path(__file__).resolve().parents[1] / "server"))
    out = subprocess.run([sys.executable, "-c", code], capture_output=True, text=True, check=True).stdout.split()
    assert out == ["False", "False"]  # imported by the warm-up thread, after the server is up
    create(client, title="x", start="2026-11-12")
    assert entries(client, "2026-11-12", "2026-11-12")[0]["all_day"]
    data = client.app.state.data
    index = client.app.state.warmer.index
    sources = index.sources
    assert sources is not None and index._entries
    assert data.trim_if_idle(now=data.last_request + 1000)
    assert index.sources is sources and not index._entries  # the index stays, the windows go
    assert entries(client, "2026-11-12", "2026-11-12")[0]["all_day"]


def test_windows_cut_from_the_wide_one_are_what_a_direct_build_returns(client):
    create(client, title="Hebdo", start="2026-11-02T09:00", end="2026-11-02T10:00", tz="Europe/Paris",
           rrule="FREQ=WEEKLY")
    create(client, title="Jour", start="2026-11-12")
    create(client, title="Instant", start="2026-11-20T12:00", tz="Europe/Paris")
    create(client, title="Nuit", start="2026-11-29T23:30", end="2026-11-30T00:30", tz="America/New_York")
    data = client.app.state.data
    index = client.app.state.warmer.index
    zone = "Europe/Paris"
    with data.lock:
        index.load()
        index.zone = zone
        index.prepare_wide(today=date(2026, 11, 15))
        assert not index.wants_wide() and index._wide is not None
        for first, last in [(date(2026, 10, 26), date(2026, 12, 6)), (date(2026, 11, 30), date(2026, 11, 30)),
                            (date(2026, 11, 20), date(2026, 11, 20)), (date(2026, 11, 9), date(2026, 11, 15))]:
            cut = index.entries(first, last, zone)
            direct = index._build(first, last, zone)
            assert [e.key for e in cut.entries] == [e.key for e in direct.entries] and cut.entries
            assert cut.problems == direct.problems
