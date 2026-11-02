<script>
  // One contact: numbers (call, SMS, copy), e-mails, address (maps), birthday, notes; favourite, edit, export,
  // delete with "Annuler". The links matter most from a phone's browser.
  import { onDestroy, onMount } from 'svelte';
  import { ApiError, undo } from '../../lib/api.js';
  import { onChange } from '../../lib/events.js';
  import { go, href } from '../../lib/router.js';
  import { showError, showSnack } from '../../lib/ui.js';
  import Icon from '../../lib/components/Icon.svelte';
  import IconButton from '../../lib/components/IconButton.svelte';
  import { copyText } from '../notes/notes.js';
  import { api, age, birthdayText, initials, mapsHref, smsHref, telHref } from './contacts.js';

  /** @type {string} */
  export let ref;

  /** @type {import('./contacts.js').Contact | null} */
  let contact = null;
  /** @type {string | null} */
  let version = null;
  let busy = false;

  async function load(quiet = false) {
    try {
      ({ contact, version } = await api.get(ref));
    } catch (error) {
      if (error instanceof ApiError && error.status === 404) {
        if (quiet) showSnack('Ce contact a été supprimé ailleurs');
        else showError(error);
        go(['contacts'], undefined, true);
      } else showError(error);
    }
  }

  const stop = onChange((areas) => areas.includes('contacts') && !busy && load(true));
  onMount(() => load());
  onDestroy(stop);

  /** @param {string} text @param {string} what */
  async function copy(text, what) {
    try {
      await copyText(text);
      showSnack(what);
    } catch (error) {
      showError(error);
    }
  }

  async function toggleFavorite() {
    if (!contact || !version || busy) return;
    busy = true;
    try {
      ({ contact, version } = await api.update(contact.ref, version, { favorite: !contact.favorite }));
    } catch (error) {
      if (error instanceof ApiError && error.status === 409) {
        showSnack('Ce contact venait de changer sur un autre appareil : rechargé, réessayez.');
        await load(true);
      } else showError(error);
    } finally {
      busy = false;
    }
  }

  async function remove() {
    if (!contact || !version || busy) return;
    busy = true;
    const name = contact.name;
    try {
      const { undo: token } = await api.remove(contact.ref, version);
      go(['contacts'], undefined, true);
      showSnack(`${name} supprimé`, { action: { label: 'Annuler', run: () => undo(token) } });
    } catch (error) {
      if (error instanceof ApiError && error.status === 409) {
        showSnack('Ce contact venait de changer sur un autre appareil : rechargé, rien n\'a été supprimé.');
        await load(true);
      } else showError(error);
    } finally {
      busy = false;
    }
  }

  $: years = contact?.birthday ? age(contact.birthday) : null;
</script>

