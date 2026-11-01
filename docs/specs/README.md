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

## 4. Roadmap

Each phase ships on its own (every push to `main` already publishes a build).

| Phase | Content | Why this order |
|---|---|---|
| 0 | Module skeleton: folders, navigation, per-module settings file, module on/off. Shared "dated item" index. | Everything below plugs into it. |
| 1 | **Contacts, standalone**: list, search, create/edit, vCard import/export, call/SMS/email actions. Windows too. | Smallest module and the one that frees the user from Google Contacts. |
| 2 | **Agenda core**: calendars, events (single and recurring), month/week/day/list views, activities and dated notes in the calendar, **ICS import/export**. Windows too. | The big one. |
| 3 | **Reminders and widget** (Android): notifications, agenda widget. | Needs phase 2's data. |
| 4 | **Contacts mirrored into Android** (caller ID, SMS names): own account in the system contacts provider. | Optional, needs real-phone testing. |
| 5 | Extras: month widget, birthdays from contacts, read-only URL subscription, "this and following" edits, Windows reminders. | Only if wanted. |

## 5. Risks

- **Import volume.** A Google or Proton calendar of several years is thousands of events, hence thousands of small
  files for Synchrotron (WebDAV/rclone): slow first sync. Mitigations in agenda.md (import cut-off date, subfolders per year).
- **Recurrence rules and time zones** are where calendar apps go wrong (DST, exceptions, all-day multi-day events).
  A tested library on each side, plus a battery of real-world-shaped test vectors, is the answer; no hand-written RRULE engine.
- **Android's contacts system** has no "default contacts app" role: see contacts.md for what is and is not possible.
- **Behaviour on the user's phone** (Samsung) can differ from the documentation: every Android integration point in
  these specs is marked *to verify on the device* and gets a short test checklist.

## 6. Open questions

Answer what you can; each has a default (in bold) that the specs assume.

1. **Events as notes.** An event is a Markdown note (description = the note body, with checklists, photos, drawings)
   stored in `agenda/`, and any normal note can also get a date. **Yes**, or events as a separate, simpler kind of item?
2. **Import cut-off.** When importing a calendar, the default is **the last 12 months and everything after** (older
   events skippable by choice), to keep the file count sane. OK?
3. **Contacts and the phone.** Do you want the app's contacts to show up in the phone's dialer, messages and
   WhatsApp (needs the "mirror" of phase 4)? **Yes, later.** Do you stop syncing Google Contacts afterwards?
4. **Windows scope.** Contacts and agenda **views and editing** on Windows; no reminders on Windows (the app is not
   resident). OK?
5. **Widget.** Send screenshots of the Google and Proton widgets you like (layout, sizes, what a row shows). Default:
   an agenda list widget, resizable; month widget later.
6. **Birthdays.** An optional `birthday` field on a contact, shown yearly in the calendar. **Yes, optional.**
7. **Navigation.** Bottom bar with four modules (Android) and a left rail (Windows). **OK?**
8. **Name.** Undecided; independent of this work.
9. **Time zones.** Events keep their own zone (so a flight "14:00 Paris" stays correct abroad) and show in the
   device zone; "floating" events (no zone) follow the device. **OK?**
10. **Note reminders.** Notes already have a `reminder` field reserved (Keep-style, not implemented). **Plan:** the
    same alert mechanism serves both, and a note with a reminder also appears in the calendar. OK?
11. **Parallel use.** After the one-shot import, do you keep using Google or Proton calendars at the same time? If
    yes, you will want re-importing (updates by UID, supported) or later a read-only URL subscription. **Assumed: you switch over.**
12. **Priority.** The phases above put contacts first, then the agenda. Preferred order?
