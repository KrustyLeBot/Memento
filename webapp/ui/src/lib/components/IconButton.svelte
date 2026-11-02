<script>
  import Icon from './Icon.svelte';
  /** @type {any} */
  export let icon;
  /** Tooltip and accessible name (French). */
  export let label;
  export let active = false;
  export let disabled = false;
  export let size = 24;
  /** Keeps the focus where it was (the text being formatted). */
  export let keepFocus = false;
</script>

<button type="button" class="icon-button" class:active title={label} aria-label={label} aria-pressed={active ? 'true' : undefined}
  {disabled} on:click on:mousedown={(e) => keepFocus && e.preventDefault()}>
  <Icon name={icon} {size} />
</button>

<style>
  .icon-button {
    border: none; background: transparent; color: inherit; border-radius: 50%; width: 40px; height: 40px; flex: none;
    display: inline-flex; align-items: center; justify-content: center; cursor: pointer;
  }
  .icon-button:hover { background: rgba(128, 128, 128, 0.18); }
  .icon-button.active { background: rgba(128, 128, 128, 0.3); color: var(--primary); }
  .icon-button:disabled { opacity: 0.4; cursor: default; }
</style>
