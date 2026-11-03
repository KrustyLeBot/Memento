<script>
  // The shared map page (web/map) in an <iframe> of this same site, with the switch 3D/2D, re-centre and full
  // screen drawn over it. One frame for the whole module: it is never moved in the document (moving an iframe
  // reloads it), only shown, parked off screen or spread over the window, and opening another activity only sends
  // it data. As the Windows host (ui/mapview.py), the last value of each setter is kept and replayed whenever the
  // page (re)loads.
  import { createEventDispatcher, onDestroy, onMount } from 'svelte';
  import { dark, prefs, setPref } from '../../lib/ui.js';
  import Glyph from './Glyph.svelte';
  import { photoMaxZoom } from '../settings/map.js';

  /** Off screen, kept loaded (the list, an activity without track). */
  export let parked = true;
  /** Load the page now (else on the first activity shown). */
  export let preload = false;

  const STATE_ORDER = ['setTheme', 'setMode', 'setSquare', 'setLines', 'setTrack', 'setDots', 'setCursor'];
  const dispatch = createEventDispatcher();
  /** @type {HTMLIFrameElement} */
  let frame;
  let src = '';
  let loaded = false;
  let expanded = false;
  let mode = $prefs['activities.mapMode'] === '2d' ? '2d' : '3d';
  /** @type {Map<string, any[]>} */
  const state = new Map([['setMode', [mode]]]);

  /** @param {string} name @param {...any} args */
  export function call(name, ...args) {
    if (STATE_ORDER.includes(name)) state.set(name, args);
    if (loaded) post(name, args);
    if (!src) start();
  }

  /** Replaces what the map shows; the square first, so the 3D block is built once for the new data. */
  export function show(/** @type {{ track?: any, lines?: any[], dots?: any[], square?: number[] | null }} */ what) {
    call('setSquare', what.square ?? null);
    call('setLines', what.lines ?? []);
    call('setTrack', what.track ?? null);
    call('setDots', what.dots ?? []);
    call('setCursor', null);
  }

  /** @param {string} name @param {any[]} args */
  function post(name, args) {
    frame?.contentWindow?.postMessage({ target: 'smap', call: name, args }, location.origin);
  }

  function start() {
    const theme = $dark ? 'dark' : 'light';
    state.set('setTheme', [theme]);
    src = `/map/index.html?mode=${mode}&theme=${theme}&interactive=1&photomax=${photomax}`;
  }

  // the page reads the photo's top zoom when it loads: a new value reloads it, and onLoad replays the state
  $: photomax = photoMaxZoom($prefs['map.photoMaxZoom']);
  $: if (src && !src.endsWith(`&photomax=${photomax}`)) {
    loaded = false;
    start();
  }

  function onLoad() {
    if (!src) return;
    loaded = true;
    for (const name of STATE_ORDER) {
      const args = state.get(name);
      if (args) post(name, args);
    }
  }

  /** @param {MessageEvent} e */
  function onMessage(e) {
    if (e.origin !== location.origin || !frame || e.source !== frame.contentWindow) return;
    const event = e.data;
    if (!event || event.source !== 'smap') return;
    if (event.type === 'error') console.error('carte :', event.message);
    dispatch('event', event);
  }

  /** @param {'3d' | '2d'} value */
  function setMode(value) {
    if (value === mode) return;
    mode = value;
    setPref('activities.mapMode', value);
    call('setMode', value);
  }

  /** @param {KeyboardEvent} e */
  function key(e) {
    if (e.key === 'Escape' && expanded) {
      e.stopPropagation();
      expanded = false;
    }
  }

  $: if (loaded) call('setTheme', $dark ? 'dark' : 'light');
  $: if (preload && !src) start();
  $: if (parked) expanded = false;

  onMount(() => addEventListener('message', onMessage));
  onDestroy(() => removeEventListener('message', onMessage));
</script>

<svelte:window on:keydown={key} />
<div class="pane" class:parked class:expanded aria-hidden={parked ? 'true' : undefined}>
  {#if src}
    <iframe bind:this={frame} {src} title="Carte de l'activité" on:load={onLoad} tabindex={parked ? -1 : undefined}></iframe>
  {/if}
  <div class="bar top">
    <div class="segmented" role="radiogroup" aria-label="Vue de la carte">
      <button role="radio" aria-checked={mode === '3d'} class:on={mode === '3d'} title="Relief en bloc" on:click={() => setMode('3d')}>3D</button>
      <button role="radio" aria-checked={mode === '2d'} class:on={mode === '2d'} title="Carte à plat" on:click={() => setMode('2d')}>2D</button>
    </div>
    <span class="spacer"></span>
    <button class="round" title="Vue par défaut" aria-label="Vue par défaut" on:click={() => call('reset')}><Glyph name="center" size={20} /></button>
    <button class="round" title={expanded ? 'Quitter le plein écran' : 'Plein écran'} aria-label={expanded ? 'Quitter le plein écran' : 'Plein écran'}
      on:click={() => (expanded = !expanded)}><Glyph name={expanded ? 'fullscreenExit' : 'fullscreen'} size={20} /></button>
  </div>
  {#if $$slots.default}<div class="bar bottom"><slot /></div>{/if}
</div>

<style>
  .pane { position: relative; width: 100%; height: 100%; background: var(--surface); border-radius: 16px; overflow: hidden; }
  .pane.parked { position: fixed; left: -10000px; top: 0; width: 800px; height: 600px; visibility: hidden; }
  .pane.expanded { position: fixed; inset: 0; z-index: 60; border-radius: 0; width: auto; height: auto; }
  iframe { position: absolute; inset: 0; width: 100%; height: 100%; border: 0; display: block; }
  .bar { position: absolute; left: 10px; right: 10px; display: flex; align-items: center; gap: 8px; pointer-events: none; }
  .bar.top { top: 10px; }
  .bar.bottom { bottom: 28px; justify-content: flex-start; }
  .bar > :global(*) { pointer-events: auto; }
  .spacer { flex: 1; pointer-events: none !important; }
  /* opaque surfaces with the app's own colours: legible whatever the photo shows under them */
  .segmented, .round, .bar.bottom :global(select) {
    background: var(--surface-high); color: var(--on-surface); border: 1px solid var(--outline-variant);
    box-shadow: 0 1px 4px rgba(0, 0, 0, 0.35);
  }
  .segmented { display: inline-flex; border-radius: 20px; padding: 2px; }
  .segmented button {
    border: none; background: transparent; color: var(--on-surface); font-weight: 700; font-size: 14px; min-width: 46px;
    height: 32px; border-radius: 16px; cursor: pointer;
  }
  .segmented button.on { background: var(--primary); color: var(--on-primary); }
  .round {
    width: 38px; height: 38px; border-radius: 50%; display: inline-flex; align-items: center; justify-content: center; cursor: pointer;
  }
  .round:hover, .segmented button:not(.on):hover { filter: brightness(0.95); }
  .bar.bottom :global(select) { height: 34px; border-radius: 17px; padding: 0 12px; font-size: 13px; font-weight: 600; max-width: 100%; }
</style>
