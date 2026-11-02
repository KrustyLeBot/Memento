"""Contacts: one note per contact in contacts/ (same format and algorithms as the Android app's contacts/ package).

Pure Python, no Qt: unit-tested on its own against the shared vectors of testdata/contacts/.
"""
from __future__ import annotations

import calendar
import os
import re
import time
from dataclasses import dataclass, field, replace
from datetime import date
from enum import Enum
from pathlib import Path

from . import atomic
from . import note as fmt
from .note import Note

DIR = "contacts"

MOBILE, HOME, WORK, OTHER = "mobile", "domicile", "travail", "autre"
STANDARD_LABELS = [MOBILE, HOME, WORK, OTHER]


def clean_label(label: str | None) -> str | None:
    """A label as it can be stored after a number: without parentheses, and with a letter in it, or (2) would read
    back as part of the number. None when nothing is left."""
    if label is None:
        return None
    s = " ".join(label.replace("(", " ").replace(")", " ").split())
    return s if any(c.isalpha() for c in s) else None

_KEYS = {"first_name", "last_name", "phones", "emails", "address", "birthday", "favorite", "uid", "import"}
_KEY_RE = re.compile(r"^([A-Za-z_][\w-]*):[ \t]*(.*)$")
_LABEL_RE = re.compile(r"^(.*?)\s*\(([^()]*[^\W\d_][^()]*)\)\s*$")
_CONFLICT_COPY = re.compile(r".+\.md\.conflict\d+$")


def now_ms() -> int:
    return int(time.time() * 1000)


# ---- model ----

@dataclass(frozen=True)
class Phone:
    number: str
    label: str | None = None

    @property
    def normalized(self) -> str:
        return normalize(self.number)

    def encode(self) -> str:
        label = clean_label(self.label)
        return f"{self.number.strip()} ({label})" if label else self.number.strip()

    @staticmethod
    def decode(raw: str) -> "Phone":
        m = _LABEL_RE.match(raw.strip())
        return Phone(raw.strip()) if m is None else Phone(m[1].strip(), m[2].strip())


@dataclass(frozen=True)
class Birthday:
    year: int | None
    month: int
    day: int

    def encode(self) -> str:
        return f"--{self.month:02d}-{self.day:02d}" if self.year is None else f"{self.year:04d}-{self.month:02d}-{self.day:02d}"

    def occurrence(self, in_year: int) -> date:
        """The date it falls on in [in_year]; a 29 February falls on the 28th in other years."""
        if self.month == 2 and self.day == 29 and not calendar.isleap(in_year):
            return date(in_year, 2, 28)
        return date(in_year, self.month, self.day)

    def age_in(self, in_year: int) -> int | None:
        if self.year is None or in_year - self.year < 0:
            return None
        return in_year - self.year

    @staticmethod
    def of(day: int, month: int, year: int | None) -> "Birthday | None":
        if not 1 <= month <= 12 or day < 1:
            return None
        if year is not None and not 1 <= year <= 9999:
            return None
        if day > calendar.monthrange(year if year is not None else 2000, month)[1]:
            return None
        return Birthday(year, month, day)

    @staticmethod
    def parse(raw: str | None) -> "Birthday | None":
        """1990-05-14, 19900514, --05-14, --0514 (a time part is ignored); None if not a valid date."""
        s = (raw or "").strip()
        m = re.fullmatch(r"(\d{4})-?(\d{2})-?(\d{2})(?:[T ].*)?", s)
        if m:
            y = int(m[1])
            return Birthday.of(int(m[3]), int(m[2]), None if y in (1604, 0) else y)  # Apple writes 1604 for "unknown"
        m = re.fullmatch(r"--(\d{2})-?(\d{2})", s)
        if m:
            return Birthday.of(int(m[2]), int(m[1]), None)
        return None


