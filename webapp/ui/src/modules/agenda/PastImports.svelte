<script>
  // "Imports passés": the imports whose files are still there, each with "Annuler cet import".
  import { createEventDispatcher, onMount } from 'svelte';
  import Modal from '../../lib/components/Modal.svelte';
  import { confirm, showError, showSnack } from '../../lib/ui.js';
  import { agenda } from './api.js';
  import { plural } from './dates.js';

  const dispatch = createEventDispatcher();
  /** @type {{ id: string, files: number, calendars: string[], date: string }[] | null} */
  let imports = null;

  async function load() {
    try {
      imports = (await agenda.imports()).imports;
    } catch (error) {
      showError(error);
    }
  }
  onMount(load);

  /** @param {{ id: string, date: string }} item */
  async function undoImport(item) {
    if (!(await confirm("Annuler l'import", `Supprimer les événements de l'import du ${item.date} ?`, { confirm: 'Supprimer', danger: true }))) return;
    try {
      showSnack(`Import annulé : ${(await agenda.undoImport(item.id)).message}`);
      dispatch('changed');
      load();
    } catch (error) {
      showError(error);
    }
  }
</script>

<Modal title="Imports passés" wide on:close={() => dispatch('close')}>
  {#if imports === null}
    <p class="muted">Chargement…</p>
  {:else if !imports.length}
    <p class="muted">Aucun import : les fichiers importés ont tous été retirés ou modifiés à la main.</p>
  {:else}
    {#each imports as item (item.id)}
      <div class="item">
        <div class="what">
          <strong>{item.date}</strong>
          <span class="muted">{plural(item.files, 'fichier', 'fichiers')} · {item.calendars.join(', ')}</span>
        </div>
        <button class="btn delete" on:click={() => undoImport(item)}>Annuler cet import</button>
      </div>
    {/each}
  {/if}
  <svelte:fragment slot="actions">
    <button class="btn primary" on:click={() => dispatch('close')}>Fermer</button>
  </svelte:fragment>
</Modal>

<style>
  .item { display: flex; align-items: center; gap: 10px; padding: 8px 0; border-top: 1px solid var(--outline-variant); flex-wrap: wrap; }
  .item:first-child { border-top: none; }
  .what { flex: 1; min-width: 200px; display: flex; flex-direction: column; }
  .btn.delete { color: var(--error); }
</style>
