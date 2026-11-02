// @ts-check
import { registerNoteAction } from '../../lib/hooks.js';

// "Ajouter à l'agenda" in the note editor, as on Windows: a date (+ time, + reminder) on the note, or "Retirer de
// l'agenda". The dialog lives in the agenda's chunk, fetched on first use. An event's own note (agenda/…) never
// shows it.
registerNoteAction({
  key: 'agenda',
  icon: 'event',
  label: "Ajouter à l'agenda",
  visible: (note) => !note.path.startsWith('agenda/'),
  run: async ({ note }) => {
    const { default: DateNoteDialog } = await import('./DateNoteDialog.svelte');
    const dialog = new DateNoteDialog({ target: document.body, props: { path: note.path } });
    dialog.$on('close', () => dialog.$destroy());
  },
});

export default { key: 'agenda', label: 'Agenda', icon: 'calendar', hideable: true, load: () => import('./AgendaPage.svelte') };
