# Specs: contacts, agenda and the "everything in Markdown files" app

Status: **specification only, nothing is implemented.** Written for review: decisions to confirm are listed at
the end ("Open questions"). UI strings are French (the app's language); everything else is English.

Documents:

- [agenda.md](agenda.md): calendar, events, dated notes, activities in the calendar, reminders, widget, ICS import and export.
- [contacts.md](contacts.md): contact list, vCard import and export, how (and whether) the phone's dialer can use them.
- [import-guides.md](import-guides.md): step by step guides to move the data out of Google and Proton and into the app.

## 1. Where this fits

Griffonote started as a Keep replacement and now holds notes, drawings, scans, attachments and activities. Every
piece of data is a Markdown file with a YAML frontmatter in one folder synced by Synchrotron, read by the Android
app and the Windows app. Contacts and an agenda are two more kinds of the same thing.

**Decision (recommended): one app, one folder, several modules.** The app keeps its code, its sync and its update
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

The app name is undecided (see the list of candidates in the conversation); nothing below depends on it. Only the
displayed name should change, never the Android `applicationId` (`app.griffonote`): changing it would make an
unrelated app, with no in-place update.

## 2. Architecture rules (all modules)

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

## 3. Navigation

Today: the notes list with a pinned "Activités" entry. With four modules the pinned-entry pattern stops scaling.
Proposal (question 7): a bottom bar **Notes · Agenda · Activités · Contacts** on Android, a left rail on Windows,
modules hideable in the settings. The editor, search and settings stay where they are.

## 4. Delivery

**Decision: one delivery, no phase order.** Contacts, agenda, reminders, widgets and the contacts mirror are built together
and shipped as one version of the app (each push to `main` still publishes a build; nothing needs to be released piecemeal).
What the work does have is a **dependency order inside it**, which is how it will be sequenced:

1. Module skeleton: folders, navigation, per-module settings file, module on/off, the shared "dated entries" index.
2. Contacts (list, edit, vCard import/export) and the agenda data layer (events, recurrence, time zones, ICS import/export): they only need (1).
3. The views (agenda, month, week, day, contacts), the editors, activities and dated notes in the calendar.
4. Reminders and the two widgets (need the agenda index).
5. The contacts mirror into the phone's contacts (needs the contacts module; **verified on the real phone first**, see contacts.md).
6. The Windows UI of both modules, the guides, the README.

Left for later, on request only: a month widget, "this and following" edits of a series, Windows reminders, read-only URL
subscription to an external calendar, a two-way contacts sync.

## 5. Risks

- **Import volume.** A Google or Proton calendar of several years is thousands of events, hence thousands of small
  files for Synchrotron (WebDAV/rclone): slow first sync. Mitigations in agenda.md (import cut-off date, subfolders per year).
- **Recurrence rules and time zones** are where calendar apps go wrong (DST, exceptions, all-day multi-day events).
  A tested library on each side, plus a battery of real-world-shaped test vectors, is the answer; no hand-written RRULE engine.
- **Android's contacts system** has no "default contacts app" role: see contacts.md for what is and is not possible.
- **Behaviour on the user's phone** (Samsung) can differ from the documentation: every Android integration point in
  these specs is marked *to verify on the device* and gets a short test checklist.

## 6. Decisions and remaining questions

Decided (answers of the user):

| Topic | Decision |
|---|---|
| One app or several | One app, one folder, modules. |
| Events | An event is a note (Markdown). ICS is the interchange format; properties the app does not model are kept verbatim (`ical_extra`) so nothing is lost on export. |
| Import | Everything is imported by default (the user has few events); an optional "depuis le …" cut-off. |
| Recurrence | The usual: daily, weekdays, weekly, every 2 weeks, monthly, yearly, and a custom editor (interval, weekdays, n-th weekday, end by date or count). |
| Time zones | Standard iCalendar model: an event keeps its zone (local time + IANA zone), floating events are an option, display in the device zone. See agenda.md 3.5. |
| Contacts in the phone | Yes: the mirror into Android's contacts so names show in the dialer and the messages; then Google's contact sync is turned off. |
| Widget | The user's screenshot is the reference (agenda.md section 7). |
| Order | No order: everything in one delivery. |
| Contact fields | First name, last name, phones, emails (+ optional address, birthday, notes). No photos. |

Still open (each has a default, in bold, that the specs assume; answer only to change it):

1. **Birthdays** from contacts in the calendar, optional per contact: **yes**.
2. **Navigation**: a bottom bar Notes · Agenda · Activités · Contacts on Android, a left rail on Windows, modules hideable: **yes**.
3. **Windows scope**: views and editing of both modules, no reminders on Windows (the app is not resident): **yes**.
4. **Note reminders** (the `reminder` field reserved since the first version): the same alert mechanism as events, and a note with a reminder shows in the calendar: **yes**.
5. **Parallel use** of Google/Proton calendars after the import: assumed that you switch over; re-importing an updated export is supported by event ID if you ever need it.
6. **Name** of the app: undecided, independent of this work.
