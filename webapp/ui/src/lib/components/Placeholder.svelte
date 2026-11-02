<script>
  // Stand-in page of a module not built yet.
  import Icon from './Icon.svelte';
  /** @type {any} */
  export let icon;
  export let title;
  export let text = 'Ce module arrive bientôt dans la version web. Il fonctionne déjà sur le téléphone et sur le PC, avec les mêmes fichiers.';
</script>

<section class="placeholder">
  <span class="badge"><Icon name={icon} size={48} /></span>
  <h1>{title}</h1>
  <p class="muted">{text}</p>
</section>

<style>
  .placeholder { height: 100%; display: flex; flex-direction: column; align-items: center; justify-content: center; padding: 24px; text-align: center; }
  .badge { width: 96px; height: 96px; border-radius: 32px; background: var(--primary-container); color: var(--on-primary-container); display: flex; align-items: center; justify-content: center; }
  h1 { margin: 18px 0 6px; font-size: 22px; }
  p { max-width: 420px; margin: 0; }
</style>
