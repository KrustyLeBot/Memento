"""Contacts API against the shared vectors of testdata/contacts/ (the Windows and Android tests read the same files)."""
from __future__ import annotations

import json
import shutil
from pathlib import Path

import pytest

from conftest import HEADERS
from memento_web.core import fmt
from memento_web.modules import contacts as module
from memento_win.core import contacts as ct
from memento_win.core import vcard

VECTORS = Path(__file__).resolve().parents[2] / "testdata" / "contacts"


def vector(name: str) -> dict:
    return json.loads((VECTORS / name).read_text(encoding="utf-8"))


def write_contacts(data_dir: Path, people: list[dict]) -> None:
    """The vector contacts as files, as the phone would have written them."""
    folder = data_dir / "contacts"
    folder.mkdir(exist_ok=True)
    for raw in people:
        c = ct.Contact(id=raw["id"], first_name=raw.get("first_name", ""), last_name=raw.get("last_name", ""),
                       phones=[ct.Phone.decode(p) for p in raw.get("phones", [])], emails=raw.get("emails", []),
                       address=raw.get("address"), birthday=ct.Birthday.parse(raw.get("birthday")),
                       favorite=raw.get("favorite", False), notes=raw.get("notes", ""), created=1, updated=1)
        c = c.copy(path=ct.file_name_for(c, c.id))
        (data_dir / c.path).write_text(fmt.serialize(ct.to_note(c)), encoding="utf-8")


def refs(sections) -> list:
    return [[s["title"], [c["ref"] for c in s["contacts"]]] for s in sections]


def put(client, ref, version, **fields):
    return client.put(f"/api/contacts/{ref}", json=fields, headers={**HEADERS, "If-Match": version})


def upload(client, name: str, content: bytes):
    return client.post("/api/contacts/import", params={"name": name}, content=content,
                       headers={**HEADERS, "Content-Type": "application/octet-stream"})


def run_import(client, token: str, policy: str = "keep_both") -> dict:
    progress = client.post(f"/api/contacts/import/{token}/start", json={"policy": policy}, headers=HEADERS).json()
    while not progress["finished"]:
        progress = client.post(f"/api/contacts/import/{token}/step", headers=HEADERS).json()
    return progress


# ---- list, sections, search ----

def test_empty_folder_lists_nothing(client):
    assert client.get("/api/contacts").json() == {"sections": [], "count": 0, "results": None}


@pytest.mark.parametrize("first, key", [(False, "sections_last_first"), (True, "sections_first_first")])
def test_sections_follow_the_core_and_the_name_order(client, data_dir, first, key):
    v = vector("list.json")
    write_contacts(data_dir, v["contacts"])
    body = client.get("/api/contacts", params={"first": "1"} if first else {}).json()
    assert refs(body["sections"]) == v[key]
    assert body["count"] == len(v["contacts"])


def test_search_is_accent_apostrophe_and_digit_aware(client, data_dir):
    v = vector("list.json")
    write_contacts(data_dir, v["contacts"] + [{"id": "q1", "first_name": "Jean-François", "last_name": "O’Neill"}])
    for query, expected in v["search"]:
        body = client.get("/api/contacts", params={"q": query}).json()
        found = [c["ref"] for s in body["sections"] for c in s["contacts"]]
        assert found == expected, query
        assert body["results"] == len(expected)
    found = client.get("/api/contacts", params={"q": "oneill"}).json()["sections"][0]["contacts"]
    assert [c["ref"] for c in found] == ["q1"]


def test_conflict_copies_are_listed_by_file_name_and_not_counted(client, data_dir):
    write_contacts(data_dir, [{"id": "c1", "first_name": "Ana", "last_name": "B"}])
    original = next((data_dir / "contacts").iterdir())
    shutil.copy(original, original.with_name(original.name + ".conflict1"))
    body = client.get("/api/contacts").json()
    assert body["count"] == 1
    listed = [c for s in body["sections"] for c in s["contacts"]]
    assert sorted(c["ref"] for c in listed) == sorted(["c1", original.name + ".conflict1"])
    copy = client.get(f"/api/contacts/{original.name}.conflict1").json()["contact"]
    assert copy["conflict"] and copy["id"] == "c1"


# ---- read, create, update, delete ----

def test_get_keeps_every_field_of_the_shared_file(client, data_dir):
    (data_dir / "contacts").mkdir()
    shutil.copy(VECTORS / "dupont-marie-p4x8q1.md", data_dir / "contacts")
    body = client.get("/api/contacts/p4x8q1").json()
    c = body["contact"]
    assert c["name"] == "Marie Dupont" and c["favorite"]
    assert c["phones"] == [{"number": "+33 6 12 34 56 78", "label": "mobile"},
                           {"number": "+33 1 23 45 67 89", "label": "domicile"}]
    assert c["birthday"] == {"day": 14, "month": 5, "year": None}
    assert len(body["version"]) == 24


