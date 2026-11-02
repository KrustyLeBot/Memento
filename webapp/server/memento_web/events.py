"""Live updates: the folder's cheap snapshot (names, times, sizes: core Store.snapshot) is polled every few seconds,
and each difference is pushed to every open page as a Server-Sent Event, so a sync from the phone shows up at once.

Stream format (GET /api/events):
    event: change
    data: {"areas": ["notes", "agenda"]}       areas: notes | activities | agenda | contacts
A comment line (`: ping`) goes out every HEARTBEAT seconds: Cloudflare closes a request silent for 100 s.
A write made through the API calls `poke()`, so the other tabs hear of it without waiting for the next poll.
"""
from __future__ import annotations

import asyncio
import json

from starlette.concurrency import run_in_threadpool
from starlette.requests import Request
from starlette.responses import StreamingResponse

AREAS = ("notes", "activities", "agenda", "contacts")  # the order of Store.snapshot()'s parts
HEARTBEAT = 20.0


def changed_areas(before, after) -> list[str]:
    if before is None or after is None:
        return []
    return [area for area, a, b in zip(AREAS, before, after) if a != b]


class Watcher:
    def __init__(self, data, interval: float):
        self.data = data
        self.interval = interval
        self.snapshot = None
        self._subscribers: set[asyncio.Queue] = set()
        self._wake = asyncio.Event()
        self._task: asyncio.Task | None = None

    async def start(self) -> None:
        self.snapshot = await run_in_threadpool(self.data.store.snapshot)
        self._task = asyncio.create_task(self._run())

    async def stop(self) -> None:
        if self._task is not None:
            self._task.cancel()
            try:
                await self._task
            except asyncio.CancelledError:
                pass

    def poke(self) -> None:
        self._wake.set()

    async def _run(self) -> None:
        while True:
            try:
                await asyncio.wait_for(self._wake.wait(), self.interval)
            except asyncio.TimeoutError:
                pass
            self._wake.clear()
            await self.check()
            await run_in_threadpool(self.data.trim_if_idle)

    async def check(self) -> list[str]:
        snapshot = await run_in_threadpool(self.data.store.snapshot)
        if snapshot is None:  # the folder cannot be read right now (share offline): keep the last good one
            return []
        areas = changed_areas(self.snapshot, snapshot)
        self.snapshot = snapshot
        if areas:
            self.publish("change", {"areas": areas})
        return areas

    def publish(self, event: str, payload: dict) -> None:
        message = f"event: {event}\ndata: {json.dumps(payload)}\n\n"
        for queue in list(self._subscribers):
            if queue.qsize() < 100:  # a stalled client loses events, not the server's memory
                queue.put_nowait(message)

    async def stream(self, request: Request) -> StreamingResponse:
        queue: asyncio.Queue = asyncio.Queue()
        self._subscribers.add(queue)

        async def body():
            try:
                yield "retry: 3000\nevent: hello\ndata: {}\n\n"
                while True:
                    try:
                        yield await asyncio.wait_for(queue.get(), HEARTBEAT)
                    except asyncio.TimeoutError:
                        if await request.is_disconnected():
                            return
                        yield ": ping\n\n"
            finally:
                self._subscribers.discard(queue)

        return StreamingResponse(body(), media_type="text/event-stream",
                                 headers={"Cache-Control": "no-store", "X-Accel-Buffering": "no"})
