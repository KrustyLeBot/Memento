// @ts-check
import { activities as api } from './api.js';

export default {
  key: 'activities', label: 'Activités', icon: 'run', hideable: true, load: () => import('./ActivitiesPage.svelte'),
  prefetch: api.prefetch,
};
