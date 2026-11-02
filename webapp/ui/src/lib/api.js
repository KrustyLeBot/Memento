// @ts-check
// The only way the UI talks to the server. Conventions every module follows:
// - JSON in and out; an error is `{ error: "<French message>" }` with an HTTP status, thrown here as ApiError
//   (show it with `showError`, never swallow it).
// - Every mutating request carries `X-Memento: 1` (the server's CSRF rule); `request` adds it.
// - A file the user edits is versioned: GET returns `version`, the write sends it back (`version` option →
//   If-Match), and a 409 carries `current` = the file as it is now ({...,version}) or null if it was deleted.
// - A 401 anywhere means the session ended: `session` is cleared and the login screen shows.
import { writable } from 'svelte/store';

/** @typedef {{ user: string, expires: number }} Session */
/** @type {import('svelte/store').Writable<Session | null | undefined>} undefined = not checked yet */
export const session = writable(undefined);

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
  const text = await response.text();
  let data = null;
  try {
    data = text ? JSON.parse(text) : null;
  } catch {
    data = null;
  }
  if (!response.ok) {
    if (response.status === 401 && path !== '/api/login') session.set(null);
    const message = (data && data.error) || `Erreur ${response.status}${text && !data ? ` : ${text.slice(0, 200)}` : ''}`;
    throw new ApiError(response.status, message, data);
  }
  return data;
}

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
  /** @returns {Promise<{ notes: NoteSummary[], labels: string[], conflicts: number }>} */
  list: (q = '', label = '', /** @type {AbortSignal | undefined} */ signal = undefined) =>
    request('GET', `/api/notes?${new URLSearchParams({ q, label })}`, { signal }),
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
};
