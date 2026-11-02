<script>
  // The notes list: search pill, label chips, pinned section, grid (masonry) or list, and the two new-note buttons.
  import { onDestroy, onMount } from 'svelte';
  import { notes as api } from '../../lib/api.js';
  import { onChange } from '../../lib/events.js';
  import { go } from '../../lib/router.js';
  import { prefs, setPref, showError } from '../../lib/ui.js';
  import Icon from '../../lib/components/Icon.svelte';
  import IconButton from '../../lib/components/IconButton.svelte';
  import NoteCard from './NoteCard.svelte';

  const CARD_MIN = 240;
  const GAP = 12;

  /** @type {import('../../lib/api.js').NoteSummary[]} */
  let notes = [];
  /** @type {string[]} */
  let labels = [];
  let loaded = false;
  let query = sessionStorage.getItem('memento.notes.q') || '';
  let label = sessionStorage.getItem('memento.notes.label') || '';
  let width = 800;
  /** @type {AbortController | null} */
  let pending = null;
  /** @type {any} */
  let debounce = null;

  /** @param {{ notes: import('../../lib/api.js').NoteSummary[], labels: string[] }} result */
  function apply(result) {
    notes = result.notes;
    labels = result.labels;
    if (label && !labels.includes(label)) {
      label = '';
      load();
    }
    loaded = true;
  }

  async function load() {
    pending?.abort();
    const controller = new AbortController();
    pending = controller;
    try {
      apply(await api.list(query.trim(), label, controller.signal, apply));
    } catch (error) {
      showError(error, 'Liste des notes');
    }
  }

  $: {
    sessionStorage.setItem('memento.notes.q', query);
    sessionStorage.setItem('memento.notes.label', label);
  }

  function searchChanged() {
    clearTimeout(debounce);
    debounce = setTimeout(load, 180);
  }

  /** @param {string} value */
  function pickLabel(value) {
    label = label === value ? '' : value;
    load();
  }

  const stop = onChange((areas) => areas.includes('notes') && load());
  onMount(load);
  onDestroy(() => {
    stop();
    pending?.abort();
    clearTimeout(debounce);
  });

  $: grid = $prefs.grid;
  $: pinned = notes.filter((n) => n.pinned);
  $: others = notes.filter((n) => !n.pinned);
  $: columns = grid ? Math.max(2, Math.floor((width + GAP) / (CARD_MIN + GAP))) : 1;
  $: filtered = !!(query.trim() || label);

  /** Cards go to the shortest column, by an estimate of their height (left to right, like the Windows masonry). */
  function masonry(/** @type {import('../../lib/api.js').NoteSummary[]} */ list, /** @type {number} */ count) {
    const cols = Array.from({ length: count }, () => /** @type {typeof list} */ ([]));
    const heights = new Array(count).fill(0);
    const perLine = Math.max(18, (width / count) / 8.2);
    for (const n of list) {
      const lines = n.preview.split('\n').reduce((sum, l) => sum + Math.max(1, Math.ceil(l.length / perLine)), 0);
      const h = 34 + (n.title ? 26 * Math.min(4, Math.ceil(n.title.length / perLine)) : 0) + 20 * Math.min(11, lines)
        + (n.labels.length ? 24 : 0) + (n.pdf || n.attachments.length || n.conflict ? 24 : 0);
      const i = heights.indexOf(Math.min(...heights));
      cols[i].push(n);
      heights[i] += h + GAP;
    }
    return cols;
  }

  function newNote(checklist = false) {
    go(['notes', 'new'], { ...(checklist ? { list: '1' } : {}), t: String(Date.now()) });
  }
</script>

