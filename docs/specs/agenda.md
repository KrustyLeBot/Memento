# Agenda

Part of [the specs](README.md). Specification only; nothing is implemented.

## 1. Goals and non-goals

Goals

- A personal calendar that lives in Markdown files, synced by Synchrotron like the notes, on Android and Windows.
- **One-shot import** of the user's Google and Proton calendars through ICS files (not live links), and ICS export (no lock-in).
- Activities and dated notes show in the calendar; recurring events; reminders; an Android widget with the upcoming events.

Non-goals (v1)

- No server, no CalDAV, no account, no live sync with Google or Proton.
- No invitations: attendees of imported events are kept as text, nobody is invited or answers.
- No free/busy, no shared calendars, no time-zone picker per attendee.
- No replacement of the Android system calendar: other apps do not see these events (see section 9).

## 2. Vocabulary

- **Calendar**: a named, coloured group of events ("Perso", "Travail", "Sport"). Defined in `agenda/calendars.json`.
- **Event**: something with a start, usually an end, optionally a recurrence. One file in `agenda/`.
- **Dated note**: a normal note that has a date; shows in the calendar in the "Notes" layer.
- **Layer**: a source of calendar entries that is not an event file: **Activités** (from `activities/`), **Notes** (dated notes), **Anniversaires** (optional, from contacts).
- **Occurrence**: one instance of a (possibly recurring) event on a given day.

## 3. Data model

### 3.1 An event is a note

An event is a Markdown file `agenda/<slug>-<id6>.md`. The description is the Markdown body (the user sees the normal
editor: checklists, photos, drawings and scans all work, because it is a note). Frontmatter, all flat keys:

```yaml
---
id: k3f9a2
title: Dentiste
created: 2026-11-01T10:12:00+01:00
updated: 2026-11-01T10:12:00+01:00
start: 2026-11-12T14:30        # local wall time; a date alone (2026-11-12) means all-day
end: 2026-11-12T15:15          # optional; all-day: LAST day included (inclusive, human-friendly)
tz: Europe/Paris               # IANA zone of start/end; absent = floating (follows the device zone)
calendar: perso                # id in calendars.json; absent = the default calendar
rrule: FREQ=WEEKLY;BYDAY=TH    # optional, RFC 5545 syntax without the "RRULE:" prefix
exdate: [2026-11-19, 2026-11-26]   # skipped occurrences (dates or local date-times, like start)
alerts: [-15m, -1d]            # reminders: offsets before the start; m, h, d, w ("0m" = at start)
location: 12 rue de la Paix, Paris
status: confirmed              # confirmed | tentative | cancelled (cancelled is hidden)
uid: 0a1b2c@google.com         # original ICS UID, kept for re-imports; generated for events created here
---
Prendre la carte vitale.
```

