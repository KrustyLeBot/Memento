<script>
  // A centred modal card (full screen on a phone when `wide`). Escape and a click outside call `on:close`.
  import { createEventDispatcher, onMount } from 'svelte';
  export let title = '';
  export let wide = false;
  export let fullscreen = false;
  const dispatch = createEventDispatcher();
  /** @type {HTMLElement} */
  let card;
  onMount(() => {
    const focusable = card.querySelector('input, textarea, button.primary, button');
    if (focusable instanceof HTMLElement) focusable.focus();
  });
  /** @param {KeyboardEvent} e */
  function key(e) {
    if (e.key === 'Escape') {
      e.stopPropagation();
      dispatch('close');
    }
  }
</script>

<svelte:window on:keydown={key} />
<!-- svelte-ignore a11y-click-events-have-key-events a11y-no-static-element-interactions -->
<div class="scrim" on:click|self={() => dispatch('close')}>
  <div class="card" class:wide class:fullscreen role="dialog" aria-modal="true" aria-label={title} bind:this={card}>
    {#if title}<h2>{title}</h2>{/if}
    <slot />
    {#if $$slots.actions}<div class="actions"><slot name="actions" /></div>{/if}
  </div>
</div>

<style>
  .scrim {
    position: fixed; inset: 0; background: rgba(20, 10, 12, 0.45); display: flex; align-items: center;
    justify-content: center; z-index: 50; padding: 16px; animation: fade 0.12s ease-out;
  }
  .card {
    background: var(--surface-high); color: var(--on-surface); border-radius: 24px; padding: 22px 24px 16px;
    width: min(420px, 100%); max-height: calc(100vh - 32px); overflow: auto; box-shadow: var(--shadow);
  }
  .card.wide { width: min(720px, 100%); }
  .card.fullscreen { width: min(1100px, 100%); height: min(820px, calc(100vh - 32px)); display: flex; flex-direction: column; padding: 12px; }
  h2 { margin: 0 0 12px; font-size: 20px; font-weight: 600; }
  .actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 18px; flex-wrap: wrap; }
  @media (max-width: 600px) {
    .scrim { padding: 0; align-items: flex-end; }
    .card { border-radius: 24px 24px 0 0; width: 100%; }
    .card.fullscreen { height: 100%; border-radius: 0; }
  }
  @keyframes fade { from { opacity: 0; } }
</style>
