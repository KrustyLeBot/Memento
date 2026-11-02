<script>
  // One activity: summary figures, ski descents and climbs, charts (altitude, pace or speed, heart rate; hovering
  // one moves the cursor on the map), pauses and stops, photos and files, edit, delete with undo, and the altitude
  // looked up in the terrain model when the file has none. The map itself belongs to the page (one frame for the
  // module): this component sends it the analysis (`analysis` event) and the hovered point (`cursor`).
  import { createEventDispatcher, onDestroy } from 'svelte';
  import { ApiError, fileUrl, notes as notesApi, undo } from '../../lib/api.js';
  import { onChange } from '../../lib/events.js';
  import { go } from '../../lib/router.js';
  import { choose, confirm, showError, showSnack } from '../../lib/ui.js';
  import Icon from '../../lib/components/Icon.svelte';
  import IconButton from '../../lib/components/IconButton.svelte';
  import Chart from './Chart.svelte';
  import EditDialog from './EditDialog.svelte';
  import Glyph from './Glyph.svelte';
  import JobBanner from './JobBanner.svelte';
  import { activities as api } from './api.js';
  import { follow, job } from './jobs.js';
  import {
    EMOJI, SPORT_LABELS, fmtDate, fmtDistance, fmtDuration, fmtHour, fmtPaceSeconds, fmtSpeed, fmtSpeedFor, hasGps,
  } from './activities.js';

  /** note id, or file name for a conflict copy */
  export let id;
  const dispatch = createEventDispatcher();

  /** @type {import('./api.js').Activity | null} */
  let activity = null;
  let version = '';
  /** @type {any} */
  let analysis = null;
  let analysisError = '';
  let analysing = false;
  let loadedKey = /** @type {string | null | undefined} */ (undefined);
  let editing = false;
  let saving = false;
  /** @type {HTMLInputElement} */
  let photoPicker;
  /** @type {AbortController | null} */
  let pending = null;
  /** false once deleted here or left: a late answer (the change event of our own delete) is ignored */
  let alive = true;

  async function load() {
    try {
      const body = await api.get(id);
      if (!alive) return;
      activity = body.activity;
      version = body.version;
    } catch (error) {
      if (!alive) return;
      if (error instanceof ApiError && error.status === 404) {
        showSnack(error.message, { error: true });
        go(['activities'], undefined, true);
        return;
      }
      showError(error);
      return;
    }
    if (activity.analysis_key !== loadedKey) {
      dispatch('track', !!activity.analysis_key);
      loadAnalysis(activity.analysis_key);
    }
  }

  /** @param {string | null} key */
  async function loadAnalysis(key) {
    loadedKey = key;
    pending?.abort();
    analysisError = '';
    if (!key) {
      analysis = null;
      dispatch('analysis', null);
      return;
    }
    const controller = new AbortController();
    pending = controller;
    analysing = true;
    try {
      analysis = await api.analysis(id, controller.signal);
      dispatch('analysis', analysis);
    } catch (error) {
      if (error instanceof DOMException && error.name === 'AbortError') return;
      analysis = null;
      analysisError = error instanceof Error ? error.message : String(error);
      dispatch('analysis', null);
    } finally {
      if (pending === controller) analysing = false;
    }
  }

  load();
  const stop = onChange((areas) => areas.includes('activities') && load());
  onDestroy(() => {
    alive = false;
    stop();
    pending?.abort();
    dispatch('cursor', null);
  });

  function back() {
    if (history.length > 1) history.back();
    else go(['activities']);
  }

  /** @param {Record<string, any>} changes */
  async function save(changes) {
    if (!activity) return;
    if (!Object.keys(changes).length) {
      editing = false;
      return;
    }
    saving = true;
    try {
      const body = await api.update(id, version, changes);
      activity = body.activity;
      version = body.version;
      editing = false;
      if (activity.analysis_key !== loadedKey) loadAnalysis(activity.analysis_key);
    } catch (error) {
      if (error instanceof ApiError && error.status === 409) {
        showSnack('Cette activité venait de changer ailleurs : rechargée, réessayez.', { error: true });
        editing = false;
        load();
      } else showError(error, 'Modification impossible');
    } finally {
      saving = false;
    }
  }

  async function remove() {
    if (!activity) return;
    const ok = await confirm('Supprimer cette activité ?', 'Le fichier du tracé et les photos sont supprimés aussi.',
      { confirm: 'Supprimer', danger: true });
    if (!ok) return;
    const target = id;
    try {
      const { undo: token } = await api.remove(id, version);
      alive = false;
      stop();  // the change event of this very delete must not reload it
      go(['activities'], undefined, true);
      showSnack('Activité supprimée', { action: { label: 'Annuler', run: async () => {
        try {
          await undo(token);
          go(['activities', target]);
        } catch (error) {
          showError(error, 'Annulation impossible');
        }
      } } });
    } catch (error) {
      if (error instanceof ApiError && error.status === 409) {
        showSnack('Cette activité venait de changer ailleurs : rechargée.', { error: true });
        load();
      } else showError(error, 'Suppression impossible');
    }
  }

  /** @param {Event} e */
  async function addPhotos(e) {
    const files = [.../** @type {HTMLInputElement} */ (e.currentTarget).files || []];
    photoPicker.value = '';
    for (const file of files) {
      if (!activity) return;
      try {
        const saved = await notesApi.attach(activity.path, version, file);
        version = saved.version;
      } catch (error) {
        showError(error, `${file.name}`);
        break;
      }
    }
    load();
  }

  /** @param {string} photo */
  async function removePhoto(photo) {
    if (!activity || !(await confirm('Retirer cette photo ?', 'Le fichier est supprimé du dossier.', { confirm: 'Retirer', danger: true }))) return;
    try {
      await notesApi.detach(activity.path, version, photo);
    } catch (error) {
      showError(error);
    }
    load();
  }

  async function completeAltitude() {
    if (!activity) return;
    const answer = await choose('Compléter l\'altitude',
      'Les positions de cette activité (environ une par 30 m) sont envoyées à opentopodata.org, qui renvoie l\'altitude '
      + 'du relief. Le résultat est gardé dans un petit fichier .alt à côté du tracé.',
      [{ key: 'cancel', label: 'Annuler', kind: 'plain' }, { key: 'ok', label: 'Compléter', kind: 'primary' }]);
    if (answer !== 'ok') return;
    try {
      follow(await api.completeAltitude([activity.id]), (done) => {
        if (done.error) showSnack(`Altitude indisponible : ${done.error}`, { error: true });
        else showSnack('Altitude complétée');
        load();
      });
    } catch (error) {
      showError(error);
    }
  }

  /** @param {CustomEvent<number | null>} e @param {any} chart */
  function hovered(e, chart) {
    const i = e.detail;
    dispatch('cursor', i == null ? null : { lon: chart.lon[i], lat: chart.lat[i] });
  }

  /** @param {{ lon: number, lat: number } | null} at */
  const point = (at) => dispatch('cursor', at);

  /** @param {number[]} values */
  const minOf = (values) => values.reduce((a, b) => (b != null && b < a ? b : a), Infinity);
  /** @param {number[]} values */
  const maxOf = (values) => values.reduce((a, b) => (b != null && b > a ? b : a), -Infinity);
  const attachmentName = (/** @type {string} */ path) => (path.split('/').pop() || path).replace(/-[a-z0-9]{6}(\.[^.]+)$/, '$1');

  $: ski = activity?.sport === 'ski';
  $: walk = activity?.sport === 'walk';
  $: stats = analysis?.stats;
  $: quality = analysis?.quality;
  $: chart = analysis?.chart;
  $: items = activity ? summaryItems(activity, analysis) : [];

  /** @param {import('./api.js').Activity} a @param {any} an @returns {[string, string][]} */
  function summaryItems(a, an) {
    /** @type {[string, string][]} */
    const out = [];
    const gps = hasGps(a.sport);
    if (a.distance > 0 || gps) out.push(['Distance', fmtDistance(a.distance)]);
    out.push(['Durée', fmtDuration(a.elapsed)]);
    if (gps) {
      out.push(['En mouvement', fmtDuration(a.moving)]);
      if (a.moving > 0 && a.distance > 0) out.push([a.sport === 'walk' ? 'Allure moyenne' : 'Vitesse moyenne', fmtSpeedFor(a.sport, a.distance / a.moving)]);
      if (a.vmax && a.sport !== 'ski') out.push([a.sport === 'walk' ? 'Allure max' : 'Vitesse max', fmtSpeedFor(a.sport, a.vmax)]);
      if ((!an || an.quality.has_altitude) && a.sport !== 'ski') {
        out.push(['Dénivelé +', `${a.ascent} m`]);
        out.push(['Dénivelé −', `${a.descent} m`]);
      }
    } else if (a.distance > 0 && a.elapsed > 0) out.push(['Vitesse moyenne', fmtSpeed(a.distance / a.elapsed)]);
    if (a.hr != null) out.push(['FC moyenne', `${a.hr} bpm`]);
    if (an?.stats.hr_max != null) out.push(['FC max', `${an.stats.hr_max} bpm`]);
    return out;
  }

  $: altitudeSource = quality ? (quality.has_altitude
    ? (activity?.alt ? (activity.alt === 'baro+relief' ? 'baromètre corrigé avec le relief' : 'calculée d\'après le relief') : 'oui')
    : 'absente du fichier') : '';
