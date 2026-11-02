<script>
  import { createEventDispatcher } from 'svelte';
  import Modal from '../../lib/components/Modal.svelte';
  import { fold } from './notes.js';
  /** every label of the folder */
  export let all = [];
  /** this note's labels */
  export let selected = [];
  const dispatch = createEventDispatcher();
  let chosen = [...selected];
  let options = [...new Set([...all, ...selected])];
  let fresh = '';

  function add() {
    const label = fresh.trim().replace(/,/g, ' ');
    fresh = '';
    if (!label) return;
    if (!options.includes(label)) options = [...options, label].sort((a, b) => fold(a).localeCompare(fold(b)));
    if (!chosen.includes(label)) chosen = [...chosen, label];
  }

  function done() {
    add();
    dispatch('done', options.filter((l) => chosen.includes(l)));
  }
</script>

<Modal title="Libellés" on:close={() => dispatch('close')}>
  <div class="list">
    {#each options as label}
      <label><input type="checkbox" bind:group={chosen} value={label} /> {label}</label>
    {:else}
      <p class="muted">Aucun libellé pour l'instant.</p>
    {/each}
  </div>
  <input class="field" placeholder="Nouveau libellé" bind:value={fresh} on:keydown={(e) => e.key === 'Enter' && (e.preventDefault(), add())} />
  <svelte:fragment slot="actions">
    <button class="btn" on:click={() => dispatch('close')}>Annuler</button>
    <button class="btn primary" on:click={done}>OK</button>
  </svelte:fragment>
</Modal>

<style>
  .list { display: flex; flex-direction: column; max-height: 300px; overflow: auto; margin-bottom: 12px; }
  label { display: flex; align-items: center; gap: 10px; padding: 8px 4px; border-radius: 8px; cursor: pointer; }
  label:hover { background: rgba(128, 128, 128, 0.1); }
  input[type='checkbox'] { width: 18px; height: 18px; accent-color: var(--primary); }
</style>
