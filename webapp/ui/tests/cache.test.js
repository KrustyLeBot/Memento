// The lists' cache (src/lib/cache.js): answered from memory, revalidated after a server change, emptied by a write.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createCache } from '../src/lib/cache.js';

/** A fake server: each fetch answers `${path}#${version}`, when `release()` is called if `hold` is set. */
function server() {
  const s = {
    version: 1,
    calls: /** @type {string[]} */ ([]),
    hold: false,
    /** @type {(() => void)[]} */
    waiting: [],
    release() {
      for (const fn of s.waiting.splice(0)) fn();
    },
    /** @param {string} path */
    fetch(path) {
      s.calls.push(path);
      const answer = `${path}#${s.version}`;
      if (!s.hold) return Promise.resolve(answer);
      return new Promise((resolve) => s.waiting.push(() => resolve(answer)));
    },
  };
  return s;
}
const tick = () => new Promise((resolve) => setTimeout(resolve, 0));

test('a cached list is answered without a request until its area changes', async () => {
  const s = server();
  const cache = createCache(s.fetch);
  assert.equal(await cache.get('/n', 'notes'), '/n#1');
  assert.equal(await cache.get('/n', 'notes'), '/n#1');
  assert.deepEqual(s.calls, ['/n']);
  s.version = 2;
  cache.stale(['agenda']);
  assert.equal(await cache.get('/n', 'notes'), '/n#1');
  assert.equal(s.calls.length, 1);
});

test('a change refreshes the latest entry of the area in the background; a stale read gets both answers', async () => {
  const s = server();
  const cache = createCache(s.fetch);
  await cache.get('/a?1', 'agenda');
  await cache.get('/a?2', 'agenda');
  s.version = 2;
  cache.stale(['agenda']);
  assert.deepEqual(s.calls, ['/a?1', '/a?2', '/a?2']); // only the most recently used one
  await tick();
  assert.equal(await cache.get('/a?2', 'agenda'), '/a?2#2');
  const fresh = [];
  assert.equal(await cache.get('/a?1', 'agenda', { onFresh: (d) => fresh.push(d) }), '/a?1#1'); // at once
  await tick();
  assert.deepEqual(fresh, ['/a?1#2']);
  assert.equal(await cache.get('/a?1', 'agenda'), '/a?1#2');
});

test('an answer fetched across a change is kept stale, and a request started before it is not reused', async () => {
  const s = server();
  const cache = createCache(s.fetch);
  s.hold = true;
  const first = cache.get('/c', 'contacts');
  cache.stale(['contacts']); // arrives while the first request runs
  s.version = 2;
  const second = cache.prefetch('/c', 'contacts');
  assert.equal(s.calls.length, 2);
  s.release();
  assert.equal(await first, '/c#1');
  await second;
  assert.equal(await cache.get('/c', 'contacts'), '/c#2');
});

test('a write empties the cache, even of an answer still on its way', async () => {
  const s = server();
  const cache = createCache(s.fetch);
  await cache.get('/n', 'notes');
  s.hold = true;
  cache.stale(['notes']); // a background refresh starts
  cache.forget();
  s.version = 2;
  s.release();
  await tick();
  assert.equal(cache.size(), 0);
  s.hold = false;
  assert.equal(await cache.get('/n', 'notes'), '/n#2');
});

test('a superseded read fails as aborted; the cache keeps its answer; the size is bounded', async () => {
  const s = server();
  const cache = createCache(s.fetch, 2);
  const controller = new AbortController();
  const read = cache.get('/x', 'notes', { signal: controller.signal });
  controller.abort();
  await assert.rejects(read, { name: 'AbortError' });
  assert.equal(await cache.get('/x', 'notes'), '/x#1');
  await cache.get('/y', 'notes');
  await cache.get('/z', 'notes');
  assert.equal(cache.size(), 2);
  await cache.get('/x', 'notes');
  assert.equal(s.calls.filter((p) => p === '/x').length, 2); // /x was the least recently used, dropped
});
