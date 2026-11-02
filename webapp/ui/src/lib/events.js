// @ts-check
// Live changes from the server (GET /api/events, Server-Sent Events): a sync from the phone, another tab, another
// device. `onChange(areas => …)` with areas among 'notes' | 'activities' | 'agenda' | 'contacts'. A lost stream is
// reopened with a growing delay (1 s … 30 s); once back, every subscriber gets all areas (it may have missed
// something meanwhile).
import { writable } from 'svelte/store';
import { auth, lists } from './api.js';

export const AREAS = ['notes', 'activities', 'agenda', 'contacts'];
/** false while the live stream is down (shown discreetly in the shell). */
export const live = writable(true);

/** @type {Set<(areas: string[]) => void>} */
const listeners = new Set();
/** @type {EventSource | null} */
let source = null;
let delay = 1000;
/** @type {any} */
let retryTimer = null;
let wanted = false;
let lost = false;

/** @param {(areas: string[]) => void} fn @returns {() => void} unsubscribe */
export function onChange(fn) {
  listeners.add(fn);
  return () => listeners.delete(fn);
}

/** @param {string[]} areas */
function emit(areas) {
  lists.stale(areas);
  for (const fn of listeners) {
    try {
      fn(areas);
    } catch (error) {
      console.error(error);
    }
  }
}

function open() {
  source = new EventSource('/api/events');
  source.addEventListener('hello', () => {
    delay = 1000;
    live.set(true);
    if (lost) emit(AREAS);
    lost = false;
  });
  source.addEventListener('change', (event) => {
    try {
      emit(JSON.parse(/** @type {MessageEvent} */ (event).data).areas);
    } catch (error) {
      console.error(error);
    }
  });
  source.onerror = () => {
    source?.close();
    source = null;
    retryLater();
  };
}

function retryLater() {
  lost = true;
  live.set(false);
  if (!wanted) return;
  clearTimeout(retryTimer);
  retryTimer = setTimeout(async () => {
    if (!wanted || source) return;
    try {
      await auth.me(); // a 401 clears the session, and the shell calls disconnect()
    } catch {
      retryLater();
      return;
    }
    if (wanted && !source) open();
  }, delay);
  delay = Math.min(delay * 2, 30000);
}

export function connect() {
  wanted = true;
  if (!source) open();
}

export function disconnect() {
  wanted = false;
  clearTimeout(retryTimer);
  source?.close();
  source = null;
}