@dataclass
class Contact:
    id: str = ""
    path: str = ""
    first_name: str = ""
    last_name: str = ""
    phones: list[Phone] = field(default_factory=list)
    emails: list[str] = field(default_factory=list)
    address: str | None = None
    birthday: Birthday | None = None
    favorite: bool = False
    uid: str | None = None
    import_id: str | None = None
    notes: str = ""
    created: int = 0
    updated: int = 0
    conflict: bool = False
    base: Note | None = None

    def copy(self, **changes) -> "Contact":
        return replace(self, **changes)

    @property
    def name(self) -> str:
        return " ".join(x for x in (self.first_name.strip(), self.last_name.strip()) if x)

    @property
    def display_name(self) -> str:
        if self.name:
            return self.name
        if self.phones:
            return self.phones[0].number
        return self.emails[0] if self.emails else "(Sans nom)"

    @property
    def is_empty(self) -> bool:
        return (not self.name and not self.phones and not self.emails and not (self.address or "").strip()
                and self.birthday is None and not self.notes.strip())

    def fields(self) -> dict:
        """The comparable fields, in the shape of the test vectors."""
        return {"first_name": self.first_name, "last_name": self.last_name, "phones": [p.encode() for p in self.phones],
                "emails": list(self.emails), "address": self.address,
                "birthday": self.birthday.encode() if self.birthday else None, "notes": self.notes, "uid": self.uid,
                "favorite": self.favorite}


# ---- file format ----

def is_contact_path(path: str) -> bool:
    return path.startswith(f"{DIR}/")


def _slug_or_empty(s: str) -> str:
    return fmt.slug(s) if any(c.isascii() and c.isalnum() for c in fmt.fold(s)) else ""


def file_name_for(c: Contact, contact_id: str) -> str:
    """contacts/dupont-marie-p4x8q1.md; fixed at creation, never renamed."""
    parts = [p for p in (_slug_or_empty(c.last_name), _slug_or_empty(c.first_name)) if p]
    stem = "-".join(parts)[:40].strip("-") or "contact"
    return f"{DIR}/{stem}-{contact_id}.md"


def _blocks(lines: list[str]) -> dict[str, list[str]]:
    out: dict[str, list[str]] = {}
    current: list[str] | None = None
    for line in lines:
        m = _KEY_RE.match(line)
        if m and not line.startswith((" ", "-")):
            current = [line]
            out[m[1]] = current
        elif current is not None:
            current.append(line)
    return out


def _loose(lines: list[str]) -> list[str]:
    for i, line in enumerate(lines):
        if _KEY_RE.match(line) and not line.startswith((" ", "-")):
            return lines[:i]
    return list(lines)


def from_note(note: Note) -> Contact:
    blocks = _blocks(note.extra)

    def scalar(key: str) -> str | None:
        if key not in blocks:
            return None
        first = _KEY_RE.match(blocks[key][0])[2].strip()
        return fmt.unquote(first) if first else None

    def items(key: str) -> list[str]:
        if key not in blocks:
            return []
        lines = blocks[key]
        first = _KEY_RE.match(lines[0])[2].strip()
        if first:
            return fmt.parse_list(first)
        values = [fmt.unquote(x.strip().removeprefix("-").strip()) for x in lines[1:] if x.strip().startswith("-")]
        return [v for v in values if v]

    return Contact(
        id=note.id, path=note.path,
        first_name=scalar("first_name") or "", last_name=scalar("last_name") or "",
        phones=[p for p in (Phone.decode(x) for x in items("phones")) if p.number],
        emails=items("emails"),
        address=(scalar("address") or "").strip() and scalar("address") or None,
        birthday=Birthday.parse(scalar("birthday")),
        favorite=(scalar("favorite") or "").lower() == "true",
        uid=(scalar("uid") or "").strip() and scalar("uid") or None,
        import_id=(scalar("import") or "").strip() and scalar("import") or None,
        notes=note.body, created=note.created, updated=note.updated, conflict=note.conflict, base=note,
    )


def to_note(c: Contact) -> Note:
    own: list[str] = []
    if c.first_name.strip():
        own.append("first_name: " + fmt.yaml_quote(c.first_name.strip()))
    if c.last_name.strip():
        own.append("last_name: " + fmt.yaml_quote(c.last_name.strip()))
    if c.phones:
        own.append("phones:")
        own.extend("  - " + fmt.yaml_quote(p.encode()) for p in c.phones)
    if c.emails:
        own.append("emails:")
        own.extend("  - " + fmt.yaml_quote(e.strip()) for e in c.emails)
    if c.address and c.address.strip():
        own.append("address: " + fmt.yaml_quote(c.address.replace("\n", ", ").strip()))
    if c.birthday:
        own.append("birthday: " + fmt.yaml_quote(c.birthday.encode()))
    if c.favorite:
        own.append("favorite: true")
    if c.uid:
        own.append("uid: " + fmt.yaml_quote(c.uid))
    if c.import_id:
        own.append("import: " + fmt.yaml_quote(c.import_id))
    base = c.base or Note(path=c.path, id=c.id, created=c.created, updated=c.updated)
    others = [line for key, block in _blocks(base.extra).items() if key not in _KEYS for line in block] + _loose(base.extra)
    title = c.name or (c.phones[0].number if c.phones else (c.emails[0] if c.emails else ""))
    return base.copy(path=c.path, id=c.id, has_id=True, title=title, body=c.notes.strip("\n"),
                     created=c.created, updated=c.updated, extra=own + others)


