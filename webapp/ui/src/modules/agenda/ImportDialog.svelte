<script>
  // ICS import, as the Windows dialog: choose the file (.ics or .zip) -> preview (nothing written yet) -> options ->
  // progress (the server writes in the background) -> report, with "Annuler l'import". A mail invitation of one
  // event gets the compact "Ajouter cet événement à …" form.
  import { createEventDispatcher, onDestroy } from 'svelte';
  import Modal from '../../lib/components/Modal.svelte';
  import Icon from '../../lib/components/Icon.svelte';
  import { confirm, showError } from '../../lib/ui.js';
  import { agenda } from './api.js';
  import { plural, shortDate } from './dates.js';

  /** @type {import('./api.js').Calendars} */
  export let settings;
  const dispatch = createEventDispatcher();
  const NEW = '__new__';
  const SKIP = '__skip__';

  /** @type {'pick' | 'reading' | 'preview' | 'running' | 'report'} */
  let step = 'pick';
  let compact = false;
  /** @type {{ token: string, calendars: any[], compact: boolean, problem: string | null } | null} */
  let preview = null;
  /** @type {{ target: string, name: string, color: string }[]} */
  let rows = [];
  let since = false;
  let sinceDate = new Date().toISOString().slice(0, 10);
  let duplicates = 'update';
  let done = 0;
  let total = 0;
  /** @type {any} */
  let report = null;
  let undone = '';
  let dragging = false;
  /** @type {any} */
  let poll = null;
  /** @type {HTMLInputElement} */
  let input;
  onDestroy(() => {
    clearTimeout(poll);
    if (preview && step === 'preview') agenda.discardImport(preview.token).catch(() => {});
  });

  /** @param {File | undefined} file */
  async function read(file) {
    if (!file) return;
    step = 'reading';
    try {
      preview = await agenda.upload(file);
    } catch (error) {
      showError(error);
      step = 'pick';
      return;
    }
    if (preview.problem && !(await confirm('Import', `${preview.problem}\n\nL'import peut devoir réécrire ce fichier (nouveaux agendas). Continuer ?`))) {
      close();
      return;
    }
    const used = new Set(settings.calendars.map((c) => c.color.toUpperCase()));
    const free = settings.palette.map(([c]) => c).filter((c) => !used.has(c.toUpperCase()));
    rows = preview.calendars.map((c, i) => ({ target: c.suggested_calendar ?? NEW, name: c.name, color: free[i % Math.max(1, free.length)] || settings.palette[0][0] }));
    compact = preview.compact;
    step = 'preview';
  }

  /** @param {any} c */
  function line(c) {
    const parts = [plural(c.events, 'événement', 'événements')];
    if (c.series) parts.push(plural(c.series, 'série', 'séries'));
    if (c.exceptions) parts.push(plural(c.exceptions, 'occurrence modifiée', 'occurrences modifiées'));
    if (c.first && c.last) parts.push(`du ${shortDate(c.first)} au ${shortDate(c.last)}`);
    return parts.join(' · ');
  }
  /** @param {any} c */
  function warning(c) {
    const parts = [];
    if (c.ignored) parts.push(plural(c.ignored, 'élément ignoré', 'éléments ignorés'));
    if (c.cancelled) parts.push(plural(c.cancelled, 'annulé', 'annulés'));
    if (c.unresolved_zones) parts.push(plural(c.unresolved_zones, "fuseau inconnu (celui de l'appareil est utilisé)", "fuseaux inconnus (celui de l'appareil est utilisé)"));
    if (c.error_count) parts.push(plural(c.error_count, 'événement illisible', 'événements illisibles'));
    return parts.join(' · ');
  }
  /** @param {any} c @param {string} target */
  function dupText(c, target) {
    if (target === NEW || target === SKIP) return '';
    const n = c.duplicates[target] || 0;
    return n ? plural(n, 'événement déjà présent dans cet agenda', 'événements déjà présents dans cet agenda') : 'Aucun événement déjà présent dans cet agenda';
  }

  async function start() {
    if (!preview) return;
    /** @type {Record<string, any>} */
    const targets = {};
    rows.forEach((r, i) => {
      targets[String(i)] = r.target === SKIP ? { skip: true } : r.target === NEW ? { calendar_id: null, name: r.name, color: r.color } : { calendar_id: r.target };
    });
    const options = compact ? { targets: { 0: { calendar_id: rows[0].target } } }
      : { targets, since: since ? sinceDate : null, duplicates };
    step = 'running';
    try {
      const status = await agenda.applyImport(preview.token, options);
      follow(status);
    } catch (error) {
      showError(error);
      step = 'preview';
    }
  }

  /** @param {{ state: string, done: number, total: number, report?: any, error?: string }} status */
  function follow(status) {
    done = status.done;
    total = status.total;
    if (status.state === 'running') {
      poll = setTimeout(async () => {
        try {
          follow(await agenda.importStatus(/** @type {any} */ (preview).token));
        } catch (error) {
          showError(error);
        }
      }, 400);
      return;
    }
    dispatch('changed');
    if (status.state === 'error') {
      showError(new Error(status.error));
      close();
      return;
    }
    report = status.report;
    if (compact) {
      dispatch('imported', report);
      close();
      return;
    }
    step = 'report';
  }

  async function undo() {
    try {
      undone = (await agenda.undoImport(report.import_id)).message;
      dispatch('changed');
    } catch (error) {
      showError(error);
    }
  }

  /** @param {any} c */
  function reportLine(c) {
    const parts = [plural(c.created, 'créé', 'créés')];
    if (c.updated) parts.push(plural(c.updated, 'mis à jour', 'mis à jour'));
    if (c.skipped_duplicates) parts.push(plural(c.skipped_duplicates, 'doublon ignoré', 'doublons ignorés'));
    if (c.before_cutoff) parts.push(plural(c.before_cutoff, 'avant la date choisie', 'avant la date choisie'));
    if (c.unresolved_zones) parts.push(plural(c.unresolved_zones, 'fuseau inconnu', 'fuseaux inconnus'));
    if (c.errors.length) parts.push(plural(c.errors.length, 'illisible', 'illisibles'));
    return parts.join(' · ');
  }

  function close() {
    dispatch('close');
  }
  /** @param {DragEvent} e */
  function drop(e) {
    dragging = false;
    read(e.dataTransfer?.files?.[0]);
  }
  $: compactCal = settings.calendars.find((c) => c.id === rows[0]?.target);
  $: canImport = rows.some((r) => r.target !== SKIP && (r.target !== NEW || r.name.trim()));
