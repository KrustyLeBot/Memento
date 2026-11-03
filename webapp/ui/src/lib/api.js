// @ts-check
// The only way the UI talks to the server. Conventions every module follows:
// - JSON in and out; an error is `{ error: "<French message>" }` with an HTTP status, thrown here as ApiError
//   (show it with `showError`, never swallow it).
// - Every mutating request carries `X-Memento: 1` (the server's CSRF rule); `request` adds it.
// - A file the user edits is versioned: GET returns `version`, the write sends it back (`version` option →
//   If-Match), and a 409 carries `current` = the file as it is now ({...,version}) or null if it was deleted.
// - A 401 anywhere means the session ended: `session` is cleared and the login screen shows.
// - The modules' root lists go through `lists` (lib/cache.js): shown at once from memory, refreshed in the background
//   on a server change; any write from this tab empties it.
import { writable } from 'svelte/store';
import { createCache } from './cache.js';

/** @typedef {{ user: string, expires: number }} Session */
/** @type {import('svelte/store').Writable<Session | null | undefined>} undefined = not checked yet */
export const session = writable(undefined);

// Requests that change nothing although they are not GETs.
const READ_ONLY = ['/api/agenda/rule'];

export class ApiError extends Error {
  /** @param {number} status @param {string} message @param {any} body */
  constructor(status, message, body) {
    super(message);
    this.status = status;
    this.body = body;
  }
}

/**
 * @param {string} method
 * @param {string} path
 * @param {{ json?: any, body?: BodyInit, version?: string | null, headers?: Record<string, string>, signal?: AbortSignal }} [options]
 * @returns {Promise<any>}
 */
export async function request(method, path, options = {}) {
  /** @type {Record<string, string>} */
  const headers = { Accept: 'application/json', ...(options.headers || {}) };
  if (method !== 'GET') headers['X-Memento'] = '1';
  if (options.version !== undefined) headers['If-Match'] = options.version ?? 'none';
  let body = options.body;
  if (options.json !== undefined) {
    headers['Content-Type'] = 'application/json';
    body = JSON.stringify(options.json);
  }
  let response;
  try {
    response = await fetch(path, { method, headers, body, credentials: 'same-origin', signal: options.signal });
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') throw error;
    throw new ApiError(0, 'Serveur injoignable : vérifiez la connexion.', null);
  }
  return answer(method, path, response.status, await response.text());
}

/** The common end of `request` and `sendFile`: the parsed body, or the ApiError. */
function answer(/** @type {string} */ method, /** @type {string} */ path, /** @type {number} */ status, /** @type {string} */ text) {
  let data = null;
  try {
    data = text ? JSON.parse(text) : null;
  } catch {
    data = null;
  }
  if (method !== 'GET' && !READ_ONLY.some((p) => path.startsWith(p))) lists.forget();
  if (status < 200 || status >= 300) {
    if (status === 401 && path !== '/api/login') session.set(null);
    const message = (data && data.error) || `Erreur ${status}${text && !data ? ` : ${text.slice(0, 200)}` : ''}`;
    throw new ApiError(status, message, data);
  }
  return data;
}

/** gzip in the browser (a FIT shrinks 3 times, a GPX 5 to 8): far less to send over a home connection's upload.
 * Null when the browser cannot, or the result is no smaller. @param {Blob} file @returns {Promise<Blob | null>} */
export async function gzipped(file) {
  if (typeof CompressionStream !== 'function') return null;
  try {
    const packed = await new Response(file.stream().pipeThrough(new CompressionStream('gzip'))).blob();
    return packed.size < file.size ? packed : null;
  } catch {
    return null;
  }
}

/**
 * POSTs a file as the request body, reporting the upload's progress (fetch cannot), gzipped first when `gzip`
 * (only for endpoints that accept `Content-Encoding: gzip`).
 * @param {string} path @param {File} file
 * @param {{ gzip?: boolean, onProgress?: (fraction: number, stage: 'pack' | 'send' | 'wait') => void,
 *   signal?: AbortSignal }} [options]
 * @returns {Promise<any>}
 */
export async function sendFile(path, file, options = {}) {
  const progress = options.onProgress || (() => {});
  const { signal } = options;
  const aborted = () => new DOMException('Aborted', 'AbortError');
  if (signal?.aborted) throw aborted();
  /** @type {Blob} */
  let body = file;
  /** @type {Record<string, string>} */
  const headers = { Accept: 'application/json', 'Content-Type': 'application/octet-stream', 'X-Memento': '1' };
  if (options.gzip) {
    progress(0, 'pack');
    const packed = await gzipped(file);
    if (packed) {
      body = packed;
      headers['Content-Encoding'] = 'gzip';
    }
  }
  if (signal?.aborted) throw aborted();
  progress(0, 'send');
  const { status, text } = await new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    signal?.addEventListener('abort', () => xhr.abort(), { once: true });
    xhr.onabort = () => reject(aborted());
    xhr.open('POST', path);
    for (const [k, v] of Object.entries(headers)) xhr.setRequestHeader(k, v);
    xhr.upload.onprogress = (e) => { if (e.lengthComputable) progress(e.loaded / e.total, 'send'); };
    // the bytes are all sent: the server reads the file now (seconds for a long race)
    xhr.upload.onload = () => progress(1, 'wait');
    xhr.onload = () => resolve({ status: xhr.status, text: xhr.responseText });
    xhr.onerror = () => reject(new ApiError(0, 'Serveur injoignable : vérifiez la connexion.', null));
    xhr.send(body);
  });
  return answer('POST', path, status, text);
}

/** The root lists' cache: `lists.get(path, area, { signal, onFresh })`, `lists.prefetch(path, area)`. */
export const lists = createCache((path) => request('GET', path));
session.subscribe((s) => {
  if (!s) lists.forget();
});