# ---- phone numbers ----

def normalize(raw: str) -> str:
    """+<country><number> for an international or French national number, bare digits otherwise.
    06 12 34 56 78, +33 6 12 34 56 78, 0033 6 12 34 56 78 and (06) 12-34-56-78 are the same number."""
    s = raw.strip()
    digits = "".join(c for c in s if "0" <= c <= "9")
    if not digits:
        return ""
    if s.startswith("+"):
        intl = digits
    elif digits.startswith("00"):
        intl = digits[2:]
    elif len(digits) == 10 and digits.startswith("0"):
        intl = "33" + digits[1:]
    else:
        intl = None
    if intl is not None and intl.startswith("330") and len(intl) == 12:
        intl = "33" + intl[3:]
    return "+" + intl if intl is not None else digits


def search_forms(raw: str) -> list[str]:
    n = normalize(raw)
    digits = n.removeprefix("+")
    out = [digits, "".join(c for c in raw if "0" <= c <= "9")]
    if n.startswith("+33"):
        out.append("0" + digits[2:])
    return list(dict.fromkeys(x for x in out if x))


def is_number_query(query: str) -> bool:
    q = query.strip()
    return any("0" <= c <= "9" for c in q) and all(("0" <= c <= "9") or c in " +-.()/" for c in q)


def query_forms(query: str) -> list[str]:
    q = query.strip()
    digits = "".join(c for c in q if "0" <= c <= "9")
    out = [digits]
    if q.startswith("+"):
        pass
    elif digits.startswith("00"):
        out.append(digits[2:])
    elif digits.startswith("0") and len(digits) > 1:
        out.append("33" + digits[1:])
    return list(dict.fromkeys(x for x in out if x))


# ---- list: sorting, sections, search ----

FAVORITES = "Favoris"
OTHER_LETTER = "#"


def sort_key(c: Contact, first_name_first: bool) -> list[str]:
    names = [c.first_name, c.last_name] if first_name_first else [c.last_name, c.first_name]
    folded = [fmt.fold(n.strip()) for n in names if n.strip()]
    return folded or [fmt.fold(c.display_name)]


def letter(c: Contact, first_name_first: bool) -> str:
    first = sort_key(c, first_name_first)[0][:1]
    return first.upper() if first and "a" <= first <= "z" else OTHER_LETTER


def _order(c: Contact, first_name_first: bool):
    key = sort_key(c, first_name_first)
    return (letter(c, first_name_first) == OTHER_LETTER, key, c.id)


def sorted_contacts(contacts: list[Contact], first_name_first: bool) -> list[Contact]:
    return sorted(contacts, key=lambda c: _order(c, first_name_first))


def sections(contacts: list[Contact], first_name_first: bool) -> list[tuple[str, list[Contact]]]:
    """"Favoris" first when any is ticked, then one section per letter ("#" last)."""
    ordered = sorted_contacts(contacts, first_name_first)
    out: list[tuple[str, list[Contact]]] = []
    favorites = [c for c in ordered if c.favorite]
    if favorites:
        out.append((FAVORITES, favorites))
    for c in ordered:
        key = letter(c, first_name_first)
        if out and out[-1][0] == key and key != FAVORITES:
            out[-1][1].append(c)
        else:
            out.append((key, [c]))
    return out


def matches(c: Contact, query: str) -> bool:
    q = query.strip()
    if not q:
        return True
    if is_number_query(q):
        forms = query_forms(q)
        if any(f in s for p in c.phones for s in search_forms(p.number) for f in forms):
            return True
    hay = _searchable("\n".join([c.first_name, c.last_name, " ".join(c.emails), c.address or "", c.notes]))
    return all(word in hay for word in _searchable(q).split())


def _searchable(s: str) -> str:
    """Folded, apostrophes left out: oneill and o'neill find O’Neill."""
    return re.sub(r"['’ʼ`]", "", fmt.fold(s))


