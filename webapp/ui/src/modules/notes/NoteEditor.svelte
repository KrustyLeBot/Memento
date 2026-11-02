<script>
  // A note open for editing, as the Windows editor: autosave 700 ms after the last change, the server refusing a
  // save over a version the sync replaced (409) and the user then choosing "Recharger" or "Garder ma version".
  import { onDestroy, onMount, tick } from 'svelte';
  import { ApiError, conflicts, fileUrl, notes as api, undo } from '../../lib/api.js';
  import { onChange } from '../../lib/events.js';
  import { noteActions } from '../../lib/hooks.js';
  import { plainText } from '../../lib/markdown.js';
  import { go, href } from '../../lib/router.js';
  import { choose, confirm, showError, showSnack } from '../../lib/ui.js';
  import IconButton from '../../lib/components/IconButton.svelte';
  import Icon from '../../lib/components/Icon.svelte';
  import RichText from './RichText.svelte';
  import Checklist from './Checklist.svelte';
  import AttachmentStrip from './AttachmentStrip.svelte';
  import ColorDialog from './ColorDialog.svelte';
  import LabelsDialog from './LabelsDialog.svelte';
  import DrawingEditor from './DrawingEditor.svelte';
  import { MAX_ATTACHMENT, attachmentName, copyText, findUrls, isColored, isDrawing, noteBackground } from './notes.js';

  /** null: a new note, created on the first save that has something in it */
  export let path = null;
  export let checklist = false;

  /** @type {import('../../lib/api.js').Note | null} the note as last read or saved */
  let note = null;
  /** @type {string | null} */
  let version = null;
  let loading = !!path;
  let title = '';
  /** @type {import('../../lib/api.js').BodyParts} */
  let parts = { before: '', items: checklist ? [{ text: '', checked: false }] : [], after: '' };
  let color = 'default';
  let pinned = false;
  /** @type {string[]} */
  let labels = [];
  /** @type {string[]} */
  let attachments = [];

  let dirty = false;
  /** @type {any} */
  let timer = null;
  /** @type {Promise<void>} saves run one after the other */
  let chain = Promise.resolve();
  let asking = false;
  let closed = false;
  /** @type {'color' | 'labels' | 'drawing' | null} */
  let dialog = null;
  /** @type {string[]} */
  let allLabels = [];
  /** @type {{ file: string | null, strokes: import('../../lib/api.js').Stroke[] } | null} */
  let drawing = null;
  /** @type {RichText} */
  let rich;
  /** @type {HTMLInputElement} */
  let fileInput;
  /** @type {HTMLInputElement} */
  let titleInput;

  /** @param {import('../../lib/api.js').VersionedNote} v */
  function apply(v) {
    note = v.note;
    version = v.version;
    title = v.note.title;
    parts = v.note.parts;
    color = v.note.color;
    pinned = v.note.pinned;
    labels = v.note.labels;
    attachments = v.note.attachments;
    dirty = false;
  }

  onMount(async () => {
    if (path) {
      try {
        apply(await api.get(path));
      } catch (error) {
        showError(error);
        go(['notes'], undefined, true);
        return;
      } finally {
        loading = false;
      }
    } else {
      await tick();
      if (!checklist) titleInput?.focus();
    }
  });

  const stop = onChange((areas) => {
    const area = note?.path.startsWith('agenda/') ? 'agenda' : note?.path.startsWith('activities/') ? 'activities' : 'notes';
    if (areas.includes(area)) checkDisk();
  });

  onDestroy(() => {
    stop();
    closed = true;
    if (dirty) flush().catch(showError);
  });

  function edited() {
    dirty = true;
    clearTimeout(timer);
    timer = setTimeout(() => flush().catch((e) => showError(e, "Échec de l'enregistrement")), 700);
  }

  function changes() {
    return { title: title.trim(), parts, color, pinned, labels };
  }

  const blank = () => !title.trim() && !parts.before.trim() && !parts.after.trim()
    && parts.items.every((i) => !i.text.trim()) && !attachments.length;

  /** Saves now if something changed; resolves once written (or refused and handled). */
  function flush() {
    clearTimeout(timer);
    chain = chain.then(saveNow, saveNow);
    return chain;
  }

  async function saveNow() {
    if (!dirty) return;
    if (!note) {
      if (blank()) return;
      dirty = false;
      const created = await api.create(changes());
      note = created.note;
      version = created.version;
      if (!closed) history.replaceState(null, '', href(['notes', 'edit', created.note.path]));
      return;
    }
    dirty = false;
    try {
      const saved = await api.update(note.path, /** @type {string} */ (version), changes());
      note = saved.note;
      version = saved.version;
    } catch (error) {
      if (error instanceof ApiError && error.status === 409) {
        dirty = true;
        await resolveConflict(error.body?.current ?? null);
      } else {
        dirty = true;
        throw error;
      }
    }
  }

  /** The file changed under us (sync, other tab): the user picks, as with the Windows overwrite guard.
   * @param {import('../../lib/api.js').VersionedNote | null} current */
  async function resolveConflict(current) {
    if (asking) return;
    asking = true;
    try {
      if (current === null) {
        const answer = await choose('Note supprimée ailleurs',
          'Cette note a été supprimée ailleurs (synchronisation, autre appareil) pendant que vous la modifiiez.',
          [{ key: 'close', label: 'Fermer', kind: 'plain' }, { key: 'recreate', label: 'La recréer', kind: 'primary' }]);
        if (answer === 'recreate') {
          note = null;
          version = null;
          dirty = true;
          await saveNow();
          showSnack('Note recréée');
        } else {
          dirty = false;
          leave();
        }
        return;
      }
      const answer = await choose('Note modifiée ailleurs',
        'Ce fichier a changé (synchronisation ou autre appareil) pendant que vous le modifiiez.',
        [{ key: 'reload', label: 'Recharger', kind: 'plain' }, { key: 'mine', label: 'Garder ma version', kind: 'primary' }]);
      if (answer === 'mine') {
        version = current.version;
        dirty = true;
        await saveNow();
      } else {
        apply(current);
      }
    } finally {
      asking = false;
    }
  }

  /** A change event: reload silently when nothing is being edited, else ask. */
  async function checkDisk() {
    if (!note || asking || closed) return;
    await chain;
    if (closed) return;
    try {
      const disk = await api.get(note.path);
      if (disk.version === version) return;
      if (dirty) await resolveConflict(disk);
      else apply(disk);
    } catch (error) {
      if (error instanceof ApiError && error.status === 404) {
        if (dirty) await resolveConflict(null);
        else {
          showSnack('Note supprimée ailleurs');
          leave();
        }
      } else showError(error);
    }
  }

  function leave() {
    closed = true;
    go(['notes']);
  }

  async function back() {
    try {
      await flush();
    } catch (error) {
      showError(error, "Échec de l'enregistrement");
      return;
    }
    leave();
  }

  /** The note must exist on disk before a file can be attached to it. */
  async function ensureSaved() {
    if (!note) dirty = true;
    await flush();
    if (!note) {
      const created = await api.create(changes());
      note = created.note;
      version = created.version;
      history.replaceState(null, '', href(['notes', 'edit', created.note.path]));
    }
    return /** @type {import('../../lib/api.js').Note} */ (note);
  }

  /** Attach/detach/draw answer the whole note; the text being typed meanwhile stays.
   * @param {import('../../lib/api.js').VersionedNote} v */
  function applyFiles(v) {
    note = v.note;
    version = v.version;
    attachments = v.note.attachments;
  }

  // ---- tools ----

  function toggleChecklist() {
    if (!parts.items.length) {
      const lines = parts.before.split('\n').map((l) => l.trim().replace(/^- /, '').trim()).filter(Boolean);
      parts = { ...parts, before: '', items: lines.length ? lines.map((text) => ({ text, checked: false })) : [{ text: '', checked: false }] };
    } else {
      const asText = parts.items.map((i) => `- ${i.text}`).join('\n');
      parts = { ...parts, before: [parts.before.trimEnd(), asText].filter(Boolean).join('\n\n'), items: [] };
    }
    edited();
  }

  async function openLabels() {
    try {
      allLabels = (await api.list()).labels;
    } catch (error) {
      showError(error);
    }
    dialog = 'labels';
  }

  /** @param {Event} e */
  async function upload(e) {
    const input = /** @type {HTMLInputElement} */ (e.target);
    const files = [...(input.files || [])];
    input.value = '';
    if (!files.length) return;
    try {
      const current = await ensureSaved();
      for (const file of files) {
        if (file.size > MAX_ATTACHMENT) {
          showError(new Error(`${file.name} dépasse ${MAX_ATTACHMENT / 1_000_000} Mo`), 'Pièce jointe impossible');
          continue;
        }
        showSnack(`Envoi de ${file.name}…`, { duration: 60000 });
        applyFiles(await api.attach(current.path, /** @type {string} */ (version), file));
      }
      showSnack(files.length > 1 ? 'Fichiers joints' : 'Fichier joint');
    } catch (error) {
      if (error instanceof ApiError && error.status === 409) await checkDisk();
      showError(error, 'Pièce jointe impossible');
    }
  }

  /** @param {CustomEvent<string>} e */
  async function removeAttachment(e) {
    const file = e.detail;
    if (!note || !(await confirm('Retirer la pièce jointe', `Retirer « ${attachmentName(file)} » de la note ? Le fichier sera supprimé.`,
      { confirm: 'Retirer', danger: true }))) return;
    try {
      await flush();
      applyFiles(await api.detach(note.path, /** @type {string} */ (version), file));
    } catch (error) {
      showError(error);
    }
  }

  /** @param {CustomEvent<string>} e */
  async function openAttachment(e) {
    const file = e.detail;
    if (isDrawing(file)) {
      try {
        drawing = { file, strokes: (await api.drawing(file)).strokes };
        dialog = 'drawing';
      } catch (error) {
        showError(error);
      }
    } else window.open(fileUrl(file), '_blank', 'noopener');
  }

  function newDrawing() {
    drawing = { file: null, strokes: [] };
    dialog = 'drawing';
  }

  /** @param {CustomEvent<import('../../lib/api.js').Stroke[]>} e */
  async function saveDrawing(e) {
    const target = drawing;
    dialog = null;
    if (!target) return;
    if (!target.file && !e.detail.length) return;
    try {
      const current = await ensureSaved();
      applyFiles(await api.saveDrawing(current.path, /** @type {string} */ (version), target.file, e.detail));
      if (!e.detail.length) showSnack('Dessin supprimé');
    } catch (error) {
      showError(error, 'Dessin non enregistré');
    }
  }

  async function copy() {
    try {
      await copyText(plainText(title, parts));
      showSnack('Note copiée dans le presse-papiers');
    } catch (error) {
      showError(error, 'Copie impossible');
    }
  }

  async function remove() {
    clearTimeout(timer);
    if (!note) {
      dirty = false;
      leave();
      return;
    }
    try {
      await chain;
      closed = true; // our own delete is no "deleted elsewhere"
      const { undo: token } = await api.remove(note.path, /** @type {string} */ (version));
      dirty = false;
      leave();
      showSnack('Note supprimée', { action: { label: 'Annuler', run: () => undo(token) } });
    } catch (error) {
      closed = false;
      if (error instanceof ApiError && error.status === 409) await checkDisk();
      else showError(error, 'Suppression impossible');
    }
  }

  async function keepConflict() {
    if (!note) return;
    try {
      await flush();
      const result = await conflicts.resolve(note.path, 'keep');
      dirty = false;
      closed = true;
      showSnack("Cette version a remplacé l'originale", { action: { label: 'Annuler', run: () => undo(result.undo) } });
      go(['notes', 'edit', /** @type {string} */ (result.path)], undefined, true);
    } catch (error) {
      showError(error);
    }
  }

  /** @param {import('../../lib/hooks.js').NoteAction} action */
  async function runAction(action) {
    try {
      const current = await ensureSaved();
      await action.run({
        note: current, version: /** @type {string} */ (version),
        save: async (fields) => {
          await flush();
          const saved = await api.update(current.path, /** @type {string} */ (version), fields);
          apply(saved);
          return saved;
        },
      });
    } catch (error) {
      showError(error);
    }
  }

  /** @param {BeforeUnloadEvent} e */
  function beforeUnload(e) {
    if (dirty) {
      flush().catch(() => {});
      e.preventDefault();
    }
  }

  /** @param {KeyboardEvent} e */
  function key(e) {
    if (e.key === 'Escape' && !dialog && !asking) back();
  }

  $: urls = findUrls([parts.before, ...parts.items.map((i) => i.text), parts.after].join('\n'));
  $: colored = isColored(color);
  $: actions = $noteActions.filter((a) => !note || !a.visible || a.visible(note));
