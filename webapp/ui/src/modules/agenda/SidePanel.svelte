<script>
  // The calendars and the layers (Activités, Notes datées, Anniversaires): shown / hidden and colour, written to
  // agenda/calendars.json (synced). "Gérer…" opens the calendars dialog.
  import { createEventDispatcher } from 'svelte';
  import Icon from '../../lib/components/Icon.svelte';
  import { calendars, changeCalendars } from './calendars.js';
  import PalettePicker from './PalettePicker.svelte';

  /** @type {string[]} */
  export let problems = [];
  const dispatch = createEventDispatcher();
  const LAYERS = [['activities', 'Activités', 'run'], ['notes', 'Notes datées', 'notes'], ['birthdays', 'Anniversaires', 'cake']];
</script>

{#if $calendars}
  <div class="panel">
    <div class="title"><span>Agendas</span><button class="btn small" on:click={() => dispatch('manage')}>Gérer…</button></div>
    {#each $calendars.calendars as cal (cal.id)}
      <div class="row">
        <label class="check" style="--c: {cal.color}">
          <input type="checkbox" checked={cal.visible} on:change={(e) => changeCalendars({ op: 'update', id: cal.id, visible: e.currentTarget.checked })} />
          <span class="name">{cal.name}</span>
        </label>
        <PalettePicker color={cal.color} palette={$calendars.palette} on:pick={(e) => changeCalendars({ op: 'update', id: cal.id, color: e.detail })} />
      </div>
    {/each}
    <div class="title"><span>Calques</span></div>
    {#each LAYERS as [key, label, icon]}
      {@const layer = $calendars.layers[key]}
      <div class="row">
        <label class="check" style="--c: {layer.color}">
          <input type="checkbox" checked={layer.visible} on:change={(e) => changeCalendars({ op: 'layer', key, visible: e.currentTarget.checked })} />
          <span class="name"><Icon name={icon} size={16} /> {label}</span>
        </label>
        <PalettePicker color={layer.color} palette={$calendars.palette} on:pick={(e) => changeCalendars({ op: 'layer', key, color: e.detail })} />
      </div>
    {/each}
    {#if problems.length}
      <button class="problems" on:click={() => dispatch('problems')}>
        <Icon name="warning" size={18} /> {problems.length} problème{problems.length > 1 ? 's' : ''} de lecture
      </button>
    {/if}
  </div>
{/if}

<style>
  .panel { display: flex; flex-direction: column; gap: 2px; padding: 4px 8px 16px 16px; }
  .title {
    display: flex; align-items: center; justify-content: space-between; color: var(--on-surface-variant); font-size: 12px;
    font-weight: 700; letter-spacing: 0.08em; text-transform: uppercase; margin: 14px 0 4px;
  }
  .btn.small { padding: 4px 10px; font-size: 13px; text-transform: none; letter-spacing: 0; }
  .row { display: flex; align-items: center; gap: 6px; border-radius: 10px; padding: 2px 4px 2px 0; }
  .row:hover { background: var(--surface); }
  .check { flex: 1; min-width: 0; display: flex; align-items: center; gap: 10px; cursor: pointer; padding: 6px 4px; }
  .check input { width: 18px; height: 18px; accent-color: var(--c); flex: none; }
  .name { display: inline-flex; align-items: center; gap: 6px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .name :global(svg) { color: var(--c); }
  .problems {
    margin-top: 14px; display: flex; align-items: center; gap: 8px; border: none; background: var(--error-container);
    color: var(--on-error-container); border-radius: 12px; padding: 8px 12px; cursor: pointer; font-weight: 600; text-align: left;
  }
</style>
