from __future__ import annotations

import pytest

from conftest import HEADERS
from memento_web.core import fmt


def put(client, path, version, **fields):
    return client.put(f"/api/notes/{path}", json=fields, headers={**HEADERS, "If-Match": version})


def no_temporaries(folder):
    return not [p for p in folder.rglob("*") if ".synctmp-" in p.name or p.name.endswith(".tmp")]


def test_list_sorts_pinned_first_with_labels_and_previews(client):
    body = client.get("/api/notes").json()
    assert [n["title"] for n in body["notes"]] == ["Courses", "Idée"]
    first = body["notes"][0]
    assert first["pinned"] and first["color"] == "green" and first["labels"] == ["maison", "à faire"]
    assert first["checklist"] == {"done": 1, "total": 2}
    assert "☐ pain" in first["preview"] and "**" not in first["preview"]
    assert body["labels"] == ["à faire", "maison"]


@pytest.mark.parametrize("query, titles", [("SAMEDI", ["Courses"]), ("idee", ["Idée"]), ("maison", ["Courses"]),
                                           ("rien", [])])
def test_search_ignores_case_and_accents(client, query, titles):
    assert [n["title"] for n in client.get("/api/notes", params={"q": query}).json()["notes"]] == titles


def test_label_filter(client):
    assert [n["title"] for n in client.get("/api/notes", params={"label": "maison"}).json()["notes"]] == ["Courses"]


def test_get_returns_the_body_split_like_the_editor(client):
    body = client.get("/api/notes/courses-abc123.md").json()
    assert body["note"]["parts"] == {"before": "Liste pour **samedi**",
                                     "items": [{"text": "pain", "checked": False}, {"text": "lait", "checked": True}],
                                     "after": ""}
    assert len(body["version"]) == 24


def test_create_writes_a_file_named_from_the_title(client, data_dir):
    response = client.post("/api/notes", json={"title": "Réunion d'équipe", "parts": {
        "before": "Ordre du jour", "items": [{"text": "budget", "checked": False}], "after": ""}}, headers=HEADERS)
    assert response.status_code == 201
    note = response.json()["note"]
    assert note["path"].startswith("reunion-d-equipe-") and note["path"].endswith(".md")
    text = (data_dir / note["path"]).read_text(encoding="utf-8")
    assert "title: Réunion d'équipe" in text and "- [ ] budget" in text
    parsed = fmt.parse(note["path"], text, 0)
    assert parsed.created > 1_700_000_000_000 and parsed.updated >= parsed.created  # never 1970
    assert no_temporaries(data_dir)


def test_update_keeps_unknown_keys_and_rebuilds_the_body_with_checked_items_last(client, data_dir):
    loaded = client.get("/api/notes/courses-abc123.md").json()
    response = put(client, "courses-abc123.md", loaded["version"], title="Courses du week-end", color="blue",
                   labels=["maison"], parts={"before": "Liste", "after": "",
                                             "items": [{"text": "lait", "checked": True}, {"text": "œufs", "checked": False}]})
    assert response.status_code == 200, response.text
    text = (data_dir / "courses-abc123.md").read_text(encoding="utf-8")
    assert "x-custom: gardé tel quel" in text
    assert "id: abc123" in text and "color: blue" in text
    assert text.endswith("Liste\n\n- [ ] œufs\n- [x] lait\n")
    assert response.json()["version"] != loaded["version"]
    assert no_temporaries(data_dir)


def test_a_change_from_the_sync_is_refused_with_409_and_the_disk_version(client, data_dir):
    loaded = client.get("/api/notes/courses-abc123.md").json()
    synced = (data_dir / "courses-abc123.md").read_text(encoding="utf-8").replace("Courses", "Courses (téléphone)")
    (data_dir / "courses-abc123.md").write_text(synced, encoding="utf-8")
    response = put(client, "courses-abc123.md", loaded["version"], title="Ma version")
    assert response.status_code == 409
    current = response.json()["current"]
    assert current["note"]["title"] == "Courses (téléphone)"
    assert "téléphone" in (data_dir / "courses-abc123.md").read_text(encoding="utf-8")
    # "Garder ma version": the client saves again against the version it was shown
    assert put(client, "courses-abc123.md", current["version"], title="Ma version").status_code == 200


def test_update_of_a_note_deleted_elsewhere_is_a_409_without_current(client, data_dir):
    loaded = client.get("/api/notes/courses-abc123.md").json()
    (data_dir / "courses-abc123.md").unlink()
    response = put(client, "courses-abc123.md", loaded["version"], title="x")
    assert response.status_code == 409 and response.json()["current"] is None


def test_update_without_version_is_refused(client):
    assert client.put("/api/notes/courses-abc123.md", json={"title": "x"}, headers=HEADERS).status_code == 428


