"""Settings from the environment, checked once at startup: a misconfigured container stops with a message
instead of serving a half-working site."""
from __future__ import annotations

import ipaddress
import os
import secrets
import uuid
from dataclasses import dataclass, field
from pathlib import Path


class ConfigError(Exception):
    """Startup refused; the message is printed as is."""


@dataclass(frozen=True)
class Config:
    user: str
    password: str
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
    static_dir: Path | None = None  # the built UI (webapp/ui/dist)
    map_dir: Path | None = None  # web/map of the repository
    tiles_fetch: object = field(default=None, compare=False)  # tests replace the network

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


def from_env(env: dict | None = None) -> Config:
    env = dict(os.environ if env is None else env)
    user, password = env.get("MEMENTO_USER", ""), env.get("MEMENTO_PASSWORD", "")
    if not user.strip() or not password:
        raise ConfigError("MEMENTO_USER et MEMENTO_PASSWORD sont obligatoires (voir .env.example).")
    if len(password) < 12:
        raise ConfigError("MEMENTO_PASSWORD doit faire au moins 12 caractères.")
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
    static = env.get("MEMENTO_STATIC")
    map_dir = env.get("MEMENTO_MAP")
    return Config(
        user=user.strip(), password=password, data=data, config_dir=config_dir,
        session_secret=_session_secret(env, config_dir), session_days=days, cookie_secure=secure,
        trust_proxy=_bool("MEMENTO_TRUST_PROXY", env.get("MEMENTO_TRUST_PROXY", "false")), trusted_proxies=proxies,
        poll_seconds=max(poll, 0.5), static_dir=Path(static) if static else None, map_dir=Path(map_dir) if map_dir else None,
    )