def search(contacts: list[Contact], query: str, first_name_first: bool) -> list[Contact]:
    return sorted_contacts([c for c in contacts if matches(c, query)], first_name_first)


def count_label(n: int) -> str:
    return f"{n} contact" if n <= 1 else f"{n} contacts"


@dataclass(frozen=True)
class BirthdayEntry:
    contact_id: str
    name: str
    birthday: Birthday


def birthdays(contacts: list[Contact]) -> list[BirthdayEntry]:
    """Contacts with a birthday, by month and day: the agenda's "Anniversaires" layer (expanded with occurrence())."""
    entries = [BirthdayEntry(c.id, c.display_name, c.birthday) for c in contacts if c.birthday and not c.conflict]
    return sorted(entries, key=lambda e: (e.birthday.month, e.birthday.day, fmt.fold(e.name)))


# ---- duplicates ----

_MIN_PHONE = 6


def name_key(c: Contact) -> str | None:
    names = sorted(fmt.fold(n.strip()) for n in (c.first_name, c.last_name) if n.strip())
    return " ".join(names) or None


def phone_keys(c: Contact) -> set[str]:
    return {n for n in (p.normalized for p in c.phones) if len(n.removeprefix("+")) >= _MIN_PHONE}


def duplicate_groups(contacts: list[Contact], first_name_first: bool = False) -> list[list[Contact]]:
    """Contacts sharing a normalised number or the same name (accents, case, order ignored), transitively."""
    parent = list(range(len(contacts)))

    def find(i: int) -> int:
        while parent[i] != i:
            parent[i] = parent[parent[i]]
            i = parent[i]
        return i

    seen: dict[str, int] = {}
    for i, c in enumerate(contacts):
        keys = [f"t:{k}" for k in phone_keys(c)] + ([f"n:{name_key(c)}"] if name_key(c) else [])
        for k in keys:
            if k in seen:
                a, b = find(seen[k]), find(i)
                if a != b:
                    parent[max(a, b)] = min(a, b)
            else:
                seen[k] = i
    groups: dict[int, list[Contact]] = {}
    for i, c in enumerate(contacts):
        groups.setdefault(find(i), []).append(c)
    result = [sorted_contacts(g, first_name_first) for g in groups.values() if len(g) > 1]
    return sorted(result, key=lambda g: _order(g[0], first_name_first))


def best(group: list[Contact]) -> Contact:
    """The contact of a group that holds the most, offered as the one to keep."""
    def score(c: Contact):
        return (len(c.phones) + len(c.emails) + (1 if c.address is not None else 0) + (1 if c.birthday else 0),
                len(c.notes), -c.created)
    return max(group, key=score)


def _other_name(o: Contact, c: Contact) -> bool:
    """[o] has a name with a word [c]'s name lacks ("Paul" against "Marie Dupont"; not "Marie" against it)."""
    mine, theirs = name_key(c), name_key(o)
    return bool(mine and theirs) and not set(theirs.split(" ")) <= set(mine.split(" "))


def merge(keep: Contact, others: list[Contact]) -> Contact:
    """[keep] with the union of the others; its id, file and uid stay. What has no room left (another name, a second
    address or birthday) goes to the notes, so a merge never loses a value."""
    c = keep
    for o in others:
        known = {p.normalized for p in c.phones}
        phones = list(c.phones)
        for p in o.phones:
            if p.normalized not in known:
                known.add(p.normalized)
                phones.append(p)
        mails = {e.lower() for e in c.emails}
        emails = list(c.emails)
        for e in o.emails:
            if e.lower() not in mails:
                mails.add(e.lower())
                emails.append(e)
        leftovers = []
        if _other_name(o, c):
            leftovers.append(f"Autre nom : {o.name}")
        if (o.address and o.address.strip() and c.address and c.address.strip()
                and fmt.fold(o.address.strip()) != fmt.fold(c.address.strip())):
            leftovers.append(f"Adresse : {o.address.strip()}")
        if o.birthday and c.birthday and o.birthday != c.birthday:
            leftovers.append(f"Anniversaire : {o.birthday.encode()}")
        # appended once: merging or re-importing the same text again changes nothing
        notes = c.notes
        for text in [o.notes.strip(), *leftovers]:
            if text and text not in notes:
                notes = "\n\n".join(x for x in (notes.strip(), text) if x)
        c = c.copy(first_name=c.first_name if c.first_name.strip() else o.first_name,
                   last_name=c.last_name if c.last_name.strip() else o.last_name,
                   phones=phones, emails=emails,
                   address=c.address if c.address and c.address.strip() else o.address,
                   birthday=c.birthday or o.birthday, favorite=c.favorite or o.favorite,
                   uid=c.uid or o.uid, notes=notes)
    return c


