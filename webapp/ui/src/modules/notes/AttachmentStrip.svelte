<script>
  // The note's files in a row at the top of the editor: images and drawings as thumbnails, others by name.
  import { createEventDispatcher } from 'svelte';
  import { fileUrl } from '../../lib/api.js';
  import Icon from '../../lib/components/Icon.svelte';
  import { attachmentName, isDrawing, isImage } from './notes.js';

  /** @type {string[]} */
  export let files = [];
  /** changes when the note is saved: refreshes a drawing's thumbnail */
  export let stamp = '';
  const dispatch = createEventDispatcher();
  /** @type {string | null} */
  let menu = null;
</script>

{#if files.length}
  <div class="strip">
    {#each files as path (path)}
      <div class="tile-wrap">
        <button class="tile" title="{attachmentName(path)} — clic : ouvrir" on:click={() => dispatch('open', path)}
          on:contextmenu|preventDefault={() => (menu = path)}>
          {#if isImage(path) || isDrawing(path)}
            <img src="{fileUrl(path)}{stamp ? `?v=${stamp}` : ''}" alt={attachmentName(path)} loading="lazy" class:drawing={isDrawing(path)} />
          {:else}
            <Icon name="file" size={32} />
            <span class="name">{attachmentName(path)}</span>
          {/if}
        </button>
        <button class="more" title="Plus d'options" aria-label="Plus d'options pour {attachmentName(path)}" on:click={() => (menu = menu === path ? null : path)}>
          <Icon name="more" size={18} />
        </button>
        {#if menu === path}
          <div class="menu" role="menu">
            <button role="menuitem" on:click={() => { menu = null; dispatch('open', path); }}>Ouvrir</button>
            <a role="menuitem" href={fileUrl(path, true)} download on:click={() => (menu = null)}>Télécharger</a>
            <button role="menuitem" class="danger" on:click={() => { menu = null; dispatch('remove', path); }}>Retirer de la note</button>
          </div>
        {/if}
      </div>
    {/each}
  </div>
{/if}
<svelte:window on:click={(e) => menu && !(/** @type {HTMLElement} */ (e.target)).closest('.tile-wrap') && (menu = null)} />

<style>
  .strip { display: flex; gap: 8px; overflow-x: auto; padding: 4px 0 10px; }
  .tile-wrap { position: relative; flex: none; }
  .tile {
    width: 112px; height: 112px; border: none; border-radius: 12px; background: rgba(128, 128, 128, 0.15); color: inherit;
    cursor: pointer; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 6px; padding: 6px; overflow: hidden;
  }
  .tile:hover { background: rgba(128, 128, 128, 0.3); }
  img { width: 100%; height: 100%; object-fit: cover; border-radius: 8px; }
  img.drawing { object-fit: contain; background: #fff; }
  .name { font-size: 12px; max-width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .more {
    position: absolute; top: 4px; right: 4px; width: 28px; height: 28px; border-radius: 50%; border: none; cursor: pointer;
    background: rgba(0, 0, 0, 0.45); color: #fff; display: flex; align-items: center; justify-content: center; opacity: 0;
  }
  .tile-wrap:hover .more, .more:focus-visible { opacity: 1; }
  @media (hover: none) { .more { opacity: 0.85; } }
  .menu {
    position: absolute; top: 36px; right: 4px; z-index: 20; background: var(--surface-high); color: var(--on-surface);
    border: 1px solid var(--outline-variant); border-radius: 10px; padding: 4px; display: flex; flex-direction: column;
    min-width: 170px; box-shadow: var(--shadow);
  }
  .menu button, .menu a {
    border: none; background: transparent; text-align: left; padding: 8px 12px; border-radius: 6px; cursor: pointer;
    color: inherit; text-decoration: none; font-size: 14px;
  }
  .menu button:hover, .menu a:hover { background: var(--secondary-container); }
  .menu .danger { color: var(--error); }
</style>
