"""iCalendar (RFC 5545) content lines, shared by the import and the export. Same code on Android (`Ics.kt`).

A small reader on purpose: the import keeps every property it does not model *verbatim* in `ical_extra`,
which needs the raw (unfolded) line, and both platforms must cut, unescape and fold exactly alike.
Read line by line from a binary stream (calendars of tens of MB): unfolding is done on bytes (a fold may split
a UTF-8 character), each logical line is decoded as UTF-8, or Latin-1 when it is not valid UTF-8.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import BinaryIO, Iterator


@dataclass
class ContentLine:
    name: str                       # upper case, without a group prefix ("item1.")
    params: dict[str, list[str]]    # upper-case names; values unquoted
    value: str                      # raw value (still escaped)
    raw: str                        # the whole unfolded line, as read

    def param(self, key: str) -> str | None:
        values = self.params.get(key)
        return values[0] if values else None


@dataclass
class Component:
    name: str
    lines: list[ContentLine] = field(default_factory=list)
    children: list["Component"] = field(default_factory=list)
    line_number: int = 0

    def first(self, name: str) -> ContentLine | None:
        return next((p for p in self.lines if p.name == name), None)

    def all(self, name: str) -> list[ContentLine]:
        return [p for p in self.lines if p.name == name]


class IcsSyntaxError(ValueError):
    pass


def logical_lines(stream: BinaryIO) -> Iterator[tuple[int, str]]:
    """(line number, unfolded text) of each non-empty logical line."""
    current: bytearray | None = None
    start = 0
    number = 0
    for raw in stream:
        number += 1
        line = raw.rstrip(b"\r\n")
        if number == 1 and line.startswith(b"\xef\xbb\xbf"):
            line = line[3:]
        if line[:1] in (b" ", b"\t") and current is not None:
            current += line[1:]
            continue
        if current is not None and current.strip():
            yield start, _decode(bytes(current))
        current, start = bytearray(line), number
    if current is not None and current.strip():
        yield start, _decode(bytes(current))


def _decode(data: bytes) -> str:
    try:
        return data.decode("utf-8")
    except UnicodeDecodeError:
        return data.decode("latin-1")


def parse_line(text: str) -> ContentLine:
    """NAME;PARAM=a,"b:c";X=y:value -> ContentLine. IcsSyntaxError when there is no ':'."""
    i, n = 0, len(text)
    while i < n and text[i] not in ";:":
        i += 1
    if i >= n:
        raise IcsSyntaxError(f"ligne sans « : » : {text[:60]!r}")
    name = text[:i].strip().upper()
    if "." in name:
        name = name.rsplit(".", 1)[1]
    params: dict[str, list[str]] = {}
    while i < n and text[i] == ";":
        i += 1
        j = i
        while j < n and text[j] not in "=;:":
            j += 1
        key = text[i:j].strip().upper()
        values: list[str] = []
        if j < n and text[j] == "=":
            j += 1
            while True:
                if j < n and text[j] == '"':
                    k = text.find('"', j + 1)
                    if k < 0:
                        raise IcsSyntaxError(f"guillemet non fermé : {text[:60]!r}")
                    values.append(text[j + 1:k])
                    j = k + 1
                else:
                    k = j
                    while k < n and text[k] not in ",;:":
                        k += 1
                    values.append(text[j:k])
                    j = k
                if j < n and text[j] == ",":
                    j += 1
                    continue
                break
        params.setdefault(key, []).extend(values)
        i = j
    if i >= n or text[i] != ":":
        raise IcsSyntaxError(f"ligne sans « : » : {text[:60]!r}")
    return ContentLine(name, params, text[i + 1:], text)


def read_calendars(stream: BinaryIO, errors: list[str]) -> Iterator[Component]:
    """Each VCALENDAR of the stream, with its components. Unreadable lines are skipped and reported in
    `errors` (French, with the line number); they never stop the reading."""
    stack: list[Component] = []
    for number, text in logical_lines(stream):
        try:
            line = parse_line(text)
        except IcsSyntaxError as error:
            errors.append(f"ligne {number} : {error}")
            continue
        if line.name == "BEGIN":
            comp = Component(line.value.strip().upper(), line_number=number)
            if stack:
                stack[-1].children.append(comp)
            stack.append(comp)
        elif line.name == "END":
            name = line.value.strip().upper()
            if not stack or stack[-1].name != name:
                errors.append(f"ligne {number} : END:{name} inattendu")
                # resynchronise on the matching BEGIN, if any
                while stack and stack[-1].name != name:
                    stack.pop()
                if not stack:
                    continue
            comp = stack.pop()
            if not stack:
                yield comp
        elif stack:
            stack[-1].lines.append(line)
        else:
            errors.append(f"ligne {number} : propriété hors de BEGIN:VCALENDAR")
    if stack:
        errors.append(f"fin de fichier dans {stack[-1].name}")
        yield stack[0]


# ---- text values ----

def unescape_text(value: str) -> str:
    out, i, n = [], 0, len(value)
    while i < n:
        c = value[i]
        if c == "\\" and i + 1 < n:
            nxt = value[i + 1]
            out.append("\n" if nxt in "nN" else nxt)
            i += 2
        else:
            out.append(c)
            i += 1
    return "".join(out)


def escape_text(value: str) -> str:
    return (value.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,")
            .replace("\r\n", "\n").replace("\n", "\\n"))


def split_list(value: str) -> list[str]:
    """Comma list of a value (EXDATE, CATEGORIES), honouring "\\,"."""
    out, cur, i = [], [], 0
    while i < len(value):
        c = value[i]
        if c == "\\" and i + 1 < len(value):
            cur.append(value[i:i + 2])
            i += 2
            continue
        if c == ",":
            out.append("".join(cur))
            cur = []
        else:
            cur.append(c)
        i += 1
    out.append("".join(cur))
    return [x.strip() for x in out if x.strip()]


def fold(line: str) -> str:
    """One content line folded at 75 octets (CRLF + space), never inside a UTF-8 character."""
    out, cur, size = [], [], 0
    limit = 75
    for ch in line:
        width = len(ch.encode("utf-8"))
        if size + width > limit:
            out.append("".join(cur))
            cur, size, limit = [], 0, 74
        cur.append(ch)
        size += width
    out.append("".join(cur))
    return "\r\n ".join(out) + "\r\n"


# ---- durations ----

_DURATION_RE = re.compile(r"^([+-])?P(?:(\d+)W)?(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?$")


def parse_duration(value: str) -> tuple[int, int]:
    """RFC 5545 duration -> (days, seconds), both carrying the sign. ValueError otherwise."""
    m = _DURATION_RE.match(value.strip().upper())
    if not m or value.strip().upper() in ("P", "PT", "+P", "-P"):
        raise ValueError(f"durée illisible : {value!r}")
    sign = -1 if m[1] == "-" else 1
    days = int(m[2] or 0) * 7 + int(m[3] or 0)
    seconds = int(m[4] or 0) * 3600 + int(m[5] or 0) * 60 + int(m[6] or 0)
    return sign * days, sign * seconds


def format_alert_trigger(minutes: int) -> str:
    sign = "-" if minutes < 0 else ""
    n = abs(minutes)
    if n == 0:
        return "PT0M"
    if n % 1440 == 0:
        return f"{sign}P{n // 1440}D"
    if n % 60 == 0:
        return f"{sign}PT{n // 60}H"
    return f"{sign}PT{n}M"


# ---- HTML descriptions ----

_HTML_RE = re.compile(r"<(br|p|div|a|b|i|u|ul|ol|li|span|strong|em|html|body|font|table|tr|td|h[1-6])\b[^>]*>", re.I)
_LINK_RE = re.compile(r"""<a\b[^>]*?href\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))[^>]*>(.*?)</a\s*>""", re.I | re.S)
_ENTITY_RE = re.compile(r"&(#[0-9]+|#[xX][0-9a-fA-F]+|[a-zA-Z]+);")
_NAMED = {"amp": "&", "lt": "<", "gt": ">", "quot": '"', "apos": "'", "nbsp": " "}


def looks_html(text: str) -> bool:
    return _HTML_RE.search(text) is not None


def _entities(text: str) -> str:
    def repl(m: re.Match) -> str:
        e = m[1]
        if e.startswith("#"):
            try:
                code = int(e[2:], 16) if e[1:2] in "xX" else int(e[1:])
                return chr(code) if 0 < code < 0x110000 else m[0]
            except ValueError:
                return m[0]
        return _NAMED.get(e.lower(), m[0])
    return _ENTITY_RE.sub(repl, text)


def html_to_text(text: str) -> str:
    """Plain text of an HTML description: line breaks kept, links as "texte (url)", tags dropped."""
    s = re.sub(r"(?is)<(script|style)\b.*?</\1\s*>", "", text)

    def link(m: re.Match) -> str:
        href = _entities(m[1] if m[1] is not None else m[2] if m[2] is not None else m[3]).strip()
        label = _entities(re.sub(r"<[^>]+>", "", m[4])).strip()
        return href if not label or label == href else f"{label} ({href})"

    s = _LINK_RE.sub(link, s)
    s = re.sub(r"(?i)<br\s*/?>", "\n", s)
    s = re.sub(r"(?i)<li\b[^>]*>", "\n- ", s)
    s = re.sub(r"(?i)</(p|div|ul|ol|tr|h[1-6])\s*>", "\n", s)
    s = re.sub(r"(?i)<(p|div|ul|ol|tr|h[1-6])\b[^>]*>", "\n", s)
    s = re.sub(r"<[^>]+>", "", s)
    s = _entities(s).replace(" ", " ")
    lines = [ln.strip() for ln in s.replace("\r\n", "\n").split("\n")]
    s = "\n".join(lines)
    s = re.sub(r"\n{3,}", "\n\n", s)
    return s.strip()

