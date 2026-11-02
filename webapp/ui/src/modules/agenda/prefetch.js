// @ts-check
// What the agenda shows when opened from the rail (#/agenda: the preferred view, today), fetched into the lists'
// cache ahead of the click.
import { get } from 'svelte/store';
import { prefs } from '../../lib/ui.js';
import { agenda } from './api.js';
import { CHUNK, today, windowOf } from './dates.js';
import { agendaPrefs } from './prefs.js';

export function prefetch() {
  const p = agendaPrefs(get(prefs));
  const [from, to] = windowOf(p.view, today(), CHUNK, p.weekStart);
  return Promise.all([agenda.prefetchEntries(from, to), agenda.prefetchCalendars()]);
}