<div class="scroll">
  {#if contact}
    <header>
      <a class="back" href={href(['contacts'])} title="Retour à la liste" aria-label="Retour à la liste"><Icon name="back" /></a>
      <div class="actions">
        <IconButton icon={contact.favorite ? 'star' : 'starBorder'} active={contact.favorite}
          label={contact.favorite ? 'Retirer des favoris' : 'Ajouter aux favoris'} on:click={toggleFavorite} />
        <a class="icon-link" href={href(['contacts', contact.ref, 'edit'])} title="Modifier" aria-label="Modifier"><Icon name="edit" /></a>
        <a class="icon-link" href={api.exportOneUrl(contact.ref)} download title="Exporter (.vcf)" aria-label="Exporter (.vcf)"><Icon name="download" /></a>
        <IconButton icon="delete" label="Supprimer" on:click={remove} />
      </div>
    </header>

    <div class="identity">
      <span class="avatar" aria-hidden="true">{initials(contact.name)}</span>
      <h1>{contact.name}</h1>
      {#if contact.conflict}
        <p class="conflict"><Icon name="warning" size={18} /> Copie en conflit de synchronisation : supprimez celle qui est en trop.</p>
      {/if}
    </div>

    <div class="cards">
      {#if contact.phones.length}
        <div class="card">
          {#each contact.phones as p}
            <div class="line">
              <span class="kind"><Icon name="phone" size={20} /></span>
              <a class="value" href={telHref(p.number)}>
                <span class="main">{p.number}</span>
                {#if p.label}<span class="sub muted">{p.label}</span>{/if}
              </a>
              <a class="icon-link" href={smsHref(p.number)} title="Message" aria-label="Envoyer un message à {p.number}"><Icon name="sms" size={20} /></a>
              <IconButton icon="copy" size={20} label="Copier {p.number}" on:click={() => copy(p.number, 'Numéro copié')} />
            </div>
          {/each}
        </div>
      {/if}
      {#if contact.emails.length}
        <div class="card">
          {#each contact.emails as e}
            <div class="line">
              <span class="kind"><Icon name="mail" size={20} /></span>
              <a class="value" href="mailto:{e}"><span class="main">{e}</span><span class="sub muted">e-mail</span></a>
              <IconButton icon="copy" size={20} label="Copier {e}" on:click={() => copy(e, 'E-mail copié')} />
            </div>
          {/each}
        </div>
      {/if}
      {#if contact.address || contact.birthday}
        <div class="card">
          {#if contact.address}
            <div class="line">
              <span class="kind"><Icon name="place" size={20} /></span>
              <a class="value" href={mapsHref(contact.address)} target="_blank" rel="noopener noreferrer">
                <span class="main">{contact.address}</span><span class="sub muted">adresse · ouvrir le plan</span>
              </a>
              <IconButton icon="copy" size={20} label="Copier l'adresse" on:click={() => copy(contact?.address ?? '', 'Adresse copiée')} />
            </div>
          {/if}
          {#if contact.birthday}
            <div class="line">
              <span class="kind"><Icon name="cake" size={20} /></span>
              <span class="value">
                <span class="main">{birthdayText(contact.birthday)}</span>
                <span class="sub muted">anniversaire{years !== null ? ` · ${years} an${years > 1 ? 's' : ''}` : ''}</span>
              </span>
            </div>
          {/if}
        </div>
      {/if}
      {#if contact.notes.trim()}
        <div class="card">
          <div class="line">
            <span class="kind"><Icon name="notes" size={20} /></span>
            <p class="notes">{contact.notes}</p>
          </div>
        </div>
      {/if}
    </div>
  {/if}
</div>

<style>
  .scroll { flex: 1; overflow-y: auto; padding: 8px 16px 120px; }
  header { display: flex; align-items: center; max-width: 720px; margin: 0 auto; }
  .actions { margin-left: auto; display: flex; gap: 2px; }
  .back { visibility: hidden; }
  .icon-link, .back {
    width: 40px; height: 40px; border-radius: 50%; display: inline-flex; align-items: center; justify-content: center;
    color: inherit; flex: none;
  }
  .icon-link:hover, .back:hover { background: rgba(128, 128, 128, 0.18); }
  .identity { display: flex; flex-direction: column; align-items: center; text-align: center; margin: 4px auto 20px; max-width: 720px; }
  .avatar {
    width: 96px; height: 96px; border-radius: 50%; display: flex; align-items: center; justify-content: center;
    background: var(--primary-container); color: var(--on-primary-container); font-size: 36px; font-weight: 600;
  }
  h1 { margin: 14px 0 0; font-size: 26px; font-weight: 600; overflow-wrap: anywhere; user-select: text; }
  .conflict { display: flex; align-items: center; gap: 6px; color: var(--error); margin: 8px 0 0; font-size: 14px; }
  .cards { display: flex; flex-direction: column; gap: 12px; max-width: 720px; margin: 0 auto; }
  .card { background: var(--surface); border-radius: 20px; padding: 6px 8px; }
  .line { display: flex; align-items: center; gap: 4px; min-height: 56px; }
  .kind { width: 44px; flex: none; display: flex; justify-content: center; color: var(--primary); align-self: flex-start; padding-top: 18px; }
  .value {
    flex: 1; min-width: 0; display: flex; flex-direction: column; color: inherit; text-decoration: none; padding: 8px 4px;
    border-radius: 12px; overflow-wrap: anywhere;
  }
  a.value:hover .main { text-decoration: underline; }
  .main { font-size: 16px; }
  .sub { font-size: 13px; }
  .notes { flex: 1; margin: 0; padding: 16px 4px; white-space: pre-wrap; overflow-wrap: anywhere; user-select: text; }
  @media (max-width: 899px) {
    .back { visibility: visible; }
    .scroll { padding: 4px 8px 120px; }
  }
</style>
