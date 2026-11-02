"""A small vCard 2.1 / 3.0 / 4.0 reader and 3.0 writer (same algorithm as the Android app's VCard.kt).

Copes with what real exports contain: quoted-printable with a CHARSET (Android 2.1), folded lines, item1.TEL grouped
with item1.X-ABLabel (Google, Apple), tel: URIs (4.0), FN without N, company-only cards, BDAY in three forms.
"""
from __future__ import annotations

import codecs
from dataclasses import dataclass, field

from .contacts import HOME, MOBILE, OTHER, WORK, Birthday, Contact, Phone


@dataclass
class VCardResult:
    contacts: list[Contact] = field(default_factory=list)
    bad: int = 0  # cards that could not be read (truncated, garbled): skipped
    empty: int = 0  # cards with nothing to import: skipped
    ignored: dict[str, int] = field(default_factory=dict)  # properties not kept, by name, for the report


class _CardError(Exception):
    pass


@dataclass
class _Prop:
    group: str | None
    name: str
    params: dict[str, list[str]]
    types: set[str]
    raw: str

    @property
    def qp(self) -> bool:
        return any(v.upper() == "QUOTED-PRINTABLE" for v in self.params.get("ENCODING", [])) or "QUOTED-PRINTABLE" in self.types

    @property
    def binary(self) -> bool:
        return any(v.upper() in ("B", "BASE64") for v in self.params.get("ENCODING", [])) or "BASE64" in self.types

    @property
    def charset(self) -> str | None:
        values = self.params.get("CHARSET")
        return values[0] if values else None


_READ = {"BEGIN", "END", "VERSION", "N", "FN", "TEL", "EMAIL", "ADR", "BDAY", "NOTE", "ORG", "TITLE", "UID", "X-ABLABEL",
         "X-SCRIPTORIUM-FAVORITE", "PRODID", "KIND"}
_HEX = set("0123456789abcdefABCDEF")


def _decode_file(data: bytes) -> str:
    try:
        return data.decode("utf-8")
    except UnicodeDecodeError:
        return data.decode("cp1252", errors="replace")


def _qp_open(line: str) -> bool:
    if not line.endswith("="):
        return False
    colon = line.find(":")
    return colon > 0 and "QUOTED-PRINTABLE" in line[:colon].upper()


def _unfold(text: str) -> list[str]:
    out: list[str] = []
    for line in text.replace("\r\n", "\n").replace("\r", "\n").split("\n"):
        if out and line.startswith((" ", "\t")) and not _qp_open(out[-1]):
            out[-1] += line[1:]
        elif out and _qp_open(out[-1]):
            out[-1] = out[-1][:-1] + line
        else:
            out.append(line)
    return [x for x in out if x.strip()]


def _split_unquoted(s: str, sep: str) -> list[str]:
    out, cur, quoted = [], [], False
    for ch in s:
        if ch == '"':
            quoted = not quoted
        if ch == sep and not quoted:
            out.append("".join(cur))
            cur = []
        else:
            cur.append(ch)
    out.append("".join(cur))
    return out


def _parse_line(line: str) -> _Prop | None:
    colon, quoted = -1, False
    for i, ch in enumerate(line):
        if ch == '"':
            quoted = not quoted
        elif ch == ":" and not quoted:
            colon = i
            break
    if colon <= 0:
        return None
    head = _split_unquoted(line[:colon], ";")
    full = head[0].strip()
    dot = full.rfind(".")
    group = full[:dot] if dot > 0 else None
    name = full[dot + 1:].upper()
    params: dict[str, list[str]] = {}
    types: set[str] = set()
    for p in head[1:]:
        eq = p.find("=")
        if eq < 0:
            types.add(p.strip().upper())  # vCard 2.1: TEL;CELL;VOICE
            continue
        key = p[:eq].strip().upper()
        # TYPE="cell,voice" (4.0) and TYPE=cell,voice are the same list
        values = [v.strip() for part in _split_unquoted(p[eq + 1:], ",") for v in part.strip().strip('"').split(",")]
        values = [v for v in values if v]
        params.setdefault(key, []).extend(values)
        if key == "TYPE":
            types.update(v.upper() for v in values)
    return _Prop(group, name, params, types, line[colon + 1:])


def _decoded(p: _Prop) -> str:
    if not p.qp:
        return p.raw
    s, out, i = p.raw, bytearray(), 0
    while i < len(s):
        ch = s[i]
        hx = s[i + 1:i + 3] if ch == "=" and i + 2 < len(s) else ""
        if len(hx) == 2 and set(hx) <= _HEX:
            out.append(int(hx, 16))
            i += 3
        elif ch == "=" and i == len(s) - 1:
            i += 1  # trailing soft break
        else:
            out.extend(ch.encode("utf-8"))
            i += 1
    try:
        codec = codecs.lookup(p.charset).name if p.charset else "utf-8"
    except LookupError:
        codec = "utf-8"
    try:
        return bytes(out).decode(codec)
    except UnicodeDecodeError:
        return bytes(out).decode("cp1252", errors="replace")


