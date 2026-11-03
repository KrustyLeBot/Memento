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
  /** the file being sent: its share sent (0..1) and what is happening to it */
  let current = { name: '', fraction: 0, stage: 'pack' };

  const STAGES = /** @type {Record<string, string>} */ ({ pack: 'Préparation', send: 'Envoi', wait: 'Lecture par le serveur' });
  $: overall = files.length ? (sent + (current.stage === 'wait' ? 1 : current.fraction * 0.9)) / files.length : 0;

  onMount(async () => {
    for (const file of files) {
      current = { name: file.name, fraction: 0, stage: 'pack' };
      try {
        const staged = await api.upload(file, (fraction, stage) => { current = { name: file.name, fraction, stage }; });
        items = [...items, { staged, title: staged.title, sport: GPS_SPORTS.includes(staged.sport) ? staged.sport : 'gps', altitude: false }];
      } catch (error) {
        failed = [...failed, { name: file.name, error: error instanceof Error ? error.message : String(error) }];
      }
      sent += 1;
      current = { name: '', fraction: 0, stage: 'pack' };
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
    <p class="muted status" aria-live="polite">
      {#if files.length > 1}Fichier {Math.min(sent + 1, files.length)}/{files.length} · {/if}{current.name}
      <br />{STAGES[current.stage]}{current.stage === 'send' ? ` · ${Math.round(current.fraction * 100)} %` : '…'}
    </p>
    <div class="track"><div class="fill" style="width: {Math.round(100 * overall)}%"></div></div>
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
  .status { margin: 0 0 8px; word-break: break-all; font-variant-numeric: tabular-nums; }
  .track { height: 4px; border-radius: 2px; background: rgba(128, 128, 128, 0.3); overflow: hidden; margin-bottom: 12px; }
  .fill { height: 100%; background: var(--primary); transition: width 0.3s; }
  @media (max-width: 600px) { .fields { grid-template-columns: 1fr; } }
</style>
