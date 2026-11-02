<script>
  // The month: 6 weeks filling the page; bars for all-day and multi-day entries, a dot and the time for the
  // others; "+N" when a day holds more than fits (it opens the day).
  import { createEventDispatcher } from 'svelte';
  import { WEEKDAYS_SHORT, addDays, hhmm, inkOn, isBar, lanes, minutesOf, parts, wall, weekday } from './dates.js';

  /** @type {import('./api.js').Entry[]} */
  export let entries = [];
  export let first = '';
  export let month = '';
  export let today = '';
  export let eventZone = false;
  const dispatch = createEventDispatcher();
  const HEAD = 26;
  const LANE = 21;
  let height = 600;

  $: weeks = Array.from({ length: 6 }, (_, w) => addDays(first, w * 7));
  $: rowHeight = Math.max(60, (height - 28) / 6);
  $: maxLanes = Math.max(1, Math.floor((rowHeight - HEAD - 2) / LANE));
  $: rows = weeks.map((start) => {
    const { segments, counts } = lanes(entries, start, 7);
    const shown = segments.filter((s) => {
      for (let c = s.col; c < s.col + s.span; c++) if (counts[c] > maxLanes && s.lane >= maxLanes - 1) return false;
      return s.lane < maxLanes;
    });
    const hidden = counts.map((n, c) => (n > maxLanes ? n - shown.filter((s) => s.col <= c && c < s.col + s.span).length : 0));
    return { start, shown, hidden };
  });
  /** @param {import('./api.js').Entry} e */
  const startText = (e) => (eventZone ? wall(e, true).start : hhmm(minutesOf(e.start)));
  $: firstWeekday = weekday(first);
</script>

<div class="month" bind:clientHeight={height}>
  <div class="heads">
    {#each Array(7) as _, i}<div class="head">{WEEKDAYS_SHORT[(firstWeekday + i) % 7]}</div>{/each}
  </div>
  {#each rows as row (row.start)}
    <div class="week" style="height: {rowHeight}px">
      {#each Array(7) as _, c}
        {@const day = addDays(row.start, c)}
        <!-- svelte-ignore a11y-click-events-have-key-events a11y-no-static-element-interactions -->
        <div class="cell" class:first={c === 0} class:other={day.slice(0, 7) !== month.slice(0, 7)} style="left: {(c / 7) * 100}%"
          on:click|self={() => dispatch('day', day)}>
          <button class="num" class:today={day === today} on:click={() => dispatch('day', day)}
            title="Voir la journée">{parts(day)[2] === 1 ? `1er` : parts(day)[2]}</button>
          {#if row.hidden[c]}
            <button class="more" style="top: {HEAD + (maxLanes - 1) * LANE}px" on:click={() => dispatch('day', day)}>+{row.hidden[c]}</button>
          {/if}
        </div>
      {/each}
      {#each row.shown as s (s.entry.key + row.start)}
        {@const e = s.entry}
        <button class="item" class:bar={isBar(e)} title={e.title}
          style="left: calc({(s.col / 7) * 100}% + 3px); width: calc({(s.span / 7) * 100}% - 6px); top: {HEAD + s.lane * LANE}px;
            --c: {e.color}; --ink: {inkOn(e.color)}"
          on:click={() => dispatch('open', e)}>
          {#if !isBar(e)}<span class="dot"></span><span class="time">{startText(e)}</span>{/if}
          <span class="t">{e.title || '(Sans titre)'}</span>
        </button>
      {/each}
    </div>
  {/each}
</div>

<style>
  .month { height: 100%; display: flex; flex-direction: column; padding: 0 8px 8px; overflow: hidden; }
  .heads { display: grid; grid-template-columns: repeat(7, 1fr); height: 28px; flex: none; }
  .head { text-align: center; font-size: 12px; color: var(--on-surface-variant); text-transform: uppercase; letter-spacing: 0.04em; padding-top: 6px; }
  .week { position: relative; flex: none; border-top: 1px solid var(--outline-variant); }
  .cell { position: absolute; top: 0; bottom: 0; width: calc(100% / 7); border-left: 1px solid var(--outline-variant); cursor: pointer; }
  .cell.first { border-left: none; }
  .cell.other { background: color-mix(in srgb, var(--surface) 55%, transparent); }
  .cell.other .num { color: var(--on-surface-variant); font-weight: 400; }
  .num {
    margin: 2px 0 0 4px; border: none; background: transparent; cursor: pointer; font-size: 13px; font-weight: 600;
    min-width: 22px; height: 22px; border-radius: 11px; padding: 0 5px; color: var(--on-surface);
  }
  .num:hover { background: var(--surface); }
  .num.today { background: var(--primary); color: var(--on-primary); }
  .item {
    position: absolute; height: 19px; display: flex; align-items: center; gap: 4px; border: none; background: transparent;
    border-radius: 5px; padding: 0 5px; font-size: 12.5px; cursor: pointer; color: var(--on-surface); text-align: left;
    overflow: hidden; white-space: nowrap;
  }
  .item:hover { background: var(--surface-high); }
  .item.bar { background: var(--c); color: var(--ink); font-weight: 600; }
  .item.bar:hover { filter: brightness(1.08); }
  .dot { width: 8px; height: 8px; border-radius: 50%; background: var(--c); flex: none; }
  .time { color: var(--on-surface-variant); flex: none; font-variant-numeric: tabular-nums; }
  .t { overflow: hidden; text-overflow: ellipsis; }
  .more {
    position: absolute; left: 3px; right: 3px; height: 19px; border: none; background: transparent; text-align: left;
    padding: 0 6px; font-size: 12.5px; font-weight: 700; color: var(--on-surface-variant); cursor: pointer; border-radius: 5px;
  }
  .more:hover { background: var(--surface-high); }
  @media (max-width: 600px) {
    .month { padding: 0 2px 4px; }
    .time { display: none; }
    .item { font-size: 11px; padding: 0 3px; }
    .dot { width: 6px; height: 6px; }
  }
</style>
