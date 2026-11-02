"""Adversarial checks: every route behind the session and the CSRF rules, cookies, the login throttle's lockout,
request size caps, and files arriving by sync that try to reach outside what they own."""
from __future__ import annotations

import io
import re
import urllib.error
import urllib.request
import zipfile

import pytest
from starlette.routing import Mount, Route
from starlette.testclient import TestClient

from conftest import HEADERS, PASSWORD, USER, login, make_env
from memento_web.app import create_app
from memento_web.auth import COOKIE, DEVICE_COOKIE, Throttle, device_of
from memento_web.config import from_env
from memento_web.events import MAX_STREAMS
from memento_web.modules.maphost import SameHostRedirects
from memento_web.security import MAX_PUBLIC_BODY, MUTATING

PUBLIC = {"/healthz", "/api/login", "/api/logout", "/{rest:path}"}  # the last: the UI's files when not built


def concrete(path: str) -> str:
    """A route's path with every parameter filled in."""
    return re.sub(r"\{(\w+)(?::\w+)?\}", lambda m: {"path": "x-abcdef.md", "rest": "x"}.get(m[1], "x"), path)


def api_routes(app) -> list[tuple[str, str]]:
    out = []
    for route in app.routes:
        if isinstance(route, Route) and not isinstance(route, Mount):
            for method in sorted(route.methods - {"HEAD"}):
                out.append((method, route.path))
    return out


def test_every_route_but_the_public_ones_needs_a_session(make_client):
    client = make_client()
    routes = api_routes(client.app)
    assert len(routes) > 60  # the enumeration really found the modules' routes
    for method, path in routes:
        if path in PUBLIC:
            continue
        response = client.request(method, concrete(path), headers=HEADERS)
        assert response.status_code == 401, (method, path, response.status_code)


def test_every_mutating_route_needs_the_csrf_header_and_a_same_origin(client):
    for method, path in api_routes(client.app):
        if method not in MUTATING:
            continue
        url = concrete(path)
        assert client.request(method, url).status_code == 403, (method, path)
        assert client.request(method, url, headers={**HEADERS, "Origin": "https://evil.example"}).status_code == 403
        assert client.request(method, url, headers={**HEADERS, "Origin": "null"}).status_code == 403


def test_the_static_ui_and_its_unknown_paths_need_no_session_but_reveal_nothing(make_client):
    client = make_client()
    for path in ("/", "/index.html", "/whatever"):
        assert client.get(path).status_code in (200, 503)


def https_client(folders, net, **extra):
    data, config = folders
    app = create_app(from_env(make_env(data, config, **extra)), fetch=net)
    client = TestClient(app, base_url="https://testserver")
    client.__enter__()
    return client


def test_over_https_the_cookies_are_host_prefixed_and_hsts_is_sent(folders, net):
    client = https_client(folders, net)
    try:
        response = login(client)
        assert response.status_code == 200
        cookies = response.headers.get_list("set-cookie")
        session = next(c for c in cookies if c.startswith(f"__Host-{COOKIE}="))
        assert all(f in session.lower() for f in ("secure", "httponly", "path=/", "samesite=strict"))
        assert "domain=" not in session.lower()
        assert any(c.startswith(f"__Host-{DEVICE_COOKIE}=") for c in cookies)
        assert client.get("/api/me").status_code == 200
        assert response.headers["strict-transport-security"].startswith("max-age=")
        # a cookie without the prefix (planted over http, or by a sibling domain) is not read over https
        token = client.cookies[f"__Host-{COOKIE}"]
        client.cookies.clear()
        client.cookies.set(COOKIE, token)
        assert client.get("/api/me").status_code == 401
    finally:
        client.__exit__(None, None, None)


def test_no_hsts_over_plain_http(make_client):
    assert "strict-transport-security" not in make_client().get("/healthz").headers


def test_an_attacker_locking_the_login_out_does_not_lock_out_the_owners_browser(make_client):
    owner = make_client()
    assert login(owner).status_code == 200  # the owner's browser is now a known device
    assert device_of(owner.app.state.auth.config, owner.cookies[DEVICE_COOKIE])
    owner.post("/api/logout", headers=HEADERS)
    auth = owner.app.state.auth
    for i in range(Throttle.GLOBAL_FREE + 10):  # from everywhere, the global record too
        auth.throttle.failed(f"10.0.{i // 250}.{i % 250}")
        auth.throttle.failed("testclient")
    attacker = make_client()
    attacker.app.state.auth.throttle = auth.throttle
    assert login(attacker).status_code == 429
    assert login(owner).status_code == 200
    # a forged device cookie is no way around the lockout
    attacker.cookies.set(DEVICE_COOKIE, "forged.0000")
    assert login(attacker).status_code == 429


