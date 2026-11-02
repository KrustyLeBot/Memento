"""Contacts module: list (favourites, letters, search), read, create, update (409 when the file changed since the
client loaded it), delete with undo, vCard import / export, duplicates. Formats and algorithms come from the Windows
core (contacts.py, vcard.py): sorting, sections, search, merge, import plan are all the core's.

A contact is addressed by its `ref`: its id, or for a sync conflict copy (which shares its original's id) its file
name in contacts/. The UI's route is `#/contacts/<ref>`; the agenda links birthdays there by id.

An import runs in steps, each well under Cloudflare's 100 s (and the UI's 60 s): upload + preview, start (the plan,
the undo stash), then `step` until done, the page showing the progress.
"""
from __future__ import annotations

import secrets
import time
from dataclasses import dataclass

from starlette.requests import Request
from starlette.responses import JSONResponse, Response
from starlette.routing import Route

from ..core import fmt
from memento_win.core import contacts as ct  # noqa: E402 (on the path once ..core is imported)
from ..errors import ApiError, bad_request, json_body, not_found
from ..files import read_versioned, require_version, unlink_quiet, write_stream

MAX_VCF = 20_000_000  # as the Windows import
STEP_SECONDS = 4.0  # work done per import request; the page asks for the next step at once
IMPORT_TTL = 30 * 60
EDITABLE = {"first_name", "last_name", "phones", "emails", "address", "birthday", "notes", "favorite"}
POLICIES = {"keep_both": "KEEP_BOTH", "skip": "SKIP", "update": "UPDATE"}


def ref_of(c) -> str:
    return c.path.rsplit("/", 1)[1] if c.conflict else c.id


def summary(c) -> dict:
    second = c.phones[0].number if c.phones else (c.emails[0] if c.emails else "")
    return {"ref": ref_of(c), "id": c.id, "name": c.display_name, "second": "" if second == c.display_name else second,
            "favorite": c.favorite, "conflict": c.conflict}


def full(c, version: str) -> dict:
    b = c.birthday
    return {"contact": {
        "ref": ref_of(c), "id": c.id, "path": c.path, "name": c.display_name,
        "first_name": c.first_name, "last_name": c.last_name,
        "phones": [{"number": p.number, "label": p.label} for p in c.phones], "emails": list(c.emails),
        "address": c.address, "birthday": {"day": b.day, "month": b.month, "year": b.year} if b else None,
        "notes": c.notes, "favorite": c.favorite, "conflict": c.conflict, "created": c.created, "updated": c.updated,
    }, "version": version}


def _text(value, what: str) -> str:
    if value is None:
        return ""
    if not isinstance(value, str):
        raise bad_request(f"{what} invalide")
    return value


def apply_changes(c, body: dict):
    """The fields a client may change; anything else in the file (unknown keys, uid, import id) stays."""
    unknown = set(body) - EDITABLE
    if unknown:
        raise bad_request(f"Champs inconnus : {', '.join(sorted(unknown))}")
    changes: dict = {}
    for key, what in (("first_name", "Prénom"), ("last_name", "Nom")):
        if key in body:
            changes[key] = _text(body[key], what).strip()
    if "phones" in body:
        try:
            phones = [ct.Phone(_text(p["number"], "Numéro").strip(), (_text(p.get("label"), "Type").strip() or None))
                      for p in body["phones"]]
        except (KeyError, TypeError, AttributeError):
            raise bad_request("Numéros invalides") from None
        changes["phones"] = [p for p in phones if p.number]
    if "emails" in body:
        if not isinstance(body["emails"], list):
            raise bad_request("E-mails invalides")
        changes["emails"] = [e for e in (_text(x, "E-mail").strip() for x in body["emails"]) if e]
    if "address" in body:
        changes["address"] = _text(body["address"], "Adresse").strip() or None
    if "birthday" in body:
        b = body["birthday"]
        if b is None:
            changes["birthday"] = None
        else:
            try:
                year = b.get("year")
                birthday = ct.Birthday.of(int(b["day"]), int(b["month"]), None if year in (None, "") else int(year))
            except (KeyError, TypeError, ValueError, AttributeError):
                birthday = None
            if birthday is None:
                raise bad_request("Date d'anniversaire invalide")
            changes["birthday"] = birthday
    if "notes" in body:
        changes["notes"] = _text(body["notes"], "Notes").rstrip()
    if "favorite" in body:
        changes["favorite"] = bool(body["favorite"])
    result = c.copy(**changes)
    if result.is_empty:
        raise bad_request("Le contact est vide")
    return result


