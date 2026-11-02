<script>
  // A filled line chart over the distance, its extremes written above (as the Windows Chart). Hovering it (mouse,
  // or a finger dragged sideways) shows the value there and dispatches `hover` with the point's index, null when it
  // leaves: the page moves the map's cursor there.
  import { createEventDispatcher } from 'svelte';
  import { fmtDistance, nearest } from './activities.js';

  export let title = '';
  /** @type {number[]} metres since the start */
  export let xs;
  /** @type {(number | null)[]} */
  export let ys;
  export let color = '#2E7DF6';
  /** Labels of the low and high ends, e.g. ["min 1 040 m", "max 1 300 m"]. */
  export let low = '';
  export let high = '';
  /** @type {(y: number) => string} the hovered value */
  export let format = (y) => String(Math.round(y));

  const dispatch = createEventDispatcher();
  const H = 120;
  let width = 600;
  /** @type {number | null} */
  let hover = null;
  const gid = `g${Math.random().toString(36).slice(2, 8)}`;

  $: pts = xs.map((x, i) => [x, ys[i]]).filter((p) => p[1] != null);
  $: lo = Math.min(...pts.map((p) => /** @type {number} */ (p[1])));
  $: hi = Math.max(...pts.map((p) => /** @type {number} */ (p[1])));
  $: x0 = xs[0] ?? 0;
  $: spanX = Math.max((xs[xs.length - 1] ?? 0) - x0, 1e-6);
  $: spanY = Math.max(hi - lo, 1e-6);
  $: px = (/** @type {number} */ x) => ((x - x0) / spanX) * width;
  $: py = (/** @type {number} */ y) => H - ((y - lo) / spanY) * H * 0.9 - H * 0.05;
  $: line = pts.map((p, i) => `${i ? 'L' : 'M'}${px(p[0]).toFixed(1)},${py(/** @type {number} */ (p[1])).toFixed(1)}`).join('');
  $: area = pts.length ? `${line}L${px(pts[pts.length - 1][0]).toFixed(1)},${H}L${px(pts[0][0]).toFixed(1)},${H}Z` : '';

  /** @param {PointerEvent} e */
  function move(e) {
    const rect = /** @type {SVGElement} */ (e.currentTarget).getBoundingClientRect();
    const x = x0 + Math.max(0, Math.min(1, (e.clientX - rect.left) / rect.width)) * spanX;
    let i = nearest(xs, x);
    if (ys[i] == null) {  // a gap in the series (heart rate): the nearest point that has a value
      let best = -1;
      for (let k = 0; k < xs.length; k++) if (ys[k] != null && (best < 0 || Math.abs(xs[k] - x) < Math.abs(xs[best] - x))) best = k;
      if (best < 0) return;
      i = best;
    }
    if (i !== hover) {
      hover = i;
      dispatch('hover', i);
    }
  }

  function leave() {
    hover = null;
    dispatch('hover', null);
  }
</script>

<div class="chart">
  <div class="head">
    <span class="title">{title}</span>
    {#if hover != null && ys[hover] != null}
      <span class="value">{format(/** @type {number} */ (ys[hover]))} · {fmtDistance(xs[hover])}</span>
    {:else}
      <span class="ends">{low}&nbsp;&nbsp; {high}</span>
    {/if}
  </div>
  <div class="plot" bind:clientWidth={width}>
    {#if pts.length >= 2}
      <svg {width} height={H} viewBox="0 0 {width} {H}" role="img" aria-label={title}
        on:pointermove={move} on:pointerdown={move} on:pointerleave={leave} on:pointercancel={leave}>
        <defs>
          <linearGradient id={gid} x1="0" y1="0" x2="0" y2="1">
            <stop offset="0" style="stop-color: {color}; stop-opacity: 0.35" />
            <stop offset="1" style="stop-color: {color}; stop-opacity: 0.02" />
          </linearGradient>
        </defs>
        <path d={area} fill="url(#{gid})" />
        <path d={line} fill="none" style="stroke: {color}" stroke-width="2" stroke-linejoin="round" />
        {#if hover != null && ys[hover] != null}
          <line x1={px(xs[hover])} x2={px(xs[hover])} y1="0" y2={H} class="cursor" />
          <circle cx={px(xs[hover])} cy={py(/** @type {number} */ (ys[hover]))} r="4.5" style="fill: {color}" class="dot" />
        {/if}
      </svg>
    {/if}
  </div>
  <div class="axis"><span>0 km</span><span>{fmtDistance(xs[xs.length - 1] ?? 0)}</span></div>
</div>

<style>
  .chart { display: flex; flex-direction: column; gap: 4px; }
  .head { display: flex; align-items: baseline; justify-content: space-between; gap: 8px; flex-wrap: wrap; }
  .title { color: var(--primary); font-weight: 600; }
  .ends, .value, .axis { color: var(--on-surface-variant); font-size: 12px; }
  .value { color: var(--on-surface); font-weight: 600; font-variant-numeric: tabular-nums; }
  .plot { height: 120px; touch-action: pan-y; }
  svg { display: block; cursor: crosshair; }
  .cursor { stroke: var(--on-surface-variant); stroke-width: 1; stroke-dasharray: 3 3; }
  .dot { stroke: var(--background); stroke-width: 2; }
  .axis { display: flex; justify-content: space-between; }
</style>
