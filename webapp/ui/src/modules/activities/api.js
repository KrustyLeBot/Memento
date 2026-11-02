// @ts-check
// The Activités endpoints (server: memento_web/modules/activities.py). Photos go through the notes' attachment
// endpoints: an activity is a note of activities/.
import { lists, request } from '../../lib/api.js';

/** @typedef {import('./activities.js').ActivitySummary} ActivitySummary */
/** @typedef {ActivitySummary & { description: string, file: string | null, alt: string | null, photos: string[],
 *   files: string[], analysis_key: string | null }} Activity */
/** @typedef {{ activity: Activity, version: string }} VersionedActivity */
/** @typedef {{ id: string, kind: string, total: number, done: number, text: string, finished: boolean,
 *   error: string | null, results: { name?: string, id?: string, path?: string, title?: string, error?: string,
 *   warning?: string, replaced?: boolean }[] }} Job */
/** @typedef {{ token: string, name: string, title: string, sport: string, start: number | null, points: number,
 *   needs_altitude: boolean, existing: { id: string, path: string, title: string } | null }} Staged */

const key = (/** @type {string} */ k) => encodeURIComponent(k);

export const activities = {
  /** @returns {Promise<{ activities: ActivitySummary[], totals: string[], sports: { key: string, label: string, has_gps: boolean }[] }>} */
  list: (/** @type {((data: any) => void) | undefined} */ onFresh = undefined) =>
    lists.get('/api/activities', 'activities', { onFresh }),
  prefetch: () => lists.prefetch('/api/activities', 'activities'),
  /** @returns {Promise<VersionedActivity>} */
  get: (/** @type {string} */ id) => request('GET', `/api/activities/${key(id)}`),
  /** @returns {Promise<any>} */
  analysis: (/** @type {string} */ id, /** @type {AbortSignal | undefined} */ signal = undefined) =>
    request('GET', `/api/activities/${key(id)}/analysis`, { signal }),
  /** @returns {Promise<VersionedActivity>} */
  update: (/** @type {string} */ id, /** @type {string} */ version, /** @type {Record<string, any>} */ changes) =>
    request('PUT', `/api/activities/${key(id)}`, { version, json: changes }),
  /** @returns {Promise<{ undo: string }>} */
  remove: (/** @type {string} */ id, /** @type {string} */ version) => request('DELETE', `/api/activities/${key(id)}`, { version }),
  /** @returns {Promise<Staged>} */
  upload: (/** @type {File} */ file) => request('POST', `/api/activities/uploads?${new URLSearchParams({ name: file.name })}`,
    { body: file, headers: { 'Content-Type': 'application/octet-stream' } }),
  /** @returns {Promise<Job>} */
  importStaged: (/** @type {{ token: string, name: string, title: string, sport: string, altitude: boolean }[]} */ items) =>
    request('POST', '/api/activities/imports', { json: { items } }),
  /** @returns {Promise<Job>} */
  completeAltitude: (/** @type {string[] | null} */ ids) =>
    request('POST', '/api/activities/altitude', { json: ids ? { ids } : { all: true } }),
  /** @returns {Promise<Job>} */
  job: (/** @type {string} */ id) => request('GET', `/api/activities/jobs/${key(id)}`),
};