</script>

<Modal title={step === 'preview' && compact ? 'Ajouter un événement' : step === 'report' ? '' : 'Importer un agenda (.ics)'} wide on:close={close}>
  {#if step === 'pick' || step === 'reading'}
    <!-- svelte-ignore a11y-no-static-element-interactions -->
    <div class="drop" class:dragging on:dragover|preventDefault={() => (dragging = true)} on:dragleave={() => (dragging = false)} on:drop|preventDefault={drop}>
      <Icon name="upload" size={40} />
      {#if step === 'reading'}
        <p>Lecture du fichier…</p>
      {:else}
        <p>Déposez un fichier .ics (invitation, export d'agenda) ou un .zip d'export Google ici</p>
        <button class="btn primary" on:click={() => input.click()}>Choisir un fichier</button>
      {/if}
    </div>
    <input type="file" accept=".ics,.zip,text/calendar,application/zip" hidden bind:this={input} on:change={() => read(input.files?.[0])} />
  {:else if step === 'preview' && preview && compact}
    {@const item = preview.calendars[0].items[0]}
    <div class="card">
      <strong>{item.title}</strong>
      <span>{item.when}</span>
      {#if item.location}<span class="muted">{item.location}</span>{/if}
    </div>
    <label class="row">Ajouter cet événement à
      <select class="field auto" bind:value={rows[0].target}>
        {#each settings.calendars as c}<option value={c.id}>{c.name}</option>{/each}
      </select>
    </label>
    {#if preview.calendars[0].duplicates[rows[0].target]}<p class="muted">Déjà présent dans cet agenda : il sera mis à jour.</p>{/if}
  {:else if step === 'preview' && preview}
    <p class="muted intro">Rien n'est encore écrit. Choisissez où vont les événements du fichier.</p>
    {#each preview.calendars as c, i}
      <div class="card">
        <strong>{c.name}</strong>
        <span class="muted small">{c.source}</span>
        <span>{line(c)}</span>
        {#if c.items}
          <ul class="items">{#each c.items as it}<li><strong>{it.title}</strong> · {it.when}{it.location ? ` · ${it.location}` : ''}</li>{/each}</ul>
        {/if}
        {#if warning(c)}<span class="warn" title={c.errors.join('\n')}>{warning(c)}</span>{/if}
        <div class="row">
          <select class="field auto" bind:value={rows[i].target} aria-label="Agenda de destination">
            {#each settings.calendars as cal}<option value={cal.id}>{cal.name}</option>{/each}
            <option value={NEW}>Nouvel agenda</option>
            <option value={SKIP}>Ne pas importer</option>
          </select>
          {#if rows[i].target === NEW}
            <input class="field auto" placeholder="Nom du nouvel agenda" bind:value={rows[i].name} aria-label="Nom du nouvel agenda" />
            <select class="field auto swatch" style="background: {rows[i].color}; color: #fff" bind:value={rows[i].color} aria-label="Couleur">
              {#each settings.palette as [color, name]}<option value={color}>{name}</option>{/each}
            </select>
          {/if}
        </div>
        {#if dupText(c, rows[i].target)}<span class="muted small">{dupText(c, rows[i].target)}</span>{/if}
      </div>
    {/each}
    <div class="options">
      <label class="row"><input type="checkbox" bind:checked={since} /> Seulement depuis le
        <input class="field auto" type="date" bind:value={sinceDate} disabled={!since} aria-label="Date de début" /></label>
      <div class="row">Déjà présents (même identifiant)
        {#each [['update', 'Mettre à jour'], ['skip', 'Ignorer'], ['keep', 'Garder les deux']] as [value, label]}
          <label class="radio"><input type="radio" bind:group={duplicates} {value} /> {label}</label>
        {/each}
      </div>
    </div>
  {:else if step === 'running'}
    <p>Import en cours… {total ? `${done} / ${total}` : ''}</p>
    <progress max={total || 1} value={done}></progress>
  {:else if step === 'report' && report}
    {#if undone}
      <h2>Import annulé : {undone}</h2>
    {:else}
      <h2>Import terminé : {report.summary}</h2>
      {#each report.calendars as c}
        <p><strong>{c.name}</strong> : {reportLine(c)}</p>
        {#if c.errors.length}<details><summary class="muted">Détails</summary><pre>{c.errors.join('\n')}</pre></details>{/if}
      {/each}
    {/if}
  {/if}

  <svelte:fragment slot="actions">
    {#if step === 'preview' && compact}
      <button class="btn" on:click={() => (compact = false)} title="Nouvel agenda, doublons, date de début">Plus d'options…</button>
      <span class="grow"></span>
      <button class="btn" on:click={close}>Annuler</button>
      <button class="btn primary" on:click={start}>Ajouter à « {compactCal?.name ?? ''} »</button>
    {:else if step === 'preview'}
      <button class="btn" on:click={close}>Annuler</button>
      <button class="btn primary" disabled={!canImport} on:click={start}>Importer</button>
    {:else if step === 'report'}
      {#if !undone}<button class="btn delete" on:click={undo}>Annuler l'import</button>{/if}
      <span class="grow"></span>
      <button class="btn primary" on:click={close}>Fermer</button>
    {:else if step !== 'running'}
      <button class="btn" on:click={close}>Annuler</button>
    {/if}
  </svelte:fragment>
</Modal>

<style>
  .drop {
    border: 2px dashed var(--outline-variant); border-radius: 18px; padding: 28px 16px; display: flex; flex-direction: column;
    align-items: center; gap: 10px; text-align: center; color: var(--on-surface-variant);
  }
  .drop.dragging { border-color: var(--primary); background: var(--surface); }
  .drop p { margin: 0; max-width: 420px; }
  .intro { margin: 0 0 10px; }
  .card { background: var(--surface); border-radius: 14px; padding: 12px 14px; display: flex; flex-direction: column; gap: 4px; margin-bottom: 10px; overflow-wrap: anywhere; }
  .row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-top: 6px; }
  .field.auto { width: auto; padding: 7px 10px; max-width: 100%; }
  .swatch { min-width: 120px; }
  .small { font-size: 13px; }
  .warn { color: var(--error); font-size: 14px; }
  .items { margin: 4px 0; padding-left: 18px; }
  .options { display: flex; flex-direction: column; gap: 4px; margin-top: 6px; }
  .radio { display: inline-flex; gap: 4px; align-items: center; }
  input[type='checkbox'], input[type='radio'] { accent-color: var(--primary); width: 18px; height: 18px; }
  progress { width: 100%; accent-color: var(--primary); height: 10px; }
  h2 { font-size: 18px; margin: 0 0 10px; }
  pre { white-space: pre-wrap; font-size: 12px; max-height: 200px; overflow: auto; }
  .btn.delete { color: var(--error); }
  .grow { flex: 1; }
</style>
