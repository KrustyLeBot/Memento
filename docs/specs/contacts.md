# Contacts

Part of [the specs](README.md). Specification only; nothing is implemented.

## 1. Goals and non-goals

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

## 2. Data model

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

## 3. Features

### 3.1 List

- Alphabetical by last name then first name (setting: first name first), sticky letter headers and a draggable
  letter scroller on Android; accent- and case-insensitive.
- **Favoris** section on top when any is ticked.
- Search box: matches names, phone digits (searching `0612` finds `+33 6 12…`), emails, and the notes body.
- Count in the header ("236 contacts").

### 3.2 Contact detail

Name, then one row per phone and per email with the matching action:

| Row | Tap | Long press |
|---|---|---|
| Phone | **Appeler**: opens the dialer with the number filled in (`ACTION_DIAL`, needs no permission and cannot place a call by accident) | Copy; SMS |
| Phone (SMS icon) | **SMS**: `smsto:` | |
| Email | `mailto:` | Copy |
| Address | opens the maps app | Copy |

Menu: Modifier · Partager (as a `.vcf`) · Ajouter/retirer des favoris · Supprimer (with undo).

### 3.3 Create / edit

Fields: Prénom · Nom · Téléphone (+ type, "Ajouter un numéro") · E-mail (+ "Ajouter") · Adresse · Anniversaire
(optional date picker, year optional) · Notes. Saving with only a number is allowed. Same discard/undo patterns as notes.

### 3.4 Duplicates

Tool "Doublons" (in the menu): groups by identical normalised phone or identical name; per group, "Fusionner"
(keep one, union of numbers/emails/notes) or "Ignorer". Never automatic.

### 3.5 Birthday in the agenda

If wanted (open question 6): the `birthday` field shows in the agenda every year ([agenda.md](agenda.md), 4.5).

## 4. Import and export

### 4.1 Import vCard (`.vcf`)

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

### 4.2 Export

- One contact: share as `.vcf` (vCard 3.0, widest compatibility).
- All contacts: **"Exporter les contacts"** → a single `contacts.vcf` (backup, and the way out of the app).
- Round trip tested: export → import into an empty app → identical contacts.

## 5. The phone's own contacts system: what is and is not possible

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
| **L1 Mirror (recommended)** | The files stay the source of truth; the app **writes a copy into the system provider**, under its own account ("Contacts Scriptorium" or whatever the app is called), one way (files → provider) | Caller ID, names in SMS/WhatsApp, the car, the watch all work. Delete the account and the copies disappear. | moderate: an account type (a stub authenticator), a sync step, the `READ_CONTACTS`/`WRITE_CONTACTS` permissions |
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

## 6. Migration from Google (the plan, steps in [import-guides.md](import-guides.md))

1. Export the contacts from Google Contacts as vCard (keep that file: it is the backup).
2. Import into the app (phase 1); check the count against Google's ("236 contacts").
3. Use the app's contacts for a few days next to Google's (no conflict, nothing is mirrored yet).
4. When phase 4 exists: turn on the mirror, check the checklist above, then turn off Google's contact sync on the
   phone (Settings → Accounts → Google → Account sync → Contacts) and, only after keeping a fresh vCard, delete the contacts from Google.

## 7. Windows

The same module: list, search, detail, create/edit, duplicates, vCard import/export. Call/SMS buttons are hidden
(or copy the number). Nothing is mirrored anywhere.

## 8. Privacy

The contacts are personal data in a **public repository's tests**: tests use synthetic contacts only. The app
never sends contacts anywhere; import and export are local files.

## 9. Test plan (when implemented)

- vCard vectors (synthetic, shaped like exports from Google, Android 2.1 quoted-printable, Proton, Apple): names
  with accents and apostrophes, several phones, grouped `item1.` properties, folded lines, `FN` without `N`, a card
  with only a company, `BDAY` in three formats, empty cards.
- Phone normalisation: `06 12 34 56 78`, `+33 6 12 34 56 78`, `0033612345678`, `(06) 12-34-56-78` all equal.
- Search by digits, accents, case; letter headers; sorting with empty names.
- Round trip export → import; undo of an import removes exactly the created files.
- L1 on the phone: the checklist of section 5.
