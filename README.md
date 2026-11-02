# Scriptorium

A simple Google Keep replacement for Android. Every note is a plain Markdown file in a
folder (`Documents/Scriptorium/` by default), so notes can be read and edited on a PC
(Obsidian, any editor) and synced by a separate app. The user never sees Markdown on the phone.

## Storage format

```
Scriptorium/
├── courses-k3f9a2.md      # one file per note: YAML frontmatter + Markdown body
├── attachments/           # scanned PDFs (`pdf:`), attached files (`attachments:`) and activity tracks
├── activities/            # the "Activités" folder: one note per activity, kept out of the regular list
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
listed in the note's `attachments:` like any file: any viewer opens it, and Scriptorium reads back its own paths
(`<path d="M… L…" stroke="#rrggbb" stroke-width="…">`, page marked `data-scriptorium="1"`, older drawings `data-griffonote="1"`) to edit it again. Tap
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
  "Importer FIT / GPX" button. Recording (Android only): GPS in a foreground service, a log written as it goes
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
- The map is OpenStreetMap raster tiles drawn by hand (no map library, no web engine), cached on disk (100 MB at
  most, oldest purged) and fetched only online; offline only the track is drawn. Tiles are requested with an
  identifying User-Agent, as the tile usage policy asks.
- FIT is read and written with a small implementation of the public format (`Fit.kt`, `fit.py`), no SDK.
- Tests use synthetic tracks only: this repository is public and a real track reveals where you live.

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

Off by default. "Afficher mes contacts dans le téléphone (appels, SMS…)" in the settings asks for the contacts
permissions and copies the contacts into the phone's own contacts, under an account "Scriptorium" (type
`app.scriptorium.contacts`, visible in the phone's Settings → Accounts), so the dialer, the messaging apps and the car
show the names. It is one way: the files stay the truth, edits made in the phone's contacts app are overwritten at the
next update, and the app is where a contact is edited. One raw contact per file (`SOURCE_ID` = the contact id,
`SYNC1` = a hash of what is copied), updated at app start, after a change of the files, after an import and when the
setting is turned on; nothing is rewritten when nothing changed. An empty `contacts/` folder never wipes the copies
(a folder that cannot be read looks empty). Turning the setting off, or removing the account in the phone's
settings, removes the copies.

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

Milestones 1–2 and 6 (partly) are implemented: text notes, checklists (drag to reorder,
checked items folded), colours, pin, labels, full-text search,
grid/list view, share in/out (text), conflict-file badge, external-change handling,
settings, self-update. Images, drawings and reminders (milestones 3–5) are not done yet.

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

The CI builds `Scriptorium.exe` (PyInstaller, one file) and publishes it next to the APK in the
`latest` release. The exe is not signed, so SmartScreen may warn the first time.

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
  -dname "CN=Scriptorium, O=Scriptorium, C=FR"
base64 -w0 griffonote.jks > griffonote.jks.b64
```

Then in GitHub → Settings → Environments → `release` (deployment branches: `main` only), add
the secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
Back up the `.jks` and passwords offline; a lost key means no more updates.
