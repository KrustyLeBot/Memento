# Mémento

Notes, agenda, contacts and sport activities, on Android and Windows. Everything is a plain Markdown file in
one folder (`Documents/Memento/` by default), so it can be read and edited on a PC (Obsidian, any editor)
and synced by a separate app (Synchrotron). The user never sees Markdown on the phone. Formerly Scriptorium, and
Griffonote before that: on first launch the Android app offers to copy `Documents/Scriptorium` (or
`Documents/Griffonote`) into the new folder.

Four modules, reached from the bottom bar on Android (Settings is its last entry) and the left rail on Windows:
**Notes**, **Agenda**, **Activités** (with a 3D/2D map), **Contacts**. Agenda, Activités and Contacts can be
hidden in the settings.

## Storage format

```
Mémento/
├── courses-k3f9a2.md      # one file per note: YAML frontmatter + Markdown body
├── attachments/           # scanned PDFs (`pdf:`), attached files (`attachments:`) and activity tracks
├── activities/            # the "Activités" folder: one note per activity, kept out of the regular list
├── agenda/                # events, one note each, in a folder per year; agenda/calendars.json
├── contacts/              # the "Contacts" module: one note per contact, kept out of the regular list
└── drawings/
```

- A scan is a normal note whose frontmatter has `pdf: attachments/<name>.pdf`; the Android app creates it
  with Google's document scanner (automatic page corners, several pages) and fits each page on A4 or
  US Letter (guessed from the page shape, or fixed in the settings), compressed for a fast sync.
- Files attached to a note (photos, videos, any document) are copied to `attachments/<name>-<id6>.<ext>` and
  listed in its frontmatter: `attachments: [attachments/facture-edf-k3f9a2.pdf, ...]`. On Android they come from
  the gallery or the file manager (paperclip button of the editor), on Windows from a file dialog; 50 MB at most
  each. Deleting the note or removing an attachment deletes its file.
- The file name is fixed at creation (`<slug>-<id6>.md`) and never renamed.
- Unknown frontmatter fields are preserved verbatim.
- Writes are atomic: `.<name>.synctmp-<uuid>` in the same folder, flushed to disk, then renamed over the file (Synchrotron never syncs that pattern).
- The folder is the source of truth; the in-memory index is rebuilt on launch, on resume
  and when files change on disk.

## Drawings

A note can hold sketches (editor toolbar, brush button): a page to draw on with a finger (phone) or the mouse
(PC), with a pen in six colours and three widths, an eraser that removes whole strokes, undo/redo, and zoom and
pan (two fingers on the phone; wheel and right/middle drag on the PC). A drawing is a plain SVG, `drawings/dessin-<id6>.svg`,
listed in the note's `attachments:` like any file: any viewer opens it, and Mémento reads back its own paths
(`<path d="M… L…" stroke="#rrggbb" stroke-width="…">`, page marked `data-memento="1"`, older drawings `data-griffonote="1"`) to edit it again. Tap
the thumbnail in the note to edit; erasing everything removes the drawing. Deleting the note deletes its drawings.

## Activities

The pinned "Activités" entry of the notes list opens a separate folder (`activities/`): figures for the
week, the month and overall, then the latest activities (walking, cycling, electric bike, skiing, other with or without GPS).

- An activity is a note whose frontmatter has `activity: {"sport": "ski", "start": <ms>, "elapsed": <s>, "moving": <s>,
  "distance": <m>, "ascent": <m>, "descent": <m>, "hr": <bpm>, "vmax": <m/s>, "runs": <n>, "file": "attachments/….fit"}`:
  what the list shows without opening the track. The track itself is one **FIT** (or **GPX**, for imports) file in
  `attachments/`, listed in `attachments:` with the photos. Everything shown (map, figures, charts) is recomputed
  from that file, so the analysis can improve without touching old activities.
- Import: any FIT (Coros and other watches, Strava's "original file" export) or GPX file, one at a time, from the
  "Importer FIT / GPX" button. A GPX without any time (Strava's GPX export of a route or of someone else's
  activity) is imported too, dated the day of the import: map, distance, climb and profile, no duration or speed.
  Recording (Android only): GPS in a foreground service, a log written as it goes
  (a crash loses nothing), turned into a FIT file when the activity ends. A time-only sport has no file; its
  distance can be typed afterwards (treadmill).
- Analysis (same algorithms in Kotlin and Python, tested on synthetic tracks): GPS jumps dropped; **stops** found
  by position (nothing moved more than 8 m for 30 s, about 1 km/h), on top of the pauses made with the button, and
  both left out of the moving time, the distance and the average pace; climb with a 3 m hysteresis on a smoothed
  altitude; skiing is cut into **descents** (altitude falling and fast enough), the only part counted for the
  skiing figures and drawn on the map. Without altitude in a file, descents fall back on speed.
- Importing a recording already imported (same start time) offers to replace it: the name, description and photos
  stay, the track and the figures are redone. A watch that writes the sport as "generic" gives "Autre"; the sport
  is picked in the import dialog.
- Some watches (Kiprun/Coros for instance) record positions only. For such a file the import dialog (or the
  "Altitude" card of the activity) offers to look the altitude up in the SRTM 30 m terrain model through the public
  OpenTopoData service: the positions, about one per 30 m, are sent to opentopodata.org, only when asked, and the
  result is kept in `attachments/<name>.alt` (`time ms,altitude` lines; `alt` in the activity frontmatter). The
  FIT/GPX file itself is never modified. Without altitude, skiing descents fall back on speed, which also picks up
  gondolas.
- Settings of the folder live in `activities/settings.json` (`{"refine_altitude": false, "totals": ["walk", ...]}`) and
  sync with the notes: the sports counted in the totals (all by default) and "Affiner l'altitude avec le relief à
  l'enregistrement" (off by default: on saving a recording, the barometer's altitude is corrected with the terrain
  model, which follows its slow drift without taking the model's local errors; needs the network, sends the
  positions to opentopodata.org; offline the barometer altitude is kept).
