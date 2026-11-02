"""Contacts and vCard against the shared vectors of testdata/contacts/ (the Android JUnit tests read the same files)."""
import json
from datetime import date
from pathlib import Path

import pytest

from scriptorium_win.core import contacts as ct
from scriptorium_win.core import note as fmt
from scriptorium_win.core import vcard
from scriptorium_win.core.store import Store

NOW = 1_793_000_000_000  # 2026-10: Windows cannot format dates near 1970
VECTORS = Path(__file__).resolve().parents[2] / "testdata" / "contacts"


def load(name):
    return json.loads((VECTORS / name).read_text(encoding="utf-8"))


def contact(d) -> ct.Contact:
    return ct.Contact(id=d.get("id", ""), first_name=d.get("first_name", ""), last_name=d.get("last_name", ""),
                      phones=[ct.Phone.decode(p) for p in d.get("phones", [])], emails=list(d.get("emails", [])),
                      address=d.get("address"), birthday=ct.Birthday.parse(d.get("birthday")),
                      favorite=d.get("favorite", False), uid=d.get("uid"), notes=d.get("notes", ""))


@pytest.mark.parametrize("name", ["google", "android21", "proton40", "apple", "edge"])
def test_vcard_vectors(name):
    expected = load(f"{name}.json")
    result = vcard.parse((VECTORS / f"{name}.vcf").read_bytes())
    assert [c.fields() for c in result.contacts] == expected["contacts"]
    assert (result.bad, result.empty, result.ignored) == (expected["bad"], expected["empty"], expected["ignored"])
    if "blank" in expected:
        assert ct.preview(result, []).blank == expected["blank"]


def test_crlf_and_windows_1252():
    text = (VECTORS / "google.vcf").read_text(encoding="utf-8")
    assert vcard.parse(text.replace("\n", "\r\n")) == vcard.parse(text)
    latin = "BEGIN:VCARD\nVERSION:3.0\nN:Lefèvre;Hélène;;;\nEND:VCARD\n".encode("cp1252")
    assert vcard.parse(latin).contacts[0].first_name == "Hélène"


def test_phone_normalisation():
    v = load("phones.json")
    for group in v["same"]:
        assert len({ct.normalize(x) for x in group}) == 1, group
    for raw, out in v["normalized"]:
        assert ct.normalize(raw) == out, raw
    for query, number, found in v["search"]:
        assert ct.matches(ct.Contact(id="x", phones=[ct.Phone(number)]), query) is found, (query, number)


def test_sections_search_and_birthdays():
    v = load("list.json")
    people = [contact(d) for d in v["contacts"]]
    for key, first in (("sections_last_first", False), ("sections_first_first", True)):
        assert [[t, [c.id for c in cs]] for t, cs in ct.sections(people, first)] == v[key]
    for query, ids in v["search"]:
        assert [c.id for c in ct.search(people, query, False)] == ids, query
    assert [[b.contact_id, b.name, b.birthday.encode()] for b in ct.birthdays(people)] == v["birthdays"]


def test_birthday_forms():
    assert ct.Birthday.parse("19900514") == ct.Birthday(1990, 5, 14)
    assert ct.Birthday.parse("--0514") == ct.Birthday(None, 5, 14)
    assert ct.Birthday.parse("1990-05-14T00:00:00Z") == ct.Birthday(1990, 5, 14)
    assert ct.Birthday.parse("1990-02-30") is None
    assert ct.Birthday.parse("circa 1800") is None
    assert ct.Birthday(None, 2, 29).occurrence(2027) == date(2027, 2, 28)
    assert ct.Birthday(1990, 5, 14).age_in(2026) == 36


def test_duplicates_and_merge():
    v = load("duplicates.json")
    people = [contact(d) for d in v["contacts"]]
    assert [[c.id for c in g] for g in ct.duplicate_groups(people)] == v["groups"]
    for m in v["merges"]:
        keep = next(c for c in people if c.id == m["keep"])
        others = [next(c for c in people if c.id == i) for i in m["others"]]
        assert ct.merge(keep, others).fields() == m["expected"]


def test_file_format_keeps_unknown_keys():
    text = (VECTORS / "dupont-marie-p4x8q1.md").read_text(encoding="utf-8")
    c = ct.from_note(fmt.parse("contacts/dupont-marie-p4x8q1.md", text, 0))
    e = load("dupont-marie-p4x8q1.json")
    assert (c.id, c.import_id) == (e["id"], e["import"])
    assert c.fields() == contact(e).fields()
    out = fmt.serialize(ct.to_note(c))
    assert "aliases: [Marie D.]\n" in out
    again = ct.from_note(fmt.parse(c.path, out, 0))
    assert again.fields() == c.fields() and again.import_id == c.import_id
    assert [x for x in again.base.extra if x.startswith("aliases")] == e["extra"]
    assert ct.to_note(c).title == "Marie Dupont"


def test_file_names():
    assert ct.file_name_for(ct.Contact(first_name="Marie", last_name="Dupont"), "abc123") == "contacts/dupont-marie-abc123.md"
    assert ct.file_name_for(ct.Contact(first_name="Boulangerie"), "abc123") == "contacts/boulangerie-abc123.md"
    assert ct.file_name_for(ct.Contact(phones=[ct.Phone("06")]), "abc123") == "contacts/contact-abc123.md"


