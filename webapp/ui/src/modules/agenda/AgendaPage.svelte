<script>
  // The Agenda module: #/agenda/<view>/<YYYY-MM-DD> with view = agenda | month | week | day. Toolbar (Aujourd'hui,
  // ◀ ▶, jump to a date, search, views, menu, "+ Événement"), the calendars and layers panel, the four views, and the
  // event detail / editor / import / export / calendars dialogs. Writes go through the server (409 when the file
  // changed meanwhile, undo token on deletes); live changes from the sync reload what is shown.
  import { onDestroy, onMount, tick } from 'svelte';
  import { ApiError, undo } from '../../lib/api.js';
  import { onChange } from '../../lib/events.js';
  import { go } from '../../lib/router.js';
  import { choose, prefs, setPref, showError, showSnack } from '../../lib/ui.js';
  import Icon from '../../lib/components/Icon.svelte';
  import IconButton from '../../lib/components/IconButton.svelte';
  import './icons.js';
  import { ZONE, agenda } from './api.js';
  import { calendars, loadCalendars } from './calendars.js';
  import { agendaPrefs } from './prefs.js';
  import {
    CHUNK, MONTHS_SHORT, addDays, addMonths, byDay, cap, hhmm, longDate, mediumDate, minutesStamp, monthTitle, parts,
    scheduleRows, searchRows, stampMinutes, today as todayIso, weekTitle, windowOf,
  } from './dates.js';
  import ListView from './ListView.svelte';
  import MonthView from './MonthView.svelte';
  import TimeGrid from './TimeGrid.svelte';
  import SidePanel from './SidePanel.svelte';
  import EventDetail from './EventDetail.svelte';
  import EventEditor from './EventEditor.svelte';
  import ImportDialog from './ImportDialog.svelte';
  import ExportDialog from './ExportDialog.svelte';
  import CalendarsDialog from './CalendarsDialog.svelte';
  import PastImports from './PastImports.svelte';

  /** @type {import('../../lib/router.js').Route} */
  export let route;

  const VIEWS = [['agenda', 'Agenda'], ['month', 'Mois'], ['week', 'Semaine'], ['day', 'Jour']];
  $: p = agendaPrefs($prefs);

  let view = '';
  let anchor = '';
  let today = todayIso();
  /** @type {import('./api.js').Entry[]} */
  let entries = [];
  /** @type {string[]} */
  let problems = [];
  let span = CHUNK;
  let loaded = false;
  let query = sessionStorage.getItem('memento.agenda.q') || '';
  /** @type {import('./api.js').Entry[] | null} */
  let results = null;
  /** @type {AbortController | null} */
  let pending = null;
  /** @type {any} */
  let searchTimer = null;
  /** @type {any} */
  let reloadTimer = null;
  let menuOpen = false;
  let panelOpen = false;
  let narrow = false;
  let width = 1200;
  /** @type {HTMLInputElement} */
  let jumpInput;
  /** @type {ListView} */
  let list;

  /** @type {{ entry: import('./api.js').Entry, event: import('./api.js').AgendaEvent, series: import('./api.js').AgendaEvent | null } | null} */
  let detail = null;
  /** @type {{ event: any, isNew: boolean, scope: 'one' | 'all' | null, target: any, occurrence?: string, entry?: any, series?: any } | null} */
  let editor = null;
  let editorBusy = false;
  let editorError = '';
  /** @type {'import' | 'export' | 'calendars' | 'past' | null} */
  let dialog = null;

  // ---- route ----

  /** @param {import('../../lib/router.js').Route} r */
  function applyRoute(r) {
    const v = VIEWS.some(([k]) => k === r.rest[0]) ? r.rest[0] : p.view;
    const d = /^\d{4}-\d{2}-\d{2}$/.test(r.rest[1] || '') ? r.rest[1] : todayIso();
    if (v !== view || d !== anchor) {
      if (v !== view || view === 'agenda') span = CHUNK;
      view = v;
      anchor = d;
      load();
    }
  }
  $: applyRoute(route);

  /** @param {string} v @param {string} d */
  function show(v, d) {
    if (v !== p.view) setPref('agenda.view', v);
    go(['agenda', v, d], undefined, true);
  }
  const setView = (/** @type {string} */ v) => show(v, anchor);
  function goToday() {
    today = todayIso();
    show(view, today);
    if (view === 'agenda') list?.scrollTop();
  }
  /** @param {number} direction */
  function step(direction) {
    const a = anchor;
    const next = view === 'month' ? addMonths(`${a.slice(0, 8)}01`, direction) : view === 'day' ? addDays(a, direction) : addDays(a, 7 * direction);
    show(view, next);
  }
  function jump() {
    jumpInput.value = anchor;
    try {
      jumpInput.showPicker();
    } catch {
      jumpInput.focus();
      jumpInput.click();
    }
  }

  // ---- loading ----

  const window_ = () => windowOf(view, anchor, span, p.weekStart);
  $: [first, last] = (void (view, anchor, span, p.weekStart), window_());

  async function load() {
    pending?.abort();
    const controller = new AbortController();
    pending = controller;
    today = todayIso();
    const [from, to] = window_();
    /** @param {{ entries: import('./api.js').Entry[], problems: string[] }} result */
    const apply = (result) => {
      entries = result.entries;
      problems = [...new Set(result.problems)];
      loaded = true;
    };
    try {
      const result = await agenda.entries(from, to, controller.signal, apply);
      if (controller.signal.aborted) return;
      apply(result);
      if (view === 'agenda' && entries.length < 25 && span < 400) {
        span = Math.min(400, span + CHUNK);
        load();
      }
    } catch (error) {
      showError(error, 'Agenda');
    }
  }

  async function search() {
    sessionStorage.setItem('memento.agenda.q', query);
    if (!query.trim()) {
      results = null;
      return;
    }
    try {
      results = (await agenda.search(query.trim())).entries;
    } catch (error) {
      showError(error);
    }
  }
  function searchChanged() {
    clearTimeout(searchTimer);
    searchTimer = setTimeout(search, 220);
  }

  function reload() {
    load();
    if (query.trim()) search();
    loadCalendars();
  }
  const stop = onChange(() => {
    clearTimeout(reloadTimer);
    reloadTimer = setTimeout(reload, 300);
  });
  // a change of the week start or of calendars.json (visibility, colours) redraws
  let shownKey = '';
  $: {
    const key = `${p.weekStart}|${$calendars?.version ?? ''}`;
    if (key !== shownKey) {
      const before = shownKey;
      shownKey = key;
      if (before && loaded && $calendars) load();
    }
  }

  onMount(() => {
    loadCalendars();
    if (query.trim()) search();
  });
  onDestroy(() => {
    stop();
    pending?.abort();
    clearTimeout(searchTimer);
    clearTimeout(reloadTimer);
  });

  // ---- opening ----

  /** @param {import('./api.js').Entry} e */
  async function open(e) {
    if (e.kind === 'note' && e.target_path) go(['notes', 'edit', e.target_path]);
    else if (e.kind === 'activity') go(['activities', e.target_id]);
    else if (e.kind === 'birthday') go(['contacts', e.target_id]);
    else if (e.target_path) {
      try {
        const got = await agenda.get(e.target_path);
        detail = { entry: e, event: got.event, series: got.series };
      } catch (error) {
        showError(error);
        reload();
      }
    }
  }

  /** @param {string} verb */
  async function askScope(verb) {
    const key = await choose(verb, 'Cet événement fait partie d\'une série.', [
      { key: 'cancel', label: 'Annuler', kind: 'plain' },
      { key: 'all', label: 'Tous les événements', kind: 'plain' },
      { key: 'one', label: 'Cet événement', kind: 'primary' },
    ]);
    return key === 'one' || key === 'all' ? key : null;
  }

  /** @param {string} action */
  async function detailAction(action) {
    if (!detail) return;
    const { entry, event, series } = detail;
    detail = null;
    if (action === 'edit') await edit(entry, event, series);
    else if (action === 'delete') await remove(entry, event, series);
    else if (action === 'note') go(['notes', 'edit', event.path]);
    else if (action === 'restore') await restore(event);
  }

  // ---- writing ----

  /** @param {string} [start] wall stamp; default: the next hour today, 9:00 on another day */
  function newEvent(start) {
    if (!start) {
      const day = view === 'agenda' ? todayIso() : anchor;
      const hour = new Date().getHours();
      start = `${day}T${hhmm((day === todayIso() && hour < 23 ? hour + 1 : 9) * 60)}`;
    }
    const cal = $calendars;
    editorError = '';
    editor = {
      isNew: true, scope: null, target: null,
      event: { title: '', body: '', start, end: minutesStamp(stampMinutes(start) + 60, false), tz: ZONE, end_tz: null,
        calendar: cal?.default, alerts: [...(cal?.default_alerts ?? [-15])], location: '', rrule: null, exdate: [], rich: true },
    };
  }

  /** @param {import('./api.js').Entry} entry @param {import('./api.js').AgendaEvent} event @param {import('./api.js').AgendaEvent | null} series */
  async function edit(entry, event, series) {
    editorError = '';
    if (event.rrule) {
      const scope = await askScope('Modifier');
      if (!scope) return;
      if (scope === 'one' && entry.occurrence) {
        const draft = { ...event, start: entry.occurrence.start, end: entry.occurrence.end, rrule: null, exdate: [] };
        editor = { event: draft, isNew: false, scope, target: event, occurrence: entry.occurrence.start, entry };
      } else editor = { event, isNew: false, scope: 'all', target: event, entry };
    } else editor = { event, isNew: false, scope: null, target: event, entry, series };
  }

  /** @param {Record<string, any>} fields */
  async function save(fields) {
    if (!editor) return;
    const ed = editor;
    editorBusy = true;
    editorError = '';
    try {
      if (ed.isNew) {
        await agenda.create(fields);
        showSnack('Événement créé');
      } else {
        await write(ed, fields, ed.target.version);
        showSnack('Événement enregistré');
      }
      editor = null;
      reload();
    } catch (error) {
      if (error instanceof ApiError && error.status === 400) editorError = error.message;
      else if (!(error instanceof ApiError && error.status === 409)) showError(error);
    } finally {
      editorBusy = false;
    }
  }

  /** The update, or after a 409 the user's choice: overwrite the other version, or keep it. @param {any} ed @param {Record<string, any>} fields @param {string} version */
  async function write(ed, fields, version) {
    try {
      await agenda.update(ed.target.path, version, fields, ed.scope || undefined, ed.occurrence);
    } catch (error) {
      if (!(error instanceof ApiError && error.status === 409)) throw error;
      const current = error.body?.current;
      if (!current) {
        showError(error);
        editor = null;
        reload();
        throw error;
      }
      const key = await choose('Modifié ailleurs', `« ${current.title || 'Sans titre'} » vient d'être modifié ailleurs (synchronisation, autre appareil). Remplacer cette version par la vôtre ?`, [
        { key: 'cancel', label: 'Garder l\'autre version', kind: 'plain' },
        { key: 'overwrite', label: 'Remplacer', kind: 'danger' },
      ]);
      if (key !== 'overwrite') {
        editor = null;
        reload();
        throw error;
      }
      await agenda.update(ed.target.path, current.version, fields, ed.scope || undefined, ed.occurrence);
    }
  }

  /** @param {import('./api.js').Entry} entry @param {import('./api.js').AgendaEvent} event @param {import('./api.js').AgendaEvent | null} series @param {'one' | 'all' | null} [given] */
  async function remove(entry, event, series, given = null) {
    let scope = given;
    const inSeries = !!event.rrule || (!!event.recurrence_of && !!series);
    if (inSeries && !scope) {
      scope = await askScope('Supprimer');
      if (!scope) return;
    }
    try {
      let result;
      if (scope === 'all' && event.recurrence_of && series) result = await agenda.remove(series.path, series.version, 'all');
      else if (event.rrule) result = await agenda.remove(event.path, event.version, scope || 'all', scope === 'one' ? entry.occurrence?.start : undefined);
      else result = await agenda.remove(event.path, event.version, inSeries ? 'one' : undefined);
      reload();
      showSnack(result.message, { action: { label: 'Annuler', run: async () => { await undo(result.undo); reload(); } } });
    } catch (error) {
      showError(error);
      reload();
    }
  }

  /** @param {import('./api.js').AgendaEvent} event */
  async function restore(event) {
    try {
      const result = await agenda.restore(event.path, event.version);
      reload();
      showSnack('Occurrence rétablie', { action: { label: 'Annuler', run: async () => { await undo(result.undo); reload(); } } });
    } catch (error) {
      showError(error);
    }
  }

  function editorDelete() {
    const ed = editor;
    if (!ed || ed.isNew) return;
    editor = null;
    remove(ed.entry, ed.target, ed.series ?? null, ed.scope);
  }

  /** @param {any} report */
  function imported(report) {
    showSnack(`Import : ${report.summary}`, {
      action: { label: 'Annuler', run: async () => { await agenda.undoImport(report.import_id); reload(); } },
    });
  }

  function showProblems() {
    choose('Problèmes de lecture', "Ces fichiers ou ces règles n'ont pas pu être lus entièrement. Rien n'a été modifié ; ce qui est lisible est affiché.\n\n"
      + problems.join('\n'), [{ key: 'ok', label: 'OK', kind: 'primary' }]);
  }

  /** @param {KeyboardEvent} e */
  function key(e) {
    if (detail || editor || dialog || e.ctrlKey || e.metaKey || e.altKey) return;
    const target = /** @type {HTMLElement} */ (e.target);
    if (target && (target.closest('input, textarea, select, [contenteditable="true"]') || document.querySelector('.scrim'))) return;
    if (e.key === 't' || e.key === 'T') goToday();
    else if (e.key === 'ArrowLeft' || e.key === 'PageUp') step(-1);
    else if (e.key === 'ArrowRight' || e.key === 'PageDown') step(1);
  }

  $: narrow = width < 900;
  $: panelShown = narrow ? panelOpen : p.panel;
  $: phone = width < 560;
  $: period = phone
    ? (view === 'week' ? weekTitle(first, last).replace(/ \d{4}$/, '') : view === 'day' ? cap(mediumDate(anchor)) : `${MONTHS_SHORT[parts(anchor)[1] - 1]} ${anchor.slice(0, 4)}`)
    : view === 'month' ? monthTitle(anchor) : view === 'week' ? weekTitle(first, last) : view === 'day' ? cap(longDate(anchor)) : monthTitle(anchor);
  let searchOpen = false;
  $: searchShown = !phone || searchOpen || !!query.trim();
  $: searching = !!query.trim() && results !== null;
  $: rows = searching ? searchRows(results || []) : view === 'agenda' ? scheduleRows(byDay(entries, first, last), today) : [];
  $: cals = $calendars?.calendars ?? [];
  $: days = view === 'week' ? Array.from({ length: 7 }, (_, i) => addDays(first, i)) : [anchor];
