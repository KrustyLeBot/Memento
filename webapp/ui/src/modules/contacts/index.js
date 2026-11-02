// @ts-check
import { get } from 'svelte/store';
import { prefs } from '../../lib/ui.js';
import { api } from './contacts.js';

export default {
  key: 'contacts', label: 'Contacts', icon: 'person', hideable: true, load: () => import('./ContactsPage.svelte'),
  prefetch: () => api.prefetch(!!get(prefs)['contacts.firstNameFirst']),
};
