import hashlib
import http.server
import os
import threading

import pytest

from memento_win.services import updater

PAYLOAD = b"MZ" + os.urandom(5000)


class _Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200)
        self.send_header("Content-Length", str(len(PAYLOAD)))
        self.end_headers()
        self.wfile.write(PAYLOAD)

    def log_message(self, *args):
        pass


@pytest.fixture
def server():
    httpd = http.server.HTTPServer(("127.0.0.1", 0), _Handler)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    yield f"http://127.0.0.1:{httpd.server_port}/Memento.exe"
    httpd.shutdown()


def test_parse_release_reads_build_exe_and_digest():
    data = {
        "name": "Mémento build 42",
        "assets": [
            {"name": "memento.apk", "browser_download_url": "http://x/a.apk"},
            {"name": "Memento.exe", "browser_download_url": "http://x/g.exe", "size": 10, "digest": "sha256:ABCD"},
        ],
    }
    release = updater.parse_release(data)
    assert (release.build, release.url, release.size, release.sha256) == (42, "http://x/g.exe", 10, "abcd")
    assert updater.parse_release({"name": "no number here", "assets": []}) is None
    assert updater.parse_release({"name": "Mémento build 3", "assets": []}) is None


def test_download_verifies_size_and_sha(server):
    good = updater.Release(1, server, len(PAYLOAD), hashlib.sha256(PAYLOAD).hexdigest())
    path = updater.download(good)
    assert open(path, "rb").read() == PAYLOAD
    os.remove(path)
    with pytest.raises(OSError, match="SHA-256"):
        updater.download(updater.Release(1, server, len(PAYLOAD), "0" * 64))
    with pytest.raises(OSError, match="incomplet"):
        updater.download(updater.Release(1, server, len(PAYLOAD) + 1, ""))


def test_install_swaps_the_exe_and_cleanup_removes_the_old_one(tmp_path):
    exe, new = tmp_path / "Memento.exe", tmp_path / "new.download"
    exe.write_bytes(b"old")
    new.write_bytes(b"new")
    updater.install(str(exe), str(new))
    assert exe.read_bytes() == b"new"
    assert (tmp_path / "Memento.exe.old").read_bytes() == b"old"
    assert updater.cleanup_previous(str(exe)) is True
    assert not (tmp_path / "Memento.exe.old").exists()


def test_install_works_when_the_download_is_in_another_folder(tmp_path):
    app_dir, other = tmp_path / "app", tmp_path / "temp"
    app_dir.mkdir()
    other.mkdir()
    exe, new = app_dir / "Memento.exe", other / "dl.download"
    exe.write_bytes(b"old")
    new.write_bytes(b"new")
    updater.install(str(exe), str(new))
    assert exe.read_bytes() == b"new"
    assert sorted(p.name for p in app_dir.iterdir()) == ["Memento.exe", "Memento.exe.old"]
    assert not new.exists()


def test_install_rolls_back_when_the_new_file_is_missing(tmp_path):
    exe = tmp_path / "Memento.exe"
    exe.write_bytes(b"old")
    with pytest.raises(OSError):
        updater.install(str(exe), str(tmp_path / "missing"))
    assert exe.read_bytes() == b"old"


def test_is_newer_compares_with_the_current_build(monkeypatch):
    monkeypatch.setattr(updater._version, "BUILD", 5)
    assert updater.is_newer(updater.Release(6, "u"))
    assert not updater.is_newer(updater.Release(5, "u"))