def test_unknown_fields_are_refused(client):
    loaded = client.get("/api/notes/courses-abc123.md").json()
    assert put(client, "courses-abc123.md", loaded["version"], id="other").status_code == 400


def test_delete_then_undo_restores_the_note_and_its_files(client, data_dir):
    loaded = client.get("/api/notes/courses-abc123.md").json()
    up = client.post("/api/notes/courses-abc123.md/attachments", params={"name": "Facture EDF.pdf"},
                     content=b"%PDF-1.4 synthetic", headers={**HEADERS, "If-Match": loaded["version"]})
    assert up.status_code == 200, up.text
    attachment = up.json()["note"]["attachments"][0]
    deleted = client.delete("/api/notes/courses-abc123.md", headers={**HEADERS, "If-Match": up.json()["version"]})
    token = deleted.json()["undo"]
    assert not (data_dir / "courses-abc123.md").exists() and not (data_dir / attachment).exists()
    assert client.post(f"/api/undo/{token}", headers=HEADERS).status_code == 200
    assert (data_dir / attachment).read_bytes() == b"%PDF-1.4 synthetic"
    assert "x-custom" in (data_dir / "courses-abc123.md").read_text(encoding="utf-8")
    assert client.post(f"/api/undo/{token}", headers=HEADERS).status_code == 410


def test_attachment_upload_download_and_removal(client, data_dir):
    version = client.get("/api/notes/courses-abc123.md").json()["version"]
    up = client.post("/api/notes/courses-abc123.md/attachments", params={"name": "photo vacances.JPG"},
                     content=b"\xff\xd8 not really a jpeg", headers={**HEADERS, "If-Match": version}).json()
    path = up["note"]["attachments"][0]
    assert path.startswith("attachments/photo-vacances-") and path.endswith(".jpg")
    got = client.get(f"/api/files/{path}")
    assert got.content == b"\xff\xd8 not really a jpeg" and got.headers["content-type"] == "image/jpeg"
    assert "sandbox" in got.headers["content-security-policy"]
    removed = client.delete("/api/notes/courses-abc123.md/attachments", params={"file": path},
                            headers={**HEADERS, "If-Match": up["version"]})
    assert removed.status_code == 200 and removed.json()["note"]["attachments"] == []
    assert not (data_dir / path).exists()
    assert no_temporaries(data_dir)


def test_attachment_over_50_mb_is_refused(client, data_dir, monkeypatch):
    from memento_web.modules import notes
    monkeypatch.setattr(notes, "MAX_ATTACHMENT", 10)
    version = client.get("/api/notes/courses-abc123.md").json()["version"]
    response = client.post("/api/notes/courses-abc123.md/attachments", params={"name": "big.bin"},
                           content=b"x" * 11, headers={**HEADERS, "If-Match": version})
    assert response.status_code == 413
    assert not (data_dir / "attachments").exists() or not any((data_dir / "attachments").iterdir())


def test_html_and_svg_files_are_downloaded_never_rendered(client, data_dir):
    (data_dir / "attachments").mkdir()
    (data_dir / "attachments" / "page.html").write_text("<script>alert(1)</script>")
    response = client.get("/api/files/attachments/page.html")
    assert response.headers["content-disposition"].startswith("attachment")
    assert response.headers["content-type"] == "application/octet-stream"


def test_pdf_opens_in_the_browser(client, data_dir):
    (data_dir / "attachments").mkdir()
    (data_dir / "attachments" / "scan.pdf").write_bytes(b"%PDF-1.4")
    response = client.get("/api/files/attachments/scan.pdf")
    assert response.headers["content-disposition"].startswith("inline")
    assert "sandbox" not in response.headers["content-security-policy"]


@pytest.mark.parametrize("path", ["../secret.md", "attachments/../../x", "%2e%2e/x", "/etc/passwd", ".hidden.md",
                                  "attachments/.x.synctmp-1", "a\\b.md"])
def test_paths_outside_the_folder_are_refused(client, path):
    assert client.get(f"/api/files/{path}").status_code in (400, 404)


def test_symlink_escaping_the_folder_is_refused(client, data_dir, tmp_path):
    (tmp_path / "outside.txt").write_text("secret")
    (data_dir / "link.txt").symlink_to(tmp_path / "outside.txt")
    assert client.get("/api/files/link.txt").status_code == 400


@pytest.mark.parametrize("path", ["../x.md", "contacts/a.md", "attachments/a.md", "a/b/c.md", "x.txt"])
def test_note_endpoints_only_accept_note_paths(client, path):
    assert client.get(f"/api/notes/{path}").status_code in (400, 404)
    assert client.put(f"/api/notes/{path}", json={}, headers={**HEADERS, "If-Match": "x"}).status_code in (400, 404)


