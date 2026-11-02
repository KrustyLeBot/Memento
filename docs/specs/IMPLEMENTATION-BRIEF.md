# Implementation brief: Contacts, Agenda and the "everything in Markdown" app (Griffonote)

> **Status (2026-11-02): implemented.** The app has since been renamed **Scriptorium** (applicationId `app.scriptorium`, folder `Documents/Scriptorium`, burgundy accent, `Scriptorium.exe`). The text below is the original brief and keeps the old name.

Snapshot of 2026-11-01. **Self-contained**: it carries the context of the project, the decisions already taken and the
full specifications. A fresh conversation can implement from this file alone (plus the repository).

**Task:** implement everything in Parts A to D **in one delivery** (contacts, agenda, reminders, widgets, the contacts
mirror, the Windows UI, the docs). Nothing of it exists yet. The specifications are reviewed and approved by the
owner; the few remaining open points have a default (Part A, section 6).

Repository: `github.com/KrustyLeBot/Griffonote` (public), branch `main`. Specs live in `docs/specs/` (this brief is the same content in one file).

## 0. Working agreements (from the owner, apply them as they are)

- **Language.** Talk to the owner in **French**, concise, say plainly what is verified and what is not. **Code, comments,
  README and docs in English; every UI string in French.**
- **Work directly on `main`**, no feature branches, no pull requests for this repo. Commit messages in English.
- **Every push to `main` builds and publishes both apps** (APK + Windows exe) through GitHub Actions; after each push,
  check that the CI is green (poll the run by its **run id**, not a short SHA), and tell the owner when something is ready to
  test. A docs-only commit should say `[skip ci]` in its message so no build is published for nothing.
- **Never commit a keystore, never print a secret.** The signing key never changes (a different key makes in-place
  updates impossible). One GitHub environment, `release`, holds the signing secrets; do not create another.
- **The repository is public: tests use synthetic data only** (a real GPS track reveals where someone lives; real contacts and calendars are worse).
- The apps **do not have a "set up Synchrotron" button**: they only ask Synchrotron to sync when they open. The folder to sync is added inside Synchrotron itself.
- The Android compile of the Compose/Android code can only be done by the CI in the environments used so far (no Android SDK
  locally): **keep logic in pure Kotlin files** (no `android.*` imports) so it is unit-testable on the JVM (`./gradlew testDebugUnitTest` runs in CI), and keep Android glue thin.
  Windows tests run with `QT_QPA_PLATFORM=offscreen`.
- **A Windows test lesson**: do not use timestamps near 1970 in tests (Windows cannot format them); use realistic dates.
- Ask the owner when a decision is really theirs; otherwise pick the conventional option, say so, and go on.

## 1. The owner's apps and how they are built

The owner builds a **personal suite of apps**, all sharing one look and one update flow:

