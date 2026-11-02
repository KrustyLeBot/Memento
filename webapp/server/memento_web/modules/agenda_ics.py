"""ICS import and export of the agenda, with the core's rules (ics_import, ics_export).

Import, in three steps like the Windows dialog:
1. `POST /api/agenda/import?name=<file>` (the .ics or .zip as the body): parsed and previewed, nothing written. The
   parsed calendars wait in memory under a token (IMPORT_TTL).
2. `POST /api/agenda/import/<token>/apply` {targets, since, duplicates}: starts the writing in the background and
   answers at once (202): a calendar of thousands of events takes longer than a proxy lets a request live.
3. `GET /api/agenda/import/<token>`: {state: ready | running | done | error, done, total, report | error}.
`POST /api/agenda/imports/<import id>/undo` removes an import's files ("Annuler l'import", "Imports passés").

The writing does not hold the data lock for the whole import (other requests would wait): each file write takes it.

Export: `GET /api/agenda/export?calendar=<id>&include=1` (one .ics) or without `calendar` (a .zip, one .ics per
calendar); `include` adds the activities and the dated notes.
"""
from __future__ import annotations

import asyncio
import copy
import secrets
import time
import zipfile
from datetime import date, datetime, timezone
from urllib.parse import quote

from starlette.concurrency import run_in_threadpool
from starlette.requests import Request
from starlette.responses import JSONResponse, Response
from starlette.routing import Route

from ..core import fmt
from ..errors import ApiError, bad_request, json_body, not_found
from .agenda_index import zone_of

MAX_UPLOAD = 40_000_000
IMPORT_TTL = 30 * 60
MAX_ERRORS = 30


def ics():
    from memento_win.core.agenda import ics_export, ics_import, store
    return ics_import, ics_export, store


def plural(n: int, one: str, many: str) -> str:
    return f"{n} {one if n <= 1 else many}"


def preview_json(index: int, p, pv, existing, settings) -> dict:
    """One calendar of the file as the import dialog shows it; `duplicates` by target calendar, so changing the
    target needs no round trip."""
    uids = {e.uid for e in existing if e.uid and not e.recurrence_of}
    by_calendar: dict[str, int] = {}
    if any(e.uid in uids for e in p.events):
        for cal in settings.calendars:
            mine = {e.uid for e in existing if e.uid and settings.calendar(e.calendar).id == cal.id and not e.recurrence_of}
            n = sum(1 for e in p.events if e.uid in mine)
            if n:
                by_calendar[cal.id] = n
    return {
        "index": index, "name": pv.name, "source": pv.source, "events": pv.events, "series": pv.series,
        "exceptions": pv.exceptions, "cancelled": pv.cancelled, "ignored": pv.ignored,
        "unresolved_zones": pv.unresolved_zones, "first": pv.first.isoformat() if pv.first else None,
        "last": pv.last.isoformat() if pv.last else None, "errors": pv.errors[:MAX_ERRORS], "error_count": len(pv.errors),
        "suggested_calendar": pv.suggested_calendar, "calendar_export": pv.calendar_export,
        "items": [{"title": i.title, "when": i.when_text, "location": i.location} for i in pv.items] if pv.items else None,
        "duplicates": by_calendar,
    }


def report_json(report) -> dict:
    return {
        "import_id": report.import_id, "summary": report.summary(), "new_calendars": report.new_calendars,
        "calendars": [{"name": c.name, "calendar_id": c.calendar_id, "created": c.created, "series": c.series,
                       "exceptions": c.exceptions, "updated": c.updated, "skipped_duplicates": c.skipped_duplicates,
                       "before_cutoff": c.before_cutoff, "cancelled": c.cancelled, "ignored": c.ignored,
                       "unresolved_zones": c.unresolved_zones, "errors": c.errors[:MAX_ERRORS]} for c in report.calendars],
    }


class LockedFs:
    """The folder for a background import: every write (and delete) under the data lock, one at a time."""

    def __init__(self, data):
        from memento_win.core.agenda.store import DirFs
        self.inner = DirFs(data.root)
        self.lock = data.lock

    def list(self, directory):
        return self.inner.list(directory)

    def read_text(self, path):
        return self.inner.read_text(path)

    def write_text(self, path, text):
        with self.lock:
            self.inner.write_text(path, text)

    def delete(self, path):
        with self.lock:
            self.inner.delete(path)

    def mtime(self, path):
        return self.inner.mtime(path)


