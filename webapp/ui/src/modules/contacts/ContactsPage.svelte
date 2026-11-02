<script>
  // #/contacts → the list; #/contacts/<ref> → a contact (the agenda links birthdays here by id);
  // #/contacts/<ref>/edit → its editor; #/contacts/new → a new contact.
  // Wide screens show the list beside the contact; a phone shows one at a time.
  import ContactList from './ContactList.svelte';
  import ContactDetail from './ContactDetail.svelte';
  import ContactEditor from './ContactEditor.svelte';
  import Icon from '../../lib/components/Icon.svelte';
  /** @type {import('../../lib/router.js').Route} */
  export let route;

  $: ref = route.rest[0] && route.rest[0] !== 'new' ? route.rest[0] : null;
  $: mode = route.rest[0] === 'new' ? 'new' : ref && route.rest[1] === 'edit' ? 'edit' : ref ? 'detail' : 'list';
</script>

<div class="page" class:open={mode !== 'list'}>
  <section class="list-pane"><ContactList current={ref} /></section>
  <section class="detail-pane">
    {#if mode === 'detail' && ref}
      {#key ref}<ContactDetail {ref} />{/key}
    {:else if mode === 'edit' && ref}
      {#key ref}<ContactEditor {ref} />{/key}
    {:else if mode === 'new'}
      <ContactEditor ref={null} />
    {:else}
      <div class="nothing"><Icon name="person" size={72} /><p>Sélectionnez un contact</p></div>
    {/if}
  </section>
</div>

<style>
  .page { height: 100%; display: flex; min-height: 0; }
  .list-pane { width: 380px; flex: none; border-right: 1px solid var(--outline-variant); min-height: 0; display: flex; flex-direction: column; }
  .detail-pane { flex: 1; min-width: 0; min-height: 0; display: flex; flex-direction: column; }
  .nothing { margin: auto; display: flex; flex-direction: column; align-items: center; color: var(--outline); }
  .nothing p { margin: 8px 0 0; }
  @media (max-width: 899px) {
    .list-pane { width: 100%; border-right: none; }
    .detail-pane { display: none; }
    .open .list-pane { display: none; }
    .open .detail-pane { display: flex; }
  }
</style>
