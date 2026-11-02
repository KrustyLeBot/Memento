from __future__ import annotations

import os

import pytest

from conftest import HEADERS, PASSWORD, USER, login, make_env
from memento_web.auth import COOKIE, Throttle
from memento_web.config import ConfigError, from_env


def test_login_sets_a_strict_http_only_cookie_for_seven_days(make_client):
    client = make_client(MEMENTO_COOKIE_SECURE="true")
    response = login(client)
    assert response.status_code == 200
    cookie = response.headers["set-cookie"]
    assert f"{COOKIE}=" in cookie
    for flag in ("HttpOnly", "Secure", "SameSite=strict", "Path=/", "Max-Age=604800"):
        assert flag.lower() in cookie.lower()
    assert PASSWORD not in cookie


def test_cookie_is_secure_automatically_behind_the_https_tunnel_only(make_client):
    lan = make_client(MEMENTO_TRUST_PROXY="true")
    assert "secure" not in login(lan).headers["set-cookie"].lower()
    tunnel = make_client(MEMENTO_TRUST_PROXY="true")
    response = tunnel.post("/api/login", json={"user": USER, "password": PASSWORD},
                           headers={**HEADERS, "X-Forwarded-Proto": "https"})
    assert "secure" in response.headers["set-cookie"].lower()


def test_wrong_credentials_are_refused(make_client):
    client = make_client()
    assert login(client, password="nope").status_code == 401
    assert login(client, user="autre").status_code == 401
    assert COOKIE not in client.cookies
    assert client.get("/api/notes").status_code == 401


def test_session_gives_access_and_logout_revokes_it(make_client):
    client = make_client()
    login(client)
    token = client.cookies[COOKIE]
    assert client.get("/api/me").json()["user"] == USER
    assert client.post("/api/logout", headers=HEADERS).status_code == 200
    client.cookies.set(COOKIE, token)  # a stolen copy of the cookie is dead too
    assert client.get("/api/me").status_code == 401


@pytest.mark.parametrize("path", ["/api/notes", "/api/notes/courses-abc123.md", "/api/files/courses-abc123.md",
                                  "/api/events", "/api/conflicts", "/api/settings/activities", "/api/me",
                                  "/t/dem/1/0/0", "/peaks?w=6&s=45&e=6.1&n=45.1", "/map/index.html",
                                  "/api/drawings/drawings/dessin-aaaaaa.svg", "/api/unknown"])
def test_everything_but_the_login_needs_a_session(make_client, path):
    client = make_client()
    assert client.get(path).status_code == 401


def test_public_endpoints(make_client):
    client = make_client()
    assert client.get("/healthz").json() == {"ok": True}


def test_mutations_need_the_custom_header_and_a_matching_origin(client):
    assert client.post("/api/notes", json={"title": "x"}).status_code == 403
    assert client.post("/api/notes", json={"title": "x"},
                       headers={**HEADERS, "Origin": "https://evil.example"}).status_code == 403
    assert client.post("/api/notes", json={"title": "x"},
                       headers={**HEADERS, "Origin": "http://testserver"}).status_code == 201
    # the login itself too: no cross-site login
    assert client.post("/api/login", json={"user": USER, "password": PASSWORD}).status_code == 403


def test_session_expires_after_the_configured_days_without_extension(make_client):
    client = make_client()
    login(client)
    sessions = client.app.state.auth.sessions
    start = sessions.clock()
    sessions.clock = lambda: start + 6.9 * 86400
    assert client.get("/api/me").status_code == 200  # use does not extend it...
    sessions.clock = lambda: start + 7 * 86400 + 1
    assert client.get("/api/me").status_code == 401  # ...it ends 7 days after the login


def test_sessions_survive_a_restart_but_not_a_password_change(folders, make_client):
    first = make_client()
    login(first)
    token = first.cookies[COOKIE]
    again = make_client()
    again.cookies.set(COOKIE, token)
    assert again.get("/api/me").status_code == 200
    changed = make_client(MEMENTO_PASSWORD="a brand new passphrase")
    changed.cookies.set(COOKIE, token)
    assert changed.get("/api/me").status_code == 401
    # sessions.json holds hashes, never the token itself
    assert token not in (folders[1] / "sessions.json").read_text()


