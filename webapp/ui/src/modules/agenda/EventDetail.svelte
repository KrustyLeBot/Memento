<script>
  // What a click on an event shows (Windows EventDetailDialog): when, zone or flight line, repetition, calendar,
  // place (to the map), reminders, description, the "Liens" (meeting link first), and the actions.
  import { createEventDispatcher } from 'svelte';
  import Modal from '../../lib/components/Modal.svelte';
  import Icon from '../../lib/components/Icon.svelte';
  import { markdownToHtml } from '../../lib/markdown.js';
  import { ZONE } from './api.js';
  import { alertLabel, city, stampClock, whenText } from './dates.js';

  /** @type {import('./api.js').Entry} */
  export let entry;
  /** @type {import('./api.js').AgendaEvent} */
  export let event;
  /** @type {import('./api.js').AgendaEvent | null} */
  export let series = null;
  /** @type {import('./api.js').Calendar[]} */
  export let calendars = [];
  export let eventZone = false;
  const dispatch = createEventDispatcher();

  $: calendar = calendars.find((c) => c.id === entry.calendar_id);
  $: zoneLine = !entry.all_day && !entry.flight && event.tz && event.tz !== ZONE && entry.occurrence && entry.occurrence.start.length > 10
    ? `${stampClock(entry.occurrence.start)} à ${city(event.tz)} (${event.tz})` : null;
  $: repeat = series?.rule_text || event.rule_text || (event.recurrence_of ? "Occurrence modifiée d'une série" : null);
  /** @param {string} action */
  const act = (action) => dispatch('action', action);
</script>

<Modal title="" wide on:close={() => dispatch('close')}>
  <div class="head">
    <span class="dot" style="background: {entry.color}"></span>
    <h2>{event.title || '(Sans titre)'}</h2>
  </div>
  <ul class="lines">
    <li><Icon name="schedule" size={20} /><span>{whenText(entry, eventZone)}</span></li>
    {#if entry.flight}<li><Icon name="flight" size={20} /><span>{entry.flight}</span></li>{/if}
    {#if zoneLine}<li><Icon name="public" size={20} /><span>{zoneLine}</span></li>{/if}
    {#if repeat}<li><Icon name="repeat" size={20} /><span>{repeat}</span></li>{/if}
    {#if calendar}<li><Icon name="calendar" size={20} /><span>{calendar.name}</span></li>{/if}
    {#if event.location}
      <li><Icon name="place" size={20} />
        <a href="https://www.openstreetmap.org/search?query={encodeURIComponent(event.location)}" target="_blank"
          rel="noopener noreferrer" title="Ouvrir dans la carte (OpenStreetMap)">{event.location}</a></li>
    {/if}
    {#if event.alerts.length}<li><Icon name="alarm" size={20} /><span>{event.alerts.map(alertLabel).join(', ')}</span></li>{/if}
    {#if event.status === 'tentative'}<li><Icon name="event" size={20} /><span>Provisoire</span></li>{/if}
  </ul>
  {#if event.body.trim()}
    <div class="body">{@html markdownToHtml(event.body)}</div>
  {/if}
  {#if event.links.length}
    <div class="links">
      <div class="links-title"><Icon name="link" size={20} /> Liens</div>
      {#each event.links as link}
        <a class="link" href={link.url} target="_blank" rel="noopener noreferrer" title={link.url}>{link.label}</a>
      {/each}
    </div>
  {/if}
  {#if event.warnings.length}<p class="warn">{event.warnings.join('\n')}</p>{/if}

  <svelte:fragment slot="actions">
    {#if event.recurrence_of && series}
      <button class="btn" title="Supprime cette modification : l'occurrence reprend l'heure et le contenu de la série"
        on:click={() => act('restore')}>Rétablir l'original</button>
    {/if}
    <button class="btn delete" on:click={() => act('delete')}>Supprimer</button>
    <button class="btn" title="La description dans l'éditeur de notes : listes, pièces jointes, dessins" on:click={() => act('note')}>Ouvrir comme note</button>
    <span class="grow"></span>
    <button class="btn" on:click={() => dispatch('close')}>Fermer</button>
    <button class="btn primary" on:click={() => act('edit')}>Modifier</button>
  </svelte:fragment>
</Modal>

<style>
  .head { display: flex; align-items: flex-start; gap: 12px; margin-bottom: 10px; }
  .dot { width: 16px; height: 16px; border-radius: 50%; flex: none; margin-top: 8px; }
  h2 { margin: 0; font-size: 22px; font-weight: 600; overflow-wrap: anywhere; }
  .lines { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 8px; }
  .lines li { display: flex; gap: 12px; align-items: flex-start; color: var(--on-surface); overflow-wrap: anywhere; }
  .lines :global(svg) { color: var(--on-surface-variant); margin-top: 1px; }
  .body {
    margin-top: 14px; background: var(--surface); border-radius: 12px; padding: 10px 14px; max-height: 320px; overflow: auto;
    overflow-wrap: anywhere; line-height: 1.5;
  }
  .body :global(p) { margin: 0; min-height: 1.5em; }
  .body :global(h1) { font-size: 1.4em; margin: 0.2em 0; }
  .body :global(h2) { font-size: 1.2em; margin: 0.2em 0; }
  .body :global(ul) { margin: 0; padding-left: 1.4em; }
  .links { margin-top: 14px; display: flex; flex-direction: column; align-items: flex-start; gap: 6px; }
  .links-title { display: flex; gap: 12px; align-items: center; color: var(--on-surface-variant); font-weight: 600; }
  .link {
    margin-left: 32px; background: var(--surface); border-radius: 16px; padding: 6px 14px; text-decoration: none; font-weight: 600;
    max-width: calc(100% - 32px); overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  }
  .link:hover { background: var(--secondary-container); }
  .warn { color: var(--error); white-space: pre-line; }
  .btn.delete { color: var(--error); }
  .grow { flex: 1; }
</style>
