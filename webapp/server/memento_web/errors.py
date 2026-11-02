"""One error shape for the whole API: `{"error": "<French message for the user>", ...}` with a meaningful status.
Raise ApiError anywhere in a module; the app turns it into that response. Anything else is a 500 that is logged and
also reaches the UI as a message, never silently dropped."""
from __future__ import annotations

from starlette.requests import Request
from starlette.responses import JSONResponse


class ApiError(Exception):
    def __init__(self, status: int, message: str, **extra):
        super().__init__(message)
        self.status = status
        self.message = message
        self.extra = extra

    def response(self) -> JSONResponse:
        return JSONResponse({"error": self.message, **self.extra}, status_code=self.status)


def not_found(what: str = "Fichier introuvable (supprimé ou pas encore synchronisé ?)") -> ApiError:
    return ApiError(404, what)


def bad_request(message: str) -> ApiError:
    return ApiError(400, message)


async def json_body(request: Request) -> dict:
    """The request's JSON object, or 400."""
    try:
        body = await request.json()
    except ValueError:
        raise bad_request("JSON invalide") from None
    if not isinstance(body, dict):
        raise bad_request("JSON invalide")
    return body
