<script>
  // #/activities → the list; #/activities/<id> → one activity (id: the note id, as the agenda links it, or the
  // file name of a conflict copy). The map frame is the page's, kept across activities and while the list shows:
  // opening an activity only sends it the new track.
  import { tick } from 'svelte';
  import { go } from '../../lib/router.js';
  import { showSnack } from '../../lib/ui.js';
  import ActivityList from './ActivityList.svelte';
  import ActivityDetail from './ActivityDetail.svelte';
  import ImportDialog from './ImportDialog.svelte';
  import MapPane from './MapPane.svelte';
  import { follow } from './jobs.js';
  import { skiLines } from './activities.js';

  /** @type {import('../../lib/router.js').Route} */
  export let route;

  $: id = route.rest[0] || null;
  /** @type {HTMLElement} */
  let root;
  $: if (root && id !== undefined) root.scrollTop = 0;  // narrow screens: each activity opens at its top
  /** @type {MapPane} */
  let pane;
  /** @type {any} the map part of the open activity's analysis */
  let mapData = null;
  let preload = false;
  /** @type {'both' | 'down' | 'all'} */
  let skiMode = 'both';
  /** @type {File[] | null} */
  let importing = null;

  let trackExpected = false;
  $: if (!id) {
    mapData = null;
    trackExpected = false;
  }
  $: hasMap = !!(id && (mapData || trackExpected));

  /** The activity is known: room is made for its map at once, and the previous track goes. @param {boolean} has */
  function expectTrack(has) {
    if (has && !trackExpected && !mapData) pane.show({});
    trackExpected = has;
  }

  /** @param {any} analysis */
  async function showAnalysis(analysis) {
    mapData = analysis?.map ?? null;
    trackExpected = !!mapData;
    skiMode = 'both';
    await tick();
    // the map page shares this page's main thread (same site): let the figures and charts paint first
    await new Promise((resolve) => requestAnimationFrame(() => setTimeout(resolve, 0)));
    if (!mapData || mapData !== analysis?.map) return;
    if (mapData.kind) pane.show({ lines: skiLines(mapData, skiMode), dots: mapData.dots, square: mapData.square });
    else pane.show({ track: { coords: mapData.lon.map((/** @type {number} */ lon, /** @type {number} */ i) => [lon, mapData.lat[i]]), speeds: mapData.speeds, avg: mapData.avg }, dots: mapData.dots });
  }

  /** @type {{ lon: number, lat: number } | null | undefined} the cursor to send at the next frame */
  let nextCursor;
  let cursorFrame = 0;
  /** At most one move a frame: a fast mouse fires several pointer events per frame, and each one redraws the map,
   * which shares this page's main thread. @param {{ lon: number, lat: number } | null} at */
  function cursor(at) {
    nextCursor = at;
    if (cursorFrame) return;
    cursorFrame = requestAnimationFrame(() => {
      cursorFrame = 0;
      const to = nextCursor;
      if (pane && mapData) pane.call('setCursor', to ? to.lon : null, to ? to.lat : null);
    });
  }

  function changeSkiMode() {
    if (mapData?.kind) pane.call('setLines', skiLines(mapData, skiMode));
  }

  /** @param {import('./api.js').Job} started */
  function imported(started) {
    importing = null;
    follow(started, (done) => {
      const ok = done.results.filter((r) => !r.error);
      const problems = done.results.filter((r) => r.error || r.warning).map((r) => `${r.name} : ${r.error || r.warning}`);
      const replaced = ok.filter((r) => r.replaced).length;
      const text = (ok.length === 1 ? `« ${ok[0].title} » ${replaced ? 'remplacée' : 'importée'}` : `${ok.length} activités importées`)
        + (ok.length > 1 && replaced ? ` (${replaced} remplacée${replaced > 1 ? 's' : ''})` : '');
      showSnack(problems.length ? `${text}. ${problems.join(' · ')}` : text, { error: problems.length > 0 || !!done.error });
      if (ok.length === 1 && ok[0].id) go(['activities', ok[0].id]);
    });
  }
</script>

<div class="root" bind:this={root} class:detail={!!id} class:with-map={hasMap}>
  <section class="main">
    {#if id}
      {#key id}
        <ActivityDetail {id} on:track={(e) => expectTrack(e.detail)} on:analysis={(e) => showAnalysis(e.detail)} on:cursor={(e) => cursor(e.detail)} />
      {/key}
    {:else}
      <ActivityList on:import={(e) => (importing = e.detail)} on:loaded={() => setTimeout(() => (preload = true), 800)} />
    {/if}
  </section>
  <div class="map-area">
    <MapPane bind:this={pane} parked={!hasMap} {preload}>
      {#if mapData?.kind}
        <select bind:value={skiMode} on:change={changeSkiMode} aria-label="Tracé affiché">
          <option value="both">Descentes + montées (en bleu)</option>
          <option value="down">Descentes seulement</option>
          <option value="all">Tout le tracé</option>
        </select>
      {/if}
    </MapPane>
  </div>
</div>

{#if importing}
  <ImportDialog files={importing} on:close={() => (importing = null)} on:started={(e) => imported(e.detail)} />
{/if}

<style>
  .root { height: 100%; display: flex; min-height: 0; }
  .main { flex: 1; min-width: 0; min-height: 0; }
  .map-area { display: none; }
  .root.with-map .map-area { display: block; flex: 1.35; min-width: 0; padding: 12px 12px 12px 0; }
  .root.with-map .main { flex: 0 0 min(500px, 42%); }
  .root.detail:not(.with-map) .main { max-width: 860px; margin: 0 auto; }
  /* the frame must exist even when not shown (parked off screen), so the area keeps it in the document */
  .root:not(.with-map) .map-area { display: block; position: absolute; width: 0; height: 0; overflow: hidden; }

  /* narrow screens: one scrolling column, the map between the header and the figures */
  @media (max-width: 899px) {
    .root.with-map { flex-direction: column; overflow-y: auto; display: flex; }
    .root.with-map .main { display: contents; }
    .root.with-map .main > :global(.detail) { display: contents; }
    .root.with-map :global(.detail-head) { order: 1; position: sticky; top: 0; z-index: 5; background: var(--background); }
    .root.with-map .map-area { order: 2; flex: none; height: min(52vh, 480px); padding: 0 12px 10px; }
    .root.with-map :global(.detail-body) { order: 3; overflow: visible; flex: none; }
  }
</style>
