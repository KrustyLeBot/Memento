<script>
  import { snack, hideSnack, showError } from '../ui.js';

  async function act() {
    const action = $snack?.action;
    hideSnack();
    try {
      await action?.run();
    } catch (error) {
      showError(error);
    }
  }
</script>

{#if $snack}
  <div class="snack" class:error={$snack.error} role={$snack.error ? 'alert' : 'status'}>
    <span>{$snack.text}</span>
    {#if $snack.action}<button class="action" on:click={act}>{$snack.action.label}</button>{/if}
    <button class="close" aria-label="Fermer" title="Fermer" on:click={hideSnack}>×</button>
  </div>
{/if}

<style>
  .snack {
    position: fixed; left: 50%; transform: translateX(-50%); bottom: calc(28px + var(--bottom-bar, 0px));
    z-index: 60; display: flex; align-items: center; gap: 8px; max-width: min(640px, calc(100vw - 32px));
    background: var(--snack); color: var(--on-snack); border-radius: 8px; padding: 6px 6px 6px 16px;
    box-shadow: var(--shadow); animation: rise 0.15s ease-out;
  }
  .snack.error { background: var(--error-container); color: var(--on-error-container); }
  span { flex: 1; padding: 6px 0; }
  button { border: none; background: transparent; cursor: pointer; border-radius: 6px; padding: 6px 10px; font-weight: 700; }
  .action { color: var(--snack-action); text-transform: none; }
  .error .action { color: inherit; text-decoration: underline; }
  .close { color: inherit; opacity: 0.7; font-size: 18px; line-height: 1; }
  button:hover { background: rgba(128, 128, 128, 0.25); }
  @keyframes rise { from { transform: translate(-50%, 12px); opacity: 0; } }
</style>
