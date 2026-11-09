"""Agenda module: the entries of a window (events, dated notes, activities, birthdays), search, events (create, edit
with series scopes, delete with undo, "Rétablir l'original"), the repetition field, calendars and layers
(agenda/calendars.json), dated notes ("Ajouter à l'agenda"). ICS import and export: agenda_ics.py.

Every format, recurrence and wording rule is the Windows core's (memento_win/core/agenda), imported on first use.
Times: the browser sends its IANA zone (`tz`), which plays the part of the device zone of the apps: floating events,
all-day days and the entries' dates are in it.

An event is addressed by its path (`agenda/2026/rdv-x1y2z3.md`). An occurrence of a series by the series' path plus
`occurrence=<wall start>` (`2026-11-19T14:30`, the stamp of the entry's `occurrence.start`).
"""
from __future__ import annotations

import re
from datetime import date, datetime, timedelta

from starlette.requests import Request
from starlette.responses import JSONResponse
from starlette.routing import Route

from ..core import fmt, now_ms
from ..errors import ApiError, bad_request, json_body, not_found
from ..files import read_versioned, require_version
from ..memo import Answers, json_response
from .agenda_index import AgendaIndex, core, entry_json, parse_day, zone_of
from .notes import check_note_path

EVENT_PATH = re.compile(r"^agenda/(?:[^/]+/)?[^/]+\.md$")
EDITABLE = {"title", "body", "start", "end", "tz", "end_tz", "calendar", "rrule", "alerts", "location"}
LAYERS = ("activities", "notes", "birthdays")
COLOR = re.compile(r"^#[0-9A-Fa-f]{6}$")


def check_event_path(relative: str) -> str:
    if not EVENT_PATH.match(relative) or relative.endswith("/calendars.json"):
        raise bad_request("Chemin d'événement invalide")
    return relative


def stamp(raw, what: str):
    _, _, _, _, model, _, _, _ = core()
    try:
        return model.parse_stamp(str(raw))
    except ValueError:
        raise bad_request(f"Date invalide ({what})") from None


def city(tz_name: str) -> str:
    return tz_name.rsplit("/", 1)[-1].replace("_", " ")


def apply_fields(ev, body: dict):
    """The fields the editor may change, checked as the Windows editor checks them before saving."""
    _, _, _, _, model, recurrence, _, zones = core()
    unknown = set(body) - EDITABLE
    if unknown:
        raise bad_request(f"Champs inconnus : {', '.join(sorted(unknown))}")
    changes: dict = {}
    note_changes: dict = {}
    if "title" in body:
        note_changes["title"] = str(body["title"] or "").strip()
    if "body" in body:
        note_changes["body"] = str(body["body"] or "")
    start = stamp(body["start"], "début") if "start" in body else ev.start
    end = ev.end if "end" not in body else (stamp(body["end"], "fin") if body["end"] else None)
    if end is not None and model.is_all_day(end) != model.is_all_day(start):
        raise bad_request("La fin n'a pas la forme du début")
    tz = body["tz"] if "tz" in body else ev.tz
    end_tz = body["end_tz"] if "end_tz" in body else ev.end_tz
    if model.is_all_day(start):
        tz = end_tz = None
    for name in (tz, end_tz):
        if name is not None and not zones.is_valid(str(name)):
            raise bad_request(f"Fuseau horaire inconnu : {name}")
    if not tz or end_tz == tz:
        end_tz = None
    if end is not None:
        if end_tz:
            if zones.to_instant(end, zones.zone(end_tz)) <= zones.to_instant(start, zones.zone(tz)):
                raise bad_request(f"L'arrivée (heure de {city(end_tz)}) doit être après le départ.")
        elif end < start:
            raise bad_request("La fin est avant le début.")
        if model.is_all_day(start) and end == start:
            end = None
    changes.update(start=start, end=end, tz=tz or None, end_tz=end_tz or None)
    if "calendar" in body:
        changes["calendar"] = str(body["calendar"]) if body["calendar"] else None
    if "location" in body:
        changes["location"] = str(body["location"] or "").strip() or None
    if "alerts" in body:
        try:
            changes["alerts"] = sorted({int(a) for a in body["alerts"] or []})
        except (TypeError, ValueError):
            raise bad_request("Rappels invalides") from None
    if "rrule" in body:
        rrule = str(body["rrule"] or "").strip() or None
        if rrule:
            try:
                recurrence.parse_rule(rrule)
            except ValueError as error:
                raise bad_request(f"Règle de répétition illisible : {error}") from None
        changes["rrule"] = rrule
    out = ev.copy(**changes, warnings=[])
    return out.with_note(**note_changes) if note_changes else out


