<script>
  // The Agenda view (and the search results): month titles, then each day with its entries; today always shown.
  // A sentinel at the bottom asks for more days when it comes into view.
  import { createEventDispatcher, onDestroy, onMount } from 'svelte';
  import Icon from '../../lib/components/Icon.svelte';
  import { WEEKDAYS_SHORT, MONTHS_SHORT, cap, longDate, parts, timeText, weekday } from './dates.js';

  /** @type {any[]} rows of dates.scheduleRows / searchRows */
  export let rows = [];
  export let eventZone = false;
  export let today = '';
  export let more = false;
  /** @type {string} shown when there is no row at all */
  export let empty = '';
  const dispatch = createEventDispatcher();
  /** @type {HTMLElement} */
  let sentinel;
  /** @type {HTMLElement} */
  let scroller;
  /** @type {IntersectionObserver | null} */
  let observer = null;
  const KIND_ICON = { activity: 'run', note: 'notes', birthday: 'cake' };

  onMount(() => {
    observer = new IntersectionObserver((items) => items.some((i) => i.isIntersecting) && more && dispatch('more'),
      { root: scroller, rootMargin: '400px' });
    if (sentinel) observer.observe(sentinel);
  });
  onDestroy(() => observer?.disconnect());
  $: if (observer && sentinel) observer.observe(sentinel);

  export function scrollTop() {
    if (scroller) scroller.scrollTop = 0;
  }
</script>

<div class="scroll" bind:this={scroller}>
  <div class="list">
    {#if !rows.length && empty}<p class="muted none">{empty}</p>{/if}
    {#each rows as row (row.kind + row.day)}
      {#if row.kind === 'month'}
        <h2 class="month">{row.text}</h2>
      {:else}
        <section class="day" class:today={row.today}>
          <div class="date" title={cap(longDate(row.day))}>
            <span class="wd">{WEEKDAYS_SHORT[weekday(row.day)]}</span>
            <span class="num">{parts(row.day)[2]}</span>
            {#if row.day.slice(0, 4) !== today.slice(0, 4)}<span class="yr">{MONTHS_SHORT[parts(row.day)[1] - 1]} {row.day.slice(0, 4)}</span>{/if}
          </div>
          <div class="entries">
            {#if !row.entries.length}
              <p class="muted nothing">Rien de prévu aujourd'hui</p>
            {/if}
            {#each row.entries as e (e.key)}
              <button class="entry" on:click={() => dispatch('open', e)} style="--c: {e.color}">
                <span class="bar"></span>
                <span class="time">{timeText(e, row.day, eventZone, today)}</span>
                <span class="text">
                  <span class="title">
                    {#if KIND_ICON[e.kind]}<Icon name={KIND_ICON[e.kind]} size={16} />{/if}
                    <span class="t" class:tentative={e.tentative}>{e.title || '(Sans titre)'}</span>
                    {#if e.recurring}<span class="rep" title="Événement répété"><Icon name="repeat" size={14} /></span>{/if}
                  </span>
                  {#if e.flight}<span class="sub">{e.flight}</span>{:else if e.subtitle}<span class="sub">{e.subtitle}</span>{/if}
                </span>
              </button>
            {/each}
          </div>
        </section>
      {/if}
    {/each}
    <div class="sentinel" bind:this={sentinel}>{#if more}<span class="muted">Chargement…</span>{/if}</div>
  </div>
</div>

<style>
  .scroll { height: 100%; overflow-y: auto; }
  .list { max-width: 900px; margin: 0 auto; padding: 4px 16px 120px; }
  .none { text-align: center; margin-top: 48px; }
  .month { font-size: 18px; font-weight: 600; margin: 22px 8px 8px; }
  .day { display: flex; gap: 12px; padding: 6px 0; border-top: 1px solid var(--outline-variant); }
  .month + .day { border-top: none; }
  .date { width: 56px; flex: none; display: flex; flex-direction: column; align-items: center; padding-top: 6px; color: var(--on-surface-variant); }
  .wd { font-size: 12px; text-transform: uppercase; letter-spacing: 0.04em; }
  .num { font-size: 22px; font-weight: 600; line-height: 1.2; color: var(--on-surface); width: 40px; height: 40px; display: flex; align-items: center; justify-content: center; border-radius: 50%; }
  .yr { font-size: 11px; white-space: nowrap; }
  .today .num { background: var(--primary); color: var(--on-primary); }
  .today .wd { color: var(--primary); font-weight: 700; }
  .entries { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px; padding: 4px 0; }
  .nothing { margin: 10px 4px; }
  .entry {
    display: grid; grid-template-columns: 4px 128px minmax(0, 1fr); grid-template-areas: "bar time text"; align-items: start;
    gap: 2px 12px; border: none; background: transparent; text-align: left; cursor: pointer;
    padding: 8px 10px; border-radius: 12px; width: 100%; color: inherit; min-height: 44px;
  }
  .entry:hover { background: var(--surface); }
  .bar { grid-area: bar; width: 4px; align-self: stretch; border-radius: 2px; background: var(--c); flex: none; min-height: 20px; }
  .time { grid-area: time; color: var(--on-surface-variant); font-size: 14px; padding-top: 1px; font-variant-numeric: tabular-nums; }
  .text { grid-area: text; min-width: 0; display: flex; flex-direction: column; }
  .title { display: flex; align-items: center; gap: 6px; font-weight: 600; min-width: 0; }
  .title :global(svg) { color: var(--c); flex: none; }
  .t { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .t.tentative { font-style: italic; }
  .rep { color: var(--on-surface-variant); display: inline-flex; }
  .rep :global(svg) { color: var(--on-surface-variant); }
  .sub { color: var(--on-surface-variant); font-size: 14px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .sentinel { height: 40px; display: flex; align-items: center; justify-content: center; }
  @media (max-width: 600px) {
    .list { padding: 4px 8px 120px; }
    .date { width: 44px; }
    .entry { grid-template-columns: 4px minmax(0, 1fr); grid-template-areas: "bar text" "bar time"; gap: 0 10px; padding: 6px 8px; }
    .time { font-size: 13px; }
  }
</style>
