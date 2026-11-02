"""Login, sessions and the brute-force throttle.

Sessions are opaque random tokens (32 bytes) kept server side, not signed cookies: logout really revokes one, there
is no cryptography of our own to get wrong, and only a SHA-256 of each token is written to /config/sessions.json, so
that file is useless to a thief. Each session carries a fingerprint of the configured user and password (an HMAC
under the session secret): changing MEMENTO_PASSWORD (or the user) closes every session. A session lives exactly
MEMENTO_SESSION_DAYS from the login, never extended.
"""
from __future__ import annotations

import hashlib
import hmac
import ipaddress
import json
import os
import secrets
import threading
import time
import uuid
from dataclasses import dataclass

from starlette.requests import Request
from starlette.responses import JSONResponse

from .config import Config
from .errors import ApiError

COOKIE = "memento_session"
MAX_SESSIONS = 50


def password_fingerprint(config: Config) -> str:
    message = b"memento-web-credentials\0" + config.user.encode() + b"\0" + config.password.encode()
    return hmac.new(config.session_secret, message, hashlib.sha256).hexdigest()


def _token_hash(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()


@dataclass
class Session:
    created: float
    expires: float
    fingerprint: str


class Sessions:
    def __init__(self, config: Config, clock=time.time):
        self.config = config
        self.clock = clock
        self.file = config.config_dir / "sessions.json"
        self._lock = threading.Lock()
        self._items: dict[str, Session] = self._load()

    def _load(self) -> dict[str, Session]:
        try:
            raw = json.loads(self.file.read_text(encoding="utf-8"))
        except FileNotFoundError:
            return {}
        except (OSError, ValueError) as error:
            print(f"memento-web: {self.file} ignored ({error}); every session is closed", flush=True)
            return {}
        current = password_fingerprint(self.config)
        now = self.clock()
        return {k: Session(v["created"], v["expires"], v["fingerprint"]) for k, v in raw.items()
                if v.get("fingerprint") == current and v.get("expires", 0) > now}

    def _save(self) -> None:
        data = json.dumps({k: s.__dict__ for k, s in self._items.items()})
        tmp = self.file.with_name(f".sessions.{uuid.uuid4().hex}.tmp")
        fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            handle.write(data)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(tmp, self.file)

    def create(self) -> tuple[str, Session]:
        token = secrets.token_urlsafe(32)
        now = self.clock()
        session = Session(now, now + self.config.session_days * 86400, password_fingerprint(self.config))
        with self._lock:
            self._items = {k: s for k, s in self._items.items() if s.expires > now}
            while len(self._items) >= MAX_SESSIONS:
                del self._items[min(self._items, key=lambda k: self._items[k].created)]
            self._items[_token_hash(token)] = session
            self._save()
        return token, session

    def get(self, token: str | None) -> Session | None:
        if not token or len(token) > 200:
            return None
        with self._lock:
            session = self._items.get(_token_hash(token))
        if session is None or session.expires <= self.clock():
            return None
        if not hmac.compare_digest(session.fingerprint, password_fingerprint(self.config)):
            return None
        return session

    def revoke(self, token: str | None) -> None:
        if not token:
            return
        with self._lock:
            if self._items.pop(_token_hash(token), None) is not None:
                self._save()


class Throttle:
    """Per client IP, and globally (a spread-out attack): the first FREE failures cost nothing, then each failure
    closes the door for 2^(n - FREE) seconds, up to LOCKOUT (15 min). A success clears the IP's record; a record
    with no failure for an hour is forgotten. Kept in memory: a restart forgets it, which costs an attacker a
    restart they cannot trigger."""

    FREE = 3
    GLOBAL_FREE = 30
    LOCKOUT = 900
    FORGET = 3600

    def __init__(self, clock=time.monotonic):
        self.clock = clock
        self._lock = threading.Lock()
        self._failures: dict[str, tuple[int, float]] = {}  # key -> (count, time of the last failure)

    def _wait(self, key: str, free: int, now: float) -> float:
        count, last = self._failures.get(key, (0, 0.0))
        if not count or now - last > self.FORGET:
            return 0.0
        if count <= free:
            return 0.0
        return max(0.0, last + min(2 ** (count - free), self.LOCKOUT) - now)

    def retry_after(self, ip: str) -> float:
        now = self.clock()
        with self._lock:
            return max(self._wait(ip, self.FREE, now), self._wait("*", self.GLOBAL_FREE, now))

    def failed(self, ip: str) -> None:
        now = self.clock()
        with self._lock:
            for key in (ip, "*"):
                count, last = self._failures.get(key, (0, 0.0))
                if now - last > self.FORGET:
                    count = 0
                self._failures[key] = (count + 1, now)
            if len(self._failures) > 10_000:  # bounded memory under a flood of addresses
                self._failures = {k: v for k, v in self._failures.items() if now - v[1] <= self.FORGET}

    def succeeded(self, ip: str) -> None:
        with self._lock:
            self._failures.pop(ip, None)


def _peer_trusted(request: Request, config: Config) -> bool:
    if not config.trust_proxy:
        return False
    if not config.trusted_proxies:
        return True
    try:
        peer = ipaddress.ip_address(request.client.host if request.client else "")
    except ValueError:
        return False
    return any(peer in net for net in config.trusted_proxies)


def client_ip(request: Request, config: Config) -> str:
    """CF-Connecting-IP (the visitor, set by Cloudflare) only from a trusted proxy; otherwise the TCP peer."""
    if _peer_trusted(request, config):
        header = request.headers.get("cf-connecting-ip", "").strip()
        try:
            return str(ipaddress.ip_address(header))
        except ValueError:
            pass
    return request.client.host if request.client else "?"


def is_https(request: Request, config: Config) -> bool:
    if request.url.scheme == "https":
        return True
    return _peer_trusted(request, config) and request.headers.get("x-forwarded-proto", "").lower() == "https"


def cookie_secure(request: Request, config: Config) -> bool:
    if config.cookie_secure == "auto":
        return is_https(request, config)
    return config.cookie_secure == "true"


def _same(a: str, b: str) -> bool:
    return hmac.compare_digest(a.encode("utf-8"), b.encode("utf-8"))


class Auth:
    def __init__(self, config: Config, sessions: Sessions | None = None, throttle: Throttle | None = None):
        self.config = config
        self.sessions = sessions or Sessions(config)
        self.throttle = throttle or Throttle()

    def session_of(self, request: Request) -> Session | None:
        return self.sessions.get(request.cookies.get(COOKIE))

    async def login(self, request: Request) -> JSONResponse:
        ip = client_ip(request, self.config)
        wait = self.throttle.retry_after(ip)
        if wait > 0:
            seconds = int(wait) + 1
            raise ApiError(429, f"Trop de tentatives. Réessayez dans {_duration(seconds)}.", retry_after=seconds)
        try:
            body = await request.json()
            user, password = str(body["user"]), str(body["password"])
        except (ValueError, KeyError, TypeError):
            raise ApiError(400, "Identifiant et mot de passe requis") from None
        # Both comparisons always run, so the time taken does not tell whether the user name was right.
        ok = _same(user, self.config.user) & _same(password, self.config.password)
        if not ok:
            self.throttle.failed(ip)
            print(f"memento-web: failed login from {ip}", flush=True)
            raise ApiError(401, "Identifiant ou mot de passe incorrect")
        self.throttle.succeeded(ip)
        token, session = self.sessions.create()
        response = JSONResponse({"user": self.config.user, "expires": int(session.expires * 1000)})
        response.set_cookie(COOKIE, token, max_age=self.config.session_days * 86400, path="/", httponly=True,
                            secure=cookie_secure(request, self.config), samesite="strict")
        return response

    async def logout(self, request: Request) -> JSONResponse:
        self.sessions.revoke(request.cookies.get(COOKIE))
        response = JSONResponse({"ok": True})
        response.delete_cookie(COOKIE, path="/", httponly=True, secure=cookie_secure(request, self.config),
                               samesite="strict")
        return response

    async def me(self, request: Request) -> JSONResponse:
        session = self.session_of(request)
        if session is None:
            raise ApiError(401, "Session expirée, reconnectez-vous")
        return JSONResponse({"user": self.config.user, "expires": int(session.expires * 1000),
                             "build": os.environ.get("MEMENTO_BUILD", "dev")})


def _duration(seconds: int) -> str:
    if seconds < 60:
        return f"{seconds} s"
    return f"{(seconds + 59) // 60} min"