</script>

<svelte:window on:beforeunload={beforeUnload} on:keydown={key} />

<div class="editor" class:colored style="background: {noteBackground(color, true)}">
  <header>
    <IconButton icon="back" label="Retour (Échap)" on:click={back} />
    <span class="grow"></span>
    <IconButton icon="pin" label={pinned ? 'Désépingler' : 'Épingler'} active={pinned} on:click={() => { pinned = !pinned; edited(); }} />
  </header>

  <div class="scroll">
    <div class="page">
      {#if loading}
        <p class="muted">Chargement…</p>
      {:else}
        {#if note?.conflict}
          <div class="banner">
            <Icon name="warning" />
            <span>Copie de conflit créée par la synchronisation.</span>
            <button class="btn" on:click={keepConflict}>Garder celle-ci</button>
            <button class="btn" on:click={remove}>Supprimer</button>
          </div>
        {/if}
        {#if note?.pdf}
          <div class="banner">
            <Icon name="file" />
            <span>Document scanné</span>
            <a class="btn" href={fileUrl(note.pdf)} target="_blank" rel="noopener">Ouvrir le PDF</a>
          </div>
        {/if}
        <AttachmentStrip files={attachments} stamp={version || ''} on:open={openAttachment} on:remove={removeAttachment} />
        <input class="title" placeholder="Titre" aria-label="Titre" bind:value={title} bind:this={titleInput} on:input={edited}
          on:keydown={(e) => e.key === 'Enter' && (e.preventDefault(), rich?.focus())} />
        {#if !parts.items.length || parts.before.trim()}
          <RichText bind:this={rich} value={parts.before} on:change={(e) => { parts = { ...parts, before: e.detail }; edited(); }} />
        {/if}
        {#if parts.items.length}
          <Checklist items={parts.items} on:change={(e) => { parts = { ...parts, items: e.detail }; edited(); }} />
        {/if}
        {#if parts.after.trim()}
          <textarea class="after" aria-label="Suite de la note" bind:value={parts.after} on:input={() => { parts = parts; edited(); }}></textarea>
        {/if}
        {#if urls.length}
          <div class="links">
            {#each urls as url}<a class="link" href={url} target="_blank" rel="noopener noreferrer">{url.replace(/^https?:\/\//, '')}</a>{/each}
          </div>
        {/if}
      {/if}
    </div>
  </div>

  <footer>
    <div class="row format" aria-label="Mise en forme">
      <IconButton icon="bold" label="Gras (Ctrl+B)" keepFocus on:click={() => rich?.bold()} />
      <IconButton icon="italic" label="Italique (Ctrl+I)" keepFocus on:click={() => rich?.italic()} />
      <IconButton icon="title" label="Titre" keepFocus on:click={() => rich?.heading()} />
      <IconButton icon="bullets" label="Puces" keepFocus on:click={() => rich?.bullets()} />
      <span class="sep"></span>
      <IconButton icon="checklist" label={parts.items.length ? 'Liste en texte' : 'Liste à cocher'} on:click={toggleChecklist} />
      <IconButton icon="palette" label="Couleur" on:click={() => (dialog = 'color')} />
      <IconButton icon="label" label="Libellés" on:click={openLabels} />
      <IconButton icon="attach" label="Joindre des fichiers" on:click={() => fileInput.click()} />
      <IconButton icon="brush" label="Dessiner" on:click={newDrawing} />
      {#each actions as action (action.key)}
        <IconButton icon={action.icon} label={action.label} on:click={() => runAction(action)} />
      {/each}
      <IconButton icon="copy" label="Copier la note" on:click={copy} />
      <span class="grow"></span>
      <span class="delete"><IconButton icon="delete" label="Supprimer la note" on:click={remove} /></span>
    </div>
    {#if labels.length}
      <div class="labels">{#each labels as l}<span class="chip">{l}</span>{/each}</div>
    {/if}
  </footer>
  <input type="file" multiple hidden bind:this={fileInput} on:change={upload} />
</div>

{#if dialog === 'color'}
  <ColorDialog current={color} on:close={() => (dialog = null)} on:pick={(e) => { color = e.detail; dialog = null; edited(); }} />
{:else if dialog === 'labels'}
  <LabelsDialog all={allLabels} selected={labels} on:close={() => (dialog = null)}
    on:done={(e) => { labels = e.detail; dialog = null; edited(); }} />
{:else if dialog === 'drawing' && drawing}
  <DrawingEditor strokes={drawing.strokes} on:close={() => (dialog = null)} on:save={saveDrawing} />
{/if}

<style>
  .editor { height: 100%; display: flex; flex-direction: column; color: var(--on-surface); transition: background 0.2s; }
  .editor.colored { color: var(--note-fg); }
  header { display: flex; align-items: center; padding: 8px 8px 0; }
  .grow { flex: 1; }
  .scroll { flex: 1; overflow-y: auto; }
  .page { max-width: 860px; margin: 0 auto; padding: 0 24px 60px; }
  .title {
    width: 100%; border: none; background: transparent; outline: none; font-size: 26px; font-weight: 600; padding: 8px 0;
    color: inherit;
  }
  .title::placeholder { color: currentColor; opacity: 0.5; }
  .after { width: 100%; min-height: 80px; border: none; background: transparent; outline: none; color: inherit; opacity: 0.7; resize: vertical; font-size: 15px; }
  .banner {
    display: flex; align-items: center; gap: 10px; flex-wrap: wrap; background: var(--primary-container);
    color: var(--on-primary-container); border-radius: 16px; padding: 8px 8px 8px 14px; margin: 8px 0;
  }
  .banner span { flex: 1; min-width: 160px; }
  .banner .btn { color: var(--on-primary-container); text-decoration: none; }
  .links { display: flex; flex-direction: column; align-items: flex-start; gap: 6px; margin-top: 12px; }
  .link {
    background: rgba(128, 128, 128, 0.16); color: var(--primary); border-radius: 16px; padding: 6px 14px; text-decoration: none;
    max-width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  }
  .colored .link { color: inherit; text-decoration: underline; }
  footer { padding: 4px 8px calc(8px + env(safe-area-inset-bottom)); }
  .row { display: flex; align-items: center; gap: 2px; flex-wrap: wrap; }
  .sep { width: 1px; height: 24px; background: currentColor; opacity: 0.2; margin: 0 6px; flex: none; }
  .delete { color: var(--error); }
  .colored .delete { color: inherit; }
  .labels { display: flex; gap: 6px; flex-wrap: wrap; padding: 4px 10px; }
  .chip { background: rgba(128, 128, 128, 0.22); border-radius: 9px; padding: 1px 10px; font-size: 13px; }
  @media (max-width: 719px) {
    .page { padding: 0 16px 40px; }
    .title { font-size: 22px; }
  }
</style>