</script>

<svelte:window on:keydown={key} bind:innerWidth={width} />

<div class="page">
  <header class="toolbar">
    <div class="group">
      <IconButton icon="menu" label="Agendas et calques" on:click={() => (narrow ? (panelOpen = !panelOpen) : setPref('agenda.panel', !p.panel))} />
      <button class="btn tonal today" on:click={goToday} title="Aujourd'hui (T)">Aujourd'hui</button>
      <IconButton icon="chevronLeft" label="Précédent (←)" on:click={() => step(-1)} />
      <IconButton icon="chevronRight" label="Suivant (→)" on:click={() => step(1)} />
      <button class="period" on:click={jump} title="Aller à une date">{period}</button>
      <input class="jump" type="date" tabindex="-1" aria-hidden="true" bind:this={jumpInput}
        on:change={() => jumpInput.value && show(view === 'agenda' ? 'agenda' : view, jumpInput.value)} />
    </div>
    <div class="group grow-group">
      {#if searchShown}
        <label class="search">
          <Icon name="search" size={20} />
          <!-- svelte-ignore a11y-autofocus -->
          <input type="search" placeholder="Rechercher (titre, lieu, description)" aria-label="Rechercher dans l'agenda"
            bind:value={query} on:input={searchChanged} autofocus={phone} />
          {#if phone}
            <button class="close-search" aria-label="Fermer la recherche" title="Fermer la recherche"
              on:click={() => { query = ''; results = null; searchOpen = false; sessionStorage.setItem('memento.agenda.q', ''); }}>
              <Icon name="close" size={20} /></button>
          {/if}
        </label>
      {/if}
      {#if !phone || !searchShown}
        <div class="segmented" role="radiogroup" aria-label="Vue">
          {#each VIEWS as [k, label]}
            <button role="radio" aria-checked={view === k && !searching} class:on={view === k && !searching}
              on:click={() => { query = ''; results = null; sessionStorage.setItem('memento.agenda.q', ''); setView(k); }}>{label}</button>
          {/each}
        </div>
        {#if phone}<IconButton icon="search" label="Rechercher" on:click={() => (searchOpen = true)} />{/if}
      {/if}
      <div class="menu-wrap">
        <IconButton icon="more" label="Agendas, import, export" on:click={() => (menuOpen = !menuOpen)} />
        {#if menuOpen}
          <!-- svelte-ignore a11y-click-events-have-key-events a11y-no-static-element-interactions -->
          <div class="backdrop" on:click={() => (menuOpen = false)}></div>
          <div class="menu" role="menu">
            <button role="menuitem" on:click={() => { menuOpen = false; dialog = 'calendars'; }}><Icon name="calendar" size={20} /> Gérer les agendas…</button>
            <button role="menuitem" on:click={() => { menuOpen = false; dialog = 'import'; }}><Icon name="upload" size={20} /> Importer un agenda (.ics, .zip)…</button>
            <button role="menuitem" on:click={() => { menuOpen = false; dialog = 'past'; }}><Icon name="history" size={20} /> Imports passés…</button>
            <button role="menuitem" on:click={() => { menuOpen = false; dialog = 'export'; }}><Icon name="download" size={20} /> Exporter…</button>
          </div>
        {/if}
      </div>
      <button class="btn primary new" on:click={() => newEvent()} title="Nouvel événement sur le jour affiché">
        <span class="inline"><Icon name="add" size={20} /> Événement</span>
      </button>
    </div>
  </header>

  <div class="main">
    {#if panelShown}
      {#if narrow}
        <!-- svelte-ignore a11y-click-events-have-key-events a11y-no-static-element-interactions -->
        <div class="drawer-scrim" on:click={() => (panelOpen = false)}></div>
      {/if}
      <aside class="side" class:drawer={narrow}>
        <SidePanel {problems} on:manage={() => (dialog = 'calendars')} on:problems={showProblems} />
      </aside>
    {/if}
    <section class="view">
      {#if searching}
        <ListView rows={rows} eventZone={p.eventZone} {today} empty="Aucun résultat pour « {query.trim()} »" on:open={(e) => open(e.detail)} />
      {:else if view === 'agenda'}
        <ListView bind:this={list} {rows} eventZone={p.eventZone} {today} more={loaded && span < 400}
          on:more={() => { span = Math.min(400, span + CHUNK); load(); }} on:open={(e) => open(e.detail)} />
      {:else if view === 'month'}
        <MonthView {entries} {first} month={anchor} {today} eventZone={p.eventZone} on:open={(e) => open(e.detail)}
          on:day={(e) => show('day', e.detail)} />
      {:else}
        <TimeGrid {days} {entries} {today} eventZone={p.eventZone} on:open={(e) => open(e.detail)}
          on:day={(e) => show('day', e.detail)} on:slot={(e) => newEvent(`${e.detail.day}T${hhmm(e.detail.minutes)}`)} />
      {/if}
    </section>
  </div>
  <button class="fab" on:click={() => newEvent()} aria-label="Nouvel événement" title="Nouvel événement"><Icon name="add" size={28} /></button>
</div>

{#if detail}
  <EventDetail entry={detail.entry} event={detail.event} series={detail.series} calendars={cals} eventZone={p.eventZone}
    on:close={() => (detail = null)} on:action={(e) => detailAction(e.detail)} />
{/if}
{#if editor}
  <EventEditor event={editor.event} calendars={cals} isNew={editor.isNew} scope={editor.scope} busy={editorBusy} error={editorError}
    on:close={() => (editor = null)} on:save={(e) => save(e.detail)} on:delete={editorDelete} />
{/if}
{#if dialog === 'import' && $calendars}
  <ImportDialog settings={$calendars} on:close={() => (dialog = null)} on:changed={reload} on:imported={(e) => imported(e.detail)} />
{:else if dialog === 'export' && $calendars}
  <ExportDialog settings={$calendars} on:close={() => (dialog = null)} />
{:else if dialog === 'calendars'}
  <CalendarsDialog on:close={() => (dialog = null)} on:changed={reload} />
{:else if dialog === 'past'}
  <PastImports on:close={() => (dialog = null)} on:changed={reload} />
{/if}

<style>
  .page { height: 100%; display: flex; flex-direction: column; position: relative; }
  .toolbar { display: flex; align-items: center; gap: 8px 12px; padding: 8px 12px; flex-wrap: wrap; flex: none; }
  .group { display: flex; align-items: center; gap: 4px; min-width: 0; }
  .grow-group { flex: 1; justify-content: flex-end; gap: 8px; }
  .today { padding: 7px 16px; }
  .period {
    border: none; background: transparent; font-size: 20px; font-weight: 600; padding: 4px 10px; border-radius: 12px; cursor: pointer;
    white-space: nowrap; overflow: hidden; text-overflow: ellipsis; color: var(--on-surface);
  }
  .period:hover { background: var(--surface); }
  .jump { position: absolute; opacity: 0; width: 1px; height: 1px; pointer-events: none; left: 280px; top: 48px; }
  .search {
    display: flex; align-items: center; gap: 8px; background: var(--surface); border-radius: 24px; padding: 0 14px;
    flex: 0 1 300px; min-width: 120px; color: var(--on-surface-variant);
  }
  .search input::-webkit-search-cancel-button { display: none; }
  .close-search { border: none; background: transparent; color: inherit; cursor: pointer; display: flex; padding: 4px; border-radius: 50%; }
  .search input { border: none; background: transparent; outline: none; padding: 9px 0; flex: 1; min-width: 0; color: var(--on-surface); }
  .segmented { display: inline-flex; border: 1px solid var(--outline); border-radius: 20px; overflow: hidden; flex: none; }
  .segmented button { border: none; background: transparent; padding: 7px 14px; cursor: pointer; font-weight: 600; }
  .segmented button + button { border-left: 1px solid var(--outline); }
  .segmented button.on { background: var(--secondary-container); color: var(--on-secondary-container); }
  .menu-wrap { position: relative; }
  .backdrop { position: fixed; inset: 0; z-index: 40; }
  .menu {
    position: absolute; right: 0; top: 44px; z-index: 41; background: var(--surface-high); border-radius: 14px; box-shadow: var(--shadow);
    padding: 6px; display: flex; flex-direction: column; min-width: 280px;
  }
  .menu button {
    display: flex; align-items: center; gap: 12px; border: none; background: transparent; padding: 10px 12px; border-radius: 10px;
    cursor: pointer; text-align: left; white-space: nowrap;
  }
  .menu button:hover { background: var(--surface); }
  .menu :global(svg) { color: var(--on-surface-variant); }
  .inline { display: inline-flex; align-items: center; gap: 6px; }
  .new { padding: 8px 18px 8px 12px; }
  .main { flex: 1; min-height: 0; display: flex; position: relative; }
  .side { width: 240px; flex: none; overflow-y: auto; }
  .side.drawer {
    position: absolute; left: 0; top: 0; bottom: 0; z-index: 30; width: min(300px, 85vw); background: var(--background);
    box-shadow: var(--shadow); animation: slide 0.15s ease-out;
  }
  .drawer-scrim { position: absolute; inset: 0; z-index: 29; background: rgba(20, 10, 12, 0.3); }
  .view { flex: 1; min-width: 0; min-height: 0; }
  .fab { display: none; }
  @keyframes slide { from { transform: translateX(-20px); opacity: 0; } }
  @media (max-width: 899px) {
    .toolbar { padding: 6px 8px; gap: 6px; }
    .grow-group { flex-basis: 100%; justify-content: space-between; }
    .search { flex: 1 1 auto; }
    .segmented button { padding: 6px 10px; }
    .new { display: none; }
    .fab {
      display: flex; position: absolute; right: 16px; bottom: 16px; width: 56px; height: 56px; border-radius: 18px; border: none;
      background: var(--primary-container); color: var(--on-primary-container); align-items: center; justify-content: center;
      box-shadow: var(--shadow); cursor: pointer; z-index: 20;
    }
  }
  @media (max-width: 560px) {
    .period { font-size: 17px; padding: 4px 6px; }
    .today { padding: 6px 12px; font-size: 14px; }
    .group:first-child { width: 100%; }
    .grow-group { gap: 2px; }
    .segmented { flex: 1; }
    .segmented button { flex: 1; padding: 6px 2px; font-size: 14px; }
  }
</style>
