<script>
  // Import of FIT / GPX files: each is sent and read by the server first (nothing written yet), then the user
  // checks name, sport and whether to look the altitude up; the import itself runs in the background.
  // A file recorded at the same start as an existing activity replaces it (name, description and photos kept).
  import { createEventDispatcher, onMount } from 'svelte';
  import Modal from '../../lib/components/Modal.svelte';
  import { showError } from '../../lib/ui.js';
  import { activities as api } from './api.js';
  import { EMOJI, GPS_SPORTS, shortLabel } from './activities.js';

  /** @type {File[]} */
  export let files;
  const dispatch = createEventDispatcher();

  /** @type {{ staged: import('./api.js').Staged, title: string, sport: string, altitude: boolean }[]} */
  let items = [];
  /** @type {{ name: string, error: string }[]} */
  let failed = [];
  let sent = 0;
  let uploading = true;
  let busy = false;

  onMount(async () => {
    for (const file of files) {
      try {
        const staged = await api.upload(file);
        items = [...items, { staged, title: staged.title, sport: GPS_SPORTS.includes(staged.sport) ? staged.sport : 'gps', altitude: false }];
      } catch (error) {
        failed = [...failed, { name: file.name, error: error instanceof Error ? error.message : String(error) }];
      }
      sent += 1;
    }
    uploading = false;
  });

  async function start() {
    busy = true;
    try {
      const started = await api.importStaged(items.map((i) => ({ token: i.staged.token, name: i.staged.name, title: i.title.trim(), sport: i.sport, altitude: i.altitude })));
      dispatch('started', started);
    } catch (error) {
      showError(error, 'Import impossible');
      busy = false;
    }
  }

  $: replacing = items.filter((i) => i.staged.existing).length;
  $: anyAltitude = items.some((i) => i.altitude);
</script>

<Modal title={files.length > 1 ? `Importer ${files.length} activités` : 'Importer l\'activité'} wide on:close={() => !busy && dispatch('close')}>
  {#if uploading}
    <p class="muted">Envoi des fichiers… {sent}/{files.length}</p>
    <div class="track"><div class="fill" style="width: {Math.round((100 * sent) / files.length)}%"></div></div>
  {/if}
  {#each failed as f}
    <p class="error">{f.error}</p>
  {/each}
  <div class="items">
    {#each items as item (item.staged.token)}
      <div class="item">
        <div class="name">{item.staged.name}</div>
        {#if item.staged.existing}
          <p class="warn">Déjà importée : « {item.staged.existing.title} ». Elle sera remplacée (nom, description et photos conservés).</p>
        {/if}
        <div class="fields">
          <label class="title">Nom<input class="field" bind:value={item.title} placeholder={item.staged.title} /></label>
          <label>Sport
            <select class="field" bind:value={item.sport}>
              {#each GPS_SPORTS as s}<option value={s}>{EMOJI[s]} {shortLabel(s)}</option>{/each}
            </select>
          </label>
        </div>
        {#if item.staged.needs_altitude}
          <label class="check"><input type="checkbox" bind:checked={item.altitude} />
            Compléter l'altitude avec le relief (le fichier n'en a pas ; envoie les positions à opentopodata.org)</label>
        {/if}
      </div>
    {/each}
  </div>
  {#if anyAltitude}<p class="muted small">L'altitude prend quelques secondes par activité (une requête par seconde) : l'import continue en arrière-plan.</p>{/if}
  <svelte:fragment slot="actions">
    <button class="btn" on:click={() => dispatch('close')} disabled={busy}>Annuler</button>
    <button class="btn primary" on:click={start} disabled={uploading || busy || !items.length}>
      {replacing && replacing === items.length ? 'Remplacer' : 'Importer'}{items.length > 1 ? ` (${items.length})` : ''}
    </button>
  </svelte:fragment>
</Modal>

<style>
  .items { display: flex; flex-direction: column; gap: 12px; }
  .item { background: var(--surface); border-radius: 16px; padding: 12px 14px; display: flex; flex-direction: column; gap: 8px; }
  .name { font-weight: 600; word-break: break-all; }
  .fields { display: grid; grid-template-columns: 1fr 200px; gap: 10px; }
  .fields label { display: flex; flex-direction: column; gap: 4px; font-size: 13px; color: var(--on-surface-variant); }
  .fields .field { color: var(--on-surface); font-size: 15px; }
  .check { display: flex; gap: 8px; align-items: flex-start; font-size: 14px; }
  .check input { margin-top: 3px; }
  .warn { margin: 0; color: #B26A00; font-weight: 600; font-size: 14px; }
  :global(:root[data-theme="dark"]) .warn { color: #FFC266; }
  .error { color: var(--error); margin: 4px 0; }
  .small { font-size: 13px; }
  .track { height: 4px; border-radius: 2px; background: rgba(128, 128, 128, 0.3); overflow: hidden; margin-bottom: 12px; }
  .fill { height: 100%; background: var(--primary); transition: width 0.3s; }
  @media (max-width: 600px) { .fields { grid-template-columns: 1fr; } }
</style>
