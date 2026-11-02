import pytest
from PySide6.QtWidgets import QApplication

from memento_win.core import drawing as dr


def stroke(rgb, w, *pts):
    return dr.Stroke(rgb, w, tuple(zip(pts[0::2], pts[1::2])))


def test_svg_round_trip_keeps_strokes_and_dots():
    d = [stroke(0x1A1A1A, 3.0, 10, 20, 30.5, 40, -5, 7.5), stroke(0xE53935, 6.0, 100, 100)]
    svg = dr.to_svg(d)
    assert dr.looks_like_drawing(svg) and "viewBox=" in svg
    back = dr.parse(svg)
    assert len(back) == 2
    assert back[0].rgb == 0x1A1A1A and back[0].width == 3.0 and len(back[0].points) == 3
    assert back[0].points[1] == pytest.approx((30.5, 40), abs=0.05)
    assert back[0].points[2] == pytest.approx((-5, 7.5), abs=0.05)
    assert back[1].rgb == 0xE53935 and len(back[1].points) == 1  # a dot stays a dot


def test_empty_drawing_is_a_valid_svg_and_other_paths_are_ignored():
    assert dr.parse(dr.to_svg([])) == []
    assert dr.bounds([]) is None
    svg = '<svg><path d="M0 0 L5 5" fill="red"/><path d="M1 1 L2 2" stroke="#112233" stroke-width="2"/></svg>'
    assert [s.rgb for s in dr.parse(svg)] == [0x112233]


def test_bounds_and_eraser_hit_testing():
    assert dr.bounds([stroke(0, 10, 0, 0, 100, 50)]) == (-5, -5, 105, 55)
    d = [stroke(1, 4, 0, 0, 100, 0), stroke(2, 4, 0, 3, 100, 3), stroke(3, 4, 0, 200, 100, 200)]
    assert dr.stroke_at(d, 50, 2, 3) == 1  # both are near: the later one is on top
    assert dr.stroke_at(d, 50, 198, 3) == 2
    assert dr.stroke_at(d, 50, 100, 3) == -1
    assert dr.stroke_at(d, -2, -3, 3) == 0


def test_the_kotlin_svg_parses_here():
    svg = ('<svg xmlns="http://www.w3.org/2000/svg" data-griffonote="1" viewBox="0 0 10 10">\n'
           '<path d="M10.0 20.0 L30.5 40.0" fill="none" stroke="#1a1a1a" stroke-width="3.0" stroke-linecap="round" stroke-linejoin="round"/>\n</svg>')
    assert dr.parse(svg)[0].points == ((10.0, 20.0), (30.5, 40.0))


def test_the_canvas_draws_erases_and_undoes(qtbot=None):
    from PySide6.QtCore import QPointF
    from memento_win.ui.drawing_view import Canvas, render_pixmap
    QApplication.instance() or QApplication([])
    c = Canvas([])
    c.resize(400, 300)
    c.live = [(10, 10), (50, 50)]
    c._push()
    c.strokes = c.strokes + [dr.Stroke(c.rgb, c.width_units, tuple(c.live))]
    c.live = []
    assert len(c.strokes) == 1 and c.changed
    c.eraser = True
    c._erase_start = c.strokes
    c._erase_at(30, 30)
    assert c.strokes == []
    c.undo_stack.append(c._erase_start)
    c.undo()
    assert len(c.strokes) == 1
    c.redo()
    c.fit()
    pix = render_pixmap([dr.Stroke(0xE53935, 6.0, ((0, 0), (100, 50)))], 96)
    assert not pix.isNull() and max(pix.width(), pix.height()) == 96
    assert c.to_world(QPointF(0, 0)) is not None


def test_a_drawing_attached_to_a_note_shows_a_thumbnail_and_deletes_with_it(tmp_path):
    from memento_win.core import atomic, note as fmt
    from memento_win.core.store import Store, now_ms
    store = Store(tmp_path)
    path = dr.new_path(fmt.generate_id)
    atomic.write_text(tmp_path / path, dr.to_svg([stroke(0x1A1A1A, 3.0, 0, 0, 50, 50)]))
    note = store.save(fmt.Note(path="", id=fmt.generate_id(), title="Triangle", created=now_ms(), updated=now_ms(), attachments=[path]))
    assert dr.is_drawing(path) and (tmp_path / path).is_file()
    store.delete(note)
    assert not (tmp_path / path).exists()
