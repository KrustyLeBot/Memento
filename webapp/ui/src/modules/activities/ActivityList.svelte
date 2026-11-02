<script>
  // The list: week / month / total figures (sports of activities/settings.json's `totals`), a sport filter and one
  // row per activity, newest first. Import (FIT / GPX) and the altitude completion of every activity.
  import { createEventDispatcher, onDestroy, onMount, tick } from 'svelte';
  import { onChange } from '../../lib/events.js';
  import { href } from '../../lib/router.js';
  import { choose, showError, showSnack } from '../../lib/ui.js';
  import Icon from '../../lib/components/Icon.svelte';
  import Glyph from './Glyph.svelte';
  import JobBanner from './JobBanner.svelte';
  import { activities as api } from './api.js';
  import { follow, job } from './jobs.js';
  import { EMOJI, fmtDate, fmtDistance, fmtDuration, shortLabel, summaryLine, totals } from './activities.js';

  const dispatch = createEventDispatcher();
  /** @type {import('./activities.js').ActivitySummary[]} */
  let list = [];
  /** @type {string[]} */
  let included = [];
  let loaded = false;
  let sport = sessionStorage.getItem('memento.activities.sport') || '';
  /** @type {HTMLElement} */
  let scroller;
  /** @type {HTMLInputElement} */
  let picker;

  async function load() {
    try {
      const body = await api.list();
      list = body.activities;
      included = body.totals;
      const first = !loaded;
      loaded = true;
      if (first) {
        await tick();
        scroller.scrollTop = Number(sessionStorage.getItem('memento.activities.scroll') || 0);
        dispatch('loaded');
      }
    } catch (error) {
      showError(error, 'Liste des activités');
    }
  }

  const stop = onChange((areas) => areas.includes('activities') && load());
  onMount(load);
  onDestroy(() => {
    stop();
    if (scroller) sessionStorage.setItem('memento.activities.scroll', String(scroller.scrollTop));
  });

  $: sessionStorage.setItem('memento.activities.sport', sport);
  $: sports = [...new Set(list.map((a) => a.sport))];
  $: shown = sport ? list.filter((a) => a.sport === sport) : list;
  $: figures = totals(list, included);

  /** @param {Event} e */
  function picked(e) {
    const files = [.../** @type {HTMLInputElement} */ (e.currentTarget).files || []];
    picker.value = '';
    if (files.length) dispatch('import', files);
  }

  async function completeAll() {
    const answer = await choose('Compléter l\'altitude',
      `${list.length} activité(s). Pour celles dont le fichier n'a pas d'altitude, les positions (environ une par 30 m) `
      + 'sont envoyées à opentopodata.org, à raison d\'une requête par seconde. Celles qui ont déjà une altitude '
      + '(enregistrées avec le baromètre, ou déjà complétées) sont ignorées.',
      [{ key: 'cancel', label: 'Annuler', kind: 'plain' }, { key: 'ok', label: 'Compléter', kind: 'primary' }]);
    if (answer !== 'ok') return;
    try {
      follow(await api.completeAltitude(null), (done) => {
        showSnack(`Altitude : ${done.text}${done.error ? ` · arrêté : ${done.error}` : ''}`, { error: !!done.error });
        load();
      });
    } catch (error) {
      showError(error);
    }
  }
</script>

