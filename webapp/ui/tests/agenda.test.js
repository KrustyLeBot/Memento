// The agenda views' pure helpers: day arithmetic, French wording (same words as core/agenda/frdates.py), layouts.
// Run with TZ=Europe/Paris semantics checked through explicit instants.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  addDays, addMonths, alertLabel, byDay, dayLayout, daysBetween, gridStart, lanes, longDate, minutesStamp, monthTitle,
  scheduleRows, searchRows, shortDate, stampMinutes, timeText, weekStartOf, weekTitle, weekday, whenText,
} from '../src/modules/agenda/dates.js';

process.env.TZ = 'Europe/Paris';

/** A timed entry of the browser's zone from local wall times. */
function timed(title, start, end, extra = {}) {
  const s = new Date(start).getTime();
  const e = new Date(end).getTime();
  const day = (ms) => new Date(ms - (ms === s ? 0 : 1)).toLocaleDateString('sv-SE');
  return { kind: 'event', key: title, title, subtitle: null, color: '#4F8BF5', all_day: false, start: s, end: e,
    start_date: day(s), end_date: day(e), target_id: title, target_path: null, calendar_id: 'perso', recurring: false,
    sport: null, ...extra };
}
function allDay(title, first, last) {
  return { kind: 'event', key: title, title, subtitle: null, color: '#4F8BF5', all_day: true, start: 0, end: 0,
    start_date: first, end_date: last, target_id: title, target_path: null, calendar_id: 'perso', recurring: false, sport: null };
}

test('day arithmetic crosses months, years and daylight saving', () => {
  assert.equal(addDays('2026-10-24', 2), '2026-10-26');
  assert.equal(addDays('2026-12-31', 1), '2027-01-01');
  assert.equal(daysBetween('2026-03-28', '2026-03-30'), 2);
  assert.equal(addMonths('2026-01-31', 1), '2026-02-28');
  assert.equal(addMonths('2026-01-15', -1), '2025-12-15');
  assert.equal(weekday('2026-11-02'), 0);
  assert.equal(weekStartOf('2026-11-08', 0), '2026-11-02');
  assert.equal(weekStartOf('2026-11-08', 6), '2026-11-08');
  assert.equal(weekStartOf('2026-11-07', 5), '2026-11-07');
  assert.equal(gridStart('2026-11-18', 0), '2026-10-26');
});

test('French wording matches the apps', () => {
  assert.equal(longDate('2026-11-01'), 'dimanche 1er novembre 2026');
  assert.equal(shortDate('2026-11-12', '2026-01-01'), '12 nov.');
  assert.equal(shortDate('2027-11-12', '2026-01-01'), '12 nov. 2027');
  assert.equal(monthTitle('2026-08-03'), 'Août 2026');
  assert.equal(weekTitle('2026-11-30', '2026-12-06'), '30 nov. – 6 déc. 2026');
  assert.equal(weekTitle('2026-12-28', '2027-01-03'), '28 déc. 2026 – 3 janv. 2027');
  assert.equal(alertLabel(-15), '15 min avant');
  assert.equal(alertLabel(-90), '1 h 30 avant');
  assert.equal(alertLabel(-1440), '1 jour avant');
  assert.equal(alertLabel(0), 'Au début');
});

test('time text uses the browser clock, or the event zone when asked', () => {
  const e = timed('Vol', '2026-12-17T10:00:00+01:00', '2026-12-17T19:00:00+01:00',
    { occurrence: { start: '2026-12-17T10:00', end: '2026-12-17T13:00' }, tz: 'Europe/Paris', end_tz: 'America/New_York' });
  assert.equal(timeText(e, '2026-12-17', false), '10:00 – 19:00');
  assert.equal(timeText(e, '2026-12-17', true), '10:00 – 13:00');
  assert.equal(whenText(e, false), 'Jeudi 17 décembre 2026 · 10:00 – 19:00');
  const night = timed('Nuit', '2026-11-02T22:00:00+01:00', '2026-11-03T06:00:00+01:00');
  assert.equal(timeText(night, '2026-11-02', false), 'À partir de 22:00');
  assert.equal(timeText(night, '2026-11-03', false), "Jusqu'à 06:00");
  assert.equal(timeText(allDay('Congés', '2026-12-01', '2026-12-03'), '2026-12-02', false, '2026-01-01'), 'Du 1 au 3 déc.');
});

test('schedule rows: month titles, today even when empty', () => {
  const days = byDay([allDay('A', '2026-10-30', '2026-11-01')], '2026-10-29', '2026-11-03');
  const rows = scheduleRows(days, '2026-11-03');
  assert.deepEqual(rows.map((r) => r.kind === 'month' ? r.text : `${r.day}:${r.entries.length}`),
    ['Octobre 2026', '2026-10-30:1', '2026-10-31:1', 'Novembre 2026', '2026-11-01:1', '2026-11-03:0']);
  const hits = searchRows([allDay('B', '2026-12-01', '2026-12-01'), allDay('C', '2026-12-01', '2026-12-01'), allDay('D', '2025-01-02', '2025-01-02')]);
  assert.deepEqual(hits.map((r) => r.kind === 'month' ? r.text : r.entries.length), ['Décembre 2026', 2, 'Janvier 2025', 1]);
});

test('lanes put bars first and count per column', () => {
  const { segments, counts } = lanes([
    timed('dot', '2026-11-03T09:00:00+01:00', '2026-11-03T10:00:00+01:00'),
    allDay('long', '2026-11-01', '2026-11-04'),
    allDay('short', '2026-11-03', '2026-11-03'),
  ], '2026-11-02', 7);
  assert.deepEqual(segments.map((s) => [s.entry.title, s.col, s.span, s.lane]),
    [['long', 0, 3, 0], ['short', 1, 1, 1], ['dot', 1, 1, 2]]);
  assert.deepEqual(counts, [1, 3, 1, 0, 0, 0, 0]);
});

test('day layout shares the width of overlapping entries only', () => {
  const items = dayLayout([
    timed('a', '2026-11-03T09:00:00+01:00', '2026-11-03T11:00:00+01:00'),
    timed('b', '2026-11-03T10:00:00+01:00', '2026-11-03T10:30:00+01:00'),
    timed('c', '2026-11-03T10:30:00+01:00', '2026-11-03T12:00:00+01:00'),
    timed('d', '2026-11-03T14:00:00+01:00', '2026-11-03T14:00:00+01:00'),
  ], '2026-11-03');
  assert.deepEqual(items.map((i) => [i.entry.title, i.top, i.bottom, i.column, i.columns]),
    [['a', 540, 660, 0, 2], ['b', 600, 630, 1, 2], ['c', 630, 720, 1, 2], ['d', 840, 860, 0, 1]]);
});

test('wall stamps keep their form', () => {
  const m = stampMinutes('2026-11-12T23:30');
  assert.equal(minutesStamp(m + 60, false), '2026-11-13T00:30');
  assert.equal(minutesStamp(stampMinutes('2026-02-28') + 1440, true), '2026-03-01');
});
