"""The analysis child's imports and the order in which callers get it."""
from __future__ import annotations

import os
import subprocess
import sys
import threading
import contextlib
import time

import tracks
from memento_web import shapes, trackwork
from memento_web.modules import activities
from test_activities import T0, clear_cache, import_file


def test_the_web_module_re_exports_the_shapes():
    for name in ("analysis_json", "dumps", "_bucket_means", "_thin", "_r", "_pos", "CHART_POINTS", "MAP_POINTS"):
        assert getattr(activities, name) is getattr(shapes, name)


def test_the_child_imports_neither_starlette_nor_asyncio():
    import memento_win
    paths = [str(trackwork.PACKAGE_PARENT), os.path.dirname(os.path.dirname(memento_win.__file__))]
    code = ("import sys, memento_web.trackwork as w, memento_web.shapes, memento_win.core.analysis, "
            "memento_win.core.store; print(sorted(m for m in ('starlette', 'asyncio') if m in sys.modules))")
    done = subprocess.run([sys.executable, "-c", code], capture_output=True, text=True,
                          env={**os.environ, "PYTHONPATH": os.pathsep.join(paths)})
    assert done.returncode == 0, done.stderr
    assert done.stdout.strip() == "[]"


def test_a_waiting_request_gets_the_child_before_the_warm_up():
    children = trackwork.Children()
    order, holding = [], threading.Event()
    release = threading.Event()

    def take(name, background):
        with children.slot(background):
            order.append(name)
            if name == "first":
                holding.set()
                release.wait(5)

    first = threading.Thread(target=take, args=("first", False))
    first.start()
    assert holding.wait(5)
    warm = threading.Thread(target=take, args=("warm-up", True))
    warm.start()
    time.sleep(0.05)  # the warm-up waits first...
    opened = threading.Thread(target=take, args=("opened", False))
    opened.start()
    deadline = time.monotonic() + 5
    while children._waiting != 1 and time.monotonic() < deadline:  # the opened activity is queued, not just started
        time.sleep(0.005)
    release.set()
    for t in (first, warm, opened):
        t.join(5)
    assert order == ["first", "opened", "warm-up"]  # ...but the opened activity goes before it


def test_the_warm_up_runs_when_no_one_else_waits():
    children = trackwork.Children()
    with trackwork.background():
        assert trackwork._local.background
        with children.slot(True):
            pass
    assert not trackwork._local.background


class Recorder(trackwork.Children):
    """The real gate, noting whether each child was asked for in the background."""

    def __init__(self):
        super().__init__()
        self.flags = []

    @contextlib.contextmanager
    def slot(self, background: bool):
        self.flags.append(background)
        with super().slot(background):
            yield


def test_a_child_started_inside_background_asks_for_a_background_slot(monkeypatch):
    recorder = Recorder()
    monkeypatch.setattr(trackwork, "CHILDREN", recorder)
    monkeypatch.setattr(trackwork.subprocess, "run",
                        lambda args, **_kw: subprocess.CompletedProcess(args, 0, stdout=b"{}\n", stderr=b""))
    assert trackwork.run("analyse", "root", "info", apart=True, timeout=1) == ({}, b"")
    with trackwork.background():
        trackwork.run("analyse", "root", "info", apart=True, timeout=1)
    assert recorder.flags == [False, True]


def test_the_warm_up_analyses_in_the_background(client, monkeypatch):
    module = client.app.state.activities
    import_file(client, tracks.walk(T0, 900).encode(), "w.gpx", sport="walk")
    clear_cache(client)
    recorder = Recorder()
    monkeypatch.setattr(trackwork, "CHILDREN", recorder)
    module.subprocess_bytes = 0
    assert module.prepare_recent() == 1
    assert recorder.flags == [True]
