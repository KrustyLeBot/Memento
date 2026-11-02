<script>
  // Create / edit a contact, as the Windows dialog: Prénom, Nom, numbers with their type, e-mails, address,
  // birthday (year optional), favourite, notes. Saved on "Enregistrer"; a file the sync replaced meanwhile is never
  // overwritten silently (409 → "Recharger" or "Garder ma version").
  import { onMount, tick } from 'svelte';
  import { ApiError } from '../../lib/api.js';
  import { go } from '../../lib/router.js';
  import { choose, showError } from '../../lib/ui.js';
  import Icon from '../../lib/components/Icon.svelte';
  import IconButton from '../../lib/components/IconButton.svelte';
  import { LABELS, MONTHS, api, fromForm, isEmpty, toForm } from './contacts.js';

  /** @type {string | null} null: a new contact */
  export let ref;

  /** @type {import('./contacts.js').Contact | null} */
  let original = null;
  /** @type {string | null} */
  let version = null;
  let form = toForm(null);
  let loading = !!ref;
  let saving = false;
  let error = '';
  /** @type {HTMLInputElement} */
  let firstInput;

  onMount(async () => {
    if (ref) {
      try {
        ({ contact: original, version } = await api.get(ref));
        form = toForm(original);
      } catch (e) {
        showError(e);
        go(['contacts'], undefined, true);
        return;
      } finally {
        loading = false;
      }
    }
    await tick();
    firstInput?.focus();
  });

  function cancel() {
    go(ref ? ['contacts', ref] : ['contacts'], undefined, true);
  }

  async function save() {
    error = '';
    let fields;
    try {
      fields = fromForm(form);
    } catch (e) {
      error = e instanceof Error ? e.message : String(e);
      return;
    }
    if (isEmpty(fields)) {
      error = 'Le contact est vide';
      return;
    }
    saving = true;
    try {
      const saved = original && version ? await api.update(original.ref, version, fields) : await api.create(fields);
      go(['contacts', saved.contact.ref], undefined, true);
    } catch (e) {
      if (e instanceof ApiError && e.status === 409) await conflict(e.body?.current ?? null, fields);
      else if (e instanceof ApiError && e.status === 400) error = e.message;
      else showError(e, "Échec de l'enregistrement");
    } finally {
      saving = false;
    }
  }

  /** @param {import('./contacts.js').VersionedContact | null} current @param {object} fields */
  async function conflict(current, fields) {
    if (!current) {
      const key = await choose('Contact supprimé ailleurs', 'Ce contact a été supprimé sur un autre appareil pendant que vous le modifiiez.',
        [{ key: 'drop', label: 'Abandonner', kind: 'plain' }, { key: 'create', label: 'Le recréer', kind: 'primary' }]);
      if (key === 'create') {
        const saved = await api.create(fields);
        go(['contacts', saved.contact.ref], undefined, true);
      } else if (key === 'drop') go(['contacts'], undefined, true);
      return;
    }
    const key = await choose('Modifié ailleurs', 'Ce contact a changé sur un autre appareil pendant que vous le modifiiez.',
      [{ key: 'reload', label: 'Recharger', kind: 'plain' }, { key: 'mine', label: 'Garder ma version', kind: 'primary' }]);
    if (key === 'reload') {
      original = current.contact;
      version = current.version;
      form = toForm(original);
    } else if (key === 'mine') {
      version = current.version;
      original = current.contact;
      await save();
    }
  }

  function addPhone() {
    form.phones = [...form.phones, { number: '', label: 'mobile' }];
  }
  function addEmail() {
    form.emails = [...form.emails, ''];
  }
</script>

