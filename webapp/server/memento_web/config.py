"""Settings from the environment, checked once at startup: a misconfigured container stops with a message
instead of serving a half-working site."""
from __future__ import annotations

import ipaddress
import os
import secrets
import uuid
from dataclasses import dataclass, field
from pathlib import Path

from . import htpasswd


class ConfigError(Exception):
    """Startup refused; the message is printed as is."""


@dataclass(frozen=True)
class Config:
    user: str
    password: str  # from MEMENTO_PASSWORD; empty when the login comes from an htpasswd file
    data: Path
    config_dir: Path
    session_secret: bytes
    session_days: int = 7
    # "auto": Secure when the request arrived over https (directly or, with trust_proxy, per X-Forwarded-Proto),
    # so the same container works behind the tunnel and over plain http on the LAN.
    cookie_secure: str = "auto"
    trust_proxy: bool = False
    trusted_proxies: tuple = ()  # networks allowed to set CF-Connecting-IP / X-Forwarded-Proto; empty = any peer
    poll_seconds: float = 3.0
    warm: bool = True  # build the indexes in the background at startup (warm.py); the tests turn it off
    static_dir: Path | None = None  # the built UI (webapp/ui/dist)
    map_dir: Path | None = None  # web/map of the repository
    tiles_fetch: object = field(default=None, compare=False)  # tests replace the network
    password_hash: str = ""  # the user's line of the htpasswd file (bcrypt, apr1 or {SHA})

    @property
    def tiles_dir(self) -> Path:
        return self.config_dir / "tiles"


def _bool(name: str, value: str) -> bool:
    v = value.strip().lower()
    if v in ("1", "true", "yes", "on"):
        return True
    if v in ("0", "false", "no", "off"):
        return False
    raise ConfigError(f"{name} doit valoir true ou false (reçu « {value} »)")


def check_writable(path: Path, what: str) -> None:
    """Creates and removes a temporary named like the apps' own (`.<name>.synctmp-<uuid>`), which Synchrotron never
    syncs: proves the running user may write there without leaving anything a sync could pick up."""
    if not path.is_dir():
        raise ConfigError(f"{what} {path} n'existe pas (ou n'est pas un dossier) : montez-le dans le conteneur.")
    probe = path / f".memento-web-check.synctmp-{uuid.uuid4().hex}"
    try:
        probe.write_bytes(b"")
        probe.unlink()
    except OSError as error:
        raise ConfigError(
            f"{what} {path} n'est pas accessible en écriture pour l'utilisateur {os.getuid()}:{os.getgid()} "
            f"({error.strerror}). Réglez PUID/PGID sur le propriétaire des fichiers (ls -ln).") from None


# Folders the app writes into. Their owners differ in practice (files copied by hand vs written by the WebDAV
# server as root), so each existing one is checked, not only the root.
DATA_SUBFOLDERS = ("attachments", "drawings", "activities", "agenda", "contacts")


def check_data(data: Path) -> None:
    check_writable(data, "Le dossier des données")
    for name in DATA_SUBFOLDERS:
        sub = data / name
        if sub.is_dir():
            check_writable(sub, "Le dossier")
    agenda = data / "agenda"
    if agenda.is_dir():
        for year in sorted(agenda.iterdir()):
            if year.is_dir() and not year.name.startswith("."):
                check_writable(year, "Le dossier")


def _session_secret(env: dict, config_dir: Path) -> bytes:
    if env.get("MEMENTO_SESSION_SECRET"):
        return env["MEMENTO_SESSION_SECRET"].encode()
    file = config_dir / "secret"
    try:
        return bytes.fromhex(file.read_text().strip())
    except FileNotFoundError:
        pass
    except ValueError:
        raise ConfigError(f"{file} est illisible : supprimez-le, il sera recréé (les sessions seront fermées).") from None
    value = secrets.token_bytes(32)
    tmp = file.with_name(f".secret.{uuid.uuid4().hex}.tmp")
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w") as handle:
        handle.write(value.hex())
        handle.flush()
        os.fsync(handle.fileno())
    os.replace(tmp, file)
    return value


