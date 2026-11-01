from __future__ import annotations

import hashlib
import os
import re
import shutil
import subprocess
import sys
import tempfile
from dataclasses import dataclass
from typing import Callable

import requests

from .. import _version

REPO_URL = "https://github.com/KrustyLeBot/Griffonote/releases/tag/latest"
API = "https://api.github.com/repos/KrustyLeBot/Griffonote/releases/tags/latest"
_UA = f"Scriptorium-Windows/{_version.BUILD} (https://github.com/KrustyLeBot/Griffonote)"


@dataclass
class Release:
    build: int
    url: str
    size: int = 0
    sha256: str = ""


def current_build() -> int:
    return int(_version.BUILD)


def running_exe() -> str | None:
    """Path of the running .exe, or None when running from source."""
    return sys.executable if getattr(sys, "frozen", False) else None


def parse_release(data: dict) -> Release | None:
    """The release title ends with the build number; the Windows asset is the .exe (GitHub gives its SHA-256)."""
    match = re.search(r"(\d+)\s*$", data.get("name") or "")
    if not match:
        return None
    for asset in data.get("assets", []):
        if asset.get("name", "").lower().endswith(".exe"):
            digest = str(asset.get("digest") or "")
            return Release(int(match[1]), asset["browser_download_url"], int(asset.get("size") or 0),
                           digest.removeprefix("sha256:").lower() if digest.startswith("sha256:") else "")
    return None


def latest() -> Release | None:
    response = requests.get(API, headers={"Accept": "application/vnd.github+json", "User-Agent": _UA}, timeout=15)
    if response.status_code == 404:
        return None  # e.g. while the CI is re-publishing the release
    response.raise_for_status()
    return parse_release(response.json())


def is_newer(release: Release) -> bool:
    return release.build > current_build()


def download(release: Release, progress: Callable[[int, int], None] | None = None) -> str:
    """Downloads the new executable to a temp file and verifies it; returns its path."""
    target = os.path.join(tempfile.gettempdir(), f"Scriptorium-{release.build}.exe.download")
    digest, done = hashlib.sha256(), 0
    with requests.get(release.url, headers={"User-Agent": _UA}, stream=True, timeout=60) as response, \
            open(target, "wb") as out:
        response.raise_for_status()
        total = int(response.headers.get("Content-Length") or release.size or 0)
        for chunk in response.iter_content(256 * 1024):
            out.write(chunk)
            digest.update(chunk)
            done += len(chunk)
            if progress:
                progress(done, total)
    # A truncated, stale (CDN cache) or tampered download must never replace a working executable.
    if release.size and done != release.size:
        os.remove(target)
        raise OSError(f"Téléchargement incomplet ({done} octets sur {release.size})")
    if release.sha256 and digest.hexdigest() != release.sha256:
        os.remove(target)
        raise OSError("Le fichier téléchargé ne correspond pas à l'empreinte SHA-256 publiée")
    return target


def install(current_exe: str, new_file: str) -> str:
    """Puts new_file in place of current_exe, which may be running."""
    # The download lives in the temp folder, possibly on another drive than the exe (a rename cannot
    # cross drives): stage a copy next to the exe first, so the final swap is a same-folder rename.
    staged = current_exe + ".new"
    shutil.copyfile(new_file, staged)
    old = current_exe + ".old"
    try:
        if os.path.exists(old):
            os.remove(old)
        os.replace(current_exe, old)
    except OSError:
        os.remove(staged)
        raise
    try:
        os.replace(staged, current_exe)
    except OSError:
        os.replace(old, current_exe)  # put the working version back
        os.remove(staged)
        raise
    try:
        os.remove(new_file)
    except OSError:
        pass
    return current_exe


def relaunch(exe: str) -> None:
    # A --onefile child would otherwise inherit the parent's bootloader variables and load from its temp dir.
    env = {k: v for k, v in os.environ.items() if not k.startswith(("_PYI", "_MEIPASS"))}
    env["PYINSTALLER_RESET_ENVIRONMENT"] = "1"
    flags = getattr(subprocess, "DETACHED_PROCESS", 0) | getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0)
    subprocess.Popen([exe], creationflags=flags, close_fds=True, cwd=os.path.dirname(exe), env=env)


def cleanup_previous(exe: str | None = None) -> bool:
    """Removes the executable left behind by the last self-update; True once nothing is left."""
    exe = exe or running_exe()
    old = exe + ".old" if exe else ""
    if not old or not os.path.exists(old):
        return True
    try:
        os.remove(old)
        return True
    except OSError:
        return False
