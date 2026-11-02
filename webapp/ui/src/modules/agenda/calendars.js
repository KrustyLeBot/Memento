// @ts-check
// agenda/calendars.json as the pages see it (calendars, default, layers), shared by the agenda page and its section
// of Réglages. Every change is one request carrying the version read: a change made meanwhile elsewhere (409) is
// reloaded and reported, and an unreadable file is only replaced after the user agreed (as on Windows).
import { get, writable } from 'svelte/store';
import { ApiError, undo } from '../../lib/api.js';
import { confirm, showError, showSnack } from '../../lib/ui.js';
import { agenda } from './api.js';

/** @type {import('svelte/store').Writable<import('./api.js').Calendars | null>} */
export const calendars = writable(null);

export async function loadCalendars() {
  try {
    calendars.set(await agenda.calendars((fresh) => calendars.set(fresh)));
  } catch (error) {
    showError(error, 'Agendas');
  }
}

async function mayReplace() {
  const current = get(calendars);
  if (!current?.problem) return true;
  return confirm('Agendas', `${current.problem}\n\nL'enregistrer remplacera ce fichier par les réglages affichés. Continuer ?`);
}

/** One change ({op: 'create' | 'update' | 'default' | 'layer', ...}); resolves with the new state, or null. @param {Record<string, any>} change */
export async function changeCalendars(change) {
  if (!(await mayReplace())) return null;
  try {
    const next = await agenda.changeCalendars(get(calendars)?.version ?? null, change);
    calendars.set(next);
    return next;
  } catch (error) {
    if (error instanceof ApiError && error.status === 409) {
      calendars.set(error.body.current);
      showSnack('Les agendas venaient de changer sur un autre appareil : rechargés, réessayez.');
    } else showError(error);
    return null;
  }
}

/** Removes a calendar, its events moved to `moveTo` or deleted; "Annuler" puts everything back. @param {string} id @param {string | null} moveTo @param {() => void} [after] */
export async function deleteCalendar(id, moveTo, after) {
  if (!(await mayReplace())) return;
  try {
    const result = await agenda.deleteCalendar(get(calendars)?.version ?? null, id, moveTo);
    await loadCalendars();
    after?.();
    showSnack('Agenda supprimé', { action: { label: 'Annuler', run: async () => { await undo(result.undo); await loadCalendars(); after?.(); } } });
  } catch (error) {
    if (error instanceof ApiError && error.status === 409) {
      calendars.set(error.body.current);
      showSnack('Les agendas venaient de changer sur un autre appareil : rechargés, réessayez.');
    } else showError(error);
  }
}