**Why Markdown files and not `.ics` files as the storage** (decision, the question was "an ICS already contains
everything": it contains the event, but not what this app adds). ICS stays the *interchange* format (import, export),
the storage is one note per event because: the description is a **rich note** (checklists, photos, drawings, scans;
`DESCRIPTION` in ICS is plain text and `ATTACH` is only a URL); one file per event syncs and merges per event, while one
`.ics` per calendar would turn two edits on two devices into a whole-calendar conflict; the same files serve notes,
events and activities (links by id, one search, one editor). To make the format **lossless**, any ICS property the app
does not model is kept verbatim as raw lines in `ical_extra` and written back on export (so an imported event exports
back with everything it came with):

```yaml
ical_extra:
  - "X-GOOGLE-CONFERENCE:https://meet.google.com/abc-defg-hij"
  - "CATEGORIES:Santé"
```

Rules

- `start` decides what the file is: **a note with a `start` appears in the calendar.** In `agenda/` it is an event;
  elsewhere (root) it is a dated note (section 3.3).
- All-day end is **inclusive** in the file (a two-day trip: `start: 2026-12-01`, `end: 2026-12-02`) and converted to
  RFC 5545's exclusive `DTEND` on export; importing does the opposite. The user never sees the difference.
- Times are local wall times of `tz`; no UTC `Z` suffix in files, so editing by hand in Obsidian is unsurprising.
  A file without `tz` is floating. Imported UTC events (`...Z`) are converted to the device zone at import and given that zone.
- Date-times in `exdate` follow the format of `start` (date for all-day series).
- Unknown keys stay verbatim (Obsidian compatibility) like for every note.
- Recurring events are **one file**: occurrences are computed, never stored.

### 3.2 Exceptions of a recurring series (modified single occurrence)

An occurrence moved or edited is a **separate event file** with `recurrence_of: <id of the series>` and
`recurrence_at: <original start of the occurrence>`, plus its own `start`/`end`/`title`/... The series gets that
occurrence in its `exdate`, written at the same time. Deleting the exception file restores nothing by itself (the
original occurrence stays excluded): "restore" is an explicit action that removes both.
v1 ships the two edit scopes **"cet événement"** and **"tous les événements"**; **"celui-ci et les suivants"**
(split the series with an `UNTIL` and start a new one) comes later, on request.

### 3.3 Dated notes

Any note may carry:

```yaml
start: 2026-11-20            # date, or date-time (then tz as above)
alerts: [0m]                 # optional
```

It shows in the calendar in the **Notes** layer (note icon, no calendar colour of its own: it takes the "Notes"
layer colour) and keeps living in the notes list. Adding a date: editor menu **"Ajouter à l'agenda"** → date
(+ optional time, + reminder). Removing the date removes the entries, not the note. The existing, still
unimplemented `reminder` field of notes is the same idea: **a note with a `reminder` is treated as dated at that
time**, with an alert at that time (open question 10).

How the calendar relates notes and events: **three ways were considered**

| Option | How | Verdict |
|---|---|---|
| A. A date on a note | Note → calendar | Kept, section 3.3. |
| B. An event links a note | Event has `note: <id>`, shows "Ouvrir la note" | Redundant when the event itself is a note; kept only as an optional `links: [id, id]` (chips on the event) for linking existing notes (later, on request). |
| C. The event *is* a note | Description = note body; everything notes can do, events can do | **Chosen**: no second text editor, attachments and drawings for free, links by id are trivial. |

### 3.4 Calendars (`agenda/calendars.json`, synced settings)

Same pattern as `activities/settings.json`: a small JSON file that syncs with the notes.

```json
{"calendars": [
  {"id": "perso", "name": "Perso", "color": "#4F8BF5", "visible": true},
  {"id": "travail", "name": "Travail", "color": "#E8A317", "visible": true}
 ],
 "default": "perso",
 "layers": {"activities": {"visible": true, "color": "#2E9E5B"}, "notes": {"visible": true, "color": "#9A8CF0"}}}
```

Colours come from a fixed palette of about twelve entries (contrast checked on light and dark themes), not a free
picker. An event whose `calendar` id is unknown is shown in the default calendar's colour (a sync may bring the
event before the settings file).

### 3.5 Time zones

**What the standard says** (RFC 5545, iCalendar, the format behind Google, Proton, Apple and Outlook calendars): a
date-time has **three forms**, and the choice is made per event:

| Form | In an `.ics` | Meaning |
|---|---|---|
| **Local time with a zone** | `DTSTART;TZID=Europe/Paris:20261112T143000` | 14:30 on the wall clocks of Paris, whatever zone you are in. The rules of the zone (daylight saving) apply to every occurrence of a series. |
| **UTC** | `DTSTART:20261112T133000Z` | One absolute instant. |
| **Floating** | `DTSTART:20261112T143000` (no `TZID`, no `Z`) | 14:30 *wherever you are*: "take my pill at 8:00". |
| *All-day* | `DTSTART;VALUE=DATE:20261112` | A calendar day, no zone. |

The standard requires a `TZID` on every date-time that is neither UTC nor floating, and forbids it on UTC values. For
**recurring events** the correct form is local time + zone: "every Thursday 14:30 in Paris" stays at 14:30 across
the daylight-saving change, whereas a UTC series would drift an hour. This is also what Google Calendar does (each
event has its own time zone, optionally a different one for its end) and why the user's guess is right: **an event keeps
its zone**.

