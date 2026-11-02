// @ts-check
// Pure helpers of the agenda views: days as ISO strings ('2026-11-02', no time zone trap in the arithmetic), French
// wording (same words as core/agenda/frdates.py), and the layouts of the month grid and the time grid. No DOM:
// tested by tests/agenda.test.js.

export const MONTHS = ['janvier', 'février', 'mars', 'avril', 'mai', 'juin', 'juillet', 'août', 'septembre', 'octobre', 'novembre', 'décembre'];
export const MONTHS_SHORT = ['janv.', 'févr.', 'mars', 'avr.', 'mai', 'juin', 'juil.', 'août', 'sept.', 'oct.', 'nov.', 'déc.'];
export const WEEKDAYS = ['lundi', 'mardi', 'mercredi', 'jeudi', 'vendredi', 'samedi', 'dimanche'];
export const WEEKDAYS_SHORT = ['lun.', 'mar.', 'mer.', 'jeu.', 'ven.', 'sam.', 'dim.'];
const DAY = 86_400_000;

/** @param {string} iso @returns {[number, number, number]} */
export function parts(iso) {
  return [Number(iso.slice(0, 4)), Number(iso.slice(5, 7)), Number(iso.slice(8, 10))];
}
const utc = (/** @type {string} */ iso) => {
  const [y, m, d] = parts(iso);
  return Date.UTC(y, m - 1, d);
};
const fromUtc = (/** @type {number} */ t) => new Date(t).toISOString().slice(0, 10);

/** @param {string} iso @param {number} n */
export const addDays = (iso, n) => fromUtc(utc(iso) + n * DAY);
/** Days from a to b (b - a). @param {string} a @param {string} b */
export const daysBetween = (a, b) => Math.round((utc(b) - utc(a)) / DAY);
/** 0 = Monday … 6 = Sunday. @param {string} iso */
export const weekday = (iso) => (new Date(utc(iso)).getUTCDay() + 6) % 7;

/** @param {string} iso @param {number} n */
export function addMonths(iso, n) {
  const [y, m, d] = parts(iso);
  const index = y * 12 + (m - 1) + n;
  const year = Math.floor(index / 12);
  const month = index % 12;
  const last = new Date(Date.UTC(year, month + 1, 0)).getUTCDate();
  return fromUtc(Date.UTC(year, month, Math.min(d, last)));
}

/** The local day of an instant (the browser's zone). @param {number} ms */
export function dayOf(ms) {
  const d = new Date(ms);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}
export const today = () => dayOf(Date.now());

/** Minutes since local midnight of an instant. @param {number} ms */
export function minutesOf(ms) {
  const d = new Date(ms);
  return d.getHours() * 60 + d.getMinutes();
}

/** First day of the week holding `iso`; weekStart: 0 = Monday, 5 = Saturday, 6 = Sunday. @param {string} iso @param {number} weekStart */
export const weekStartOf = (iso, weekStart) => addDays(iso, -((weekday(iso) - weekStart + 7) % 7));
/** First cell of the 6-week month grid. @param {string} iso @param {number} weekStart */
export const gridStart = (iso, weekStart) => weekStartOf(`${iso.slice(0, 8)}01`, weekStart);

