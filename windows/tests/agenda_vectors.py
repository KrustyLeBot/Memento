"""The shared agenda vectors (`testdata/agenda/` at the repository root): import a `.ics` into memory and list
its occurrences over the fixed window, in the JSON shape of `testdata/agenda/expected/*.json` (the Android
tests compute the same shape). Run as a script to print the result of one vector:

    python -m tests.agenda_vectors google_perso
"""
from __future__ import annotations

import json
import sys
from datetime import datetime, timezone
from pathlib import Path

from memento_win.core import note as fmt
from memento_win.core.agenda.calendars import CalendarSettings
from memento_win.core.agenda.entries import AgendaSources, build_entries
from memento_win.core.agenda.ics_import import IcsSource, ImportOptions, apply_import, preview, read_sources
from memento_win.core.agenda.store import AgendaStore, MemoryFs

VECTORS = Path(__file__).resolve().parents[2] / "testdata" / "agenda"
DEVICE_ZONE = "Europe/Paris"
WINDOW = (datetime(2026, 1, 1, tzinfo=timezone.utc), datetime(2027, 1, 1, tzinfo=timezone.utc))
NOW = 1_793_520_000_000  # 2026-11-01T09:20:00Z
IMPORT_ID = "20261101T092000-vector"


def iso(instant: datetime) -> str:
    return instant.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def occurrences(store: AgendaStore) -> list[dict]:
    scan = store.scan()
    assert not scan.problems, scan.problems
    result = build_entries(AgendaSources(events=scan.events, settings=store.calendars()), WINDOW[0], WINDOW[1], DEVICE_ZONE)
    assert not result.problems, result.problems
    out = []
    for e in result.entries:
        if e.all_day:
            out.append({"title": e.title, "all_day": True, "start": e.start_date.isoformat(), "end": e.end_date.isoformat()})
        else:
            out.append({"title": e.title, "all_day": False, "start": iso(e.start), "end": iso(e.end)})
    return out


def import_vector(name: str, fs: MemoryFs | None = None):
    fs = fs or MemoryFs()
    store = AgendaStore(fs)
    parsed = read_sources([IcsSource.from_path(VECTORS / f"{name}.ics")], DEVICE_ZONE, NOW)
    settings = store.calendars()
    settings.set_default_alerts(False, [])  # no default reminders: the vectors check the ICS mapping alone
    previews = preview(parsed, [], settings)
    report = apply_import(store, parsed, ImportOptions(), [], settings, DEVICE_ZONE, IMPORT_ID, NOW)
    return store, previews, report


def compute(name: str) -> dict:
    store, previews, report = import_vector(name)
    return {
        "preview": [{
            "name": p.name, "events": p.events, "series": p.series, "exceptions": p.exceptions,
            "cancelled": p.cancelled, "ignored": p.ignored, "unresolved_zones": p.unresolved_zones,
            "first": p.first.isoformat() if p.first else None, "last": p.last.isoformat() if p.last else None,
            "errors": len(p.errors),
        } for p in previews],
        "created": report.created,
        "occurrences": occurrences(store),
    }


def files_of(store: AgendaStore) -> dict[str, str]:
    """title -> file text, for the assertions on file contents."""
    out = {}
    for path in store.paths():
        text = store.fs.read_text(path)
        out[fmt.parse(path, text, 0).title] = text
    return out


if __name__ == "__main__":
    print(json.dumps(compute(sys.argv[1]), ensure_ascii=False, indent=2))
    if len(sys.argv) > 2:
        for title, text in files_of(import_vector(sys.argv[1])[0]).items():
            print("=====", title)
            print(text)
