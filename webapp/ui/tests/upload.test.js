// sendFile (src/lib/api.js): an aborted signal stops the upload, before it starts or while it is being sent.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { sendFile } from '../src/lib/api.js';

/** A fake XMLHttpRequest that never answers by itself: records what was sent and whether it was aborted. */
class FakeXhr {
  /** @type {FakeXhr[]} */
  static made = [];
  constructor() {
    this.upload = {};
    this.sent = false;
    this.aborted = false;
    /** @type {(() => void) | null} */
    this.onabort = null;
    FakeXhr.made.push(this);
  }
  open() {}
  setRequestHeader() {}
  send() {
    this.sent = true;
  }
  abort() {
    this.aborted = true;
    this.onabort?.();
  }
}
globalThis.XMLHttpRequest = /** @type {any} */ (FakeXhr);

const isAbort = (/** @type {any} */ e) => e instanceof DOMException && e.name === 'AbortError';

test('an already aborted upload sends nothing', async () => {
  FakeXhr.made = [];
  const controller = new AbortController();
  controller.abort();
  await assert.rejects(sendFile('/api/x', new Blob(['abc']), { gzip: true, signal: controller.signal }), isAbort);
  assert.equal(FakeXhr.made.length, 0);
});

test('aborting during the send aborts the request', async () => {
  FakeXhr.made = [];
  const controller = new AbortController();
  const sending = sendFile('/api/x', new Blob(['abc']), { signal: controller.signal });
  await new Promise((resolve) => setTimeout(resolve, 0));
  assert.equal(FakeXhr.made.length, 1);
  assert.ok(FakeXhr.made[0].sent);
  controller.abort();
  await assert.rejects(sending, isAbort);
  assert.ok(FakeXhr.made[0].aborted);
});
