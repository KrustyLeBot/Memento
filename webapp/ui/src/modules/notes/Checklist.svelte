<script>
  // Checklist editor, as Keep and the other apps: drag the handle to reorder, Enter adds an item, Backspace on an
  // empty one removes it, checked items folded at the bottom (the file keeps them last too: core build_body).
  import { createEventDispatcher, tick } from 'svelte';
  import Icon from '../../lib/components/Icon.svelte';

  /** @type {import('../../lib/api.js').CheckItem[]} */
  export let items = [];
  const dispatch = createEventDispatcher();

  let nextId = 1;
  /** @type {{ id: number, text: string, checked: boolean }[]} */
  let rows = [];
  /** @type {import('../../lib/api.js').CheckItem[] | null} what this component last sent: anything else is a reload */
  let emitted = null;
  let showChecked = false;
  /** @type {Record<number, HTMLInputElement>} */
  const inputs = {};

  $: if (items !== emitted) rows = items.map((i) => ({ id: nextId++, text: i.text, checked: i.checked }));
  $: open = rows.filter((r) => !r.checked);
  $: done = rows.filter((r) => r.checked);

  function emit() {
    rows = [...rows.filter((r) => !r.checked), ...rows.filter((r) => r.checked)];
    emitted = rows.map(({ text, checked }) => ({ text, checked }));
    items = emitted;
    dispatch('change', emitted);
  }

  /** @param {number} id */
  async function focusRow(id, atEnd = true) {
    await tick();
    const input = inputs[id];
    if (!input) return;
    input.focus();
    if (atEnd) input.setSelectionRange(input.value.length, input.value.length);
  }

  /** @param {typeof rows[number]} row */
  function toggle(row) {
    row.checked = !row.checked;
    if (!row.checked) rows = [...rows.filter((r) => r !== row), row]; // back at the end of the open items
    emit();
  }

  /** @param {number} index in `open` */
  function addAfter(index) {
    const row = { id: nextId++, text: '', checked: false };
    const at = index < 0 ? open.length : rows.indexOf(open[index]) + 1;
    rows = [...rows.slice(0, at), row, ...rows.slice(at)];
    emit();
    focusRow(row.id);
  }

  /** @param {typeof rows[number]} row @param {boolean} focusPrevious */
  function remove(row, focusPrevious) {
    const list = row.checked ? done : open;
    const previous = list[list.indexOf(row) - 1];
    rows = rows.filter((r) => r !== row);
    emit();
    if (focusPrevious && previous) focusRow(previous.id);
  }

  /** @param {KeyboardEvent} e @param {typeof rows[number]} row @param {number} index */
  function key(e, row, index) {
    if (e.key === 'Enter' && !e.isComposing) {
      e.preventDefault();
      if (!row.checked) addAfter(index);
    } else if (e.key === 'Backspace' && !row.text) {
      e.preventDefault();
      remove(row, true);
    }
  }

  // ---- drag to reorder (pointer events: mouse, pen and touch alike) ----
  /** @type {{ row: typeof rows[number], pointer: number } | null} */
  let drag = null;
  /** @type {HTMLElement} */
  let list;

  /** @param {PointerEvent} e @param {typeof rows[number]} row */
  function dragStart(e, row) {
    e.preventDefault();
    drag = { row, pointer: e.pointerId };
    /** @type {HTMLElement} */ (e.currentTarget).setPointerCapture(e.pointerId);
  }

  /** @param {PointerEvent} e */
  function dragMove(e) {
    if (!drag || e.pointerId !== drag.pointer) return;
    const elements = [...list.querySelectorAll('[data-row]')];
    let target = elements.length - 1;
    for (let i = 0; i < elements.length; i++) {
      const box = elements[i].getBoundingClientRect();
      if (e.clientY < box.top + box.height / 2) {
        target = i;
        break;
      }
    }
    const from = open.indexOf(drag.row);
    if (target === from) return;
    const reordered = open.filter((r) => r !== drag?.row);
    reordered.splice(target, 0, drag.row);
    rows = [...reordered, ...done];
  }

  function dragEnd() {
    if (!drag) return;
    drag = null;
    emit();
  }
