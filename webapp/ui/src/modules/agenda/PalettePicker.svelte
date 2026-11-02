<script>
  // A colour swatch that opens the calendars' palette (12 colours readable on both themes, core calendars.PALETTE).
  import { createEventDispatcher } from 'svelte';
  export let color = '#4F8BF5';
  /** @type {[string, string][]} */
  export let palette = [];
  export let label = 'Couleur';
  const dispatch = createEventDispatcher();
  let open = false;
  /** @param {string} c */
  function pick(c) {
    open = false;
    if (c.toUpperCase() !== color.toUpperCase()) dispatch('pick', c);
  }
</script>

<svelte:window on:keydown={(e) => e.key === 'Escape' && open && (e.stopPropagation(), (open = false))} />
<span class="wrap">
  <button type="button" class="swatch" style="background: {color}" title={label} aria-label={label} aria-expanded={open}
    on:click|stopPropagation={() => (open = !open)}></button>
  {#if open}
    <!-- svelte-ignore a11y-click-events-have-key-events a11y-no-static-element-interactions -->
    <div class="backdrop" on:click|stopPropagation={() => (open = false)}></div>
    <div class="pop" role="listbox" aria-label={label}>
      {#each palette as [c, name]}
        <button type="button" class="opt" class:on={c.toUpperCase() === color.toUpperCase()} style="background: {c}" title={name}
          aria-label={name} role="option" aria-selected={c.toUpperCase() === color.toUpperCase()} on:click|stopPropagation={() => pick(c)}></button>
      {/each}
    </div>
  {/if}
</span>

<style>
  .wrap { position: relative; display: inline-flex; }
  .swatch { width: 26px; height: 26px; border-radius: 50%; border: 2px solid var(--background); box-shadow: 0 0 0 1px var(--outline-variant); cursor: pointer; padding: 0; }
  .backdrop { position: fixed; inset: 0; z-index: 70; }
  .pop {
    position: absolute; top: 32px; left: 0; z-index: 71; background: var(--surface-high); border-radius: 14px; padding: 10px;
    display: grid; grid-template-columns: repeat(6, 28px); gap: 8px; box-shadow: var(--shadow);
  }
  .opt { width: 28px; height: 28px; border-radius: 50%; border: none; cursor: pointer; padding: 0; }
  .opt.on { box-shadow: 0 0 0 2px var(--surface-high), 0 0 0 4px var(--on-surface); }
</style>