def _text(p: _Prop) -> str:
    return _decoded(p).replace("\r\n", "\n").replace("\r", "\n")


def _unescape(s: str) -> str:
    out, i = [], 0
    while i < len(s):
        ch = s[i]
        if ch == "\\" and i + 1 < len(s):
            n = s[i + 1]
            out.append("\n" if n in "nN" else n)
            i += 2
        else:
            out.append(ch)
            i += 1
    return "".join(out)


def _components(value: str) -> list[str]:
    out, cur, i = [], [], 0
    while i < len(value):
        ch = value[i]
        if ch == "\\" and i + 1 < len(value):
            cur.append(value[i:i + 2])
            i += 2
            continue
        if ch == ";":
            out.append(_unescape("".join(cur)))
            cur = []
        else:
            cur.append(ch)
        i += 1
    out.append(_unescape("".join(cur)))
    return out


def _phone_label(types: set[str], ab_label: str | None) -> str | None:
    if ab_label and ab_label.strip():
        label = ab_label.strip().removeprefix("_$!<").removesuffix(">!$_").strip()
        return {"mobile": MOBILE, "cell": MOBILE, "iphone": MOBILE, "portable": MOBILE, "mobile phone": MOBILE,
                "home": HOME, "domicile": HOME, "maison": HOME,
                "work": WORK, "travail": WORK, "bureau": WORK,
                "other": OTHER, "autre": OTHER, "main": OTHER}.get(label.lower(), label)
    t = types - {"PREF", "VOICE", "INTERNET", "X-INTERNET", "QUOTED-PRINTABLE", "BASE64"}
    if t & {"CELL", "MOBILE", "IPHONE"}:
        return MOBILE
    if "WORK" in t:
        return WORK
    if "HOME" in t:
        return HOME
    if not t:
        return OTHER if "VOICE" in types else None
    return OTHER


def _address(parts: list[str]) -> str | None:
    def p(i: int) -> str:
        return parts[i].replace("\n", ", ").strip() if i < len(parts) else ""
    street = ", ".join(x for x in (p(1), p(2)) if x)
    city = " ".join(x for x in (p(5), p(3)) if x)
    return ", ".join(x for x in (p(0), street, city, p(4), p(6)) if x) or None


def _read_card(lines: list[str], ignored: dict[str, int]) -> Contact | None:
    props: list[_Prop] = []
    last_binary = False
    for line in lines:
        p = _parse_line(line)
        if p is None:
            if last_binary:
                continue  # unindented base64 continuation of a photo
            raise _CardError("Ligne illisible")
        last_binary = p.binary
        props.append(p)
    labels = {p.group.upper(): _unescape(_text(p)) for p in props if p.name == "X-ABLABEL" and p.group}
    version = next((p.raw.strip() for p in props if p.name == "VERSION"), None)

    first = last = fn = org = title = ""
    phones: list[Phone] = []
    emails: list[str] = []
    addresses: list[str] = []
    birthday = None
    notes: list[str] = []
    uid = None
    favorite = False
    for p in props:
        if p.name == "N":
            c = _components(_text(p))
            last = c[0].strip() if c else ""
            first = " ".join(x.strip() for x in c[1:3] if x.strip())
        elif p.name == "FN":
            fn = _unescape(_text(p)).strip()
        elif p.name == "ORG":
            org = ", ".join(x.strip() for x in _components(_text(p)) if x.strip())
        elif p.name == "TITLE":
            title = _unescape(_text(p)).strip()
        elif p.name == "TEL":
            number = _unescape(_text(p)).strip().removeprefix("tel:").removeprefix("TEL:").strip()
            if number:
                phones.append(Phone(number, _phone_label(p.types, labels.get(p.group.upper()) if p.group else None)))
        elif p.name == "EMAIL":
            e = _unescape(_text(p)).strip().removeprefix("mailto:")
            if e and all(x.lower() != e.lower() for x in emails):
                emails.append(e)
        elif p.name == "ADR":
            a = _address(_components(_text(p)))
            if a:
                addresses.append(a)
        elif p.name == "BDAY":
            b = Birthday.parse(_unescape(_text(p)))
            if b is not None:
                birthday = b
            else:
                ignored["BDAY"] = ignored.get("BDAY", 0) + 1
        elif p.name == "NOTE":
            n = _unescape(_text(p)).strip()
            if n:
                notes.append(n)
        elif p.name == "UID":
            uid = _unescape(_text(p)).strip() or None
        elif p.name == "X-SCRIPTORIUM-FAVORITE":
            favorite = _text(p).strip().upper() == "TRUE"
        elif p.name not in _READ:
            key = "X-…" if p.name.startswith("X-") else p.name
            ignored[key] = ignored.get(key, 0) + 1
    if version is not None and version not in ("2.1", "3.0", "4.0"):
        raise _CardError(f"Version {version}")
    company_name = False
    if not first and not last:
        if fn and fn == org:
            first, company_name = org, True
        elif fn:
            sp = fn.rfind(" ")
            if sp > 0:
                first, last = fn[:sp].strip(), fn[sp + 1:].strip()
            else:
                first = fn
        elif org:
            first, company_name = org, True
    body: list[str] = []
    company = "" if company_name else org
    if company and title:
        body.append(f"Société : {company} — {title}")
    elif company:
        body.append(f"Société : {company}")
    elif title:
        body.append(f"Fonction : {title}")
    body.extend(notes)
    body.extend(f"Adresse : {a}" for a in addresses[1:])
    c = Contact(first_name=first, last_name=last, phones=phones, emails=emails,
                address=addresses[0] if addresses else None, birthday=birthday, favorite=favorite, uid=uid,
                notes="\n\n".join(body))
    return None if c.is_empty else c


