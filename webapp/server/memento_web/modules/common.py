"""Endpoints shared by every module: raw files (attachments, scans' PDFs, drawings, tracks), the undo stash, sync
conflict copies, and the synced settings files."""
from __future__ import annotations

import os
import re

from starlette.requests import Request
from starlette.responses import JSONResponse
from starlette.routing import Route

from ..core import ACTIVITIES_DIR, SETTINGS_PATH, ActivitySettings, Sport, atomic
from ..errors import ApiError, bad_request, json_body, not_found
from ..files import file_response, read_versioned, require_version, unlink_quiet

# Synchrotron (rclone bisync) names a conflict copy `<name>.conflictN`; other sync tools put "conflict" inside the
# name (`note-conflict-2026….md`), which is how the apps detect them (core note.parse).
SUFFIX_CONFLICT = re.compile(r"^(?P<original>.+)\.conflict\d+$")
CONFLICT_DIRS = ("", ACTIVITIES_DIR, "contacts")


def conflict_original(name: str) -> str | None:
    """The file a conflict copy stands for, or None when the name is no conflict copy."""
    m = SUFFIX_CONFLICT.match(name)
    if m:
        return m["original"]
    if "conflict" in name.lower() and name.endswith(".md"):
        return re.sub(r"(?i)[ ._-]*conflict[^.]*", "", name)
    return None


def area_of(relative: str) -> str:
    head = relative.split("/", 1)[0]
    return {"activities": "activities", "agenda": "agenda", "contacts": "contacts"}.get(head, "notes") \
        if "/" in relative else "notes"


class Common:
    def __init__(self, data):
        self.data = data

    def routes(self) -> list[Route]:
        return [
            Route("/api/files/{path:path}", self.file, methods=["GET"]),
            Route("/api/undo/{token}", self.undo, methods=["POST"]),
            Route("/api/conflicts", self.conflicts, methods=["GET"]),
            Route("/api/conflicts/resolve", self.resolve_conflict, methods=["POST"]),
            Route("/api/settings/activities", self.get_activity_settings, methods=["GET"]),
            Route("/api/settings/activities", self.put_activity_settings, methods=["PUT"]),
        ]

    async def file(self, request: Request):
        target = self.data.path(request.path_params["path"])
        return file_response(target, download=request.query_params.get("download") == "1")

    async def undo(self, request: Request) -> JSONResponse:
        restored = await self.data.run(self.data.undo.restore, self.data.root, request.path_params["token"])
        return JSONResponse({"restored": restored})

    def _conflict_files(self) -> list[dict]:
        folders = list(CONFLICT_DIRS)
        agenda = self.data.root / "agenda"
        if agenda.is_dir():
            folders += [f"agenda/{d.name}" for d in agenda.iterdir() if d.is_dir() and not d.name.startswith(".")]
        found = []
        for folder in folders:
            try:
                entries = list(os.scandir(self.data.root / folder)) if folder else list(os.scandir(self.data.root))
            except OSError:
                continue
            for entry in entries:
                if entry.name.startswith(".") or not entry.is_file():
                    continue
                original = conflict_original(entry.name)
                if original is None:
                    continue
                path = f"{folder}/{entry.name}" if folder else entry.name
                target = f"{folder}/{original}" if folder else original
                found.append({"path": path, "original": target, "area": area_of(path),
                              "original_exists": (self.data.root / target).is_file()})
        return sorted(found, key=lambda c: c["path"])

    async def conflicts(self, request: Request) -> JSONResponse:
        return JSONResponse({"conflicts": await self.data.read(self._conflict_files)})

    async def resolve_conflict(self, request: Request) -> JSONResponse:
        """{"path": <the copy>, "action": "keep" | "discard"}. keep: the copy's content replaces the original (the
        old original can be brought back with the undo token); discard: the copy goes. Only the copy's own file
        is touched, never the attachments it shares with the original."""
        body = await json_body(request)
        path, action = str(body.get("path", "")), body.get("action")
        if action not in ("keep", "discard"):
            raise bad_request("Action inconnue")

        def run():
            match = next((c for c in self._conflict_files() if c["path"] == path), None)
            if match is None:
                raise not_found("Cette copie de conflit n'existe plus")
            copy, original = self.data.path(match["path"]), self.data.path(match["original"])
            if action == "discard":
                token = self.data.undo.stash(self.data.root, [match["path"]])
                unlink_quiet(copy)
                return {"undo": token, "path": None}
            token = self.data.undo.stash(self.data.root, [match["path"], match["original"]])
            atomic.write_bytes(original, copy.read_bytes())
            unlink_quiet(copy)
            return {"undo": token, "path": match["original"]}
        return JSONResponse(await self.data.run(run))

    # ---- activities/settings.json (synced with the phone) ----

    def _activity_settings(self) -> dict:
        found = read_versioned(self.data.path(SETTINGS_PATH))
        settings = ActivitySettings.parse(found[0].decode("utf-8", "replace")) if found else ActivitySettings()
        return {"refine_altitude": settings.refine_altitude, "totals": [s.key for s in settings.totals],
                "version": found[1] if found else None}

    async def get_activity_settings(self, request: Request) -> JSONResponse:
        return JSONResponse(await self.data.read(self._activity_settings))

    async def put_activity_settings(self, request: Request) -> JSONResponse:
        """If-Match: the version read, or `none` when the file did not exist."""
        expected = require_version(request.headers.get("if-match"))
        body = await json_body(request)
        known = {s.key: s for s in Sport}
        try:
            totals = {known[k] for k in body["totals"]}
            refine = bool(body["refine_altitude"])
        except (KeyError, TypeError):
            raise bad_request("Réglages invalides") from None

        def run():
            current = self._activity_settings()
            if (current["version"] or "none") != expected:
                raise ApiError(409, "Ces réglages ont été modifiés ailleurs.", current=current)
            self.data.store.save_activity_settings(ActivitySettings(refine, totals))
            return self._activity_settings()
        return JSONResponse(await self.data.run(run))

