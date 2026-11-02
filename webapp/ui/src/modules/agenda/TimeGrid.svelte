<script>
  // Week and day views: the 24 hours always fit the page height (no vertical scroll, as the owner asked on the other
  // platforms), all-day and multi-day entries in a band above, overlapping entries side by side, the current time
  // as a line. A click on an empty slot creates an event at that half hour.
  import { createEventDispatcher, onDestroy } from 'svelte';
  import { WEEKDAYS_SHORT, dayLayout, hhmm, inkOn, isBar, lanes, minutesOf, parts, today as todayIso, wall, weekday } from './dates.js';

  /** @type {string[]} */
  export let days = [];
  /** @type {import('./api.js').Entry[]} */
  export let entries = [];
  export let today = '';
  export let eventZone = false;
  const dispatch = createEventDispatcher();
  const BAND_LANE = 22;
  const MAX_BAND = 3;
  let gridHeight = 500;
  let now = Date.now();
  const tick = setInterval(() => (now = Date.now()), 60_000);
  onDestroy(() => clearInterval(tick));

  $: band = lanes(entries, days[0], days.length, isBar);
  $: bandLanes = Math.min(MAX_BAND, Math.max(0, ...band.segments.map((s) => s.lane + 1)));
  $: bandShown = band.segments.filter((s) => s.lane < bandLanes);
  $: hidden = band.counts.map((n, c) => n - bandShown.filter((s) => s.col <= c && c < s.col + s.span).length);
  $: hour = gridHeight / 24;
  $: columns = days.map((day) => ({ day, items: dayLayout(entries, day) }));
  $: nowDay = (void now, todayIso());
  $: nowTop = (minutesOf(now) / 1440) * gridHeight;

  /** @param {MouseEvent} e @param {string} day */
  function slot(e, day) {
    const box = /** @type {HTMLElement} */ (e.currentTarget).getBoundingClientRect();
    const minutes = Math.min(1410, Math.max(0, Math.floor(((e.clientY - box.top) / box.height) * 48) * 30));
    dispatch('slot', { day, minutes });
  }
  /** @param {import('./api.js').Entry} e */
  function label(e) {
    const w = wall(e, eventZone);
    return e.end === e.start ? w.start : `${w.start} – ${w.end}`;
  }
</script>