/** @param {string} iso */
export const dayNumber = (iso) => (parts(iso)[2] === 1 ? '1er' : String(parts(iso)[2]));
/** "lundi 2 novembre 2026". @param {string} iso */
export function longDate(iso, withYear = true) {
  const [y, m] = parts(iso);
  const text = `${WEEKDAYS[weekday(iso)]} ${dayNumber(iso)} ${MONTHS[m - 1]}`;
  return withYear ? `${text} ${y}` : text;
}
/** "12 nov." or "12 nov. 2027" (the year when it is not the current one). @param {string} iso @param {string} [current] */
export function shortDate(iso, current) {
  const [y, m, d] = parts(iso);
  const text = `${d} ${MONTHS_SHORT[m - 1]}`;
  return current && current.slice(0, 4) === iso.slice(0, 4) ? text : `${text} ${y}`;
}
/** "lun. 2 nov." @param {string} iso */
export const mediumDate = (iso) => `${WEEKDAYS_SHORT[weekday(iso)]} ${parts(iso)[2]} ${MONTHS_SHORT[parts(iso)[1] - 1]}`;
/** "Novembre 2026". @param {string} iso */
export const monthTitle = (iso) => `${MONTHS[parts(iso)[1] - 1]} ${parts(iso)[0]}`.replace(/^./, (c) => c.toUpperCase());
/** "2 – 8 nov. 2026", "30 nov. – 6 déc. 2026", "28 déc. 2026 – 3 janv. 2027". @param {string} first @param {string} last */
export function weekTitle(first, last) {
  const [y1, m1, d1] = parts(first);
  const [y2, m2, d2] = parts(last);
  if (y1 !== y2) return `${shortDate(first)} – ${shortDate(last)}`;
  if (m1 !== m2) return `${d1} ${MONTHS_SHORT[m1 - 1]} – ${d2} ${MONTHS_SHORT[m2 - 1]} ${y2}`;
  return `${d1} – ${d2} ${MONTHS_SHORT[m2 - 1]} ${y2}`;
}

/** "14:30" @param {number} minutes */
export const hhmm = (minutes) => `${String(Math.floor(minutes / 60)).padStart(2, '0')}:${String(minutes % 60).padStart(2, '0')}`;
/** "14:30" of a wall stamp '2026-11-12T14:30'. @param {string} s */
export const stampClock = (s) => s.slice(11, 16);

/**
 * Start and end as shown: the browser's clock, or the event's own wall times ("heures du fuseau de l'événement").
 * @param {import('./api.js').Entry} e @param {boolean} eventZone
 * @returns {{ startDay: string, start: string, endDay: string, end: string }}
 */
export function wall(e, eventZone) {
  const o = e.occurrence;
  if (eventZone && o && !e.all_day && o.start.length > 10) {
    const end = o.end && o.end.length > 10 ? o.end : o.start;
    return { startDay: o.start.slice(0, 10), start: stampClock(o.start), endDay: end.slice(0, 10), end: stampClock(end) };
  }
  return { startDay: dayOf(e.start), start: hhmm(minutesOf(e.start)), endDay: dayOf(e.end), end: hhmm(minutesOf(e.end)) };
}

/** "Du 1 au 3 déc." @param {string} first @param {string} last @param {string} [current] */
export function daySpan(first, last, current) {
  if (first.slice(0, 7) === last.slice(0, 7)) return `Du ${parts(first)[2]} au ${shortDate(last, current)}`;
  return `Du ${shortDate(first, first.slice(0, 4) === last.slice(0, 4) ? current : undefined)} au ${shortDate(last, current)}`;
}

/**
 * The time line of an entry on `day`: "14:30 – 15:15", "Toute la journée", "Du 1 au 3 déc.", "Jusqu'à 10:00"…
 * @param {import('./api.js').Entry} e @param {string} day @param {boolean} eventZone @param {string} [current]
 */
export function timeText(e, day, eventZone, current) {
  if (e.all_day) return e.start_date === e.end_date ? 'Toute la journée' : daySpan(e.start_date, e.end_date, current);
  const w = wall(e, eventZone);
  if (e.end === e.start) return w.start;
  if (w.startDay === w.endDay) return `${w.start} – ${w.end}`;
  if (day === w.startDay) return `À partir de ${w.start}`;
  if (day === w.endDay) return `Jusqu'à ${w.end}`;
  return 'Toute la journée';
}

/** The detail card's first line: "lundi 2 novembre 2026 · 14:30 – 15:15". @param {import('./api.js').Entry} e @param {boolean} eventZone */
export function whenText(e, eventZone) {
  if (e.all_day) {
    if (e.start_date === e.end_date) return `${cap(longDate(e.start_date))} · Toute la journée`;
    return `Du ${longDate(e.start_date)} au ${longDate(e.end_date)}`;
  }
  const w = wall(e, eventZone);
  if (e.end === e.start) return `${cap(longDate(w.startDay))} · ${w.start}`;
  if (w.startDay === w.endDay) return `${cap(longDate(w.startDay))} · ${w.start} – ${w.end}`;
  return `Du ${longDate(w.startDay)}, ${w.start} au ${longDate(w.endDay)}, ${w.end}`;
}
/** @param {string} s */
export const cap = (s) => s.replace(/^./, (c) => c.toUpperCase());

