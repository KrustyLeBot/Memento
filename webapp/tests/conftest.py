"""Synthetic data only: a temporary notes folder with made-up notes, a temporary /config, no network."""
from __future__ import annotations

import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "server"))

from starlette.testclient import TestClient  # noqa: E402

from memento_web.app import create_app  # noqa: E402
from memento_web.config import from_env  # noqa: E402

USER = "camille"
PASSWORD = "correct horse battery"
HEADERS = {"X-Memento": "1"}

NOTE = """---
id: abc123
title: Courses
color: green
pinned: true
labels: [maison, "à faire"]
x-custom: gardé tel quel
created: 2026-10-01T10:00:00+02:00
updated: 2026-10-02T10:00:00+02:00
---

Liste pour **samedi**

- [ ] pain
- [x] lait
"""


@pytest.fixture
def folders(tmp_path):
    data, config = tmp_path / "data", tmp_path / "config"
    data.mkdir()
    config.mkdir()
    (data / "courses-abc123.md").write_text(NOTE, encoding="utf-8")
    (data / "idee-def456.md").write_text("---\nid: def456\ntitle: Idée\ncreated: 2026-09-01T08:00:00+02:00\n"
                                         "updated: 2026-09-01T08:00:00+02:00\n---\n\n# Plan\n- un\n- deux\n", encoding="utf-8")
    return data, config


def make_env(data: Path, config: Path, **extra) -> dict:
    return {"MEMENTO_USER": USER, "MEMENTO_PASSWORD": PASSWORD, "MEMENTO_DATA": str(data),
            "MEMENTO_CONFIG": str(config), "MEMENTO_POLL_SECONDS": "0.5", **extra}


class FakeNet:
    """Answers the map host's upstream requests; records them."""

    def __init__(self):
        self.calls: list[str] = []
        self.answers: dict[str, tuple[int, str, bytes]] = {}
        self.default = (200, "image/png", b"\x89PNG fake")
        self.offline = False

    def __call__(self, url: str):
        from memento_web.modules.maphost import Offline
        self.calls.append(url)
        if self.offline:
            raise Offline("no network in tests")
        for prefix, answer in self.answers.items():
            if url.startswith(prefix):
                return answer
        return self.default


@pytest.fixture
def net():
    return FakeNet()


@pytest.fixture
def make_client(folders, net):
    clients = []

    def make(**extra):
        data, config = folders
        app = create_app(from_env(make_env(data, config, **extra)), fetch=net)
        client = TestClient(app)
        client.__enter__()
        clients.append(client)
        return client
    yield make
    for client in clients:
        client.__exit__(None, None, None)


def login(client, user=USER, password=PASSWORD):
    return client.post("/api/login", json={"user": user, "password": password}, headers=HEADERS)


@pytest.fixture
def client(make_client):
    c = make_client()
    assert login(c).status_code == 200
    return c


@pytest.fixture
def data_dir(folders):
    return folders[0]