def test_create_then_update_keeps_unknown_keys(client, data_dir):
    response = client.post("/api/contacts", json={
        "first_name": " Zoé ", "last_name": "Écrivain", "phones": [{"number": "06 11 22 33 44", "label": "mobile"},
                                                                   {"number": " ", "label": "travail"}],
        "emails": ["zoe@example.org", ""], "birthday": {"day": 29, "month": 2, "year": None}, "notes": "Hello\n"},
        headers=HEADERS)
    assert response.status_code == 201
    created = response.json()
    c = created["contact"]
    assert c["path"].startswith("contacts/ecrivain-zoe-") and c["first_name"] == "Zoé"
    assert c["phones"] == [{"number": "06 11 22 33 44", "label": "mobile"}] and c["emails"] == ["zoe@example.org"]
    text = (data_dir / c["path"]).read_text(encoding="utf-8")
    assert 'birthday: "--02-29"' in text or "birthday: --02-29" in text
    (data_dir / c["path"]).write_text(text.replace("---\n", "---\naliases: [Z]\n", 1), encoding="utf-8")
    loaded = client.get(f"/api/contacts/{c['ref']}").json()
    updated = put(client, c["ref"], loaded["version"], favorite=True, address="1 rue X")
    assert updated.status_code == 200, updated.text
    assert updated.json()["contact"]["favorite"] and updated.json()["contact"]["first_name"] == "Zoé"
    text = (data_dir / c["path"]).read_text(encoding="utf-8")
    assert "aliases: [Z]" in text and "favorite: true" in text


@pytest.mark.parametrize("body, message", [
    ({}, "Le contact est vide"),
    ({"first_name": "A", "birthday": {"day": 31, "month": 2, "year": None}}, "Date d'anniversaire invalide"),
    ({"first_name": "A", "birthday": {"day": 29, "month": 2, "year": 2023}}, "Date d'anniversaire invalide"),
    ({"first_name": "A", "colour": "red"}, "Champs inconnus : colour"),
])
def test_create_refuses_bad_input(client, body, message):
    response = client.post("/api/contacts", json=body, headers=HEADERS)
    assert response.status_code == 400 and response.json()["error"] == message


def test_update_over_a_changed_file_is_a_409_with_the_current_contact(client, data_dir):
    write_contacts(data_dir, [{"id": "u1", "first_name": "Ana"}])
    version = client.get("/api/contacts/u1").json()["version"]
    path = next((data_dir / "contacts").iterdir())
    path.write_text(path.read_text(encoding="utf-8").replace("Ana", "Anna"), encoding="utf-8")
    response = put(client, "u1", version, first_name="Anaïs")
    assert response.status_code == 409
    assert response.json()["current"]["contact"]["first_name"] == "Anna"
    assert put(client, "u1", response.json()["current"]["version"], first_name="Anaïs").status_code == 200
    assert client.put("/api/contacts/u1", json={"first_name": "X"}, headers=HEADERS).status_code == 428


def test_delete_then_undo_puts_the_file_back(client, data_dir):
    write_contacts(data_dir, [{"id": "d1", "first_name": "Ana", "phones": ["0611223344"]}])
    path = next((data_dir / "contacts").iterdir())
    before = path.read_bytes()
    version = client.get("/api/contacts/d1").json()["version"]
    assert client.delete("/api/contacts/d1", headers={**HEADERS, "If-Match": "nope"}).status_code == 409
    token = client.delete("/api/contacts/d1", headers={**HEADERS, "If-Match": version}).json()["undo"]
    assert not path.exists() and client.get("/api/contacts/d1").status_code == 404
    assert client.post(f"/api/undo/{token}", headers=HEADERS).status_code == 200
    assert path.read_bytes() == before


# ---- import / export ----

@pytest.mark.parametrize("name", ["google", "apple", "android21", "proton40", "edge"])
def test_import_matches_the_vectors_and_undo_removes_it(client, data_dir, name):
    expected = vector(f"{name}.json")
    preview = upload(client, f"{name}.vcf", (VECTORS / f"{name}.vcf").read_bytes())
    assert preview.status_code == 200, preview.text
    p = preview.json()
    assert (p["cards"], p["bad"], p["empty"]) == (len(expected["contacts"]), expected["bad"], expected["empty"])
    assert p["policies"][0] == {"key": "keep_both", "label": "Garder les deux"}
    done = run_import(client, p["token"])
    assert done["report"]["created"] == len(expected["contacts"]) and done["done"] == done["total"]
    book = ct.ContactBook(data_dir)
    imported = sorted(book.scan(), key=lambda c: c.created)
    assert sorted(json.dumps(c.fields(), sort_keys=True) for c in imported) == \
        sorted(json.dumps(c, sort_keys=True) for c in expected["contacts"])
    assert len({c.import_id for c in imported}) == 1
    assert client.post(f"/api/undo/{done['undo']}", headers=HEADERS).status_code == 200
    assert not list((data_dir / "contacts").glob("*.md"))


