# Import guides (draft)

Part of [the specs](README.md). These are the guides the app (or its README) will carry once the import exists.
**The menu names below are written from memory of how Google and Proton were organised and must be checked against
the real screens when the guides are finalised: both services move their menus around.** Items marked *(verify)*
are the ones to check first.

Principle: everything is a **one-time copy of files**, not a link. Export a file from the service, import it into the
app. Keep the exported files: they are your backup.

## 1. Google Calendar → the app

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

## 2. Proton Calendar → the app

On <https://calendar.proton.me> in a browser *(verify)*:

1. Gear → **Tous les paramètres** (All settings) → **Calendriers** (Calendars).
2. Choose the calendar and use its **Exporter** (Export) action; an `.ics` file downloads. Repeat for each calendar.
3. Put the `.ics` file(s) on the device.

Proton encrypts the calendar end to end: the export happens in your browser, after decrypting, so it needs you to be
signed in; the file itself is **not encrypted**, treat it like your agenda in plain text.

## 3. Importing the calendar files into the app

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

## 4. Google Contacts → the app

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

## 5. Importing the contacts into the app

1. Contacts → menu → **Importer des contacts** and pick `contacts.vcf`. (Opening the `.vcf` from a mail or the file
   manager does the same.)
2. The preview shows the number of contacts, possible duplicates of what is already in the app, and cards without
   a name or number. Pick the duplicate policy (the default for a fresh import is "garder les deux", which cannot lose anything).
3. **Importer**, then compare the number with Google's.
4. Use the **Doublons** tool if both versions exist.
5. **Annuler l'import** undoes it if something is off.

Until the optional "mirror" exists (part of the same delivery), the phone's own dialer and messages still use the
contacts kept in Google: nothing changes there. Do not turn Google's contact sync off before the mirror works and has been checked.

### Exporting contacts from the phone itself (alternative to Google)

On the phone, in the contacts app: menu → manage contacts → import/export → export to a `.vcf` file *(verify, the
path differs by manufacturer)*. The same import applies. Useful if some contacts only exist on the phone.

## 6. Getting out of the app

- Calendars: Agenda → menu → **Exporter en .ics** (one calendar) or **Tout exporter** (a zip), readable by Google, Proton, Thunderbird, Apple, Outlook.
- Contacts: Contacts → menu → **Exporter les contacts** → `contacts.vcf`, readable by anything.
- Everything else is Markdown files in a folder: no export needed.
