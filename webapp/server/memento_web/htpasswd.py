"""The login as an Apache htpasswd file, like the Synchrotron server's (rclone serve webdav --htpasswd): one
`user:hash` per line, made with `htpasswd -B` (bcrypt). The older apr1 (MD5) and {SHA} hashes are read too, so a
file made elsewhere works; a plain-text password is refused."""
from __future__ import annotations

import base64
import hashlib
import hmac
from pathlib import Path

import bcrypt


class HtpasswdError(Exception):
    """The file cannot be used; the message is printed at startup."""


def read(path: Path) -> dict[str, str]:
    """user → hash. Blank lines and # comments are skipped."""
    try:
        text = path.read_text(encoding="utf-8")
    except OSError as error:
        raise HtpasswdError(f"{path} illisible : {error}") from None
    entries: dict[str, str] = {}
    for number, line in enumerate(text.splitlines(), 1):
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        user, sep, hashed = line.partition(":")
        if not sep or not user or not hashed:
            raise HtpasswdError(f"{path}, ligne {number} : attendu « utilisateur:empreinte »")
        if not supported(hashed):
            raise HtpasswdError(f"{path}, ligne {number} : empreinte non reconnue (créer le fichier avec htpasswd -B)")
        entries[user] = hashed
    if not entries:
        raise HtpasswdError(f"{path} ne contient aucun utilisateur")
    return entries


def supported(hashed: str) -> bool:
    return hashed.startswith(("$2a$", "$2b$", "$2y$", "$apr1$", "{SHA}"))


def verify(password: str, hashed: str) -> bool:
    secret = password.encode()
    if hashed.startswith(("$2a$", "$2b$", "$2y$")):
        # the libraries differ only in the prefix ($2y$ is PHP's and Apache's name for $2b$)
        try:
            return bcrypt.checkpw(secret, ("$2b$" + hashed[4:]).encode())
        except ValueError:
            return False
    if hashed.startswith("$apr1$"):
        salt = hashed[6:].split("$", 1)[0]
        return hmac.compare_digest(apr1(secret, salt.encode()), hashed)
    if hashed.startswith("{SHA}"):
        return hmac.compare_digest(base64.b64encode(hashlib.sha1(secret).digest()).decode(), hashed[5:])
    return False


_ITOA64 = "./0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"


def apr1(password: bytes, salt: bytes) -> str:
    """Apache's MD5-crypt variant (md5crypt with the "$apr1$" magic)."""
    magic, salt = b"$apr1$", salt[:8]
    final = hashlib.md5(password + salt + password).digest()
    ctx = password + magic + salt
    for i in range(len(password), 0, -16):
        ctx += final[:min(16, i)]
    i = len(password)
    while i:
        ctx += b"\0" if i & 1 else password[:1]
        i >>= 1
    final = hashlib.md5(ctx).digest()
    for i in range(1000):
        step = (password if i & 1 else final) + (salt if i % 3 else b"") + (password if i % 7 else b"")
        final = hashlib.md5(step + (final if i & 1 else password)).digest()
    out = ""
    for a, b, c in ((0, 6, 12), (1, 7, 13), (2, 8, 14), (3, 9, 15), (4, 10, 5)):
        v = (final[a] << 16) | (final[b] << 8) | final[c]
        for _ in range(4):
            out += _ITOA64[v & 0x3F]
            v >>= 6
    v = final[11]
    for _ in range(2):
        out += _ITOA64[v & 0x3F]
        v >>= 6
    return f"$apr1${salt.decode()}${out}"
