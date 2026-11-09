// @ts-check
// The agenda's preferences of this browser (lib/ui.js prefs, keys `agenda.*`), with their defaults.

/**
 * @param {import('../../lib/ui.js').Prefs} p
 * @returns {{ view: string, weekStart: number, eventZone: boolean, panel: boolean }}
 */
export function agendaPrefs(p) {
  return {
    view: ['agenda', 'month', 'week', 'day'].includes(p['agenda.view']) ? p['agenda.view'] : 'agenda',
    weekStart: [0, 5, 6].includes(p['agenda.weekStart']) ? p['agenda.weekStart'] : 0,
    eventZone: !!p['agenda.eventZone'],
    panel: p['agenda.panel'] !== false,
  };
}
