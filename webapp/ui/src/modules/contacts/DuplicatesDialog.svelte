<script>
  // Groups of contacts sharing a number or a name (core duplicate_groups); per group, choose the one to keep, then
  // "Fusionner" (it gets the others' numbers, e-mails and notes; they are deleted; undoable) or "Ignorer" (for now).
  import { createEventDispatcher, onMount } from 'svelte';
  import { ApiError, undo } from '../../lib/api.js';
  import { showError, showSnack } from '../../lib/ui.js';
  import Modal from '../../lib/components/Modal.svelte';
  import { api } from './contacts.js';

  export let firstNameFirst = false;
  const dispatch = createEventDispatcher();

  /** @type {import('./contacts.js').DuplicateGroup[]} */
  let groups = [];
  let loaded = false;
  let busy = false;
  /** @type {Set<string>} groups ignored in this dialog, by their sorted ids */
  let ignored = new Set();
  /** @type {Record<string, string>} chosen contact per group */
  let keep = {};

  const key = (/** @type {import('./contacts.js').DuplicateGroup} */ g) => g.contacts.map((c) => c.id).sort().join(' ');

  async function load() {
    try {
      groups = (await api.duplicates(firstNameFirst)).groups;
      keep = Object.fromEntries(groups.map((g) => [key(g), keep[key(g)] ?? g.keep]));
      loaded = true;
    } catch (error) {
      showError(error);
    }
  }
  onMount(load);

  /** @param {import('./contacts.js').DuplicateGroup} g */
  async function merge(g) {
    const kept = keep[key(g)];
    busy = true;
    try {
      const result = await api.merge(kept, g.contacts.map((c) => c.id).filter((id) => id !== kept));
      showSnack(`${g.contacts.length} contacts fusionnés`, { action: { label: 'Annuler', run: async () => { await undo(result.undo); load(); } } });
    } catch (error) {
      if (!(error instanceof ApiError && error.status === 409)) showError(error);
      else showSnack(error.message);
    } finally {
      busy = false;
    }
    await load();
  }

  $: shown = groups.filter((g) => !ignored.has(key(g)));
</script>

<Modal title="Doublons" wide on:close={() => dispatch('close')}>
  {#if loaded}
    <p class="muted">
      {#if !shown.length}
        Aucun doublon : pas de numéro ni de nom en double.
      {:else}
        {shown.length} groupe{shown.length > 1 ? 's' : ''} : même numéro ou même nom. Choisissez la fiche à garder ; elle reçoit
        les numéros, e-mails et notes des autres, qui sont supprimées.
      {/if}
    </p>
    {#each shown as g (key(g))}
      <fieldset class="group">
        <legend class="sr-only">Groupe de doublons</legend>
        {#each g.contacts as c}
          <label class="choice">
            <input type="radio" name="keep-{key(g)}" value={c.id} bind:group={keep[key(g)]} />
            <span class="who">
              <strong>{c.name}</strong>
              {#if c.details.length}<span class="muted">{c.details.join(' · ')}</span>{/if}
            </span>
          </label>
        {/each}
        <div class="row">
          <button class="btn" disabled={busy} on:click={() => (ignored = new Set([...ignored, key(g)]))}>Ignorer</button>
          <button class="btn primary" disabled={busy} on:click={() => merge(g)}>Fusionner</button>
        </div>
      </fieldset>
    {/each}
  {/if}
  <svelte:fragment slot="actions">
    <button class="btn" on:click={() => dispatch('close')}>Fermer</button>
  </svelte:fragment>
</Modal>

<style>
  p { margin: 0 0 12px; }
  .group { border: none; background: var(--surface); border-radius: 18px; padding: 10px 14px; margin: 0 0 10px; }
  .choice { display: flex; align-items: flex-start; gap: 10px; padding: 6px 0; cursor: pointer; }
  .choice input { width: 18px; height: 18px; margin-top: 2px; accent-color: var(--primary); flex: none; }
  .who { display: flex; flex-direction: column; min-width: 0; overflow-wrap: anywhere; }
  .who .muted { font-size: 13px; }
  .row { display: flex; justify-content: flex-end; gap: 6px; margin-top: 4px; }
</style>