def event_json(ev, version: str, zone: str) -> dict:
    _, _, _, links, model, _, rules, zones = core()
    parts = fmt.parse_body(ev.body)
    today = datetime.now(zones.zone(zone)).date()
    fmt_ = model.format_stamp
    return {
        "path": ev.path, "id": ev.id, "title": ev.title, "body": ev.body, "all_day": ev.all_day,
        "start": fmt_(ev.start), "end": fmt_(ev.end) if ev.end is not None else None, "tz": ev.tz, "end_tz": ev.end_tz,
        "calendar": ev.calendar, "rrule": ev.rrule, "exdate": [fmt_(x) for x in ev.exdate], "alerts": ev.alerts,
        "location": ev.location, "status": ev.status, "uid": ev.uid, "recurrence_of": ev.recurrence_of,
        "recurrence_at": fmt_(ev.recurrence_at) if ev.recurrence_at is not None else None, "import_id": ev.import_id,
        "warnings": ev.warnings, "version": version,
        # the editor edits the description in place only when it is plain rich text (as on Windows)
        "rich": not parts.items and not ev.note.attachments and not ev.note.pdf,
        "links": [{"url": x.url, "label": x.label} for x in links.extract(ev.body)],
        "rule_text": rules.describe_rule(ev.rrule, ev.start, ev.tz, zone, today) if ev.rrule else None,
    }


def spec_json(spec) -> dict:
    return {"freq": spec.freq, "interval": spec.interval, "weekdays": spec.weekdays, "monthly": spec.monthly,
            "month_day": spec.month_day, "nth": spec.nth, "nth_weekday": spec.nth_weekday,
            "until": spec.until.isoformat() if spec.until else None, "count": spec.count}


def spec_from(raw: dict):
    _, _, _, _, _, _, rules, _ = core()
    try:
        spec = rules.RuleSpec(
            freq=str(raw["freq"]), interval=max(1, int(raw.get("interval", 1))),
            weekdays=sorted({int(x) for x in raw.get("weekdays") or [] if 0 <= int(x) <= 6}),
            monthly="nth" if raw.get("monthly") == "nth" else "day", month_day=min(31, max(1, int(raw.get("month_day", 1)))),
            nth=int(raw.get("nth", 1)), nth_weekday=int(raw.get("nth_weekday", 0)) % 7,
            until=date.fromisoformat(raw["until"]) if raw.get("until") else None,
            count=max(1, int(raw["count"])) if raw.get("count") else None)
    except (KeyError, TypeError, ValueError):
        raise bad_request("Répétition invalide") from None
    if spec.freq not in rules.FREQS or spec.nth not in (1, 2, 3, 4, 5, -1):
        raise bad_request("Répétition invalide")
    return spec


def preview_text(rrule, start, tz_name, zone, today, exdate) -> str:
    """"Prochaines : 12 nov., 19 nov., 26 nov." under the repetition field (wording of the Windows editor)."""
    _, _, frdates, _, model, _, rules, _ = core()
    if not rrule:
        return ""
    try:
        nxt = rules.next_starts(rrule, start, tz_name, zone, max(today, model.stamp_date(start)), exdate)
    except (ValueError, TypeError, OverflowError) as error:
        return f"Règle illisible : {error}"
    if not nxt:
        return "Aucune occurrence à venir"
    return "Prochaines : " + ", ".join(frdates.short_date(model.stamp_date(x), today) for x in nxt)


def settings_json(settings, version: str | None, counts: dict) -> dict:
    calendars, *_ = core()
    return {
        "calendars": [{"id": c.id, "name": c.name, "color": c.color, "visible": c.visible, "imported": bool(c.import_id),
                       "count": counts.get(c.id, 0)} for c in settings.calendars],
        "default": settings.default_calendar().id,
        "layers": {k: {"visible": settings.layer(k).visible, "color": settings.layer(k).color} for k in LAYERS},
        "default_alerts": settings.default_alerts(False), "default_alerts_all_day": settings.default_alerts(True),
        "problem": settings.problem, "version": version, "palette": [list(p) for p in calendars.PALETTE],
    }


