<script>
  import Placeholder from '../../lib/components/Placeholder.svelte';
  /** @type {import('../../lib/router.js').Route} */
  export let route;
  $: void route;
</script>

<Placeholder icon="calendar" title="Agenda" />
