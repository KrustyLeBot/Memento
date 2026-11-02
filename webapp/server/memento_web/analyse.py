"""`python -m memento_web.analyse <data folder> <activity JSON>`: the analysis JSON of one activity on stdout.

The server runs a long track's analysis in this short-lived process: parsing tens of thousands of points scatters
small objects over the heap, and a long-running process never gets all of that memory back (measured: about
+20 MB kept after opening 20 long tracks). A child that exits returns every byte. On failure the last stderr line
is the message for the user, and the exit status is 3 for an unreadable file, 4 for a missing one.
"""
from __future__ import annotations

import sys
from pathlib import Path


def main(argv: list[str]) -> int:
    from .core import Store
    from .modules.activities import analysis_body
    from memento_win.core.activity import ActivityInfo

    root, raw = Path(argv[0]), argv[1]
    info = ActivityInfo.parse(raw)
    if info is None:
        print("Résumé d'activité illisible", file=sys.stderr)
        return 2
    try:
        track = Store(root).read_track(info)
    except OSError as error:
        print(f"Fichier du tracé introuvable : {error}", file=sys.stderr)
        return 4
    except Exception as error:  # noqa: BLE001 — told to the user by the server
        print(f"Tracé illisible : {error}", file=sys.stderr)
        return 3
    sys.stdout.buffer.write(analysis_body(track, info))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
