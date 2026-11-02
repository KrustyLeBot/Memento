import base64
import hashlib

import bcrypt
import pytest
from conftest import PASSWORD, USER, make_env

from memento_web import htpasswd
from starlette.testclient import TestClient

from memento_web.app import create_app
from memento_web.config import ConfigError, from_env

# made by `openssl passwd -apr1 -salt abcdefgh 'mot de passe é'`, the algorithm `htpasswd -m` uses
APR1 = "$apr1$abcdefgh$zzyXjBBvAKJvT/AKfJlP1."


def bcrypt_line(password: str, prefix: str = "$2y$") -> str:
    return prefix + bcrypt.hashpw(password.encode(), bcrypt.gensalt(4)).decode()[4:]


def test_every_htpasswd_hash_kind_is_checked():
    assert htpasswd.verify("mot de passe é", APR1) and not htpasswd.verify("mot de passe e", APR1)
    for prefix in ("$2y$", "$2b$", "$2a$"):
        line = bcrypt_line("secret phrase", prefix)
        assert htpasswd.verify("secret phrase", line) and not htpasswd.verify("secret phrasE", line)
    sha = "{SHA}" + base64.b64encode(hashlib.sha1(b"abc").digest()).decode()
    assert htpasswd.verify("abc", sha) and not htpasswd.verify("abd", sha)
    assert not htpasswd.verify("abc", "abc")  # plain text is never a hash


def test_file_is_read_and_a_plain_password_refused(tmp_path):
    file = tmp_path / "htpasswd"
    file.write_text(f"# comment\n\n{USER}:{APR1}\nother:{bcrypt_line('x')}\n", encoding="utf-8")
    assert htpasswd.read(file) == {USER: APR1, "other": htpasswd.read(file)["other"]}
    file.write_text(f"{USER}:plaintext\n", encoding="utf-8")
    with pytest.raises(htpasswd.HtpasswdError, match="ligne 1"):
        htpasswd.read(file)


def _env(folders, file, **extra):
    data, config = folders
    env = make_env(data, config, MEMENTO_HTPASSWD=str(file), **extra)
    env.pop("MEMENTO_PASSWORD")
    return env


def test_startup_takes_the_htpasswd_file_and_never_both(folders, tmp_path):
    file = tmp_path / "htpasswd"
    file.write_text(f"{USER}:{bcrypt_line(PASSWORD)}\n", encoding="utf-8")
    env = _env(folders, file)
    env.pop("MEMENTO_USER")
    config = from_env(env)  # one user in the file: no MEMENTO_USER needed
    assert config.user == USER and config.password == "" and config.password_hash.startswith("$2y$")
    with pytest.raises(ConfigError, match="retirer MEMENTO_PASSWORD"):
        from_env({**env, "MEMENTO_PASSWORD": PASSWORD})
    file.write_text(f"{USER}:{bcrypt_line(PASSWORD)}\nb:{APR1}\n", encoding="utf-8")
    with pytest.raises(ConfigError, match="plusieurs utilisateurs"):
        from_env(env)
    assert from_env({**env, "MEMENTO_USER": "b"}).password_hash == APR1
    with pytest.raises(ConfigError, match="n'est pas dans"):
        from_env({**env, "MEMENTO_USER": "nobody"})


def test_login_checks_the_hash_and_a_new_line_closes_the_sessions(folders, net, tmp_path):
    file = tmp_path / "htpasswd"
    file.write_text(f"{USER}:{bcrypt_line(PASSWORD)}\n", encoding="utf-8")
    with TestClient(create_app(from_env(_env(folders, file)), fetch=net)) as client:
        assert client.post("/api/login", json={"user": USER, "password": "wrong"}, headers={"X-Memento": "1"}).status_code == 401
        assert client.post("/api/login", json={"user": "x", "password": PASSWORD}, headers={"X-Memento": "1"}).status_code == 401
        assert client.post("/api/login", json={"user": USER, "password": PASSWORD}, headers={"X-Memento": "1"}).status_code == 200
        assert client.get("/api/me").status_code == 200
        cookies = dict(client.cookies)
    file.write_text(f"{USER}:{bcrypt_line('another passphrase')}\n", encoding="utf-8")  # same user, new password
    with TestClient(create_app(from_env(_env(folders, file)), fetch=net)) as client:
        client.cookies.update(cookies)
        assert client.get("/api/me").status_code == 401
