"""Everything that touches a path inside the data folder goes through here: path checks (nothing outside the folder,
no hidden or temporary file), content versions for optimistic concurrency, streamed atomic writes and safe file
responses."""
from __future__ import annotations

import hashlib
import mimetypes
import os
from pathlib import Path
from urllib.parse import quote

from starlette.responses import FileResponse

from .core import atomic
from .errors import ApiError, bad_request, not_found

# Types a browser may display in place. Everything else downloads: an HTML or SVG file opened from the site would
# otherwise run inside its origin.
INLINE = {"application/pdf", "image/jpeg", "image/png", "image/gif", "image/webp", "image/bmp", "video/mp4",
          "video/webm", "video/quicktime", "audio/mpeg", "audio/ogg", "audio/mp4", "audio/wav", "text/plain"}
FILE_CSP = "default-src 'none'; img-src 'self' data:; media-src 'self'; style-src 'unsafe-inline'; sandbox"
# Chrome's PDF viewer refuses to run inside a sandboxed document.
PDF_CSP = "default-src 'none'; img-src 'self' data:; style-src 'unsafe-inline'; object-src 'self'"


def resolve(root: Path, relative: str) -> Path:
    """The absolute path of `relative` inside root. Refused (400): absolute paths, `..`, backslashes, NUL, hidden or
    temporary names, and anything a symlink would carry outside the folder."""
    if not relative or "\x00" in relative or "\\" in relative or relative.startswith("/"):
        raise bad_request("Chemin invalide")
    parts = relative.split("/")
    if any(p in ("", ".", "..") or p.startswith(".") or atomic.is_temp(p) for p in parts):
        raise bad_request("Chemin invalide")
    target = root / relative
    real_root = root.resolve()
    try:
        real = target.resolve()
    except (OSError, RuntimeError):
        raise bad_request("Chemin invalide") from None
    if real != real_root and real_root not in real.parents:
        raise bad_request("Chemin invalide")
    return target


def version_of(data: bytes) -> str:
    """What a client echoes in If-Match: a hash of the bytes, so a sync that rewrote the same content is no conflict
    and one that changed it always is, whatever the share does to modification times."""
    return hashlib.sha256(data).hexdigest()[:24]


def read_versioned(path: Path) -> tuple[bytes, str] | None:
    try:
        data = path.read_bytes()
    except FileNotFoundError:
        return None
    return data, version_of(data)


def require_version(if_match: str | None) -> str:
    if not if_match:
        raise ApiError(428, "Version manquante (en-tête If-Match)")
    return if_match.strip().strip('"')


async def write_stream(target: Path, chunks, limit: int) -> int:
    """Streams a request body to `.<name>.synctmp-<uuid>` next to target, flushed, then renamed over it: the same
    atomic write as the apps. Over `limit` bytes the temporary is removed and 413 raised."""
    target.parent.mkdir(parents=True, exist_ok=True)
    tmp = target.with_name(atomic.temp_name(target.name))
    size = 0
    try:
        with open(tmp, "xb") as handle:
            async for chunk in chunks:
                size += len(chunk)
                if size > limit:
                    raise ApiError(413, f"Fichier trop volumineux ({limit // 1_000_000} Mo au plus)")
                handle.write(chunk)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(tmp, target)
    except BaseException:
        try:
            tmp.unlink()
        except OSError:
            pass
        raise
    return size


def unlink_quiet(path: Path) -> None:
    """Deleting a file that is already gone is what was asked."""
    try:
        path.unlink()
    except FileNotFoundError:
        pass


def file_response(path: Path, download: bool = False) -> FileResponse:
    if not path.is_file():
        raise not_found()
    mime = mimetypes.guess_type(path.name)[0] or "application/octet-stream"
    inline = not download and mime in INLINE
    disposition = "inline" if inline else "attachment"
    name = path.name
    headers = {
        "Content-Disposition": f"{disposition}; filename*=UTF-8''{quote(name)}",
        "Content-Security-Policy": PDF_CSP if mime == "application/pdf" and inline else FILE_CSP,
        "Cache-Control": "private, no-cache",
    }
    return FileResponse(path, media_type=mime if inline else "application/octet-stream", headers=headers)
