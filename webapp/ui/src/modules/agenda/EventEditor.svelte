<script>
  // Create or edit an event, as the Windows editor: title, all-day, start/end (the end follows the start), time zone
  // (shown only when it is not the browser's, or asked for), arrival zone, calendar, place, repetition (presets,
  // "Personnalisé…", read-only imported rule), reminders, description (the notes' rich text when it is plain text).
  // It only collects the fields: `save` gets them, the page writes them.
  import { createEventDispatcher, onDestroy, onMount } from 'svelte';
  import Modal from '../../lib/components/Modal.svelte';
  import IconButton from '../../lib/components/IconButton.svelte';
  import RichText from '../notes/RichText.svelte';
  import { showError } from '../../lib/ui.js';
  import { ZONE, agenda } from './api.js';
  import { city, minutesStamp, stampMinutes } from './dates.js';
  import ZonePicker from './ZonePicker.svelte';
  import AlertsField from './AlertsField.svelte';
  import RepeatDialog from './RepeatDialog.svelte';

  /** @type {Partial<import('./api.js').AgendaEvent> & { start: string }} the event as it is (or the new draft) */
  export let event;
  /** @type {import('./api.js').Calendar[]} */
  export let calendars = [];
  export let isNew = false;
  /** @type {'one' | 'all' | null} */
  export let scope = null;
  export let busy = false;
  /** @type {string} a server refusal to show (French) */
  export let error = '';
  const dispatch = createEventDispatcher();

  const repeatable = scope !== 'one' && !event.recurrence_of;
  const hint = scope === 'all' ? 'Série : les dates sont celles de la première occurrence.'
    : event.recurrence_of ? "Occurrence modifiée d'une série." : scope === 'one' ? 'Cette occurrence seulement.' : '';

  let title = event.title || '';
  let allDay = event.start.length <= 10;
  const initialEnd = event.end || (allDay ? event.start : minutesStamp(stampMinutes(event.start) + 60, false));
  let startDate = event.start.slice(0, 10);
  let startTime = allDay ? '09:00' : event.start.slice(11, 16);
  let endDate = initialEnd.slice(0, 10);
  let endTime = allDay ? '10:00' : initialEnd.slice(11, 16);
  /** @type {string | null} */
  let tz = allDay ? (event.tz ?? ZONE) : (event.tz ?? null);
  let endZoneOn = !!event.end_tz;
  /** @type {string | null} */
  let endTz = event.end_tz || null;
  let zoneShown = !allDay && tz !== ZONE;
  let calendar = event.calendar || calendars[0]?.id || '';
  let location = event.location || '';
  let alerts = [...(event.alerts || [])];
  let body = event.body || '';
  let bodyTouched = false;
  const rich = event.rich !== false;
  /** @type {string | null} */
  let rrule = repeatable ? event.rrule || null : null;
  /** @type {import('./api.js').RuleInfo | null} */
  let rule = null;
  let custom = false;
  let localError = '';
  let duration = Math.max(0, stampMinutes(stampOf(endDate, endTime)) - stampMinutes(stampOf(startDate, startTime)));
  /** @type {any} */
  let ruleTimer = null;
  /** @type {RichText} */
  let richText;

  /** @param {string} d @param {string} t */
  function stampOf(d, t) {
    return allDay ? d : `${d}T${t || '00:00'}`;
  }
  $: start = stampOf(startDate, startTime);
  $: end = stampOf(endDate, endTime);
  $: zone = allDay ? null : tz;

  function startChanged() {
    if (!startDate) return;
    const s = stampMinutes(stampOf(startDate, startTime || '00:00'));
    const e = minutesStamp(s + (allDay ? Math.floor(duration / 1440) * 1440 : duration), allDay);
    endDate = e.slice(0, 10);
    if (!allDay) endTime = e.slice(11, 16);
    localError = '';
    followRule();
  }
  function endChanged() {
    const d = stampMinutes(stampOf(endDate, endTime)) - stampMinutes(stampOf(startDate, startTime));
    if (d >= 0) duration = d;
    localError = '';
  }
  function allDayChanged() {
    duration = Math.max(0, stampMinutes(stampOf(endDate, endTime)) - stampMinutes(stampOf(startDate, startTime)));
    if (!allDay && tz === null && !event.tz) tz = ZONE;
    followRule();
  }

  /** @param {Record<string, any>} extra */
  async function loadRule(extra = {}) {
    if (!repeatable) return;
    try {
      rule = await agenda.rule({ start, tz: zone, rrule, exdate: event.exdate || [], ...extra });
      rrule = rule.rrule;
    } catch (e) {
      showError(e);
    }
  }
  /** The start moved: a preset follows it ("le jeudi" becomes "le vendredi"), a custom rule stays. */
  function followRule() {
    clearTimeout(ruleTimer);
    ruleTimer = setTimeout(() => {
      const key = rule?.preset;
      loadRule(key && !['none', 'custom', 'imported'].includes(key) ? { preset: key } : {});
    }, 150);
  }
  /** @param {Event} e */
  function repeatChosen(e) {
    const select = /** @type {HTMLSelectElement} */ (e.currentTarget);
    const key = select.value;
    if (key === 'edit') {
      custom = true;
      select.value = rule?.preset ?? 'none';
    } else if (key !== 'custom' && key !== 'imported') loadRule({ preset: key });
  }

  function endZoneToggled() {
    if (endZoneOn) {
      if (tz === null) tz = ZONE;
      if (endTz === null) endTz = tz;
    }
    localError = '';
  }

  function fields() {
    /** @type {Record<string, any>} */
    const out = {
      title: title.trim(), start, end: allDay && end === start ? null : end, tz: zone,
      end_tz: !allDay && endZoneOn && endTz && endTz !== zone ? endTz : null,
      calendar, location: location.trim() || null, alerts,
    };
    if (repeatable) out.rrule = rrule;
    if (rich && bodyTouched) out.body = body;
    return out;
  }

  function submit() {
    if (!startDate || !endDate || (!allDay && (!startTime || !endTime))) {
      localError = 'Date ou heure manquante.';
      return;
    }
    if (!(endZoneOn && endTz && endTz !== zone) && stampMinutes(end) < stampMinutes(start)) {
      localError = 'La fin est avant le début.';
      return;
    }
    dispatch('save', fields());
  }

  onMount(() => loadRule());
  onDestroy(() => clearTimeout(ruleTimer));
  $: shownError = localError || error;
  $: calColor = calendars.find((c) => c.id === calendar)?.color ?? 'transparent';