- Skiing: climbs (lifts, uphill: altitude going up for at least a minute and 30 m, short stops allowed) are counted
  and drawn in blue next to the descents. Totals count the sports ticked in the settings; when skiing is counted it also gets a line with its descents and
  metres skied down (its track includes the lifts, so many people untick it). The altitude of several activities can
  be completed in one go (selection, or all): those that already have one are skipped.
- Altitude when recording: the barometer gives the changes, the GPS keeps it honest (the gap between both is
  followed with a 5 minute time constant). The consolidated altitude is the standard FIT field; the raw GPS
  altitude and the pressure are kept as developer fields (`gps_altitude`, `pressure`).
- GPS rate while recording: 5 s walking, 2 s cycling and skiing, 3 s other; "Économie de batterie" uses 12 s.
- The map is one web page shared by both apps (`web/map/`, MapLibre GL JS 4.7.1 vendored): the IGN aerial
  photo (orthophotos, WMTS `ORTHOIMAGERY.ORTHOPHOTOS`, JPEG, up to z19, never filtered, in either theme) on the Terrarium
  elevation model, in 3D (default) as a square block cut out of the relief around the track (side = the
  track's larger side + 25 %, at least 1 km), or flat in 2D; the switch is a native control and is remembered. The
  track is coloured by its speed relative to the mean (red slow, yellow mean, green fast), ski days keep their
  descents/climbs colours; lines are drawn with a white halo and a dark casing and the overlays on opaque surfaces,
  so everything reads on snow as on forest; up to 12 summits (IGN BD TOPO + IGN altimetry, spread out) are labelled. The page asks
  its host for everything under one base URL (`t/ortho|dem/{z}/{x}/{y}`, `peaks?w&s&e&n`; JPEG for
  the photo, PNG for the relief and the OpenStreetMap stand-in): the host fetches with an
  identifying User-Agent and keeps a disk cache (300 MB, least recently used purged), so what was seen once works
  offline. Where IGN has no photo (it answers 404 abroad and at sea, and a uniform white JPEG at the edge of its coverage) the
  OpenStreetMap tile is served instead. Offline route packs (Android) hold the photo of the route's square up to
  z14 and, along the track, up to z19: z15–17 within 1 km, z18–19 on a narrower band (300 m down to 75 m), within
  500 MB (the z18–19 band narrows, then z19, z18, z17 and the band's width give way, then z14, when it does not fit; a
  short route gets its whole square at z17; a route so wide that even its relief does not fit gets a flat 2D pack),
  estimated at 15 KB a tile (measured 10–20 KB in the Alps, 17–23 KB in towns). One setting (Réglages → Cartes → 3D
  hors ligne: asked, always or never) decides the 3D (relief, buildings, peaks) everywhere: preparing a route asks
  for 2D + 3D or 2D alone with the size of each (or offers only the one the setting imposes); a recording started on
  a route not prepared downloads its 2D pack at once and asks under the recording, as without a route. A recording without
  route always downloads a 2D zone pack around its start (Wi-Fi or mobile data): the photo z8–13 over a square whose
  side is set in Réglages → Cartes (10–80 km, 40 km by default, about 4 MB in the Alps), plus z14–17 within 3 km of
  the start and z18–19 within 1 km (about 45 MB). An optional 3D zone adds relief z6–13 (130 KB a
  tile, the full precision of the elevation data), buildings and peaks over its own square (5–80 km, 40 km by default; about
  40 MB more at 40 km); the photo also covers the 3D square when it is the larger, so 3D is never drawn
  without imagery. Réglages → Cartes says whether it is asked (default: an inline question once the recording has
  started, never delaying it; unanswered means no), always or never downloaded, with the live estimate of each size.
  A 2D zone pack is turned into the 3D one in place (its tiles kept). Zone packs are capped at 300 MB (the disc around the start goes first, then the plan lowers
  the photo's top zoom past it; the real bytes are held to it while downloading), kept 24 h and pinned while
  the recording runs; offline, the live map stays 3D inside the relief square and falls back to 2D outside. Offline, a photo tile
  missing from the packs is cut from its nearest ancestor and enlarged, so zooming in off the band shows the
  medium-definition photo instead of nothing (MapLibre does not overzoom a raster tile it could not get). `tools/mapdev.py` serves
  the page with these endpoints for development (`python tools/mapdev.py`, then drive `window.SMap`).
- In 3D, close up (zoom 14.5, or a screen spanning 2 km or less), the buildings stand on the block as extruded blocks:
  IGN BD TOPO V3 `batiment` from the Géoplateforme WFS (Licence Ouverte Etalab 2.0), height from `hauteur`, else the
  roof's top minus the ground, else 3 m a floor, else 6 m. The host serves them one z15 cell at a time
  (`buildings/15/{x}/{y}`: footprints rounded to 1e-6°, heights, a few tens of KB for a town's cell), fetched on demand
  and kept in the same disk cache as the tiles; offline and not cached, there are none (404). The page draws the 16
  cells nearest the camera's centre (15 000 buildings at most), only inside the block, all in one light stone colour
  (one extrusion layer, nothing computed per building). Route packs and 3D zone packs hold the cells of their relief square, at most 600 (past
  that, those within 1 km of the route or nearest the start), estimated at 16 KB a cell (measured 33 KB around
  Annecy, 14 KB around Chamonix, 3 KB in the Vanoise).
- FIT is read and written with a small implementation of the public format (`Fit.kt`, `fit.py`), no SDK.
- Tests use synthetic tracks only: this repository is public and a real track reveals where you live.

## Agenda

Events live in `agenda/<year of the start>/<slug>-<id6>.md` (path fixed at creation), one note per event: the
description is the note body. Agenda keys follow the usual note keys in the frontmatter:

```yaml
start: 2026-11-12T14:30        # local wall time; a date alone (2026-11-12) means all-day
end: 2026-11-12T15:15          # optional; all-day: LAST day included
tz: Europe/Paris               # IANA zone of start/end; absent = floating (follows the device zone)
end_tz: America/New_York       # optional: the end's own zone (a flight), `end` is a wall time there; absent = tz
calendar: perso                # id in agenda/calendars.json; unknown or absent = the default calendar
rrule: FREQ=WEEKLY;BYDAY=TH    # RFC 5545 without "RRULE:"
exdate: [2026-11-19T14:30]     # skipped occurrences, same form as start (a date skips that whole day)
alerts: [-15m, -1d]            # offsets from the start: m, h, d, w
location: 12 rue de la Paix
status: confirmed              # confirmed | tentative | cancelled (hidden)
uid: 0a1b2c@google.com         # ICS UID (re-import key); <id>@memento for events made here
recurrence_of: k3f9a2          # an edited occurrence of a series (its own file)…
recurrence_at: 2026-11-19T14:30  # …and the original start of that occurrence (also in the series' exdate)
import: 20261101T092000-x1y2z3 # set on every file an ICS import created ("Annuler l'import" deletes them)
ical_hash: 1a2b3c4d            # hash of the body the import wrote (a re-import keeps a description edited here)
ical_extra:                    # ICS properties the app does not model, verbatim, written back on export
  - "CATEGORIES:Santé"
```

- Any regular note with `start` (and optionally `end`, `tz`, `alerts`) is a **dated note**; a note with `reminder`
  shows at that time with an alert. Activities and contacts' birthdays are layers computed from their own files.
- `agenda/calendars.json` (synced settings): `{"calendars": [{"id", "name", "color", "visible"}], "default": "perso",
  "layers": {"activities": {...}, "notes": {...}, "birthdays": {...}}}`; colours come from a fixed palette of 12, each
  at least 3:1 against white and against #121212.
- Recurrence is expanded in the wall time of the event's zone (biweekly on Android, `dateutil` on Windows), then
  converted to instants: a time in the spring-forward gap moves forward by the gap (02:30 → 03:30), a repeated time
  takes the first one. `DTSTART` always counts as the first occurrence (RFC 5545). At most 10 000 occurrences per
  window. All-day entries are dates and never move.
- `end_tz` (RFC 5545's `DTEND;TZID=` other than `DTSTART`'s, Google's "fuseau horaire de fin"): only on a timed event
  with `tz`, otherwise ignored with a warning. The end instant is `end` in `end_tz`; each occurrence of a series lasts
  the instant duration of the first one (start in `tz`, end in `end_tz`). The views and widgets still show device
  times; the detail adds "Départ 10:00 (Paris) → arrivée 13:00 (New York)". The editor's "Fuseau d'arrivée
  différent" sets it and refuses an arrival that is not after the departure.
- ICS import (`.ics` or a `.zip` of them): preview, target calendar, "depuis le …" cut-off, duplicates by UID (skip,
  update, keep both), undo. `TZID`s are IANA names, Windows names (CLDR table) or embedded `VTIMEZONE`s recognised
  from their rules; an unknown zone falls back to the device zone and is counted. A `DTEND` with another `TZID`
  than `DTSTART`'s is kept as `end_tz` (no conversion); a UTC `DTEND` is converted to the start's zone. A single UTC
  event takes the device zone; a UTC **series** keeps `tz: UTC` so it does not shift with daylight saving. Overrides
  (`RECURRENCE-ID`) become exception files; HTML descriptions become text; attendees are listed as text.
  Target: a mail invitation goes to the default calendar; a new calendar is proposed only for a real calendar export
  (`X-WR-CALNAME`, more than 20 events, or a `.zip`), named from `X-WR-CALNAME` (else the file name). Up to 5 events
  are listed one by one (title, time in the event's zone and on the device, place); a single invitation gets a
  short "Ajouter cet événement à …" dialog ("Plus d'options…" opens the full form). `LOCATION` placeholders
  (`null`, `undefined`, `None`, blank) are no place. A Proton Meet / Google Meet / Teams / Zoom link of the
  description becomes the first line "Visio : …" unless it is already there.
- Event detail: a "Liens" section lists every link of the description (the meeting link first, "Rejoindre la visio
  (Proton Meet)"), at most 10, each opening the browser; links in the description text are clickable too.
- Export: one `.ics` per calendar (or a zip of all), with a `VTIMEZONE` for every zone used (`end_tz` included,
  written as `DTEND;TZID=<end_tz>`); same bytes on Android
  and Windows. Shared test vectors (synthetic Google, Proton, Outlook, airline-booking and mail-invitation shapes): `testdata/agenda/`.
- Windows: views Agenda, Mois, Semaine, Jour (keys T, ← →, Ctrl+F), the event editor (repetition presets and a
  custom editor writing plain `RRULE`s; a rule it cannot express is shown as "Règle importée" and kept as is),
  series scopes "cet événement" / "tous les événements", delete with undo, calendars, import and export, and
  "Ajouter à l'agenda" in the note editor. Week start, "heures dans le fuseau de l'événement" and default reminders
  are settings of the PC; the default calendar and the layers are in `calendars.json`. Windows stores reminders but
  never notifies (the phone does). Editing a whole series moves its `exdate` and its edited occurrences with the
  first start, so they keep matching.

- `agenda/calendars.json` also holds the default reminders of new events: `"default_alerts": ["-15m"]` and
  `"default_alerts_all_day": ["-6h"]` (all-day offsets count from 00:00, so `-6h` is the day before at 18:00).

### Using the agenda (Android)

- **Views**: *Agenda* (default; the days with entries under month headers, "Aujourd'hui" always shown, it keeps
  loading months as you scroll either way), *Mois* (≤ 3 bars a day and "+N"; tap a day for its list, tap it again for
  the day), *Semaine* (7 columns, all-day strip, hour grid, red line at the current time) and *Jour*. The view icon
  in the top bar switches; swipe or tap the title (jump to a date) to move; the calendar icon returns to today.
  Search (magnifier) covers titles, places and descriptions of events and dated notes. Week start (Monday by
  default) and "Heures dans le fuseau de l'événement" are in Settings → Agenda; the clock follows the system 12/24 h.
- **Layers**: activities (sport emoji, tap → the activity), dated notes (📝, tap → the note), birthdays (🎂, tap →
  the contact). Menu ⋮ → *Agendas affichés*: show/hide each calendar and layer, rename, colour (fixed palette),
  default calendar, new calendar, delete a calendar with its events (undo in the snackbar).
- **New event**: the + button (the selected day; in *Semaine*/*Jour* tap an hour). The editor has Titre, Toute la
  journée, Début/Fin, Fuseau horaire (only shown when it differs from the phone's, or via "Fuseau horaire…"),
  Agenda, Lieu, Répétition (presets, then *Personnalisé…*: every N days/weeks/months/years, weekdays, day of the
  month or n-th/last weekday, end never/until/after N; "Prochaines : …" previews the rule; a rule the editor cannot
  express shows as "Règle importée" and is kept untouched), Rappels (several) and Description: it saves the event
  and opens it in the note editor (checklists, photos, drawings work, since an event is a note).
- **Series**: editing or deleting an occurrence asks *Cet événement* (an exception file plus an `exdate`) or *Tous
  les événements*. An edited occurrence offers *Modifier toute la série* and *Rétablir l'occurrence d'origine*.
  Deletes can be undone from the snackbar.
- **Dated notes**: in the note editor, the calendar icon (*Ajouter à l'agenda*) gives a note a date, an optional
  time and a reminder; the same dialog removes it (*Retirer de l'agenda*). A note's old `reminder` field counts as
  dated and is converted when the date is edited.
- **Import / export**: menu ⋮ → *Importer un fichier .ics* (or open an `.ics` from a mail or the file manager),
  *Exporter en .ics* (one calendar, or all as a `.zip`, optionally with the activities and dated notes), *Imports
  passés* (undo any import whose files are still there).

### Reminders (Android)

- Notifications on the **"Rappels d'agenda"** channel (high importance, sound): title, day, time and place, a
  **Reporter 10 min** action; a tap opens the event (or the note). Settings → Agenda → *Canal « Rappels d'agenda »*
  opens the channel's system page.
- Only the next 48 hours are registered with Android (`AlarmManager`), recomputed after every change (edit, sync,
  import, file watcher), every 6 hours, after a reboot, a time or zone change and an app update. Keys are stable
  (event id + occurrence + offset): a resync never shows a reminder twice; vanished ones are cancelled.
- Exact alarms need "Alarmes et rappels" (Android 12+): the app asks once with an explanation; refused, reminders
  arrive a few minutes late. Notifications need the permission on Android 13+ (asked once from the agenda). On
  Samsung, also exempt the app from battery optimisation (button in Settings → Agenda). *To verify on the phone.*

### Widgets (Android)

- **Agenda** (resizable, about 2×2 to 4×6): the weekday over the date, ⟳ (asks Synchrotron to sync, reads the folder
  again; a spinner meanwhile) and + (new event today); then the next entries, a date block on the first row of each
  day, the calendar's colour bar, the title on up to two lines and "20:00 - 20:30", "Toute la journée" or "Du 1 au 3
  déc.". "Rien de prévu" when empty. A tap on a row opens it, on the date opens the agenda on today. Its settings
  (when added, or long press → reconfigure): calendars and layers, 7/14/30/90 days, keep today's finished entries,
  the ⟳ button, background opacity. It follows the phone's light/dark theme.
- **Aujourd'hui** (2×1): today's next entry and how many others.
- Both are redrawn after every change made in the app, at midnight, on time/zone changes and every 30 minutes; they
  read the app's index off the main thread, never the files on their own.

### Moving the calendars out of Google or Proton

The menu names below are written from memory and must be checked against the real screens: items marked *(verify)*
are the ones to check first. Everything is a one-time copy of files: keep the exported files, they are your backup.

**Google Calendar**, on a computer (the export is not in the phone app) *(verify)*:

1. Open <https://calendar.google.com> signed in to the right account.
2. The gear (top right) → **Paramètres** (Settings).
3. In the left column, **Importer et exporter** (Import & export), then **Exporter** (Export).
4. A `.zip` downloads, with **one `.ics` per calendar** you own. Calendars shared with you, holidays and the
   birthdays calendar are usually **not** included *(verify)*; to get a shared calendar, its owner has to export it.
5. Put the `.zip` on the phone (cloud drive, cable, share) or keep it on the PC for the Windows app.

Everything at once: <https://takeout.google.com> → deselect all → tick **Agenda** (Calendar) → create the export;
the same `.ics` files come inside the archive.

**Proton Calendar**, on <https://calendar.proton.me> in a browser *(verify)*:

1. Gear → **Tous les paramètres** (All settings) → **Calendriers** (Calendars).
2. Choose the calendar and its **Exporter** (Export) action: an `.ics` downloads. Repeat for each calendar.
3. Put the `.ics` file(s) on the device.

Proton encrypts the calendar end to end: the export happens in your browser after decrypting, so the file itself is
**not encrypted**; treat it like your agenda in plain text.

**Importing them into the app**:

1. Agenda → menu ⋮ → **Importer un fichier .ics** and pick the `.zip` (Google) or the `.ics` files. Opening an `.ics`
   from a mail attachment or the file manager starts the same dialog.
2. The preview lists each calendar with its number of events and series and its date range. Rename the new
   calendars, pick their colours, or send one into an existing calendar.
3. By default **everything** is imported; tick **"depuis le …"** only to skip old history (the first sync of the
   folder is slower with thousands of events).
4. **Importer**. The report says how many events were imported and how many items were ignored.
5. Check a few things you know: a recurring event (its next three occurrences), an all-day event, an event in
   another time zone, a birthday or a holiday.
6. Something wrong? **Annuler l'import** (in the report, or later from menu ⋮ → *Imports passés*) removes everything
   that import created.

Importing again later is safe: events are matched by their original ID and the default "Mettre à jour" updates the
existing ones and adds the new ones. What is lost or changes: invitation answers (attendees become a text list),
colours of single events, attachments, Google Meet buttons (the link stays as text), and anything that is not
standard iCalendar.
## Contacts

The "Contacts" module (bottom bar on the phone, left rail on the PC) keeps an address book as one note per contact
in `contacts/`, kept out of the notes list: `contacts/<lastname>-<firstname>-<id6>.md`, name fixed at creation.

```yaml
---
id: p4x8q1
title: Marie Dupont            # maintained by the app: "<first> <last>"
first_name: Marie
last_name: Dupont
phones:
  - +33 6 12 34 56 78 (mobile)   # as typed; optional label: mobile, domicile, travail, autre or free text
  - +33 1 23 45 67 89 (domicile)
emails:
  - marie@example.org
address: 12 rue de la Paix, 75002 Paris     # one free-text line
birthday: 1990-05-14           # --05-14 when the year is unknown
favorite: true
uid: 8c1f…@google              # vCard UID, the key of a re-import
import: 20261101-101200-k3f9a2 # only on files created by an import, so it can be undone
created: 2026-11-01T10:12:00+01:00
updated: 2026-11-01T10:12:00+01:00
---
Free text shown as "Notes".
```

- Either name may be empty (a company, a nickname); unknown frontmatter keys are kept verbatim.
- Numbers are never rewritten; matching and search use a normalised form: `06 12 34 56 78`, `+33 6 12 34 56 78`,
  `0033 6 12 34 56 78` and `(06) 12-34-56-78` are the same number, and searching `0612` finds `+33 6 12…`.
- The list is sorted by last name then first name ("Prénom d'abord" in the settings, a setting of each device),
  accents and case ignored, with a "Favoris" section, letter headers (a draggable letter scroller on the phone) and
  a search on names, digits, e-mails, the address and the notes.
- Phone: tap a number to open the dialer with it (nothing is dialled), the SMS icon for a message, an e-mail for the
  mail app, the address for the maps app; a long press copies. The PC shows copy buttons only.
- **Doublons** groups contacts with the same normalised number or the same name; per group, "Fusionner" keeps one
  (it gets the others' numbers, e-mails and notes) or "Ignorer". Never automatic. Deleting and merging can be undone
  from the snackbar.
- **Import** of a `.vcf` (Contacts menu "Importer des contacts", or opening / sharing a `.vcf` from another app on
  the phone): vCard 2.1, 3.0 and 4.0, quoted-printable with a charset (older Android exports), folded lines, Google's
  and Apple's grouped labels (`item1.TEL` + `item1.X-ABLabel`), several numbers of a type, `FN` without `N`, company-only
  cards, `BDAY` as `1990-05-14`, `19900514` or `--0514`. `ORG`/`TITLE` become a "Société : …" line of the notes; photos,
  URLs, `X-…` and the like are left out and counted in the report. A card that cannot be read is skipped and counted.
  The preview gives the number of cards, of likely duplicates and of cards without a name or number; duplicates are
  kept both (default, nothing can be lost), skipped, or merged into the contact with the same UID or number.
  **"Annuler l'import"** removes exactly the files that import created and puts back the contacts it updated.
- **Export**: "Exporter les contacts" writes one `contacts.vcf` (vCard 3.0); one contact is shared (phone) or saved
  (PC) as a `.vcf`. Export then import gives the same contacts.
- The vCard reader and writer are small hand-written ones (`contacts/VCard.kt`, `core/vcard.py`), tested against the
  synthetic vectors of `testdata/contacts/` by both the JUnit and the pytest suites.

### Contacts in the phone (Android)

Off by default. "Mes contacts dans le téléphone (appels, SMS, app Contacts)" in the settings asks for the contacts
permissions and syncs the contacts with the phone's own contacts, both ways, under an account "Mémento" (type
`app.memento.contacts`, visible in the phone's Settings → Accounts): the dialer, the messaging apps and the car show
the names, and a contact created, edited or deleted in the phone's contacts app is created, edited or deleted in the
files too (so on the PC as well, through the synced folder).

- **Mémento as the storage of the phone's contacts app.** The sync adapter declares `supportsUploading="true"` and
  `contacts.xml` an `EditSchema` (name, phones with their type, e-mails, one formatted address, the birthday with an
  optional year, a note; `photo` and every part of the name are declared because the platform rejects the account
  type otherwise: a prefix and a middle name go into the first name, a suffix into the last name, while a photo and
  a phonetic name stay on the phone only; e-mails and the address offer a single type, since the files keep none): the account is writable, so the contacts app lists "Mémento" when a contact is created ("save to") and
  edits its contacts with those fields. In Samsung Contacts, set **Emplacement de stockage par défaut** to Mémento
  (Contacts → ≡ → Paramètres *(verify)*) so "Créer un nouveau contact" in the dialer saves there.
- **One raw contact per file**: `SOURCE_ID` = the contact id, `SYNC1` = a hash of the mirrored fields.
- **Phone → files**, on every run: a raw contact with no `SOURCE_ID` was created on the phone and becomes a new
  contact file; a `DIRTY` one was edited and its file takes the phone's fields (the notes body is the note field;
  unknown frontmatter keys, attachments and a number's own spelling or label are kept when the phone left it as it
  was; phone types without a Mémento label keep a French one: "fax travail", "principal"…; what a file has no field
  for, a second address or a date in a form not read, goes into the notes); a `DELETED` one was deleted, its file
  goes to an app-private trash and the app shows "Contact supprimé sur le téléphone : Annuler" the next time it is
  open; the trash keeps it 7 days from that moment. A file edited since the last run wins over a deletion on the phone.
- **Files → phone** as before, by hash: insert, rewrite (the photo and other rows the app does not handle are kept),
  remove when the file is gone. A file deleted while the phone edited its contact comes back from the phone.
- **Both sides changed** since the last run: the most recent change wins (the file's modification time against the
  phone's last-updated time of the contact), and the other version's differing fields are appended to the notes
  ("Version du téléphone remplacée le … :" or "Version de Mémento remplacée le … :"), so nothing is lost.
- **When it runs**: app start and resume, a change of the files, after an edit or an import, a change of the phone's
  contacts while the app is alive (a `ContentObserver`), and, app closed, when Android starts the account's sync
  adapter because contacts are dirty (automatic sync must be on in the phone's settings). One run at a time; each one
  is idempotent, applies the files first, then the provider in batches of at most 400 operations, all off the main
  thread. The settings show "Dernière synchro des contacts : …" with the counts, or the error.
- **Safety**: an empty `contacts/` folder never removes or rewrites anything on the phone (a folder that cannot be
  read looks empty); only contacts newly created on the phone are still saved. More than 10 contacts deleted on the
  phone in one run, over a quarter of them (moved to another account, storage cleared), are not trusted: the files
  stay and come back on the phone. A file listed but not readable, or changed on disk while the run was deciding,
  stops the run (done again later) rather than being taken as deleted or overwritten; a contact the phone edited
  never loses its fields to a raw contact that reads back empty. A run waits for a Synchrotron sync to end. A run
  that failed half way is simply done again (a contact created on the phone whose file was written is not created
  twice). Turning the setting off first saves what the phone changed, and the account and its copies are removed
  only after that run succeeded; removing the account in the phone's settings turns the setting off.

Pure decisions in `contacts/MirrorSync.kt` and `contacts/MirrorPlan.kt` (JVM-tested against a fake provider),
app-private state and trash in `contacts/MirrorStore.kt`, provider glue in `contacts/ContactsMirror.kt`, the
authenticator and sync adapter in `contacts/MirrorServices.kt`.

### Mémento as the contacts app (Android)

Android has no "default contacts app" role, so Mémento declares the contact intents the stock contacts app handles
and the chooser's **Toujours** makes it the one they open (Settings → Contacts says so):

- `INSERT` (`dir/contact`, `dir/raw_contact`) — the dialer's "Créer un contact", "Ajouter aux contacts" in messaging —
  opens the contact editor prefilled from the `ContactsContract.Intents.Insert` extras (name, three numbers with
  their type, three e-mails, address, notes, company, the `DATA` rows). `INSERT_OR_EDIT` (`item/contact`) first
  offers "Nouveau contact" or "Ajouter à un contact existant" (a searchable list; numbers and e-mails are added
  without duplicates, what has no field left goes to the notes).
- `VIEW`, `QUICK_CONTACT`, `EDIT` on `contacts/lookup/…`, `contacts/<id>`, `raw_contacts/<id>`: when the contact has a
  raw contact of the mirror's account, its `SOURCE_ID` names the Mémento contact, whose detail (or editor) opens. A
  contact of another account offers an import (its data rows read into the editor). Needs READ_CONTACTS, asked then
  if missing.
- `contacts.xml` declares `inviteContactActivity`, the only standard hook: a contacts app honouring it shows
  "Ajouter à Mémento" on the contacts of other accounts. The mirrored contacts themselves are editable there (its
  `EditSchema`, see above), and those edits come back into the files.

Started just for such an action, the app finishes once the contact is saved or the action cancelled, back to the
app that asked. Pure parsing in `contacts/ExternalContact.kt` (tested), provider glue and flow in
`contacts/ContactRequestFlow.kt`.

### Moving the contacts out of Google

The menu names below are written from memory and must be checked against the real screens: items marked *(verify)*
are the ones to check first.

1. On a computer (or in the phone's browser, desktop mode) *(verify)*, open <https://contacts.google.com>.
2. In the left column, **Exporter** (Export).
3. Choose **Tous les contacts** (or a label, or tick contacts first) and the format **vCard (pour les contacts iOS)**.
   vCard is the right one: it is the standard the app reads. (**Google CSV** also exists, but it is a second-best
   format for this app.)
4. **Exporter**: a `contacts.vcf` file downloads. Keep it: it is your backup.
5. Note the number of contacts shown in Google, to compare afterwards. Only the contacts you saved are exported; the
   **"Autres contacts"** (people Google collected from your mails) are not, unless you copy them into "Mes contacts"
   first *(verify)*.

Then, in the app:

1. Contacts → menu → **Importer des contacts** and pick `contacts.vcf` (opening the `.vcf` from a mail or the file
   manager does the same).
2. The preview shows the number of contacts, possible duplicates of what is already in the app, and cards without a
   name or number. Pick the duplicate policy (the default for a fresh import is "Garder les deux", which cannot lose
   anything).
3. **Importer**, then compare the number with Google's.
4. Use the **Doublons** tool if both versions exist.
5. **Annuler l'import** undoes it if something is off.

Until the copy into the phone is turned on and checked, the phone's dialer and messages still use the contacts kept
in Google: do not turn Google's contact sync off before that. Once it works: Settings → Accounts → Google → Account
sync → Contacts off *(verify)*, and only after keeping a fresh vCard, delete the contacts from Google.

Contacts that only exist on the phone: in the phone's contacts app, menu → manage contacts → import/export → export
to a `.vcf` file *(verify, the path differs by manufacturer)*; the same import applies.

## Status

Implemented on both platforms: notes (checklists, colours, pin, labels, search, scans, drawings, attachments),
activities (FIT/GPX import, analysis, 3D/2D map), agenda (views, recurrence, time zones, ICS import/export),
contacts (vCard import/export, duplicates). Android only: activity recording with route following and offline
maps, agenda reminders and widgets, the contacts mirror into the phone. Self-update on both.

## Windows client

`windows/` holds a Python 3.12 + PySide6 desktop client that works on the same notes folder
(pick it at first launch; it is remembered). It reads and writes the same Markdown files,
atomically, watches the folder for changes made by the sync tool, and updates itself from
the `latest` release (checks size and SHA-256, swaps the exe, restarts).

```
cd windows
pip install -r requirements.txt pytest
python -m pytest tests -q
python run.py
```

The CI builds `Memento.exe` with `pyinstaller --noconfirm memento.spec` (one file; the spec keeps only the
Qt parts the app loads, Qt WebEngine for the map included) and publishes it next to the APK in the
`latest` release. The exe is not signed, so SmartScreen may warn the first time.

## Web app (self-hosted)

`webapp/` serves the same folder to a browser, from a Docker container on the NAS that holds the synced folder
(the WebDAV server's data): the Windows client's pure modules (`windows/memento_win/core`) are imported as is, the
files are written with the same atomic rule, and a change made by a sync shows up live (the folder is polled every
few seconds, changes are pushed to the page). It has its own login (throttled, sessions of 7 days, revoked at logout
or when the password changes) on top of whatever sits in front (Cloudflare Access). The login is an Apache `htpasswd`
file mounted at `/auth/htpasswd`, like the Synchrotron server's (bcrypt from `htpasswd -B`; apr1 and `{SHA}` are read
too), or else `MEMENTO_USER` / `MEMENTO_PASSWORD` in `.env`, never both.

```
webapp/server/memento_web   Starlette + uvicorn: auth, API, live events, map host (one folder per module)
webapp/ui                   Svelte 4 + Vite: shell, one folder per module (src/modules/<module>)
webapp/tests                pytest, synthetic data only
webapp/Dockerfile           built from the repository root: docker build -f webapp/Dockerfile .
```

Activités on the web: the list reads only the notes' `activity:` summaries; a track is parsed and analysed (core
`analysis.py`) only when it is opened, and what the page draws (figures, charts, map data thinned to 5000 points)
is cached as JSON, up to 40 in memory and the rest in `/config/cache/activities`. FIT / GPX
imports and the optional altitude completion (opentopodata.org, asked for explicitly) run in the background with
their progress shown. The map is the shared page in an `<iframe>` of the same site, driven through `postMessage`
(`{target: 'smap', call, args}` in, `{source: 'smap', …event}` out, same origin only), one frame kept for every activity.

Speed: at startup a low-priority thread (`warm.py`) reads the whole folder into memory (parsed notes, activities,
contacts, events), builds the agenda's ±3–4 months around today and prepares the 10 most recent activities'
analyses; the server answers from the first second meanwhile. These indexes stay in memory (the idle trim drops only
the other analyses and the agenda's windows) and are saved in `/config/cache/index-*.bin` (signed with the session
secret, each entry checked against its file's time and size), so a restart re-reads only what changed. A change seen
by the folder poll is parsed before it is pushed to the pages. In the browser, the modules' lists are kept in memory
and refreshed in the background on a change, and pointing at a rail entry fetches its page and list ahead of the
click. About 50–60 MB of memory for a folder of a few thousand files (`MEMENTO_WARM=false` turns the warm-up off).

Deployment: `webapp/docker-compose.example.yml` and `webapp/.env.example`. The CI tests both halves and publishes
`ghcr.io/krustylebot/memento-web:latest` (and `:<build>`) from `main`. Development: `python -m memento_web` in
`webapp/server` (with `MEMENTO_USER`, `MEMENTO_PASSWORD`, `MEMENTO_DATA`, `MEMENTO_CONFIG`) and `npm run dev` in
`webapp/ui`; `python webapp/tests/synthetic.py <folder>` makes a test folder.

## Build

```
./gradlew assembleRelease
```

Local builds use Android's default debug key (release builds fall back to it without the CI key). CI (`.github/workflows/build-apk.yml`) signs
builds with a private key stored in the `release` GitHub environment and publishes the
rolling `latest` release that the in-app updater reads.

### One-time signing setup

```bash
keytool -genkeypair -keystore griffonote.jks -alias griffonote \
  -keyalg RSA -keysize 4096 -validity 36500 \
  -dname "CN=Mémento, O=Mémento, C=FR"
base64 -w0 griffonote.jks > griffonote.jks.b64
```

Then in GitHub → Settings → Environments → `release` (deployment branches: `main` only), add
the secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
Back up the `.jks` and passwords offline; a lost key means no more updates.
