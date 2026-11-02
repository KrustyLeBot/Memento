// @ts-check
// The contacts API and the module's small rules (texts, links, the editor's form), as in
// windows/memento_win/ui/contacts_view.py. Sorting, sections, search, merge and import are the server's (the core's).
import { request } from '../../lib/api.js';

/**
 * @typedef {{ ref: string, id: string, name: string, second: string, favorite: boolean, conflict: boolean }} ContactSummary
 * @typedef {{ number: string, label: string | null }} Phone
 * @typedef {{ day: number, month: number, year: number | null }} Birthday
 * @typedef {{ ref: string, id: string, path: string, name: string, first_name: string, last_name: string,
 *   phones: Phone[], emails: string[], address: string | null, birthday: Birthday | null, notes: string,
 *   favorite: boolean, conflict: boolean, created: number, updated: number }} Contact
 * @typedef {{ contact: Contact, version: string }} VersionedContact
 * @typedef {{ title: string, contacts: ContactSummary[] }} Section
 * @typedef {{ token: string, name: string, cards: number, duplicates: number, blank: number, bad: number, empty: number,
 *   ignored: Record<string, number>, policies: { key: string, label: string }[] }} ImportPreview
 * @typedef {{ created: number, updated: number, unchanged: number, skipped: number, bad: number, empty: number,
 *   ignored: Record<string, number> }} ImportReport
 * @typedef {{ done: number, total: number, finished: boolean, undo: string, report?: ImportReport }} ImportProgress
 * @typedef {{ keep: string, contacts: (ContactSummary & { details: string[] })[] }} DuplicateGroup
 */

const enc = encodeURIComponent;
const first = (/** @type {boolean} */ firstNameFirst) => (firstNameFirst ? '?first=1' : '');

export const api = {
  /** @returns {Promise<{ sections: Section[], count: number, results: number | null }>} */
  list: (q = '', firstNameFirst = false, /** @type {AbortSignal | undefined} */ signal = undefined) =>
    request('GET', `/api/contacts?${new URLSearchParams({ q, ...(firstNameFirst ? { first: '1' } : {}) })}`, { signal }),
  /** @returns {Promise<VersionedContact>} */
  get: (/** @type {string} */ ref) => request('GET', `/api/contacts/${enc(ref)}`),
  /** @returns {Promise<VersionedContact>} */
  create: (/** @type {object} */ fields) => request('POST', '/api/contacts', { json: fields }),
  /** @returns {Promise<VersionedContact>} */
  update: (/** @type {string} */ ref, /** @type {string} */ version, /** @type {object} */ fields) =>
    request('PUT', `/api/contacts/${enc(ref)}`, { version, json: fields }),
  /** @returns {Promise<{ undo: string }>} */
  remove: (/** @type {string} */ ref, /** @type {string} */ version) => request('DELETE', `/api/contacts/${enc(ref)}`, { version }),
  /** @returns {Promise<{ groups: DuplicateGroup[] }>} */
  duplicates: (firstNameFirst = false) => request('GET', `/api/contacts/duplicates${first(firstNameFirst)}`),
  /** @returns {Promise<{ undo: string, ref: string }>} */
  merge: (/** @type {string} */ keep, /** @type {string[]} */ others) => request('POST', '/api/contacts/merge', { json: { keep, others } }),
  /** @returns {Promise<ImportPreview>} */
  upload: (/** @type {File} */ file) => request('POST', `/api/contacts/import?${new URLSearchParams({ name: file.name })}`,
    { body: file, headers: { 'Content-Type': 'application/octet-stream' } }),
  /** @returns {Promise<ImportProgress>} */
  start: (/** @type {string} */ token, /** @type {string} */ policy) =>
    request('POST', `/api/contacts/import/${enc(token)}/start`, { json: { policy } }),
  /** @returns {Promise<ImportProgress>} */
  step: (/** @type {string} */ token) => request('POST', `/api/contacts/import/${enc(token)}/step`),
  exportAllUrl: (firstNameFirst = false) => `/api/contacts/export/contacts.vcf${first(firstNameFirst)}`,
  exportOneUrl: (/** @type {string} */ ref) => `/api/contacts/${enc(ref)}/vcard`,
};

export const MONTHS = ['janvier', 'février', 'mars', 'avril', 'mai', 'juin', 'juillet', 'août', 'septembre', 'octobre',
  'novembre', 'décembre'];
/** The core's STANDARD_LABELS, offered in the editor; any other text is kept as typed. */
export const LABELS = ['mobile', 'domicile', 'travail', 'autre'];
export const FAVORITES = 'Favoris';

/** 1er mai 1990, 14 mai */
export function birthdayText(/** @type {Birthday} */ b) {
  return `${b.day}${b.day === 1 ? 'er' : ''} ${MONTHS[b.month - 1]}${b.year ? ` ${b.year}` : ''}`;
}

/** Age today, or null without a year (a 29 February counts on the 28th in other years, as core occurrence()). */
export function age(/** @type {Birthday} */ b, today = new Date()) {
  if (!b.year) return null;
  const y = today.getFullYear();
  const leap = (y % 4 === 0 && y % 100 !== 0) || y % 400 === 0;
  const day = b.month === 2 && b.day === 29 && !leap ? 28 : b.day;
  const m = today.getMonth() + 1;
  const reached = m > b.month || (m === b.month && today.getDate() >= day);
  const years = y - b.year - (reached ? 0 : 1);
  return years >= 0 ? years : null;
}

export const plural = (/** @type {number} */ n, /** @type {string} */ one, /** @type {string} */ many) => `${n} ${n > 1 ? many : one}`;

export const countLabel = (/** @type {number} */ n) => plural(n, 'contact', 'contacts');

