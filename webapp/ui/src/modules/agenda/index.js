// @ts-check
import { registerNoteAction } from '../../lib/hooks.js';
import { showSnack } from '../../lib/ui.js';

// "Ajouter à l'agenda" in the note editor, as on Windows. Placeholder until the agenda module replaces it with the
// date dialog (register the same key again); an event's own note (agenda/…) never shows it.
registerNoteAction({
  key: 'agenda',
  icon: 'event',
  label: "Ajouter à l'agenda",
  visible: (note) => !note.path.startsWith('agenda/'),
  run: () => showSnack("L'agenda arrive bientôt dans la version web."),
});

export default { key: 'agenda', label: 'Agenda', icon: 'calendar', hideable: true, load: () => import('./AgendaPage.svelte') };
