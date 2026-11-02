// @ts-check
// Hash routing: `#/<module>/<rest>?<query>`, e.g. `#/notes`, `#/notes/edit/courses-k3f9a2.md`, `#/settings`.
// The hash keeps deep links working behind any reverse proxy, with no server-side route table.
import { readable } from 'svelte/store';

/** @typedef {{ module: string, rest: string[], query: URLSearchParams }} Route */

/** @returns {Route} */
function parse() {
  const raw = location.hash.replace(/^#\/?/, '');
  const [path, search = ''] = raw.split('?');
  const parts = path.split('/').filter(Boolean).map((p) => {
    try {
      return decodeURIComponent(p);
    } catch {
      return p;
    }
  });
  return { module: parts[0] || '', rest: parts.slice(1), query: new URLSearchParams(search) };
}

export const route = readable(parse(), (set) => {
  const update = () => set(parse());
  addEventListener('hashchange', update);
  return () => removeEventListener('hashchange', update);
});

/**
 * @param {string[]} parts e.g. ['notes', 'edit', 'agenda/2026/x.md'] (each part is encoded; a part may contain '/')
 * @param {Record<string, string>} [query]
 */
export function href(parts, query) {
  const q = query && Object.keys(query).length ? `?${new URLSearchParams(query)}` : '';
  return `#/${parts.map(encodeURIComponent).join('/')}${q}`;
}

/** @param {string[]} parts @param {Record<string, string>} [query] @param {boolean} [replace] */
export function go(parts, query, replace = false) {
  const target = href(parts, query);
  if (!replace) {
    location.hash = target;
    return;
  }
  history.replaceState(null, '', target);
  // after the current update: a store changed from inside a component's reactive block would be lost
  queueMicrotask(() => dispatchEvent(new HashChangeEvent('hashchange')));
}