def _login(env: dict) -> tuple[str, str, str]:
    """(user, password, hash): the htpasswd file when it is there (as for the Synchrotron server), else
    MEMENTO_USER / MEMENTO_PASSWORD. Never both, so a forgotten password in .env cannot keep working."""
    user, password = env.get("MEMENTO_USER", "").strip(), env.get("MEMENTO_PASSWORD", "")
    file = Path(env.get("MEMENTO_HTPASSWD", "/auth/htpasswd"))
    if file.is_file():
        if password:
            raise ConfigError(f"{file} est présent : retirer MEMENTO_PASSWORD du .env (l'un ou l'autre, pas les deux).")
        try:
            entries = htpasswd.read(file)
        except htpasswd.HtpasswdError as error:
            raise ConfigError(str(error)) from None
        if user:
            if user not in entries:
                raise ConfigError(f"MEMENTO_USER « {user} » n'est pas dans {file}.")
        elif len(entries) == 1:
            user = next(iter(entries))
        else:
            raise ConfigError(f"{file} contient plusieurs utilisateurs : choisir le sien avec MEMENTO_USER.")
        return user, "", entries[user]
    if not user or not password:
        raise ConfigError(f"Identifiants manquants : un fichier {file} (htpasswd -B), ou MEMENTO_USER et "
                          "MEMENTO_PASSWORD (voir .env.example).")
    if len(password) < 12:
        raise ConfigError("MEMENTO_PASSWORD doit faire au moins 12 caractères.")
    return user, password, ""


def from_env(env: dict | None = None) -> Config:
    env = dict(os.environ if env is None else env)
    user, password, password_hash = _login(env)
    data = Path(env.get("MEMENTO_DATA", "/data"))
    check_data(data)
    config_dir = Path(env.get("MEMENTO_CONFIG", "/config"))
    check_writable(config_dir, "Le dossier de configuration")
    try:
        days = int(env.get("MEMENTO_SESSION_DAYS", "7"))
        poll = float(env.get("MEMENTO_POLL_SECONDS", "3"))
    except ValueError:
        raise ConfigError("MEMENTO_SESSION_DAYS et MEMENTO_POLL_SECONDS doivent être des nombres.") from None
    if not 1 <= days <= 30:
        raise ConfigError("MEMENTO_SESSION_DAYS doit être entre 1 et 30.")
    secure = env.get("MEMENTO_COOKIE_SECURE", "auto").strip().lower()
    if secure != "auto":
        secure = "true" if _bool("MEMENTO_COOKIE_SECURE", secure) else "false"
    try:
        proxies = tuple(ipaddress.ip_network(p.strip(), strict=False)
                        for p in env.get("MEMENTO_TRUSTED_PROXIES", "").split(",") if p.strip())
    except ValueError as error:
        raise ConfigError(f"MEMENTO_TRUSTED_PROXIES : {error}") from None
    trust = _bool("MEMENTO_TRUST_PROXY", env.get("MEMENTO_TRUST_PROXY", "false"))
    warm = _bool("MEMENTO_WARM", env.get("MEMENTO_WARM", "true"))
    if trust and not proxies:
        print("memento-web: MEMENTO_TRUST_PROXY without MEMENTO_TRUSTED_PROXIES: any peer that reaches the port "
              "(the LAN too) may set CF-Connecting-IP and X-Forwarded-Proto. Set the tunnel's network.", flush=True)
    static = env.get("MEMENTO_STATIC")
    map_dir = env.get("MEMENTO_MAP")
    return Config(
        user=user, password=password, password_hash=password_hash, data=data, config_dir=config_dir,
        session_secret=_session_secret(env, config_dir), session_days=days, cookie_secure=secure,
        trust_proxy=trust, trusted_proxies=proxies, warm=warm,
        poll_seconds=max(poll, 0.5), static_dir=Path(static) if static else None, map_dir=Path(map_dir) if map_dir else None,
    )