def test_a_known_device_is_still_throttled_on_its_own(make_client):
    client = make_client()
    login(client)
    for _ in range(4):
        login(client, password="wrong guess")
    assert login(client).status_code == 429


def test_login_replaces_the_session_the_browser_held(make_client):
    client = make_client()
    login(client)
    first = client.cookies[COOKIE]
    login(client)
    assert client.cookies[COOKIE] != first
    probe = make_client()
    probe.cookies.set(COOKIE, first)
    assert probe.get("/api/me").status_code == 401


def test_session_tokens_are_long_and_random(make_client):
    tokens = set()
    for _ in range(3):
        client = make_client()
        login(client)
        tokens.add(client.cookies[COOKIE])
    assert len(tokens) == 3 and all(len(t) >= 43 for t in tokens)


def test_request_bodies_are_capped_before_and_after_the_login(make_client):
    anonymous = make_client()
    big = {"user": USER, "password": "x" * (MAX_PUBLIC_BODY + 1)}
    assert anonymous.post("/api/login", json=big, headers=HEADERS).status_code == 413

    def chunks():  # no Content-Length: the cap counts what streams in
        for _ in range(20):
            yield b"x" * 1_000_000
    client = make_client()
    login(client)
    response = client.post("/api/notes", content=chunks(), headers={**HEADERS, "Content-Type": "application/json"})
    assert response.status_code == 413


def test_server_errors_do_not_leak_paths_or_values(client):
    def broken():
        raise RuntimeError("/data/secret/path.md: token=abc")
    client.app.state.data.store.scan = broken
    quiet = TestClient(client.app, raise_server_exceptions=False, cookies=dict(client.cookies))
    response = quiet.get("/api/notes")
    assert response.status_code == 500
    assert "secret" not in response.text and "RuntimeError" not in response.text
    assert "réf." in response.json()["error"]


def test_deleting_a_note_deletes_only_the_files_it_owns(client, data_dir):
    (data_dir / "contacts").mkdir()
    (data_dir / "contacts" / "ami-aaaaaa.md").write_text("---\nid: aaaaaa\n---\n")
    (data_dir / "attachments").mkdir()
    (data_dir / "attachments" / "mine-bbbbbb.pdf").write_bytes(b"%PDF")
    (data_dir / "evil-cccccc.md").write_text(
        "---\nid: cccccc\ntitle: Evil\npdf: courses-abc123.md\n"
        "attachments: [contacts/ami-aaaaaa.md, idee-def456.md, attachments/mine-bbbbbb.pdf]\n---\n")
    version = client.get("/api/notes/evil-cccccc.md").json()["version"]
    assert client.delete("/api/notes/evil-cccccc.md", headers={**HEADERS, "If-Match": version}).status_code == 200
    assert (data_dir / "contacts" / "ami-aaaaaa.md").exists()
    assert (data_dir / "idee-def456.md").exists() and (data_dir / "courses-abc123.md").exists()
    assert not (data_dir / "attachments" / "mine-bbbbbb.pdf").exists()


def test_removing_a_listed_attachment_never_deletes_another_note(client, data_dir):
    (data_dir / "evil-cccccc.md").write_text("---\nid: cccccc\ntitle: Evil\nattachments: [idee-def456.md]\n---\n")
    version = client.get("/api/notes/evil-cccccc.md").json()["version"]
    response = client.delete("/api/notes/evil-cccccc.md/attachments", params={"file": "idee-def456.md"},
                             headers={**HEADERS, "If-Match": version})
    assert response.status_code == 200
    assert (data_dir / "idee-def456.md").exists()


