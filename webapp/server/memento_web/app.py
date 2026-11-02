"""The ASGI application. Each module is one class in memento_web/modules/ exposing `routes()`; add yours to MODULES
order below (before the static UI, which answers everything else)."""
from __future__ import annotations

import contextlib
import mimetypes
import re
import traceback
from pathlib import Path

from starlette.applications import Starlette
from starlette.exceptions import HTTPException
from starlette.requests import Request
from starlette.responses import FileResponse, JSONResponse, Response
from starlette.routing import Mount, Route
from starlette.staticfiles import StaticFiles

from .auth import Auth
from .config import Config
from .data import Data
from .errors import ApiError
from .modules.common import Common
from .modules.contacts import Contacts
from .modules.maphost import MapHost
from .modules.notes import Notes
from .security import Guard


def default_static_dir() -> Path:
    """The built UI: webapp/ui/dist in a checkout, /app/static in the image."""
    here = Path(__file__).resolve().parent
    for candidate in (here.parent / "static", here.parents[1] / "ui" / "dist"):
        if (candidate / "index.html").is_file():
            return candidate
    return here.parents[1] / "ui" / "dist"


ASSET = re.compile(r"^assets/[\w.-]+$")


class SpaFiles(StaticFiles):
    """The built UI; any unknown path that is not an API one gets index.html (the UI routes with the hash, but a
    reload on a deep link must still land somewhere). A `<file>.gz` made at image build time is sent instead of
    the file to browsers that accept gzip: no compression work at request time."""

    async def get_response(self, path: str, scope):
        if not ASSET.match(path):
            try:
                response = await super().get_response(path, scope)
            except HTTPException as error:
                if error.status_code != 404:
                    raise
            else:
                if path not in ("", ".", "index.html"):  # another top-level file of the build
                    response.headers["Cache-Control"] = "no-cache"
                    return response
            path = "index.html"
        gz = Path(self.directory) / f"{path}.gz"
        accepts = any(k == b"accept-encoding" and b"gzip" in v for k, v in scope["headers"])
        if accepts and gz.is_file():
            response = FileResponse(gz, media_type=mimetypes.guess_type(path)[0] or "application/octet-stream",
                                    headers={"Content-Encoding": "gzip", "Vary": "Accept-Encoding"})
        else:
            response = await super().get_response(path, scope)
        # hashed file names never change; index.html must be checked every time to pick up a new build
        response.headers["Cache-Control"] = "public, max-age=31536000, immutable" if path.startswith("assets/") else "no-cache"
        return response


def create_app(config: Config, fetch=None) -> Starlette:
    data = Data(config)
    auth = Auth(config)
    maphost = MapHost(config.tiles_dir, config.map_dir, **({"fetch": fetch} if fetch else {}))

    async def healthz(request: Request) -> Response:
        return JSONResponse({"ok": True})

    async def events(request: Request):
        return await data.watcher.stream(request)

    routes = [
        Route("/healthz", healthz),
        Route("/api/login", auth.login, methods=["POST"]),
        Route("/api/logout", auth.logout, methods=["POST"]),
        Route("/api/me", auth.me, methods=["GET"]),
        Route("/api/events", events, methods=["GET"]),
        *Common(data).routes(),
        *Notes(data).routes(),
        *Contacts(data).routes(),
        *maphost.routes(),
    ]

    async def api_not_found(request: Request) -> Response:
        return JSONResponse({"error": "Adresse inconnue"}, status_code=404)
    routes.append(Route("/api/{rest:path}", api_not_found, methods=["GET", "POST", "PUT", "PATCH", "DELETE"]))

    static = config.static_dir or default_static_dir()
    if (static / "index.html").is_file():
        routes.append(Mount("/", SpaFiles(directory=static, html=True)))
    else:
        async def no_ui(request: Request) -> Response:
            return Response("Interface non construite (npm run build dans webapp/ui).", status_code=503,
                            media_type="text/plain; charset=utf-8")
        routes.append(Route("/{rest:path}", no_ui))

    @contextlib.asynccontextmanager
    async def lifespan(app):
        await data.watcher.start()
        try:
            yield
        finally:
            await data.watcher.stop()

    async def api_error(request: Request, error: ApiError) -> Response:
        response = error.response()
        if error.status == 429 and "retry_after" in error.extra:
            response.headers["Retry-After"] = str(error.extra["retry_after"])
        return response

    async def unexpected(request: Request, error: Exception) -> Response:
        traceback.print_exception(error)
        return JSONResponse({"error": f"Erreur du serveur : {type(error).__name__}: {error}"}, status_code=500)

    async def http_error(request: Request, error: HTTPException) -> Response:
        return JSONResponse({"error": error.detail}, status_code=error.status_code)

    app = Starlette(routes=routes, lifespan=lifespan, exception_handlers={
        ApiError: api_error, HTTPException: http_error, Exception: unexpected})
    app.state.data = data
    app.state.auth = auth
    app.state.maphost = maphost
    app.add_middleware(Guard, auth=auth, on_request=data.touch)
    return app