def test_export_import_round_trip():
    people = [c for n in ("google", "android21", "proton40", "apple")
              for c in vcard.parse((VECTORS / f"{n}.vcf").read_bytes()).contacts]
    people[0] = people[0].copy(favorite=True)
    text = vcard.write(people)
    assert all(len(line.encode("utf-8")) <= 75 for line in text.split("\r\n"))
    back = vcard.parse(text)
    assert back.bad == 0
    assert [c.fields() for c in back.contacts] == [c.fields() for c in people]


def files(root: Path) -> dict[str, str]:
    return {p.relative_to(root).as_posix(): p.read_text(encoding="utf-8") for p in root.rglob("*.md")}


def test_import_policies_and_undo(tmp_path):
    store = Store(tmp_path)
    book = store.contacts
    marie = book.save(ct.Contact(first_name="Marie", last_name="Dupont", phones=[ct.Phone("06 12 34 56 78", "mobile")],
                                 notes="à moi"))
    before = files(tmp_path)
    result = vcard.parse((VECTORS / "google.vcf").read_bytes())
    p = ct.preview(result, book.contacts)
    assert (p.cards, p.duplicates) == (4, 1)

    out = book.apply(ct.plan(result, book.contacts, ct.DuplicatePolicy.KEEP_BOTH, "I1", NOW), "I1", result)
    assert len(out.created) == 4 and len(store.scan_contacts()) == 5
    assert all("import: I1" in (tmp_path / f).read_text(encoding="utf-8") for f in out.created)
    book.undo(out)
    assert files(tmp_path) == before

    skip = ct.plan(result, book.contacts, ct.DuplicatePolicy.SKIP, "I2", NOW)
    assert (skip.skipped, len(skip.creates)) == (1, 3)

    upd = ct.plan(result, book.contacts, ct.DuplicatePolicy.UPDATE, "I3", NOW)
    assert len(upd.updates) == 1
    out3 = book.apply(upd, "I3", result)
    merged = book.by_id(marie.id)
    assert len(merged.phones) == 4 and merged.notes.startswith("à moi") and merged.uid == "a1b2c3d4@example.org"
    assert "import:" not in (tmp_path / marie.path).read_text(encoding="utf-8")
    book.undo(out3)
    assert files(tmp_path) == before

    again = book.apply(ct.plan(result, book.contacts, ct.DuplicatePolicy.UPDATE, "I4", NOW), "I4", result)
    third = ct.plan(result, book.contacts, ct.DuplicatePolicy.UPDATE, "I5", NOW + 1000)
    assert (len(third.creates), len(third.updates), third.unchanged) == (0, 0, 4)
    book.undo(again)
    assert files(tmp_path) == before


def test_delete_restore_merge_and_the_notes_list(tmp_path):
    store = Store(tmp_path)
    book = store.contacts
    a = book.save(ct.Contact(first_name="Luc", phones=[ct.Phone("06 00 00 00 01")]))
    b = book.save(ct.Contact(first_name="Luc", emails=["luc@example.org"]))
    before = files(tmp_path)
    raw = book.delete(a)
    assert len(book.contacts) == 1
    book.restore(a.path, raw)
    assert files(tmp_path) == before
    texts = book.merge(a, [b])
    assert [c.emails for c in book.contacts] == [["luc@example.org"]]
    book.restore_all(texts)
    assert files(tmp_path) == before
    assert store.scan() == []  # contacts stay out of the notes list
    assert len(store.scan_contacts()) == 2


def test_a_label_never_reads_back_as_part_of_the_number():
    for label in ("2", "a (b)", "(x)", "  "):
        assert ct.Phone.decode(ct.Phone("06 00 00 00 03", label).encode()).number == "06 00 00 00 03"
    assert ct.Phone("06", "Pro (ancien)").encode() == "06 (Pro ancien)"


def test_a_shared_home_number_is_not_the_same_person():
    marie = ct.Contact(id="m1", path="contacts/m1.md", first_name="Marie", last_name="Dupont",
                       phones=[ct.Phone("01 23 45 67 89", ct.HOME)])
    r = vcard.parse("BEGIN:VCARD\nVERSION:3.0\nN:Dupont;Paul;;;\nTEL;TYPE=HOME:+33 1 23 45 67 89\nEND:VCARD\n"
                    "BEGIN:VCARD\nVERSION:3.0\nFN:Marie\nTEL;TYPE=CELL:06 00 00 00 09\nTEL;TYPE=HOME:0123456789\nEND:VCARD\n")
    assert ct.preview(r, [marie]).duplicates == 1
    upd = ct.plan(r, [marie], ct.DuplicatePolicy.UPDATE, "I", NOW, lambda: "new001")
    assert [c.first_name for c in upd.creates] == ["Paul"]
    assert upd.updates[0][1].name == "Marie Dupont"
    assert ct.plan(r, [marie], ct.DuplicatePolicy.SKIP, "I", NOW, lambda: "new001").skipped == 1
