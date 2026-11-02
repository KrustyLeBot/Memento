"""The warm-up, the saved indexes (snapshot.py) and the incremental refresh on changes (warm.py)."""
from __future__ import annotations

import asyncio
import os
import threading
import time

import pytest

import tracks
from conftest import login
from memento_web import snapshot as snap
from memento_web.snapshot import Snapshots

T0 = 1_790_000_000_000


def note(note_id: str, title: str) -> str:
    return f"---\nid: {note_id}\ntitle: {title}\n---\n\nTexte\n"


def activity(data, n: int) -> None:
    track = f"attachments/t{n}.gpx"
    (data / "attachments").mkdir(exist_ok=True)
    (data / "activities").mkdir(exist_ok=True)
    (data / track).write_text(tracks.walk(T0 + n * 86_400_000, 300), encoding="utf-8")
    (data / "activities" / f"sortie-a{n}.md").write_text(
        f"---\nid: a{n}\ntitle: Sortie {n}\nattachments: [{track}]\n"
        f'activity: {{"sport": "walk", "start": {T0 + n * 86_400_000}, "elapsed": 300, "moving": 300, '
        f'"distance": 400.0, "file": "{track}"}}\n---\n', encoding="utf-8")


def event(data, n: int, day: str) -> None:
    (data / "agenda" / "2026").mkdir(parents=True, exist_ok=True)
    (data / "agenda" / "2026" / f"rdv-e{n}.md").write_text(
        f"---\nid: e{n}\ntitle: Rdv {n}\nstart: {day}T10:00\nend: {day}T11:00\ntz: Europe/Paris\n---\n", encoding="utf-8")


def bump(path, text: str | None = None) -> None:
    """Rewrites a file with a later modification time (the tests run faster than the clock's resolution)."""
    if text is not None:
        path.write_text(text, encoding="utf-8")
    st = path.stat()
    os.utime(path, ns=(st.st_atime_ns, st.st_mtime_ns + 5_000_000_000))


def warm_client(make_client):
    client = make_client(MEMENTO_WARM="true", MEMENTO_POLL_SECONDS="3600")
    assert client.app.state.warmer.ready.wait(30)
    assert login(client).status_code == 200
    return client


# ---- the saved index ----


@pytest.fixture
def snapshots(tmp_path):
    return Snapshots(tmp_path / "cache", b"k" * 32)


def test_a_saved_index_comes_back_while_its_files_are_unchanged(snapshots, tmp_path):
    root = tmp_path / "data"
    root.mkdir()
    (root / "a.md").write_text("un")
    (root / "b.md").write_text("deux")
    entries = {p: (snap.stamp_of(str(root / p)), {"parsed": p}) for p in ("a.md", "b.md")}
    snapshots.save("notes", entries, {"zone": "Europe/Paris"})
    loaded, meta = snapshots.load("notes")
    assert loaded == entries and meta == {"zone": "Europe/Paris"}
    assert snapshots.valid(root, loaded) == entries
    bump(root / "a.md")  # same size, another time
    (root / "b.md").write_text("trois")  # another size
    os.utime(root / "b.md", ns=(entries["b.md"][0][0], entries["b.md"][0][0]))  # ... with the saved time
    assert snapshots.valid(root, loaded) == {}
    (root / "a.md").unlink()
    assert snapshots.valid(root, {"a.md": loaded["a.md"]}) == {}


def test_an_index_of_another_format_or_core_version_is_ignored(snapshots, monkeypatch):
    snapshots.save("notes", {"a.md": ((1, 1), "x")})
    assert snapshots.load("notes") is not None
    assert snapshots.load("contacts") is None  # never saved
    snapshots._code = "another core"
    assert snapshots.load("notes") is None
    snapshots._code = None
    monkeypatch.setattr(snap, "FORMAT", snap.FORMAT + 1)
    assert snapshots.load("notes") is None  # the core's hash includes the format too


