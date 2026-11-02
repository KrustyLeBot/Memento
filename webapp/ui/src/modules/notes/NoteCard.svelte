<script>
  import Icon from '../../lib/components/Icon.svelte';
  import { href } from '../../lib/router.js';
  import { noteBackground, isColored } from './notes.js';
  /** @type {import('../../lib/api.js').NoteSummary} */
  export let note;

  $: chips = [...(note.pdf ? ['PDF'] : []), ...(note.attachments.length ? [`📎 ${note.attachments.length}`] : [])];
</script>

<a class="card" class:colored={isColored(note.color)} href={href(['notes', 'edit', note.path])}
  style="background: {noteBackground(note.color)}">
  {#if chips.length || note.conflict}
    <div class="chips">
      {#if note.conflict}<span class="chip badge">Conflit</span>{/if}
      {#each chips as chip}<span class="chip">{chip}</span>{/each}
    </div>
  {/if}
  {#if note.title.trim()}
    <div class="head">
      <h3>{note.title}</h3>
      {#if note.pinned}<span class="pin" title="Épinglée"><Icon name="pin" size={16} /></span>{/if}
    </div>
  {/if}
  {#if note.preview}<p class="preview">{note.preview}</p>{/if}
  {#if note.labels.length}
    <div class="chips">
      {#each note.labels.slice(0, 3) as label}<span class="chip">{label}</span>{/each}
    </div>
  {/if}
</a>

<style>
  .card {
    display: flex; flex-direction: column; gap: 6px; padding: 12px 14px; border-radius: 16px; text-decoration: none;
    color: var(--on-surface); border: 1px solid var(--outline-variant); transition: border-color 0.12s, box-shadow 0.12s;
    overflow: hidden; break-inside: avoid;
  }
  .card.colored { border-color: transparent; color: var(--note-fg); }
  .card:hover { border-color: var(--primary); box-shadow: var(--shadow); }
  .head { display: flex; gap: 8px; align-items: flex-start; }
  h3 {
    flex: 1; margin: 0; font-size: 16px; font-weight: 600; line-height: 1.3; overflow-wrap: anywhere;
    display: -webkit-box; -webkit-line-clamp: 4; -webkit-box-orient: vertical; overflow: hidden;
  }
  .pin { opacity: 0.8; margin-top: 2px; }
  .preview {
    margin: 0; font-size: 14px; white-space: pre-line; overflow-wrap: anywhere; opacity: 0.92;
    display: -webkit-box; -webkit-line-clamp: 11; -webkit-box-orient: vertical; overflow: hidden;
  }
  .chips { display: flex; flex-wrap: wrap; gap: 4px; }
  .chip { background: rgba(128, 128, 128, 0.22); border-radius: 9px; padding: 1px 8px; font-size: 12px; }
  .chip.badge { background: var(--error-container); color: var(--on-error-container); font-weight: 600; }
</style>
