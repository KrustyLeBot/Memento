"""Files arriving by sync are untrusted: paths in a note's frontmatter and the content of track files."""
import time

import pytest

from memento_win.core import gpx
from memento_win.core.store import Store, inside


@pytest.mark.parametrize("relative", ["../secret.txt", "attachments/../../secret.txt", "/etc/passwd",
                                      "C:/Windows/win.ini", "C:\\Windows\\win.ini", "\\\\host\\share\\x.exe",
                                      "attachments\\..\\..\\secret.txt", "attachments/x.pdf:stream", "a\x00b",
                                      "", ".", "attachments//x"])
def test_paths_leaving_the_folder_are_refused(tmp_path, relative):
    root = tmp_path / "data"
    root.mkdir()
    assert not inside(root, relative)
    assert not Store(root).file_path(relative).exists()


def test_a_symlink_leading_out_is_refused(tmp_path):
    root = tmp_path / "data"
    (root / "attachments").mkdir(parents=True)
    (tmp_path / "secret.txt").write_text("secret")
    (root / "attachments" / "link.txt").symlink_to(tmp_path / "secret.txt")
    assert not inside(root, "attachments/link.txt")
    assert inside(root, "attachments/x-abcdef.pdf")


def test_deleting_a_note_never_deletes_a_file_outside_the_folder(tmp_path):
    root = tmp_path / "data"
    root.mkdir()
    victim = tmp_path / "victim.txt"
    victim.write_text("keep me")
    (root / "evil-abcdef.md").write_text(
        "---\nid: abcdef\ntitle: Evil\npdf: ../victim.txt\nattachments: [../victim.txt, " + str(victim) + "]\n---\n")
    store = Store(root)
    note = next(n for n in store.scan() if n.id == "abcdef")
    assert note.pdf == "../victim.txt"
    store.delete(note)
    assert victim.read_text() == "keep me"
    assert not (root / "evil-abcdef.md").exists()


def test_a_track_cannot_be_read_from_outside_the_folder(tmp_path):
    from memento_win.core.activity import ActivityInfo
    root = tmp_path / "data"
    root.mkdir()
    (tmp_path / "outside.gpx").write_text('<gpx><trk><trkseg><trkpt lat="1" lon="1"><time>2026-01-01T00:00:00Z</time>'
                                          '</trkpt></trkseg></trk></gpx>')
    info = ActivityInfo.parse('{"sport": "walk", "start": 0, "file": "../outside.gpx"}')
    assert info is not None and info.file == "../outside.gpx"
    with pytest.raises(OSError):
        Store(root).read_track(info)


def test_gpx_of_unclosed_points_is_parsed_in_linear_time():
    n = 20_000
    crafted = "<gpx><trk>" + "<trk>" * n + "<trkseg>" + "<trkpt lat='1' lon='1'>" * n + "</trkseg></trk></gpx>"
    started = time.monotonic()
    track = gpx.read(crafted)
    assert time.monotonic() - started < 2  # was minutes (quadratic backtracking)
    assert track.points == [] and track.name is None


def test_gpx_reading_is_unchanged_for_real_files():
    text = """<gpx><trk><name> <![CDATA[Tour du lac]]> </name><trkseg>
<trkpt lat="45.0" lon="6.0"><ele>1000</ele><time>2024-01-01T10:00:00Z</time></trkpt>
<trkpt lat="45.1" lon="6.1"/><trkpt lat="45.2" lon="6.2"><time>2024-01-01T10:01:00Z</time></trkpt>
</trkseg></trk></gpx>"""
    t = gpx.read(text)
    assert t.name == "Tour du lac"
    assert [(p.lat, p.alt) for p in t.points] == [(45.0, 1000.0), (45.2, None)]
