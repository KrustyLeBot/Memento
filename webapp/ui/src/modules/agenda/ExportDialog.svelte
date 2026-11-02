<script>
  // "Exporter": one calendar as .ics, or every calendar as a .zip (one .ics each); optionally the activities and the
  // dated notes. The file is made by the server (core ics_export) and downloaded.
  import { createEventDispatcher } from 'svelte';
  import Modal from '../../lib/components/Modal.svelte';
  import { showSnack } from '../../lib/ui.js';
  import { agenda } from './api.js';

  /** @type {import('./api.js').Calendars} */
  export let settings;
  const dispatch = createEventDispatcher();
  let which = 'one';
  let calendar = settings.default;
  let include = false;

  function exportNow() {
    const a = document.createElement('a');
    a.href = agenda.exportUrl(which === 'one' ? calendar : null, include);
    a.download = '';
    document.body.appendChild(a);
    a.click();
    a.remove();
    showSnack(which === 'one' ? 'Export de l\'agenda téléchargé' : 'Agendas exportés');
    dispatch('close');
  }
</script>

<Modal title="Exporter" on:close={() => dispatch('close')}>
  <div class="col">
    <label class="row"><input type="radio" bind:group={which} value="one" /> Un agenda (.ics)</label>
    <select class="field" bind:value={calendar} disabled={which !== 'one'} aria-label="Agenda à exporter">
      {#each settings.calendars as c}<option value={c.id}>{c.name}</option>{/each}
    </select>
    <label class="row"><input type="radio" bind:group={which} value="all" /> Tous les agendas (.zip, un .ics par agenda)</label>
    <label class="row"><input type="checkbox" bind:checked={include} /> Inclure les activités et les notes datées</label>
  </div>
  <svelte:fragment slot="actions">
    <button class="btn" on:click={() => dispatch('close')}>Annuler</button>
    <button class="btn primary" on:click={exportNow}>Exporter</button>
  </svelte:fragment>
</Modal>

<style>
  .col { display: flex; flex-direction: column; gap: 10px; }
  .row { display: flex; gap: 10px; align-items: center; cursor: pointer; }
  .field { padding: 8px 12px; margin-left: 28px; width: calc(100% - 28px); }
  input[type='radio'], input[type='checkbox'] { accent-color: var(--primary); width: 18px; height: 18px; }
</style>