</script>

<div class="detail">
  <header class="detail-head">
    <IconButton icon="back" label="Retour" on:click={back} />
    <h1 title={activity?.title}>{activity ? activity.title || 'Sans titre' : ''}</h1>
    <span class="grow"></span>
    {#if activity}
      <button class="btn tonal icon-label" on:click={() => (editing = true)}><Glyph name="edit" size={18} /><span class="wide-only">Modifier</span></button>
      <IconButton icon="delete" label="Supprimer" on:click={remove} />
    {/if}
  </header>

  <div class="detail-body">
    <JobBanner />
    {#if activity}
      <p class="sub">{EMOJI[activity.sport] || '📍'} {SPORT_LABELS[activity.sport] || activity.sport} · {fmtDate(activity.start)}
        {#if activity.conflict}<span class="badge">copie de conflit</span>{/if}</p>
      {#if activity.description.trim()}<p class="description">{activity.description}</p>{/if}

      {#if analysisError}
        <section class="card"><h2>Fichier</h2><p class="error">{analysisError}</p></section>
      {/if}

      <section class="card">
        <h2>Résumé</h2>
        <div class="grid">
          {#each items as [label, value]}<div class="stat"><span class="label">{label}</span><span class="value">{value}</span></div>{/each}
        </div>
        {#if analysing}<p class="muted small">Lecture du tracé…</p>{/if}
      </section>

      {#if stats && ski}
        <section class="card">
          <h2>Ski</h2>
          {#if stats.runs === 0}
            <p class="muted">Aucune descente détectée.</p>
          {:else}
            <div class="grid">
              <div class="stat"><span class="label">Descentes</span><span class="value">{stats.runs}</span></div>
              <div class="stat"><span class="label">Dénivelé descendu</span><span class="value">{quality.has_altitude ? `${Math.round(stats.run_drop)} m` : '–'}</span></div>
              <div class="stat"><span class="label">Vitesse max</span><span class="value">{fmtSpeed(stats.run_max_speed)}</span></div>
              <div class="stat"><span class="label">Vitesse moyenne</span><span class="value">{fmtSpeed(stats.run_avg_speed)}</span></div>
              <div class="stat"><span class="label">Plus longue</span><span class="value">{fmtDistance(stats.run_longest)}</span></div>
              <div class="stat"><span class="label">Montées</span><span class="value">{quality.has_altitude ? `${stats.climbs} · +${Math.round(stats.climb_gain)} m` : '–'}</span></div>
            </div>
            <p class="muted small">Sur la carte : descentes du violet au rouge selon la vitesse, montées en bleu.</p>
            <ol class="list">
              {#each analysis.runs as r, n}
                <li on:mouseenter={() => point(r)} on:mouseleave={() => point(null)}>
                  <span>Descente {n + 1} <span class="muted">· {fmtHour(r.start)}</span></span>
                  <span class="muted">{quality.has_altitude ? `${r.drop} m · ` : ''}{fmtDistance(r.distance)} · max {fmtSpeed(r.max_speed)}</span>
                </li>
              {/each}
            </ol>
            {#if analysis.climbs.length}
              <h3>Remontées</h3>
              <ol class="list">
                {#each analysis.climbs as c, n}
                  <li on:mouseenter={() => point(c)} on:mouseleave={() => point(null)}>
                    <span>Montée {n + 1} <span class="muted">· {fmtHour(c.start)}</span></span>
                    <span class="muted">+{c.gain} m · {fmtDuration(c.duration)}</span>
                  </li>
                {/each}
              </ol>
            {/if}
          {/if}
        </section>
      {/if}

      {#if chart}
        {#if chart.alt}
          <section class="card">
            <Chart title="Altitude" xs={chart.dist} ys={chart.alt} color="var(--primary)"
              low="min {Math.round(stats.min_alt ?? minOf(chart.alt))} m" high="max {Math.round(stats.max_alt ?? maxOf(chart.alt))} m"
              format={(y) => `${Math.round(y)} m`} on:hover={(e) => hovered(e, chart)} />
          </section>
        {/if}
        {#if walk && chart.pace}
          <section class="card">
            <!-- pace drawn upside down (minus seconds per km): faster is higher, as a speed curve; capped at 20 min/km -->
            <Chart title="Allure" xs={chart.dist} ys={chart.pace.map((/** @type {number} */ p) => -p)} color="#2E7DF6"
              low="plus lente {fmtPaceSeconds(maxOf(chart.pace))}" high="plus rapide {fmtPaceSeconds(minOf(chart.pace))}"
              format={(y) => fmtPaceSeconds(-y)} on:hover={(e) => hovered(e, chart)} />
          </section>
        {:else if chart.speed}
          <section class="card">
            <Chart title="Vitesse" xs={chart.dist} ys={chart.speed.map((/** @type {number} */ v) => v * 3.6)} color="#2E7DF6"
              low="min 0 km/h" high="max {fmtSpeed(stats.max_speed)}" format={(y) => fmtSpeed(y / 3.6)}
              on:hover={(e) => hovered(e, chart)} />
          </section>
        {/if}
        {#if chart.hr}
          <section class="card">
            <Chart title="Fréquence cardiaque" xs={chart.dist} ys={chart.hr} color="#E5484D"
              low="min {minOf(chart.hr)} bpm" high="max {stats.hr_max ?? maxOf(chart.hr)} bpm"
              format={(y) => `${Math.round(y)} bpm`} on:hover={(e) => hovered(e, chart)} />
          </section>
        {/if}
      {/if}

      {#if analysis?.stops.length}
        <section class="card">
          <h2>Pauses et arrêts</h2>
          <ul class="list">
            {#each analysis.stops as s}
              <li on:mouseenter={() => point(s)} on:mouseleave={() => point(null)}>
                <span>{s.manual ? 'Pause' : 'Arrêt détecté'}</span>
                <span class="muted">{fmtDuration(s.duration)} · {fmtHour(s.start)}</span>
              </li>
            {/each}
          </ul>
        </section>
      {/if}

      <section class="card">
        <h2>Photos</h2>
        {#if activity.photos.length}
          <div class="photos">
            {#each activity.photos as p (p)}
              <div class="photo">
                <a href={fileUrl(p)} target="_blank" rel="noopener"><img src={fileUrl(p)} alt={attachmentName(p)} loading="lazy" /></a>
                <button class="remove" title="Retirer" aria-label="Retirer {attachmentName(p)}" on:click={() => removePhoto(p)}><Icon name="close" size={18} /></button>
              </div>
            {/each}
          </div>
        {/if}
        {#if activity.files.length}
          <ul class="files">
            {#each activity.files as f}<li><Icon name="file" size={18} /><a href={fileUrl(f)} target="_blank" rel="noopener">{attachmentName(f)}</a></li>{/each}
          </ul>
        {/if}
        <button class="btn tonal icon-label" on:click={() => photoPicker.click()}><Glyph name="photo" size={18} /> Ajouter une photo…</button>
        <input bind:this={photoPicker} type="file" accept="image/*" multiple hidden on:change={addPhotos} />
      </section>

      {#if !activity.has_file}
        <button class="btn tonal icon-label wide" on:click={() => (editing = true)}>
          <Glyph name="edit" size={18} /> {activity.distance > 0 ? 'Modifier la distance…' : 'Ajouter une distance…'}
        </button>
      {/if}

      {#if analysis && !quality.has_altitude && hasGps(activity.sport) && !activity.alt}
        <button class="btn tonal icon-label wide" on:click={completeAltitude} disabled={!!$job}
          title="Envoie les positions à opentopodata.org et garde le résultat dans un petit fichier .alt">
          <Glyph name="terrain" size={18} /> Compléter l'altitude avec le relief (en ligne)
        </button>
      {/if}

      {#if quality}
        <section class="card quality">
          <h2>Qualité des données</h2>
          <p class="muted small">{quality.points} points{quality.dropped ? ` (${quality.dropped} écartés)` : ''}
            · un point toutes les {Math.round(quality.median_interval_sec)} s<br />
            Altitude : {altitudeSource} · Fréquence cardiaque : {quality.has_hr ? 'oui' : 'non'}</p>
        </section>
      {/if}
    {/if}
  </div>
</div>

{#if editing && activity}
  <EditDialog {activity} busy={saving} on:save={(e) => save(e.detail)} on:close={() => (editing = false)} />
{/if}

<style>
  .detail { display: flex; flex-direction: column; height: 100%; min-height: 0; }
  .detail-head { display: flex; align-items: center; gap: 6px; padding: 8px 12px 6px 6px; }
  h1 { margin: 0; font-size: 20px; font-weight: 700; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; min-width: 0; }
  .grow { flex: 1; }
  .icon-label { display: inline-flex; align-items: center; gap: 6px; }
  .detail-body { flex: 1; overflow-y: auto; padding: 0 16px 120px; display: flex; flex-direction: column; gap: 10px; }
  .sub { margin: 0; color: var(--on-surface-variant); }
  .description { margin: 0; white-space: pre-wrap; }
  .card { background: var(--surface); border-radius: 16px; padding: 12px 16px 14px; }
  .card h2 { margin: 0 0 8px; font-size: 15px; color: var(--primary); font-weight: 600; }
  .card h3 { margin: 12px 0 4px; font-size: 14px; font-weight: 600; }
  .grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(120px, 1fr)); gap: 10px 20px; }
  .stat { display: flex; flex-direction: column; }
  .stat .label { color: var(--on-surface-variant); font-size: 12px; }
  .stat .value { font-size: 18px; font-weight: 600; font-variant-numeric: tabular-nums; }
  .small { font-size: 13px; margin: 8px 0 0; }
  .list { list-style: none; margin: 6px 0 0; padding: 0; display: flex; flex-direction: column; }
  .list li { display: flex; justify-content: space-between; gap: 12px; padding: 6px 8px; margin: 0 -8px; border-radius: 8px; flex-wrap: wrap; }
  .list li:hover { background: var(--surface-high); }
  .photos { display: grid; grid-template-columns: repeat(auto-fill, minmax(110px, 1fr)); gap: 8px; margin-bottom: 10px; }
  .photo { position: relative; aspect-ratio: 1; border-radius: 12px; overflow: hidden; background: var(--surface-high); }
  .photo img { width: 100%; height: 100%; object-fit: cover; display: block; }
  .remove {
    position: absolute; top: 4px; right: 4px; width: 28px; height: 28px; border-radius: 50%; border: none; cursor: pointer;
    background: rgba(0, 0, 0, 0.6); color: #fff; display: flex; align-items: center; justify-content: center;
  }
  .files { list-style: none; padding: 0; margin: 0 0 10px; display: flex; flex-direction: column; gap: 4px; }
  .files li { display: flex; align-items: center; gap: 6px; }
  .wide { align-self: flex-start; }
  .error { color: var(--error); margin: 0; }
  .badge { font-size: 11px; font-weight: 700; padding: 1px 6px; border-radius: 6px; background: var(--error-container); color: var(--on-error-container); margin-left: 6px; }
  @media (max-width: 719px) { .wide-only { display: none; } }
</style>