/** "15 min", "1 h 30", "2 jours", "1 semaine" (frdates.duration). @param {number} minutes */
export function duration(minutes) {
  const n = Math.abs(minutes);
  if (n && n % 10080 === 0) return `${n / 10080} semaine${n / 10080 > 1 ? 's' : ''}`;
  if (n && n % 1440 === 0) return `${n / 1440} jour${n / 1440 > 1 ? 's' : ''}`;
  if (n >= 60) return `${Math.floor(n / 60)} h${n % 60 ? ` ${String(n % 60).padStart(2, '0')}` : ''}`;
  return `${n} min`;
}
/** -15 -> "15 min avant", 0 -> "Au début". @param {number} minutes */
export const alertLabel = (minutes) => (minutes === 0 ? 'Au début' : `${duration(minutes)} ${minutes < 0 ? 'avant' : 'après'}`);
export const ALERT_PRESETS = [-5, -10, -15, -30, -60, -1440, -10080];

/** City of an IANA zone: "America/New_York" -> "New York". @param {string} zone */
export const city = (zone) => zone.split('/').pop()?.replace(/_/g, ' ') ?? zone;

/** @param {number} n @param {string} one @param {string} many */
export const plural = (n, one, many) => `${n} ${n <= 1 ? one : many}`;

// ---- grouping and layouts ----

/**
 * Every day of [first, last] with the entries touching it (in the server's order).
 * @param {import('./api.js').Entry[]} entries @param {string} first @param {string} last
 * @returns {Map<string, import('./api.js').Entry[]>}
 */
export function byDay(entries, first, last) {
  /** @type {Map<string, import('./api.js').Entry[]>} */
  const out = new Map();
  for (let d = first; d <= last; d = addDays(d, 1)) out.set(d, []);
  for (const e of entries) {
    for (let d = e.start_date < first ? first : e.start_date; d <= e.end_date && d <= last; d = addDays(d, 1)) out.get(d)?.push(e);
  }
  return out;
}

/**
 * Rows of the Agenda view: a month title when the month changes, each day with entries, and today even when
 * empty (Windows schedule_rows).
 * @param {Map<string, import('./api.js').Entry[]>} days @param {string} current today
 * @returns {({ kind: 'month', day: string, text: string } | { kind: 'day', day: string, entries: import('./api.js').Entry[], today: boolean })[]}
 */
export function scheduleRows(days, current) {
  /** @type {any[]} */
  const rows = [];
  let month = '';
  for (const [day, entries] of days) {
    if (!entries.length && day !== current) continue;
    if (day.slice(0, 7) !== month) {
      month = day.slice(0, 7);
      rows.push({ kind: 'month', day, text: monthTitle(day) });
    }
    rows.push({ kind: 'day', day, entries, today: day === current });
  }
  return rows;
}

/** Search results: a month title when it changes (upcoming first, then the past, as the server sorted them). @param {import('./api.js').Entry[]} hits */
export function searchRows(hits) {
  /** @type {any[]} */
  const rows = [];
  let month = '';
  for (const e of hits) {
    const day = e.start_date;
    if (day.slice(0, 7) !== month) {
      month = day.slice(0, 7);
      rows.push({ kind: 'month', day, text: monthTitle(day) });
    }
    const last = rows[rows.length - 1];
    if (last.kind === 'day' && last.day === day) last.entries.push(e);
    else rows.push({ kind: 'day', day, entries: [e], today: false });
  }
  return rows;
}

/** An entry drawn as a bar (all-day, or timed over several days) rather than a dot. @param {import('./api.js').Entry} e */
export const isBar = (e) => e.all_day || e.end_date > e.start_date;

/**
 * Lanes of `columns` days from `first` (a week row of the month, the all-day band of the week): each entry in the
 * first free lane, bars first (longest first), then by time; and how many entries touch each column.
 * @param {import('./api.js').Entry[]} entries @param {string} first @param {number} columns @param {(e: any) => boolean} [keep]
 * @returns {{ segments: { entry: import('./api.js').Entry, col: number, span: number, lane: number }[], counts: number[] }}
 */
