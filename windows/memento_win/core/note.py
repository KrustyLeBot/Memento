"""Note model and Markdown + YAML frontmatter reader/writer (same format as the Android app).

Pure Python, no Qt: this module is unit-tested on its own.
"""
from __future__ import annotations

import random
import re
import unicodedata
from dataclasses import dataclass, field, replace
from datetime import datetime

from .activity import ActivityInfo

DEFAULT_COLOR = "default"
COLORS = {
    "default": "Par défaut",
    "red": "Corail",
    "orange": "Pêche",
    "yellow": "Sable",
    "green": "Menthe",
    "teal": "Sauge",
    "blue": "Brume",
    "darkblue": "Orage",
    "purple": "Lavande",
    "pink": "Rose",
    "brown": "Argile",
    "gray": "Graphite",
}

_KNOWN = {"id", "title", "color", "pinned", "labels", "reminder", "pdf", "attachments", "activity", "created", "updated"}
_KEY_RE = re.compile(r"^([A-Za-z_][\w-]*):[ \t]*(.*)$")
_ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
CHECK_RE = re.compile(r"^\s*[-*+] \[( |x|X)\] ?(.*)$")


@dataclass
class Note:
    path: str  # file name relative to the notes folder; empty until the first save
    id: str
    title: str = ""
    body: str = ""
    color: str = DEFAULT_COLOR
    pinned: bool = False
    labels: list[str] = field(default_factory=list)
    reminder: int | None = None  # epoch millis, preserved untouched
    pdf: str | None = None  # scanned document, relative to the notes folder ("attachments/<name>.pdf")
    attachments: list[str] = field(default_factory=list)  # attached files, relative to the notes folder
    activity: ActivityInfo | None = None  # set for the notes of the "Activités" folder
    created: int = 0
    updated: int = 0
    has_id: bool = True
    extra: list[str] = field(default_factory=list)  # unknown frontmatter lines, kept verbatim
    conflict: bool = False

    def copy(self, **changes) -> "Note":
        return replace(self, **changes)

    def plain_body(self) -> str:
        out = []
        for line in self.body.split("\n"):
            m = CHECK_RE.match(line)
            out.append(line if m is None else ("☐ " if m[1] == " " else "☑ ") + m[2])
        return "\n".join(out)


def generate_id() -> str:
    return "".join(random.choice(_ID_ALPHABET) for _ in range(6))


def fold(text: str) -> str:
    """Lower-case and accent-free, for search."""
    decomposed = unicodedata.normalize("NFD", text.lower())
    return "".join(c for c in decomposed if not unicodedata.combining(c))


def slug(title: str) -> str:
    s = re.sub(r"[^a-z0-9]+", "-", fold(title)).strip("-")[:40].strip("-")
    return s or "note"


def file_name_for(title: str, note_id: str) -> str:
    return f"{slug(title)}-{note_id}.md"


def attachment_name(path: str) -> str:
    """attachments/facture-edf-k3f9a2.pdf -> facture-edf.pdf"""
    name = path.rsplit("/", 1)[-1]
    stem, dot, ext = name.rpartition(".")
    if not dot or not stem:
        stem, ext = name, ""
    stem = re.sub(r"-[a-z0-9]{6}$", "", stem)
    return f"{stem}.{ext}" if ext else stem


def attachment_path(original: str) -> str:
    """Where a file attached under this name is stored: attachments/<slug>-<id6>.<ext> (random, the caller checks it is free)."""
    name = original.replace("\\", "/").rsplit("/", 1)[-1]
    stem, dot, ext = name.rpartition(".")
    if not dot or not stem:
        stem, ext = name, ""
    ext = "".join(c for c in ext.lower() if c.isascii() and c.isalnum())[:10]
    return f"attachments/{slug(stem)}-{generate_id()}" + (f".{ext}" if ext else "")


# ---- YAML scalars ----

