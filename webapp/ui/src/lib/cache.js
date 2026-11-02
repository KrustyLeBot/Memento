// @ts-check
// The lists' last answers, kept in memory so that switching modules shows them at once (stale-while-revalidate):
// - get(path, area) answers from the cache when it can; an entry made stale by a change of its area (server event)
//   is answered as is AND fetched again in the background, `onFresh` getting the new answer;
// - stale(areas) (a change pushed by the server) marks the areas' entries stale and refreshes in the background the
//   most recently used entry of each, the one the user is most likely to see next;
// - forget() (a write from this tab, a session change) drops everything: the next read waits for the server, so a
//   page never shows a list from before its own change.
// Pure (the fetching function is given), so the tests run it in node.

/** @param {string} message */
const abortError = (message) => new DOMException(message, 'AbortError');

/**
 * @param {(path: string) => Promise<any>} fetch
 * @param {number} [max] entries kept (least recently used dropped first)
 */
export function createCache(fetch, max = 24) {
  /** @type {Map<string, { area: string, data: any, stale: boolean, seq: number }>} in use order, most recent last */
  const items = new Map();
  /** @type {Map<string, { promise: Promise<any>, seen: number }>} */
  const pending = new Map();
  /** @type {Map<string, number>} bumped by stale(): an answer fetched across a change is stored stale */
  const changes = new Map();
  let epoch = 0; // bumped by forget(): an answer fetched across it is not stored at all
  let seq = 0; // an answer never replaces one fetched after it

  /** @param {string} path @param {string} area @returns {Promise<any>} */
  function load(path, area) {
    const seen = changes.get(area) || 0;
    const running = pending.get(path);
    if (running && running.seen === seen) return running.promise; // started after the last change: good enough
    const started = epoch;
    const mine = ++seq;
    const promise = fetch(path).then((data) => {
      const current = items.get(path);
      if (epoch === started && !(current && current.seq > mine)) {
        items.delete(path);
        items.set(path, { area, data, stale: (changes.get(area) || 0) !== seen, seq: mine });
        while (items.size > max) items.delete(/** @type {string} */ (items.keys().next().value));
      }
      return data;
    }).finally(() => {
      if (pending.get(path)?.promise === promise) pending.delete(path);
    });
    pending.set(path, { promise, seen });
    return promise;
  }

  /**
   * @param {string} path
   * @param {string} area 'notes' | 'activities' | 'agenda' | 'contacts'
   * @param {{ signal?: AbortSignal, onFresh?: (data: any) => void }} [options]
   * @returns {Promise<any>}
   */
  function get(path, area, options = {}) {
    const { signal, onFresh } = options;
    const item = items.get(path);
    if (!item) {
      return load(path, area).then((data) => {
        if (signal?.aborted) throw abortError('superseded');
        return data;
      });
    }
    items.delete(path);
    items.set(path, item);
    if (item.stale) {
      load(path, area).then((data) => {
        if (!signal?.aborted) onFresh?.(data);
      }, (error) => console.error(error)); // the page keeps what it shows; the next change retries
    }
    return Promise.resolve(item.data);
  }

  /** Fetches `path` into the cache unless a fresh answer is there; never fails (a hint, not a need). */
  function prefetch(/** @type {string} */ path, /** @type {string} */ area) {
    const item = items.get(path);
    if (item && !item.stale) return Promise.resolve();
    return load(path, area).then(() => {}, () => {});
  }

  /** @param {string[]} areas */
  function stale(areas) {
    for (const area of areas) changes.set(area, (changes.get(area) || 0) + 1);
    /** @type {Map<string, string>} area -> its most recently used path */
    const latest = new Map();
    for (const [path, item] of items) {
      if (!areas.includes(item.area)) continue;
      item.stale = true;
      latest.set(item.area, path);
    }
    for (const [area, path] of latest) load(path, area).catch(() => {});
  }

  function forget() {
    epoch += 1;
    items.clear();
    pending.clear();
  }

  return { get, prefetch, stale, forget, size: () => items.size };
}