**What the app does**

- An event is stored as **local wall time + IANA zone** (`start: 2026-11-12T14:30`, `tz: Europe/Paris`), exactly the
  first form. Series therefore survive daylight saving correctly, and a flight "14:00 Paris" is still 14:00 Paris abroad.
- **Floating** events are an option ("Fuseau: celui de l'appareil"): no `tz` key; they show at the same wall time wherever the phone is.
- **UTC events** (`...Z`, the form some senders use for single meetings) are converted at import to the device
  zone's wall time and stored with that zone (the instant is preserved; the file stays human-readable).
- **Display**: entries are shown in the **device's current zone** (the instant is converted), so after landing in
  New York a 14:30 Paris event shows 08:30; its detail says "14:30 à Paris". A setting "Afficher les heures dans le
  fuseau de l'événement" shows the event's own wall time instead (Google offers the same choice).
- All-day events are zone-free dates and never move.
- **Names**: IANA (`Europe/Paris`). `java.time` on Android; `zoneinfo` + the `tzdata` package on Windows (Windows has no IANA database of its own).
  Outlook-style names (`Romance Standard Time`) and custom `TZID`s with an embedded `VTIMEZONE` are mapped to IANA
  (CLDR table; if the embedded rules are standard, the zone is recognised from them); an unresolvable zone falls
  back to the device zone and the import report says how many events were affected. Export writes a `TZID` with a
  `VTIMEZONE` block for every zone used, which is what strict clients expect.
- **Daylight-saving gaps**: a local time that does not exist (02:30 the night the clocks jump forward) is moved to
  the first valid instant after the gap; a time that exists twice (the night they go back) uses the first one: the
  same rules as `java.time` and Python's `zoneinfo`, and tested.
- A series whose `DTSTART` is excluded by an `EXDATE` (allowed by the standard) is kept as is; `start` stays the series anchor.

### 3.6 Volume and layout

A multi-year Google or Proton calendar can mean thousands of events. Every event is a file, so:

- **Import cut-off** (decision: the user has few events): **everything is imported by default**; the dialog still
  offers "depuis le …" for someone with a long history.
- **Subfolders by year of the start** (`agenda/2026/…`) keep directories small; a recurring series lives in the year of its first occurrence.
- A rebuilt **in-memory index** (sorted by start; recurring series expanded on demand for the visible window plus a margin) keeps every view fast; startup scans the folder with the same watcher as the notes.
- Measure before deciding anything more: import a synthetic 5 000-event calendar on a real phone and time the first scan and the first sync. If it hurts, the fallback is one `.ics` file per calendar per year for **imported past events only** (read-only archive), never for events the user edits.

## 4. Features

### 4.1 Views (Android and Windows)

| View | Content |
|---|---|
| **Agenda** (default) | Upcoming entries grouped by day, endless scroll, "Aujourd'hui" header always present even when empty. |
| **Mois** | Grid, up to three entries per day (coloured bars) and "+N"; tap a day to see its list; entries of other layers have their icon. |
| **Semaine** | 7 columns with an hour grid; all-day entries in a strip on top; current-time line. |
| **Jour** | The same, one column. |

Common: "Aujourd'hui" button, jump to a date, swipe/arrows between periods, a floating "+" (new event on the selected
day), search of events by title/location/description, week starting Monday (setting), 24 h clock following the system.

### 4.2 Event editor

French labels: Titre · Toute la journée · Début / Fin · Fuseau horaire (hidden unless different from the device) ·
Agenda (the calendar) · Lieu (tap opens the maps app) · Répétition · Rappels · Description (the note editor).