def test_a_damaged_or_foreign_index_is_ignored(snapshots, capsys):
    snapshots.save("notes", {"a.md": ((1, 1), "x")})
    file = snapshots.file("notes")
    raw = file.read_bytes()
    file.write_bytes(raw[:-5] + b"xxxxx")
    assert snapshots.load("notes") is None
    file.write_bytes(raw[:20])
    assert snapshots.load("notes") is None
    file.write_bytes(b"")
    assert snapshots.load("notes") is None
    file.write_bytes(raw)
    assert Snapshots(snapshots.directory, b"o" * 32).load("notes") is None  # signed with another secret
    assert snapshots.load("notes") is not None
    assert "ignored" in capsys.readouterr().out


# ---- warm-up and restart ----


def test_the_warm_up_reads_everything_and_a_restart_reuses_the_saved_index(make_client, folders, monkeypatch):
    data, config = folders
    for n in range(3):
        activity(data, n)
        event(data, n, f"2026-11-{10 + n}")
    client = warm_client(make_client)
    store = client.app.state.data.store
    index = client.app.state.warmer.index
    assert {"courses-abc123.md", "idee-def456.md", "activities/sortie-a2.md"} <= set(store.cached())
    assert len(index.files()) == 3 and index.sources is not None
    analyses = client.app.state.activities.cache
    assert len(analyses.pinned) == 3 and set(analyses.memory) == analyses.pinned
    assert {f"index-{a}.bin" for a in snap.AREAS} <= {p.name for p in (config / "cache").iterdir()}

    bump(data / "idee-def456.md", note("def456", "Idée revue"))  # changed while the server was down
    from memento_web.core import fmt
    parsed = []
    real = fmt.parse
    monkeypatch.setattr(fmt, "parse", lambda path, *a, **k: (parsed.append(path), real(path, *a, **k))[1])
    again = warm_client(make_client)
    assert parsed == ["idee-def456.md"]  # everything else came from the saved index
    titles = {n["title"] for n in again.get("/api/notes").json()["notes"]}
    assert titles == {"Courses", "Idée revue"}
    assert len(again.get("/api/activities").json()["activities"]) == 3
    days = again.get("/api/agenda/entries", params={"from": "2026-11-01", "to": "2026-11-30", "tz": "Europe/Paris"})
    assert [e["title"] for e in days.json()["entries"]] == ["Rdv 0", "Rdv 1", "Rdv 2"]


def test_requests_made_during_the_warm_up_are_answered(make_client, monkeypatch):
    from memento_web import warm
    release = threading.Event()
    real = warm.Warmer.read_files

    def slow(self):
        release.wait(10)
        real(self)
    monkeypatch.setattr(warm.Warmer, "read_files", slow)
    client = make_client(MEMENTO_WARM="true", MEMENTO_POLL_SECONDS="3600")
    assert login(client).status_code == 200
    assert not client.app.state.warmer.ready.is_set()
    assert len(client.get("/api/notes").json()["notes"]) == 2
    assert client.get("/api/agenda/entries", params={"from": "2026-11-01", "to": "2026-11-30"}).status_code == 200
    release.set()
    assert client.app.state.warmer.ready.wait(10)
    assert len(client.get("/api/notes").json()["notes"]) == 2


def test_the_idle_trim_keeps_the_recent_analyses_only(make_client, folders):
    for n in range(3):
        activity(folders[0], n)
    client = warm_client(make_client)
    activities = client.app.state.activities
    pinned = set(activities.cache.pinned)
    assert client.get("/api/activities/a0/analysis").status_code == 200
    activities.recent = 2
    activities.prepare_recent()
    data = client.app.state.data
    assert len(activities.cache.memory) == 3
    assert data.trim_if_idle(now=data.last_request + 1000)
    assert set(activities.cache.memory) == activities.cache.pinned and len(activities.cache.pinned) == 2
    assert activities.cache.pinned < pinned


