"""The heavy part of every activity operation: reading a FIT/GPX file and analysing it. Nothing here touches the
data folder's notes or takes the data lock; the module (modules/activities.py) commits the result under the lock.

A long track (tens of thousands of points) runs in a short-lived child process (`python -m memento_web.analyse`):
its 200 000 point objects then never enter the server's heap, which a long-running process does not hand back (a
60-hour race peaked the server at 300 MB and left it at 250 MB). A small one runs in the calling thread.

Each operation returns (header: dict, body: bytes). In a child, both go to stdout: the header as one JSON line, then
the body. A failure is a TrackError: `missing` (the file is not there, 404) or not (unreadable, 422).
"""
from __future__ import annotations

import json
import os
import subprocess
import sys
import threading
from pathlib import Path

from .core import Sport

PACKAGE_PARENT = Path(__file__).resolve().parents[1]
EXIT_UNREADABLE, EXIT_MISSING = 3, 4
# One child at a time: each peaks near 170 MB on a 200 000-point race, and the warm-up, an import and an opened
# activity may all want one at once on a NAS with little RAM.
CHILDREN = threading.BoundedSemaphore(1)


class TrackError(Exception):
    def __init__(self, message: str, missing: bool = False):
        super().__init__(message)
        self.message, self.missing = message, missing


def _sport(key: str) -> Sport:
    found = next((s for s in Sport if s.key == key), None)
    if found is None:
        raise TrackError(f"Sport inconnu : {key}")
    return found


def _info(raw: str):
    from memento_win.core.activity import ActivityInfo
    info = ActivityInfo.parse(raw)
    if info is None:
        raise TrackError("Résumé d'activité illisible")
    return info


def _read_source(path: Path):
    from .core import Store
    try:
        return Store(path.parent).read_source(path)
    except OSError as error:
        raise TrackError(str(error), missing=not path.exists()) from None
    except Exception as error:  # noqa: BLE001 — told to the user
        raise TrackError(f"fichier illisible ({error})") from None


def _read_track(root: Path, info):
    from .core import Store
    try:
        return Store(root).read_track(info)
    except OSError:  # its message holds the server's path: not for the page
        raise TrackError("Fichier du tracé introuvable (pas encore synchronisé ?)", missing=True) from None
    except Exception as error:  # noqa: BLE001 — told to the user
        raise TrackError(f"Tracé illisible : {error}") from None


# ---------------------------------------------------------------- the operations (pure: arguments are strings)


def inspect(source: str) -> tuple[dict, bytes]:
    """What the import dialog shows of an uploaded file."""
    from memento_win.core import elevation
    track, _ext = _read_source(Path(source))
    return {"empty": not track.points and track.session_distance is None, "start": track.start_time,
            "fit_sport": track.fit_sport, "name": track.name, "points": len(track.points),
            "needs_altitude": elevation.missing(track)}, b""


def prepare(source: str, sport: str) -> tuple[dict, bytes]:
    """An import's summary and analysis, before anything is written: the summary's `file` is set by the caller."""
    from memento_win.core import elevation
    from memento_win.core.activity import ActivityInfo
    from memento_win.core.analysis import analyze
    from .modules.activities import analysis_json, dumps
    track, ext = _read_source(Path(source))
    if not track.points and track.session_distance is None:
        raise TrackError("Aucune position dans ce fichier")
    s = _sport(sport)
    a = analyze(track, s)
    info = ActivityInfo.from_analysis(s, a, track, None)
    return ({"ext": ext, "start": track.start_time, "info": info.to_json(), "needs_altitude": elevation.missing(track)},
            dumps(analysis_json(a, s, None)))


def analyse(root: str, info: str) -> tuple[dict, bytes]:
    """The analysis JSON of an activity as it is."""
    from memento_win.core.analysis import analyze
    from .modules.activities import analysis_json, dumps
    i = _info(info)
    return {}, dumps(analysis_json(analyze(_read_track(Path(root), i), i.sport), i.sport, i.alt))


def resummarise(root: str, info: str, sport: str, alt: str) -> tuple[dict, bytes]:
    """The summary and analysis an activity gets with another sport or a new altitude file ('' = none)."""
    from memento_win.core.activity import ActivityInfo
    from memento_win.core.analysis import analyze
    from .modules.activities import analysis_json, dumps
    i = _info(info)
    s = _sport(sport)
    i = ActivityInfo(**{**i.__dict__, "alt": alt or None})
    track = _read_track(Path(root), i)
    a = analyze(track, s)
    new = ActivityInfo.from_analysis(s, a, track, i.file, i.alt, untimed_start=i.start)
    return {"info": new.to_json()}, dumps(analysis_json(a, s, i.alt))


def altitude_samples(root: str, info: str) -> tuple[dict, bytes]:
    """The positions to send to the terrain service, for a file without any altitude (`samples` empty otherwise)."""
    from memento_win.core import elevation
    from memento_win.core.activity import ActivityInfo
    from memento_win.core.analysis import clean
    i = _info(info)
    track = _read_track(Path(root), ActivityInfo(**{**i.__dict__, "alt": None}))
    if not elevation.missing(track):
        return {"samples": []}, b""
    return {"samples": [[p.time, p.lat, p.lon] for p in elevation.pick(clean(track))]}, b""


OPERATIONS = {"inspect": inspect, "prepare": prepare, "analyse": analyse, "resummarise": resummarise,
              "altitude-samples": altitude_samples}


# ---------------------------------------------------------------- running one


def run(operation: str, *args: str, apart: bool, timeout: float) -> tuple[dict, bytes]:
    """The operation in this thread, or in a child process when `apart` (a long track)."""
    if not apart:
        return OPERATIONS[operation](*args)
    env = {**os.environ, "PYTHONPATH": os.pathsep.join(filter(None, [str(PACKAGE_PARENT), os.environ.get("PYTHONPATH")]))}
    try:
        with CHILDREN:
            done = subprocess.run([sys.executable, "-m", "memento_web.analyse", operation, *args],
                                  capture_output=True, env=env, timeout=timeout)
    except subprocess.TimeoutExpired:
        raise TrackError("Lecture du tracé trop longue") from None
    if done.returncode != 0:
        lines = done.stderr.decode("utf-8", "replace").strip().splitlines()
        if done.returncode not in (EXIT_UNREADABLE, EXIT_MISSING):
            print(f"memento-web: track process failed ({done.returncode}): "
                  f"{done.stderr.decode('utf-8', 'replace')[-2000:]}", flush=True)
        if done.returncode < 0:  # killed: the container's mem_limit is the usual reason
            raise TrackError("Lecture du tracé interrompue (mémoire du conteneur insuffisante ?)")
        raise TrackError(lines[-1] if lines else f"code {done.returncode}", missing=done.returncode == EXIT_MISSING)
    header, _, body = done.stdout.partition(b"\n")
    return json.loads(header), body


def main(argv: list[str]) -> int:
    if not argv or argv[0] not in OPERATIONS:
        print(f"usage: python -m memento_web.analyse {{{'|'.join(OPERATIONS)}}} <arguments>", file=sys.stderr)
        return 2
    try:
        header, body = OPERATIONS[argv[0]](*argv[1:])
    except TrackError as error:
        print(error.message, file=sys.stderr)
        return EXIT_MISSING if error.missing else EXIT_UNREADABLE
    out = sys.stdout.buffer
    out.write(json.dumps(header, separators=(",", ":")).encode() + b"\n")
    out.write(body)
    out.flush()
    return 0