class AgendaIcs:
    def __init__(self, data, agenda):
        self.data = data
        self.index = agenda.index
        self.jobs: dict[str, dict] = {}
        data.idle_hooks.append(self.drop_idle)

    def routes(self) -> list[Route]:
        return [
            Route("/api/agenda/import", self.upload, methods=["POST"]),
            Route("/api/agenda/import/{token}/apply", self.apply, methods=["POST"]),
            Route("/api/agenda/import/{token}", self.status, methods=["GET"]),
            Route("/api/agenda/import/{token}", self.discard, methods=["DELETE"]),
            Route("/api/agenda/imports", self.past, methods=["GET"]),
            Route("/api/agenda/imports/{id}/undo", self.undo, methods=["POST"]),
            Route("/api/agenda/export", self.export, methods=["GET"]),
        ]

    def drop_idle(self) -> None:
        """Parsed files nobody applied are forgotten when the server goes idle; a running import is kept."""
        self.jobs = {k: j for k, j in self.jobs.items() if j["state"] == "running"}

    def _purge(self) -> None:
        now = time.monotonic()
        self.jobs = {k: j for k, j in self.jobs.items() if j["state"] == "running" or now - j["time"] < IMPORT_TTL}

    def _job(self, token: str) -> dict:
        job = self.jobs.get(token)
        if job is None:
            raise ApiError(410, "Cet import a expiré : choisissez le fichier à nouveau.")
        return job

    # ---- import ----

    async def upload(self, request: Request) -> JSONResponse:
        ics_import, _, _ = ics()
        name = request.query_params.get("name", "").strip() or "agenda.ics"
        zone = zone_of(request.query_params.get("tz"))
        length = request.headers.get("content-length")
        if length and length.isdigit() and int(length) > MAX_UPLOAD:
            raise ApiError(413, f"Fichier trop volumineux ({MAX_UPLOAD // 1_000_000} Mo au plus)")
        chunks, size = [], 0
        async for chunk in request.stream():
            size += len(chunk)
            if size > MAX_UPLOAD:
                raise ApiError(413, f"Fichier trop volumineux ({MAX_UPLOAD // 1_000_000} Mo au plus)")
            chunks.append(chunk)
        raw = b"".join(chunks)
        del chunks

        def parse():
            try:
                return ics_import.read_sources([ics_import.IcsSource.from_bytes(name, raw)], zone)
            except zipfile.BadZipFile as error:
                raise ApiError(422, f"Import impossible : archive .zip illisible ({error})") from None
            except UnicodeDecodeError as error:
                raise ApiError(422, f"Import impossible : encodage illisible ({error})") from None
            except (OSError, ValueError) as error:
                raise ApiError(422, f"Import impossible : {error}") from None
        parsed = await run_in_threadpool(parse)
        if not parsed:
            raise ApiError(422, "Aucun agenda trouvé dans ce fichier.")

        def run():
            self.index.load()
            existing = list(self.index.sources.events)
            settings = copy.deepcopy(self.index.sources.settings)
            previews = ics_import.preview(parsed, existing, settings)
            return [preview_json(i, p, pv, existing, settings) for i, (p, pv) in enumerate(zip(parsed, previews))], settings.problem
        calendars, problem = await self.data.read(run)
        self._purge()
        token = secrets.token_urlsafe(12)
        self.jobs[token] = {"state": "ready", "time": time.monotonic(), "parsed": parsed, "zone": zone,
                            "done": 0, "total": sum(len(p.events) + len(p.overrides) for p in parsed)}
        single = calendars[0]["items"] if len(calendars) == 1 and not calendars[0]["calendar_export"] else None
        return JSONResponse({"token": token, "calendars": calendars, "compact": bool(single and len(single) == 1),
                             "problem": problem})

    async def apply(self, request: Request) -> JSONResponse:
        """{"targets": {"0": {"calendar_id": "perso"} | {"calendar_id": null, "name", "color"} | {"skip": true}},
        "since": "2026-01-01" | null, "duplicates": "update" | "skip" | "keep"}; a missing target is the suggested
        calendar (a new one named after the file when there is none)."""
        ics_import, _, store_mod = ics()
        job = self._job(request.path_params["token"])
        if job["state"] != "ready":
            raise ApiError(409, "Cet import est déjà lancé.")
        body = await json_body(request)
        duplicates = body.get("duplicates", "update")
        if duplicates not in ics_import.DUPLICATES:
            raise bad_request("Politique de doublons inconnue")
        try:
            since = date.fromisoformat(body["since"]) if body.get("since") else None
            targets = {int(k): ics_import.ImportTarget(
                calendar_id=v.get("calendar_id") or None, name=(v.get("name") or "").strip() or None,
                color=v.get("color") or None, skip=bool(v.get("skip")))
                for k, v in (body.get("targets") or {}).items()}
        except (AttributeError, TypeError, ValueError):
            raise bad_request("Options d'import invalides") from None
        options = ics_import.ImportOptions(targets, since, duplicates)

        def prepare():
            self.index.load()
            settings = copy.deepcopy(self.index.sources.settings)
            settings.problem = None
            return list(self.index.sources.events), settings, frozenset(self.index.taken_ids())
        existing, settings, taken = await self.data.read(prepare)
        job.update(state="running", time=time.monotonic())

        def progress(done: int, total: int) -> None:
            job["done"], job["total"] = done, total

        def write():
            agenda = store_mod.AgendaStore(LockedFs(self.data))
            agenda.remember_ids({e.id for e in existing})
            return ics_import.apply_import(agenda, job["parsed"], options, existing, settings, job["zone"],
                                           taken_ids=taken, progress=progress)

        async def background():
            try:
                report = await run_in_threadpool(write)
                job.update(state="done", report=report_json(report))
            except Exception as error:  # reported to the page, which shows it; files written so far carry the import id
                job.update(state="error", error=f"Import interrompu : {error}. Les fichiers déjà écrits portent "
                                                 "l'identifiant de l'import : « Imports passés » permet de les retirer.")
            finally:
                job["parsed"] = None
                job["time"] = time.monotonic()
                self.data.watcher.poke()
        job["task"] = asyncio.create_task(background())
        return JSONResponse(self._status(job), status_code=202)

    @staticmethod
    def _status(job: dict) -> dict:
        return {k: job[k] for k in ("state", "done", "total", "report", "error") if k in job}

    async def status(self, request: Request) -> JSONResponse:
        self.data.touch()
        return JSONResponse(self._status(self._job(request.path_params["token"])))

    async def discard(self, request: Request) -> JSONResponse:
        job = self.jobs.get(request.path_params["token"])
        if job is not None and job["state"] != "running":
            del self.jobs[request.path_params["token"]]
        return JSONResponse({"ok": True})

    async def past(self, request: Request) -> JSONResponse:
        def run():
            self.index.load()
            return {"imports": self.index.imports()}
        return JSONResponse(await self.data.read(run))

    async def undo(self, request: Request) -> JSONResponse:
        ics_import, _, _ = ics()
        import_id = request.path_params["id"]

        def run():
            self.index.load()
            events = list(self.index.sources.events)
            if not any(e.import_id == import_id for e in events):
                raise not_found("Plus aucun fichier de cet import")
            report = ics_import.undo_import(self.data.store.agenda, import_id, events,
                                            copy.deepcopy(self.index.sources.settings))
            text = plural(report.deleted, "fichier supprimé", "fichiers supprimés")
            if report.calendars_removed:
                text += ", " + plural(len(report.calendars_removed), "agenda retiré", "agendas retirés")
            return {"message": text, "deleted": report.deleted, "calendars_removed": report.calendars_removed}
        return JSONResponse(await self.data.run(run))

    # ---- export ----

    async def export(self, request: Request) -> Response:
        _, ics_export, _ = ics()
        calendar_id = request.query_params.get("calendar") or None
        include = request.query_params.get("include") == "1"

        def run():
            self.index.load()
            s = self.index.sources
            if calendar_id and not any(c.id == calendar_id for c in s.settings.calendars):
                raise not_found("Agenda introuvable")
            now = datetime.now(timezone.utc)
            notes = s.notes if include else None
            acts = s.activities if include else None
            if calendar_id:
                result = ics_export.export_calendar(s.events, s.settings, calendar_id, now, notes=notes, activities=acts)
                name = f"{fmt.slug(s.settings.calendar(calendar_id).name)}.ics"
                return result.text.encode("utf-8"), name, "text/calendar; charset=utf-8", len(result.warnings), result.events
            payload, warnings = ics_export.export_all(s.events, s.settings, now, notes=notes, activities=acts)
            return payload, "agendas.zip", "application/zip", len(warnings), None
        payload, name, mime, warnings, count = await self.data.read(run)
        headers = {"Content-Disposition": f"attachment; filename*=UTF-8''{quote(name)}",
                   "X-Memento-Warnings": str(warnings)}
        if count is not None:
            headers["X-Memento-Events"] = str(count)
        return Response(payload, media_type=mime, headers=headers)