<div class="grid" style="--cols: {days.length}">
  <div class="heads">
    <div class="gutter"></div>
    {#each days as day}
      <button class="head" class:today={day === today} on:click={() => dispatch('day', day)} title="Voir la journée">
        <span class="wd">{WEEKDAYS_SHORT[weekday(day)]}</span><span class="num">{parts(day)[2]}</span>
      </button>
    {/each}
  </div>
  <div class="band" style="height: {Math.max(1, bandLanes + (hidden.some((n) => n > 0) ? 1 : 0)) * BAND_LANE + 6}px">
    <div class="gutter"></div>
    <div class="band-area">
      {#each bandShown as s (s.entry.key)}
        <button class="bar" title={s.entry.title}
          style="left: calc({(s.col / days.length) * 100}% + 2px); width: calc({(s.span / days.length) * 100}% - 4px); top: {3 + s.lane * BAND_LANE}px; --c: {s.entry.color}; --ink: {inkOn(s.entry.color)}"
          on:click={() => dispatch('open', s.entry)}>{s.entry.title || '(Sans titre)'}</button>
      {/each}
      {#each hidden as n, c}
        {#if n > 0}
          <button class="more" style="left: calc({(c / days.length) * 100}% + 2px); width: calc({100 / days.length}% - 4px); top: {3 + bandLanes * BAND_LANE}px"
            on:click={() => dispatch('day', days[c])}>+{n}</button>
        {/if}
      {/each}
    </div>
  </div>
  <div class="body">
    <div class="hours">
      {#each Array(24) as _, h}
        <span class="h" style="top: {h * hour}px">{h ? hhmm(h * 60) : ''}</span>
      {/each}
    </div>
    <div class="cols" bind:clientHeight={gridHeight}>
      {#each Array(24) as _, h}<div class="line" style="top: {h * hour}px"></div>{/each}
      {#each columns as col, c (col.day)}
        <!-- svelte-ignore a11y-click-events-have-key-events a11y-no-static-element-interactions -->
        <div class="col" class:today={col.day === today} style="left: {(c / days.length) * 100}%" on:click|self={(e) => slot(e, col.day)}>
          {#each col.items as it (it.entry.key)}
            {@const h = ((it.bottom - it.top) / 1440) * gridHeight}
            <button class="ev" class:short={h < 30} title="{label(it.entry)} · {it.entry.title}"
              style="top: {(it.top / 1440) * gridHeight}px; height: {Math.max(14, h - 1)}px; left: calc({(it.column / it.columns) * 100}% + 1px);
                width: calc({100 / it.columns}% - 3px); --c: {it.entry.color}; --ink: {inkOn(it.entry.color)}"
              on:click={() => dispatch('open', it.entry)}>
              <span class="t">{it.entry.title || '(Sans titre)'}</span>
              {#if h >= 30}<span class="when">{label(it.entry)}</span>{/if}
            </button>
          {/each}
          {#if col.day === nowDay}<div class="now" style="top: {nowTop}px"></div>{/if}
        </div>
      {/each}
    </div>
  </div>
</div>

<style>
  .grid { height: 100%; display: flex; flex-direction: column; padding: 0 8px 8px 0; overflow: hidden; }
  .heads { display: flex; flex: none; }
  .gutter { width: 52px; flex: none; }
  .head {
    flex: 1; min-width: 0; border: none; background: transparent; cursor: pointer; display: flex; flex-direction: column;
    align-items: center; padding: 2px 0 4px; color: var(--on-surface-variant); border-radius: 12px;
  }
  .head:hover { background: var(--surface); }
  .wd { font-size: 12px; text-transform: uppercase; letter-spacing: 0.04em; }
  .num { font-size: 20px; font-weight: 600; color: var(--on-surface); width: 34px; height: 34px; border-radius: 50%; display: flex; align-items: center; justify-content: center; }
  .head.today .num { background: var(--primary); color: var(--on-primary); }
  .head.today .wd { color: var(--primary); font-weight: 700; }
  .band { display: flex; flex: none; border-bottom: 1px solid var(--outline-variant); }
  .band-area { position: relative; flex: 1; }
  .bar, .more {
    position: absolute; height: 20px; border: none; border-radius: 5px; padding: 0 6px; font-size: 12.5px; font-weight: 600;
    text-align: left; overflow: hidden; white-space: nowrap; text-overflow: ellipsis; cursor: pointer;
  }
  .bar { background: var(--c); color: var(--ink); }
  .more { background: transparent; color: var(--on-surface-variant); }
  .more:hover { background: var(--surface); }
  .body { flex: 1; min-height: 0; display: flex; }
  .hours { width: 52px; flex: none; position: relative; }
  .h { position: absolute; right: 8px; transform: translateY(-50%); font-size: 11px; color: var(--on-surface-variant); font-variant-numeric: tabular-nums; }
  .cols { flex: 1; position: relative; min-width: 0; }
  .line { position: absolute; left: 0; right: 0; border-top: 1px solid var(--outline-variant); opacity: 0.6; pointer-events: none; }
  .col { position: absolute; top: 0; bottom: 0; width: calc(100% / var(--cols)); border-left: 1px solid var(--outline-variant); cursor: copy; }
  .col.today { background: color-mix(in srgb, var(--primary-container) 18%, transparent); }
  .ev {
    position: absolute; border: none; border-radius: 6px; background: var(--c); color: var(--ink); padding: 2px 6px; text-align: left;
    overflow: hidden; cursor: pointer; display: flex; flex-direction: column; font-size: 12.5px; line-height: 1.25;
    box-shadow: 0 0 0 1px var(--background);
  }
  .ev.short { flex-direction: row; align-items: center; padding: 0 6px; font-size: 11.5px; }
  .ev:hover { filter: brightness(1.08); z-index: 2; }
  .t { font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .when { opacity: 0.9; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .now { position: absolute; left: -1px; right: 0; height: 2px; background: var(--error); pointer-events: none; z-index: 3; }
  .now::before { content: ''; position: absolute; left: -5px; top: -4px; width: 10px; height: 10px; border-radius: 50%; background: var(--error); }
  @media (max-width: 600px) {
    .gutter, .hours { width: 34px; }
    .h { right: 4px; font-size: 10px; }
    .ev { padding: 1px 3px; font-size: 11px; }
    .num { font-size: 16px; width: 28px; height: 28px; }
  }
</style>
