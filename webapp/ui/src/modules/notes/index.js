// @ts-check
import { notes as api } from '../../lib/api.js';

export default {
  key: 'notes', label: 'Notes', icon: 'notes', hideable: false, load: () => import('./NotesPage.svelte'), prefetch: api.prefetch,
};