def test_throttle_backs_off_exponentially_then_locks_out():
    now = [1000.0]
    t = Throttle(clock=lambda: now[0])
    for _ in range(3):
        t.failed("1.2.3.4")
    assert t.retry_after("1.2.3.4") == 0  # three free tries
    t.failed("1.2.3.4")
    assert t.retry_after("1.2.3.4") == pytest.approx(2)
    t.failed("1.2.3.4")
    assert t.retry_after("1.2.3.4") == pytest.approx(4)
    for _ in range(20):
        t.failed("1.2.3.4")
    assert t.retry_after("1.2.3.4") == pytest.approx(900)  # capped: the lockout window
    assert t.retry_after("5.6.7.8") == 0  # another address is not punished...
    now[0] += 901
    assert t.retry_after("1.2.3.4") == 0
    t.succeeded("1.2.3.4")


def test_throttle_has_a_global_limit_against_spread_attacks():
    t = Throttle(clock=lambda: 0.0)
    for i in range(Throttle.GLOBAL_FREE + 1):
        t.failed(f"10.0.0.{i}")
    assert t.retry_after("192.168.1.9") > 0


def test_login_answers_429_with_retry_after_once_throttled(make_client):
    client = make_client()
    for _ in range(4):
        login(client, password="wrong one")
    response = login(client)  # even the right password waits
    assert response.status_code == 429
    assert int(response.headers["retry-after"]) >= 1
    assert "Réessayez" in response.json()["error"]


def test_cf_connecting_ip_is_only_trusted_when_enabled(make_client):
    spoofer = make_client()
    for i in range(4):
        spoofer.post("/api/login", json={"user": USER, "password": "x"}, headers={**HEADERS, "CF-Connecting-IP": f"9.9.9.{i}"})
    # without MEMENTO_TRUST_PROXY the header is ignored: all four counted against the real peer
    assert login(spoofer).status_code == 429

    tunnel = make_client(MEMENTO_TRUST_PROXY="true")
    for _ in range(4):
        tunnel.post("/api/login", json={"user": USER, "password": "x"}, headers={**HEADERS, "CF-Connecting-IP": "9.9.9.9"})
    response = tunnel.post("/api/login", json={"user": USER, "password": PASSWORD},
                           headers={**HEADERS, "CF-Connecting-IP": "8.8.8.8"})
    assert response.status_code == 200


def test_security_headers(make_client):
    client = make_client()
    headers = client.get("/healthz").headers
    assert "frame-ancestors 'none'" in headers["content-security-policy"]
    assert "default-src 'self'" in headers["content-security-policy"]
    assert headers["x-frame-options"] == "DENY"
    assert headers["x-content-type-options"] == "nosniff"
    assert headers["referrer-policy"] == "no-referrer"


def test_the_password_is_never_logged(make_client, capsys):
    client = make_client()
    login(client, password="guess-" + PASSWORD)
    login(client)
    out = capsys.readouterr()
    assert PASSWORD not in out.out + out.err


def test_startup_refuses_missing_credentials_and_unwritable_data(folders):
    data, config = folders
    with pytest.raises(ConfigError, match="MEMENTO_USER"):
        from_env({**make_env(data, config), "MEMENTO_PASSWORD": ""})
    with pytest.raises(ConfigError, match="n'existe pas"):
        from_env(make_env(data / "absent", config))
    if os.getuid() != 0:  # root writes everywhere
        (data / "agenda").mkdir()
        (data / "agenda").chmod(0o555)
        try:
            with pytest.raises(ConfigError, match="écriture"):
                from_env(make_env(data, config))
        finally:
            (data / "agenda").chmod(0o755)
    assert not [p for p in data.rglob("*") if "synctmp" in p.name]


def test_session_secret_is_generated_once_and_private(folders):
    data, config = folders
    first = from_env(make_env(data, config)).session_secret
    assert from_env(make_env(data, config)).session_secret == first
    assert oct((config / "secret").stat().st_mode & 0o777) == "0o600"