**Répétition**: the usual presets, then a full custom editor. Presets: Jamais · Tous les jours · Du lundi au vendredi ·
Toutes les semaines (same weekday) · **Toutes les 2 semaines** · Tous les mois (same day of the month, or "le 2e jeudi")
· Tous les ans · Personnalisé…
**Personnalisé**: every N days / weeks / months / years (N from 1), on which weekdays (weekly), by day of the month or by
"n-th weekday" including "dernier" (monthly), which date (yearly), and the end: **jamais**, **jusqu'au** a date, or **après
N occurrences**. All of these map to a standard `RRULE` (`FREQ`, `INTERVAL`, `BYDAY`, `BYMONTHDAY`, `BYSETPOS`, `UNTIL`,
`COUNT`), so import and export need no translation layer. Anything the editor cannot express but the file contains is
shown as read-only text "Règle importée" and preserved untouched. The list of the next occurrences is shown under the rule
("Prochaines : 12 nov., 26 nov., 10 déc.") so a wrong rule is obvious before saving.

**Rappels**: list of offsets (5 min, 10 min, 15 min, 30 min, 1 h, 1 day, 1 week, custom), several allowed.

Delete asks the scope for series, then offers undo (same snackbar pattern as notes).

### 4.3 Activities in the calendar

Read from the activities index (no duplication, no event files):

- Entry per activity at its `start`, lasting `elapsed`; title = the activity note's title; sport emoji; second line
  distance and duration.
- Tap → the existing activity detail screen (map, charts). Not editable from the agenda; long press shows "Ouvrir l'activité".
- Layer **Activités**: toggle, colour; optional filter per sport (reuse the sports list of the activity settings).
- An activity that crosses midnight shows on its start day only.

### 4.4 Dated notes in the calendar

Layer **Notes**: entries from notes with `start` (and notes with `reminder`). Tap opens the note. Time shown if there
is one, otherwise it is an all-day marker. A checklist note with a date shows its progress ("3/5") as the second line.

### 4.5 Birthdays (optional, question 6)

Contacts with a `birthday` appear every year, all-day, "Anniversaire de <prénom nom>", layer **Anniversaires**,
computed (no files). Tap opens the contact. If the year of birth is known, "(34 ans)".

### 4.6 Reminders (Android)

- Notification channel **"Rappels d'agenda"** (importance high, sound), separate from everything else so the user can
  tune or mute it in the system settings; the app's Settings has a shortcut to that channel's page (the same pattern as Synchrotron).
- Notification: title, time and place, **"Reporter 10 min"** action, tap opens the event.
- Scheduling uses `AlarmManager.setExactAndAllowWhileIdle`. Exact alarms need the "Alarmes et rappels" special
  access on Android 12+ (`SCHEDULE_EXACT_ALARM`); the app asks once with a short explanation and a settings shortcut,
  and falls back to inexact alarms (`setAndAllowWhileIdle`, a few minutes of drift) if refused. *To verify on the device.*
- **Rolling horizon**: only the alarms of the next 48 hours are registered (never one per occurrence of a series).
  A periodic job (every 6 hours) and every data change (edit, sync, import) recompute the window.
- Rebuilt after reboot (`BOOT_COMPLETED`), time or zone change (`TIME_SET`, `TIMEZONE_CHANGED`) and app update.
- Needs `POST_NOTIFICATIONS` on Android 13+ (the app already asks for it for other features).
- Shown reminders are not duplicated when a sync brings the same event again (alarm key = event id + occurrence + offset).

Windows: no reminders in v1 (the app is not resident). A tray mode could add them later.

## 5. Import

### 5.1 What is imported

Input: one or several `.ics` files, or a `.zip` of them (Google's export is a zip with one `.ics` per calendar: the
user picks the zip directly). Each `.ics` becomes one calendar named after the file (editable in the preview).

Mapping of the ICS content (RFC 5545):

