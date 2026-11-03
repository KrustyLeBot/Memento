// @ts-check
// Display rules of the Activités module, as windows/memento_win/core/activity.py (French formatting) and
// windows/memento_win/ui/activities_view.py (summary line, totals, map colouring) do them.

/** @typedef {{ path: string, id: string, title: string, conflict: boolean, sport: string, start: number,
 *   elapsed: number, moving: number, distance: number, ascent: number, descent: number, hr: number | null,
 *   vmax: number | null, runs: number, drop: number, climbs: number, has_file: boolean }} ActivitySummary */

export const EMOJI = /** @type {Record<string, string>} */ ({ walk: '🚶', bike: '🚴', ebike: '⚡', ski: '⛷️', gps: '📍', plain: '⏱️' });
/** Sports a track can be given (core Sport, has_gps), in the apps' order. */
export const GPS_SPORTS = ['walk', 'bike', 'ebike', 'ski', 'gps'];
export const SPORT_LABELS = /** @type {Record<string, string>} */ ({
  walk: 'Marche', bike: 'Vélo', ebike: 'Vélo électrique', ski: 'Ski', gps: 'Autre (GPS)', plain: 'Autre (sans GPS)',
});
export const shortLabel = (/** @type {string} */ sport) => (SPORT_LABELS[sport] || sport).split(' (')[0];
export const hasGps = (/** @type {string} */ sport) => sport !== 'plain';

const fr = (/** @type {string} */ text) => text.replace('.', ',');
const pad = (/** @type {number} */ n) => String(n).padStart(2, '0');

export function fmtDistance(/** @type {number} */ m) {
  return m < 1000 ? `${Math.round(m)} m` : fr(`${(m / 1000).toFixed(2)} km`);
}

export function fmtDuration(/** @type {number} */ seconds) {
  seconds = Math.max(0, Math.round(seconds));
  const h = Math.floor(seconds / 3600), m = Math.floor((seconds % 3600) / 60), s = seconds % 60;
  return h ? `${h} h ${pad(m)}` : `${m} min ${pad(s)}`;
}

export const fmtSpeed = (/** @type {number} */ ms) => fr(`${(ms * 3.6).toFixed(1)} km/h`);

/** Seconds per km as "m:ss /km". */
export function fmtPaceSeconds(/** @type {number} */ sec) {
  sec = Math.round(sec);
  return `${Math.floor(sec / 60)}:${pad(sec % 60)} /km`;
}

export const fmtPace = (/** @type {number} */ ms) => (ms < 0.2 ? '–' : fmtPaceSeconds(1000 / ms));
export const fmtSpeedFor = (/** @type {string} */ sport, /** @type {number} */ ms) => (sport === 'walk' ? fmtPace(ms) : fmtSpeed(ms));

const DAYS = ['dim.', 'lun.', 'mar.', 'mer.', 'jeu.', 'ven.', 'sam.'];
const MONTHS = ['janv.', 'févr.', 'mars', 'avr.', 'mai', 'juin', 'juil.', 'août', 'sept.', 'oct.', 'nov.', 'déc.'];

