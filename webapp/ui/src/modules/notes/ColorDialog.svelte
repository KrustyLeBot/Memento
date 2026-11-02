<script>
  import { createEventDispatcher } from 'svelte';
  import Modal from '../../lib/components/Modal.svelte';
  import Icon from '../../lib/components/Icon.svelte';
  import { COLORS, noteBackground } from './notes.js';
  export let current = 'default';
  const dispatch = createEventDispatcher();
</script>

<Modal title="Couleur" on:close={() => dispatch('close')}>
  <div class="grid">
    {#each Object.entries(COLORS) as [id, name]}
      <button class="swatch" class:on={id === current} style="background: {noteBackground(id)}" title={name} aria-label={name}
        aria-pressed={id === current} on:click={() => dispatch('pick', id)}>
        {#if id === current}<Icon name="check" size={22} />{/if}
      </button>
    {/each}
  </div>
</Modal>

<style>
  .grid { display: grid; grid-template-columns: repeat(4, 52px); gap: 14px; justify-content: center; padding: 4px 0 8px; }
  .swatch {
    width: 52px; height: 52px; border-radius: 50%; border: 1px solid var(--outline); cursor: pointer;
    display: flex; align-items: center; justify-content: center; color: var(--note-fg);
  }
  .swatch:first-child { color: var(--on-surface); }
  .swatch.on { border: 3px solid var(--primary); }
  .swatch:hover { transform: scale(1.06); }
</style>
