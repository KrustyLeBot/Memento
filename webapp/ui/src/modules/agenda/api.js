// @ts-check
// The agenda's calls to the server (memento_web/modules/agenda.py, agenda_ics.py). Every call carries the browser's
// IANA zone: it plays the part of the device zone of the apps (floating events, all-day days, entry dates).
import { request } from '../../lib/api.js';

export const ZONE = (() => {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
  } catch {
    return 'UTC';
  }
})();

const enc = (/** @type {string} */ path) => path.split('/').map(encodeURIComponent).join('/');
/** @param {Record<string, string | undefined | null>} [params] */
const qs = (params = {}) => {
  const clean = Object.fromEntries(Object.entries({ ...params, tz: ZONE }).filter(([, v]) => v !== undefined && v !== null && v !== ''));
  return `?${new URLSearchParams(/** @type {Record<string, string>} */ (clean))}`;
};

/**
 * @typedef {{ kind: 'event' | 'note' | 'activity' | 'birthday', key: string, title: string, subtitle: string | null,
 *   color: string, all_day: boolean, start: number, end: number, start_date: string, end_date: string, target_id: string,
 *   target_path: string | null, calendar_id: string | null, recurring: boolean, sport: string | null,
 *   occurrence?: { start: string, end: string | null }, tz?: string | null, end_tz?: string | null,
 *   location?: string | null, tentative?: boolean, exception?: boolean, flight?: string | null }} Entry
 * @typedef {{ path: string, id: string, title: string, body: string, all_day: boolean, start: string, end: string | null,
 *   tz: string | null, end_tz: string | null, calendar: string | null, rrule: string | null, exdate: string[],
 *   alerts: number[], location: string | null, status: string | null, recurrence_of: string | null,
 *   recurrence_at: string | null, import_id: string | null, warnings: string[], version: string, rich: boolean,
 *   links: { url: string, label: string }[], rule_text: string | null }} AgendaEvent
 * @typedef {{ id: string, name: string, color: string, visible: boolean, imported: boolean, count: number }} Calendar
 * @typedef {{ calendars: Calendar[], default: string, layers: Record<string, { visible: boolean, color: string }>,
 *   problem: string | null, version: string | null, palette: [string, string][], created?: string | null }} Calendars
 * @typedef {{ freq: string, interval: number, weekdays: number[], monthly: string, month_day: number, nth: number,
 *   nth_weekday: number, until: string | null, count: number | null }} RuleSpec
 * @typedef {{ rrule: string | null, preset: string, presets: { key: string, label: string }[], description: string,
 *   preview: string, spec: RuleSpec, summary: string | null }} RuleInfo
 */

