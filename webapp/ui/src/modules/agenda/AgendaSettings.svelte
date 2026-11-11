<script>
  // The agenda's section of Réglages: this browser's preferences (week start, event-zone times, like the PC's
  // settings on Windows) and the synced ones (default calendar, default reminders, birthdays layer: calendars.json).
  import { onMount } from 'svelte';
  import { prefs, setPref } from '../../lib/ui.js';
  import AlertsField from './AlertsField.svelte';
  import { calendars, changeCalendars, loadCalendars } from './calendars.js';
  import { agendaPrefs } from './prefs.js';

  onMount(loadCalendars);
  $: p = agendaPrefs($prefs);
</script>

<h2 class="section-title">Agenda</h2>
<div class="card">
  <div class="row">
    <span>Premier jour de la semaine</span>
    <div class="segmented" role="radiogroup" aria-label="Premier jour de la semaine">
      {#each [[0, 'Lundi'], [5, 'Samedi'], [6, 'Dimanche']] as [value, label]}
        <button role="radio" aria-checked={p.weekStart === value} class:on={p.weekStart === value}
          on:click={() => setPref('agenda.weekStart', value)}>{label}</button>
      {/each}
    </div>
  </div>
  <label class="check"><input type="checkbox" checked={p.eventZone} on:change={(e) => setPref('agenda.eventZone', e.currentTarget.checked)} />
    Heures dans le fuseau de l'événement (sinon celui de ce navigateur)</label>
  {#if $calendars}
    <p class="muted">Synchronisé avec le téléphone et le PC :</p>
    <div class="stack">
      <span>Rappels par défaut des nouveaux événements et des imports .ics</span>
      <AlertsField value={$calendars.default_alerts} on:change={(e) => changeCalendars({ op: 'alerts', alerts: e.detail })} />
    </div>
    <div class="stack">
      <span>Rappels par défaut des événements sur la journée</span>
      <AlertsField value={$calendars.default_alerts_all_day}
        on:change={(e) => changeCalendars({ op: 'alerts', all_day: true, alerts: e.detail })} />
    </div>
    <div class="stack">
      <span>Rappels des anniversaires de tous les contacts, déjà là ou futurs (aucun si les anniversaires sont masqués)</span>
      <AlertsField allDay value={$calendars.birthday_alerts}
        on:change={(e) => changeCalendars({ op: 'birthday_alerts', alerts: e.detail })} />
    </div>
    <div class="row">
      <span>Agenda par défaut</span>
      <select class="field auto" value={$calendars.default} aria-label="Agenda par défaut"
        on:change={(e) => changeCalendars({ op: 'default', id: e.currentTarget.value })}>
        {#each $calendars.calendars as c}<option value={c.id}>{c.name}</option>{/each}
      </select>
    </div>
    <label class="check"><input type="checkbox" checked={$calendars.layers.birthdays.visible}
      on:change={(e) => changeCalendars({ op: 'layer', key: 'birthdays', visible: e.currentTarget.checked })} />
      Afficher les anniversaires des contacts</label>
  {/if}
</div>

<style>
  .card { background: var(--surface); border-radius: 20px; padding: 14px 18px; display: flex; flex-direction: column; gap: 10px; }
  .card p { margin: 4px 0 0; }
  .row { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
  .stack { display: flex; flex-direction: column; gap: 6px; }
  .segmented { display: inline-flex; border: 1px solid var(--outline); border-radius: 20px; overflow: hidden; }
  .segmented button { border: none; background: transparent; padding: 7px 14px; cursor: pointer; font-weight: 600; }
  .segmented button + button { border-left: 1px solid var(--outline); }
  .segmented button.on { background: var(--secondary-container); color: var(--on-secondary-container); }
  .check { display: flex; align-items: center; gap: 10px; cursor: pointer; }
  .check input { width: 18px; height: 18px; accent-color: var(--primary); flex: none; }
  .field.auto { width: auto; padding: 7px 12px; }
</style>
