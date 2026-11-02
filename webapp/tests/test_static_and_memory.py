from __future__ import annotations

import gzip

import pytest


@pytest.fixture
def static(tmp_path):
    root = tmp_path / "static"
    (root / "assets").mkdir(parents=True)
    (root / "index.html").write_text("<!doctype html><title>Mémento</title>")
    (root / "assets" / "index-abc123.js").write_text("console.log('x')")
    (root / "assets" / "index-abc123.js.gz").write_bytes(gzip.compress(b"console.log('x')"))
    return root


def test_ui_is_public_with_hashed_assets_cached_for_good(make_client, static):
    client = make_client(MEMENTO_STATIC=str(static))
    page = client.get("/")
    assert page.status_code == 200 and "Mémento" in page.text
    assert page.headers["cache-control"] == "no-cache"
    asset = client.get("/assets/index-abc123.js", headers={"Accept-Encoding": "gzip"})
    assert asset.headers["content-encoding"] == "gzip"
    assert asset.text == "console.log('x')"  # decoded by the client
    assert "immutable" in asset.headers["cache-control"]
    plain = client.get("/assets/index-abc123.js", headers={"Accept-Encoding": "identity"})
    assert "content-encoding" not in plain.headers and plain.text == "console.log('x')"


def test_unknown_paths_get_the_app_and_unknown_api_paths_a_json_404(make_client, static):
    client = make_client(MEMENTO_STATIC=str(static))
    assert "Mémento" in client.get("/une/page/profonde").text
    assert client.get("/assets/absent-zzz.js").status_code in (200, 404)


def test_idle_trim_drops_caches_once_per_idle_period(client):
    data = client.app.state.data
    client.get("/api/notes")
    before = data.store
    calls = []
    data.idle_hooks.append(lambda: calls.append(1))
    assert not data.trim_if_idle(now=data.last_request + 10)
    assert data.trim_if_idle(now=data.last_request + 121)
    assert data.store is not before and calls == [1]
    assert not data.trim_if_idle(now=data.last_request + 500)  # once per idle period
    client.get("/api/notes")
    assert not data.trimmed
    assert client.get("/api/notes").json()["notes"]  # the cache rebuilds itself