</script>

<!-- while the repetition dialog is open, Escape and outside clicks are its own -->
<Modal title={isNew ? 'Nouvel événement' : "Modifier l'événement"} wide on:close={() => !custom && dispatch('close')}>
  <form class="form" on:submit|preventDefault={submit}>
    <div class="title-box">
      <input class="field title" placeholder="Ajouter un titre" aria-label="Titre" bind:value={title} />
      {#if hint}<span class="hint">{hint}</span>{/if}
    </div>

    <label class="check"><input type="checkbox" bind:checked={allDay} on:change={allDayChanged} /> Toute la journée</label>

    <div class="grid">
      <span class="label">Début</span>
      <div class="when">
        <input class="field" type="date" bind:value={startDate} on:change={startChanged} aria-label="Date de début" required />
        {#if !allDay}<input class="field time" type="time" bind:value={startTime} on:change={startChanged} aria-label="Heure de début" required />{/if}
      </div>
      <span class="label">Fin</span>
      <div class="when">
        <input class="field" type="date" bind:value={endDate} on:change={endChanged} aria-label="Date de fin" required />
        {#if !allDay}<input class="field time" type="time" bind:value={endTime} on:change={endChanged} aria-label="Heure de fin" required />{/if}
      </div>

      {#if !allDay}
        {#if zoneShown || endZoneOn}
          <span class="label">{endZoneOn ? 'Fuseau de départ' : 'Fuseau horaire'}</span>
          <div><ZonePicker value={tz} on:change={(e) => { tz = e.detail; followRule(); }} /></div>
        {/if}
        <span class="label"></span>
        <div class="zone-row">
          {#if !zoneShown && !endZoneOn}
            <button type="button" class="link" on:click={() => (zoneShown = true)}>Fuseau horaire… <span class="muted">({city(tz || ZONE)})</span></button>
          {/if}
          <label class="check"><input type="checkbox" bind:checked={endZoneOn} on:change={endZoneToggled} /> Fuseau d'arrivée différent</label>
        </div>
        {#if endZoneOn}
          <span class="label">Fuseau d'arrivée</span>
          <div><ZonePicker value={endTz} allowFloating={false} label="Fuseau d'arrivée" on:change={(e) => (endTz = e.detail)} /></div>
        {/if}
      {/if}

      <span class="label">Agenda</span>
      <div class="cal">
        <span class="dot" style="background: {calColor}"></span>
        <select class="field" bind:value={calendar} aria-label="Agenda">
          {#each calendars as c}<option value={c.id}>{c.name}</option>{/each}
        </select>
      </div>

      <span class="label">Lieu</span>
      <div class="place">
        <input class="field" placeholder="Ajouter un lieu" aria-label="Lieu" bind:value={location} />
        {#if location.trim()}
          <a class="map" href="https://www.openstreetmap.org/search?query={encodeURIComponent(location.trim())}" target="_blank"
            rel="noopener noreferrer" title="Ouvrir dans la carte (OpenStreetMap)">Carte</a>
        {/if}
      </div>

      {#if repeatable}
        <span class="label">Répétition</span>
        <div>
          <select class="field" aria-label="Répétition" value={rule?.preset ?? 'none'} on:change={repeatChosen} disabled={!rule}>
            {#if rule}
              {#each rule.presets as p}<option value={p.key}>{p.label}</option>{/each}
              {#if rule.preset === 'custom'}<option value="custom">{rule.description}</option>{/if}
              {#if rule.preset === 'imported'}<option value="imported">Règle importée : {rrule}</option>{/if}
            {:else}
              <option value="none">Jamais</option>
            {/if}
            <option value="edit">Personnalisé…</option>
          </select>
          {#if rule?.preview}<p class="muted small">{rule.preview}</p>{/if}
        </div>
      {/if}

      <span class="label">Rappels</span>
      <div>
        <AlertsField bind:value={alerts} />
        <p class="muted small">Les rappels sonnent sur le téléphone ; le navigateur n'envoie pas de notification.</p>
      </div>
    </div>

    <div class="desc">
      <span class="label">Description</span>
      {#if rich}
        <div class="rich-box">
          <div class="tools">
            <IconButton icon="bold" label="Gras (Ctrl+B)" keepFocus size={20} on:click={() => richText?.bold()} />
            <IconButton icon="italic" label="Italique (Ctrl+I)" keepFocus size={20} on:click={() => richText?.italic()} />
            <IconButton icon="title" label="Titre" keepFocus size={20} on:click={() => richText?.heading()} />
            <IconButton icon="bullets" label="Puces" keepFocus size={20} on:click={() => richText?.bullets()} />
          </div>
          <RichText bind:this={richText} value={body} placeholder="Ajouter une description"
            on:change={(e) => { body = e.detail; bodyTouched = true; }} />
        </div>
      {:else}
        <p class="muted">La description contient une liste à cocher ou des pièces jointes : elle reste telle quelle ici,
          modifie-la avec « Ouvrir comme note ».</p>
      {/if}
    </div>

    {#if shownError}<p class="error" role="alert">{shownError}</p>{/if}
    <button type="submit" hidden></button>
  </form>

  <svelte:fragment slot="actions">
    {#if !isNew}<button class="btn delete" type="button" on:click={() => dispatch('delete')}>Supprimer</button><span class="grow"></span>{/if}
    <button class="btn" type="button" on:click={() => dispatch('close')}>Annuler</button>
    <button class="btn primary" type="button" disabled={busy} on:click={submit}>Enregistrer</button>
  </svelte:fragment>
</Modal>

{#if custom && rule}
  <RepeatDialog spec={rule.spec} {start} tz={zone} exdate={event.exdate || []} on:close={() => (custom = false)}
    on:done={(e) => { custom = false; rrule = e.detail; loadRule(); }} />
{/if}

<style>
  .form { display: flex; flex-direction: column; gap: 12px; }
  .title-box { display: flex; flex-direction: column; gap: 4px; }
  .title { font-size: 20px; font-weight: 600; }
  .hint { color: var(--on-surface-variant); font-size: 13px; padding-left: 4px; }
  .grid { display: grid; grid-template-columns: 120px minmax(0, 1fr); gap: 10px 14px; align-items: center; }
  .label { color: var(--on-surface-variant); font-size: 14px; text-align: right; }
  .when, .cal, .place, .zone-row { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
  .when .field { width: auto; flex: 1 1 150px; }
  .when .time { flex: 0 1 120px; }
  .field { padding: 8px 12px; }
  .cal .field { flex: 1; }
  .place .field { flex: 1; }
  .dot { width: 14px; height: 14px; border-radius: 50%; flex: none; }
  .map { font-weight: 600; text-decoration: none; padding: 6px 10px; border-radius: 16px; }
  .map:hover { background: var(--surface); }
  .link { border: none; background: transparent; color: var(--primary); cursor: pointer; padding: 4px 0; font-weight: 600; }
  .check { display: inline-flex; align-items: center; gap: 8px; cursor: pointer; }
  .check input { width: 18px; height: 18px; accent-color: var(--primary); }
  .small { font-size: 13px; margin: 4px 2px 0; }
  .desc { display: flex; flex-direction: column; gap: 6px; }
  .desc > .label { text-align: left; }
  .rich-box { border: 1px solid var(--outline-variant); border-radius: 12px; padding: 2px 12px 0; background: var(--background); position: relative; }
  .tools { display: flex; gap: 2px; border-bottom: 1px solid var(--outline-variant); margin: 0 -8px 6px; padding: 2px 4px; }
  .rich-box :global(.rich) { min-height: 70px; }
  .error { color: var(--error); margin: 0; font-weight: 600; }
  .btn.delete { color: var(--error); }
  .grow { flex: 1; }
  @media (max-width: 600px) {
    .grid { grid-template-columns: minmax(0, 1fr); gap: 4px; }
    .label { text-align: left; margin-top: 6px; }
    .label:empty { display: none; }
  }
</style>
