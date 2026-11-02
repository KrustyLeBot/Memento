"""The snackbar's "Annuler": before a destructive action a module stashes the files it is about to delete or rewrite
(and lists the ones it is about to create); `POST /api/undo/<token>` puts the folder back as it was. Stashes live in
/config/undo, never in the synced folder, and expire after TTL.

    token = data.undo.stash(root, before=["note.md", "attachments/x.pdf"], created=[])
    ... delete the files ...
    return {"undo": token}
"""
from __future__ import annotations

import json
import secrets
import shutil
import threading
import time
from pathlib import Path

from .core import atomic
from .errors import ApiError
from .files import resolve, unlink_quiet

TTL = 15 * 60


class Undo:
    def __init__(self, directory: Path, clock=time.time):
        self.directory = directory
        self.clock = clock
        self._lock = threading.Lock()

    def stash(self, root: Path, before: list[str], created: list[str] | None = None) -> str:
        """Copies each existing file of `before`; `created` are deleted again on undo."""
        self.purge()
        token = secrets.token_urlsafe(16)
        folder = self.directory / token
        folder.mkdir(parents=True)
        saved = []
        for i, relative in enumerate(dict.fromkeys(before)):
            source = resolve(root, relative)
            if source.is_file():
                shutil.copyfile(source, folder / str(i))
                saved.append({"path": relative, "copy": str(i)})
        manifest = {"time": self.clock(), "saved": saved, "created": list(created or [])}
        (folder / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
        return token

    def restore(self, root: Path, token: str) -> list[str]:
        if not token.replace("-", "").replace("_", "").isalnum() or len(token) > 64:
            raise ApiError(400, "Jeton d'annulation invalide")
        folder = self.directory / token
        with self._lock:
            try:
                manifest = json.loads((folder / "manifest.json").read_text(encoding="utf-8"))
            except FileNotFoundError:
                raise ApiError(410, "Trop tard pour annuler") from None
            if self.clock() - manifest["time"] > TTL:
                shutil.rmtree(folder, ignore_errors=True)
                raise ApiError(410, "Trop tard pour annuler")
            for relative in manifest["created"]:
                unlink_quiet(resolve(root, relative))
            restored = []
            for item in manifest["saved"]:
                atomic.write_bytes(resolve(root, item["path"]), (folder / item["copy"]).read_bytes())
                restored.append(item["path"])
            shutil.rmtree(folder)
        return restored

    def purge(self) -> None:
        if not self.directory.is_dir():
            return
        now = self.clock()
        for folder in self.directory.iterdir():
            try:
                manifest = json.loads((folder / "manifest.json").read_text(encoding="utf-8"))
                expired = now - manifest["time"] > TTL
            except (OSError, ValueError, KeyError):
                expired = True
            if expired:
                shutil.rmtree(folder, ignore_errors=True)