def test_attachment_removal_cannot_delete_files_outside_the_note(client, data_dir):
    version = client.get("/api/notes/courses-abc123.md").json()["version"]
    response = client.delete("/api/notes/courses-abc123.md/attachments", params={"file": "idee-def456.md"},
                             headers={**HEADERS, "If-Match": version})
    assert response.status_code == 404
    assert (data_dir / "idee-def456.md").exists()


def test_drawing_create_read_update_and_erase(client, data_dir):
    version = client.get("/api/notes/idee-def456.md").json()["version"]
    stroke = {"color": "#e53935", "width": 5, "points": [[0, 0], [10, 5], [20, 0]]}
    made = client.post("/api/notes/idee-def456.md/drawings", json={"file": None, "strokes": [stroke]},
                       headers={**HEADERS, "If-Match": version}).json()
    path = made["note"]["attachments"][0]
    assert path.startswith("drawings/dessin-") and path.endswith(".svg")
    svg = (data_dir / path).read_text()
    assert 'data-memento="1"' in svg and 'stroke="#e53935"' in svg
    read = client.get(f"/api/drawings/{path}").json()
    assert read["strokes"] == [{"color": "#e53935", "width": 5.0, "points": [[0.0, 0.0], [10.0, 5.0], [20.0, 0.0]]}]
    erased = client.post("/api/notes/idee-def456.md/drawings", json={"file": path, "strokes": []},
                         headers={**HEADERS, "If-Match": made["version"]}).json()
    assert erased["note"]["attachments"] == [] and not (data_dir / path).exists()


def test_a_bad_stroke_is_refused(client):
    version = client.get("/api/notes/idee-def456.md").json()["version"]
    response = client.post("/api/notes/idee-def456.md/drawings",
                           json={"file": None, "strokes": [{"color": "red", "width": 1, "points": [[0, 0]]}]},
                           headers={**HEADERS, "If-Match": version})
    assert response.status_code == 400


def test_conflict_copies_are_listed_and_resolved(client, data_dir):
    (data_dir / "courses-abc123.md.conflict1").write_text((data_dir / "courses-abc123.md").read_text().replace("pain", "baguette"))
    (data_dir / "idee-def456-conflict-2026.md").write_text("---\nid: def456\ntitle: Idée (autre)\n---\n")
    listed = client.get("/api/conflicts").json()["conflicts"]
    assert [(c["path"], c["original"]) for c in listed] == [
        ("courses-abc123.md.conflict1", "courses-abc123.md"), ("idee-def456-conflict-2026.md", "idee-def456.md")]
    assert client.get("/api/notes").json()["conflicts"] == 1  # the one the notes list can show (a .md)
    kept = client.post("/api/conflicts/resolve", json={"path": "courses-abc123.md.conflict1", "action": "keep"},
                       headers=HEADERS).json()
    assert kept["path"] == "courses-abc123.md"
    assert "baguette" in (data_dir / "courses-abc123.md").read_text()
    assert not (data_dir / "courses-abc123.md.conflict1").exists()
    client.post(f"/api/undo/{kept['undo']}", headers=HEADERS)
    assert "pain" in (data_dir / "courses-abc123.md").read_text()
    discarded = client.post("/api/conflicts/resolve", json={"path": "idee-def456-conflict-2026.md", "action": "discard"},
                            headers=HEADERS)
    assert discarded.status_code == 200 and not (data_dir / "idee-def456-conflict-2026.md").exists()


def test_deleting_a_conflict_copy_keeps_the_attachments_its_original_shares(client, data_dir):
    (data_dir / "attachments").mkdir()
    (data_dir / "attachments" / "plan-aaaaaa.pdf").write_bytes(b"%PDF")
    shared = "---\nid: zzz111\ntitle: Plan\nattachments: [attachments/plan-aaaaaa.pdf]\n---\n"
    (data_dir / "plan-zzz111.md").write_text(shared)
    (data_dir / "plan-zzz111-conflict.md").write_text(shared)
    version = client.get("/api/notes/plan-zzz111-conflict.md").json()["version"]
    client.delete("/api/notes/plan-zzz111-conflict.md", headers={**HEADERS, "If-Match": version})
    assert (data_dir / "attachments" / "plan-aaaaaa.pdf").exists()


def test_activity_settings_round_trip_with_versions(client, data_dir):
    first = client.get("/api/settings/activities").json()
    assert first["version"] is None and first["refine_altitude"] is False
    saved = client.put("/api/settings/activities", json={"refine_altitude": True, "totals": ["walk"]},
                       headers={**HEADERS, "If-Match": "none"})
    assert saved.status_code == 200, saved.text
    assert '"refine_altitude": true' in (data_dir / "activities" / "settings.json").read_text()
    stale = client.put("/api/settings/activities", json={"refine_altitude": False, "totals": []},
                       headers={**HEADERS, "If-Match": "none"})
    assert stale.status_code == 409