</script>

<div class="checklist" bind:this={list}>
  {#each open as row, index (row.id)}
    <div class="row" class:dragging={drag?.row === row} data-row>
      <span class="handle" title="Déplacer" on:pointerdown={(e) => dragStart(e, row)} on:pointermove={dragMove}
        on:pointerup={dragEnd} on:pointercancel={dragEnd}><Icon name="drag" size={20} /></span>
      <input type="checkbox" checked={false} on:change={() => toggle(row)} aria-label="Cocher" />
      <input class="text" bind:this={inputs[row.id]} bind:value={row.text} on:input={emit} on:keydown={(e) => key(e, row, index)}
        placeholder="Élément de liste" />
      <button class="remove" title="Supprimer l'élément" aria-label="Supprimer l'élément" on:click={() => remove(row, false)}>×</button>
    </div>
  {/each}
  <button class="add" on:click={() => addAfter(-1)}><Icon name="add" size={20} /> Élément de liste</button>
  {#if done.length}
    <button class="fold" on:click={() => (showChecked = !showChecked)} aria-expanded={showChecked}>
      <span class="chevron" class:open={showChecked}><Icon name="expand" size={20} /></span>
      {done.length} élément{done.length > 1 ? 's' : ''} coché{done.length > 1 ? 's' : ''}
    </button>
    {#if showChecked}
      {#each done as row (row.id)}
        <div class="row checked">
          <span class="handle-space"></span>
          <input type="checkbox" checked on:change={() => toggle(row)} aria-label="Décocher" />
          <input class="text" bind:this={inputs[row.id]} bind:value={row.text} on:input={emit} on:keydown={(e) => key(e, row, -1)} />
          <button class="remove" title="Supprimer l'élément" aria-label="Supprimer l'élément" on:click={() => remove(row, false)}>×</button>
        </div>
      {/each}
    {/if}
  {/if}
</div>

<style>
  .checklist { display: flex; flex-direction: column; padding: 4px 0 8px; }
  .row { display: flex; align-items: center; gap: 4px; border-radius: 8px; min-height: 38px; }
  .row:hover { background: rgba(128, 128, 128, 0.08); }
  .row.dragging { background: rgba(128, 128, 128, 0.18); box-shadow: var(--shadow); }
  .handle { cursor: grab; opacity: 0.45; display: flex; padding: 6px 0; touch-action: none; }
  .row:hover .handle { opacity: 0.85; }
  .handle-space { width: 20px; flex: none; }
  input[type='checkbox'] { width: 18px; height: 18px; accent-color: var(--primary); margin: 0 6px; flex: none; cursor: pointer; }
  .text { flex: 1; min-width: 0; border: none; background: transparent; outline: none; font-size: 16px; padding: 6px 0; color: inherit; }
  .checked .text { text-decoration: line-through; opacity: 0.6; }
  .remove { border: none; background: transparent; color: inherit; font-size: 20px; opacity: 0; cursor: pointer; width: 32px; height: 32px; border-radius: 50%; }
  .row:hover .remove, .row:focus-within .remove { opacity: 0.6; }
  .remove:hover { background: rgba(128, 128, 128, 0.2); opacity: 1; }
  .add, .fold {
    display: flex; align-items: center; gap: 8px; border: none; background: transparent; color: inherit; opacity: 0.7;
    padding: 8px 4px; cursor: pointer; text-align: left; font-size: 15px;
  }
  .add:hover, .fold:hover { opacity: 1; }
  .fold { margin-top: 6px; border-top: 1px solid rgba(128, 128, 128, 0.3); padding-top: 12px; }
  .chevron { display: flex; transform: rotate(-90deg); transition: transform 0.15s; }
  .chevron.open { transform: none; }
  @media (hover: none) { .remove { opacity: 0.5; } .handle { opacity: 0.7; } }
</style>