# ---- changes ----


def test_a_change_is_read_before_it_is_announced_and_only_the_changed_file_is(make_client, data_dir, monkeypatch):
    from memento_web.core import fmt
    client = make_client(MEMENTO_POLL_SECONDS="3600")  # the test runs the check itself
    assert login(client).status_code == 200
    app = client.app
    data, watcher = app.state.data, app.state.data.watcher
    assert client.get("/api/notes").status_code == 200
    event(data_dir, 1, "2026-11-12")
    client.get("/api/agenda/entries", params={"from": "2026-11-01", "to": "2026-11-30"})
    parsed = []
    real = fmt.parse
    monkeypatch.setattr(fmt, "parse", lambda path, *a, **k: (parsed.append(path), real(path, *a, **k))[1])
    bump(data_dir / "courses-abc123.md", note("abc123", "Courses du jour"))
    bump(data_dir / "agenda" / "2026" / "rdv-e1.md",
         "---\nid: e1\ntitle: Rdv déplacé\nstart: 2026-11-13T10:00\ntz: Europe/Paris\n---\n")
    seen = []

    async def check():
        queue: asyncio.Queue = asyncio.Queue()
        watcher._subscribers.add(queue)
        try:
            areas = await watcher.check()
            seen.append((areas, data.store.cached()["courses-abc123.md"][1].title,
                         [e.title for e in app.state.warmer.index.sources.events]))
            return await asyncio.wait_for(queue.get(), 2)
        finally:
            watcher._subscribers.discard(queue)
    message = client.portal.call(check)  # on the app's own loop, as the polling task runs it
    assert '"notes"' in message and '"agenda"' in message
    assert seen and set(seen[0][0]) >= {"notes", "agenda"}
    assert seen[0][1] == "Courses du jour" and seen[0][2] == ["Rdv déplacé"]  # parsed before the event went out
    assert sorted(set(parsed)) == ["agenda/2026/rdv-e1.md", "courses-abc123.md"]
    parsed.clear()
    titles = {n["title"] for n in client.get("/api/notes").json()["notes"]}
    assert "Courses du jour" in titles and parsed == []  # the request found it ready


def test_changes_are_saved_once_the_folder_is_quiet(make_client, folders, monkeypatch):
    from memento_web import warm
    data, config = folders
    client = warm_client(make_client)
    warmer = client.app.state.warmer
    saved = (config / "cache" / "index-notes.bin").stat().st_mtime_ns
    monkeypatch.setattr(warm, "SAVE_AFTER", 0.2)
    bump(data / "idee-def456.md", note("def456", "Idée sauvée"))
    warmer.refresh(["notes"])
    warmer.tick()  # too soon
    time.sleep(0.3)
    warmer.tick()
    deadline = time.monotonic() + 5
    while (config / "cache" / "index-notes.bin").stat().st_mtime_ns == saved and time.monotonic() < deadline:
        time.sleep(0.05)
    entries, _ = warmer.snapshots.load("notes")
    assert entries["idee-def456.md"][1].title == "Idée sauvée"


def test_list_answers_are_reused_while_the_objects_are_the_same(client, data_dir):
    from memento_web.memo import Answers
    answers, calls = Answers(size=2), []
    a, b = object(), object()
    assert answers.body("k", [a, b], lambda: calls.append(1) or {"n": 1}) == b'{"n":1}'
    assert answers.body("k", [a, b], lambda: calls.append(1) or {"n": 2}) == b'{"n":1}' and len(calls) == 1
    assert answers.body("k", [b, a], lambda: calls.append(1) or {"n": 3}) == b'{"n":3}'  # another order
    first = client.get("/api/notes").json()
    assert client.get("/api/notes").json() == first
    bump(data_dir / "idee-def456.md", note("def456", "Idée changée"))
    assert "Idée changée" in {n["title"] for n in client.get("/api/notes").json()["notes"]}
