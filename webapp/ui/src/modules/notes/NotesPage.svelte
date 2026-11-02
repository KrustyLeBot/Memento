<script>
  // #/notes → the list; #/notes/edit/<path> → a note; #/notes/new[?list=1] → a new note (or checklist).
  import NotesList from './NotesList.svelte';
  import NoteEditor from './NoteEditor.svelte';
  /** @type {import('../../lib/router.js').Route} */
  export let route;

  $: mode = route.rest[0] === 'edit' && route.rest[1] ? 'edit' : route.rest[0] === 'new' ? 'new' : 'list';
  // A new note keeps its editor when it gets its path: the editor rewrites the URL to edit/<path> without a
  // hashchange, so this key does not change.
  $: editorKey = mode === 'new' ? `new-${route.query.get('t') || ''}` : `edit-${route.rest[1] || ''}`;
</script>

{#if mode === 'list'}
  <NotesList />
{:else}
  {#key editorKey}
    <NoteEditor path={mode === 'edit' ? route.rest[1] : null} checklist={route.query.get('list') === '1'} />
  {/key}
{/if}