def unquote(raw: str) -> str:
    s = raw.strip()
    if len(s) >= 2 and s[0] == '"' and s[-1] == '"':
        inner, out, i = s[1:-1], [], 0
        while i < len(inner):
            c = inner[i]
            if c == "\\" and i + 1 < len(inner):
                n = inner[i + 1]
                out.append({"n": "\n", "t": "\t", "r": "\r"}.get(n, n))
                i += 2
            else:
                out.append(c)
                i += 1
        return "".join(out)
    if len(s) >= 2 and s[0] == "'" and s[-1] == "'":
        return s[1:-1].replace("''", "'")
    return s


def yaml_quote(s: str, flow: bool = False) -> str:
    reserved = {"true", "false", "null", "yes", "no", "on", "off", "~"}
    needs = (
        not s or s != s.strip() or "\n" in s or ": " in s or " #" in s or s.endswith(":")
        or s[0] in "[]{}&*!|>'\"%@`#,?-:" or s.lower() in reserved
        or re.fullmatch(r"-?\d+(\.\d+)?", s) is not None
        or (flow and any(c in ",[]{}" for c in s)) or "\\" in s
    )
    if not needs:
        return s
    escaped = s.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n").replace("\t", "\\t")
    return f'"{escaped}"'


def parse_list(raw: str) -> list[str]:
    s = raw.strip()
    inner = s[1:-1] if s.startswith("[") and s.endswith("]") else s
    out: list[str] = []
    cur: list[str] = []
    quote: str | None = None
    i = 0
    while i < len(inner):
        c = inner[i]
        if quote is not None:
            cur.append(c)
            if c == "\\" and quote == '"' and i + 1 < len(inner):
                i += 1
                cur.append(inner[i])
            elif c == quote:
                quote = None
        elif c in "\"'":
            quote = c
            cur.append(c)
        elif c == ",":
            out.append("".join(cur))
            cur = []
        else:
            cur.append(c)
        i += 1
    out.append("".join(cur))
    return [v for v in (unquote(x) for x in out) if v]


# ---- timestamps ----

def format_time(ms: int) -> str:
    return datetime.fromtimestamp(ms / 1000).astimezone().isoformat(timespec="seconds")


def parse_time(raw: str) -> int | None:
    s = unquote(raw)
    try:
        dt = datetime.fromisoformat(s)
    except ValueError:
        return None
    return int(dt.astimezone().timestamp() * 1000) if dt.tzinfo is None else int(dt.timestamp() * 1000)


# ---- note <-> text ----

def parse(path: str, raw_text: str, last_modified: int) -> Note:
    text = raw_text.removeprefix("\ufeff").replace("\r\n", "\n")
    file_name = path.rsplit("/", 1)[-1]
    conflict = "conflict" in file_name.lower()
    lines = text.split("\n")
    fallback_title = re.sub(r"[-_]+", " ", file_name.removesuffix(".md")).strip()

    close = -1
    if lines and lines[0].rstrip() == "---":
        for i in range(1, len(lines)):
            if lines[i].rstrip() == "---":
                close = i
                break
    if close < 0:
        # Plain Markdown file created elsewhere: the title comes from the file name.
        return Note(path=path, id=generate_id(), has_id=False, title=fallback_title,
                    created=last_modified, updated=last_modified, body=text.strip("\n"), conflict=conflict)

    blocks: list[tuple[str, list[str]]] = []
    for line in lines[1:close]:
        m = _KEY_RE.match(line)
        if m and not line.startswith((" ", "-")):
            blocks.append((m[1], [line]))
        elif blocks:
            blocks[-1][1].append(line)

    values: dict[str, str] = {}
    extra: list[str] = []
    for key, block in blocks:
        if key in _KNOWN:
            first = _KEY_RE.match(block[0])[2].strip()
            if not first and len(block) > 1:
                items = [yaml_quote(unquote(b.strip().removeprefix("-").strip()), flow=True)
                         for b in block[1:] if b.strip().startswith("-")]
                first = "[" + ", ".join(items) + "]"
            values[key] = first
        else:
            extra.extend(block)

    body = "\n".join(lines[close + 1:]).strip("\n")
    note_id = unquote(values["id"]) if "id" in values else ""
    return Note(
        path=path,
        id=note_id or generate_id(),
        has_id=bool(note_id),
        title=unquote(values["title"]) if "title" in values else fallback_title,
        color=(unquote(values["color"]) or DEFAULT_COLOR) if "color" in values else DEFAULT_COLOR,
        pinned=values.get("pinned", "").strip().lower() == "true",
        labels=parse_list(values["labels"]) if "labels" in values else [],
        reminder=parse_time(values["reminder"]) if "reminder" in values else None,
        pdf=unquote(values["pdf"]) or None if "pdf" in values else None,
        attachments=parse_list(values["attachments"]) if "attachments" in values else [],
        activity=ActivityInfo.parse(values["activity"]) if "activity" in values else None,
        created=(parse_time(values["created"]) if "created" in values else None) or last_modified,
        updated=(parse_time(values["updated"]) if "updated" in values else None) or last_modified,
        body=body,
        extra=extra,
        conflict=conflict,
    )


