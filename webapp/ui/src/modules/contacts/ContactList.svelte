<script>
  // The list: search pill, count, Favoris then one section per letter with a letter jump, and the module's tools
  // (Importer, Exporter, Doublons). Sorting, sections and search are the server's (core contacts.py).
  import { onDestroy, onMount, tick } from 'svelte';
  import { onChange } from '../../lib/events.js';
  import { go, href } from '../../lib/router.js';
  import { prefs, showError } from '../../lib/ui.js';
  import Icon from '../../lib/components/Icon.svelte';
  import IconButton from '../../lib/components/IconButton.svelte';
  import ImportDialog from './ImportDialog.svelte';
  import DuplicatesDialog from './DuplicatesDialog.svelte';
  import { FAVORITES, api, countLabel, initials, plural } from './contacts.js';

  /** @type {string | null} the contact shown beside the list */
  export let current = null;

  /** @type {import('./contacts.js').Section[]} */
  let sections = [];
  let count = 0;
  /** @type {number | null} */
  let results = null;
  let loaded = false;
  let query = sessionStorage.getItem('memento.contacts.q') || '';
  /** @type {AbortController | null} */
  let pending = null;
  /** @type {any} */
  let debounce = null;
  /** @type {HTMLElement} */
  let scroller;
  /** @type {'import' | 'duplicates' | null} */
  let dialog = null;

  $: firstNameFirst = !!$prefs['contacts.firstNameFirst'];
  let order = !!$prefs['contacts.firstNameFirst'];
  $: if (firstNameFirst !== order) {
    order = firstNameFirst;
    load();
  }
  $: sessionStorage.setItem('memento.contacts.q', query);

  async function load() {
    pending?.abort();
    const controller = new AbortController();
    pending = controller;
    try {
      const apply = (/** @type {any} */ data) => ({ sections, count, results } = data);
      apply(await api.list(query.trim(), firstNameFirst, controller.signal, apply));
      loaded = true;
    } catch (error) {
      showError(error, 'Liste des contacts');
    }
  }

  function searchChanged() {
    clearTimeout(debounce);
    debounce = setTimeout(async () => {
      await load();
      scroller?.scrollTo({ top: 0 });
    }, 180);
  }

  function clear() {
    query = '';
    load();
  }

  /** @param {number} i */
  async function jump(i) {
    await tick();
    scroller.querySelector(`#contacts-section-${i}`)?.scrollIntoView({ block: 'start' });
  }

  const stop = onChange((areas) => areas.includes('contacts') && load());
  onMount(load);
  onDestroy(() => {
    stop();
    pending?.abort();
    clearTimeout(debounce);
  });

  $: searching = results !== null;
</script>