<div class="page">
  <header class="head">
    <h1>Activités</h1>
    <span class="grow"></span>
    <button class="btn tonal icon-label" on:click={completeAll} disabled={!list.length || !!$job} title="Compléter l'altitude avec le relief (en ligne)">
      <Glyph name="terrain" size={20} /><span class="wide-only">Compléter l'altitude</span>
    </button>
    <button class="btn primary icon-label" on:click={() => picker.click()} disabled={!!$job}>
      <Glyph name="upload" size={20} /><span>Importer<span class="wide-only">&nbsp;FIT / GPX</span></span>
    </button>
    <input bind:this={picker} type="file" accept=".fit,.gpx,application/gpx+xml" multiple hidden on:change={picked} />
  </header>

  <div class="scroll" bind:this={scroller}>
    <JobBanner />
    <div class="totals">
      {#each figures as f}
        <section class="card total">
          <h2>{f.name}</h2>
          <div class="muted">{f.count} activité{f.count > 1 ? 's' : ''}</div>
          <div class="big">{fmtDistance(f.distance)}</div>
          <div>{fmtDuration(f.moving)}{f.ascent ? ` · ↑ ${f.ascent} m` : ''}</div>
          {#if f.ski}<div class="muted">⛷️ {f.ski.runs} desc. · {f.ski.drop} m</div>{/if}
        </section>
      {/each}
    </div>

    {#if sports.length > 1}
      <div class="chips" role="toolbar" aria-label="Sport">
        <button class="chip" class:on={!sport} aria-pressed={!sport} on:click={() => (sport = '')}>Tous les sports</button>
        {#each sports as s}
          <button class="chip" class:on={sport === s} aria-pressed={sport === s} on:click={() => (sport = sport === s ? '' : s)}>
            {EMOJI[s] || '📍'} {shortLabel(s)}
          </button>
        {/each}
      </div>
    {/if}

    {#if loaded && !shown.length}
      <div class="empty">
        <Icon name="run" size={72} />
        <h2>Aucune activité</h2>
        <p class="muted">Importez un fichier FIT ou GPX (montre, Strava), ou enregistrez une sortie avec le téléphone.</p>
      </div>
    {/if}
    <div class="rows">
      {#each shown as a (a.path)}
        <a class="row" href={href(['activities', a.conflict ? a.path.split('/')[1] : a.id])}>
          <span class="emoji" aria-hidden="true">{EMOJI[a.sport] || '📍'}</span>
          <span class="text">
            <span class="title">{a.title || 'Sans titre'}{#if a.conflict} <span class="badge">conflit</span>{/if}</span>
            <span class="when">{fmtDate(a.start)}</span>
            <span class="line">{summaryLine(a)}</span>
          </span>
        </a>
      {/each}
    </div>
  </div>
</div>

<style>
  .page { height: 100%; display: flex; flex-direction: column; }
  .head { display: flex; align-items: center; gap: 8px; padding: 12px 16px 8px; max-width: 980px; width: 100%; margin: 0 auto; }
  h1 { margin: 0; font-size: 22px; font-weight: 700; }
  .grow { flex: 1; }
  .icon-label { display: inline-flex; align-items: center; gap: 6px; }
  .scroll { flex: 1; overflow-y: auto; padding: 0 16px 120px; }
  .scroll > :global(*) { max-width: 980px; margin-left: auto; margin-right: auto; }
  .totals { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; margin-bottom: 12px; }
  .card { background: var(--surface); border-radius: 16px; padding: 12px 16px 14px; }
  .total h2 { margin: 0 0 2px; font-size: 14px; color: var(--primary); font-weight: 600; }
  .big { font-size: 20px; font-weight: 700; margin: 2px 0; }
  .chips { display: flex; gap: 8px; overflow-x: auto; padding: 2px 0 10px; scrollbar-width: none; }
  .rows { display: flex; flex-direction: column; gap: 8px; }
  .row {
    display: flex; align-items: center; gap: 14px; padding: 10px 16px; border-radius: 14px; background: var(--surface);
    color: inherit; text-decoration: none;
  }
  .row:hover { background: var(--surface-high); outline: 1px solid var(--outline-variant); }
  .emoji { font-size: 28px; width: 36px; text-align: center; flex: none; }
  .text { display: flex; flex-direction: column; min-width: 0; }
  .title { font-weight: 600; font-size: 16px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .when { color: var(--on-surface-variant); font-size: 13px; }
  .line { font-size: 14px; }
  .badge { font-size: 11px; font-weight: 700; padding: 1px 6px; border-radius: 6px; background: var(--error-container); color: var(--on-error-container); }
  .empty { display: flex; flex-direction: column; align-items: center; margin-top: 8vh; color: var(--outline); text-align: center; }
  .empty h2 { color: var(--on-surface); font-size: 18px; margin: 12px 0 4px; }
  @media (max-width: 719px) {
    .head { padding: 8px 12px 6px; }
    .scroll { padding: 0 12px 120px; }
    .totals { gap: 6px; }
    .total { padding: 8px 10px 10px; border-radius: 14px; font-size: 12px; }
    .total h2 { font-size: 13px; }
    .big { font-size: 17px; }
    .wide-only { display: none; }
  }
</style>