def serialize(note: Note) -> str:
    out = ["---", f"id: {note.id}", f"title: {yaml_quote(note.title)}"]
    if note.color != DEFAULT_COLOR:
        out.append(f"color: {note.color}")
    if note.pinned:
        out.append("pinned: true")
    if note.labels:
        out.append("labels: [" + ", ".join(yaml_quote(x, flow=True) for x in note.labels) + "]")
    if note.reminder is not None:
        out.append(f"reminder: {format_time(note.reminder)}")
    if note.pdf:
        out.append(f"pdf: {yaml_quote(note.pdf)}")
    if note.attachments:
        out.append("attachments: [" + ", ".join(yaml_quote(x, flow=True) for x in note.attachments) + "]")
    if note.activity is not None:
        out.append(f"activity: {note.activity.to_json()}")
    out.append(f"created: {format_time(note.created)}")
    out.append(f"updated: {format_time(note.updated)}")
    out.extend(note.extra)
    out.append("---")
    text = "\n".join(out) + "\n"
    if note.body.strip():
        text += "\n" + note.body.strip("\n") + "\n"
    return text


# ---- body <-> editor model (same split as the Android editor) ----

@dataclass
class CheckItem:
    text: str
    checked: bool = False


@dataclass
class BodyParts:
    before: str
    items: list[CheckItem]
    after: str


def parse_body(body: str) -> BodyParts:
    """Free text, then one checklist, then trailing text (images and anything else kept untouched)."""
    lines = body.split("\n")
    first = next((i for i, ln in enumerate(lines) if CHECK_RE.match(ln)), -1)
    if first < 0:
        return BodyParts(body.strip("\n"), [], "")
    items: list[CheckItem] = []
    i = first
    while i < len(lines):
        m = CHECK_RE.match(lines[i])
        if m:
            items.append(CheckItem(m[2], m[1] != " "))
        elif lines[i].strip():
            break
        i += 1
    return BodyParts("\n".join(lines[:first]).strip("\n"), items, "\n".join(lines[i:]).strip("\n"))


def build_body(parts: BodyParts) -> str:
    # Checked items always sit at the bottom of the list, like in Keep.
    ordered = [x for x in parts.items if not x.checked] + [x for x in parts.items if x.checked]
    checklist = "\n".join(f"- [{'x' if x.checked else ' '}] {x.text}".rstrip() for x in ordered)
    return "\n\n".join(p for p in (parts.before.strip("\n"), checklist, parts.after.strip("\n")) if p.strip())


URL_RE = re.compile(r"https?://[^\s<>\"]+")


def find_urls(text: str) -> list[str]:
    seen: list[str] = []
    for m in URL_RE.finditer(text):
        url = m.group(0).rstrip(".,;:!?)")
        if url not in seen:
            seen.append(url)
    return seen