def test_import_in_several_steps_reports_progress(client, data_dir, monkeypatch):
    monkeypatch.setattr(module, "STEP_SECONDS", 0.0)  # one slice of 20 per step
    cards = "".join(f"BEGIN:VCARD\r\nVERSION:3.0\r\nN:Nom{i};Prénom{i};;;\r\nEND:VCARD\r\n" for i in range(45))
    token = upload(client, "many.vcf", cards.encode()).json()["token"]
    progress = client.post(f"/api/contacts/import/{token}/start", json={"policy": "keep_both"}, headers=HEADERS).json()
    seen = [progress["done"]]
    while not progress["finished"]:
        progress = client.post(f"/api/contacts/import/{token}/step", headers=HEADERS).json()
        seen.append(progress["done"])
    assert seen == [0, 20, 40, 45] and progress["total"] == 45
    assert client.post(f"/api/contacts/import/{token}/step", headers=HEADERS).status_code == 410
    assert client.post(f"/api/contacts/import/{token}/start", json={"policy": "skip"}, headers=HEADERS).status_code == 410


def test_import_policies_skip_and_update(client, data_dir):
    (data_dir / "contacts").mkdir()
    shutil.copy(VECTORS / "dupont-marie-p4x8q1.md", data_dir / "contacts")
    original = (data_dir / "contacts" / "dupont-marie-p4x8q1.md").read_bytes()
    card = ("BEGIN:VCARD\r\nVERSION:3.0\r\nN:Dupont;Marie;;;\r\nTEL;TYPE=CELL:06 12 34 56 78\r\n"
            "EMAIL:marie.new@example.org\r\nEND:VCARD\r\n").encode()
    p = upload(client, "one.vcf", card).json()
    assert p["duplicates"] == 1
    skipped = run_import(client, p["token"], "skip")["report"]
    assert skipped["skipped"] == 1 and skipped["created"] == 0
    p = upload(client, "one.vcf", card).json()
    done = run_import(client, p["token"], "update")
    assert done["report"]["updated"] == 1 and done["report"]["created"] == 0
    merged = client.get("/api/contacts/p4x8q1").json()["contact"]
    assert "marie.new@example.org" in merged["emails"]
    client.post(f"/api/undo/{done['undo']}", headers=HEADERS)
    assert (data_dir / "contacts" / "dupont-marie-p4x8q1.md").read_bytes() == original


def test_import_refuses_a_file_without_cards(client):
    response = upload(client, "x.vcf", b"hello")
    assert response.status_code == 400 and response.json()["error"] == "Aucune fiche vCard dans ce fichier"
    assert client.post("/api/contacts/import/unknown/start", json={"policy": "keep_both"},
                       headers=HEADERS).status_code == 410
    assert client.post("/api/contacts/import/unknown/start", json={"policy": "nope"},
                       headers=HEADERS).status_code == 400


def test_export_round_trip(client, data_dir):
    write_contacts(data_dir, vector("list.json")["contacts"])
    (data_dir / "contacts").joinpath("x.md.conflict1").write_text("---\nid: zz\nfirst_name: Copie\n---\n", encoding="utf-8")
    response = client.get("/api/contacts/export/contacts.vcf")
    assert response.status_code == 200 and response.headers["content-type"].startswith("text/vcard")
    assert "attachment" in response.headers["content-disposition"]
    back = vcard.parse(response.content)
    originals = [c for c in ct.ContactBook(data_dir).scan() if not c.conflict]
    assert len(back.contacts) == len(originals)
    key = lambda c: (c.first_name, c.last_name)  # noqa: E731
    for a, b in zip(sorted(back.contacts, key=key), sorted(originals, key=key)):
        assert (a.phones, a.emails, a.birthday, a.favorite) == (b.phones, b.emails, b.birthday, b.favorite)
    one = client.get("/api/contacts/a3/vcard")
    assert "dupont" in one.headers["content-disposition"].lower()
    assert [c.name for c in vcard.parse(one.content).contacts] == ["Marie Dupont"]


# ---- duplicates ----

def test_duplicates_and_merge_follow_the_vectors_and_undo(client, data_dir):
    v = vector("duplicates.json")
    write_contacts(data_dir, v["contacts"])
    groups = client.get("/api/contacts/duplicates").json()["groups"]
    assert [[c["ref"] for c in g["contacts"]] for g in groups] == v["groups"]
    assert groups[1]["keep"] == "b4" and groups[1]["contacts"][0]["details"] == ["06 12 34 56 78", "marie@example.org"]
    before = {p.name: p.read_bytes() for p in (data_dir / "contacts").iterdir()}
    for case in v["merges"]:
        response = client.post("/api/contacts/merge", json={"keep": case["keep"], "others": case["others"]},
                               headers=HEADERS)
        assert response.status_code == 200, response.text
        kept = next(c for c in ct.ContactBook(data_dir).scan() if c.id == case["keep"])
        assert kept.fields() == case["expected"]
        for other in case["others"]:
            assert client.get(f"/api/contacts/{other}").status_code == 404
        assert client.post(f"/api/undo/{response.json()['undo']}", headers=HEADERS).status_code == 200
        assert {p.name: p.read_bytes() for p in (data_dir / "contacts").iterdir()} == before
    gone = client.post("/api/contacts/merge", json={"keep": "b1", "others": ["nope"]}, headers=HEADERS)
    assert gone.status_code == 409
