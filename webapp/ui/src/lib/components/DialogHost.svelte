<script>
  // Shows the question asked with ui.choose() / ui.confirm().
  import { dialog } from '../ui.js';
  import Modal from './Modal.svelte';
</script>

{#if $dialog}
  <Modal title={$dialog.title} on:close={() => $dialog?.resolve(null)}>
    <p class="message">{$dialog.message}</p>
    <svelte:fragment slot="actions">
      {#each $dialog.choices as choice}
        <button class="btn {choice.kind === 'primary' ? 'primary' : choice.kind === 'danger' ? 'danger' : ''}"
          on:click={() => $dialog?.resolve(choice.key)}>{choice.label}</button>
      {/each}
    </svelte:fragment>
  </Modal>
{/if}

<style>
  .message { margin: 0; white-space: pre-line; color: var(--on-surface-variant); }
</style>