const enc = (/** @type {string} */ path) => path.split('/').map(encodeURIComponent).join('/');

/** URL of a file of the data folder (attachments, scans, drawings), to open or show in an <img>. */
export const fileUrl = (/** @type {string} */ path, download = false) => `/api/files/${enc(path)}${download ? '?download=1' : ''}`;

export const auth = {
  /** @returns {Promise<Session>} */
  me: () => request('GET', '/api/me'),
  /** @returns {Promise<Session>} */
  login: (/** @type {string} */ user, /** @type {string} */ password) => request('POST', '/api/login', { json: { user, password } }),
  logout: () => request('POST', '/api/logout'),
};

/**
 * @typedef {{ path: string, id: string, title: string, preview: string, color: string, pinned: boolean,
 *   labels: string[], pdf: string | null, attachments: string[], conflict: boolean, created: number, updated: number,
 *   checklist: { done: number, total: number } | null }} NoteSummary
 * @typedef {{ text: string, checked: boolean }} CheckItem
 * @typedef {{ before: string, items: CheckItem[], after: string }} BodyParts
 * @typedef {NoteSummary & { body: string, parts: BodyParts }} Note
 * @typedef {{ note: Note, version: string }} VersionedNote
 * @typedef {{ title?: string, parts?: BodyParts, color?: string, pinned?: boolean, labels?: string[] }} NoteChanges
 * @typedef {{ color: string, width: number, points: [number, number][] }} Stroke
 */

export const notes = {
  /** Without a search, from the cache (`onFresh`: the newer answer when the cached one was stale).
   * @returns {Promise<{ notes: NoteSummary[], labels: string[], conflicts: number }>} */
  list: (q = '', label = '', /** @type {AbortSignal | undefined} */ signal = undefined,
    /** @type {((data: any) => void) | undefined} */ onFresh = undefined) => {
    const path = `/api/notes?${new URLSearchParams({ q, label })}`;
    return q ? request('GET', path, { signal }) : lists.get(path, 'notes', { signal, onFresh });
  },
  prefetch: () => {
    const q = sessionStorage.getItem('memento.notes.q') || '';
    const label = sessionStorage.getItem('memento.notes.label') || '';
    return q.trim() ? Promise.resolve() : lists.prefetch(`/api/notes?${new URLSearchParams({ q: '', label })}`, 'notes');
  },
  /** @returns {Promise<VersionedNote>} */
  get: (/** @type {string} */ path) => request('GET', `/api/notes/${enc(path)}`),
  /** @returns {Promise<VersionedNote>} */
  create: (/** @type {NoteChanges} */ changes) => request('POST', '/api/notes', { json: changes }),
  /** @returns {Promise<VersionedNote>} */
  update: (/** @type {string} */ path, /** @type {string} */ version, /** @type {NoteChanges} */ changes) =>
    request('PUT', `/api/notes/${enc(path)}`, { version, json: changes }),
  /** @returns {Promise<{ undo: string }>} */
  remove: (/** @type {string} */ path, /** @type {string} */ version) => request('DELETE', `/api/notes/${enc(path)}`, { version }),
  /** @returns {Promise<VersionedNote>} */
  attach: (/** @type {string} */ path, /** @type {string} */ version, /** @type {File} */ file) =>
    request('POST', `/api/notes/${enc(path)}/attachments?${new URLSearchParams({ name: file.name })}`,
      { version, body: file, headers: { 'Content-Type': 'application/octet-stream' } }),
  /** @returns {Promise<VersionedNote>} */
  detach: (/** @type {string} */ path, /** @type {string} */ version, /** @type {string} */ file) =>
    request('DELETE', `/api/notes/${enc(path)}/attachments?${new URLSearchParams({ file })}`, { version }),
  /** @returns {Promise<{ strokes: Stroke[], version: string }>} */
  drawing: (/** @type {string} */ file) => request('GET', `/api/drawings/${enc(file)}`),
  /** file null: a new drawing; no stroke: the drawing is removed. @returns {Promise<VersionedNote>} */
  saveDrawing: (/** @type {string} */ path, /** @type {string} */ version, /** @type {string | null} */ file, /** @type {Stroke[]} */ strokes) =>
    request('POST', `/api/notes/${enc(path)}/drawings`, { version, json: { file, strokes } }),
};

/** @typedef {{ path: string, original: string, area: string, original_exists: boolean }} Conflict */
export const conflicts = {
  /** @returns {Promise<{ conflicts: Conflict[] }>} */
  list: () => request('GET', '/api/conflicts'),
  /** @returns {Promise<{ undo: string, path: string | null }>} */
  resolve: (/** @type {string} */ path, /** @type {'keep' | 'discard'} */ action) =>
    request('POST', '/api/conflicts/resolve', { json: { path, action } }),
};

/** Puts back what a delete (or another undoable action) changed. */
export const undo = (/** @type {string} */ token) => request('POST', `/api/undo/${encodeURIComponent(token)}`);

/** @typedef {{ refine_altitude: boolean, totals: string[], sports: { key: string, label: string }[], version: string | null }} ActivitySettings */
export const settings = {
  /** @returns {Promise<ActivitySettings>} */
  activities: () => request('GET', '/api/settings/activities'),
  /** @returns {Promise<ActivitySettings>} */
  saveActivities: (/** @type {string | null} */ version, /** @type {{ refine_altitude: boolean, totals: string[] }} */ value) =>
    request('PUT', '/api/settings/activities', { version, json: value }),
  /** The map's tile source (MEMENTO_TILES_* on the server) and, for Tuileries, its /api/info or the error. */
  mapTiles: () => request('GET', '/api/map/tiles'),
};