| ICS | Becomes |
|---|---|
| `SUMMARY` | `title` |
| `DTSTART`/`DTEND`/`DURATION` (with `TZID`, `Z` or `VALUE=DATE`) | `start`, `end`, `tz`; date values = all-day; exclusive all-day `DTEND` → inclusive `end` |
| `RRULE`, `EXDATE`, `RDATE` | `rrule`, `exdate`; `RDATE` (rare) is expanded into extra single events |
| `RECURRENCE-ID` overrides | exception event files (3.2) |
| `STATUS:CANCELLED` | not imported (or, for an override, recorded as an `exdate` of its series) |
| `DESCRIPTION` | note body (plain text; HTML descriptions are converted, links kept) |
| `LOCATION` | `location` |
| `VALARM` (`TRIGGER` relative to start) | `alerts`; absolute triggers and `ACTION:EMAIL` are ignored |
| `UID` | `uid`: the re-import key |
| `ATTENDEE`, `ORGANIZER` | appended to the body under "Participants :" as text; no logic |
| Conference links (Google Meet / Zoom in `X-GOOGLE-CONFERENCE`, `URL`, description) | a link line at the top of the body |
| `CATEGORIES`, `COLOR`, `ATTACH`, `X-*`, `TRANSP`, `CLASS` | ignored (counted in the report) |

Google-specific and Proton-specific extensions are ignored unless listed; unknown never breaks the import.
*To verify with real exports:* whether Google's export carries `VALARM`s, and how Proton marks private or
password-shared events. The import must be tested on real exports **from the user's phone/desktop (not committed to the public repository)**,
with synthetic files of the same shape in the tests.

### 5.2 The import dialog (Android and Windows)

1. Pick the file(s) (system picker; also reachable by opening an `.ics` attachment from a mail or the file manager: the app registers for `text/calendar`).
2. **Preview**, no write yet: per calendar, count of events, series, date range, number of ignored items and of
   zones that could not be resolved.
3. Options: target calendar (new or existing, colour), optional **"depuis le …"** cut-off (default: everything), duplicates policy.
4. Import with a progress bar (events are written in batches; the UI stays usable); report at the end ("812 événements, 37 séries, 3 ignorés").
5. **Undo** the whole import in one action ("Annuler l'import"): every file created by an import carries `import: <import-id>` in its frontmatter, so removal is exact.

**Duplicates** (matching by `uid` among existing events of the target calendar): *skip*, *update the existing event*
(default for a re-import: the file's content wins, the description edited in the app is preserved in the body unless
the ICS description changed), or *keep both*.

### 5.3 Robustness

- Streaming parse (calendars of 20–50 MB exist): events are handled one by one, never the whole file as a string.
- Line unfolding, escaped characters, `\n` in text, UTF-8 and the odd Latin-1 file are all handled by the library.
- A malformed event is skipped and counted; the rest imports.
- Recurrence safety: unbounded series are fine (expanded lazily); an `RRULE` that expands to more than 10 000 occurrences in the visible window is cut and reported.
- Library: **biweekly** on Android (plain Java, ICS parse and recurrence iteration), **icalendar** + **recurring-ical-events** on Windows.
  Shared vectors (the same `.ics` → the same list of occurrences over a date range) keep both sides honest.

## 6. Export

- Per calendar: **"Exporter en .ics"**; all calendars: a `.zip` of `.ics`. The Android share sheet or save dialog; Windows save dialog.
- The inverse mapping of 5.1 (inclusive all-day end → exclusive, zone as `TZID` with a `VTIMEZONE`, alerts as `VALARM`, `uid` kept).
- Activities and dated notes are exportable as events too (option "Inclure les activités et les notes datées"); contacts' birthdays are not.
- A round trip test: export, re-import into an empty calendar, same occurrences.

## 7. Android widget

Reference: the screenshot the user sent (a dark agenda widget on the home screen, next to the Cerbère widget). What it
shows, which the spec follows:

- A **header** on the top left: the weekday in small text ("dimanche") above the full date in large text ("1 novembre"); on
  the top right two icons: **refresh** (circular arrows) and **+** (new event).
- Under it, a **list of the next entries**, one row each, generous spacing:
  - a **date block** on the left: abbreviated weekday over the day number ("mer." / "11"), the number large;
  - a **thin vertical bar** in the calendar's colour;
  - the **title** (bold, large; up to two lines) and under it, in a dimmer colour, **the time range** ("20:00 - 20:30") or
    "Toute la journée".