def parse(data: bytes | str) -> VCardResult:
    """Reads a whole .vcf: UTF-8, or Windows-1252 when the bytes are not UTF-8."""
    text = _decode_file(data) if isinstance(data, bytes) else data
    result = VCardResult()

    def finish(card: list[str]) -> None:
        try:
            c = _read_card(card, result.ignored)
        except _CardError:
            result.bad += 1
            return
        if c is None:
            result.empty += 1
        else:
            result.contacts.append(c)

    card: list[str] | None = None
    for line in _unfold(text.removeprefix("\ufeff")):
        upper = line.strip().upper()
        if upper == "BEGIN:VCARD":
            if card is not None:
                result.bad += 1  # the previous card never ended
            card = []
            continue
        if upper == "END:VCARD" and card is not None:
            finish(card)
            card = None
            continue
        if card is not None:
            card.append(line)
    if card is not None:
        result.bad += 1  # truncated file
    return result


# ---- writing (vCard 3.0) ----

def _escape(s: str) -> str:
    return (s.replace("\\", "\\\\").replace(",", "\\,").replace(";", "\\;").replace("\r\n", "\n")
            .replace("\n", "\\n"))


def _fold(line: str) -> str:
    """Lines of at most 75 octets, continued with a space, never splitting a character."""
    if len(line.encode("utf-8")) <= 75:
        return line
    out, size, limit = [], 0, 75
    for ch in line:
        n = len(ch.encode("utf-8"))
        if size + n > limit:
            out.append("\r\n ")
            size, limit = 0, 74
        out.append(ch)
        size += n
    return "".join(out)


def write_one(c: Contact) -> str:
    out = ["BEGIN:VCARD", "VERSION:3.0",
           "N:" + ";".join(_escape(x.strip()) for x in (c.last_name, c.first_name, "", "", "")),
           "FN:" + _escape(c.name)]  # empty without a name: a number in FN would come back as a name
    item = 0
    for p in c.phones:
        kind = {MOBILE: "CELL", HOME: "HOME", WORK: "WORK", OTHER: "VOICE"}.get(p.label or "")
        if not p.label:
            out.append("TEL:" + _escape(p.number))
        elif kind:
            out.append(f"TEL;TYPE={kind}:" + _escape(p.number))
        else:
            item += 1
            out.append(f"item{item}.TEL:" + _escape(p.number))
            out.append(f"item{item}.X-ABLabel:" + _escape(p.label))
    out.extend("EMAIL;TYPE=INTERNET:" + _escape(e) for e in c.emails)
    if c.address and c.address.strip():
        out.append("ADR:;;" + _escape(c.address) + ";;;;")
    if c.birthday:
        out.append("BDAY:" + c.birthday.encode())
    if c.notes.strip():
        out.append("NOTE:" + _escape(c.notes.strip("\n")))
    if c.uid:
        out.append("UID:" + _escape(c.uid))
    if c.favorite:
        out.append("X-SCRIPTORIUM-FAVORITE:TRUE")
    out.append("END:VCARD")
    return "".join(_fold(x) + "\r\n" for x in out)


def write(contacts: list[Contact]) -> str:
    return "".join(write_one(c) for c in contacts)
