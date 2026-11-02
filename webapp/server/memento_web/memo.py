"""The last answers of the list endpoints, kept while the objects they were made from are the same ones: the core's
scans return the very same parsed objects until a file changes, so an unchanged folder answers without shaping
hundreds of summaries again. Holding the objects keeps the identity check sound (no id reused)."""
from __future__ import annotations

import threading
from collections import OrderedDict

from starlette.responses import JSONResponse, Response


class Answers:
    def __init__(self, size: int = 4):
        self.size = size
        self._items: OrderedDict = OrderedDict()
        self._guard = threading.Lock()

    def clear(self) -> None:
        with self._guard:
            self._items.clear()

    def body(self, key, inputs: list, compute) -> bytes:
        """The JSON bytes of compute() for `key`, recomputed only when `inputs` are not the same objects as last time
        (compared by identity, in order)."""
        with self._guard:
            hit = self._items.get(key)
            if hit is not None and len(hit[0]) == len(inputs) and all(a is b for a, b in zip(hit[0], inputs)):
                self._items.move_to_end(key)
                return hit[1]
        body = JSONResponse(compute()).body
        with self._guard:
            self._items[key] = (list(inputs), body)
            self._items.move_to_end(key)
            while len(self._items) > self.size:
                self._items.popitem(last=False)
        return body


def json_response(body: bytes) -> Response:
    return Response(body, media_type="application/json")
