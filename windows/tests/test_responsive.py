import gc
import json
import sys
import threading
import weakref
from types import SimpleNamespace

import pytest


def test_responsive_worker_sets_and_restores_the_interpreter_once_nested():
    from memento_win.services.async_util import responsive_worker
    interval = sys.getswitchinterval()
    assert gc.isenabled()
    with responsive_worker():
        assert sys.getswitchinterval() == pytest.approx(0.001)
        assert not gc.isenabled()
        with responsive_worker():
            assert not gc.isenabled()
        # the inner exit leaves the outer work as it was
        assert not gc.isenabled() and sys.getswitchinterval() == pytest.approx(0.001)
    assert gc.isenabled()
    assert sys.getswitchinterval() == interval


def test_cyclic_garbage_alive_during_the_work_is_still_collected_after_it():
    from memento_win.services.async_util import responsive_worker

    class Page:
        pass

    def cycle():
        page = Page()
        page.me = page
        return weakref.ref(page)

    before = cycle()  # still uncollected when the work starts
    with responsive_worker():
        during = cycle()
    gc.collect()
    assert before() is None and during() is None


def test_responsive_worker_restores_after_a_failure_and_keeps_a_disabled_collector_off():
    from memento_win.services.async_util import responsive_worker
    interval = sys.getswitchinterval()
    with pytest.raises(ValueError), responsive_worker():
        raise ValueError("unreadable file")
    assert gc.isenabled() and sys.getswitchinterval() == interval
    gc.disable()
    try:
        with responsive_worker():
            pass
        assert not gc.isenabled()  # it was off before: it stays off
    finally:
        gc.enable()


def test_responsive_worker_overlapping_workers_restore_on_the_last_exit():
    from memento_win.services.async_util import responsive_worker
    interval = sys.getswitchinterval()
    first_in, second_out = threading.Event(), threading.Event()
    seen = {}

    def first():
        with responsive_worker():
            first_in.set()
            second_out.wait(5)
            seen["after second"] = gc.isenabled()  # the other worker left first: still off for this one

    worker = threading.Thread(target=first)
    worker.start()
    assert first_in.wait(5)
    with responsive_worker():
        pass
    second_out.set()
    worker.join(5)
    assert seen["after second"] is False
    assert gc.isenabled() and sys.getswitchinterval() == interval


def test_pre_encoded_values_reach_the_page_with_the_same_json():
    from memento_win.ui.mapview import MapView, pre_encoded
    track = {"coords": [[6.86, 45.9], [6.87, 45.91]], "speeds": [1.5, None, 1e-7], "avg": 1.875, "name": "Lac d'Annecy é"}
    lines = [{"coords": [[6.86, 45.9], [6.87, 45.91]], "color": "#00b0ff"}]
    encoded = {}
    worker = threading.Thread(target=lambda: encoded.update(track=pre_encoded(track), lines=pre_encoded(lines), empty=pre_encoded([])))
    worker.start()
    worker.join(5)
    encoded_track, encoded_lines = encoded["track"], encoded["lines"]
    assert encoded_track == track and encoded_lines == lines
    assert encoded_track.page_json == json.dumps(track) and encoded_lines.page_json == json.dumps(lines)
    assert encoded_track["coords"][0] == [6.86, 45.9]
    sent = []
    fake = SimpleNamespace(page=lambda: SimpleNamespace(runJavaScript=sent.append))
    for args in ((track,), (encoded_track,), (lines,), (encoded_lines,), (encoded["empty"],), ([],)):
        MapView._run(fake, "setX", args)
    assert sent[0] == sent[1] == f"window.SMap && window.SMap.setX({json.dumps(track)})"
    assert sent[2] == sent[3] and sent[4] == sent[5] == "window.SMap && window.SMap.setX([])"
    assert pre_encoded(track) is track  # on the window's thread, MapView._run encodes it as before