<div class="page">
  <div class="top">
    <div class="pill">
      <Icon name="search" />
      <input type="search" placeholder="Rechercher dans vos notes" aria-label="Rechercher dans vos notes"
        bind:value={query} on:input={searchChanged} on:keydown={(e) => e.key === 'Escape' && ((query = ''), load())} />
      {#if query}<IconButton icon="close" label="Effacer" on:click={() => { query = ''; load(); }} />{/if}
      <IconButton icon={grid ? 'list' : 'grid'} label={grid ? 'Vue liste' : 'Vue grille'} on:click={() => setPref('grid', !grid)} />
    </div>
    {#if labels.length}
      <div class="labels" role="toolbar" aria-label="Libellés">
        {#each labels as l}
          <button class="chip" class:on={label === l} aria-pressed={label === l} on:click={() => pickLabel(l)}>
            <Icon name="label" size={16} />{l}
          </button>
        {/each}
      </div>
    {/if}
  </div>

  <div class="scroll" bind:clientWidth={width}>
    {#if loaded && !notes.length}
      <div class="empty">
        <Icon name="notes" size={72} />
        <h2>{filtered ? 'Aucun résultat' : 'Aucune note pour l\'instant'}</h2>
        {#if !filtered}<p class="muted">Appuyez sur « Note » pour commencer</p>{/if}
      </div>
    {/if}
    {#each [{ title: 'Épinglées', list: pinned }, { title: pinned.length ? 'Autres' : '', list: others }] as section}
      {#if section.list.length}
        {#if section.title}<h2 class="section-title">{section.title}</h2>{/if}
        <div class="grid" class:single={!grid} style="--cols: {columns}">
          {#each masonry(section.list, columns) as column}
            <div class="column">
              {#each column as note (note.path)}<NoteCard {note} />{/each}
            </div>
          {/each}
        </div>
      {/if}
    {/each}
  </div>

  <div class="fabs">
    <button class="fab small" title="Nouvelle liste" aria-label="Nouvelle liste" on:click={() => newNote(true)}><Icon name="checklist" /></button>
    <button class="fab" on:click={() => newNote(false)}><Icon name="add" /> Note</button>
  </div>
</div>

<style>
  .page { height: 100%; display: flex; flex-direction: column; position: relative; }
  .top { padding: 12px 16px 0; display: flex; flex-direction: column; gap: 8px; max-width: 1400px; width: 100%; margin: 0 auto; }
  .pill {
    display: flex; align-items: center; gap: 6px; height: 52px; border-radius: 26px; background: var(--surface-high);
    padding: 0 6px 0 16px; color: var(--on-surface-variant);
  }
  .pill input { flex: 1; min-width: 0; border: none; background: transparent; outline: none; font-size: 16px; color: var(--on-surface); }
  .pill input::-webkit-search-cancel-button { display: none; }
  .labels { display: flex; gap: 8px; overflow-x: auto; padding: 2px 0 4px; scrollbar-width: none; }
  .scroll { flex: 1; overflow-y: auto; padding: 4px 16px 140px; }
  .grid { display: grid; grid-template-columns: repeat(var(--cols), minmax(0, 1fr)); gap: 12px; max-width: 1400px; margin: 0 auto; }
  .grid.single { max-width: 780px; }
  .column { display: flex; flex-direction: column; gap: 12px; min-width: 0; }
  .section-title { max-width: 1400px; margin-left: auto; margin-right: auto; }
  .empty { display: flex; flex-direction: column; align-items: center; margin-top: 12vh; color: var(--outline); text-align: center; }
  .empty h2 { color: var(--on-surface); font-size: 18px; margin: 12px 0 4px; }
  .fabs { position: absolute; right: 24px; bottom: 24px; display: flex; flex-direction: column; align-items: center; gap: 12px; }
  .fab {
    display: flex; align-items: center; gap: 8px; border: none; cursor: pointer; border-radius: 16px; padding: 16px 22px;
    background: var(--primary-container); color: var(--on-primary-container); font-weight: 700; font-size: 15px; box-shadow: var(--shadow);
  }
  .fab:hover { filter: brightness(1.05); outline: 2px solid var(--primary); }
  .fab.small { padding: 12px; border-radius: 12px; background: var(--secondary-container); color: var(--on-secondary-container); }
  @media (max-width: 719px) {
    .top { padding: 8px 12px 0; }
    .scroll { padding: 4px 12px 160px; }
    .grid, .column { gap: 8px; }
    .fabs { right: 16px; bottom: 16px; }
  }
</style>
