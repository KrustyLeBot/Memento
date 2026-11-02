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
# Over https the cookies carry the __Host- prefix: the browser then only accepts them Secure, for this exact host and
# path /, so neither a sibling subdomain nor a plain-http page of the same name can plant or overwrite them.
SECURE_PREFIX = "__Host-"
# A browser that once logged in carries this signed id (one year): its logins are throttled on their own, so an
# attacker hammering the login cannot lock the owner out (OWASP "device cookies").
DEVICE_COOKIE = "memento_device"
DEVICE_DAYS = 365
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
    restart they cannot trigger.

    A login from a known device (DEVICE_COOKIE) is counted under that device alone, never under the IP or the
    global record: those are what an attacker can fill, and the owner's own browser must keep getting in."""

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

    def _keys(self, ip: str, device: str | None) -> list[tuple[str, int]]:
        return [(f"device:{device}", self.FREE)] if device else [(ip, self.FREE), ("*", self.GLOBAL_FREE)]

    def retry_after(self, ip: str, device: str | None = None) -> float:
        now = self.clock()
        with self._lock:
            return max(self._wait(key, free, now) for key, free in self._keys(ip, device))

    def failed(self, ip: str, device: str | None = None) -> None:
        now = self.clock()
        with self._lock:
            for key, _free in self._keys(ip, device):
                count, last = self._failures.get(key, (0, 0.0))
                if now - last > self.FORGET:
                    count = 0
                self._failures[key] = (count + 1, now)
            if len(self._failures) > 10_000:  # bounded memory under a flood of addresses
                self._failures = {k: v for k, v in self._failures.items() if now - v[1] <= self.FORGET}

    def succeeded(self, ip: str, device: str | None = None) -> None:
        with self._lock:
            self._failures.pop(f"device:{device}" if device else ip, None)


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


def cookie_name(base: str, secure: bool) -> str:
    return SECURE_PREFIX + base if secure else base


def _same(a: str, b: str) -> bool:
    """Constant time, the length included: both sides are hashed first."""
    return hmac.compare_digest(hashlib.sha256(a.encode("utf-8")).digest(), hashlib.sha256(b.encode("utf-8")).digest())


def _device_mac(config: Config, device: str) -> str:
    return hmac.new(config.session_secret, b"memento-web-device\0" + device.encode(), hashlib.sha256).hexdigest()


def new_device(config: Config) -> str:
    device = secrets.token_urlsafe(16)
    return f"{device}.{_device_mac(config, device)}"


def device_of(config: Config, value: str | None) -> str | None:
    """The device id of a genuine DEVICE_COOKIE value, else None."""
    if not value or len(value) > 200 or "." not in value:
        return None
    device, mac = value.rsplit(".", 1)
    return device if hmac.compare_digest(mac.encode(), _device_mac(config, device).encode()) else None


class Auth:
    def __init__(self, config: Config, sessions: Sessions | None = None, throttle: Throttle | None = None):
        self.config = config
        self.sessions = sessions or Sessions(config)
        self.throttle = throttle or Throttle()

    def _cookie(self, request: Request, base: str) -> str | None:
        return request.cookies.get(cookie_name(base, cookie_secure(request, self.config)))

    def session_of(self, request: Request) -> Session | None:
        return self.sessions.get(self._cookie(request, COOKIE))

    async def login(self, request: Request) -> JSONResponse:
        ip = client_ip(request, self.config)
        device = device_of(self.config, self._cookie(request, DEVICE_COOKIE))
        wait = self.throttle.retry_after(ip, device)
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
            self.throttle.failed(ip, device)
            print(f"memento-web: failed login from {ip}{' (known device)' if device else ''}", flush=True)
            raise ApiError(401, "Identifiant ou mot de passe incorrect")
        self.throttle.succeeded(ip, device)
        self.sessions.revoke(self._cookie(request, COOKIE))  # whatever session the browser held is replaced
        token, session = self.sessions.create()
        response = JSONResponse({"user": self.config.user, "expires": int(session.expires * 1000)})
        secure = cookie_secure(request, self.config)
        response.set_cookie(cookie_name(COOKIE, secure), token, max_age=self.config.session_days * 86400, path="/",
                            httponly=True, secure=secure, samesite="strict")
        if device is None:
            response.set_cookie(cookie_name(DEVICE_COOKIE, secure), new_device(self.config),
                                max_age=DEVICE_DAYS * 86400, path="/", httponly=True, secure=secure, samesite="strict")
        return response

    async def logout(self, request: Request) -> JSONResponse:
        self.sessions.revoke(self._cookie(request, COOKIE))
        response = JSONResponse({"ok": True})
        secure = cookie_secure(request, self.config)
        response.delete_cookie(cookie_name(COOKIE, secure), path="/", httponly=True, secure=secure, samesite="strict")
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

