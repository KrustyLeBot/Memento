// @ts-check
// Shared UI state: this browser's preferences (like the Windows app's settings of the PC), the snackbar and the
// modal dialogs. Components read the stores; modules call the functions.
import { writable, get } from 'svelte/store';
import { ApiError } from './api.js';

const PREFS_KEY = 'memento.prefs';

/**
 * Per browser, never synced: theme, notes grid/list, hidden modules. Modules may add their own keys
 * (`agenda.weekStart`, `contacts.firstNameFirst`…) with `setPref`.
 * @typedef {{ theme: 'system' | 'light' | 'dark', grid: boolean, hidden: string[], [key: string]: any }} Prefs
 */
/** @type {Prefs} */
const DEFAULTS = { theme: 'system', grid: true, hidden: [] };

function loadPrefs() {
  try {
    return { ...DEFAULTS, ...JSON.parse(localStorage.getItem(PREFS_KEY) || '{}') };
  } catch {
    return { ...DEFAULTS };
  }
}

export const prefs = writable(/** @type {Prefs} */ (loadPrefs()));
prefs.subscribe((value) => {
  try {
    localStorage.setItem(PREFS_KEY, JSON.stringify(value));
  } catch {
    // private window or storage blocked: the preferences last for this tab only
  }
});

/** @param {string} key @param {any} value */
export function setPref(key, value) {
  prefs.update((p) => ({ ...p, [key]: value }));
}

/** 'light' | 'dark' actually shown, following the system when the preference is 'system'. */
export const dark = writable(false);
const media = typeof matchMedia === 'function' ? matchMedia('(prefers-color-scheme: dark)') : null;
function applyTheme() {
  const mode = get(prefs).theme;
  const isDark = mode === 'dark' || (mode === 'system' && !!media?.matches);
  dark.set(isDark);
  document.documentElement.dataset.theme = isDark ? 'dark' : 'light';
}
prefs.subscribe(applyTheme);
media?.addEventListener('change', applyTheme);

// ---- snackbar ----

/** @typedef {{ id: number, text: string, action?: { label: string, run: () => any }, error: boolean }} Snack */
export const snack = writable(/** @type {Snack | null} */ (null));
let snackId = 0;
/** @type {any} */
let snackTimer = null;

/**
 * A short message at the bottom, optionally with an action ("Annuler").
 * @param {string} text
 * @param {{ action?: { label: string, run: () => any }, error?: boolean, duration?: number }} [options]
 */
export function showSnack(text, options = {}) {
  const id = ++snackId;
  snack.set({ id, text, action: options.action, error: !!options.error });
  clearTimeout(snackTimer);
  snackTimer = setTimeout(() => snack.update((s) => (s && s.id === id ? null : s)), options.duration ?? (options.error ? 10000 : 6000));
}

export function hideSnack() {
  clearTimeout(snackTimer);
  snack.set(null);
}

/** Every failure reaches the user: the server's message, or the exception's. @param {unknown} error */
export function showError(error, prefix = '') {
  if (error instanceof DOMException && error.name === 'AbortError') return;
  if (error instanceof ApiError && error.status === 401) return; // the login screen says it
  const message = error instanceof Error ? error.message : String(error);
  console.error(error);
  showSnack(prefix ? `${prefix} : ${message}` : message, { error: true });
}

// ---- dialogs ----

/**
 * @typedef {{ key: string, label: string, kind?: 'primary' | 'danger' | 'plain' }} Choice
 * @typedef {{ title: string, message: string, choices: Choice[], resolve: (key: string | null) => void }} DialogRequest
 */
export const dialog = writable(/** @type {DialogRequest | null} */ (null));

/**
 * A modal question; resolves with the chosen key, or null when dismissed (Échap, outside click).
 * @param {string} title @param {string} message @param {Choice[]} choices
 * @returns {Promise<string | null>}
 */
export function choose(title, message, choices) {
  return new Promise((resolve) => {
    dialog.set({ title, message, choices, resolve: (key) => { dialog.set(null); resolve(key); } });
  });
}

/**
 * @param {string} title @param {string} message
 * @param {{ confirm?: string, cancel?: string, danger?: boolean }} [options]
 */
export async function confirm(title, message, options = {}) {
  const key = await choose(title, message, [
    { key: 'cancel', label: options.cancel ?? 'Annuler', kind: 'plain' },
    { key: 'ok', label: options.confirm ?? 'OK', kind: options.danger ? 'danger' : 'primary' },
  ]);
  return key === 'ok';
}