- A dark rounded card (the system's dark theme; a light variant in the light theme), no frame, no day separators.

```
┌───────────────────────────────────────┐
│ dimanche                    ⟳    +    │
│ 1 novembre                            │
│                                       │
│  dim.  ▌ Faire virements IBKR 4000€   │
│   1    ▌ Toute la journée             │
│                                       │
│  mer.  ▌ Sortir poubelles…            │
│  11    ▌ 20:00 - 20:30                │
│  …                                    │
└───────────────────────────────────────┘
```

Behaviour

- **Rows**: upcoming entries from today on (events, activities, dated notes, according to the configuration), sorted by
  start. When several entries fall on the same day, the date block appears on the first row of that day only (Google
  Calendar's habit); the others keep the bar and leave the block empty. A series shows its next occurrence(s).
- **Colour bar**: the calendar's colour; activities and notes use their layer's colour and a small icon (sport emoji, note) before the title.
- **Time line**: `HH:mm - HH:mm`, `Toute la journée`, or `Du 1 au 3 déc.` for multi-day entries; the start only when there is no end.
- **Tap** a row → the event, activity or note. **Tap the date** (header) → the agenda on today. **+** → new event on today. **⟳**
  → rescans the folder and asks Synchrotron to sync the folder (the same "sync on open" call the apps already use),
  then redraws; a spinner replaces the icon meanwhile.
- **Sizes**: resizable (about 2×2 up to 4×6); the number of rows follows the height, the list scrolls inside the widget.
- **Empty state**: "Rien de prévu" with the **+** still available.
- **Configuration screen** (when the widget is added, editable later): calendars and layers shown, how many days ahead
  (7 / 14 / 30 / 90), show today's finished entries (default off), show the refresh button, background opacity.
- **Theme**: follows the system dark/light; text sizes follow the widget size; the suite's palette for the accents.
- **Updates**: after any change made in the app or brought by a sync (the app tells the widget), at midnight, on the
  date change, and every 30 minutes as a safety net. The widget never reads the files itself: it asks the app's index.
- **Implementation**: `AppWidgetProvider` with a `RemoteViews` collection (a `RemoteViewsService`), like the existing Cerbère widget; no new UI toolkit.
  Glance (Compose for widgets) is the alternative; decide at implementation by comparing APK size and effort.

**Second widget (same delivery, small)**: **"Aujourd'hui"**, 2×1: only today's next entry and the count of the others,
for the places where the list is too big. A **Mois** widget (grid with dots) can come later.

## 8. Windows UI

Same views (Agenda, Mois, Semaine, Jour) in Qt; the editor is the note editor plus the event fields; the import
dialog is the same flow with a file dialog. No reminders, no widget. Read/write of the same files, watched for
changes by the existing watcher.

## 9. Relation with Android's system calendar

The app's events are **not** in the system calendar provider, so other apps (assistants, car, watch) do not see them.
This is a deliberate non-goal of v1: the Calendar Provider needs an account and a sync adapter just like contacts,
and a calendar the user cannot edit anywhere else than in one app is simpler to reason about. If wanted later: a
one-way "Afficher dans le calendrier du téléphone" mirror, same design as the contacts mirror
([contacts.md](contacts.md), section 5), with the same caveats.

## 10. Test plan (when implemented)

- ICS vectors (synthetic, shaped like Google, Proton and Outlook exports): single, all-day, multi-day, weekly with
  `BYDAY`, monthly `BYSETPOS`, `COUNT` and `UNTIL`, `EXDATE`, override with `RECURRENCE-ID`, DST changes (the
  Sunday of the clock change at 02:30), `TZID` unknown, Windows zone names, UTC times, folded lines, HTML description.
- Same vectors, same expected occurrences on Kotlin and Python.
- Round trip export → import.
- Import of 5 000 events: duration, memory, first scan, first sync (on the phone).
- Reminders: scheduling window, reboot, zone change, snooze, exact-alarm refusal fallback (on the phone).
- Widget: empty, one day, many days, all-day events, 24 h vs 12 h, dark and light.