# ---- import ----

class DuplicatePolicy(Enum):
    KEEP_BOTH = "Garder les deux"
    SKIP = "Ne pas importer les doublons"
    UPDATE = "Mettre à jour (même UID ou numéro)"


@dataclass
class ImportPreview:
    cards: int
    duplicates: int
    blank: int
    bad: int
    empty: int
    ignored: dict[str, int]


@dataclass
class ImportPlan:
    creates: list[Contact]
    updates: list[tuple[Contact, Contact]]
    skipped: int
    unchanged: int


def _names_agree(a: Contact, b: Contact) -> bool:
    """No name on one side, or the words of one name all in the other ("Marie" and "Marie Dupont")."""
    ka, kb = name_key(a), name_key(b)
    if not ka or not kb:
        return True
    wa, wb = set(ka.split(" ")), set(kb.split(" "))
    return wa <= wb or wb <= wa


def match(card: Contact, existing: list[Contact], by_name: bool) -> Contact | None:
    live = [c for c in existing if not c.conflict]
    if card.uid:
        for c in live:
            if c.uid == card.uid:
                return c
    phones = phone_keys(card)
    if phones:
        for c in live:
            if phone_keys(c) & phones and _names_agree(card, c):  # a shared home line is not the same person
                return c
    if by_name and name_key(card):
        for c in live:
            if name_key(c) == name_key(card):
                return c
    return None


def preview(result, existing: list[Contact]) -> ImportPreview:
    return ImportPreview(
        cards=len(result.contacts),
        duplicates=sum(1 for c in result.contacts if match(c, existing, True) is not None),
        blank=sum(1 for c in result.contacts if not c.name and not c.phones),
        bad=result.bad, empty=result.empty, ignored=dict(result.ignored))


def _update(existing: Contact, card: Contact, now: int) -> Contact:
    primary = existing.copy(first_name=card.first_name if card.first_name.strip() else existing.first_name,
                            last_name=card.last_name if card.last_name.strip() else existing.last_name,
                            address=card.address if card.address and card.address.strip() else existing.address,
                            birthday=card.birthday or existing.birthday, uid=existing.uid or card.uid)
    merged = merge(primary, [card.copy(favorite=False)])
    return existing if merged == existing else merged.copy(updated=now)


def plan(result, existing: list[Contact], policy: DuplicatePolicy, import_id: str, now: int, new_id=fmt.generate_id) -> ImportPlan:
    taken = {c.id for c in existing}
    creates: list[Contact] = []
    current: dict[str, tuple[Contact, Contact]] = {}
    skipped = unchanged = 0
    for card in result.contacts:
        if policy == DuplicatePolicy.SKIP and match(card, existing, True) is not None:
            skipped += 1
            continue
        if policy == DuplicatePolicy.UPDATE:
            target = match(card, existing, card.uid is None and not phone_keys(card))
            if target is not None:
                before = current[target.id][1] if target.id in current else target
                after = _update(before, card, now)
                if after == before:
                    unchanged += 1
                else:
                    current[target.id] = (current[target.id][0] if target.id in current else target, after)
                continue
        cid = new_id()
        while not cid or cid in taken:
            cid = new_id()
        taken.add(cid)
        c = card.copy(id=cid, import_id=import_id, created=now, updated=now)
        creates.append(c.copy(path=file_name_for(c, cid)))
    return ImportPlan(creates, list(current.values()), skipped, unchanged)


@dataclass
class ImportOutcome:
    import_id: str
    created: list[str]
    previous: dict[str, str]  # files an UPDATE import changed, with their text before
    skipped: int
    unchanged: int
    bad: int
    empty: int
    ignored: dict[str, int]

    @property
    def updated(self) -> int:
        return len(self.previous)


def new_import_id(now: int) -> str:
    from datetime import datetime
    return datetime.fromtimestamp(now / 1000).strftime("%Y%m%d-%H%M%S") + "-" + fmt.generate_id()


# ---- the folder ----