export const agenda = {
  /** @returns {Promise<{ entries: Entry[], problems: string[] }>} */
  entries: (/** @type {string} */ from, /** @type {string} */ to, /** @type {AbortSignal | undefined} */ signal = undefined) =>
    request('GET', `/api/agenda/entries${qs({ from, to })}`, { signal }),
  /** @returns {Promise<{ entries: Entry[] }>} */
  search: (/** @type {string} */ q, /** @type {AbortSignal | undefined} */ signal = undefined) =>
    request('GET', `/api/agenda/search${qs({ q })}`, { signal }),
  /** @returns {Promise<{ zones: string[] }>} */
  zones: () => request('GET', '/api/agenda/zones'),
  /** @returns {Promise<RuleInfo>} */
  rule: (/** @type {Record<string, any>} */ body) => request('POST', `/api/agenda/rule${qs()}`, { json: body }),
  /** @returns {Promise<{ event: AgendaEvent, series: AgendaEvent | null }>} */
  get: (/** @type {string} */ path) => request('GET', `/api/agenda/events/${enc(path)}${qs()}`),
  /** @returns {Promise<{ event: AgendaEvent }>} */
  create: (/** @type {Record<string, any>} */ fields) => request('POST', `/api/agenda/events${qs()}`, { json: fields }),
  /** scope: undefined | 'one' | 'all'; occurrence: the occurrence's wall start (scope 'one'). @returns {Promise<{ event: AgendaEvent }>} */
  update: (/** @type {string} */ path, /** @type {string} */ version, /** @type {Record<string, any>} */ fields,
    /** @type {string | undefined} */ scope = undefined, /** @type {string | undefined} */ occurrence = undefined) =>
    request('PUT', `/api/agenda/events/${enc(path)}${qs({ scope, occurrence })}`, { version, json: fields }),
  /** @returns {Promise<{ undo: string, message: string }>} */
  remove: (/** @type {string} */ path, /** @type {string} */ version, /** @type {string | undefined} */ scope = undefined,
    /** @type {string | undefined} */ occurrence = undefined) =>
    request('DELETE', `/api/agenda/events/${enc(path)}${qs({ scope, occurrence })}`, { version }),
  /** "Rétablir l'original". @returns {Promise<{ undo: string }>} */
  restore: (/** @type {string} */ path, /** @type {string} */ version) =>
    request('POST', `/api/agenda/restore/${enc(path)}${qs()}`, { version }),
  /** @returns {Promise<Calendars>} */
  calendars: () => request('GET', '/api/agenda/calendars'),
  /** @returns {Promise<Calendars>} */
  changeCalendars: (/** @type {string | null} */ version, /** @type {Record<string, any>} */ change) =>
    request('PUT', '/api/agenda/calendars', { version, json: change }),
  /** @returns {Promise<{ undo: string }>} */
  deleteCalendar: (/** @type {string | null} */ version, /** @type {string} */ id, /** @type {string | null} */ moveTo) =>
    request('DELETE', `/api/agenda/calendars/${encodeURIComponent(id)}${moveTo ? `?move_to=${encodeURIComponent(moveTo)}` : ''}`, { version }),
  /** @returns {Promise<{ start: string | null, alerts: number[], version: string }>} */
  noteDate: (/** @type {string} */ path) => request('GET', `/api/agenda/dated/${enc(path)}`),
  /** start null: "Retirer de l'agenda". @returns {Promise<{ path: string, version: string }>} */
  setNoteDate: (/** @type {string} */ path, /** @type {string} */ version, /** @type {string | null} */ start, /** @type {number[]} */ alerts) =>
    request('PUT', `/api/agenda/dated/${enc(path)}`, { version, json: { start, alerts } }),
  /** @returns {Promise<{ token: string, calendars: any[], compact: boolean, problem: string | null }>} */
  upload: (/** @type {File} */ file) =>
    request('POST', `/api/agenda/import${qs({ name: file.name })}`, { body: file, headers: { 'Content-Type': 'application/octet-stream' } }),
  /** @returns {Promise<any>} */
  applyImport: (/** @type {string} */ token, /** @type {Record<string, any>} */ options) =>
    request('POST', `/api/agenda/import/${encodeURIComponent(token)}/apply`, { json: options }),
  /** @returns {Promise<{ state: string, done: number, total: number, report?: any, error?: string }>} */
  importStatus: (/** @type {string} */ token) => request('GET', `/api/agenda/import/${encodeURIComponent(token)}`),
  discardImport: (/** @type {string} */ token) => request('DELETE', `/api/agenda/import/${encodeURIComponent(token)}`),
  /** @returns {Promise<{ imports: { id: string, files: number, calendars: string[], date: string }[] }>} */
  imports: () => request('GET', '/api/agenda/imports'),
  /** @returns {Promise<{ message: string }>} */
  undoImport: (/** @type {string} */ id) => request('POST', `/api/agenda/imports/${encodeURIComponent(id)}/undo`),
  exportUrl: (/** @type {string | null} */ calendar, /** @type {boolean} */ include) =>
    `/api/agenda/export?${new URLSearchParams({ ...(calendar ? { calendar } : {}), ...(include ? { include: '1' } : {}) })}`,
};
