<script>
  // The sketch editor, with the phone's and the PC's tools: pen in six colours and three widths, an eraser that
  // removes whole strokes, undo/redo, zoom (wheel, pinch) and pan (right or middle drag, two fingers). Strokes are
  // in page units, saved by the server as the shared SVG format (core/drawing.py).
  import { createEventDispatcher, onDestroy, onMount } from 'svelte';
  import Modal from '../../lib/components/Modal.svelte';
  import IconButton from '../../lib/components/IconButton.svelte';

  /** @type {import('../../lib/api.js').Stroke[]} */
  export let strokes = [];
  const dispatch = createEventDispatcher();

  const PALETTE = ['#1a1a1a', '#e53935', '#1e88e5', '#43a047', '#fb8c00', '#8e24aa'];
  const WIDTHS = [['Fin', 2.5], ['Moyen', 5], ['Épais', 10]];

  let color = PALETTE[0];
  let width = 5;
  let eraser = false;
  /** @type {import('../../lib/api.js').Stroke[][]} */
  let undoStack = [];
  /** @type {import('../../lib/api.js').Stroke[][]} */
  let redoStack = [];
  let changed = false;
  let scale = 1;
  let ox = 0;
  let oy = 0;
  /** @type {HTMLCanvasElement} */
  let canvas;
  /** @type {CanvasRenderingContext2D} */
  let ctx;
  /** @type {[number, number][] | null} */
  let live = null;
  /** @type {Map<number, { x: number, y: number }>} */
  const pointers = new Map();
  /** @type {{ x: number, y: number, ox: number, oy: number, dist: number, scale: number } | null} */
  let pan = null;
  /** @type {ResizeObserver} */
  let observer;

  onMount(() => {
    ctx = /** @type {CanvasRenderingContext2D} */ (canvas.getContext('2d'));
    observer = new ResizeObserver(() => { resize(); });
    observer.observe(canvas);
    resize();
    fit();
  });
  onDestroy(() => observer?.disconnect());

  function resize() {
    const ratio = devicePixelRatio || 1;
    canvas.width = Math.max(1, Math.round(canvas.clientWidth * ratio));
    canvas.height = Math.max(1, Math.round(canvas.clientHeight * ratio));
    draw();
  }

  function bounds() {
    if (!strokes.length) return null;
    let x0 = Infinity, y0 = Infinity, x1 = -Infinity, y1 = -Infinity;
    for (const s of strokes) for (const [x, y] of s.points) {
      x0 = Math.min(x0, x - s.width / 2); y0 = Math.min(y0, y - s.width / 2);
      x1 = Math.max(x1, x + s.width / 2); y1 = Math.max(y1, y + s.width / 2);
    }
    return [x0, y0, x1, y1];
  }

  function fit() {
    const b = bounds();
    const w = canvas.clientWidth, h = canvas.clientHeight;
    if (!b) {
      scale = 1; ox = 0; oy = 0;
    } else {
      const margin = 40;
      scale = Math.min(2, Math.max(0.1, Math.min((w - 2 * margin) / Math.max(1, b[2] - b[0]), (h - 2 * margin) / Math.max(1, b[3] - b[1]))));
      ox = (w - (b[2] - b[0]) * scale) / 2 - b[0] * scale;
      oy = (h - (b[3] - b[1]) * scale) / 2 - b[1] * scale;
    }
    draw();
  }

  /** @param {import('../../lib/api.js').Stroke} s */
  function paint(s) {
    ctx.strokeStyle = ctx.fillStyle = s.color;
    ctx.lineWidth = s.width;
    if (s.points.length === 1) {
      ctx.beginPath();
      ctx.arc(s.points[0][0], s.points[0][1], s.width / 2, 0, Math.PI * 2);
      ctx.fill();
      return;
    }
    ctx.beginPath();
    ctx.moveTo(s.points[0][0], s.points[0][1]);
    for (const [x, y] of s.points.slice(1)) ctx.lineTo(x, y);
    ctx.stroke();
  }

  function draw() {
    if (!ctx) return;
    const ratio = devicePixelRatio || 1;
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.fillStyle = '#ffffff';
    ctx.fillRect(0, 0, canvas.width, canvas.height);
    ctx.setTransform(ratio * scale, 0, 0, ratio * scale, ratio * ox, ratio * oy);
    ctx.lineCap = 'round';
    ctx.lineJoin = 'round';
    for (const s of strokes) paint(s);
    if (live) paint({ color, width, points: live });
  }

  /** @param {PointerEvent | WheelEvent} e @returns {[number, number]} */
  function world(e) {
    const box = canvas.getBoundingClientRect();
    return [(e.clientX - box.left - ox) / scale, (e.clientY - box.top - oy) / scale];
  }

  function remember() {
    undoStack = [...undoStack, strokes];
    redoStack = [];
    changed = true;
  }

  /** @param {number} x @param {number} y */
  function eraseAt(x, y) {
    const radius = 10 / scale;
    const index = strokes.findLastIndex((s) => distance(s, x, y) <= radius + s.width / 2);
    if (index < 0) return;
    remember();
    strokes = strokes.filter((_, i) => i !== index);
    draw();
  }

  /** core drawing.Stroke.distance_to @param {import('../../lib/api.js').Stroke} s @param {number} x @param {number} y */
  function distance(s, x, y) {
    const p = s.points;
    if (p.length === 1) return Math.hypot(x - p[0][0], y - p[0][1]);
    let best = Infinity;
    for (let i = 0; i + 1 < p.length; i++) {
      const [ax, ay] = p[i], [bx, by] = p[i + 1];
      const dx = bx - ax, dy = by - ay, len2 = dx * dx + dy * dy;
      const t = len2 === 0 ? 0 : Math.max(0, Math.min(1, ((x - ax) * dx + (y - ay) * dy) / len2));
      best = Math.min(best, Math.hypot(x - (ax + t * dx), y - (ay + t * dy)));
    }
    return best;
  }

  /** @param {PointerEvent} e */
  function down(e) {
    canvas.setPointerCapture(e.pointerId);
    pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
    if (pointers.size === 2 || e.button === 1 || e.button === 2) {
      live = null;
      const [a, b] = [...pointers.values()];
      pan = { x: e.clientX, y: e.clientY, ox, oy, dist: b ? Math.hypot(a.x - b.x, a.y - b.y) : 0, scale };
      draw();
      return;
    }
    if (e.button !== 0) return;
    const [x, y] = world(e);
    if (eraser) eraseAt(x, y);
    else live = [[x, y]];
    draw();
  }

  /** @param {PointerEvent} e */
  function move(e) {
    if (!pointers.has(e.pointerId)) return;
    pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
    if (pan) {
      const pts = [...pointers.values()];
      if (pts.length === 2 && pan.dist) {
        const cx = (pts[0].x + pts[1].x) / 2, cy = (pts[0].y + pts[1].y) / 2;
        const box = canvas.getBoundingClientRect();
        const factor = Math.hypot(pts[0].x - pts[1].x, pts[0].y - pts[1].y) / pan.dist;
        const next = Math.min(8, Math.max(0.1, pan.scale * factor));
        const wx = (pan.x - box.left - pan.ox) / pan.scale, wy = (pan.y - box.top - pan.oy) / pan.scale;
        scale = next;
        ox = cx - box.left - wx * next;
        oy = cy - box.top - wy * next;
      } else {
        ox = pan.ox + e.clientX - pan.x;
        oy = pan.oy + e.clientY - pan.y;
      }
      draw();
      return;
    }
    const [x, y] = world(e);
    if (eraser) eraseAt(x, y);
    else if (live) {
      const [lx, ly] = live[live.length - 1];
      if (Math.hypot(x - lx, y - ly) * scale >= 1.5) live.push([Math.round(x * 10) / 10, Math.round(y * 10) / 10]);
      draw();
    }
  }

  /** @param {PointerEvent} e */
  function up(e) {
    pointers.delete(e.pointerId);
    if (pan) {
      if (!pointers.size) pan = null;
      return;
    }
    if (live) {
      remember();
      strokes = [...strokes, { color, width, points: live }];
      live = null;
      draw();
    }
  }

  /** @param {WheelEvent} e */
  function wheel(e) {
    e.preventDefault();
    const box = canvas.getBoundingClientRect();
    const [wx, wy] = world(e);
    scale = Math.min(8, Math.max(0.1, scale * Math.exp(-e.deltaY * 0.0015)));
    ox = e.clientX - box.left - wx * scale;
    oy = e.clientY - box.top - wy * scale;
    draw();
  }

  function undo() {
    if (!undoStack.length) return;
    redoStack = [...redoStack, strokes];
    strokes = undoStack[undoStack.length - 1];
    undoStack = undoStack.slice(0, -1);
    changed = true;
    draw();
  }

  function redo() {
    if (!redoStack.length) return;
    undoStack = [...undoStack, strokes];
    strokes = redoStack[redoStack.length - 1];
    redoStack = redoStack.slice(0, -1);
    changed = true;
    draw();
  }

  /** @param {KeyboardEvent} e */
  function key(e) {
    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'z') { e.preventDefault(); e.shiftKey ? redo() : undo(); }
    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'y') { e.preventDefault(); redo(); }
  }
