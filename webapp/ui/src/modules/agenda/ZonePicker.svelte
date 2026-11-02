<script>
  // Searchable IANA zone field (the server's list, as the Windows ZoneBox); the first choice is "floating" (null).
  import { createEventDispatcher, onMount } from 'svelte';
  import { agenda } from './api.js';

  /** @type {string | null} */
  export let value = null;
  export let label = 'Fuseau horaire';
  export let allowFloating = true;
  const FLOATING = "Celui de l'appareil (flottant)";
  const dispatch = createEventDispatcher();
  const id = `zones-${Math.random().toString(36).slice(2)}`;
  /** @type {string[]} */
  let zones = cache;
  let text = value === null ? FLOATING : value.replace(/_/g, ' ');
  let invalid = false;

  onMount(async () => {
    if (!cache.length) {
      try {
        cache = (await agenda.zones()).zones;
      } catch {
        cache = [];
      }
      zones = cache;
    }
  });

  function commit() {
    const t = text.trim();
    if (allowFloating && (t === FLOATING || t === '')) {
      invalid = false;
      value = null;
      dispatch('change', null);
      return;
    }
    const match = zones.find((z) => z.replace(/_/g, ' ').toLowerCase() === t.toLowerCase() || z.toLowerCase() === t.toLowerCase());
    invalid = !match;
    if (match) {
      value = match;
      text = match.replace(/_/g, ' ');
      dispatch('change', match);
    }
  }
</script>

<script context="module">
  /** @type {string[]} fetched once per page */
  let cache = [];
</script>

<input class="field" class:invalid list={id} aria-label={label} bind:value={text} on:change={commit} on:blur={commit}
  placeholder="Rechercher une ville ou un fuseau" spellcheck="false" autocomplete="off" />
<datalist {id}>
  {#if allowFloating}<option value={FLOATING}></option>{/if}
  {#each zones as z}<option value={z.replace(/_/g, ' ')}></option>{/each}
</datalist>
{#if invalid}<span class="err">Fuseau inconnu</span>{/if}

<style>
  .invalid { border-color: var(--error); }
  .err { color: var(--error); font-size: 13px; }
</style>
