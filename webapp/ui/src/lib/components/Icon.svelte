<script>
  import { ICONS } from '../icons.js';
  /** @type {keyof typeof ICONS} */
  export let name;
  export let size = 24;
</script>

<svg width={size} height={size} viewBox="0 0 24 24" aria-hidden="true" fill="currentColor"><path d={ICONS[name]} /></svg>

<style>
  svg { flex: none; display: block; }
</style>