def first_name_first(request: Request) -> bool:
    return request.query_params.get("first") == "1"


@dataclass
class Running:
    """An import between its start and its last step (in memory: the steps follow each other at once)."""
    import_id: str
    creates: list
    updates: list
    skipped: int
    unchanged: int
    bad: int
    empty: int
    ignored: dict
    undo: str
    created: int = 0
    updated: int = 0

    @property
    def total(self) -> int:
        return self.created + self.updated + len(self.creates) + len(self.updates)


class Contacts:
    def __init__(self, data):
        self.data = data
        self.imports: dict[str, Running] = {}
        data.idle_hooks.append(self.imports.clear)

    @property
    def staging(self):
        return self.data.config.config_dir / "contact-imports"

    def routes(self) -> list[Route]:
        return [
            Route("/api/contacts", self.list, methods=["GET"]),
            Route("/api/contacts", self.create, methods=["POST"]),
            Route("/api/contacts/export/contacts.vcf", self.export_all, methods=["GET"]),
            Route("/api/contacts/duplicates", self.duplicates, methods=["GET"]),
            Route("/api/contacts/merge", self.merge, methods=["POST"]),
            Route("/api/contacts/import", self.import_upload, methods=["POST"]),
            Route("/api/contacts/import/{token}/start", self.import_start, methods=["POST"]),
            Route("/api/contacts/import/{token}/step", self.import_step, methods=["POST"]),
            Route("/api/contacts/{ref}/vcard", self.export_one, methods=["GET"]),
            Route("/api/contacts/{ref}", self.get, methods=["GET"]),
            Route("/api/contacts/{ref}", self.update, methods=["PUT"]),
            Route("/api/contacts/{ref}", self.delete, methods=["DELETE"]),
        ]

    # ---- helpers (called under the data lock) ----

    def _all(self) -> list:
        return self.data.store.scan_contacts()

    def _path_of(self, ref: str) -> str:
        if "/" in ref or not ref:
            raise bad_request("Contact invalide")
        if "." in ref:  # a conflict copy, by file name
            path = f"{ct.DIR}/{ref}"
            self.data.path(path)
            return path
        found = next((c for c in self._all() if c.id == ref and not c.conflict), None)
        if found is None:
            raise not_found("Contact introuvable (supprimé ou pas encore synchronisé ?)")
        return found.path

    def _load(self, ref: str):
        path = self._path_of(ref)
        found = read_versioned(self.data.path(path))
        if found is None:
            raise not_found("Contact introuvable (supprimé ou pas encore synchronisé ?)")
        raw, version = found
        try:
            text = raw.decode("utf-8")
        except UnicodeDecodeError:
            raise ApiError(422, "Ce contact n'est pas du texte UTF-8 : il ne peut pas être ouvert ici.") from None
        mtime = self.data.path(path).stat().st_mtime_ns // 1_000_000
        return ct.from_note(fmt.parse(path, text, mtime)), version

    def _load_expected(self, ref: str, expected: str):
        try:
            c, version = self._load(ref)
        except ApiError as error:
            if error.status == 404:
                raise ApiError(409, "Ce contact a été supprimé ailleurs (synchronisation, autre appareil).",
                               current=None) from None
            raise
        if version != expected:
            raise ApiError(409, "Ce contact a été modifié ailleurs (synchronisation, autre appareil).",
                           current=full(c, version))
        return c

    def _save(self, c) -> dict:
        self.data.store.scan_contacts()  # the book's index, for a new contact's unique id
        saved = self.data.store.contacts.save(c)
        raw, version = read_versioned(self.data.path(saved.path))
        return full(saved, version)

    # ---- list, read, write ----

    async def list(self, request: Request) -> JSONResponse:
        query = request.query_params.get("q", "").strip()
        first = first_name_first(request)

        def run():
            people = self._all()
            live = sum(1 for c in people if not c.conflict)
            if query:
                found = ct.search(people, query, first)
                return {"sections": [{"title": "", "contacts": [summary(c) for c in found]}] if found else [],
                        "count": live, "results": len(found)}
            return {"sections": [{"title": t, "contacts": [summary(c) for c in members]}
                                 for t, members in ct.sections(people, first)],
                    "count": live, "results": None}
        return JSONResponse(await self.data.read(run))

    async def get(self, request: Request) -> JSONResponse:
        c, version = await self.data.read(self._load, request.path_params["ref"])
        return JSONResponse(full(c, version))

    async def create(self, request: Request) -> JSONResponse:
        body = await json_body(request)
        c = apply_changes(ct.Contact(), body)
        return JSONResponse(await self.data.run(self._save, c), status_code=201)

    async def update(self, request: Request) -> JSONResponse:
        ref = request.path_params["ref"]
        expected = require_version(request.headers.get("if-match"))
        body = await json_body(request)

        def run():
            return self._save(apply_changes(self._load_expected(ref, expected), body))
        return JSONResponse(await self.data.run(run))

    async def delete(self, request: Request) -> JSONResponse:
        ref = request.path_params["ref"]
        expected = require_version(request.headers.get("if-match"))

        def run():
            c = self._load_expected(ref, expected)
            token = self.data.undo.stash(self.data.root, [c.path])
            unlink_quiet(self.data.path(c.path))
            return {"undo": token}
        return JSONResponse(await self.data.run(run))

    # ---- export ----

    @staticmethod
    def _vcf(text: str, name: str) -> Response:
        from urllib.parse import quote
        return Response(text.encode("utf-8"), media_type="text/vcard; charset=utf-8",
                        headers={"Content-Disposition": f"attachment; filename*=UTF-8''{quote(name)}"})

    async def export_one(self, request: Request) -> Response:
        from memento_win.core import vcard
        c, _ = await self.data.read(self._load, request.path_params["ref"])
        return self._vcf(vcard.write([c]), f"{fmt.slug(c.display_name) or 'contact'}.vcf")

    async def export_all(self, request: Request) -> Response:
        from memento_win.core import vcard
        first = first_name_first(request)

        def run():
            return vcard.write(ct.sorted_contacts([c for c in self._all() if not c.conflict], first))
        return self._vcf(await self.data.read(run), "contacts.vcf")

    # ---- duplicates ----

    async def duplicates(self, request: Request) -> JSONResponse:
        first = first_name_first(request)

        def run():
            live = [c for c in self._all() if not c.conflict]
            return {"groups": [{"keep": ct.best(g).id, "contacts": [
                {**summary(c), "details": [p.number for p in c.phones] + list(c.emails)} for c in g]}
                for g in ct.duplicate_groups(live, first)]}
        return JSONResponse(await self.data.read(run))

    async def merge(self, request: Request) -> JSONResponse:
        """{"keep": <id>, "others": [<id>…]}: the kept contact gets the others' numbers, e-mails and notes (core
        merge); the others are deleted. Undoable."""
        body = await json_body(request)
        keep, others = body.get("keep"), body.get("others")
        if not isinstance(keep, str) or not isinstance(others, list) or not others \
                or not all(isinstance(o, str) for o in others) or keep in others:
            raise bad_request("Fusion invalide")

        def run():
            people = {c.id: c for c in self._all() if not c.conflict}
            missing = [r for r in [keep, *others] if r not in people]
            if missing:
                raise ApiError(409, "Un de ces contacts a changé ailleurs entre-temps : la liste est rechargée.")
            kept, gone = people[keep], [people[o] for o in dict.fromkeys(others)]
            token = self.data.undo.stash(self.data.root, [kept.path] + [o.path for o in gone])
            self.data.store.contacts.save(ct.merge(kept, gone))
            for o in gone:
                unlink_quiet(self.data.path(o.path))
            return {"undo": token, "ref": kept.id}
        return JSONResponse(await self.data.run(run))

    # ---- import ----

    def _staged(self, token: str):
        if not token.replace("-", "").replace("_", "").isalnum() or len(token) > 64:
            raise bad_request("Import inconnu")
        return self.staging / f"{token}.vcf"

    def _purge_staging(self) -> None:
        if not self.staging.is_dir():
            return
        now = time.time()
        for f in self.staging.iterdir():
            try:
                if now - f.stat().st_mtime > IMPORT_TTL:
                    f.unlink()
            except OSError:
                pass

    def _parse_staged(self, token: str):
        from memento_win.core import vcard
        try:
            raw = self._staged(token).read_bytes()
        except FileNotFoundError:
            raise ApiError(410, "Cet import a expiré : choisissez le fichier à nouveau.") from None
        return vcard.parse(raw)

    async def import_upload(self, request: Request) -> JSONResponse:
        """The .vcf as the request body (?name=): parsed, kept aside in /config, previewed."""
        name = request.query_params.get("name", "contacts.vcf")
        length = request.headers.get("content-length")
        if length and length.isdigit() and int(length) > MAX_VCF:
            raise ApiError(413, "Fichier trop gros (20 Mo au plus)")
        await self.data.read(self._purge_staging)
        token = secrets.token_urlsafe(12)
        target = self._staged(token)
        try:
            await write_stream(target, request.stream(), MAX_VCF)
        except ApiError as error:
            if error.status == 413:
                raise ApiError(413, "Fichier trop gros (20 Mo au plus)") from None
            raise

        def run():
            result = self._parse_staged(token)
            if not result.contacts and not result.bad and not result.empty:
                unlink_quiet(target)
                raise bad_request("Aucune fiche vCard dans ce fichier")
            p = ct.preview(result, self._all())
            return {"token": token, "name": name, "cards": p.cards, "duplicates": p.duplicates, "blank": p.blank,
                    "bad": p.bad, "empty": p.empty, "ignored": p.ignored,
                    "policies": [{"key": k, "label": ct.DuplicatePolicy[v].value} for k, v in POLICIES.items()]}
        return JSONResponse(await self.data.read(run))

    async def import_start(self, request: Request) -> JSONResponse:
        """{"policy": "keep_both" | "skip" | "update"}: plans the import (core plan) and stashes what it will touch,
        so the undo token works even if the import is interrupted half way."""
        token = request.path_params["token"]
        body = await json_body(request)
        if body.get("policy") not in POLICIES:
            raise bad_request("Choix des doublons inconnu")
        if token in self.imports:
            raise ApiError(409, "Cet import est déjà lancé")

        def run():
            result = self._parse_staged(token)
            now = ct.now_ms()
            import_id = ct.new_import_id(now)
            plan = ct.plan(result, [c for c in self._all()], ct.DuplicatePolicy[POLICIES[body["policy"]]], import_id, now)
            undo = self.data.undo.stash(self.data.root, [b.path for b, _ in plan.updates],
                                        created=[c.path for c in plan.creates])
            unlink_quiet(self._staged(token))
            self.imports[token] = Running(import_id, list(plan.creates), list(plan.updates), plan.skipped,
                                          plan.unchanged, result.bad, result.empty, dict(result.ignored), undo)
            return self._progress(self.imports[token], token)
        return JSONResponse(await self.data.read(run))

    def _progress(self, r: Running, token: str) -> dict:
        done = not r.creates and not r.updates
        out = {"done": r.created + r.updated, "total": r.total, "finished": done, "undo": r.undo}
        if done:
            self.imports.pop(token, None)
            out["report"] = {"created": r.created, "updated": r.updated, "unchanged": r.unchanged,
                             "skipped": r.skipped, "bad": r.bad, "empty": r.empty, "ignored": r.ignored}
        return out

    async def import_step(self, request: Request) -> JSONResponse:
        """Writes contacts for about STEP_SECONDS (core ContactBook.apply, a slice at a time) and says how far."""
        token = request.path_params["token"]

        from memento_win.core import vcard

        def run():
            r = self.imports.get(token)
            if r is None:
                raise ApiError(410, "Import interrompu (serveur redémarré ou inactif). « Annuler » retire ce qui a été importé.")
            deadline = time.monotonic() + STEP_SECONDS
            book = self.data.store.contacts
            while r.creates or r.updates:  # at least one slice per step, however slow the disk
                creates, updates = r.creates[:20], (r.updates[:20] if not r.creates else [])
                book.apply(ct.ImportPlan(creates, updates, 0, 0), r.import_id, vcard.VCardResult())
                del r.creates[:len(creates)]
                del r.updates[:len(updates)]
                r.created += len(creates)
                r.updated += len(updates)
                if time.monotonic() >= deadline:
                    break
            return self._progress(r, token)
        return JSONResponse(await self.data.run(run))