class ContactBook:
    """The contacts/ folder; [contacts] is an index rebuilt by scan(), the files are the truth."""

    def __init__(self, root: Path):
        self.root = Path(root)
        self._cache: dict[str, tuple[int, Contact]] = {}
        self.contacts: list[Contact] = []

    def scan(self) -> list[Contact]:
        out: list[Contact] = []
        seen: set[str] = set()
        try:
            entries = list(os.scandir(self.root / DIR))
        except OSError:
            entries = []
        for entry in entries:
            name = entry.name
            if not entry.is_file() or name.startswith(".") or not (name.endswith(".md") or _CONFLICT_COPY.match(name)):
                continue
            path = f"{DIR}/{name}"
            seen.add(path)
            mtime = entry.stat().st_mtime_ns
            cached = self._cache.get(path)
            if cached and cached[0] == mtime:
                out.append(cached[1])
                continue
            try:
                text = (self.root / path).read_text(encoding="utf-8")
            except (OSError, UnicodeDecodeError):
                continue
            c = from_note(fmt.parse(path, text, mtime // 1_000_000))
            self._cache[path] = (mtime, c)
            out.append(c)
        for stale in set(self._cache) - seen:
            del self._cache[stale]
        self.contacts = out
        return out

    def by_id(self, contact_id: str) -> Contact | None:
        return next((c for c in self.contacts if c.id == contact_id and not c.conflict), None)

    def _read(self, path: str) -> str | None:
        try:
            return (self.root / path).read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError):
            return None

    def _write(self, c: Contact) -> Contact:
        text = fmt.serialize(to_note(c))
        atomic.write_text(self.root / c.path, text)
        mtime = (self.root / c.path).stat().st_mtime_ns
        saved = from_note(fmt.parse(c.path, text, mtime // 1_000_000))
        self._cache[c.path] = (mtime, saved)
        self.contacts = [x for x in self.contacts if x.path != c.path] + [saved]
        return saved

    def save(self, c: Contact, now: int | None = None) -> Contact:
        """Writes [c]; a new contact (no path) gets its id and file name here, fixed for good."""
        now = now_ms() if now is None else now
        c = c.copy(updated=now, created=c.created or now)
        if not c.path:
            cid = c.id if c.id and self.by_id(c.id) is None else ""
            taken = {x.id for x in self.contacts}
            while not cid or cid in taken:
                cid = fmt.generate_id()
            c = c.copy(id=cid, path=file_name_for(c, cid))
        return self._write(c)

    def delete(self, c: Contact) -> str | None:
        """Deletes the file; returns its text so restore() can undo it."""
        raw = self._read(c.path)
        try:
            (self.root / c.path).unlink()
        except FileNotFoundError:
            pass
        self._cache.pop(c.path, None)
        self.contacts = [x for x in self.contacts if x.path != c.path]
        return raw

    def restore(self, path: str, raw: str) -> None:
        atomic.write_text(self.root / path, raw)
        self.scan()

    def merge(self, keep: Contact, others: list[Contact]) -> dict[str, str]:
        before = {c.path: t for c in [keep, *others] if (t := self._read(c.path)) is not None}
        self.save(merge(keep, others))
        for o in others:
            self.delete(o)
        return before

    def restore_all(self, texts: dict[str, str]) -> None:
        for path, raw in texts.items():
            atomic.write_text(self.root / path, raw)
        self.scan()

    def apply(self, plan_: ImportPlan, import_id: str, result, progress=lambda done, total: None) -> ImportOutcome:
        total = len(plan_.creates) + len(plan_.updates)
        created: list[str] = []
        previous: dict[str, str] = {}
        done = 0
        for c in plan_.creates:
            self._write(c)
            created.append(c.path)
            done += 1
            progress(done, total)
        for before, after in plan_.updates:
            raw = self._read(before.path)
            if raw is not None:
                previous[before.path] = raw
            self._write(after)
            done += 1
            progress(done, total)
        return ImportOutcome(import_id, created, previous, plan_.skipped, plan_.unchanged, result.bad, result.empty,
                             dict(result.ignored))

    def undo(self, outcome: ImportOutcome) -> int:
        """Deletes the files the import created (still marked with its id) and restores the ones it updated."""
        n = 0
        for path in outcome.created:
            text = self._read(path)
            if text is None:
                continue
            if from_note(fmt.parse(path, text, 0)).import_id != outcome.import_id:
                continue
            (self.root / path).unlink()
            n += 1
        for path, raw in outcome.previous.items():
            atomic.write_text(self.root / path, raw)
            n += 1
        self.scan()
        return n