export function ignoredText(/** @type {Record<string, number>} */ ignored) {
  return Object.entries(ignored).sort((a, b) => b[1] - a[1]).map(([k, v]) => `${k} (${v})`).join(', ');
}

/** The lines of the import preview, as the Windows dialog. */
export function previewLines(/** @type {ImportPreview} */ p) {
  /** @type {string[]} */
  const lines = [];
  for (const [n, one, many] of /** @type {[number, string, string][]} */ ([
    [p.duplicates, 'ressemble à un contact déjà présent', 'ressemblent à des contacts déjà présents'],
    [p.blank, 'fiche sans nom ni numéro', 'fiches sans nom ni numéro'],
    [p.bad, 'fiche illisible (ignorée)', 'fiches illisibles (ignorées)'],
    [p.empty, 'fiche vide (ignorée)', 'fiches vides (ignorées)']])) {
    if (n) lines.push(plural(n, one, many));
  }
  if (Object.keys(p.ignored).length) lines.push(`Non repris : ${ignoredText(p.ignored)}`);
  return lines;
}

/** The report at the end of an import (core report_text). */
export function reportLines(/** @type {ImportReport} */ r) {
  const lines = [plural(r.created, 'contact créé', 'contacts créés')];
  if (r.updated) lines.push(plural(r.updated, 'contact mis à jour', 'contacts mis à jour'));
  if (r.unchanged) lines.push(plural(r.unchanged, 'déjà à jour', 'déjà à jour'));
  if (r.skipped) lines.push(plural(r.skipped, 'doublon non importé', 'doublons non importés'));
  if (r.bad) lines.push(plural(r.bad, 'fiche illisible ignorée', 'fiches illisibles ignorées'));
  if (r.empty) lines.push(plural(r.empty, 'fiche vide ignorée', 'fiches vides ignorées'));
  if (Object.keys(r.ignored).length) lines.push(`Non repris : ${ignoredText(r.ignored)}`);
  return lines;
}

/** tel:+33612345678 — the dialler wants no spaces or punctuation. */
export function telHref(/** @type {string} */ number) {
  const plus = number.trim().startsWith('+') ? '+' : '';
  return `tel:${plus}${number.replace(/[^\d*#]/g, '')}`;
}

export const smsHref = (/** @type {string} */ number) => telHref(number).replace(/^tel:/, 'sms:');

/** The phone's own maps app where there is a scheme for it, OpenStreetMap elsewhere. */
export function mapsHref(/** @type {string} */ address, userAgent = typeof navigator === 'undefined' ? '' : navigator.userAgent) {
  const q = encodeURIComponent(address);
  if (/Android/i.test(userAgent)) return `geo:0,0?q=${q}`;
  if (/iPhone|iPad|iPod/i.test(userAgent)) return `https://maps.apple.com/?q=${q}`;
  return `https://www.openstreetmap.org/search?query=${q}`;
}

/** Initials for the avatar: the first letter of the first two words of the name. */
export function initials(/** @type {string} */ name) {
  const words = name.replace(/[^\p{L}\p{N}\s]/gu, ' ').trim().split(/\s+/).filter((w) => /\p{L}/u.test(w[0] || ''));
  return words.slice(0, 2).map((w) => w[0].toUpperCase()).join('') || '#';
}

/**
 * The editor's form: strings everywhere, a phone row even when there is none.
 * @typedef {{ first_name: string, last_name: string, phones: { number: string, label: string }[], emails: string[],
 *   address: string, day: string, month: string, year: string, notes: string, favorite: boolean }} Form
 */

/** @param {Contact | null} c @returns {Form} */
export function toForm(c) {
  return {
    first_name: c?.first_name ?? '', last_name: c?.last_name ?? '',
    phones: c?.phones.length ? c.phones.map((p) => ({ number: p.number, label: p.label ?? '' })) : [{ number: '', label: 'mobile' }],
    emails: c?.emails.length ? [...c.emails] : [''],
    address: c?.address ?? '',
    day: c?.birthday ? String(c.birthday.day) : '', month: c?.birthday ? String(c.birthday.month) : '',
    year: c?.birthday?.year ? String(c.birthday.year) : '',
    notes: c?.notes ?? '', favorite: c?.favorite ?? false,
  };
}

/** The fields sent to the server; throws an Error with the French message when the birthday is incomplete. */
export function fromForm(/** @type {Form} */ f) {
  const day = f.day.trim(), month = f.month.trim(), year = f.year.trim();
  /** @type {Birthday | null} */
  let birthday = null;
  if (day || month || year) {
    if (!day || !month) throw new Error("Jour et mois de l'anniversaire");
    if (!/^\d+$/.test(day)) throw new Error("Date d'anniversaire invalide");
    if (year && !/^\d{1,4}$/.test(year)) throw new Error('Année invalide');
    birthday = { day: Number(day), month: Number(month), year: year ? Number(year) : null };
  }
  return {
    first_name: f.first_name.trim(), last_name: f.last_name.trim(),
    phones: f.phones.filter((p) => p.number.trim()).map((p) => ({ number: p.number.trim(), label: p.label.trim() || null })),
    emails: f.emails.map((e) => e.trim()).filter(Boolean),
    address: f.address.trim() || null, birthday, notes: f.notes.replace(/\s+$/, ''), favorite: f.favorite,
  };
}

/** Same test as core Contact.is_empty, so the editor can say it before asking the server. */
export function isEmpty(/** @type {ReturnType<typeof fromForm>} */ x) {
  return !x.first_name && !x.last_name && !x.phones.length && !x.emails.length && !x.address && !x.birthday && !x.notes.trim();
}
