<script>
  // Name, description and sport of an activity; the distance of one without track (entered by hand). A sport
  // change re-reads the track on the server (ski descents, pace or speed).
  import { createEventDispatcher } from 'svelte';
  import Modal from '../../lib/components/Modal.svelte';
  import { EMOJI, GPS_SPORTS, SPORT_LABELS } from './activities.js';

  /** @type {import('./api.js').Activity} */
  export let activity;
  export let busy = false;
  const dispatch = createEventDispatcher();

  let title = activity.title;
  let description = activity.description;
  let sport = activity.sport;
  let km = activity.distance > 0 ? String(Math.round(activity.distance) / 1000).replace('.', ',') : '';
  $: kmValue = km.trim() === '' ? 0 : Number(km.replace(',', '.').replace(/\s/g, ''));
  $: kmValid = Number.isFinite(kmValue) && kmValue >= 0;

  function save() {
    /** @type {Record<string, any>} */
    const changes = {};
    if (title.trim() !== activity.title) changes.title = title;
    if (description !== activity.description) changes.description = description;
    if (sport !== activity.sport) changes.sport = sport;
    if (!activity.has_file && Math.round(kmValue * 1000) !== Math.round(activity.distance)) changes.distance = kmValue * 1000;
    dispatch('save', changes);
  }
</script>

<Modal title="Modifier l'activité" on:close={() => !busy && dispatch('close')}>
  <form class="form" on:submit|preventDefault={save}>
    <label>Nom<input class="field" bind:value={title} /></label>
    <label>Description<textarea class="field" rows="5" bind:value={description}></textarea></label>
    {#if activity.has_file}
      <label>Sport
        <select class="field" bind:value={sport}>
          {#each GPS_SPORTS as s}<option value={s}>{EMOJI[s]} {SPORT_LABELS[s]}</option>{/each}
        </select>
      </label>
    {:else}
      <label>Distance (km)<input class="field" inputmode="decimal" bind:value={km} placeholder="0" aria-invalid={!kmValid} /></label>
      {#if !kmValid}<span class="error">Distance invalide</span>{/if}
    {/if}
    <button type="submit" hidden></button>
  </form>
  <svelte:fragment slot="actions">
    <button class="btn" on:click={() => dispatch('close')} disabled={busy}>Annuler</button>
    <button class="btn primary" on:click={save} disabled={busy || !kmValid}>Enregistrer</button>
  </svelte:fragment>
</Modal>

<style>
  .form { display: flex; flex-direction: column; gap: 12px; }
  label { display: flex; flex-direction: column; gap: 4px; font-size: 13px; color: var(--on-surface-variant); }
  .field { color: var(--on-surface); font-size: 15px; resize: vertical; }
  .error { color: var(--error); font-size: 13px; }
</style>
