<script>
  // "Personnalisé…": every N days/weeks/months/years, weekdays, day of the month or n-th weekday, end. The wording
  // and the next occurrences come from the server (core rules.py), so they read as in the apps.
  import { createEventDispatcher, onDestroy } from 'svelte';
  import Modal from '../../lib/components/Modal.svelte';
  import { showError } from '../../lib/ui.js';
  import { agenda } from './api.js';
  import { MONTHS, WEEKDAYS, addDays, dayNumber, parts } from './dates.js';

  /** @type {import('./api.js').RuleSpec} */
  export let spec;
  export let start = '';
  /** @type {string | null} */
  export let tz = null;
  /** @type {string[]} */
  export let exdate = [];
  const dispatch = createEventDispatcher();
  const UNITS = [['DAILY', 'jour(s)'], ['WEEKLY', 'semaine(s)'], ['MONTHLY', 'mois'], ['YEARLY', 'an(s)']];
  const NTH = [[1, '1er'], [2, '2e'], [3, '3e'], [4, '4e'], [5, '5e'], [-1, 'dernier']];
  const day = start.slice(0, 10);

  let s = { ...spec, weekdays: [...spec.weekdays] };
  let end = s.count ? 'count' : s.until ? 'until' : 'never';
  let until = s.until || addDays(day, 90);
  let count = s.count || 10;
  let summary = '';
  let preview = '';
  /** @type {string | null} */
  let rrule = null;
  /** @type {any} */
  let timer = null;

  function current() {
    return { ...s, until: end === 'until' ? until : null, count: end === 'count' ? count : null };
  }
  async function refresh() {
    try {
      const info = await agenda.rule({ start, tz, spec: current(), exdate });
      summary = info.summary || info.description;
      preview = info.preview;
      rrule = info.rrule;
    } catch (error) {
      showError(error);
    }
  }
  function changed() {
    clearTimeout(timer);
    timer = setTimeout(refresh, 120);
  }
  onDestroy(() => clearTimeout(timer));
  $: void (s, end, until, count), changed();

  /** @param {number} i */
  function toggleDay(i) {
    s.weekdays = s.weekdays.includes(i) ? s.weekdays.filter((d) => d !== i) : [...s.weekdays, i].sort();
  }
  async function done() {
    clearTimeout(timer);
    await refresh();
    if (rrule) dispatch('done', rrule);
  }
</script>

<Modal title="Répétition personnalisée" on:close={() => dispatch('close')}>
  <div class="rows">
    <div class="row">
      <span>Tous les</span>
      <input class="field num" type="number" min="1" max="999" bind:value={s.interval} aria-label="Intervalle" />
      <select class="field auto" bind:value={s.freq} aria-label="Unité">
        {#each UNITS as [freq, label]}<option value={freq}>{label}</option>{/each}
      </select>
    </div>
    {#if s.freq === 'WEEKLY'}
      <div class="row days" role="group" aria-label="Jours de la semaine">
        <span>Le</span>
        {#each WEEKDAYS as name, i}
          <button type="button" class="day" class:on={s.weekdays.includes(i)} title={name} aria-pressed={s.weekdays.includes(i)}
            on:click={() => toggleDay(i)}>{name[0].toUpperCase()}</button>
        {/each}
      </div>
    {:else if s.freq === 'MONTHLY'}
      <label class="row"><input type="radio" bind:group={s.monthly} value="day" /> Le
        <input class="field num" type="number" min="1" max="31" bind:value={s.month_day} aria-label="Jour du mois" /> du mois</label>
      <label class="row"><input type="radio" bind:group={s.monthly} value="nth" /> Le
        <select class="field auto" bind:value={s.nth} aria-label="Rang">{#each NTH as [n, label]}<option value={n}>{label}</option>{/each}</select>
        <select class="field auto" bind:value={s.nth_weekday} aria-label="Jour">{#each WEEKDAYS as name, i}<option value={i}>{name}</option>{/each}</select>
      </label>
    {:else if s.freq === 'YEARLY'}
      <p class="muted">Le {dayNumber(day)} {MONTHS[parts(day)[1] - 1]} (date du début)</p>
    {/if}

    <h3>Fin</h3>
    <label class="row"><input type="radio" bind:group={end} value="never" /> Jamais</label>
    <label class="row"><input type="radio" bind:group={end} value="until" /> Le
      <input class="field auto" type="date" bind:value={until} disabled={end !== 'until'} aria-label="Date de fin" /></label>
    <label class="row"><input type="radio" bind:group={end} value="count" /> Après
      <input class="field num" type="number" min="1" max="9999" bind:value={count} disabled={end !== 'count'} aria-label="Nombre d'occurrences" /> occurrences</label>

    <p class="summary">{summary}</p>
    <p class="muted">{preview}</p>
  </div>
  <svelte:fragment slot="actions">
    <button class="btn" on:click={() => dispatch('close')}>Annuler</button>
    <button class="btn primary" on:click={done}>OK</button>
  </svelte:fragment>
</Modal>

<style>
  .rows { display: flex; flex-direction: column; gap: 10px; }
  .row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
  .field.num { width: 76px; padding: 7px 10px; }
  .field.auto { width: auto; padding: 7px 10px; }
  .day {
    width: 36px; height: 36px; border-radius: 50%; border: 1px solid var(--outline-variant); background: transparent;
    cursor: pointer; font-weight: 600;
  }
  .day.on { background: var(--primary); color: var(--on-primary); border-color: var(--primary); }
  h3 { margin: 8px 0 0; font-size: 15px; }
  .summary { margin: 8px 0 0; font-weight: 600; }
  p { margin: 0; }
  input[type='radio'] { accent-color: var(--primary); width: 18px; height: 18px; }
</style>
