// @ts-check
// Extension points between modules, so one module can add to another without importing it.
import { writable } from 'svelte/store';

/**
 * A button of the note editor's toolbar. The agenda module registers "Ajouter à l'agenda" here.
 * @typedef {{ note: import('./api.js').Note, version: string,
 *   save: (changes: import('./api.js').NoteChanges) => Promise<import('./api.js').VersionedNote> }} NoteContext
 *   `save` writes through the editor (409 handling included) and returns the saved note.
 * @typedef {{ key: string, icon: string, label: string, visible?: (note: import('./api.js').Note) => boolean,
 *   run: (ctx: NoteContext) => any }} NoteAction
 */
/** @type {import('svelte/store').Writable<NoteAction[]>} */
export const noteActions = writable([]);

/** @param {NoteAction} action registering a key again replaces it */
export function registerNoteAction(action) {
  noteActions.update((list) => [...list.filter((a) => a.key !== action.key), action]);
}