export function lanes(entries, first, columns, keep = () => true) {
  const last = addDays(first, columns - 1);
  const shown = entries.filter((e) => keep(e) && e.end_date >= first && e.start_date <= last);
  shown.sort((a, b) => Number(isBar(b)) - Number(isBar(a))
    || (isBar(a) ? daysBetween(b.start_date, b.end_date) - daysBetween(a.start_date, a.end_date) : 0)
    || a.start - b.start || a.title.localeCompare(b.title));
  /** @type {boolean[][]} */
  const taken = [];
  const counts = new Array(columns).fill(0);
  const segments = [];
  for (const entry of shown) {
    const col = Math.max(0, daysBetween(first, entry.start_date));
    const end = Math.min(columns - 1, daysBetween(first, entry.end_date));
    const span = isBar(entry) ? end - col + 1 : 1;
    let lane = 0;
    while (taken[lane] && taken[lane].slice(col, col + span).some(Boolean)) lane++;
    taken[lane] = taken[lane] || new Array(columns).fill(false);
    for (let c = col; c < col + span; c++) {
      taken[lane][c] = true;
      counts[c]++;
    }
    segments.push({ entry, col, span, lane });
  }
  return { segments, counts };
}

/**
 * Timed entries of one day in the time grid: minutes from midnight (clipped to the day), side by side when they
 * overlap (each group of overlapping entries shares the width).
 * @param {import('./api.js').Entry[]} entries @param {string} day
 * @returns {{ entry: import('./api.js').Entry, top: number, bottom: number, column: number, columns: number }[]}
 */
export function dayLayout(entries, day) {
  const items = entries
    .filter((e) => !isBar(e) && e.start_date <= day && e.end_date >= day)
    .map((entry) => {
      const top = entry.start_date < day ? 0 : minutesOf(entry.start);
      const endDay = dayOf(entry.end);
      let bottom = endDay > day ? 1440 : minutesOf(entry.end);
      bottom = Math.max(bottom, top + 20); // a zero-length or short entry stays clickable
      return { entry, top, bottom: Math.min(1440, bottom), column: 0, columns: 1 };
    })
    .sort((a, b) => a.top - b.top || b.bottom - a.bottom);
  /** @type {typeof items} */
  let group = [];
  let groupEnd = -1;
  /** @type {number[]} */
  let ends = [];
  const close = () => {
    for (const it of group) it.columns = ends.length;
    group = [];
    ends = [];
  };
  for (const it of items) {
    if (it.top >= groupEnd) close();
    let column = ends.findIndex((end) => end <= it.top);
    if (column < 0) {
      column = ends.length;
      ends.push(it.bottom);
    } else ends[column] = it.bottom;
    it.column = column;
    group.push(it);
    groupEnd = Math.max(groupEnd, it.bottom);
  }
  close();
  return items;
}

/** Text colour readable on a bar of `color` (the palette is chosen for white text; others are guessed). @param {string} color */
export function inkOn(color) {
  const m = /^#?([0-9a-f]{2})([0-9a-f]{2})([0-9a-f]{2})$/i.exec(color);
  if (!m) return '#FFFFFF';
  const [r, g, b] = [m[1], m[2], m[3]].map((x) => parseInt(x, 16) / 255);
  return 0.2126 * r + 0.7152 * g + 0.0722 * b > 0.6 ? '#1B1B1B' : '#FFFFFF';
}

// ---- wall stamps of the editor ('2026-11-12T14:30', naive) ----

/** @param {string} s @returns {number} minutes since 1970 of the naive wall time */
export function stampMinutes(s) {
  const [y, m, d] = parts(s);
  const hh = s.length > 10 ? Number(s.slice(11, 13)) : 0;
  const mm = s.length > 10 ? Number(s.slice(14, 16)) : 0;
  return Date.UTC(y, m - 1, d, hh, mm) / 60000;
}
/** @param {number} minutes @param {boolean} allDay */
export function minutesStamp(minutes, allDay) {
  const iso = new Date(minutes * 60000).toISOString();
  return allDay ? iso.slice(0, 10) : iso.slice(0, 16);
}