</script>

<svelte:window on:keydown={key} />
<Modal fullscreen title="" on:close={() => dispatch('close')}>
  <div class="tools">
    {#each PALETTE as c}
      <button class="color" class:on={!eraser && color === c} style="background: {c}" title="Couleur" aria-label="Couleur {c}"
        on:click={() => { color = c; eraser = false; }}></button>
    {/each}
    <span class="sep"></span>
    {#each WIDTHS as [label, w]}
      <button class="width" class:on={!eraser && width === w} title={String(label)} aria-label="Trait {label}" on:click={() => { width = Number(w); eraser = false; }}>
        <span style="width: {Number(w) + 2}px; height: {Number(w) + 2}px"></span>
      </button>
    {/each}
    <span class="sep"></span>
    <IconButton icon="eraser" label="Gomme" active={eraser} on:click={() => (eraser = !eraser)} />
    <IconButton icon="undo" label="Annuler le dernier trait (Ctrl+Z)" disabled={!undoStack.length} on:click={undo} />
    <IconButton icon="redo" label="Rétablir (Ctrl+Y)" disabled={!redoStack.length} on:click={redo} />
    <IconButton icon="grid" label="Tout afficher" on:click={fit} />
    <span class="grow"></span>
    <button class="btn" on:click={() => dispatch('close')}>Annuler</button>
    <button class="btn primary" on:click={() => (changed ? dispatch('save', strokes) : dispatch('close'))}>Enregistrer</button>
  </div>
  <canvas bind:this={canvas} class:erasing={eraser} on:pointerdown={down} on:pointermove={move} on:pointerup={up}
    on:pointercancel={up} on:wheel={wheel} on:contextmenu|preventDefault></canvas>
</Modal>

<style>
  .tools { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; padding: 4px 4px 10px; }
  .color { width: 28px; height: 28px; border-radius: 50%; border: 2px solid var(--outline-variant); cursor: pointer; }
  .color.on { outline: 3px solid var(--primary); outline-offset: 2px; }
  .width { width: 36px; height: 36px; border-radius: 50%; border: none; background: transparent; cursor: pointer; display: flex; align-items: center; justify-content: center; }
  .width span { display: block; border-radius: 50%; background: var(--on-surface); }
  .width.on { background: var(--secondary-container); }
  .sep { width: 1px; height: 24px; background: var(--outline-variant); margin: 0 4px; }
  .grow { flex: 1; }
  canvas { flex: 1; width: 100%; min-height: 0; border-radius: 12px; touch-action: none; cursor: crosshair; background: #fff; border: 1px solid var(--outline-variant); }
  canvas.erasing { cursor: cell; }
</style>
