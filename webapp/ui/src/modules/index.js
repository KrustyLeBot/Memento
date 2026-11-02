// @ts-check
// The modules of the rail, in the phone's bottom-bar order. Each folder's index.js exports
// `{ key, label, icon, hideable, load, prefetch }`, where `load()` imports the page component: each module is its own
// chunk, fetched the first time it is opened (or ahead, see App.svelte and Rail.svelte), and `prefetch()` puts its
// root list in the lists cache (lib/cache.js). The page gets the current route as `route` (module, rest, query).
// Réglages (modules/settings) is not in this list: the shell always shows it last.
import notes from './notes/index.js';
import agenda from './agenda/index.js';
import activities from './activities/index.js';
import contacts from './contacts/index.js';

/** @typedef {{ key: string, label: string, icon: string, hideable: boolean, load: () => Promise<{ default: any }>,
 *   prefetch: () => Promise<any> }} Module */
/** @type {Module[]} */
export const MODULES = [notes, agenda, activities, contacts];
