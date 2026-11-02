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
- The map is one web page shared by both apps (`web/map/`, MapLibre GL JS 4.7.1 vendored): Plan IGN on the
  Terrarium elevation model, in 3D (default) as a square block cut out of the relief around the track (side = the
  track's larger side + 25 %, at least 1 km), or flat in 2D; the switch is a native control and is remembered. The
  track is coloured by its speed relative to the mean (red slow, yellow mean, green fast), ski days keep their
  descents/climbs colours; up to 12 summits (IGN BD TOPO + IGN altimetry, spread out) are labelled. The page asks
  its host for everything under one base URL (`t/plan|dem/{z}/{x}/{y}`, `peaks?w&s&e&n`): the host fetches with an
  identifying User-Agent and keeps a disk cache (300 MB, least recently used purged), so what was seen once works
  offline. Where IGN has no tile (abroad) the OpenStreetMap tile is served instead. `web/map/dev/serve.py` serves
  the page with these endpoints for development (`python web/map/dev/serve.py`, then drive `window.SMap`).
- FIT is read and written with a small implementation of the public format (`Fit.kt`, `fit.py`), no SDK.
- Tests use synthetic tracks only: this repository is public and a real track reveals where you live.

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

The CI builds `Scriptorium.exe` with `pyinstaller --noconfirm scriptorium.spec` (one file; the spec keeps only the
Qt parts the app loads, Qt WebEngine for the map included) and publishes it next to the APK in the
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