/** "lun. 3 nov. 2026, 14:05" */
export function fmtDate(/** @type {number} */ ms) {
  const d = new Date(ms);
  return `${DAYS[d.getDay()]} ${d.getDate()} ${MONTHS[d.getMonth()]} ${d.getFullYear()}, ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

export const fmtHour = (/** @type {number} */ ms) => { const d = new Date(ms); return `${pad(d.getHours())}:${pad(d.getMinutes())}`; };

/** The list row's second line. Skiing: what counts is the descents, the distance includes the lifts. */
export function summaryLine(/** @type {ActivitySummary} */ a) {
  const parts = [];
  const time = fmtDuration(a.moving > 0 ? a.moving : a.elapsed);
  if (a.sport === 'ski') {
    parts.push(time);
    if (a.runs > 0) parts.push(`${a.runs} descentes${a.drop > 0 ? ` · ${a.drop} m` : ''}`);
    if (a.climbs > 0) parts.push(`${a.climbs} montées`);
    return parts.join(' · ');
  }
  if (a.distance > 0) parts.push(fmtDistance(a.distance));
  if (a.moving > 0 || a.elapsed > 0) parts.push(time);  // an untimed GPX (no <time> in the file) has no duration
  if (hasGps(a.sport) && a.moving > 0 && a.distance > 0) parts.push(fmtSpeedFor(a.sport, a.distance / a.moving));
  if (a.ascent > 0) parts.push(`↑ ${a.ascent} m`);
  return parts.join(' · ');
}

/**
 * Semaine (from Monday 00:00), Mois, Total, counting only the sports of the settings' `totals`.
 * @param {ActivitySummary[]} list @param {string[]} included @param {Date} [now]
 */
export function totals(list, included, now = new Date()) {
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  const week = new Date(today);
  week.setDate(today.getDate() - ((today.getDay() + 6) % 7));
  const month = new Date(now.getFullYear(), now.getMonth(), 1);
  return /** @type {[string, number][]} */ ([['Semaine', week.getTime()], ['Mois', month.getTime()], ['Total', 0]]).map(([name, since]) => {
    const items = list.filter((a) => a.start >= since && included.includes(a.sport));
    const ski = items.filter((a) => a.sport === 'ski');
    return {
      name, count: items.length,
      distance: items.reduce((s, a) => s + a.distance, 0),
      moving: items.reduce((s, a) => s + a.moving, 0),
      ascent: items.reduce((s, a) => s + a.ascent, 0),
      ski: ski.length ? { runs: ski.reduce((s, a) => s + a.runs, 0), drop: ski.reduce((s, a) => s + a.drop, 0) } : null,
    };
  });
}

// ---- the map's ski colouring (activities_view.map_lines) ----

const STOPS = /** @type {[number, number[]][]} */ ([[0, [106, 0, 244]], [0.5, [229, 0, 164]], [1, [255, 61, 0]]]);
export const CLIMB = '#00b0ff';
export const OTHER = '#9aa0a6';
const hex = (/** @type {number[]} */ c) => `#${c.map((x) => x.toString(16).padStart(2, '0')).join('')}`;

/** Slow (violet) to fast (red-orange), f in 0..1. */
export function speedColor(/** @type {number} */ f) {
  f = Math.max(0, Math.min(1, f));
  for (let k = 0; k < STOPS.length - 1; k++) {
    const [a, ca] = STOPS[k], [b, cb] = STOPS[k + 1];
    if (f <= b) {
      const t = (f - a) / (b - a);
      return hex(ca.map((x, i) => Math.round(x + (cb[i] - x) * t)));
    }
  }
  return hex(STOPS[STOPS.length - 1][1]);
}

/**
 * Chunks of a ski day for the page's setLines: descents from violet to red by speed, climbs in blue ('both'),
 * descents only ('down'), or everything with the rest in grey ('all').
 * @param {{ lon: number[], lat: number[], t: number[], speed: number[], kind: string, max_gap: number }} m
 * @param {'both' | 'down' | 'all'} mode
 * @returns {{ coords: number[][], color: string }[]}
 */
export function skiLines(m, mode = 'both') {
  const n = m.lon.length;
  if (n < 2) return [];
  const sorted = [...m.speed].sort((a, b) => a - b);
  const top = Math.max(sorted[Math.min(Math.floor(n * 0.9), n - 1)], 0.5);
  const kind = (/** @type {number} */ i) => (m.kind[i] === 'd' ? 0 : m.kind[i] === 'u' ? 1 : 2);
  const shown = (/** @type {number} */ i) => mode === 'all' || m.kind[i] === 'd' || (mode === 'both' && m.kind[i] === 'u');
  const out = [];
  let i = 0;
  while (i < n - 1) {
    if (!shown(i)) { i++; continue; }
    const first = i, k = kind(i);
    let j = i, total = 0;
    while (j + 1 < n && j - first < 8 && m.t[j + 1] - m.t[j] <= m.max_gap && shown(j + 1) && kind(j + 1) === k) {
      j++;
      total += m.speed[j];
    }
    if (j === first) { i++; continue; }
    const color = k === 1 ? CLIMB : k === 2 ? OTHER : speedColor(total / (j - first) / top);
    const coords = [];
    for (let q = first; q <= j; q++) coords.push([m.lon[q], m.lat[q]]);
    out.push({ coords, color });
    i = j;
  }
  return out;
}

/** Index of the value of a sorted array nearest to x. */
export function nearest(/** @type {number[]} */ xs, /** @type {number} */ x) {
  let lo = 0, hi = xs.length - 1;
  while (hi - lo > 1) {
    const mid = (lo + hi) >> 1;
    if (xs[mid] <= x) lo = mid; else hi = mid;
  }
  return Math.abs(xs[hi] - x) < Math.abs(xs[lo] - x) ? hi : lo;
}

export const isImage = (/** @type {string} */ path) => /\.(jpe?g|png|gif|webp|bmp)$/i.test(path);
