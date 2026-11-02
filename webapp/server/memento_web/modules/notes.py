"""Notes module: list, read, create, update (409 when the file changed since the client loaded it), delete with undo,
attachments and drawings. Formats come from the Windows core (note.py, drawing.py, store.py).

A note is addressed by its path relative to the data folder (`courses-k3f9a2.md`, `agenda/2026/rdv-x1y2z3.md`,
`activities/sortie-a1b2c3.md`): ids are not unique across conflict copies, paths are.
"""
from __future__ import annotations

import re

from starlette.requests import Request
from starlette.responses import JSONResponse
from starlette.routing import Route

from ..core import MAX_ATTACHMENT, atomic, drawing, fmt, now_ms
from ..errors import ApiError, bad_request, json_body, not_found
from ..files import owned, read_versioned, require_version, unlink_quiet, write_stream

NOTE_PATH = re.compile(r"^(?:[^/]+|activities/[^/]+|agenda/[^/]+/[^/]+)\.md$")
EDITABLE = {"title", "body", "parts", "color", "pinned", "labels"}


def check_note_path(relative: str) -> str:
    if not NOTE_PATH.match(relative):
        raise bad_request("Chemin de note invalide")
    return relative


def preview(note: fmt.Note) -> str:
    """The card's text: the plain body (checklist as ☐/☑), no images, markers and headings flattened; as on Windows."""
    lines = [ln for ln in note.plain_body().split("\n") if ln.strip() and not ln.lstrip().startswith("![")]
    cleaned = []
    for ln in lines[:8]:
        ln = ln.replace("**", "")
        if ln.startswith("#"):
            ln = ln.lstrip("#").strip()
        cleaned.append(ln)
    return "\n".join(cleaned)[:300]


def summary(note: fmt.Note) -> dict:
    items = fmt.parse_body(note.body).items
    return {
        "path": note.path, "id": note.id, "title": note.title, "preview": preview(note), "color": note.color,
        "pinned": note.pinned, "labels": note.labels, "pdf": note.pdf, "attachments": note.attachments,
        "conflict": note.conflict, "created": note.created, "updated": note.updated,
        "checklist": {"done": sum(i.checked for i in items), "total": len(items)} if items else None,
    }


def full(note: fmt.Note, version: str) -> dict:
    parts = fmt.parse_body(note.body)
    return {"note": {**summary(note), "body": note.body,
                     "parts": {"before": parts.before, "items": [{"text": i.text, "checked": i.checked} for i in parts.items],
                               "after": parts.after}},
            "version": version}


def matches(note: fmt.Note, query: str, label: str | None) -> bool:
    if label and label not in note.labels:
        return False
    if not query:
        return True
    return query in fmt.fold(note.title) or query in fmt.fold(note.body) or any(query in fmt.fold(x) for x in note.labels)


def apply_changes(note: fmt.Note, body: dict) -> fmt.Note:
    """The fields a client may change; anything else in the file (unknown keys, ids, dates) stays as it is."""
    unknown = set(body) - EDITABLE
    if unknown:
        raise bad_request(f"Champs inconnus : {', '.join(sorted(unknown))}")
    changes: dict = {}
    if "title" in body:
        changes["title"] = str(body["title"]).strip()
    if "parts" in body:
        p = body["parts"]
        try:
            items = [fmt.CheckItem(str(i["text"]), bool(i["checked"])) for i in p.get("items", [])]
            changes["body"] = fmt.build_body(fmt.BodyParts(str(p.get("before", "")), items, str(p.get("after", ""))))
        except (AttributeError, KeyError, TypeError):
            raise bad_request("Corps de note invalide") from None
    elif "body" in body:
        changes["body"] = str(body["body"])
    if "color" in body:
        if body["color"] not in fmt.COLORS:
            raise bad_request("Couleur inconnue")
        changes["color"] = body["color"]
    if "pinned" in body:
        changes["pinned"] = bool(body["pinned"])
    if "labels" in body:
        if not isinstance(body["labels"], list):
            raise bad_request("Libellés invalides")
        labels = [str(x).strip().replace(",", " ") for x in body["labels"]]
        changes["labels"] = list(dict.fromkeys(x for x in labels if x))
    return note.copy(**changes)


