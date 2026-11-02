<script>
  // "Ajouter à l'agenda" from the note editor (Windows DateNoteDialog): a date, an optional time, an optional
  // reminder; on a dated note also "Retirer de l'agenda". The note editor reloads the note when the server says it
  // changed (live events).
  import { createEventDispatcher, onMount } from 'svelte';
  import Modal from '../../lib/components/Modal.svelte';
  import { ApiError } from '../../lib/api.js';
  import { showError, showSnack } from '../../lib/ui.js';
  import { agenda } from './api.js';
  import { alertLabel, today } from './dates.js';

  /** the note's path */
  export let path = '';
  const dispatch = createEventDispatcher();
  const REMINDERS = [[null, 'Aucun rappel'], [0, "À l'heure"], [-15, '15 min avant'], [-60, '1 h avant'], [-1440, '1 jour avant']];
  let loaded = false;
  let dated = false;
  let version = '';
  let day = today();
  let hasTime = false;
  let time = '09:00';
  /** @type {number | null} */
  let reminder = null;
  let options = REMINDERS;
  let busy = false;

  onMount(async () => {
    try {
      const current = await agenda.noteDate(path);
      version = current.version;
      if (current.start) {
        dated = true;
        day = current.start.slice(0, 10);
        hasTime = current.start.length > 10;
        if (hasTime) time = current.start.slice(11, 16);
        reminder = current.alerts.length ? current.alerts[0] : null;
        if (reminder !== null && !REMINDERS.some(([v]) => v === reminder)) options = [...REMINDERS, [reminder, alertLabel(reminder)]];
      }
      loaded = true;
    } catch (error) {
      showError(error);
      dispatch('close');
    }
  });

  /** @param {boolean} remove */
  async function save(remove) {
    busy = true;
    try {
      await agenda.setNoteDate(path, version, remove ? null : hasTime ? `${day}T${time}` : day, reminder === null ? [] : [reminder]);
      showSnack(remove ? "Note retirée de l'agenda" : "Note ajoutée à l'agenda");
      dispatch('close');
    } catch (error) {
      if (error instanceof ApiError && error.status === 409) showError(new Error("La note venait de changer : rouvrez « Ajouter à l'agenda »."));
      else showError(error);
      busy = false;
    }
  }
</script>

<Modal title={dated ? 'Date de la note' : "Ajouter à l'agenda"} on:close={() => dispatch('close')}>
  {#if loaded}
    <div class="form">
      <label class="row"><span class="label">Date</span><input class="field" type="date" bind:value={day} /></label>
      <div class="row"><span class="label">Heure</span>
        <label class="check"><input type="checkbox" bind:checked={hasTime} /> À une heure précise</label>
        <input class="field time" type="time" bind:value={time} disabled={!hasTime} aria-label="Heure" />
      </div>
      <label class="row"><span class="label">Rappel</span>
        <select class="field" bind:value={reminder}>{#each options as [value, label]}<option {value}>{label}</option>{/each}</select>
      </label>
      <p class="muted">La note apparaît dans l'agenda (calque Notes) ; le rappel sonne sur le téléphone.</p>
    </div>
  {:else}
    <p class="muted">Chargement…</p>
  {/if}
  <svelte:fragment slot="actions">
    {#if dated}<button class="btn delete" disabled={busy} on:click={() => save(true)}>Retirer de l'agenda</button><span class="grow"></span>{/if}
    <button class="btn" on:click={() => dispatch('close')}>Annuler</button>
    <button class="btn primary" disabled={!loaded || busy || !day} on:click={() => save(false)}>Enregistrer</button>
  </svelte:fragment>
</Modal>

<style>
  .form { display: flex; flex-direction: column; gap: 12px; }
  .row { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
  .label { width: 64px; color: var(--on-surface-variant); }
  .field { width: auto; flex: 1; padding: 8px 12px; }
  .field.time { flex: 0 1 120px; }
  .check { display: inline-flex; align-items: center; gap: 6px; cursor: pointer; }
  .check input { accent-color: var(--primary); width: 18px; height: 18px; }
  p { margin: 0; font-size: 14px; }
  .btn.delete { color: var(--error); }
  .grow { flex: 1; }
</style>
