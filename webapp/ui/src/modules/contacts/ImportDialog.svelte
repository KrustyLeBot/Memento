<script>
  // vCard import, as on Windows: choose a .vcf, preview (cards, likely duplicates, what is not kept), the duplicate
  // policy ("Garder les deux" by default), progress, then the report with "Annuler l'import".
  // The server writes in short steps (each request well under a minute) and the bar follows them.
  import { createEventDispatcher, onDestroy } from 'svelte';
  import { undo } from '../../lib/api.js';
  import { showError, showSnack } from '../../lib/ui.js';
  import Modal from '../../lib/components/Modal.svelte';
  import Icon from '../../lib/components/Icon.svelte';
  import { api, plural, previewLines, reportLines } from './contacts.js';

  const dispatch = createEventDispatcher();
  /** @type {'pick' | 'reading' | 'preview' | 'running' | 'done'} */
  let stage = 'pick';
  /** @type {import('./contacts.js').ImportPreview | null} */
  let preview = null;
  let policy = 'keep_both';
  /** @type {import('./contacts.js').ImportProgress | null} */
  let progress = null;
  let failure = '';
  let closed = false;
  /** @type {HTMLInputElement} */
  let input;

  onDestroy(() => (closed = true));

  /** @param {Event} e */
  async function picked(e) {
    const file = /** @type {HTMLInputElement} */ (e.currentTarget).files?.[0];
    if (!file) return;
    stage = 'reading';
    failure = '';
    try {
      preview = await api.upload(file);
      stage = 'preview';
    } catch (error) {
      failure = error instanceof Error ? error.message : String(error);
      stage = 'pick';
    } finally {
      input.value = '';
    }
  }

  async function run() {
    if (!preview) return;
    stage = 'running';
    try {
      progress = await api.start(preview.token, policy);
      while (!progress.finished && !closed) progress = await api.step(preview.token);
      stage = 'done';
    } catch (error) {
      showError(error, 'Import');
      if (progress) {
        stage = 'done'; // the undo token still removes what was written
        failure = error instanceof Error ? error.message : String(error);
      } else stage = 'preview';
    }
  }

  async function cancelImport() {
    if (!progress) return;
    try {
      await undo(progress.undo);
      showSnack('Import annulé : les contacts sont revenus comme avant');
    } catch (error) {
      showError(error);
    }
    dispatch('close');
  }

  function close() {
    if (stage === 'running') return; // the import goes on; closing would leave it half done
    dispatch('close');
  }

  $: percent = progress && progress.total ? Math.round((100 * progress.done) / progress.total) : 0;
</script>

<Modal title={stage === 'done' ? 'Import terminé' : 'Importer des contacts'} on:close={close}>
  {#if stage === 'pick' || stage === 'reading'}
    <p class="muted">Un fichier vCard (.vcf) exporté du téléphone, de Google, d'Apple, de Proton…</p>
    <label class="drop" class:busy={stage === 'reading'}>
      <Icon name="upload" size={32} />
      <span>{stage === 'reading' ? 'Lecture du fichier…' : 'Choisir un fichier .vcf'}</span>
      <input bind:this={input} type="file" accept=".vcf,.vcard,text/vcard,text/x-vcard" on:change={picked} disabled={stage === 'reading'} />
    </label>
    {#if failure}<p class="error" role="alert">Import impossible : {failure}</p>{/if}
  {:else if stage === 'preview' && preview}
    <p class="file muted">{preview.name}</p>
    <p class="headline">{plural(preview.cards, 'contact à importer', 'contacts à importer')}</p>
    {#each previewLines(preview) as line}<p class="line">{line}</p>{/each}
    <fieldset>
      <legend>Doublons</legend>
      {#each preview.policies as p}
        <label class="radio"><input type="radio" name="policy" value={p.key} bind:group={policy} /> {p.label}</label>
      {/each}
    </fieldset>
  {:else if stage === 'running'}
    <p>Import en cours… {progress ? `${progress.done} / ${progress.total}` : ''}</p>
    <div class="bar" role="progressbar" aria-valuemin="0" aria-valuemax="100" aria-valuenow={percent}><span style="width: {percent}%"></span></div>
    <p class="muted small">Gardez cette page ouverte jusqu'à la fin.</p>
  {:else if stage === 'done' && progress}
    {#if progress.report}
      {#each reportLines(progress.report) as line, i}<p class:headline={i === 0} class="line">{line}</p>{/each}
    {:else}
      <p class="error">Import interrompu après {plural(progress.done, 'contact', 'contacts')} : {failure}</p>
    {/if}
  {/if}

  <svelte:fragment slot="actions">
    {#if stage === 'preview'}
      <button class="btn" on:click={close}>Annuler</button>
      <button class="btn primary" disabled={!preview?.cards} on:click={run}>Importer</button>
    {:else if stage === 'done'}
      {#if progress?.done}<button class="btn" on:click={cancelImport}>Annuler l'import</button>{/if}
      <button class="btn primary" on:click={close}>OK</button>
    {:else if stage !== 'running'}
      <button class="btn" on:click={close}>Fermer</button>
    {/if}
  </svelte:fragment>
</Modal>

<style>
  p { margin: 4px 0; }
  .file { overflow-wrap: anywhere; font-size: 13px; }
  .headline { font-weight: 700; font-size: 16px; }
  .small { font-size: 13px; }
  .drop {
    display: flex; flex-direction: column; align-items: center; gap: 8px; padding: 26px 16px; margin-top: 12px; border-radius: 16px;
    border: 2px dashed var(--outline-variant); cursor: pointer; color: var(--primary); font-weight: 600; text-align: center;
  }
  .drop:hover, .drop:focus-within { border-color: var(--primary); background: var(--surface); }
  .drop.busy { cursor: progress; opacity: 0.7; }
  .drop input { position: absolute; width: 1px; height: 1px; opacity: 0; }
  fieldset { border: none; padding: 0; margin: 14px 0 0; }
  legend { font-weight: 700; color: var(--primary); font-size: 14px; margin-bottom: 4px; padding: 0; }
  .radio { display: flex; align-items: center; gap: 10px; padding: 5px 0; cursor: pointer; }
  .radio input { width: 18px; height: 18px; accent-color: var(--primary); }
  .bar { height: 8px; border-radius: 4px; background: var(--surface); overflow: hidden; margin: 10px 0; }
  .bar span { display: block; height: 100%; background: var(--primary); transition: width 0.2s; }
  .error { color: var(--error); font-weight: 600; }
</style>
