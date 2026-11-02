// @ts-check
// The module's background job (import, altitude), shown as a banner on the list and on an activity until it
// ends; then `done(job)` runs (summary snackbar, open the imported activity).
import { writable } from 'svelte/store';
import { activities as api } from './api.js';
import { showError } from '../../lib/ui.js';

/** @type {import('svelte/store').Writable<import('./api.js').Job | null>} */
export const job = writable(null);

/** @param {import('./api.js').Job} started @param {(job: import('./api.js').Job) => void} done */
export async function follow(started, done) {
  let current = started;
  job.set(current);
  try {
    while (!current.finished) {
      await new Promise((resolve) => setTimeout(resolve, 700));
      current = await api.job(current.id);
      job.set(current);
    }
  } catch (error) {
    showError(error, 'Suivi de la tâche');
    job.set(null);
    return;
  }
  job.set(null);
  done(current);
}