| App | What |
|---|---|
| **Synchrotron** (Android) | The sync engine. Syncs chosen folders with a backend (Proton Drive, or the owner's self-hosted server over WebDAV). Other apps ask it for a sync when they open (`sync/SynchrotronClient.kt` is a copy of its client library). Its notifications have separate channels so the owner can silence the permanent one. |
| **Griffonote** (Android + Windows) | The app this brief extends: notes in Markdown files (Keep-like), scans, drawings, attachments, activities (Strava-like). |
| **Cerbère** (Android) | Controls a Somfy alarm; has a home-screen widget (RemoteViews) and Android Auto tiles that fire webhooks. |
| **Ecoutify**, **Météorite** | Music and weather apps. |
| others | Small private tools. |

Common rules of the suite:

- **Look**: a fixed palette per app (Griffonote is amber), shared rounded shapes, dark and light themes; the **suite kit**
  (`suite/` package: `SuiteUi.kt`, `SuiteUpdater.kt`, `SuiteUpdates.kt`, copied from Synchrotron) gives the top bar, banners, settings cards, confirm dialog and the update flow. Reuse it; do not invent new chrome.
- **CI/CD**: one workflow per repository (`.github/workflows/build-apk.yml`, **do not rename it**: GitHub numbers the
  runs per workflow file and the run number is the app's build number). On push to `main`: Android job (unit tests +
  signed release APK) and Windows job (pytest + PyInstaller one-file exe) in parallel, then a release job that
  recreates the rolling GitHub release `latest`, titled "Griffonote build N", carrying `griffonote.apk` and `Griffonote.exe`.
  `versionCode` = the run number, `versionName` = `0.1.<N>`; the Windows build number is stamped into
  `windows/griffonote_win/_version.py`. Pull requests only build with the debug key and publish nothing.
- **Updates**: each app checks the `latest` release on open (a quiet check), shows a banner, downloads with progress, verifies size and SHA-256, installs (Android: system installer; Windows: the exe swaps itself, renaming the running exe to `.old`).
- **Android versions**: minSdk 26, compileSdk and targetSdk 35. Notifications need `POST_NOTIFICATIONS` on 13+; foreground services declare their type; the owner's phone is a **Samsung** (aggressive background limits: ask for battery-optimisation exemption where something must run in the background).
- **Notification channels**: one channel per purpose, named so the owner can tune or mute each in the system settings, with a shortcut in the app's Settings to the channel page (pattern from Synchrotron).
- **Widgets**: `AppWidgetProvider` + RemoteViews (Cerbère's alarm widget is the example), updated by the app on data changes.
- **Crashes**: Synchrotron writes the last crash to a file and shows it in Settings; do not swallow exceptions silently in new code.

## 2. Griffonote: how it is organised today

**Storage** (the folder `Documents/Griffonote/` by default, the same on Android and Windows, synced by Synchrotron):

```
Griffonote/
├── <slug>-<id6>.md        one file per note: YAML frontmatter + Markdown body
├── attachments/           scans (pdf:), attached files (attachments:), activity tracks (.fit/.gpx/.alt)
├── drawings/              SVG sketches (dessin-<id6>.svg)
└── activities/            one note per activity, kept out of the regular list; activities/settings.json (synced settings)
```

Rules that already hold and must keep holding:

- The **folder is the source of truth**; the in-memory index is rebuilt on launch, on resume and when files change (a `FileObserver` on Android; a watcher on Windows).
- File name fixed at creation (`<slug>-<id6>.md`), never renamed. **Writes are atomic** (`.<name>.synctmp-<uuid>` in the same folder, flushed, renamed over the target; Synchrotron never syncs that pattern). Sync conflicts show up as `*conflict*` copies (the app has a badge and a resolution UI).
- **Unknown frontmatter keys are preserved verbatim** (Obsidian compatibility): `NoteFormat` keeps them in `Note.extra`. The parser reads flat `key: value` lines and `- item` lists; known keys today: `id, title, color, pinned, labels, reminder, pdf, attachments, activity, created, updated`. The `reminder` field exists in the model but reminders are not implemented.
- The user **never sees Markdown on the phone**: the editor is a rich editor (checklists, formatting); Markdown is only the file format.
- Pure Kotlin for format and logic (`NoteFormat.kt`, `activity/*`), so it is tested on the JVM; the same algorithms exist in Python for Windows (`windows/griffonote_win/core/`), kept honest with shared test vectors.

**Android layout** (`app/src/main/java/app/griffonote/`): `Note.kt`, `NoteFormat.kt`, `NoteRepository.kt` (StateFlows of notes, activities, settings; scan, watch, save, delete, conflicts), `Fs.kt` (storage backends: plain folder, SAF), `MainViewModel.kt` (screens and actions), `Settings.kt`, `activity/` (the activities module), `record/` (GPS recording service), `draw/` (drawings), `scan/` (document scanner), `sync/` (Synchrotron client), `suite/` (suite kit), `ui/` (Compose screens: list, editor, settings, activities, activity detail, record, drawing, PDF viewer, permission).
**Windows layout** (`windows/griffonote_win/`): `core/` (note, store, atomic writes, activity, analysis, fit, gpx, settings…), `ui/` (PySide6 views: list, editor, activities, drawing, settings, theme, update toast), `services/`; tests in `windows/tests/`.

**The Activities module is the template for the new modules.** Copy its shape:

- a folder out of the regular list (`activities/`), reached from a **pinned entry** at the top of the notes list;
- an item type = a note with a dedicated frontmatter key (`activity: {JSON}`) that carries what the list needs;
- a **controller** object (`ActivityController`) for import, save, delete, open, with the repository exposing a `StateFlow` of the module's items;
- **synced module settings** as a small JSON file in the module's folder (`activities/settings.json`) so settings follow the data across devices;
- screens: a list screen with totals and filters, a detail screen, an editor/import dialog;
- the same module on **Windows** (`core/activity.py`, `store.py`, `ui/activities_view.py`) with the same file format and the same algorithms;
- tests: pure-Kotlin unit tests and Python tests on **synthetic** data; README section documenting the format.

## 3. Name

**The name is not decided.** The app is called **Griffonote** in the code and everywhere today, and that stays the working name
until the owner picks one. Candidates the owner liked or was offered (just names, nothing is chosen):
Scriptorium (liked), Chimère, Griffon, Almanach, Codex, Parchemin, Palimpseste, Marginalia, Folio, Calepin, Carnet,
Vademecum, Cartulaire, Éphéméride, Mémento, Chronique, Sphinx, Phénix, Atelier, Bivouac, Classeur, Cabinet.

Implementation rule: **only the displayed name will ever change, never the Android `applicationId` (`app.griffonote`)**: a
different id is a different app, with no in-place update from the old one. So put the app's display name, the exe name and
the strings that carry it in **one place each** (Android `app_name` string and launcher label, the Windows exe name in the CI
and the window title, the README title, the update notification text) so renaming later is a few lines, and do not hard-code "Griffonote" in new UI strings.


---

## Part A. Overview and decisions

### 1. Where this fits

Griffonote started as a Keep replacement and now holds notes, drawings, scans, attachments and activities. Every
piece of data is a Markdown file with a YAML frontmatter in one folder synced by Synchrotron, read by the Android
app and the Windows app. Contacts and an agenda are two more kinds of the same thing.

**Decision: one app, one folder, several modules.** The app keeps its code, its sync and its update
channel; each module owns a sub-folder, like activities do today (`activities/`):

```
<root>/
├── *.md             notes (unchanged)
├── attachments/     files, tracks, drawings (unchanged)
├── activities/      activities (unchanged)
├── agenda/          events (new)                      + agenda/calendars.json (synced settings)
└── contacts/        contacts (new)
```

Why one app: shared storage, one sync, one search, links between modules (a note on a contact, an event on an
activity), one codebase per platform. Splitting would only be justified by a module needing very different
permissions or a different lifecycle; the folder layout keeps that door open.

The app name is undecided (see section 3 above); nothing below depends on it. Only the
displayed name should change, never the Android `applicationId` (`app.griffonote`): changing it would make an
unrelated app, with no in-place update.

### 2. Architecture rules (all modules)

1. **Files are the source of truth.** One file per item, `<slug>-<id6>.md`, name fixed at creation, atomic writes
   (`.name.synctmp-uuid`), unknown frontmatter kept verbatim. The existing `NoteFormat` already reads flat
   `key: value` lines and lists and keeps unknown keys, so the new keys below need no new parser.
2. **Derived data is never synced.** Occurrence expansion of recurring events, the date index, the contact search
   index live in memory or in the app's private storage and are rebuilt from the files. Nothing derived goes in the folder.
3. **Same behaviour on both platforms.** Formats, parsing and recurrence logic are specified once; Kotlin and
   Python implement them against shared test vectors (as FIT and the activity analysis already do).
4. **Modules can be hidden.** Settings let the user switch off a module (agenda, contacts, activities) so an
   unused one costs nothing in the UI.
5. **Public repository: tests use synthetic data only.** No real contacts, no real calendar.
6. **No cloud, no account.** Import and export are files; nothing talks to Google or Proton.

### 3. Navigation

Today: the notes list with a pinned "Activités" entry. With four modules the pinned-entry pattern stops scaling.
Proposal (question 7): a bottom bar **Notes · Agenda · Activités · Contacts** on Android, a left rail on Windows,
modules hideable in the settings. The editor, search and settings stay where they are.

### 4. Delivery

**Decision: one delivery, no phase order.** Contacts, agenda, reminders, widgets and the contacts mirror are built together
and shipped as one version of the app (each push to `main` still publishes a build; nothing needs to be released piecemeal).
What the work does have is a **dependency order inside it**, which is how it will be sequenced:

1. Module skeleton: folders, navigation, per-module settings file, module on/off, the shared "dated entries" index.
2. Contacts (list, edit, vCard import/export) and the agenda data layer (events, recurrence, time zones, ICS import/export): they only need (1).
3. The views (agenda, month, week, day, contacts), the editors, activities and dated notes in the calendar.
4. Reminders and the two widgets (need the agenda index).
5. The contacts mirror into the phone's contacts (needs the contacts module; **verified on the real phone first**, see Part C).
6. The Windows UI of both modules, the guides, the README.

Left for later, on request only: a month widget, "this and following" edits of a series, Windows reminders, read-only URL
subscription to an external calendar, a two-way contacts sync.

### 5. Risks

- **Import volume.** A Google or Proton calendar of several years is thousands of events, hence thousands of small
  files for Synchrotron (WebDAV/rclone): slow first sync. Mitigations in Part B (import cut-off date, subfolders per year).
- **Recurrence rules and time zones** are where calendar apps go wrong (DST, exceptions, all-day multi-day events).
  A tested library on each side, plus a battery of real-world-shaped test vectors, is the answer; no hand-written RRULE engine.
- **Android's contacts system** has no "default contacts app" role: see Part C for what is and is not possible.
- **Behaviour on the user's phone** (Samsung) can differ from the documentation: every Android integration point in
  these specs is marked *to verify on the device* and gets a short test checklist.

### 6. Decisions and remaining questions

Decided (answers of the user):

| Topic | Decision |
|---|---|
| One app or several | One app, one folder, modules. |
| Events | An event is a note (Markdown). ICS is the interchange format; properties the app does not model are kept verbatim (`ical_extra`) so nothing is lost on export. |
| Import | Everything is imported by default (the user has few events); an optional "depuis le …" cut-off. |
| Recurrence | The usual: daily, weekdays, weekly, every 2 weeks, monthly, yearly, and a custom editor (interval, weekdays, n-th weekday, end by date or count). |
| Time zones | Standard iCalendar model: an event keeps its zone (local time + IANA zone), floating events are an option, display in the device zone. See Part B, section 3.5. |
| Contacts in the phone | Yes: the mirror into Android's contacts so names show in the dialer and the messages; then Google's contact sync is turned off. |
| Widget | The user's screenshot is the reference (Part B, section 7). |
| Order | No order: everything in one delivery. |
| Contact fields | First name, last name, phones, emails (+ optional address, birthday, notes). No photos. |

Still open (each has a default, in bold, that the specs assume; answer only to change it):

1. **Birthdays** from contacts in the calendar, optional per contact: **yes**.
2. **Navigation**: a bottom bar Notes · Agenda · Activités · Contacts on Android, a left rail on Windows, modules hideable: **yes**.
3. **Windows scope**: views and editing of both modules, no reminders on Windows (the app is not resident): **yes**.
4. **Note reminders** (the `reminder` field reserved since the first version): the same alert mechanism as events, and a note with a reminder shows in the calendar: **yes**.
5. **Parallel use** of Google/Proton calendars after the import: assumed that you switch over; re-importing an updated export is supported by event ID if you ever need it.
6. **Name** of the app: undecided, independent of this work.

---

## Part B. Agenda

### 1. Goals and non-goals

Goals

- A personal calendar that lives in Markdown files, synced by Synchrotron like the notes, on Android and Windows.
- **One-shot import** of the user's Google and Proton calendars through ICS files (not live links), and ICS export (no lock-in).
- Activities and dated notes show in the calendar; recurring events; reminders; an Android widget with the upcoming events.

Non-goals (v1)

- No server, no CalDAV, no account, no live sync with Google or Proton.
- No invitations: attendees of imported events are kept as text, nobody is invited or answers.
- No free/busy, no shared calendars, no time-zone picker per attendee.
- No replacement of the Android system calendar: other apps do not see these events (see section 9).

### 2. Vocabulary

- **Calendar**: a named, coloured group of events ("Perso", "Travail", "Sport"). Defined in `agenda/calendars.json`.
- **Event**: something with a start, usually an end, optionally a recurrence. One file in `agenda/`.
- **Dated note**: a normal note that has a date; shows in the calendar in the "Notes" layer.
- **Layer**: a source of calendar entries that is not an event file: **Activités** (from `activities/`), **Notes** (dated notes), **Anniversaires** (optional, from contacts).
- **Occurrence**: one instance of a (possibly recurring) event on a given day.

### 3. Data model

#### 3.1 An event is a note

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

#### 3.2 Exceptions of a recurring series (modified single occurrence)

An occurrence moved or edited is a **separate event file** with `recurrence_of: <id of the series>` and
`recurrence_at: <original start of the occurrence>`, plus its own `start`/`end`/`title`/... The series gets that
occurrence in its `exdate`, written at the same time. Deleting the exception file restores nothing by itself (the
original occurrence stays excluded): "restore" is an explicit action that removes both.
v1 ships the two edit scopes **"cet événement"** and **"tous les événements"**; **"celui-ci et les suivants"**
(split the series with an `UNTIL` and start a new one) comes later, on request.

#### 3.3 Dated notes

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

#### 3.4 Calendars (`agenda/calendars.json`, synced settings)

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

#### 3.5 Time zones

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

#### 3.6 Volume and layout

A multi-year Google or Proton calendar can mean thousands of events. Every event is a file, so:

- **Import cut-off** (decision: the user has few events): **everything is imported by default**; the dialog still
  offers "depuis le …" for someone with a long history.
- **Subfolders by year of the start** (`agenda/2026/…`) keep directories small; a recurring series lives in the year of its first occurrence.
- A rebuilt **in-memory index** (sorted by start; recurring series expanded on demand for the visible window plus a margin) keeps every view fast; startup scans the folder with the same watcher as the notes.
- Measure before deciding anything more: import a synthetic 5 000-event calendar on a real phone and time the first scan and the first sync. If it hurts, the fallback is one `.ics` file per calendar per year for **imported past events only** (read-only archive), never for events the user edits.

### 4. Features

#### 4.1 Views (Android and Windows)

| View | Content |
|---|---|
| **Agenda** (default) | Upcoming entries grouped by day, endless scroll, "Aujourd'hui" header always present even when empty. |
| **Mois** | Grid, up to three entries per day (coloured bars) and "+N"; tap a day to see its list; entries of other layers have their icon. |
| **Semaine** | 7 columns with an hour grid; all-day entries in a strip on top; current-time line. |
| **Jour** | The same, one column. |

Common: "Aujourd'hui" button, jump to a date, swipe/arrows between periods, a floating "+" (new event on the selected
day), search of events by title/location/description, week starting Monday (setting), 24 h clock following the system.

#### 4.2 Event editor

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

#### 4.3 Activities in the calendar

Read from the activities index (no duplication, no event files):

- Entry per activity at its `start`, lasting `elapsed`; title = the activity note's title; sport emoji; second line
  distance and duration.
- Tap → the existing activity detail screen (map, charts). Not editable from the agenda; long press shows "Ouvrir l'activité".
- Layer **Activités**: toggle, colour; optional filter per sport (reuse the sports list of the activity settings).
- An activity that crosses midnight shows on its start day only.

#### 4.4 Dated notes in the calendar

Layer **Notes**: entries from notes with `start` (and notes with `reminder`). Tap opens the note. Time shown if there
is one, otherwise it is an all-day marker. A checklist note with a date shows its progress ("3/5") as the second line.

#### 4.5 Birthdays (optional, question 6)

Contacts with a `birthday` appear every year, all-day, "Anniversaire de <prénom nom>", layer **Anniversaires**,
computed (no files). Tap opens the contact. If the year of birth is known, "(34 ans)".

#### 4.6 Reminders (Android)

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

### 5. Import

#### 5.1 What is imported

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

#### 5.2 The import dialog (Android and Windows)

1. Pick the file(s) (system picker; also reachable by opening an `.ics` attachment from a mail or the file manager: the app registers for `text/calendar`).
2. **Preview**, no write yet: per calendar, count of events, series, date range, number of ignored items and of
   zones that could not be resolved.
3. Options: target calendar (new or existing, colour), optional **"depuis le …"** cut-off (default: everything), duplicates policy.
4. Import with a progress bar (events are written in batches; the UI stays usable); report at the end ("812 événements, 37 séries, 3 ignorés").
5. **Undo** the whole import in one action ("Annuler l'import"): every file created by an import carries `import: <import-id>` in its frontmatter, so removal is exact.

**Duplicates** (matching by `uid` among existing events of the target calendar): *skip*, *update the existing event*
(default for a re-import: the file's content wins, the description edited in the app is preserved in the body unless
the ICS description changed), or *keep both*.

#### 5.3 Robustness

- Streaming parse (calendars of 20–50 MB exist): events are handled one by one, never the whole file as a string.
- Line unfolding, escaped characters, `\n` in text, UTF-8 and the odd Latin-1 file are all handled by the library.
- A malformed event is skipped and counted; the rest imports.
- Recurrence safety: unbounded series are fine (expanded lazily); an `RRULE` that expands to more than 10 000 occurrences in the visible window is cut and reported.
- Library: **biweekly** on Android (plain Java, ICS parse and recurrence iteration), **icalendar** + **recurring-ical-events** on Windows.
  Shared vectors (the same `.ics` → the same list of occurrences over a date range) keep both sides honest.

### 6. Export

- Per calendar: **"Exporter en .ics"**; all calendars: a `.zip` of `.ics`. The Android share sheet or save dialog; Windows save dialog.
- The inverse mapping of 5.1 (inclusive all-day end → exclusive, zone as `TZID` with a `VTIMEZONE`, alerts as `VALARM`, `uid` kept).
- Activities and dated notes are exportable as events too (option "Inclure les activités et les notes datées"); contacts' birthdays are not.
- A round trip test: export, re-import into an empty calendar, same occurrences.

### 7. Android widget

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

### 8. Windows UI

Same views (Agenda, Mois, Semaine, Jour) in Qt; the editor is the note editor plus the event fields; the import
dialog is the same flow with a file dialog. No reminders, no widget. Read/write of the same files, watched for
changes by the existing watcher.

### 9. Relation with Android's system calendar

The app's events are **not** in the system calendar provider, so other apps (assistants, car, watch) do not see them.
This is a deliberate non-goal of v1: the Calendar Provider needs an account and a sync adapter just like contacts,
and a calendar the user cannot edit anywhere else than in one app is simpler to reason about. If wanted later: a
one-way "Afficher dans le calendrier du téléphone" mirror, same design as the contacts mirror
(Part C (Contacts), section 5), with the same caveats.

### 10. Test plan (when implemented)

- ICS vectors (synthetic, shaped like Google, Proton and Outlook exports): single, all-day, multi-day, weekly with
  `BYDAY`, monthly `BYSETPOS`, `COUNT` and `UNTIL`, `EXDATE`, override with `RECURRENCE-ID`, DST changes (the
  Sunday of the clock change at 02:30), `TZID` unknown, Windows zone names, UTC times, folded lines, HTML description.
- Same vectors, same expected occurrences on Kotlin and Python.
- Round trip export → import.
- Import of 5 000 events: duration, memory, first scan, first sync (on the phone).
- Reminders: scheduling window, reboot, zone change, snooze, exact-alarm refusal fallback (on the phone).
- Widget: empty, one day, many days, all-day events, 24 h vs 12 h, dark and light.

---

## Part C. Contacts

### 1. Goals and non-goals

The user's address book is small and simple: a **first name, a last name, one or more phone numbers, maybe an email
address**. No photos. Today it lives in Google Contacts.

Goals

- A contact list in the app (Android and Windows), stored as Markdown files in the synced folder.
- Move the contacts out of Google: **export a vCard from Google, import it here** (a guide, not an integration).
- Make the contacts usable by the phone itself (caller ID, names in SMS and messaging apps): see section 5.

Non-goals (v1)

- No photos, no groups/labels beyond a favourite flag, no social profiles, no custom fields, no merge across accounts.
- No Google or Proton sync, no CardDAV.
- No dialer: calls go through the phone's own dialer.

### 2. Data model

One file per contact: `contacts/<lastname>-<firstname>-<id6>.md` (name fixed at creation like every other file).

```yaml
---
id: p4x8q1
title: Marie Dupont            # maintained by the app: "<first> <last>"; keeps the file a valid note
first_name: Marie
last_name: Dupont
phones:
  - +33 6 12 34 56 78 (mobile)
  - +33 1 23 45 67 89 (domicile)
emails:
  - marie@example.org
address: 12 rue de la Paix, 75002 Paris     # optional, one free-text line
birthday: 1990-05-14           # optional; --05-14 when the year is unknown
favorite: true                 # optional
uid: 8c1f…@google              # vCard UID kept for re-imports; generated for contacts created here
created: 2026-11-01T10:12:00+01:00
updated: 2026-11-01T10:12:00+01:00
---
Free text: how we met, kids' names, gate code…
```

Rules

- A phone is stored as typed, with an optional label in parentheses (`mobile`, `domicile`, `travail`, `autre`).
  Matching and search use a **normalised form** (digits only, a leading `00` or `+` country code handled, French
  `06…` and `+336…` match each other); the displayed text is never rewritten.
- The body is free Markdown text, the same note editor (checklists, attachments) as everywhere else. The user sees
  a "Notes" box, never Markdown.
- Either name may be empty (a company, a nickname): then `title` is whatever is filled, and sorting uses it.
- Unknown frontmatter keys stay verbatim.
- A few hundred files: no volume concern, no subfolders.

### 3. Features

#### 3.1 List

- Alphabetical by last name then first name (setting: first name first), sticky letter headers and a draggable
  letter scroller on Android; accent- and case-insensitive.
- **Favoris** section on top when any is ticked.
- Search box: matches names, phone digits (searching `0612` finds `+33 6 12…`), emails, and the notes body.
- Count in the header ("236 contacts").

#### 3.2 Contact detail

Name, then one row per phone and per email with the matching action:

| Row | Tap | Long press |
|---|---|---|
| Phone | **Appeler**: opens the dialer with the number filled in (`ACTION_DIAL`, needs no permission and cannot place a call by accident) | Copy; SMS |
| Phone (SMS icon) | **SMS**: `smsto:` | |
| Email | `mailto:` | Copy |
| Address | opens the maps app | Copy |

Menu: Modifier · Partager (as a `.vcf`) · Ajouter/retirer des favoris · Supprimer (with undo).

#### 3.3 Create / edit

Fields: Prénom · Nom · Téléphone (+ type, "Ajouter un numéro") · E-mail (+ "Ajouter") · Adresse · Anniversaire
(optional date picker, year optional) · Notes. Saving with only a number is allowed. Same discard/undo patterns as notes.

#### 3.4 Duplicates

Tool "Doublons" (in the menu): groups by identical normalised phone or identical name; per group, "Fusionner"
(keep one, union of numbers/emails/notes) or "Ignorer". Never automatic.

#### 3.5 Birthday in the agenda

If wanted (open question 6): the `birthday` field shows in the agenda every year (Part B (Agenda), 4.5).

### 4. Import and export

#### 4.1 Import vCard (`.vcf`)

Input: one `.vcf` with any number of contacts (Google's export, a phone's export, Proton's export, a single contact
received by message). Entry points: the Contacts module menu "Importer des contacts" and opening a `.vcf` from
elsewhere (the app registers for `text/vcard` and `text/x-vcard`).

Mapping:

| vCard | Becomes |
|---|---|
| `N` / `FN` | `last_name`, `first_name`; only `FN` present: split on the last space; a company-only card keeps the company as first name |
| `TEL;TYPE=…` | `phones` (type → label: `CELL`→mobile, `HOME`→domicile, `WORK`→travail, others→autre) |
| `EMAIL` | `emails` |
| `ADR` | `address` (joined on one line; only the first is kept, others are appended to the notes) |
| `BDAY` | `birthday` (`1990-05-14`, `19900514`, `--0514` accepted) |
| `NOTE` | body |
| `ORG`, `TITLE` | one line at the start of the body ("Société : …") |
| `UID` | `uid` (re-import key) |
| `PHOTO`, `URL`, `X-*`, `IMPP`, `CATEGORIES`, `REV` | ignored (counted in the report) |

Parsing must cope with what real exports contain: vCard **2.1, 3.0 and 4.0**; Android's older exports with
`ENCODING=QUOTED-PRINTABLE;CHARSET=UTF-8`; folded lines; grouped properties (`item1.TEL` with `item1.X-ABLabel:` from
Google); several `TEL` of the same type; a `FN` without `N`. A bad card is skipped and counted, the rest imports.

Flow, same shape as the agenda import: pick the file → **preview** (number of cards, number that look like duplicates
of existing contacts, number without any number or name) → options (duplicates: skip / update by `uid` or number /
keep both) → import with progress → report → **"Annuler l'import"** (each created file carries `import: <import-id>`).

Optional later: Google's *CSV* export format (headers `First Name`, `Last Name`, `Phone 1 - Value`…). vCard is
the recommended path because it is Google's own "export for another app" format and the phone's export is vCard too.

#### 4.2 Export

- One contact: share as `.vcf` (vCard 3.0, widest compatibility).
- All contacts: **"Exporter les contacts"** → a single `contacts.vcf` (backup, and the way out of the app).
- Round trip tested: export → import into an empty app → identical contacts.

### 5. The phone's own contacts system: what is and is not possible

Facts about Android that shape the design (*to verify on the user's phone, a Samsung; the behaviour of the
manufacturer's contacts and dialer apps can differ from stock Android*):

1. **Android has no "default contacts app" role.** There are roles for the dialer, SMS app, browser, launcher,
   assistant, call screening… but not for contacts. What exists is the **system contacts database** (the
   `ContactsContract` provider) that **every app reads**: the dialer to show a caller's name, the messaging apps,
   WhatsApp, Signal, the car…
2. Therefore **an app that keeps contacts only in its own files is invisible to the phone**: no caller name on
   an incoming call, no names in SMS. Being "the contacts app" is not enough; the data must be in the provider.
3. Which app opens when a contact is tapped elsewhere ("Add to contacts", "View contact", the contact picker) is
   decided by intent handling: an app can register for those intents and the user can make it the default for them.
   That can be done, but is secondary to point 2.

So three levels, from the simplest to the most integrated:

| Level | What | Result | Cost |
|---|---|---|---|
| **L0 Standalone** | Contacts live only in the app's files | The list, search, call/SMS buttons work in the app. The phone's dialer and messages do not know the names (unless Google Contacts stays on). | none |
| **L1 Mirror (decided)** | The files stay the source of truth; the app **writes a copy into the system provider**, under its own account ("Contacts Scriptorium" or whatever the app is called), one way (files → provider) | Caller ID, names in SMS/WhatsApp, the car, the watch all work. Delete the account and the copies disappear. | moderate: an account type (a stub authenticator), a sync step, the `READ_CONTACTS`/`WRITE_CONTACTS` permissions |
| L2 Two-way | Also read the provider's changes (edits made in the phone's contacts app) back into the files | Edit anywhere | high (conflict rules, sync adapter), only if L1's one-way rule hurts |

**L1 design**

- Own account type (`app.griffonote.contacts`) with an authenticator that does nothing and a contacts sync adapter
  declaration, so the provider accepts raw contacts owned by it and shows it as an account in the phone's settings.
- One raw contact per contact file: `SOURCE_ID` = the contact `id`, `SYNC1` = a hash of the file content;
  `StructuredName`, `Phone` (with type mapping), `Email`, `StructuredPostal`, `Event` (birthday), `Note` data rows.
- Run: at app start, after the files change (watcher), after an import, and by a periodic job. Diff by hash,
  apply in batches (the provider limits a batch to about 500 operations), delete rows whose file disappeared.
- The account's contacts are marked visible (`ContactsContract.Settings.UNGROUPED_VISIBLE`) so the dialer shows them.
- Edits made to these copies in the phone's own contacts app are **overwritten at the next run**; the app's
  Settings says so, and the contact screen of the app is where to edit. (Handling `ACTION_EDIT` for these rows to
  open the app's editor is possible and is part of the verification checklist.)
- Setting "Afficher mes contacts dans le téléphone (appels, SMS…)", off until the user turns it on, explaining what it does and asking for the two permissions.
- If the user's phone already shows the same people from Google, Android's aggregation usually merges the display,
  but the clean way is to turn Google's contact sync off after the migration (section 6).

**Verification checklist on the phone (before building more)**: create one raw contact under a custom account and
check (1) the dialer shows the name on a test call, (2) it shows in the messaging app, (3) the contacts app lists
it and where, (4) deleting the account removes it, (5) a batch of 300 contacts takes under a few seconds.

**Fallback if L1 proves unreliable on the phone**: keep Google (or the phone) as the address book and let the app's
Contacts module read the provider read-only (`READ_CONTACTS`) to list, search and export. It loses the cross-device
file sync for contacts, so it is a fallback only.

### 6. Migration from Google (the plan, steps in Part D (Import guides))

1. Export the contacts from Google Contacts as vCard (keep that file: it is the backup).
2. Import into the app; check the count against Google's ("236 contacts").
3. Use the app's contacts for a few days next to Google's (no conflict, nothing is mirrored yet).
4. Once the mirror is built: turn on the mirror, check the checklist above, then turn off Google's contact sync on the
   phone (Settings → Accounts → Google → Account sync → Contacts) and, only after keeping a fresh vCard, delete the contacts from Google.

### 7. Windows

The same module: list, search, detail, create/edit, duplicates, vCard import/export. Call/SMS buttons are hidden
(or copy the number). Nothing is mirrored anywhere.

### 8. Privacy

The contacts are personal data in a **public repository's tests**: tests use synthetic contacts only. The app
never sends contacts anywhere; import and export are local files.

### 9. Test plan (when implemented)

- vCard vectors (synthetic, shaped like exports from Google, Android 2.1 quoted-printable, Proton, Apple): names
  with accents and apostrophes, several phones, grouped `item1.` properties, folded lines, `FN` without `N`, a card
  with only a company, `BDAY` in three formats, empty cards.
- Phone normalisation: `06 12 34 56 78`, `+33 6 12 34 56 78`, `0033612345678`, `(06) 12-34-56-78` all equal.
- Search by digits, accents, case; letter headers; sorting with empty names.
- Round trip export → import; undo of an import removes exactly the created files.
- L1 on the phone: the checklist of section 5.

---

## Part D. Import guides

**The menu names below are written from memory of how Google and Proton were organised and must be checked against
the real screens when the guides are finalised: both services move their menus around.** Items marked *(verify)*
are the ones to check first.

Principle: everything is a **one-time copy of files**, not a link. Export a file from the service, import it into the
app. Keep the exported files: they are your backup.

### 1. Google Calendar → the app

On a computer (the export is not available in the phone app) *(verify)*:

1. Open <https://calendar.google.com> signed in to the right account.
2. Click the gear (top right) → **Paramètres** (Settings).
3. In the left column choose **Importer et exporter** (Import & export), then the **Exporter** (Export) button.
4. A `.zip` downloads. It contains **one `.ics` file per calendar** you own (the main calendar, plus others you
   created). Calendars shared with you, holidays and the birthdays calendar are usually **not** included *(verify)*.
   To get a calendar someone shared with you, that person has to export it.
5. Put the `.zip` on the phone (cloud drive, cable, share) or keep it on the PC for the Windows app.

Alternative for everything at once: <https://takeout.google.com> → deselect all → tick **Agenda** (Calendar) → create
the export; the same `.ics` files come inside the archive.

### 2. Proton Calendar → the app

On <https://calendar.proton.me> in a browser *(verify)*:

1. Gear → **Tous les paramètres** (All settings) → **Calendriers** (Calendars).
2. Choose the calendar and use its **Exporter** (Export) action; an `.ics` file downloads. Repeat for each calendar.
3. Put the `.ics` file(s) on the device.

Proton encrypts the calendar end to end: the export happens in your browser, after decrypting, so it needs you to be
signed in; the file itself is **not encrypted**, treat it like your agenda in plain text.

### 3. Importing the calendar files into the app

1. Agenda → menu → **Importer un fichier .ics** and pick the `.zip` (Google) or `.ics` files. (Opening an `.ics`
   from a mail attachment or the file manager starts the same dialog.)
2. The preview lists each calendar with its number of events and series and its date range. Rename the calendars,
   pick their colours.
3. By default **everything** is imported; use **"depuis le …"** only to skip old history (the first sync of the folder is slower with thousands of events).
4. **Importer.** The report tells how many events were imported and how many items were ignored.
5. Check a few things you know: a recurring event (the next three occurrences), an all-day event, an event in a
   different time zone, a birthday or a holiday.
6. Something wrong? **Annuler l'import** removes everything that import created.

**Importing again later** is safe: events are matched by their original ID, the default "mettre à jour" updates the
existing ones and adds the new ones. This is how you can keep using the old calendar for a while and catch up.

What is lost or changes (so there are no surprises): invitation answers and the "who is coming" logic (attendees
become a text list), colours of individual events, attachments, Google Meet buttons (the link stays as text), and
anything the service adds that is not standard iCalendar.

### 4. Google Contacts → the app

On a computer (or in the phone's browser, desktop mode) *(verify)*:

1. Open <https://contacts.google.com>.
2. In the left column, **Exporter** (Export).
3. Choose **Tous les contacts** (or a label, or tick contacts first) and the format **vCard (pour les contacts iOS)**.
   vCard is the right one: it is the standard the app reads. (**Google CSV** also exists, but it is a second-best
   format for this app.)
4. **Exporter**: a `contacts.vcf` file downloads.
5. Note the number of contacts shown in Google, to compare afterwards.

Only the contacts you saved are exported; the **"Autres contacts"** (people Google collected from your mails) are
not, unless you copy them into "Mes contacts" first *(verify)*.

### 5. Importing the contacts into the app

1. Contacts → menu → **Importer des contacts** and pick `contacts.vcf`. (Opening the `.vcf` from a mail or the file
   manager does the same.)
2. The preview shows the number of contacts, possible duplicates of what is already in the app, and cards without
   a name or number. Pick the duplicate policy (the default for a fresh import is "garder les deux", which cannot lose anything).
3. **Importer**, then compare the number with Google's.
4. Use the **Doublons** tool if both versions exist.
5. **Annuler l'import** undoes it if something is off.

Until the optional "mirror" exists (part of the same delivery), the phone's own dialer and messages still use the
contacts kept in Google: nothing changes there. Do not turn Google's contact sync off before the mirror works and has been checked.

#### Exporting contacts from the phone itself (alternative to Google)

On the phone, in the contacts app: menu → manage contacts → import/export → export to a `.vcf` file *(verify, the
path differs by manufacturer)*. The same import applies. Useful if some contacts only exist on the phone.

### 6. Getting out of the app

- Calendars: Agenda → menu → **Exporter en .ics** (one calendar) or **Tout exporter** (a zip), readable by Google, Proton, Thunderbird, Apple, Outlook.
- Contacts: Contacts → menu → **Exporter les contacts** → `contacts.vcf`, readable by anything.
- Everything else is Markdown files in a folder: no export needed.

---


## Part E. Notes for the implementer

Checklist of what "done" means (all of it, one delivery):

1. Module skeleton: folders, navigation (bottom bar on Android, left rail on Windows), per-module synced settings, module on/off.
2. Contacts: list, search (names, digits, emails, notes), detail with actions, create/edit, favourites, duplicates tool, vCard import (2.1/3.0/4.0, quoted-printable, grouped properties) and export, undo of an import, Windows UI.
3. Agenda data layer: event notes in `agenda/`, `ical_extra` lossless round trip, calendars file, recurrence (library on each side), time zones per the standard model, exceptions of a series, activities and dated notes as layers.
4. Agenda UI: Agenda/Mois/Semaine/Jour, event editor with the usual recurrence presets and the custom editor, search, Windows UI.
5. ICS import (zip or files, preview, undo, re-import by UID) and export; the open-from-attachment entry points (`text/calendar`, `text/vcard`).
6. Reminders (channel, exact alarms with fallback, rolling horizon, reboot/zone-change handling, snooze) and the note `reminder` field.
7. The agenda widget (from the owner's reference, Part B section 7) with its configuration screen, and the small "Aujourd'hui" widget.
8. The contacts mirror into Android's contacts (own account, one way), **after verifying on the owner's phone** with the checklist of Part C section 5.
9. README (format sections for `agenda/` and `contacts/`), the guides of Part D, tests, version stays automatic (CI run number).

Library suggestions (verify size and licence before adding): Kotlin **biweekly** (ICS parse and recurrence iteration), Python
**icalendar** + **recurring-ical-events**, Python **tzdata** (Windows has no IANA database). For vCard, a small hand-written
parser is acceptable (the format is simple, and the real-world quirks in Part C are the work); a library is fine if it handles
quoted-printable and grouped properties. No heavy dependencies, no network.

Things to verify on the owner's phone before relying on them: exact-alarm permission flow and the fallback; the
contacts mirror (dialer, messaging app, deletion of the account, batch time); widget behaviour on the owner's launcher (One UI).
Ask the owner to run the short checklists; report the results.

Remaining open points (defaults in Part A section 6): birthdays from contacts in the calendar; four-module navigation;
no reminders on Windows; unified note/event reminders; the app name.