class Agenda:
    def __init__(self, data):
        self.data = data
        self.index = AgendaIndex(data)
        self.answers = Answers(8)  # the windows' JSON
        data.idle_hooks.append(self.index.trim)
        data.idle_hooks.append(self.answers.clear)

    def routes(self) -> list[Route]:
        return [
            Route("/api/agenda/entries", self.entries, methods=["GET"]),
            Route("/api/agenda/search", self.search, methods=["GET"]),
            Route("/api/agenda/zones", self.zones, methods=["GET"]),
            Route("/api/agenda/rule", self.rule, methods=["POST"]),
            Route("/api/agenda/calendars", self.get_calendars, methods=["GET"]),
            Route("/api/agenda/calendars", self.change_calendars, methods=["PUT"]),
            Route("/api/agenda/calendars/{id}", self.delete_calendar, methods=["DELETE"]),
            Route("/api/agenda/dated/{path:path}", self.note_date, methods=["GET"]),
            Route("/api/agenda/dated/{path:path}", self.date_note, methods=["PUT"]),
            Route("/api/agenda/events", self.create, methods=["POST"]),
            Route("/api/agenda/occurrences/{path:path}", self.occurrences, methods=["GET"]),
            Route("/api/agenda/restore/{path:path}", self.restore, methods=["POST"]),
            Route("/api/agenda/events/{path:path}", self.get, methods=["GET"]),
            Route("/api/agenda/events/{path:path}", self.update, methods=["PUT"]),
            Route("/api/agenda/events/{path:path}", self.delete, methods=["DELETE"]),
        ]

    # ---- helpers (under the data lock) ----

    def _read(self, relative: str):
        _, _, _, _, model, _, _, _ = core()
        target = self.data.path(check_event_path(relative))
        found = read_versioned(target)
        if found is None:
            raise not_found("Événement introuvable (supprimé ou pas encore synchronisé ?)")
        raw, version = found
        try:
            note = fmt.parse(relative, raw.decode("utf-8"), target.stat().st_mtime_ns // 1_000_000)
            ev = model.event_from_note(note)
        except UnicodeDecodeError:
            raise ApiError(422, "Ce fichier n'est pas du texte UTF-8.") from None
        except model.AgendaFormatError as error:
            raise ApiError(422, str(error)) from None
        if ev is None:
            raise ApiError(422, f"{relative} : pas de date de début (start)")
        return ev, version

    def _expected(self, relative: str, expected: str, zone: str):
        """The event as on disk, refused with 409 (and the disk version) when it is not the one the client saw."""
        try:
            ev, version = self._read(relative)
        except ApiError as error:
            if error.status == 404:
                raise ApiError(409, "Cet événement a été supprimé ailleurs (synchronisation, autre appareil).",
                               current=None) from None
            raise
        if version != expected:
            raise ApiError(409, "Cet événement a été modifié ailleurs (synchronisation, autre appareil).",
                           current=event_json(ev, version, zone))
        return ev

    def _version(self, relative: str) -> str:
        found = read_versioned(self.data.path(relative))
        return found[1] if found else ""

    def _files_of(self, events) -> list[str]:
        return [p for ev in events for p in [ev.path, ev.note.pdf, *ev.note.attachments] if p]

    def _counts(self) -> dict:
        settings = self.index.sources.settings
        counts: dict[str, int] = {}
        for e in self.index.sources.events:
            cid = settings.calendar(e.calendar).id
            counts[cid] = counts.get(cid, 0) + 1
        return counts

    def _settings(self):
        """calendars.json as now (parsed) and its version (None when absent)."""
        calendars, *_ = core()
        found = read_versioned(self.data.path(calendars.PATH))
        if found is None:
            return calendars.CalendarSettings(), None
        return calendars.CalendarSettings.parse(found[0].decode("utf-8", "replace")), found[1]

    # ---- reading ----

    async def entries(self, request: Request) -> JSONResponse:
        q = request.query_params
        first, last = parse_day(q.get("from"), "début"), parse_day(q.get("to"), "fin")
        zone = zone_of(q.get("tz"))

        def run():
            self.index.load()

            def answer():
                result = self.index.entries(first, last, zone)
                return {"entries": [entry_json(e) for e in result.entries],
                        "problems": self.index.problems + result.problems}
            # the sources and the problems are replaced (not changed in place) by a load that finds a change
            body = self.answers.body((first, last, zone), [self.index.sources, self.index.problems], answer)
            self.index.zone = zone
            if self.index.wants_wide() and self.data.warmer is not None:
                self.data.warmer.want_wide()  # the next windows are cut from it
            return body
        return json_response(await self.data.read(run))

    async def search(self, request: Request) -> JSONResponse:
        query = request.query_params.get("q", "").strip()
        zone = zone_of(request.query_params.get("tz"))

        def run():
            self.index.load()
            return {"entries": [entry_json(e) for e in self.index.search(query, zone)]}
        return JSONResponse(await self.data.read(run))

    async def zones(self, request: Request) -> JSONResponse:
        from zoneinfo import available_timezones
        names = sorted(n for n in available_timezones()
                       if "/" in n and not n.startswith(("Etc/", "SystemV/", "posix/", "right/")))
        return JSONResponse({"zones": names + ["UTC"]})

    async def get(self, request: Request) -> JSONResponse:
        relative = request.path_params["path"]
        zone = zone_of(request.query_params.get("tz"))

        def run():
            ev, version = self._read(relative)
            out = {"event": event_json(ev, version, zone), "series": None}
            if ev.recurrence_of:
                self.index.load()
                series = self.index.event(ev.recurrence_of)
                if series is not None:
                    out["series"] = event_json(series, self._version(series.path), zone)
            return out
        return JSONResponse(await self.data.read(run))

    async def occurrences(self, request: Request) -> JSONResponse:
        """The occurrences of one event (series) in [from, to]: the recurrence expansion as the core does it."""
        _, _, _, _, model, recurrence, _, zones = core()
        relative = request.path_params["path"]
        q = request.query_params
        first, last = parse_day(q.get("from"), "début"), parse_day(q.get("to"), "fin")
        if last < first or (last - first).days > 3660:
            raise bad_request("Période invalide")
        zone = zone_of(q.get("tz"))

        def run():
            ev, _ = self._read(relative)
            self.index.load()
            overridden = [x.recurrence_at for x in self.index.exceptions_of(ev.id) if x.recurrence_at is not None]
            tz = zones.zone(zone)
            exp = recurrence.expand(ev, zones.day_start(first, tz), zones.day_start(last + timedelta(days=1), tz), zone, overridden)
            return {"occurrences": [{"start": model.format_stamp(o.start),
                                     "end": model.format_stamp(o.end) if o.end is not None else None,
                                     "start_ms": int(o.start_instant.timestamp() * 1000),
                                     "end_ms": int(o.end_instant.timestamp() * 1000)} for o in exp.occurrences],
                    "truncated": exp.truncated, "error": exp.error}
        return JSONResponse(await self.data.read(run))

    async def rule(self, request: Request) -> JSONResponse:
        """The repetition field: {start, tz, rrule?, preset?, spec?, follow?, exdate?} -> the rule, its preset key
        ("none", a preset, "custom", "imported"), the presets for this start, its wording and the next occurrences.
        `preset` replaces the rule by that preset; `follow` (the start moved) keeps the chosen preset on the new day."""
        _, _, _, _, model, _, rules, zones = core()
        body = await json_body(request)
        start = stamp(body.get("start"), "début")
        tz = body.get("tz") or None
        if tz and not zones.is_valid(tz) or model.is_all_day(start):
            tz = None
        zone = zone_of(request.query_params.get("tz"))
        today = datetime.now(zones.zone(zone)).date()
        rrule = (body.get("rrule") or "").strip() or None
        preset = body.get("preset")
        keys = [k for k, _ in rules.presets(start)]
        if preset and preset not in ("custom", "imported", "edit"):
            if preset not in keys and preset != "none":
                preset = "monthly_day" if str(preset).startswith("monthly") else None
                if preset is None:
                    raise bad_request("Répétition inconnue")
            rrule = rules.preset_rule(preset, start, tz, zone) if preset != "none" else None
        elif body.get("spec") is not None:
            rrule = rules.to_rrule(spec_from(body["spec"]), start, tz, zone)
        exdate = [stamp(x, "exdate") for x in body.get("exdate") or []]
        key = rules.preset_of(rrule, start, tz, zone)
        spec = rules.parse_spec(rrule, start, tz, zone) if rrule else None
        return JSONResponse({
            "rrule": rrule, "preset": key, "presets": [{"key": k, "label": label} for k, label in rules.presets(start)],
            "description": rules.describe_rule(rrule, start, tz, zone, today),
            "preview": preview_text(rrule, start, tz, zone, today, exdate),
            "spec": spec_json(spec or rules.default_spec("WEEKLY", start)),
            "summary": rules.describe(spec, start, today) if spec else None,
        })

    # ---- events ----

    async def create(self, request: Request) -> JSONResponse:
        _, _, _, _, model, _, _, _ = core()
        body = await json_body(request)
        zone = zone_of(request.query_params.get("tz"))
        if "start" not in body:
            raise bad_request("Date de début manquante")

        def run():
            self.index.load()
            settings = self.index.sources.settings
            draft = model.new_event("", stamp(body["start"], "début"), calendar=settings.default_calendar().id)
            ev = apply_fields(draft, body)
            saved = self.data.store.agenda.save(ev, taken_ids=self.index.taken_ids())
            return {"event": event_json(saved, self._version(saved.path), zone)}
        return JSONResponse(await self.data.run(run), status_code=201)

    async def update(self, request: Request) -> JSONResponse:
        """scope: absent for a single event (or an edited occurrence), "all" (the series) or "one" (one occurrence of
        a series, `occurrence` = its wall start: it becomes an exception file)."""
        relative = request.path_params["path"]
        expected = require_version(request.headers.get("if-match"))
        q = request.query_params
        scope, zone = q.get("scope"), zone_of(q.get("tz"))
        body = await json_body(request)

        def run():
            ev = self._expected(relative, expected, zone)
            agenda = self.data.store.agenda
            self.index.load()
            if ev.rrule and scope not in ("one", "all"):
                raise bad_request("Événement répété : précisez « cet événement » ou « tous les événements »")
            if ev.rrule and scope == "one":
                at = stamp(q.get("occurrence"), "occurrence")
                exc = agenda.make_exception(ev, at, None)
                exc = apply_fields(exc, {k: v for k, v in body.items() if k != "rrule"})
                exc = exc.copy(rrule=None).with_note(updated=now_ms())
                _, saved = agenda.save_exception(ev, exc)
            elif ev.rrule:
                saved = self._save_series(ev, apply_fields(ev, body))
            else:
                saved = apply_fields(ev, body)
                if ev.recurrence_of:
                    saved = saved.copy(rrule=None)
                saved = agenda.save(saved.with_note(updated=now_ms()))
            return {"event": event_json(saved, self._version(saved.path), zone)}
        return JSONResponse(await self.data.run(run))

    def _save_series(self, old, new):
        """"Tous les événements": when the first start moves, the skipped and edited occurrences move with it, or
        they would stop matching the series (Windows agenda_view._save_series)."""
        agenda = self.data.store.agenda
        delta = None
        if type(old.start) is type(new.start) and new.start != old.start:
            delta = new.start - old.start
        if delta is not None:
            new = new.copy(exdate=[x + delta for x in old.exdate if type(x) is type(old.start)]
                           + [x for x in old.exdate if type(x) is not type(old.start)])
            for exc in self.index.exceptions_of(old.id):
                if exc.recurrence_at is not None and type(exc.recurrence_at) is type(old.start):
                    agenda.save(exc.copy(recurrence_at=exc.recurrence_at + delta))
        elif type(old.start) is not type(new.start):
            new = new.copy(exdate=[])  # all-day <-> timed: the old stamps can no longer match
        return agenda.save(new.with_note(updated=now_ms()))

    async def delete(self, request: Request) -> JSONResponse:
        """scope as for update; on an edited occurrence "one" removes its file (the series keeps the occurrence
        skipped), "all" the whole series. Answers an undo token."""
        relative = request.path_params["path"]
        expected = require_version(request.headers.get("if-match"))
        q = request.query_params
        scope, zone = q.get("scope"), zone_of(q.get("tz"))

        def run():
            ev = self._expected(relative, expected, zone)
            agenda = self.data.store.agenda
            self.index.load()
            series = ev if ev.rrule else (self.index.event(ev.recurrence_of) if ev.recurrence_of else None)
            if series is not None and scope not in ("one", "all"):
                raise bad_request("Événement répété : précisez « cet événement » ou « tous les événements »")
            if series is not None and scope == "all":
                touched = [series] + self.index.exceptions_of(series.id)
                token = self.data.undo.stash(self.data.root, self._files_of(touched))
                agenda.delete_series(series, self.index.sources.events)
                text = "Série supprimée"
            elif ev.rrule:
                token = self.data.undo.stash(self.data.root, [ev.path])
                agenda.delete_occurrence(ev, stamp(q.get("occurrence"), "occurrence"))
                text = "Occurrence supprimée"
            else:
                token = self.data.undo.stash(self.data.root, self._files_of([ev]))
                agenda.delete(ev)
                text = "Événement supprimé"
            return {"undo": token, "message": text}
        return JSONResponse(await self.data.run(run))

    async def restore(self, request: Request) -> JSONResponse:
        """"Rétablir l'original": the edited occurrence goes, the series' own occurrence comes back."""
        relative = request.path_params["path"]
        expected = require_version(request.headers.get("if-match"))
        zone = zone_of(request.query_params.get("tz"))

        def run():
            ev = self._expected(relative, expected, zone)
            self.index.load()
            series = self.index.event(ev.recurrence_of) if ev.recurrence_of else None
            if series is None or ev.recurrence_at is None:
                raise bad_request("Cette occurrence n'appartient à aucune série présente")
            token = self.data.undo.stash(self.data.root, [ev.path, series.path])
            self.data.store.agenda.restore_occurrence(series, ev, ev.recurrence_at)
            return {"undo": token}
        return JSONResponse(await self.data.run(run))

    # ---- calendars and layers ----

    async def get_calendars(self, request: Request) -> JSONResponse:
        def run():
            self.index.load()
            settings, version = self._settings()
            return settings_json(settings, version, self._counts())
        return JSONResponse(await self.data.read(run))

    async def change_calendars(self, request: Request) -> JSONResponse:
        """One change of calendars.json, If-Match its version (`none` when absent):
        {"op": "create", "name"} | {"op": "update", "id", "name"?, "color"?, "visible"?} | {"op": "default", "id"}
        | {"op": "layer", "key", "visible"?, "color"?} | {"op": "alerts", "alerts": [-15, …], "all_day"?: bool}
        (the default reminders of new and imported events, synced with the phone). Unknown keys of the file are kept. An unreadable file is
        replaced only by a request carrying its version (the user was told and agreed)."""
        calendars, *_ = core()
        expected = require_version(request.headers.get("if-match"))
        body = await json_body(request)
        op = body.get("op")
        color = body.get("color")
        if color is not None and not COLOR.match(str(color)):
            raise bad_request("Couleur invalide")

        def run():
            self.index.load()
            settings, version = self._settings()
            if (version or "none") != expected:
                raise ApiError(409, "Les agendas ont été modifiés ailleurs.", current=settings_json(settings, version, self._counts()))
            settings.problem = None
            created = None
            if op == "create":
                name = str(body.get("name") or "").strip()
                if not name:
                    raise bad_request("Nom manquant")
                created = calendars.unique_calendar_id(name, settings)
                settings.calendars.append(calendars.Calendar(created, name, color or calendars.next_color(settings), True))
            elif op in ("update", "default"):
                cal = next((c for c in settings.calendars if c.id == body.get("id")), None)
                if cal is None:
                    raise not_found("Agenda introuvable")
                if op == "default":
                    settings.default = cal.id
                else:
                    if "name" in body:
                        name = str(body["name"] or "").strip()
                        if not name:
                            raise bad_request("Nom manquant")
                        cal.name = name
                    if color is not None:
                        cal.color = color
                    if "visible" in body:
                        cal.visible = bool(body["visible"])
            elif op == "layer":
                key = body.get("key")
                if key not in LAYERS:
                    raise bad_request("Calque inconnu")
                layer = settings.layers.get(key) or settings.layer(key)
                if "visible" in body:
                    layer.visible = bool(body["visible"])
                if color is not None:
                    layer.color = color
                settings.layers[key] = layer
            elif op == "alerts":
                raw = body.get("alerts")
                try:
                    alerts = [int(a) for a in raw] if isinstance(raw, list) else None
                except (TypeError, ValueError):
                    alerts = None
                if alerts is None:
                    raise bad_request("Rappels invalides")
                settings.set_default_alerts(bool(body.get("all_day")), alerts)
            else:
                raise bad_request("Opération inconnue")
            self.data.store.agenda.save_calendars(settings)
            self.index.load()
            out = settings_json(*self._settings(), self._counts())
            out["created"] = created
            return out
        return JSONResponse(await self.data.run(run))

    async def delete_calendar(self, request: Request) -> JSONResponse:
        """Removes a calendar; its events go to `move_to`, or are deleted without it. Undo token."""
        calendars, *_ = core()
        calendar_id = request.path_params["id"]
        expected = require_version(request.headers.get("if-match"))
        move_to = request.query_params.get("move_to") or None

        def run():
            self.index.load()
            settings, version = self._settings()
            if (version or "none") != expected:
                raise ApiError(409, "Les agendas ont été modifiés ailleurs.", current=settings_json(settings, version, self._counts()))
            if not any(c.id == calendar_id for c in settings.calendars):
                raise not_found("Agenda introuvable")
            if len(settings.calendars) == 1:
                raise bad_request("Il faut garder au moins un agenda")
            if move_to and (move_to == calendar_id or not any(c.id == move_to for c in settings.calendars)):
                raise bad_request("Agenda de destination inconnu")
            victims = [e for e in self.index.sources.events if settings.calendar(e.calendar).id == calendar_id]
            token = self.data.undo.stash(self.data.root, [calendars.PATH] + self._files_of(victims))
            settings.problem = None
            settings.calendars = [c for c in settings.calendars if c.id != calendar_id]
            if settings.default == calendar_id:
                settings.default = move_to or settings.calendars[0].id
            agenda = self.data.store.agenda
            agenda.save_calendars(settings)
            for ev in victims:
                if move_to:
                    agenda.save(ev.copy(calendar=move_to))
                else:
                    agenda.delete(ev)
            return {"undo": token, "moved" if move_to else "deleted": len(victims)}
        return JSONResponse(await self.data.run(run))

    # ---- dated notes ----

    async def note_date(self, request: Request) -> JSONResponse:
        """The note's date for the "Ajouter à l'agenda" dialog: {start, alerts, version} (start null: not dated)."""
        _, _, _, _, model, _, _, _ = core()
        relative = check_note_path(request.path_params["path"])

        def run():
            target = self.data.path(relative)
            found = read_versioned(target)
            if found is None:
                raise not_found("Note introuvable (supprimée ou pas encore synchronisée ?)")
            note = fmt.parse(relative, found[0].decode("utf-8", "replace"), 0)
            try:
                ev = model.event_from_note(note)
            except model.AgendaFormatError as error:
                return {"start": None, "alerts": [], "version": found[1], "problem": str(error)}
            return {"start": model.format_stamp(ev.start) if ev else None, "alerts": ev.alerts if ev else [],
                    "version": found[1]}
        return JSONResponse(await self.data.read(run))

    async def date_note(self, request: Request) -> JSONResponse:
        """"Ajouter à l'agenda" on a note: {"start": "2026-11-12" | "2026-11-12T09:00", "alerts": [-15]}, or
        {"start": null} to take it off ("Retirer de l'agenda"). If-Match: the note's version."""
        _, _, _, _, model, _, _, _ = core()
        relative = check_note_path(request.path_params["path"])
        if relative.startswith("agenda/"):
            raise bad_request("Un événement est déjà dans l'agenda")
        expected = require_version(request.headers.get("if-match"))
        body = await json_body(request)
        start = stamp(body["start"], "date") if body.get("start") else None
        try:
            alerts = [int(a) for a in body.get("alerts") or []]
        except (TypeError, ValueError):
            raise bad_request("Rappels invalides") from None

        def run():
            target = self.data.path(relative)
            found = read_versioned(target)
            if found is None:
                raise ApiError(409, "Cette note a été supprimée ailleurs.", current=None)
            if found[1] != expected:
                raise ApiError(409, "Cette note a été modifiée ailleurs (synchronisation, autre appareil).")
            note = fmt.parse(relative, found[0].decode("utf-8"), target.stat().st_mtime_ns // 1_000_000)
            if start is None:
                note = model.strip_agenda(note)
            else:
                try:
                    current = model.event_from_note(note)
                except model.AgendaFormatError:
                    current = None
                base = current or model.Event(note=model.strip_agenda(note), start=start)
                timed = not model.is_all_day(start)
                note = model.event_to_note(base.copy(start=start, end=None, alerts=alerts, rrule=None, exdate=[],
                                                     end_tz=None, tz=base.tz if timed else None))
            saved = self.data.store.save(note.copy(updated=now_ms()))
            return {"path": saved.path, "version": self._version(saved.path)}
        return JSONResponse(await self.data.run(run))

