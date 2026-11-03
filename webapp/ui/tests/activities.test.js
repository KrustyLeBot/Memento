import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import {
  fmtDistance, fmtDuration, fmtPace, fmtSpeed, fmtDate, summaryLine, totals, speedColor, skiLines, nearest, CLIMB, OTHER,
} from '../src/modules/activities/activities.js';

test('French formatting as core/activity.py', () => {
  assert.equal(fmtDistance(850.4), '850 m');
  assert.equal(fmtDistance(12345), '12,35 km');
  assert.equal(fmtDuration(3725), '1 h 02');
  assert.equal(fmtDuration(605), '10 min 05');
  assert.equal(fmtSpeed(10), '36,0 km/h');
  assert.equal(fmtPace(1000 / 330), '5:30 /km');
  assert.equal(fmtPace(0.1), '–');
  assert.equal(fmtDate(new Date(2026, 10, 2, 14, 5).getTime()), 'lun. 2 nov. 2026, 14:05');
});

const base = { path: 'activities/x.md', id: 'x', title: 'x', conflict: false, start: 0, elapsed: 4000, moving: 3600,
  distance: 0, ascent: 0, descent: 0, hr: null, vmax: null, runs: 0, drop: 0, climbs: 0, has_file: true };

test('summary line: walk with pace, ski with descents', () => {
  assert.equal(summaryLine({ ...base, sport: 'walk', distance: 6000, ascent: 120 }), '6,00 km · 1 h 00 · 10:00 /km · ↑ 120 m');
  assert.equal(summaryLine({ ...base, sport: 'bike', distance: 36000 }), '36,00 km · 1 h 00 · 36,0 km/h');
  // an untimed GPX: no duration, no speed
  assert.equal(summaryLine({ ...base, sport: 'walk', distance: 6000, elapsed: 0, moving: 0, ascent: 120 }), '6,00 km · ↑ 120 m');
  assert.equal(summaryLine({ ...base, sport: 'ski', distance: 30000, runs: 6, drop: 3600, climbs: 6 }), '1 h 00 · 6 descentes · 3600 m · 6 montées');
  assert.equal(summaryLine({ ...base, sport: 'plain', moving: 0 }), '1 h 06');
});

test('totals: week from Monday, month, total, only the included sports', () => {
  const now = new Date(2026, 10, 4, 12); // Wednesday 4 November
  const at = (/** @type {number} */ d) => new Date(2026, 10, d, 9).getTime();
  const list = [
    { ...base, sport: 'walk', start: at(2), distance: 5000, ascent: 100 },  // Monday
    { ...base, sport: 'ski', start: at(1), distance: 20000, runs: 5, drop: 2000 },  // Sunday: last week
    { ...base, sport: 'bike', start: at(3), distance: 30000 },
    { ...base, sport: 'walk', start: new Date(2026, 9, 30).getTime(), distance: 1000 },
  ];
  const [week, month, total] = totals(list, ['walk', 'ski'], now);
  assert.deepEqual([week.count, month.count, total.count], [1, 2, 3]);
  assert.equal(week.distance, 5000);
  assert.equal(week.ski, null);
  assert.deepEqual(month.ski, { runs: 5, drop: 2000 });
  assert.equal(total.ascent, 100);
});

test('speed colours run violet, magenta, red-orange', () => {
  assert.equal(speedColor(0), '#6a00f4');
  assert.equal(speedColor(0.5), '#e500a4');
  assert.equal(speedColor(2), '#ff3d00');
});

test('ski lines: descents coloured, climbs blue, the rest grey or hidden', () => {
  const n = 30;
  const kind = 'u'.repeat(10) + 'o'.repeat(5) + 'd'.repeat(15);
  const m = { lon: [...Array(n).keys()].map((i) => 6 + i / 1000), lat: Array(n).fill(45), t: [...Array(n).keys()],
    speed: [...Array(n).keys()].map((i) => (kind[i] === 'd' ? 10 + i : 4)), kind, max_gap: 30 };
  const both = skiLines(m, 'both');
  assert.ok(both.some((l) => l.color === CLIMB));
  assert.ok(!both.some((l) => l.color === OTHER));
  assert.ok(both.every((l) => l.coords.length >= 2 && l.coords.length <= 9));
  const down = skiLines(m, 'down');
  assert.ok(down.length && down.every((l) => l.color !== CLIMB && l.color !== OTHER));
  assert.ok(skiLines(m, 'all').some((l) => l.color === OTHER));
  // a gap longer than max_gap cuts the line
  const gap = { ...m, t: m.t.map((t, i) => (i >= 20 ? t + 100 : t)) };
  assert.ok(!skiLines(gap, 'down').some((l) => l.coords.some((c) => c[0] === m.lon[19]) && l.coords.some((c) => c[0] === m.lon[20])));
});

test('the map page answers every call the web host makes, and only from its parent of the same origin', () => {
  const page = readFileSync(new URL('../../../web/map/map.js', import.meta.url), 'utf8');
  const api = new Set(/const API = \[([^\]]+)]/.exec(page)[1].split(',').map((s) => s.trim().replace(/'/g, '')));
  const called = new Set();
  for (const file of ['MapPane.svelte', 'ActivitiesPage.svelte']) {
    const text = readFileSync(new URL(`../src/modules/activities/${file}`, import.meta.url), 'utf8');
    for (const m of text.matchAll(/call\('(\w+)'/g)) called.add(m[1]);
    const order = /STATE_ORDER = \[([^\]]+)]/.exec(text);
    if (order) order[1].split(',').forEach((s) => called.add(s.trim().replace(/'/g, '')));
  }
  assert.ok(called.size >= 7);
  assert.deepEqual([...called].filter((c) => !api.has(c)), []);
  assert.match(page, /e\.origin !== location\.origin \|\| e\.source !== window\.parent/);
  assert.match(page, /window\.parent\.postMessage\(\{ source: 'smap', \.\.\.obj \}, location\.origin\)/);
});

test('nearest index in a sorted array', () => {
  assert.equal(nearest([0, 10, 20, 30], 14), 1);
  assert.equal(nearest([0, 10, 20, 30], 16), 2);
  assert.equal(nearest([0, 10, 20, 30], 99), 3);
  assert.equal(nearest([0, 10, 20, 30], -5), 0);
});
