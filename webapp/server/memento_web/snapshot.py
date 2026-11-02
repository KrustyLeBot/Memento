"""The parsed indexes saved to /config/cache/index-<area>.bin, so a restarted server answers at once instead of
reading and parsing the whole data folder again (slow on a NAS whose disk cache went cold).

File: MAGIC + HMAC-SHA256(payload) + payload, the payload a pickle of
    {"format": FORMAT, "code": <hash of the core's sources>, "area": area, "meta": {...},
     "entries": {path: ((mtime_ns, size), value)}}
The HMAC key is the session secret: only this server can have written a file it unpickles. A file of another format,
another version of the core (whose classes the values are), with a bad signature or unreadable is ignored (and
replaced on the next save). An entry is used only while its file still has the same modification time and size.
"""
from __future__ import annotations

import hashlib
import hmac
import os
import pickle
import uuid
from pathlib import Path

FORMAT = 1
MAGIC = b"MWIX1\n"
AREAS = ("notes", "activities", "contacts", "agenda")


def _log(text: str) -> None:
    print(f"memento-web index: {text}", flush=True)


def code_version() -> str:
    """Changes with any source file of the Windows core (the classes pickled) or of this format."""
    import memento_win.core as core
    digest = hashlib.sha256(str(FORMAT).encode())
    root = Path(core.__file__).resolve().parent
    for path in sorted(root.rglob("*.py")):
        digest.update(path.relative_to(root).as_posix().encode())
        digest.update(path.read_bytes())
    return digest.hexdigest()[:24]


def stamp_of(path: str) -> tuple[int, int] | None:
    try:
        st = os.stat(path)
    except OSError:
        return None
    return st.st_mtime_ns, st.st_size


class Snapshots:
    def __init__(self, directory: Path, key: bytes):
        self.directory = directory
        self.key = key
        self._code: str | None = None

    @property
    def code(self) -> str:
        if self._code is None:
            self._code = code_version()
        return self._code

    def file(self, area: str) -> Path:
        return self.directory / f"index-{area}.bin"

    def save(self, area: str, entries: dict, meta: dict | None = None) -> None:
        payload = pickle.dumps({"format": FORMAT, "code": self.code, "area": area, "meta": meta or {},
                                "entries": entries}, protocol=pickle.HIGHEST_PROTOCOL)
        signature = hmac.new(self.key, payload, hashlib.sha256).digest()
        try:
            self.directory.mkdir(parents=True, exist_ok=True)
            tmp = self.directory / f".index-{area}.{uuid.uuid4().hex}.tmp"
            with open(tmp, "wb") as handle:
                handle.write(MAGIC)
                handle.write(signature)
                handle.write(payload)
            os.replace(tmp, self.file(area))
        except OSError as error:  # an optimisation: the server works without it
            _log(f"cannot save the {area} index: {error}")

    def load(self, area: str) -> tuple[dict, dict] | None:
        """(entries, meta), or None when there is no usable file."""
        try:
            raw = self.file(area).read_bytes()
        except FileNotFoundError:
            return None
        except OSError as error:
            _log(f"cannot read the {area} index: {error}")
            return None
        head, signature, payload = raw[:len(MAGIC)], raw[len(MAGIC):len(MAGIC) + 32], raw[len(MAGIC) + 32:]
        if head != MAGIC or not hmac.compare_digest(signature, hmac.new(self.key, payload, hashlib.sha256).digest()):
            _log(f"{area} index ignored (not written by this server, or damaged)")
            return None
        try:
            data = pickle.loads(payload)
        except Exception as error:  # noqa: BLE001 — signed but unreadable (a class moved): rebuilt from the files
            _log(f"{area} index ignored ({type(error).__name__})")
            return None
        if not isinstance(data, dict) or data.get("format") != FORMAT or data.get("code") != self.code \
                or data.get("area") != area or not isinstance(data.get("entries"), dict):
            return None
        return data["entries"], data.get("meta") or {}

    def valid(self, root: Path, entries: dict) -> dict:
        """The entries whose file is unchanged since the save: {path: ((mtime_ns, size), value)}."""
        base = str(root)
        out = {}
        for path, (stamp, value) in entries.items():
            if stamp_of(f"{base}/{path}") == stamp:
                out[path] = (stamp, value)
        return out
