<script>
  // "Gérer les agendas": create, rename, recolour, choose the default, delete (events moved or deleted). Every change
  // is written at once (agenda/calendars.json, synced with the phone and the PC).
  import { createEventDispatcher } from 'svelte';
  import Modal from '../../lib/components/Modal.svelte';
  import { choose } from '../../lib/ui.js';
  import { calendars, changeCalendars, deleteCalendar } from './calendars.js';
  import { plural } from './dates.js';
  import PalettePicker from './PalettePicker.svelte';

  const dispatch = createEventDispatcher();
  let name = '';

  async function create() {
    if (!name.trim()) return;
    if (await changeCalendars({ op: 'create', name: name.trim() })) name = '';
  }
  /** @param {import('./api.js').Calendar} cal @param {string} value */
  function rename(cal, value) {
    if (value.trim() && value.trim() !== cal.name) changeCalendars({ op: 'update', id: cal.id, name: value.trim() });
  }
  /** @param {import('./api.js').Calendar} cal */
  async function remove(cal) {
    const state = /** @type {import('./api.js').Calendars} */ ($calendars);
    const fallback = state.calendars.find((c) => c.id === (state.default !== cal.id ? state.default : state.calendars.find((x) => x.id !== cal.id)?.id));
    if (!fallback) return;
    const key = cal.count
      ? await choose("Supprimer l'agenda", `Supprimer l'agenda « ${cal.name} » ? Il contient ${plural(cal.count, 'événement', 'événements')}.`, [
        { key: 'cancel', label: 'Annuler', kind: 'plain' },
        { key: 'drop', label: 'Supprimer les événements', kind: 'danger' },
        { key: 'move', label: `Déplacer vers « ${fallback.name} »`, kind: 'primary' },
      ])
      : await choose("Supprimer l'agenda", `Supprimer l'agenda « ${cal.name} » ?`, [
        { key: 'cancel', label: 'Annuler', kind: 'plain' }, { key: 'move', label: 'Supprimer', kind: 'danger' }]);
    if (key === 'move' || key === 'drop') await deleteCalendar(cal.id, key === 'move' ? fallback.id : null, () => dispatch('changed'));
  }
</script>

<Modal title="Agendas" wide on:close={() => dispatch('close')}>
  {#if $calendars}
    <div class="table" role="table">
      <div class="row head" role="row"><span></span><span>Nom</span><span>Par défaut</span><span></span></div>
      {#each $calendars.calendars as cal (cal.id)}
        <div class="row" role="row">
          <PalettePicker color={cal.color} palette={$calendars.palette} on:pick={(e) => changeCalendars({ op: 'update', id: cal.id, color: e.detail })} />
          <div class="name">
            <input class="field" value={cal.name} aria-label="Nom de l'agenda"
              on:change={(e) => rename(cal, e.currentTarget.value)} on:keydown={(e) => e.key === 'Enter' && e.currentTarget.blur()} />
            <span class="muted small">{plural(cal.count, 'événement', 'événements')}{cal.imported ? ' · importé' : ''}</span>
          </div>
          <input type="radio" name="default" checked={$calendars.default === cal.id} aria-label="Agenda par défaut"
            on:change={() => changeCalendars({ op: 'default', id: cal.id })} />
          <button class="btn delete" disabled={$calendars.calendars.length < 2} on:click={() => remove(cal)}>Supprimer</button>
        </div>
      {/each}
    </div>
    <form class="new" on:submit|preventDefault={create}>
      <input class="field" placeholder="Nom du nouvel agenda" aria-label="Nom du nouvel agenda" bind:value={name} />
      <button class="btn tonal" type="submit" disabled={!name.trim()}>Ajouter</button>
    </form>
    {#if $calendars.problem}<p class="warn">{$calendars.problem}</p>{/if}
  {/if}
  <svelte:fragment slot="actions">
    <button class="btn primary" on:click={() => dispatch('close')}>Fermer</button>
  </svelte:fragment>
</Modal>

<style>
  .table { display: flex; flex-direction: column; gap: 4px; }
  .row { display: grid; grid-template-columns: 40px minmax(0, 1fr) 80px auto; gap: 10px; align-items: center; }
  .row.head { color: var(--on-surface-variant); font-size: 13px; }
  .row.head span:nth-child(3) { text-align: center; }
  .name { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
  .field { padding: 7px 12px; }
  .small { font-size: 12px; padding-left: 4px; }
  input[type='radio'] { justify-self: center; width: 18px; height: 18px; accent-color: var(--primary); }
  .new { display: flex; gap: 8px; margin-top: 14px; }
  .btn.delete { color: var(--error); }
  .warn { color: var(--error); }
  @media (max-width: 600px) {
    .row { grid-template-columns: 40px minmax(0, 1fr) 44px auto; gap: 6px; }
    .btn.delete { padding: 8px 8px; }
  }
</style>
