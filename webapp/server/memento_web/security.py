"""The gate every request passes: security headers on every answer, the session required everywhere but the login
page, its assets and /healthz, and the CSRF rules on every mutating request.

CSRF, in depth: the cookie is SameSite=Strict; every POST/PUT/PATCH/DELETE must carry `X-Memento: 1` (a header a
cross-site form cannot set, and a cross-site fetch cannot set without a CORS preflight this server never grants);
and an `Origin` header, when the browser sends one, must name this very host.

Request bodies are capped while they stream in (MAX_BODY), so no request makes the server hold more than that in
memory: a few kB before the login, the module's own limit on the upload routes (each also enforces a stricter one).
"""
from __future__ import annotations

from urllib.parse import urlsplit

import re

from starlette.datastructures import MutableHeaders
from starlette.requests import Request
from starlette.responses import JSONResponse

from .auth import Auth, is_https
from .errors import ApiError

MUTATING = {"POST", "PUT", "PATCH", "DELETE"}
PUBLIC_API = {"/api/login", "/api/logout", "/healthz"}
# Everything that reads the data, or spends the server's bandwidth (tiles), needs a session.
PROTECTED_PREFIXES = ("/api/", "/t/", "/peaks", "/buildings/", "/trails/", "/map/")

_BASE_CSP = ("default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; "
             "font-src 'self'; connect-src 'self'; media-src 'self' blob:; worker-src 'self' blob:; child-src 'self' blob:; "
             "frame-src 'self'; object-src 'self'; base-uri 'none'; form-action 'self'")
APP_CSP = _BASE_CSP + "; frame-ancestors 'none'"
# The map page is embedded by the Activités module in an <iframe> of this same site.
MAP_CSP = _BASE_CSP + "; frame-ancestors 'self'"


MAX_PUBLIC_BODY = 16 * 1024
MAX_BODY = 8_000_000
MAX_UPLOAD_BODY = 64_000_000
UPLOADS = re.compile(r"^/api/(?:notes/.+/attachments|activities/uploads|agenda/import|contacts/import)$")
HSTS = "max-age=31536000"


def body_limit(path: str, authenticated: bool) -> int:
    if not authenticated:
        return MAX_PUBLIC_BODY
    return MAX_UPLOAD_BODY if UPLOADS.match(path) else MAX_BODY


def capped(receive, limit: int):
    """`receive` refusing (413) a body that grows past `limit`, whatever Content-Length said."""
    seen = 0

    async def wrapped():
        nonlocal seen
        message = await receive()
        if message["type"] == "http.request":
            seen += len(message.get("body", b""))
            if seen > limit:
                raise ApiError(413, f"Requête trop volumineuse ({limit // 1000} ko au plus)")
        return message
    return wrapped


def is_protected(path: str) -> bool:
    return path not in PUBLIC_API and path.startswith(PROTECTED_PREFIXES)


def origin_ok(request: Request) -> bool:
    origin = request.headers.get("origin")
    if origin is None:
        return True
    host = request.headers.get("host", "")
    return origin != "null" and urlsplit(origin).netloc.lower() == host.lower()


class Guard:
    def __init__(self, app, auth: Auth, on_request=None):
        self.app = app
        self.auth = auth
        self.on_request = on_request  # the idle timer's reset

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return
        if self.on_request is not None:
            self.on_request()
        request = Request(scope)
        path = scope["path"]
        is_map = path.startswith("/map/")
        https = is_https(request, self.auth.config)

        async def send_with_headers(message):
            if message["type"] == "http.response.start":
                headers = MutableHeaders(scope=message)
                headers.setdefault("Content-Security-Policy", MAP_CSP if is_map else APP_CSP)
                headers.setdefault("X-Frame-Options", "SAMEORIGIN" if is_map else "DENY")
                headers.setdefault("X-Content-Type-Options", "nosniff")
                headers.setdefault("Referrer-Policy", "no-referrer")
                headers.setdefault("Cross-Origin-Opener-Policy", "same-origin")
                headers.setdefault("Cross-Origin-Resource-Policy", "same-origin")
                headers.setdefault("Permissions-Policy", "camera=(), microphone=(), geolocation=(self), payment=(), usb=()")
                if https:
                    headers.setdefault("Strict-Transport-Security", HSTS)
                if path.startswith("/api/"):
                    headers.setdefault("Cache-Control", "no-store")
            await send(message)

        refusal = self._refusal(request, path)
        if refusal is not None:
            await refusal(scope, receive, send_with_headers)
            return
        limit = body_limit(path, "session" in scope.get("state", {}))
        length = request.headers.get("content-length", "")
        if length.isdigit() and int(length) > limit:
            await ApiError(413, f"Requête trop volumineuse ({limit // 1000} ko au plus)").response()(
                scope, receive, send_with_headers)
            return
        await self.app(scope, capped(receive, limit), send_with_headers)

    def _refusal(self, request: Request, path: str) -> JSONResponse | None:
        if request.method in MUTATING:
            if request.headers.get("x-memento") != "1" or not origin_ok(request):
                return JSONResponse({"error": "Requête refusée (protection CSRF)"}, status_code=403)
        if is_protected(path):
            session = self.auth.session_of(request)
            if session is None:
                return JSONResponse({"error": "Session expirée, reconnectez-vous"}, status_code=401)
            request.scope.setdefault("state", {})["session"] = session
        return None