@pytest.mark.parametrize("field", ["file", "alt"])
def test_an_activity_cannot_read_a_track_outside_the_folder(client, data_dir, tmp_path, field):
    secret = tmp_path / "config" / "sessions.json"
    (data_dir / "activities").mkdir()
    (data_dir / "attachments").mkdir()
    (data_dir / "attachments" / "t-dddddd.gpx").write_text(
        '<gpx><trk><trkseg><trkpt lat="45" lon="6"><time>2026-01-01T10:00:00Z</time></trkpt>'
        '<trkpt lat="45.001" lon="6.001"><time>2026-01-01T10:01:00Z</time></trkpt></trkseg></trk></gpx>')
    files = {"file": "attachments/t-dddddd.gpx", "alt": None, field: "../../config/sessions.json"}
    alt = f', "alt": "{files["alt"]}"' if files["alt"] else ""
    (data_dir / "activities" / "sortie-eeeeee.md").write_text(
        f'---\nid: eeeeee\ntitle: Sortie\nactivity: {{"sport": "walk", "start": 1767261600000, '
        f'"file": "{files["file"]}"{alt}}}\n---\n')
    response = client.get("/api/activities/eeeeee/analysis")
    assert secret.exists()
    if field == "file":
        assert response.status_code in (400, 404)
        assert "sessions" not in response.text
    else:  # the track opens, without the "altitude" read from elsewhere
        assert response.status_code == 200 and response.json()["chart"]["alt"] is None
    assert str(tmp_path) not in response.text


def test_a_zip_bomb_is_refused_before_it_is_expanded(client):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("a.ics", b"BEGIN:VCALENDAR\r\n" + b" " * 60_000_000)
    assert len(buffer.getvalue()) < 1_000_000
    response = client.post("/api/agenda/import", params={"name": "bomb.zip"}, content=buffer.getvalue(),
                           headers={**HEADERS, "Content-Type": "application/zip"})
    assert response.status_code == 413


def test_zip_entry_names_cannot_climb_out(client, data_dir):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        archive.writestr("../../../evil.ics", "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:x\r\nDTSTART:20260101T100000Z\r\n"
                                              "SUMMARY:x\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n")
    response = client.post("/api/agenda/import", params={"name": "x.zip"}, content=buffer.getvalue(),
                           headers={**HEADERS, "Content-Type": "application/zip"})
    assert response.status_code == 200
    assert not any(p.name == "evil.ics" for p in data_dir.parent.rglob("*"))


def test_event_streams_are_capped(client):
    watcher = client.app.state.data.watcher
    watcher._subscribers.update(object() for _ in range(MAX_STREAMS))
    try:
        assert client.get("/api/events").status_code == 503
    finally:
        watcher._subscribers.clear()


def test_map_upstream_redirects_to_another_host_are_refused():
    handler = SameHostRedirects()
    request = urllib.request.Request("https://data.geopf.fr/wmts?x=1")
    with pytest.raises(urllib.error.HTTPError):
        handler.redirect_request(request, None, 302, "Found", {}, "http://169.254.169.254/latest/meta-data")
    with pytest.raises(urllib.error.HTTPError):
        handler.redirect_request(request, None, 302, "Found", {}, "https://192.168.1.1/")
    assert handler.redirect_request(request, None, 302, "Found", {}, "https://data.geopf.fr/other") is not None


def test_the_map_page_may_only_be_framed_by_this_site(client):
    page = client.get("/map/index.html")
    assert page.status_code == 200
    assert "frame-ancestors 'self'" in page.headers["content-security-policy"]
    assert "'unsafe-inline'" not in page.headers["content-security-policy"].split("script-src")[1].split(";")[0]
    app = client.get("/healthz").headers["content-security-policy"]
    assert "frame-ancestors 'none'" in app and "script-src 'self';" in app


def test_untrusted_files_are_never_rendered_in_the_sites_origin(client, data_dir):
    (data_dir / "attachments").mkdir()
    for name, body in (("x.html", b"<script>alert(1)</script>"), ("x.svg", b"<svg onload='alert(1)'/>"),
                       ("x.xhtml", b"<html/>"), ("x.md", b"# x")):
        (data_dir / "attachments" / name).write_bytes(body)
        response = client.get(f"/api/files/attachments/{name}")
        assert response.headers["content-disposition"].startswith("attachment"), name
        assert "sandbox" in response.headers["content-security-policy"], name
        assert response.headers["x-content-type-options"] == "nosniff"
    (data_dir / "attachments" / 'a"; filename=evil.html').write_bytes(b"x")
    response = client.get('/api/files/attachments/a"; filename=evil.html')
    assert response.headers["content-disposition"] == "attachment; filename*=UTF-8''a%22%3B%20filename%3Devil.html"


@pytest.mark.parametrize("path", ["..%2fconfig%2fsessions.json", "attachments%2f..%2f..%2fconfig%2fsecret",
                                  "%00", "attachments/%00x", "attachments\\..\\x", "/etc/passwd",
                                  "attachments/‥/x", "attachments/．．/x"])
def test_file_paths_from_the_client_stay_inside(client, path):
    response = client.get(f"/api/files/{path}")
    assert response.status_code in (400, 404)
    assert "secret" not in response.text.lower() or response.status_code == 400