<div class="list">
  <div class="top">
    <div class="pill">
      <Icon name="search" />
      <input type="search" placeholder="Rechercher un contact" title="Nom, numéro, e-mail ou notes" aria-label="Rechercher un contact (nom, numéro, e-mail, notes)"
        bind:value={query} on:input={searchChanged} on:keydown={(e) => e.key === 'Escape' && clear()} />
      {#if query}<IconButton icon="close" label="Effacer" on:click={clear} />{/if}
    </div>
    <div class="bar">
      <span class="count muted" aria-live="polite">{searching ? plural(results ?? 0, 'résultat', 'résultats') : countLabel(count)}</span>
      <IconButton icon="upload" label="Importer des contacts (.vcf)" on:click={() => (dialog = 'import')} />
      <a class="icon-link" href={api.exportAllUrl(firstNameFirst)} download="contacts.vcf" title="Exporter tous les contacts (.vcf)"
        aria-label="Exporter tous les contacts (.vcf)"><Icon name="download" /></a>
      <IconButton icon="people" label="Doublons" on:click={() => (dialog = 'duplicates')} />
    </div>
  </div>

  <div class="body">
    <div class="scroll" bind:this={scroller}>
      {#if loaded && !sections.length}
        <div class="empty">
          <Icon name="person" size={64} />
          {#if searching}
            <p>Aucun résultat</p>
          {:else}
            <p>Aucun contact.</p>
            <p class="muted">Créez-en un, ou importez un fichier .vcf.</p>
            <button class="btn tonal" on:click={() => (dialog = 'import')}>Importer…</button>
          {/if}
        </div>
      {/if}
      {#each sections as section, i (section.title + i)}
        {#if section.title}
          <h2 class="header" id="contacts-section-{i}" class:fav={section.title === FAVORITES}>{section.title}</h2>
        {/if}
        <ul>
          {#each section.contacts as c (c.ref)}
            <li>
              <a class="row" class:selected={c.ref === current} href={href(['contacts', c.ref])} aria-current={c.ref === current ? 'page' : undefined}>
                <span class="avatar" aria-hidden="true">{initials(c.name)}</span>
                <span class="text">
                  <span class="name">{c.name}</span>
                  {#if c.conflict}<span class="tag">copie en conflit</span>{:else if c.second}<span class="second muted">{c.second}</span>{/if}
                </span>
                {#if c.favorite && section.title !== FAVORITES}<span class="star" title="Favori"><Icon name="star" size={18} /></span>{/if}
              </a>
            </li>
          {/each}
        </ul>
      {/each}
    </div>
    {#if !searching && sections.length > 3}
      <nav class="letters" aria-label="Aller à la lettre">
        {#each sections as section, i}
          <button on:click={() => jump(i)} title={section.title} aria-label={section.title}>
            {#if section.title === FAVORITES}<Icon name="star" size={14} />{:else}{section.title}{/if}
          </button>
        {/each}
      </nav>
    {/if}
  </div>

  <button class="fab" on:click={() => go(['contacts', 'new'])}><Icon name="personAdd" /> Contact</button>
</div>

{#if dialog === 'import'}
  <ImportDialog on:close={() => { dialog = null; load(); }} />
{:else if dialog === 'duplicates'}
  <DuplicatesDialog {firstNameFirst} on:close={() => { dialog = null; load(); }} />
{/if}

<style>
  .list { height: 100%; display: flex; flex-direction: column; position: relative; min-height: 0; }
  .top { padding: 12px 12px 0; display: flex; flex-direction: column; gap: 4px; }
  .pill {
    display: flex; align-items: center; gap: 6px; height: 48px; border-radius: 24px; background: var(--surface-high);
    padding: 0 6px 0 14px; color: var(--on-surface-variant);
  }
  .pill input { flex: 1; min-width: 0; border: none; background: transparent; outline: none; font-size: 16px; color: var(--on-surface); }
  .pill input::-webkit-search-cancel-button { display: none; }
  .bar { display: flex; align-items: center; gap: 2px; padding: 0 2px 0 8px; color: var(--on-surface-variant); }
  .count { flex: 1; font-size: 14px; }
  .icon-link {
    width: 40px; height: 40px; border-radius: 50%; display: inline-flex; align-items: center; justify-content: center; color: inherit;
  }
  .icon-link:hover { background: rgba(128, 128, 128, 0.18); }
  .body { flex: 1; min-height: 0; display: flex; position: relative; }
  .scroll { flex: 1; overflow-y: auto; padding: 0 8px 120px; scroll-padding-top: 4px; }
  ul { list-style: none; margin: 0; padding: 0; }
  .header {
    font-size: 13px; font-weight: 700; color: var(--primary); margin: 10px 12px 2px; letter-spacing: 0.04em;
  }
  .row {
    display: flex; align-items: center; gap: 12px; padding: 7px 10px; border-radius: 14px; color: inherit; text-decoration: none;
  }
  .row:hover { background: var(--surface); }
  .row.selected { background: var(--secondary-container); color: var(--on-secondary-container); }
  .avatar {
    width: 38px; height: 38px; border-radius: 50%; flex: none; display: flex; align-items: center; justify-content: center;
    background: var(--primary-container); color: var(--on-primary-container); font-weight: 700; font-size: 14px;
  }
  .text { flex: 1; min-width: 0; display: flex; flex-direction: column; }
  .name, .second { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .name { font-weight: 500; }
  .second { font-size: 13px; }
  .selected .second { color: inherit; opacity: 0.8; }
  .tag { font-size: 12px; color: var(--error); }
  .star { color: var(--primary); }
  .letters {
    display: flex; flex-direction: column; justify-content: center; padding: 4px 2px 80px; overflow-y: auto; scrollbar-width: none;
  }
  .letters button {
    border: none; background: transparent; color: var(--primary); font-size: 11px; font-weight: 700; width: 24px;
    min-height: 18px; padding: 0; cursor: pointer; border-radius: 6px; display: flex; align-items: center; justify-content: center;
  }
  .letters button:hover { background: var(--surface); }
  .empty { display: flex; flex-direction: column; align-items: center; margin-top: 10vh; color: var(--outline); text-align: center; gap: 4px; }
  .empty p { margin: 0; color: var(--on-surface); }
  .empty p.muted { color: var(--on-surface-variant); margin-bottom: 10px; }
  .fab {
    position: absolute; right: 20px; bottom: 20px; display: flex; align-items: center; gap: 8px; border: none; cursor: pointer;
    border-radius: 16px; padding: 14px 20px; background: var(--primary-container); color: var(--on-primary-container);
    font-weight: 700; font-size: 15px; box-shadow: var(--shadow);
  }
  .fab:hover { filter: brightness(1.05); outline: 2px solid var(--primary); }
</style>
