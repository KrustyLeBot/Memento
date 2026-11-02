"""Agenda data layer (no Qt): events as notes, calendars, recurrence, the dated-entries index, alarms,
ICS import and export. Same formats and algorithms as the Android package `app.scriptorium.agenda`,
kept honest by the shared vectors in `testdata/agenda/` at the repository root.

Modules:
- `model`: `Event`, stamps (`2026-11-12` / `2026-11-12T14:30`), alerts, event <-> note frontmatter.
- `calendars`: `agenda/calendars.json` (calendars, layers, palette).
- `zones`: IANA zone resolution (Windows names, embedded VTIMEZONE), wall time -> instant, VTIMEZONE output.
- `recurrence`: occurrences of an event in a window.
- `entries`: the dated-entries index (events, dated notes, activities, birthdays) and the alarms of the next 48 h.
- `store`: `agenda/` on a file-system interface (`AgendaFs`): scan, save, delete, exceptions of a series.
- `ics`: content lines, text escaping, HTML -> text (shared by import and export).
- `ics_import`, `ics_export`: the interchange format.
"""
