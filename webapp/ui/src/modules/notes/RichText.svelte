<script>
  // WYSIWYG body: bold, italic, headings and bullets on screen, the same Markdown in the file (lib/markdown.js).
  import { createEventDispatcher, onMount } from 'svelte';
  import { blocksToMarkdown, domToBlocks, markdownToHtml } from '../../lib/markdown.js';

  /** The Markdown text; changes from outside (reload) replace the content. */
  export let value = '';
  export let placeholder = 'Note';
  const dispatch = createEventDispatcher();
  /** @type {HTMLDivElement} */
  let el;
  let shown = '';
  let focused = false;

  onMount(() => render(value));
  $: if (el && value !== shown) render(value);

  /** @param {string} markdown */
  function render(markdown) {
    shown = markdown;
    el.innerHTML = markdownToHtml(markdown);
  }

  function input() {
    shown = blocksToMarkdown(domToBlocks(el));
    value = shown;
    dispatch('change', shown);
  }

  /** @param {ClipboardEvent} e pasted text only: no foreign styles in a Markdown note */
  function paste(e) {
    e.preventDefault();
    const text = e.clipboardData?.getData('text/plain') ?? '';
    document.execCommand('insertText', false, text);
  }

  /** The block element the caret is in (h1, h2, p…), or null. */
  function currentBlock() {
    const selection = getSelection();
    let node = selection?.anchorNode ?? null;
    while (node && node !== el) {
      if (node.nodeType === 1 && /^(H[1-6]|P|DIV|LI)$/.test(node.nodeName)) return /** @type {HTMLElement} */ (node);
      node = node.parentNode;
    }
    return null;
  }

  /** @param {string} command @param {string} [arg] */
  function exec(command, arg) {
    if (!focused) el.focus();
    document.execCommand(command, false, arg);
    input();
  }

  export const bold = () => exec('bold');
  export const italic = () => exec('italic');
  export const bullets = () => exec('insertUnorderedList');
  /** none → big → medium → none, like the Windows and Android editors. */
  export function heading() {
    const name = currentBlock()?.nodeName;
    exec('formatBlock', name === 'H1' ? 'h2' : name === 'H2' ? 'p' : 'h1');
  }
  export const focus = () => el.focus();

  /** @param {KeyboardEvent} e */
  function key(e) {
    if ((e.ctrlKey || e.metaKey) && !e.altKey && !e.shiftKey) {
      if (e.key === 'b') { e.preventDefault(); bold(); }
      if (e.key === 'i') { e.preventDefault(); italic(); }
    }
  }
</script>

<div class="rich" class:empty={!value} bind:this={el} contenteditable="true" tabindex="0" role="textbox" aria-multiline="true"
  aria-label={placeholder} data-placeholder={placeholder} spellcheck="true"
  on:input={input} on:paste={paste} on:keydown={key}
  on:focus={() => { focused = true; document.execCommand('defaultParagraphSeparator', false, 'p'); dispatch('focus'); }}
  on:blur={() => { focused = false; dispatch('blur'); }}></div>

<style>
  .rich { outline: none; min-height: 90px; font-size: 16px; line-height: 1.55; overflow-wrap: anywhere; padding: 4px 0 12px; }
  .rich.empty::before { content: attr(data-placeholder); color: currentColor; opacity: 0.5; pointer-events: none; position: absolute; }
  .rich :global(p) { margin: 0; min-height: 1.55em; }
  .rich :global(h1) { font-size: 1.6em; margin: 0.2em 0; line-height: 1.25; }
  .rich :global(h2) { font-size: 1.35em; margin: 0.2em 0; line-height: 1.25; }
  .rich :global(h3) { font-size: 1.15em; margin: 0.2em 0; }
  .rich :global(ul) { margin: 0; padding-left: 1.5em; }
</style>
