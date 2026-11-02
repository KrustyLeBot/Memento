"""The http(s) links of an event's body, same rules as Android's `Links.kt`: `<…>`, quotes and brackets end a
link, trailing punctuation and an unbalanced `)` are not part of it, duplicates are dropped, at most `MAX`."""
from __future__ import annotations

import re
from dataclasses import dataclass

MAX = 10
_URL = re.compile(r"""https?://[^\s<>"'\[\]{}]+""", re.IGNORECASE)
_TRAILING = ".,;:!?*"
_MEETINGS = (("meet.proton.me", "Proton Meet"), ("meet.google.com", "Google Meet"),
             ("teams.microsoft.com", "Microsoft Teams"), ("teams.live.com", "Microsoft Teams"), ("zoom.us", "Zoom"))


@dataclass(frozen=True)
class LinkItem:
    url: str
    label: str  # what the row shows


def _clean(raw: str) -> str:
    s = raw
    while True:
        s = s.rstrip(_TRAILING)
        if s.endswith(")") and s.count("(") < s.count(")"):
            s = s[:-1]
        else:
            return s


def spans(text: str) -> list[tuple[int, int, str]]:
    """Each link of `text` as (start, end, url), in order (duplicates kept): what the detail makes clickable."""
    out = []
    for m in _URL.finditer(text):
        url = _clean(m.group(0))
        if url.split("://", 1)[1]:
            out.append((m.start(), m.start() + len(url), url))
    return out


def host(url: str) -> str:
    rest = url.split("://", 1)[-1]
    for sep in "/?#":
        rest = rest.split(sep, 1)[0]
    return rest.rsplit("@", 1)[-1].split(":", 1)[0].lower()


def meeting_service(url: str) -> str | None:
    """The service of a video-meeting link (Proton Meet, Google Meet, Teams, Zoom), else None."""
    h = host(url)
    return next((name for domain, name in _MEETINGS if h == domain or h.endswith("." + domain)), None)


def meeting_link(text: str) -> str | None:
    """The first video-meeting link of `text`, else None."""
    return next((url for _, _, url in spans(text) if meeting_service(url)), None)


def label(url: str) -> str:
    """"Rejoindre la visio (Proton Meet)", else the domain and the path, shortened: "exemple.fr/agenda/2026/…"."""
    service = meeting_service(url)
    if service:
        return f"Rejoindre la visio ({service})"
    h = host(url)
    h = h[4:] if h.startswith("www.") else h
    rest = url.split("://", 1)[-1]
    cut = next((i for i, c in enumerate(rest) if c in "/?#"), -1)
    path = (rest[cut:] if cut >= 0 else "").rstrip("/")
    return h + (path[:29] + "…" if len(path) > 30 else path)


def extract(text: str, limit: int = MAX) -> list[LinkItem]:
    """The links of `text`, the first meeting link first, each once, at most `limit`."""
    urls: dict[str, None] = {}
    first = meeting_link(text)
    if first:
        urls[first] = None
    for _, _, url in spans(text):
        urls.setdefault(url, None)
    return [LinkItem(u, label(u)) for u in list(urls)[:limit]]


def markdown_autolinks(text: str) -> str:
    """`text` (Markdown) with its bare links as `<…>` autolinks, so that a Markdown view makes each one clickable
    with exactly the `spans()` bounds; links already in `[…](…)`, `<…>` or a link text are left alone."""
    out, at = [], 0
    for start, end, url in spans(text):
        if text[max(0, start - 2):start] == "](" or text[start - 1:start] in ("<", "["):
            continue
        out.append(text[at:start])
        out.append(f"<{url}>")
        at = end
    out.append(text[at:])
    return "".join(out)
