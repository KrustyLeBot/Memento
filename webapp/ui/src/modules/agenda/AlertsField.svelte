<script>
  // Reminders as removable chips, and "Ajouter un rappel" (presets, or a custom number of minutes/hours/days/weeks).
  import { createEventDispatcher } from 'svelte';
  import { ALERT_PRESETS, alertLabel } from './dates.js';

  /** @type {number[]} minutes, negative = before the start */
  export let value = [];
  const dispatch = createEventDispatcher();
  let picking = '';
  let custom = false;
  let amount = 2;
  let unit = 60;
  let sign = -1;

  /** @param {number} minutes */
  function add(minutes) {
    if (!value.includes(minutes)) value = [...value, minutes].sort((a, b) => a - b);
    dispatch('change', value);
  }
  /** @param {number} minutes */
  function remove(minutes) {
    value = value.filter((m) => m !== minutes);
    dispatch('change', value);
  }
  function picked() {
    if (picking === 'custom') custom = true;
    else if (picking !== '') add(Number(picking));
    picking = '';
  }
</script>

<div class="alerts">
  {#each value as minutes (minutes)}
    <span class="chip on">{alertLabel(minutes)}
      <button type="button" class="x" title="Retirer ce rappel" aria-label="Retirer ce rappel {alertLabel(minutes)}" on:click={() => remove(minutes)}>×</button>
    </span>
  {/each}
  <select class="add" bind:value={picking} on:change={picked} aria-label="Ajouter un rappel">
    <option value="">+ Ajouter un rappel</option>
    {#each ALERT_PRESETS.filter((m) => !value.includes(m)) as m}<option value={String(m)}>{alertLabel(m)}</option>{/each}
    <option value="custom">Personnalisé…</option>
  </select>
</div>
{#if custom}
  <div class="custom">
    <input class="field num" type="number" min="1" max="999" bind:value={amount} aria-label="Durée" />
    <select class="field" bind:value={unit} aria-label="Unité">
      <option value={1}>minutes</option><option value={60}>heures</option><option value={1440}>jours</option><option value={10080}>semaines</option>
    </select>
    <select class="field" bind:value={sign} aria-label="Avant ou après">
      <option value={-1}>avant</option><option value={1}>après</option>
    </select>
    <button type="button" class="btn" on:click={() => { add(sign * Math.max(1, Math.round(amount)) * unit); custom = false; }}>Ajouter</button>
    <button type="button" class="btn" on:click={() => (custom = false)}>Annuler</button>
  </div>
{/if}

<style>
  .alerts { display: flex; flex-wrap: wrap; gap: 6px; align-items: center; }
  .chip { cursor: default; padding: 3px 4px 3px 12px; }
  .x { border: none; background: transparent; cursor: pointer; font-size: 18px; line-height: 1; padding: 0 6px; color: inherit; border-radius: 50%; }
  .x:hover { background: rgba(128, 128, 128, 0.25); }
  .add { border: 1px dashed var(--outline-variant); border-radius: 8px; background: transparent; color: var(--primary); padding: 5px 8px; font-weight: 600; cursor: pointer; }
  .custom { display: flex; gap: 6px; flex-wrap: wrap; align-items: center; margin-top: 6px; }
  .custom .field { width: auto; padding: 6px 10px; }
  .custom .num { width: 80px; }
</style>