class Notes:
    def __init__(self, data):
        self.data = data

    def routes(self) -> list[Route]:
        return [
            Route("/api/notes", self.list, methods=["GET"]),
            Route("/api/notes", self.create, methods=["POST"]),
            Route("/api/notes/{path:path}/attachments", self.add_attachment, methods=["POST"]),
            Route("/api/notes/{path:path}/attachments", self.remove_attachment, methods=["DELETE"]),
            Route("/api/notes/{path:path}/drawings", self.save_drawing, methods=["POST"]),
            Route("/api/notes/{path:path}", self.get, methods=["GET"]),
            Route("/api/notes/{path:path}", self.update, methods=["PUT"]),
            Route("/api/notes/{path:path}", self.delete, methods=["DELETE"]),
            Route("/api/drawings/{path:path}", self.get_drawing, methods=["GET"]),
        ]

    # ---- helpers (called under the data lock) ----

    def _load(self, relative: str) -> tuple[fmt.Note, str]:
        target = self.data.path(check_note_path(relative))
        found = read_versioned(target)
        if found is None:
            raise not_found("Note introuvable (supprimée ou pas encore synchronisée ?)")
        raw, version = found
        try:
            text = raw.decode("utf-8")
        except UnicodeDecodeError:
            raise ApiError(422, "Cette note n'est pas du texte UTF-8 : elle ne peut pas être ouverte ici.") from None
        return fmt.parse(relative, text, target.stat().st_mtime_ns // 1_000_000), version

    def _load_expected(self, relative: str, expected: str) -> fmt.Note:
        """The note as on disk, refused with 409 (and the disk version, for "Recharger") when it is not the one the
        client edited: a sync or another tab changed it meanwhile."""
        try:
            note, version = self._load(relative)
        except ApiError as error:
            if error.status == 404:
                raise ApiError(409, "Cette note a été supprimée ailleurs (synchronisation, autre appareil).",
                               current=None) from None
            raise
        if version != expected:
            raise ApiError(409, "Cette note a été modifiée ailleurs (synchronisation, autre appareil).",
                           current=full(note, version))
        return note

    def _save(self, note: fmt.Note) -> dict:
        saved = self.data.store.save(note.copy(updated=now_ms()))
        raw, version = read_versioned(self.data.path(saved.path))
        return full(saved, version)

    def _referenced_elsewhere(self, note: fmt.Note) -> set[str]:
        """Files another note still lists: a conflict copy shares its original's attachments."""
        others = self.data.store.scan() + self.data.store.scan_activities()
        return {a for n in others if n.path != note.path for a in ([n.pdf] if n.pdf else []) + n.attachments}

    # ---- endpoints ----

    async def list(self, request: Request) -> JSONResponse:
        query = fmt.fold(request.query_params.get("q", "").strip())
        label = request.query_params.get("label") or None

        def run():
            notes = self.data.store.scan()
            shown = [n for n in notes if matches(n, query, label)]
            shown.sort(key=lambda n: (not n.pinned, -n.updated))
            labels = sorted({x for n in notes for x in n.labels}, key=fmt.fold)
            return {"notes": [summary(n) for n in shown], "labels": labels,
                    "conflicts": sum(1 for n in notes if n.conflict)}
        return JSONResponse(await self.data.read(run))

    async def get(self, request: Request) -> JSONResponse:
        relative = request.path_params["path"]
        note, version = await self.data.read(self._load, relative)
        return JSONResponse(full(note, version))

    async def create(self, request: Request) -> JSONResponse:
        body = await json_body(request)
        now = now_ms()
        note = apply_changes(fmt.Note(path="", id=fmt.generate_id(), created=now, updated=now), body)
        return JSONResponse(await self.data.run(self._save, note), status_code=201)

    async def update(self, request: Request) -> JSONResponse:
        relative = request.path_params["path"]
        expected = require_version(request.headers.get("if-match"))
        body = await json_body(request)

        def run():
            return self._save(apply_changes(self._load_expected(relative, expected), body))
        return JSONResponse(await self.data.run(run))

    async def delete(self, request: Request) -> JSONResponse:
        relative = request.path_params["path"]
        expected = require_version(request.headers.get("if-match"))

        def run():
            note = self._load_expected(relative, expected)
            keep = self._referenced_elsewhere(note)
            files = [f for f in ([note.pdf] if note.pdf else []) + note.attachments if owned(f) and f not in keep]
            token = self.data.undo.stash(self.data.root, [note.path] + files)
            for f in [note.path] + files:
                unlink_quiet(self.data.path(f))
            return {"undo": token}
        return JSONResponse(await self.data.run(run))

    async def add_attachment(self, request: Request) -> JSONResponse:
        relative = check_note_path(request.path_params["path"])
        expected = require_version(request.headers.get("if-match"))
        name = request.query_params.get("name", "").strip()
        if not name:
            raise bad_request("Nom de fichier manquant")
        length = request.headers.get("content-length")
        if length and length.isdigit() and int(length) > MAX_ATTACHMENT:
            raise ApiError(413, f"Fichier trop volumineux ({MAX_ATTACHMENT // 1_000_000} Mo au plus)")

        def reserve():
            self._load_expected(relative, expected)
            target = fmt.attachment_path(name)
            while self.data.path(target).exists():
                target = fmt.attachment_path(name)
            return target
        attachment = await self.data.read(reserve)
        await write_stream(self.data.path(attachment), request.stream(), MAX_ATTACHMENT)

        def attach():
            try:
                note = self._load_expected(relative, expected)
            except ApiError:
                unlink_quiet(self.data.path(attachment))
                raise
            return self._save(note.copy(attachments=note.attachments + [attachment]))
        return JSONResponse(await self.data.run(attach))

    async def remove_attachment(self, request: Request) -> JSONResponse:
        relative = request.path_params["path"]
        expected = require_version(request.headers.get("if-match"))
        attachment = request.query_params.get("file", "")

        def run():
            note = self._load_expected(relative, expected)
            if attachment not in note.attachments:
                raise not_found("Cette pièce jointe n'est plus dans la note")
            result = self._save(note.copy(attachments=[a for a in note.attachments if a != attachment]))
            if owned(attachment) and attachment not in self._referenced_elsewhere(note):
                unlink_quiet(self.data.path(attachment))
            return result
        return JSONResponse(await self.data.run(run))

    async def get_drawing(self, request: Request) -> JSONResponse:
        relative = request.path_params["path"]
        if not drawing.is_drawing(relative):
            raise bad_request("Ce fichier n'est pas un dessin")

        def run():
            found = read_versioned(self.data.path(relative))
            if found is None:
                raise not_found("Dessin introuvable (pas encore synchronisé ?)")
            raw, version = found
            return {"strokes": [_stroke_json(s) for s in drawing.parse(raw.decode("utf-8", "replace"))], "version": version}
        return JSONResponse(await self.data.read(run))

    async def save_drawing(self, request: Request) -> JSONResponse:
        """{"file": null | "drawings/dessin-x.svg", "strokes": [...]}: creates, rewrites, or (no stroke) removes."""
        relative = request.path_params["path"]
        expected = require_version(request.headers.get("if-match"))
        body = await json_body(request)
        existing = body.get("file")
        if existing is not None and not drawing.is_drawing(str(existing)):
            raise bad_request("Ce fichier n'est pas un dessin")
        strokes = [_stroke(s) for s in body.get("strokes") or []]

        def run():
            note = self._load_expected(relative, expected)
            attachments = list(note.attachments)
            if existing is not None and existing not in attachments:
                raise not_found("Ce dessin n'est plus dans la note")
            if not strokes:
                if existing is not None:
                    attachments.remove(existing)
                    unlink_quiet(self.data.path(existing))
            else:
                target = existing
                while target is None or (existing is None and self.data.path(target).exists()):
                    target = drawing.new_path(fmt.generate_id)
                atomic.write_text(self.data.path(target), drawing.to_svg(strokes))
                if target not in attachments:
                    attachments.append(target)
            return self._save(note.copy(attachments=attachments))
        return JSONResponse(await self.data.run(run))


def _stroke_json(s: drawing.Stroke) -> dict:
    return {"color": f"#{s.rgb & 0xFFFFFF:06x}", "width": s.width, "points": [list(p) for p in s.points]}


def _stroke(raw) -> drawing.Stroke:
    try:
        color = str(raw["color"])
        if not re.fullmatch(r"#[0-9a-fA-F]{6}", color):
            raise ValueError
        points = tuple((float(x), float(y)) for x, y in raw["points"])
        width = float(raw["width"])
        if not points or not 0 < width <= 100:
            raise ValueError
    except (KeyError, TypeError, ValueError):
        raise bad_request("Trait de dessin invalide") from None
    return drawing.Stroke(int(color[1:], 16), width, points)