<div class="scroll">
  {#if !loading}
    <form class="editor" on:submit|preventDefault={save}>
      <header>
        <IconButton icon="close" label="Annuler" on:click={cancel} />
        <h1>{ref ? 'Modifier le contact' : 'Nouveau contact'}</h1>
        <button class="btn primary" type="submit" disabled={saving}>Enregistrer</button>
      </header>

      <div class="card">
        <div class="pair">
          <label>Prénom<input class="field" bind:value={form.first_name} bind:this={firstInput} autocomplete="off" /></label>
          <label>Nom<input class="field" bind:value={form.last_name} autocomplete="off" /></label>
        </div>
      </div>

      <div class="card">
        <h2><Icon name="phone" size={18} /> Téléphone</h2>
        {#each form.phones as phone, i}
          <div class="row">
            <input class="field number" type="tel" placeholder="06 12 34 56 78" aria-label="Numéro {i + 1}" bind:value={phone.number} />
            <input class="field label" list="contact-labels" placeholder="type" aria-label="Type du numéro {i + 1}" bind:value={phone.label} />
            <IconButton icon="close" label="Retirer ce numéro" on:click={() => (form.phones = form.phones.filter((_, j) => j !== i))} />
          </div>
        {/each}
        <datalist id="contact-labels">{#each LABELS as l}<option value={l}></option>{/each}</datalist>
        <button type="button" class="btn add" on:click={addPhone}><Icon name="add" size={18} /> Ajouter un numéro</button>
      </div>

      <div class="card">
        <h2><Icon name="mail" size={18} /> E-mail</h2>
        {#each form.emails as _, i}
          <div class="row">
            <input class="field" type="email" aria-label="E-mail {i + 1}" bind:value={form.emails[i]} />
            <IconButton icon="close" label="Retirer cet e-mail" on:click={() => (form.emails = form.emails.filter((_, j) => j !== i))} />
          </div>
        {/each}
        <button type="button" class="btn add" on:click={addEmail}><Icon name="add" size={18} /> Ajouter un e-mail</button>
      </div>

      <div class="card">
        <label>Adresse<input class="field" bind:value={form.address} autocomplete="off" /></label>
        <fieldset>
          <legend>Anniversaire</legend>
          <div class="birthday">
            <input class="field day" inputmode="numeric" placeholder="Jour" aria-label="Jour" bind:value={form.day} maxlength="2" />
            <select class="field month" aria-label="Mois" bind:value={form.month}>
              <option value="">Mois</option>
              {#each MONTHS as m, i}<option value={String(i + 1)}>{m}</option>{/each}
            </select>
            <input class="field year" inputmode="numeric" placeholder="Année (facultative)" aria-label="Année (facultative)" bind:value={form.year} maxlength="4" />
          </div>
        </fieldset>
        <label class="check"><input type="checkbox" bind:checked={form.favorite} /> Favori</label>
      </div>

      <div class="card">
        <label>Notes<textarea class="field" rows="5" bind:value={form.notes}></textarea></label>
      </div>

      {#if error}<p class="error" role="alert">{error}</p>{/if}
      <div class="bottom">
        <button type="button" class="btn" on:click={cancel}>Annuler</button>
        <button class="btn primary" type="submit" disabled={saving}>Enregistrer</button>
      </div>
    </form>
  {/if}
</div>

<style>
  .scroll { flex: 1; overflow-y: auto; padding: 8px 16px 120px; }
  .editor { max-width: 720px; margin: 0 auto; display: flex; flex-direction: column; gap: 12px; }
  header { display: flex; align-items: center; gap: 8px; }
  h1 { flex: 1; font-size: 20px; font-weight: 600; margin: 0; }
  .card { background: var(--surface); border-radius: 20px; padding: 14px 16px; display: flex; flex-direction: column; gap: 10px; }
  h2 { display: flex; align-items: center; gap: 6px; margin: 0; font-size: 14px; font-weight: 700; color: var(--primary); }
  label { display: flex; flex-direction: column; gap: 4px; font-size: 13px; color: var(--on-surface-variant); }
  label .field, label textarea { font-size: 15px; color: var(--on-surface); }
  .pair { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
  .row { display: flex; align-items: center; gap: 6px; }
  .row .field { flex: 1; min-width: 0; }
  .row .label { flex: 0 0 130px; }
  .add { align-self: flex-start; display: inline-flex; align-items: center; gap: 4px; padding: 6px 12px; }
  fieldset { border: none; margin: 0; padding: 0; }
  legend { font-size: 13px; color: var(--on-surface-variant); padding: 0; margin-bottom: 4px; }
  .birthday { display: flex; gap: 6px; }
  .day { flex: 0 0 76px; }
  .month { flex: 1; min-width: 0; }
  .year { flex: 0 0 170px; }
  textarea { resize: vertical; }
  .check { flex-direction: row; align-items: center; gap: 10px; font-size: 15px; color: var(--on-surface); cursor: pointer; }
  .check input { width: 18px; height: 18px; accent-color: var(--primary); }
  .error { color: var(--error); margin: 0 4px; font-weight: 600; }
  .bottom { display: flex; justify-content: flex-end; gap: 8px; }
  @media (max-width: 600px) {
    .scroll { padding: 4px 8px 120px; }
    .pair { grid-template-columns: 1fr; }
    .row .label { flex-basis: 116px; }
    .birthday { flex-wrap: wrap; }
    .year { flex: 1 1 100%; }
  }
</style>
