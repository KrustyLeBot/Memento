// Activity map shared by the Android (WebView) and Windows (Qt WebEngine) apps: the IGN aerial photo on the DEM, in 3D as a
// square block cut out of the terrain, or flat in 2D. The host serves every URL under <base> (tiles, peaks, buildings) and
// drives the page through window.SMap; the page reports back through hostPost (README, activities: the map).
'use strict';
(function () {
  const params = new URLSearchParams(location.search);
  const BASE = new URL('../', location.href).href;  // the page lives at <base>map/index.html
  const PITCH = 65;          // the block seen from the side, on a landscape view
  const PITCH_TALL = 45;     // on a tall narrow view, so the whole square's surface stays in sight
  const FOLLOW_PITCH = 55;
  const FOLLOW_WIDTH_M = 1750;  // what the screen's width spans around the position while following, in 3D
  // attrib: the tile server's own credit (e.g. the photo abroad), passed by the host; trails=1: its hiking-trails overlay
  const EXTRA_ATTRIBUTION = (params.get('attrib') || '').slice(0, 300);
  const ATTRIBUTION = '© IGN · © OpenStreetMap · Terrarium' + (EXTRA_ATTRIBUTION ? ' · ' + EXTRA_ATTRIBUTION : '');
  const TRAILS = params.get('trails') === '1';
  const TRAIL_LAYERS = ['trails-halo', 'trails-route', 'trails-path'];
  // Buildings (3D only): IGN BD TOPO footprints extruded, walls a light stone, roofs coloured from the aerial photo.
  const BLD_Z = 15;            // the host serves one z15 cell (850 m wide in the Alps) per request
  const BLD_SAMPLE_Z = 17;     // the photo zoom roofs are sampled at (1.2 m a pixel)
  const BLD_CELLS = 16;        // drawn at most, the nearest to the camera's centre
  const BLD_KEEP = 40;         // kept in memory (least recently used out)
  const BLD_MAX = 15000;       // buildings drawn at most: a phone stays fluid
  const BLD_MIN_ZOOM = 14.5;   // shown from this zoom, or once the screen's width spans BLD_SPAN_M or less
  const BLD_SPAN_M = 2000;
  const BLD_WALL = '#efe5d3', BLD_ROOF = '#9f978c', ROOF_CAP = 0.4;
  // MapLibre's extrusion lighting washes colours out: the photo's roof colour is pre-saturated, then darkened
  const ROOF_SATURATION = 1.7, ROOF_BRIGHTNESS = 0.85;
  // The theme only dresses what surrounds the photo; the photo itself is never filtered, in any theme. Lines on it
  // are sandwiched (white halo, dark casing, colour) so they read on snow, forest, rock and roofs alike.
  const THEMES = {
    light: { bg: '#ffffff', band: '#d6d8da', frame: '#3a3f45' },
    dark: { bg: '#18191c', band: '#43474e', frame: '#c9ccd1' },
  };
  const HALO = 'rgba(255,255,255,0.85)', CASING = 'rgba(0,0,0,0.75)';
  // the host's "zoom maximal de la photo" setting (14..19, default 17): MapLibre enlarges that level beyond it
  const PHOTO_MAX_Z = Math.min(19, Math.max(14, Math.round(+params.get('photomax')) || 17));
  const ORTHO = { type: 'raster', tiles: ['ortho://{z}/{x}/{y}'], tileSize: 256, maxzoom: PHOTO_MAX_Z };
  const EMPTY = { type: 'FeatureCollection', features: [] };

  let mode = params.get('mode') === '2d' ? '2d' : '3d';
  let theme = params.get('theme') === 'dark' ? 'dark' : 'light';
  const interactive = params.get('interactive') !== '0';
  let exag = 1.5;

  let SQ = null;            // current square [w,s,e,n]; the DEM protocol reads it, so it is set before a rebuild
  let built3d = undefined;  // the square the 3D sources were built for (undefined: not built)
  let relief = null;        // {sq, min, max, centre} in metres for SQ, once measured (3D only; for the camera)
  let fitPending = null;    // a 3D fit waiting for the relief ({animated, done})
  let shown = false;        // nothing is fetched until there is something to show (the page is preloaded empty)
  let track = null, lines = [], route = null, dots = [];
  let explicitSquare = null, liveSquareKm = null, liveSq = null;
  let pos = null, follow = false, followFitted = false, banner = null, bannerTimer = 0;
  const live = { coords: [], speeds: [], smooth: [], sum: 0, count: 0, colouredAvg: 0, added: [], full: false, flushedAt: 0, pending: 0 };

  // ---------------------------------------------------------------- API stubs

  // window.SMap exists from the first line: calls made before the style is loaded (or on a machine without
  // WebGL, where the map never comes) are queued, then replayed in order.
  const API = ['setTrack', 'setLines', 'setRoute', 'setLive', 'appendLive', 'setDots', 'setPos', 'locFail', 'setFollow',
    'setLiveSquare', 'setSquare', 'setMode', 'setExag', 'setTheme', 'setCursor', 'fit', 'reset'];
  let ready = false, call = null;
  const queue = [];
  window.SMap = {};
  API.forEach((name) => {
    window.SMap[name] = (...args) => { if (ready) call(name, args); else queue.push([name, args]); };
  });

  // ---------------------------------------------------------------- host bridge

  const outbox = [];
  let bridge = 'ready';
  window.__smapEvents = [];  // read by the dev server's tests
  function hostPost(obj) {
    const text = JSON.stringify(obj);
    window.__smapEvents.push(obj);
    if (bridge !== 'ready') { outbox.push(text); return; }
    const host = window.SMapHost;
    if (host && typeof host.post === 'function') host.post(text);
    else if (framed) window.parent.postMessage({ source: 'smap', ...obj }, location.origin);
  }
  // Web: the page is an <iframe> of the same site (webapp, Activités). Events go to the parent; the parent calls
  // the API with postMessage({ target: 'smap', call, args }). Only this very origin is heard. Android and Windows
  // load the page at the top level, where none of this applies.
  const framed = window.parent !== window;
  if (framed) {
    window.addEventListener('message', (e) => {
      const d = e.data;
      if (e.origin !== location.origin || e.source !== window.parent || !d || d.target !== 'smap') return;
      if (API.includes(d.call)) window.SMap[d.call](...(Array.isArray(d.args) ? d.args : []));
    });
  }
  function connectHost() {
    if (window.SMapHost || !(window.qt && window.qt.webChannelTransport)) return;
    bridge = 'waiting';  // Windows: the QWebChannel object arrives asynchronously
    const script = document.createElement('script');
    script.src = 'qrc:///qtwebchannel/qwebchannel.js';
    script.onload = () => new window.QWebChannel(window.qt.webChannelTransport, (channel) => {
      window.SMapHost = channel.objects.SMapHost;
      bridge = 'ready';
      outbox.splice(0).forEach((text) => window.SMapHost.post(text));
    });
    script.onerror = () => console.error('smap: qwebchannel.js could not be loaded, events stay in the page');
    document.head.appendChild(script);
  }
  connectHost();

  let lastError = '', lastErrorAt = 0;
  function report(message) {
    message = String(message || 'erreur inconnue');
    console.error('smap:', message);
    const now = Date.now();
    if (message === lastError && now - lastErrorAt < 10000) return;  // a broken frame loop must not flood the host
    lastError = message; lastErrorAt = now;
    hostPost({ type: 'error', message });
  }
  window.addEventListener('error', (e) => report(e.message));
  window.addEventListener('unhandledrejection', (e) => report(e.reason && (e.reason.message || e.reason)));

  // ---------------------------------------------------------------- geometry

  /** Square of the 3D block around a bbox. Same rule on Android and in core/mapdata.py: centre = bbox centre;
   *  half side = max(width, height) / 2 × 1.25, at least 500 m. */
  function squareOf(w, s, e, n) {
    const lonC = (w + e) / 2, latC = (s + n) / 2;
    const kx = 111320 * Math.cos(latC * Math.PI / 180);
    const half = Math.max(Math.max((e - w) * kx, (n - s) * 110540) / 2 * 1.25, 500);
    return [lonC - half / kx, latC - half / 110540, lonC + half / kx, latC + half / 110540];
  }
  function squareAround(lon, lat, km) {
    const kx = 111320 * Math.cos(lat * Math.PI / 180), half = km * 500;
    return [lon - half / kx, lat - half / 110540, lon + half / kx, lat + half / 110540];
  }
  function bboxOf(lists) {
    let w = Infinity, s = Infinity, e = -Infinity, n = -Infinity;
    for (const coords of lists) for (const c of coords) {
      if (c[0] < w) w = c[0]; if (c[0] > e) e = c[0];
      if (c[1] < s) s = c[1]; if (c[1] > n) n = c[1];
    }
    return w <= e ? [w, s, e, n] : null;
  }
  function sameBox(a, b) {
    if (!a || !b) return a === b;
    return a.every((v, i) => Math.abs(v - b[i]) < 1e-6);  // ~0.1 m: the hosts round the square they send
  }
  function grow(b, metres) {
    const kx = 111320 * Math.cos((b[1] + b[3]) / 2 * Math.PI / 180);
    return [b[0] - metres / kx, b[1] - metres / 110540, b[2] + metres / kx, b[3] + metres / 110540];
  }
  const ring = (b) => [[b[0], b[1]], [b[2], b[1]], [b[2], b[3]], [b[0], b[3]], [b[0], b[1]]];
  const hole = (b) => ring(b).reverse();  // opposite winding, or MapLibre reads it as a second polygon
  const mercX = (lon) => (lon + 180) / 360;
  const mercY = (lat) => { const r = lat * Math.PI / 180; return (1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2; };
  const lineFeature = (coords) => ({ type: 'Feature', properties: {}, geometry: { type: 'LineString', coordinates: coords } });

  // ---------------------------------------------------------------- DEM cut to the square

  // Each DEM tile is decoded and forced down to a fixed floor of -500 m outside the square (+3 px), so the terrain
  // mesh itself has walls, hidden correctly by the depth buffer. Tiles wholly inside are passed through untouched.
  // A cut tile goes back to MapLibre as an ImageBitmap: no PNG encode, and MapLibre does not decode it again.
  const FLOOR = -500;
  const FR = (FLOOR + 32768) >> 8, FG = (FLOOR + 32768) & 255;  // Terrarium 126, 12, 0
  function canvasOf(w, h) {
    if (typeof OffscreenCanvas !== 'undefined') return new OffscreenCanvas(w, h);
    const c = document.createElement('canvas'); c.width = w; c.height = h; return c;
  }
  const floorImages = {};
  function floorTile(size) {
    if (!floorImages[size]) {
      const img = new ImageData(size, size), d = img.data;
      for (let i = 0; i < d.length; i += 4) { d[i] = FR; d[i + 1] = FG; d[i + 2] = 0; d[i + 3] = 255; }
      floorImages[size] = img;
    }
    return createImageBitmap(floorImages[size]);  // a new bitmap each time: MapLibre transfers it to its worker
  }
  const terrarium = (d, i) => d[i] * 256 + d[i + 1] + d[i + 2] / 256 - 32768;
  async function demPixels(z, x, y, signal) {
    const res = await fetch(`${BASE}t/dem/${z}/${x}/${y}`, { signal });
    if (!res.ok) throw new Error(`dem ${res.status}`);
    return res.arrayBuffer();
  }
  async function decode(raw) {
    const bmp = await createImageBitmap(new Blob([raw], { type: 'image/png' }), { premultiplyAlpha: 'none', colorSpaceConversion: 'none' });
    const c = canvasOf(bmp.width, bmp.height), ctx = c.getContext('2d', { willReadFrequently: true });
    ctx.drawImage(bmp, 0, 0); bmp.close();
    return ctx.getImageData(0, 0, c.width, c.height);
  }
  /** Lowest, highest and centre elevation of a square, from the few coarse DEM tiles that cover it (the ones
   *  MapLibre asks for first anyway). Only the camera needs it, to frame the whole block. */
  async function measure(sq) {
    let z = 12;
    const span = (zz) => (Math.floor(mercX(sq[2]) * 2 ** zz) - Math.floor(mercX(sq[0]) * 2 ** zz) + 1)
      * (Math.floor(mercY(sq[1]) * 2 ** zz) - Math.floor(mercY(sq[3]) * 2 ** zz) + 1);
    while (z > 0 && span(z) > 4) z--;
    const n = 2 ** z, jobs = [];
    for (let x = Math.floor(mercX(sq[0]) * n); x <= Math.floor(mercX(sq[2]) * n); x++) {
      for (let y = Math.floor(mercY(sq[3]) * n); y <= Math.floor(mercY(sq[1]) * n); y++) jobs.push([x, y]);
    }
    let min = Infinity, max = -Infinity, centre = null;
    const cx = mercX((sq[0] + sq[2]) / 2) * n, cy = mercY((sq[1] + sq[3]) / 2) * n;
    await Promise.all(jobs.map(async ([x, y]) => {
      const img = await decode(await demPixels(z, x, y));
      const W = img.width, H = img.height, d = img.data;
      const c0 = Math.max(0, Math.floor((mercX(sq[0]) * n - x) * W)), c1 = Math.min(W - 1, Math.floor((mercX(sq[2]) * n - x) * W));
      const r0 = Math.max(0, Math.floor((mercY(sq[3]) * n - y) * H)), r1 = Math.min(H - 1, Math.floor((mercY(sq[1]) * n - y) * H));
      for (let r = r0; r <= r1; r++) for (let col = c0; col <= c1; col++) {
        const v = terrarium(d, (r * W + col) * 4);
        if (v < min) min = v; if (v > max) max = v;
      }
      if (Math.floor(cx) === x && Math.floor(cy) === y) {
        centre = terrarium(d, (Math.floor((cy - y) * H) * W + Math.floor((cx - x) * W)) * 4);
      }
    }));
    if (min > max) throw new Error('relief: no DEM pixel inside the square');
    return { sq, min, max, centre: centre == null ? (min + max) / 2 : centre };
  }
  // IGN's photo stops a few kilometres off the coast: from z13 or so a tile past it is white, wholly or in steps along
  // the edge (or missing, and the host's OpenStreetMap stand-in is its pale water), while z12 and below still show
  // the sea. Those pixels show the parent tile through (recursively), so the sea stays one blue. Snow is rarely that
  // white, and where it is, its parent is snow too; abroad, OpenStreetMap's land stays as it is.
  // z13 and up only: below, IGN still has the sea, and white is clouds or surf. A hole smaller than 1 % of the tile
  // (a white roof, a wave) is left alone.
  const WHITE = 235, WHITE_CHECK_PX = 64, WHITE_MIN_Z = 12, WHITE_MIN_SHARE = 0.01;
  const OSM_WATER = [170, 211, 223];
  const seaGap = (d, i) => (d[i] >= WHITE && d[i + 1] >= WHITE && d[i + 2] >= WHITE) ||
    (Math.abs(d[i] - OSM_WATER[0]) <= 4 && Math.abs(d[i + 1] - OSM_WATER[1]) <= 4 && Math.abs(d[i + 2] - OSM_WATER[2]) <= 4);
  const composedParents = new Map();  // "z/x/y" -> Promise<ImageBitmap|null>, a few, least recently used out
  async function photoBytes(z, x, y, signal) {
    const res = await fetch(`${BASE}t/ortho/${z}/${x}/${y}`, { signal });
    if (!res.ok) throw new Error(`photo ${z}/${x}/${y}: HTTP ${res.status}`);
    return res.arrayBuffer();
  }
  function hasWhite(bmp) {
    const c = canvasOf(WHITE_CHECK_PX, WHITE_CHECK_PX), g = c.getContext('2d', { willReadFrequently: true });
    g.imageSmoothingEnabled = false;  // nearest pixels: a white step is not averaged away
    g.drawImage(bmp, 0, 0, WHITE_CHECK_PX, WHITE_CHECK_PX);
    const d = g.getImageData(0, 0, WHITE_CHECK_PX, WHITE_CHECK_PX).data;
    let n = 0;
    for (let i = 0; i < d.length; i += 4) if (seaGap(d, i)) n++;
    return n >= WHITE_CHECK_PX * WHITE_CHECK_PX * WHITE_MIN_SHARE;
  }
  /** The tile with its white pixels replaced by the parent's (null: no white, use the file as it is). */
  async function composed(z, x, y, bmp, signal) {
    if (z <= WHITE_MIN_Z || !hasWhite(bmp)) return null;
    const parent = await parentPhoto(z - 1, x >> 1, y >> 1, signal);
    if (!parent) return null;
    const W = bmp.width, H = bmp.height, out = canvasOf(W, H), g = out.getContext('2d');
    g.drawImage(parent, (x & 1) * parent.width / 2, (y & 1) * parent.height / 2, parent.width / 2, parent.height / 2, 0, 0, W, H);
    const top = canvasOf(W, H), tg = top.getContext('2d', { willReadFrequently: true });
    tg.drawImage(bmp, 0, 0);
    const img = tg.getImageData(0, 0, W, H), d = img.data, mask = new Uint8Array(W * H);
    for (let p = 0; p < mask.length; p++) mask[p] = seaGap(d, p * 4) ? 1 : 0;
    // one more pixel around each hole: the JPEG's ringing along the white edge is pale, not white
    for (let r = 0; r < H; r++) for (let c = 0; c < W; c++) {
      if (!mask[r * W + c]) continue;
      for (let rr = Math.max(0, r - 1); rr <= Math.min(H - 1, r + 1); rr++) {
        for (let cc = Math.max(0, c - 1); cc <= Math.min(W - 1, c + 1); cc++) d[(rr * W + cc) * 4 + 3] = 0;
      }
    }
    tg.putImageData(img, 0, 0);
    g.drawImage(top, 0, 0);
    return out;
  }
  function parentPhoto(z, x, y, signal) {
    const key = `${z}/${x}/${y}`;
    let p = composedParents.get(key);
    if (p) { composedParents.delete(key); composedParents.set(key, p); return p; }
    p = (async () => {
      try {
        const bmp = await createImageBitmap(new Blob([await photoBytes(z, x, y, signal)]));
        const c = await composed(z, x, y, bmp, signal);
        if (!c) return bmp;
        bmp.close();
        return createImageBitmap(c);
      } catch (e) {
        composedParents.delete(key);  // an abort or a network error: asked again next time
        return null;
      }
    })();
    composedParents.set(key, p);
    while (composedParents.size > 24) {
      const old = composedParents.keys().next().value;
      composedParents.get(old).then((b) => b && b.close && b.close());
      composedParents.delete(old);
    }
    return p;
  }
  maplibregl.addProtocol('ortho', async (req, abort) => {
    const m = /^ortho:\/\/(\d+)\/(\d+)\/(\d+)/.exec(req.url);
    const z = +m[1], x = +m[2], y = +m[3];
    const raw = await photoBytes(z, x, y, abort.signal);
    if (z <= WHITE_MIN_Z) return { data: raw };
    // MapLibre 4.7 takes an ImageBitmap as it is: the tile is decoded once (off the main thread), never re-encoded
    const bmp = await createImageBitmap(new Blob([raw]));
    const c = await composed(z, x, y, bmp, abort.signal);
    if (!c) return { data: bmp };
    bmp.close();
    return { data: await createImageBitmap(c) };
  });
  maplibregl.addProtocol('dem', async (req, abort) => {
    const m = /^dem:\/\/(\d+)\/(\d+)\/(\d+)/.exec(req.url);
    const z = +m[1], x = +m[2], y = +m[3];
    const sq = built3d, W = 256, H = 256, scale = 2 ** z;  // Terrarium tiles are 256 px (checked after decoding)
    if (!sq) return { data: await demPixels(z, x, y, abort.signal) };
    const x0 = (mercX(sq[0]) * scale - x) * W - 3, x1 = (mercX(sq[2]) * scale - x) * W + 3;
    const y0 = (mercY(sq[3]) * scale - y) * H - 3, y1 = (mercY(sq[1]) * scale - y) * H + 3;
    if (x1 <= 0 || y1 <= 0 || x0 >= W || y0 >= H) return { data: await floorTile(W) };  // nothing to fetch
    const raw = await demPixels(z, x, y, abort.signal);
    if (x0 <= 0 && y0 <= 0 && x1 >= W && y1 >= H) return { data: raw };
    const img = await decode(raw), d = img.data;
    if (img.width !== W || img.height !== H) throw new Error(`dem tile of ${img.width}x${img.height} px`);
    const cx0 = Math.max(0, Math.ceil(x0 - 0.5)), cx1 = Math.min(W, Math.floor(x1 - 0.5) + 1);
    const cy0 = Math.max(0, Math.ceil(y0 - 0.5)), cy1 = Math.min(H, Math.floor(y1 - 0.5) + 1);
    for (let r = 0; r < H; r++) {
      const outRow = r < cy0 || r >= cy1;
      for (let col = 0; col < W; col++) {
        if (outRow || col < cx0 || col >= cx1) {
          const i = (r * W + col) * 4;
          d[i] = FR; d[i + 1] = FG; d[i + 2] = 0; d[i + 3] = 255;
        }
      }
    }
    return { data: await createImageBitmap(img) };
  });

  // ---------------------------------------------------------------- map and style

  const T = () => THEMES[theme];
  const rasterPaint = () => ({ 'raster-fade-duration': 0 });
  const lineLayout = { 'line-cap': 'round', 'line-join': 'round' };
  const solid = (c) => ['interpolate', ['linear'], ['line-progress'], 0, c, 1, c];
  // Small black chevrons spaced along a line show which way the track was travelled.
  const arrows = (id, source) => ({
    id, type: 'symbol', source,
    layout: { 'symbol-placement': 'line', 'symbol-spacing': 140, 'icon-image': 'smap-arrow', 'icon-size': 0.8,
              'icon-rotation-alignment': 'map', 'icon-allow-overlap': true, 'icon-ignore-placement': true },
  });
  // Images drawn to be read back (getImageData) are drawn on the CPU: reading a GPU canvas waits for every frame the GPU
  // still has to draw, seconds each for the 3D block on a slow one (the race's peaks took 13 s on a software renderer)
  const READ_BACK = { willReadFrequently: true };
  // Pointing east: line placement turns the icon along the line, in the line's direction.
  function arrowImage() {
    const n = 48, c = document.createElement('canvas');
    c.width = c.height = n;
    const g = c.getContext('2d', READ_BACK);
    g.lineCap = 'round'; g.lineJoin = 'round';
    const chevron = () => { g.beginPath(); g.moveTo(15, 12); g.lineTo(33, 24); g.lineTo(15, 36); g.stroke(); };
    g.strokeStyle = '#ffffff'; g.lineWidth = 13; chevron();
    g.strokeStyle = '#111111'; g.lineWidth = 7; chevron();
    return g.getImageData(0, 0, n, n);
  }
  function style() {
    const t = T(), geo = (extra) => Object.assign({ type: 'geojson', data: EMPTY }, extra || {});
    return {
      version: 8,
      sources: {
        ortho: ORTHO,
        band: geo(), mask: geo(), frame: geo(), route: geo(), lines: geo(), track: geo({ lineMetrics: true }),
        live: geo(), dots: geo(),
        bld: geo({ maxzoom: 16 }),
        peaks: geo(),
        // the host answers 204 where a tile has no trail (or is still being built): an empty tile, no error
        ...(TRAILS ? { trails: { type: 'vector', tiles: [`${BASE}trails/{z}/{x}/{y}`], minzoom: 10, maxzoom: 15 } } : {}),
      },
      // only the buildings are lit (the terrain and the photo are not): from behind the camera, a little to the left
      light: { anchor: 'viewport', position: [1.5, 200, 35], color: '#ffffff', intensity: 0.35 },
      layers: [
        { id: 'bg', type: 'background', paint: { 'background-color': t.bg } },
        { id: 'ortho', type: 'raster', source: 'ortho', layout: { visibility: 'none' }, paint: rasterPaint() },
        // hiking trails on the photo (under the 3D block's surround, so only the block shows them): waymarked routes
        // solid, other paths dashed, purple on a white halo so they read on snow and forest without looking like a track
        ...(TRAILS ? [
          { id: 'trails-halo', type: 'line', source: 'trails', 'source-layer': 'trails', layout: { visibility: 'none', 'line-join': 'round', 'line-cap': 'round' },
            paint: { 'line-color': HALO, 'line-width': ['interpolate', ['linear'], ['zoom'], 10, 2.5, 15, 5] } },
          { id: 'trails-route', type: 'line', source: 'trails', 'source-layer': 'trails', filter: ['==', ['get', 'kind'], 'route'],
            layout: { visibility: 'none', 'line-join': 'round', 'line-cap': 'round' },
            paint: { 'line-color': '#8e24aa', 'line-width': ['interpolate', ['linear'], ['zoom'], 10, 1.2, 15, 2.6] } },
          { id: 'trails-path', type: 'line', source: 'trails', 'source-layer': 'trails', filter: ['!=', ['get', 'kind'], 'route'],
            layout: { visibility: 'none', 'line-join': 'round' },
            paint: { 'line-color': '#8e24aa', 'line-width': ['interpolate', ['linear'], ['zoom'], 13, 1, 15, 2], 'line-dasharray': [2, 1.5] } },
        ] : []),
        { id: 'band', type: 'fill', source: 'band', paint: { 'fill-color': t.band, 'fill-antialias': false } },
        { id: 'mask', type: 'fill', source: 'mask', paint: { 'fill-color': t.bg, 'fill-antialias': false } },
        { id: 'frame', type: 'line', source: 'frame', paint: { 'line-color': t.frame, 'line-width': 1.5 } },
        // the route to follow: white on a dark casing, under the coloured trail that runs along it
        { id: 'route-case', type: 'line', source: 'route', layout: lineLayout, paint: { 'line-color': CASING, 'line-width': 11 } },
        { id: 'route', type: 'line', source: 'route', layout: lineLayout, paint: { 'line-color': '#ffffff', 'line-width': 6.5, 'line-opacity': 0.9 } },
        { id: 'lines-halo', type: 'line', source: 'lines', layout: lineLayout, paint: { 'line-color': HALO, 'line-width': 10.5 } },
        { id: 'lines-case', type: 'line', source: 'lines', layout: lineLayout, paint: { 'line-color': CASING, 'line-width': 8 } },
        { id: 'lines', type: 'line', source: 'lines', layout: lineLayout, paint: { 'line-color': ['get', 'color'], 'line-width': 5 } },
        { id: 'track-halo', type: 'line', source: 'track', layout: lineLayout, paint: { 'line-color': HALO, 'line-width': 10.5 } },
        { id: 'track-case', type: 'line', source: 'track', layout: lineLayout, paint: { 'line-color': CASING, 'line-width': 8 } },
        { id: 'track', type: 'line', source: 'track', layout: lineLayout, paint: { 'line-width': 5, 'line-gradient': solid('#d7191c') } },
        { id: 'live-halo', type: 'line', source: 'live', layout: lineLayout, paint: { 'line-color': HALO, 'line-width': 12 } },
        { id: 'live-case', type: 'line', source: 'live', layout: lineLayout, paint: { 'line-color': CASING, 'line-width': 9.5 } },
        { id: 'live', type: 'line', source: 'live', layout: lineLayout, paint: { 'line-color': ['get', 'c'], 'line-width': 6 } },
        // with terrain, each run of draped layers (fill, line, raster) is one render-to-texture pass per terrain tile, and
        // with two runs or more MapLibre 4.7 re-renders every tile every frame: no symbol layer between draped layers
        arrows('route-arrows', 'route'), arrows('lines-arrows', 'lines'), arrows('track-arrows', 'track'),
        // after every layer draped on the terrain (one render-to-texture pass for all of them), before the dots
        { id: 'bld-walls', type: 'fill-extrusion', source: 'bld', layout: { visibility: 'none' }, paint: {
          'fill-extrusion-color': BLD_WALL, 'fill-extrusion-height': ['-', ['get', 'h'], ROOF_CAP],
          'fill-extrusion-base': 0, 'fill-extrusion-vertical-gradient': true } },
        { id: 'bld-roofs', type: 'fill-extrusion', source: 'bld', layout: { visibility: 'none' }, paint: {
          'fill-extrusion-color': ['to-color', ['coalesce', ['feature-state', 'r'], ['get', 'r'], BLD_ROOF]],
          'fill-extrusion-height': ['get', 'h'], 'fill-extrusion-base': ['-', ['get', 'h'], ROOF_CAP],
          'fill-extrusion-vertical-gradient': false } },
        // a dark ring outside the white one: the dots stand out on snow as on forest
        // drawn by MapLibre, not as DOM markers: collisions resolved in the renderer (the most important kept), and no
        // per-marker "hidden behind the relief?" check, which reads the GPU's depth buffer back and stalls each frame
        { id: 'peaks', type: 'symbol', source: 'peaks', layout: {
          'icon-image': peakImageExpr(), 'icon-anchor': 'bottom', 'symbol-sort-key': ['get', 'importance'],
          'icon-padding': 4, 'icon-pitch-alignment': 'viewport', 'icon-rotation-alignment': 'viewport' } },
        { id: 'dots-halo', type: 'circle', source: 'dots', paint: { 'circle-radius': 11.5, 'circle-color': CASING, 'circle-blur': 0.15 } },
        { id: 'dots', type: 'circle', source: 'dots', paint: { 'circle-radius': 7, 'circle-color': ['get', 'color'], 'circle-stroke-width': 3, 'circle-stroke-color': '#ffffff' } },
      ],
    };
  }

  // A laptop's integrated GPU falls under 30 fps in 3D at the screen's full pixel density (1.5 at 150 % scaling draws
  // 2.25 times the pixels of 1): the page times its own frames while the map moves and, when too slow, draws fewer
  // pixels (2, then 1.5, then 1), remembered for the next visit. A fast GPU never gets there. The key changed when 3D
  // stopped redrawing its draped layers every frame: machines lowered only because of that measure again.
  const RATIO_KEY = 'smap-pixel-ratio-2', SLOW_FRAME_MS = 28, FRAME_SAMPLES = 40;
  const nativeRatio = Math.min(2, window.devicePixelRatio || 1);
  let ratio = nativeRatio;
  try {
    const kept = parseFloat(localStorage.getItem(RATIO_KEY));
    if (kept >= 1 && kept < ratio) ratio = kept;
  } catch (e) { /* no storage (private window): measured again */ }

  document.documentElement.dataset.theme = theme;
  let map;
  try {
    // fadeDuration 0 makes MapLibre place every symbol again on each frame, on the relief: a third of the frame rate in 3D
    map = new maplibregl.Map({
      container: 'map', style: style(), center: [2.5, 46.5], zoom: 5, pitch: 0,
      maxPitch: 80, fadeDuration: 300, maxTileCacheSize: 120, antialias: false, refreshExpiredTiles: false,
      pixelRatio: ratio, renderWorldCopies: false, validateStyle: false,
      interactive, attributionControl: { compact: true, customAttribution: ATTRIBUTION },
    });
  } catch (e) {
    const b = document.getElementById('banner');
    b.textContent = 'Carte indisponible : cet appareil ne permet pas le rendu WebGL';
    b.hidden = false;
    report(`map: ${e && e.message ? e.message : e}`);
    return;
  }
  let frameGaps = [], lastFrame = 0;
  map.on('render', () => {
    const now = performance.now(), gap = now - lastFrame;
    lastFrame = now;
    // only frames drawn back to back while the camera moves: an idle map renders once in a while
    if (ratio <= 1 || !(map.isMoving() || anim) || gap > 400) return;
    frameGaps.push(gap);
    if (frameGaps.length < FRAME_SAMPLES) return;
    const median = frameGaps.sort((a, b) => a - b)[FRAME_SAMPLES >> 1];
    frameGaps = [];
    if (median <= SLOW_FRAME_MS) return;
    ratio = Math.max(1, ratio - 0.5);
    map.setPixelRatio(ratio);
    try { localStorage.setItem(RATIO_KEY, String(ratio)); } catch (e) { /* measured again next time */ }
    console.info(`smap: ${Math.round(median)} ms a frame, now drawing at pixel ratio ${ratio}`);
  });
  // Added on demand: also after a style rebuild, which drops images added earlier.
  map.on('styleimagemissing', (e) => {
    if (e.id === 'smap-arrow' && !map.hasImage('smap-arrow')) map.addImage('smap-arrow', arrowImage(), { pixelRatio: 2 });
    const peak = /^peak-(light|dark)-(\d+)$/.exec(e.id);
    if (peak && peakList[+peak[2]] && !map.hasImage(e.id)) {
      const ratio = Math.min(2, window.devicePixelRatio || 1);
      map.addImage(e.id, peakImage(peakList[+peak[2]], peak[1], ratio), { pixelRatio: ratio });
    }
  });
  map.on('error', (e) => {
    // tiles the host could not get (offline, outside the coverage) are expected: a blank tile, no report
    if (e.sourceId || e.tile || (e.source && e.source.type)) return;
    report(e.error && (e.error.message || e.error));
  });
  const src = (id) => map.getSource(id);
  const vis = (id, on) => { if (map.getLayer(id)) map.setLayoutProperty(id, 'visibility', on ? 'visible' : 'none'); };

  // ---------------------------------------------------------------- the 3D block

  function bandMetres() {
    const z = Math.min(13, Math.max(0, Math.floor(map.getZoom())));
    const lat = SQ ? (SQ[1] + SQ[3]) / 2 : map.getCenter().lat;
    return 6 * 156543 * Math.cos(lat * Math.PI / 180) / 2 ** z;  // 6 DEM pixels: wider than the cliff, so no white stripes
  }
  // A setData reloads the source's tiles and, on terrain, redraws the photo of every terrain tile: only when the square
  // or the band's width really changed (a camera animation ends a zoom at every frame)
  let frameKey = null;
  function updateFrame() {
    const band = SQ ? bandMetres() : 0, key = SQ ? `${SQ.join(',')}|${band}` : '';
    if (key === frameKey) return;
    frameKey = key;
    if (!SQ) { ['band', 'mask', 'frame'].forEach((id) => src(id).setData(EMPTY)); return; }
    const b = grow(SQ, band), w = SQ[2] - SQ[0], h = SQ[3] - SQ[1];
    // a bounded ring, not the whole world: a world polygon tiled badly into a white veil
    const outer = [SQ[0] - 3 * w - 1, Math.max(-85, SQ[1] - 3 * h - 1), SQ[2] + 3 * w + 1, Math.min(85, SQ[3] + 3 * h + 1)];
    const poly = (rings) => ({ type: 'Feature', properties: {}, geometry: { type: 'Polygon', coordinates: rings } });
    src('band').setData(poly([ring(b), hole(SQ)]));
    src('mask').setData(poly([ring(outer), hole(b)]));
    src('frame').setData(lineFeature(ring(SQ)));
  }
  /** The 3D sources moved to the new square in place, when they exist: the block keeps its photo and relief while the
   *  new ones load (removing and adding them showed a blank, flat block for a second, each time a live square moved). */
  function moveSquare(bounds) {
    const photo = map.getSource('orthosq'), dem = map.getSource('dem'), caches = map.style.sourceCaches;
    const movable = (s) => s && s.tileBounds && s.tileBounds.bounds && typeof s.tileBounds.bounds.getWest === 'function';
    if (!SQ || !built3d || !movable(photo) || !movable(dem) || !caches.dem || typeof caches.dem.reload !== 'function') return false;
    for (const s of [photo, dem]) {
      s.bounds = bounds;
      s.tileBounds.bounds = maplibregl.LngLatBounds.convert(bounds);  // what MapLibre 4.7 tests each tile against
    }
    built3d = SQ;
    caches.dem.reload();  // every DEM tile cut again to the new square; the old one is drawn until it arrives
    map.triggerRepaint();
    return true;
  }
  function rebuild3d() {
    const bounds = SQ ? grow(SQ, 200) : undefined;
    if (moveSquare(bounds)) return;
    map.setTerrain(null);  // the terrain must let go of its source before the source is removed
    if (map.getLayer('orthosq')) map.removeLayer('orthosq');
    for (const id of ['orthosq', 'dem']) if (map.getSource(id)) map.removeSource(id);
    built3d = SQ;
    // sources bounded to the square: MapLibre requests no tile outside it
    if (SQ) {
      map.addSource('orthosq', Object.assign({}, ORTHO, { bounds }));
      map.addLayer({ id: 'orthosq', type: 'raster', source: 'orthosq', paint: rasterPaint() }, TRAILS ? TRAIL_LAYERS[0] : 'band');
      // MapLibre 4.7 on terrain: on the first frame at a new tile zoom, the parent standing in for the missing tiles is
      // taken from the tile cache without being drawn, and the tiles it replaces are hidden: a blank frame at each zoom
      // step (the whole view when zooming out). A second pass of the same update finds it in place and draws it.
      const cache = map.style.sourceCaches.orthosq, update = cache.update;
      cache.update = function (...args) { update.apply(this, args); update.apply(this, args); };
    }
    map.addSource('dem', { type: 'raster-dem', tiles: ['dem://{z}/{x}/{y}'], tileSize: 256, maxzoom: 13, encoding: 'terrarium', bounds });
  }
  let measuring = null;
  function measureForCamera(sq) {
    if (measuring === sq) return;
    measuring = sq;
    measure(sq).catch((e) => {
      console.warn('smap: relief unknown, the camera frames a flat block', e);  // offline: no DEM tiles anyway
      return { min: 0, max: 0, centre: 0 };
    }).then((r) => {
      if (measuring !== sq) return;
      measuring = null;
      if (SQ !== sq) return;
      relief = Object.assign(r, { sq });
      if (fitPending) { const f = fitPending; fitPending = null; fit(f.animated, f.done); }
    });
  }
  const HANDOVER_MAX_MS = 5000;  // a tile that never comes (offline) does not keep both layers drawn
  let photoLayer = null, handover = null;
  function endHandover() {
    if (!handover) return;
    const cache = map.style.sourceCaches[photoLayer];
    if (performance.now() < handover.until && cache && !(cache.used && cache.loaded())) return;
    const old = handover.layer;
    handover = null;
    if (old !== photoLayer) vis(old, false);
  }
  map.on('render', endHandover);
  map.on('idle', endHandover);
  function applyMode() {
    const is3d = mode === '3d';
    if (is3d && shown && (SQ || pos) && !sameBox(built3d, SQ)) rebuild3d();  // nothing to show: no unbounded terrain
    if (is3d && shown && SQ && !(relief && relief.sq === SQ)) measureForCamera(SQ);
    // the two photo layers draw the same photo (the block's is bounded to the square): the one going away stays drawn,
    // under the other, until the other's tiles are in, or the switch shows the background for as long as they load
    const photo = !shown ? null : is3d && SQ ? 'orthosq' : 'ortho';
    if (photo !== photoLayer) {
      handover = photoLayer && photo && map.getLayer(photoLayer) ? { layer: photoLayer, until: performance.now() + HANDOVER_MAX_MS } : null;
      photoLayer = photo;
      if (handover) setTimeout(endHandover, HANDOVER_MAX_MS + 50);
    }
    vis('ortho', photo === 'ortho' || (!!handover && handover.layer === 'ortho'));
    vis('orthosq', photo === 'orthosq' || (!!handover && handover.layer === 'orthosq'));
    ['band', 'mask', 'frame'].forEach((id) => vis(id, is3d && !!SQ));
    TRAIL_LAYERS.forEach((id) => vis(id, shown));
    // setTerrain rebuilds the whole terrain renderer: only when its state really changes
    const want = is3d && shown && !!(SQ || pos) && !!map.getSource('dem'), cur = map.getTerrain();
    if (want && (!cur || cur.exaggeration !== exag)) map.setTerrain({ source: 'dem', exaggeration: exag });
    else if (!want && cur) map.setTerrain(null);
    scheduleBuildings();
    if (!interactive) return;
    if (is3d) {
      map.dragRotate.enable(); map.touchZoomRotate.enableRotation(); map.touchPitch.enable(); map.keyboard.enableRotation();
      map.setMaxPitch(80);
    } else {
      map.dragRotate.disable(); map.touchZoomRotate.disableRotation(); map.touchPitch.disable(); map.keyboard.disableRotation();
      map.setMaxPitch(0);
    }
  }
  function show() {
    if (shown) return;
    shown = true;
    applyMode();
  }
  function wantedSquare() {
    if (explicitSquare) return explicitSquare;
    const fixed = track ? [track.coords] : lines.length ? lines.map((l) => l.coords) : route ? [route] : null;
    if (fixed) { const b = bboxOf(fixed); return b && squareOf(...b); }
    if (liveSquareKm && pos) {
      const side = liveSq ? liveSq[3] - liveSq[1] : 0, margin = side * 0.2;
      const near = !liveSq || pos.lat - liveSq[1] < margin || liveSq[3] - pos.lat < margin
        || (pos.lon - liveSq[0]) / (liveSq[2] - liveSq[0]) < 0.2 || (liveSq[2] - pos.lon) / (liveSq[2] - liveSq[0]) < 0.2;
      if (near) liveSq = squareAround(pos.lon, pos.lat, liveSquareKm);
      return liveSq;
    }
    return null;
  }
  /** Recomputes the square; the sources are rebuilt only when it really changed. Returns whether it did. */
  function refreshSquare() {
    const sq = wantedSquare();
    if (sameBox(sq, SQ)) return false;
    SQ = sq;
    updateFrame();
    applyMode();
    schedulePeaks();
    return true;
  }
  map.on('zoomend', () => { if (mode === '3d') updateFrame(); });

  // ---------------------------------------------------------------- camera

  function dataBox() {
    const lists = [];
    if (track) lists.push(track.coords);
    lines.forEach((l) => lists.push(l.coords));
    if (route) lists.push(route);
    if (!lists.length && live.coords.length) lists.push(live.coords);
    return bboxOf(lists);
  }
  // With terrain on, MapLibre 4.7's easeTo freezes the camera's elevation at the value it had when the move began
  // (0 while the DEM is still loading) and never unfreezes it: the camera then looks at sea level, under the block.
  // So the page animates the camera itself, one jumpTo a frame (MapLibre then sets the elevation under the centre at
  // each frame), in 2D as in 3D. Any gesture stops it at once, where it is.
  let anim = null, ownJump = 0;
  function jump(camera) {
    ownJump++;
    try { map.jumpTo(camera); } finally { ownJump--; }
  }
  const easeInOut = (k) => (k < 0.5 ? 4 * k * k * k : 1 - (2 - 2 * k) ** 3 / 2);
  const easeOut = (k) => k * (2 - k);
  /** Stops the page's animation where it is; its `end` runs (cancelled). */
  function stopAnim() {
    if (!anim) return;
    const a = anim;
    anim = null;
    cancelAnimationFrame(a.frame);
    a.end(true);
  }
  /** The camera to `camera` (fields left out stay), in one jump or animated: 400-700 ms with the distance, or `duration`.
   *  `minZoom` is applied without snapping the zoom on the way; `done(cancelled)` runs once it ends either way. */
  function move(camera, animated, { minZoom = null, duration = 0, ease = easeInOut, kind = 'fit', done = null } = {}) {
    stopAnim();
    map.stop();
    const to = {
      center: camera.center ? maplibregl.LngLat.convert(camera.center) : map.getCenter(),
      zoom: camera.zoom != null ? camera.zoom : map.getZoom(),
      pitch: camera.pitch != null ? camera.pitch : map.getPitch(),
      bearing: camera.bearing != null ? camera.bearing : map.getBearing(),
      padding: camera.padding || map.getPadding(),
    };
    const end = (cancelled) => {
      if (minZoom != null) map.setMinZoom(Math.min(minZoom, map.getZoom()));
      if (done) done(cancelled);
    };
    const from = { center: map.getCenter(), zoom: map.getZoom(), pitch: map.getPitch(), bearing: map.getBearing(), padding: map.getPadding() };
    const fx = mercX(from.center.lng), fy = mercY(from.center.lat), dx = mercX(to.center.lng) - fx, dy = mercY(to.center.lat) - fy;
    const dz = to.zoom - from.zoom, dp = to.pitch - from.pitch, db = ((to.bearing - from.bearing) % 360 + 540) % 360 - 180;
    // how far it goes: zoom levels, screen widths travelled (at the wider of the two zooms), quarter turns
    const width = map.getContainer().clientWidth || 1;
    const widths = Math.hypot(dx, dy) * 512 * 2 ** Math.min(from.zoom, to.zoom) / width;
    const dist = Math.abs(dz) + Math.min(3, widths) + Math.abs(dp) / 45 + Math.abs(db) / 90;
    if (!animated || dist < 0.02) {
      if (minZoom != null) map.setMinZoom(Math.min(minZoom, to.zoom));
      jump(to);
      end(false);
      return;
    }
    if (minZoom != null) map.setMinZoom(Math.min(minZoom, from.zoom, to.zoom));
    const ms = duration || Math.min(700, 400 + 100 * dist);
    // the centre moves at a steady speed on screen while the zoom changes: fast while far out, slow while close in
    const along = Math.abs(dz) > 0.01 ? (e) => (1 - 2 ** (-dz * e)) / (1 - 2 ** -dz) : (e) => e;
    const unmerc = (x, y) => [x * 360 - 180, Math.atan(Math.sinh(Math.PI * (1 - 2 * y))) * 180 / Math.PI];
    const pad = (e) => ({ top: from.padding.top + (to.padding.top - from.padding.top) * e,
      bottom: from.padding.bottom + (to.padding.bottom - from.padding.bottom) * e,
      left: from.padding.left + (to.padding.left - from.padding.left) * e,
      right: from.padding.right + (to.padding.right - from.padding.right) * e });
    // the clock starts at the first frame: a slow frame drawn just before (a mode switch) does not eat the animation
    const a = anim = { kind, end, frame: 0, t0: 0 };
    const step = () => {
      if (anim !== a) return;
      const now = performance.now();
      if (!a.t0) { a.t0 = now; a.frame = requestAnimationFrame(step); return; }
      const k = Math.min(1, (now - a.t0) / ms), e = ease(k), c = along(e);
      if (k >= 1) {
        anim = null;
        jump(to);
        end(false);
        return;
      }
      jump({ center: unmerc(fx + dx * c, fy + dy * c), zoom: from.zoom + dz * e, pitch: from.pitch + dp * e,
        bearing: from.bearing + db * e, padding: pad(e) });
      a.frame = requestAnimationFrame(step);
    };
    a.frame = requestAnimationFrame(step);
  }
  function glide(to) {
    move({ center: to }, true, { duration: 600, ease: easeOut, kind: 'glide' });
  }
  // a gesture takes the camera from wherever the animation has got to
  for (const ev of ['mousedown', 'touchstart', 'wheel', 'keydown']) {
    map.getCanvasContainer().addEventListener(ev, stopAnim, { capture: true, passive: true });
  }
  const FOV = 0.6435011087932844;  // MapLibre's vertical field of view
  /** 65° on a landscape view, down to 50° on a portrait one twice as tall as wide. */
  function blockPitch(box) {
    const aspect = box.width / box.height;
    return Math.max(PITCH_TALL, Math.min(PITCH, PITCH_TALL + (aspect - 0.5) / 0.5 * (PITCH - PITCH_TALL)));
  }
  /** Camera that shows the block's whole top surface, centred in the room left by the overlays. The 8 corners of
   *  the surface (the square at the lowest and the highest elevation of its terrain) are projected the way MapLibre
   *  does it (camera looking at the square's centre, at its terrain elevation, with the pitch), and the distance is
   *  searched until they fit. The walls go down to the floor below that, off the bottom of the screen if need be. */
  function blockCamera(sq, r, box, pad) {
    const pitch = blockPitch(box);
    const latC = (sq[1] + sq[3]) / 2, cos = Math.cos(latC * Math.PI / 180), phi = pitch * Math.PI / 180;
    const lx = (sq[2] - sq[0]) * 111320 * cos, ly = (sq[3] - sq[1]) * 110540;
    const focal = 0.5 * box.height / Math.tan(FOV / 2), sp = Math.sin(phi), cp = Math.cos(phi);
    const corners = [], floor = [];
    for (const x of [-lx / 2, lx / 2]) for (const y of [-ly / 2, ly / 2]) {
      for (const h of [r.min, r.max]) corners.push([x, y, (h - r.centre) * exag]);
      floor.push([x, y, (FLOOR - r.centre) * exag]);
    }
    const extent = (D, pts = corners) => {
      const e = { x0: Infinity, x1: -Infinity, y0: Infinity, y1: -Infinity };
      for (const [x, y, h] of pts) {
        const depth = y * sp - h * cp + D;
        if (depth <= 1) return null;
        const sx = x / depth * focal, sy = (y * cp + h * sp) / depth * focal;
        e.x0 = Math.min(e.x0, sx); e.x1 = Math.max(e.x1, sx); e.y0 = Math.min(e.y0, sy); e.y1 = Math.max(e.y1, sy);
      }
      return e;
    };
    // room at the top for the legend, the host's 3D/2D switch and the peaks' labels
    const top = pad + Math.min(56, box.height * 0.12), bottom = pad;
    const fits = (D) => { const e = extent(D); return e && e.x1 - e.x0 <= box.width - 2 * pad && e.y1 - e.y0 <= box.height - top - bottom; };
    let lo = 10, hi = 4e7;
    for (let i = 0; i < 60; i++) { const mid = Math.sqrt(lo * hi); if (fits(mid)) hi = mid; else lo = mid; }
    // the whole block centred when it fits, walls included; otherwise the surface just under the top overlays and
    // the walls running off the bottom
    const e = extent(hi), all = extent(hi, corners.concat(floor)), room = box.height - top - bottom;
    const shift = all && all.y1 - all.y0 <= room ? (all.y0 + all.y1) / 2 + (top - bottom) / 2
      : e.y1 - (box.height / 2 - top);  // how far the drawn centre must move down, in px
    const zoom = Math.min(17, Math.log2(78271.517 * cos / (hi / focal)));
    // padding moves the drawn centre (and MapLibre's perspective centre with it) down by `shift`
    const padding = { top: Math.max(0, 2 * shift), bottom: Math.max(0, -2 * shift), left: 0, right: 0 };
    return { center: [(sq[0] + sq[2]) / 2, latC], zoom, pitch, padding };
  }
  /** While following: centred on the position, north up, close enough to see the ground around it. */
  function followCamera(box, is3d) {
    const zoom = is3d ? Math.min(17, Math.log2(78271.517 * Math.cos(pos.lat * Math.PI / 180) * box.width / FOLLOW_WIDTH_M))
      : Math.max(map.getZoom(), 15);
    return { center: [pos.lon, pos.lat], zoom, pitch: is3d ? FOLLOW_PITCH : 0, bearing: 0, padding: NO_PADDING };
  }
  const NO_PADDING = { top: 0, bottom: 0, left: 0, right: 0 };
  /** Following: the camera on the position. Otherwise the overview: the block's surface in 3D, the data in 2D. */
  function fit(animated, done) {
    const is3d = mode === '3d';
    const box = map.getContainer().getBoundingClientRect();
    const padding = Math.round(Math.max(8, Math.min(48, Math.min(box.width, box.height) * 0.07)));
    if (follow && pos) {
      fitPending = null;
      followFitted = true;
      const cam = followCamera(box, is3d);
      move(cam, animated, { minZoom: is3d ? Math.max(0, cam.zoom - 5) : 0, done });
      return;
    }
    if (is3d && SQ && !(relief && relief.sq === SQ)) { fitPending = { animated, done }; return; }
    fitPending = null;
    if (is3d && SQ) {
      const block = blockCamera(SQ, relief, box, padding);
      // the block alone in a white world: no further out
      move({ center: block.center, zoom: block.zoom, pitch: block.pitch, bearing: 0, padding: block.padding }, animated,
        { minZoom: Math.max(0, block.zoom - 5), done });
      return;
    }
    let b = dataBox() || SQ;
    if (!b && pos) b = squareAround(pos.lon, pos.lat, 1);
    const cam = b && map.cameraForBounds(b[2] - b[0] < 1e-5 && b[3] - b[1] < 1e-5 ? toBounds(squareAround(b[0], b[1], 0.5)) : toBounds(b),
      { padding, maxZoom: 17 });
    if (!cam) { if (done) done(false); return; }
    move({ center: cam.center, zoom: cam.zoom, pitch: is3d ? PITCH : 0, bearing: 0, padding: NO_PADDING }, animated, { minZoom: 0, done });
  }
  const toBounds = (b) => [[b[0], b[1]], [b[2], b[3]]];
  // MapLibre 4.7 keeps the camera above the elevation at the centre of the view only: tilting or zooming towards a
  // slope (or the inertia after a drag) takes it inside the relief, where the view goes wrong. Any move that is not
  // ours and ends below the ground under the camera is undone, back to the last camera that was above it.
  const CLEARANCE_M = 30;
  let lastAbove = null;
  function underground() {
    const t = map.transform, cam = t.getCameraPosition();
    return cam.altitude < map.terrain.getElevationForLngLatZoom(cam.lngLat, t.tileZoom) + CLEARANCE_M;
  }
  map.on('move', () => {
    if (!map.terrain) { lastAbove = null; return; }
    // our own cameras are trusted as they are: a gesture from one that starts low must not be sent back elsewhere
    if (ownJump || !underground()) {
      lastAbove = { center: map.getCenter(), zoom: map.getZoom(), pitch: map.getPitch(), bearing: map.getBearing() };
    } else if (lastAbove) {
      map.stop();
      jump(lastAbove);
    }
  });
  /** The default view, from whatever state the camera is in: stops any move, then the overview (or the follow view). */
  function reset() {
    stopAnim();
    map.stop();
    lastAbove = null;
    fit(true);
  }
  ['dragstart', 'zoomstart', 'rotatestart', 'pitchstart'].forEach((ev) => map.on(ev, (e) => {
    if (!e.originalEvent) return;
    stopAnim();
    if (follow) { follow = false; hostPost({ type: 'follow', value: false }); }
  }));

  // ---------------------------------------------------------------- speed colours

  function speedColour(ratio) {
    const t = (Math.min(1.6, Math.max(0.4, ratio)) - 0.4) / 1.2;
    const lerp = (a, b, f) => a.map((v, i) => Math.round(v + (b[i] - v) * f));
    const c = t < 0.5 ? lerp([215, 25, 28], [255, 255, 191], t * 2) : lerp([255, 255, 191], [26, 150, 65], (t - 0.5) * 2);
    return `rgb(${c[0]},${c[1]},${c[2]})`;
  }
  function metres(a, b) {
    const kx = 111320 * Math.cos((a[1] + b[1]) / 2 * Math.PI / 180);
    return Math.hypot((b[0] - a[0]) * kx, (b[1] - a[1]) * 110540);
  }
  /** line-gradient over the track: ≤ 250 stops, each the mean speed of its stretch relative to the mean. */
  function speedGradient(coords, speeds, avg) {
    if (!speeds || !avg || speeds.length !== coords.length || coords.length < 2) return solid('#d7191c');
    const cum = [0];
    for (let i = 1; i < coords.length; i++) cum.push(cum[i - 1] + metres(coords[i - 1], coords[i]));
    const total = cum[cum.length - 1];
    if (total <= 0) return solid(speedColour(1));
    const N = Math.min(250, coords.length);
    const expr = ['interpolate', ['linear'], ['line-progress']];
    let i = 0, colour = speedColour(speeds[0] / avg);
    for (let k = 0; k <= N; k++) {
      const end = k / N * total;
      let sum = 0, cnt = 0;
      while (i < coords.length && cum[i] <= end) { sum += speeds[i]; cnt++; i++; }
      if (cnt) colour = speedColour(sum / cnt / avg);
      expr.push(k / N, colour);
    }
    return expr;
  }
  function showLegend(avg) {
    const el = document.getElementById('legend');
    el.hidden = !avg;
    if (avg) document.getElementById('legend-avg').textContent = `Moyenne ${(avg * 3.6).toFixed(1).replace('.', ',')} km/h`;
  }

  // ---------------------------------------------------------------- live trail

  // One short feature per segment, coloured by its own speed: appending is one updateData, and the whole trail is
  // recoloured only when the mean moved. On terrain any change to the trail's source redraws the photo of every terrain
  // tile, so the fixes (about one a second) are drawn together, every LIVE_FLUSH_3D_MS at most: the trail then ends a
  // few metres behind the position marker, which moves at each fix.
  const LIVE_FLUSH_MS = 1000, LIVE_FLUSH_3D_MS = 4000;
  function liveFeature(i) {
    const avg = live.count ? live.sum / live.count : 0;
    const c = live.smooth[i] == null || !avg ? '#d7191c' : speedColour(live.smooth[i] / avg);
    return { type: 'Feature', id: i, properties: { c }, geometry: { type: 'LineString', coordinates: [live.coords[i - 1], live.coords[i]] } };
  }
  function livePush(lon, lat, speed) {
    live.coords.push([lon, lat]);
    live.speeds.push(speed == null ? null : +speed);
    const recent = live.speeds.slice(-5).filter((v) => v != null);
    live.smooth.push(recent.length ? recent.reduce((a, b) => a + b, 0) / recent.length : null);
    if (speed != null && speed > 0.3) { live.sum += +speed; live.count++; }
  }
  function liveRedraw() {
    clearTimeout(live.pending);
    live.pending = 0; live.added = []; live.full = false; live.flushedAt = Date.now();
    const features = [];
    for (let i = 1; i < live.coords.length; i++) features.push(liveFeature(i));
    src('live').setData({ type: 'FeatureCollection', features });
    live.colouredAvg = live.count ? live.sum / live.count : 0;
    if (!track) showLegend(live.colouredAvg || null);
  }
  function liveFlush() {
    live.pending = 0;
    const avg = live.count ? live.sum / live.count : 0, s = src('live');
    if (live.full || typeof s.updateData !== 'function' || (avg && Math.abs(avg - live.colouredAvg) > live.colouredAvg * 0.03)) {
      liveRedraw();
      return;
    }
    if (live.added.length) s.updateData({ add: live.added.map(liveFeature) });
    live.added = []; live.flushedAt = Date.now();
  }
  function liveFlushSoon() {
    if (live.pending) return;
    const every = map.getTerrain() ? LIVE_FLUSH_3D_MS : LIVE_FLUSH_MS;
    live.pending = setTimeout(liveFlush, Math.max(0, every - (Date.now() - live.flushedAt)));
  }

  // ---------------------------------------------------------------- position

  let posMarker = null, posEl = null, posAdded = false, accSvg = null, accShape = null;
  function domMarker(element) {
    const marker = new maplibregl.Marker({ element, anchor: 'center', opacityWhenCovered: '1' });
    // MapLibre 4.7 checks every marker against the terrain about 10 times a second by reading the GPU's depth
    // buffer back (readPixels), which stalls the frame; these stay fully opaque when covered anyway
    marker._updateOpacity = () => {};
    return marker;
  }
  function ensurePosMarker() {
    if (posMarker) return;
    posEl = document.createElement('div');
    posEl.className = 'smap-pos';
    const NS = 'http://www.w3.org/2000/svg';
    accSvg = document.createElementNS(NS, 'svg');
    accSvg.setAttribute('class', 'acc');
    accShape = document.createElementNS(NS, 'circle');
    accShape.setAttribute('r', '1');
    accSvg.appendChild(accShape);
    posEl.appendChild(accSvg);
    for (const part of ['halo', 'arrow', 'dot']) {
      const d = document.createElement('div'); d.className = part; posEl.appendChild(d);
    }
    posMarker = domMarker(posEl);
  }
  // The point hovered on the host's charts: a DOM marker, moved for free. A GeoJSON source would reload all its tiles
  // at each move, and on terrain every tile load redraws the photo of the terrain tiles under it (50-2500 ms a move
  // on a software renderer).
  let cursorMarker = null, cursorAdded = false;
  function setCursorAt(lon, lat) {
    if (lon == null || lat == null) { if (cursorMarker) cursorMarker.remove(); cursorAdded = false; return; }
    if (!cursorMarker) cursorMarker = domMarker(el('div', 'smap-cursor'));
    cursorMarker.setLngLat([lon, lat]);
    if (!cursorAdded) { cursorMarker.addTo(map); cursorAdded = true; }
  }
  function orientArrow() {
    if (!posEl || !pos || pos.heading == null) return;
    posEl.querySelector('.arrow').style.transform = `rotate(${pos.heading - map.getBearing()}deg)`;
  }
  map.on('rotate', orientArrow);
  function setBanner(text) {
    const el = document.getElementById('banner');
    clearTimeout(bannerTimer);
    banner = text;
    el.hidden = !text;
    if (text) { el.textContent = text; bannerTimer = setTimeout(() => { el.hidden = true; }, 8000); }
  }
  // The accuracy circle is drawn in the position marker (an SVG ellipse, the circle on the ground as the camera sees it),
  // not as a GeoJSON source: a fix a second reloaded that source and, on terrain, redrew the photo of every terrain tile.
  function drawAccuracy() {
    if (!posAdded) return;
    const r = pos && pos.accuracy, kx = 111320 * Math.cos((pos ? pos.lat : 0) * Math.PI / 180);
    let shown = false;
    if (r) {
      const c = map.project([pos.lon, pos.lat]), e = map.project([pos.lon + r / kx, pos.lat]), n = map.project([pos.lon, pos.lat + r / 110540]);
      // the unit circle's x axis goes east, its y axis (down) south
      const m = [e.x - c.x, e.y - c.y, c.x - n.x, c.y - n.y], size = Math.max(Math.hypot(m[0], m[1]), Math.hypot(m[2], m[3]));
      shown = size >= 3 && size < 5000 && m.every(Number.isFinite);  // smaller than the dot, or past the horizon: none
      if (shown) accShape.setAttribute('transform', `matrix(${m.map((v) => v.toFixed(2)).join(' ')} 0 0)`);
    }
    accSvg.style.display = shown ? '' : 'none';
  }
  map.on('move', drawAccuracy);
  function updatePos() {
    if (!pos) { if (posMarker) posMarker.remove(); posAdded = false; return; }
    ensurePosMarker();
    const outside = SQ && mode === '3d' && !follow
      && (pos.lon < SQ[0] || pos.lon > SQ[2] || pos.lat < SQ[1] || pos.lat > SQ[3]);
    if (outside) {
      // looking at an activity done elsewhere: no marker floating off the block, and nothing to warn about
      posMarker.remove(); posAdded = false;
      return;
    }
    if (banner) setBanner(null);
    posMarker.setLngLat([pos.lon, pos.lat]);
    if (!posAdded) { posMarker.addTo(map); posAdded = true; }
    posEl.classList.toggle('heading', pos.heading != null);
    orientArrow();
    drawAccuracy();
  }

  // ---------------------------------------------------------------- peaks

  let peakList = [], peakSeq = 0, peakTimer = 0, peakPopup = null;
  function schedulePeaks() {
    clearTimeout(peakTimer);
    peakTimer = setTimeout(loadPeaks, 400);  // a live square moving or data arriving in bursts: one request
  }
  async function loadPeaks() {
    const seq = ++peakSeq, sq = SQ;
    if (!sq) { setPeaks([]); return; }
    const q = `w=${sq[0].toFixed(5)}&s=${sq[1].toFixed(5)}&e=${sq[2].toFixed(5)}&n=${sq[3].toFixed(5)}`;
    let list = [];
    try {
      const res = await fetch(`${BASE}peaks?${q}`);
      if (res.ok) list = await res.json();
    } catch (e) {
      console.warn('smap: no peaks', e);  // offline with nothing cached: the map simply has no peaks
    }
    if (seq === peakSeq) setPeaks(Array.isArray(list) ? list : []);
  }
  function el(tag, cls, text) {
    const e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) e.textContent = text;
    return e;
  }
  function peakImageExpr() { return ['concat', `peak-${theme}-`, ['to-string', ['get', 'id']]]; }
  const PEAK_CHIP = {
    light: { fill: 'rgba(255,255,255,0.95)', text: '#1d1f22', border: 'rgba(0,0,0,0.12)' },
    dark: { fill: 'rgba(32,34,38,0.95)', text: '#eceef1', border: 'rgba(255,255,255,0.14)' },
  };
  /** The label chip and its stem down to the summit (the image's bottom centre), as the DOM markers drew them. */
  function peakImage(p, th, ratio) {
    const c = PEAK_CHIP[th], label = p.ele != null ? `${p.name} · ${p.ele} m` : p.name;
    const FONT = '600 11px system-ui, -apple-system, "Segoe UI", Roboto, sans-serif';
    const m = 5, chipH = 19, stemH = 30;  // m: room for the shadow
    const probe = document.createElement('canvas').getContext('2d');
    probe.font = FONT;
    const textW = Math.ceil(probe.measureText(label).width), chipW = textW + 14;
    const w = chipW + 2 * m, h = m + chipH + stemH;
    const canvas = document.createElement('canvas');
    canvas.width = Math.ceil(w * ratio); canvas.height = Math.ceil(h * ratio);
    const g = canvas.getContext('2d', READ_BACK);
    g.scale(ratio, ratio);
    // stem: white fading down, outlined in black, legible on snow as on forest
    const sx = w / 2 - 1, sy = m + chipH;
    g.fillStyle = 'rgba(0,0,0,0.55)'; g.fillRect(sx - 1, sy, 4, stemH);
    const grad = g.createLinearGradient(0, sy, 0, sy + stemH);
    grad.addColorStop(0, '#ffffff'); grad.addColorStop(1, 'rgba(255,255,255,0.2)');
    g.fillStyle = grad; g.fillRect(sx, sy, 2, stemH);
    g.save();
    g.shadowColor = 'rgba(0,0,0,0.45)'; g.shadowBlur = 4; g.shadowOffsetY = 1;
    g.beginPath(); g.roundRect(m + 0.5, m + 0.5, chipW - 1, chipH - 1, 8);
    g.fillStyle = c.fill; g.fill();
    g.restore();
    g.strokeStyle = c.border; g.lineWidth = 1; g.stroke();
    g.font = FONT; g.fillStyle = c.text; g.textBaseline = 'middle';
    g.fillText(label, m + 7, m + chipH / 2 + 0.5);
    return g.getImageData(0, 0, canvas.width, canvas.height);
  }
  function setPeaks(list) {
    if (peakPopup) { peakPopup.remove(); peakPopup = null; }
    // image ids carry the index in the list: images of a former list must go, or they would show its labels
    for (const id of map.listImages()) if (id.startsWith('peak-')) map.removeImage(id);
    peakList = list;
    src('peaks').setData({ type: 'FeatureCollection', features: list.map((p, i) => ({
      type: 'Feature', properties: { id: i, importance: p.importance == null ? 99 : p.importance },
      geometry: { type: 'Point', coordinates: [p.lon, p.lat] },
    })) });
  }
  map.on('click', 'peaks', (e) => {
    const p = e.features && e.features[0] && peakList[e.features[0].properties.id];
    if (!p) return;
    const card = el('div');
    card.appendChild(el('b', null, p.name));
    card.appendChild(el('div', null, p.ele != null ? `Altitude : ${p.ele} m` : 'Altitude inconnue'));
    card.appendChild(el('div', null, `Position : ${p.lat.toFixed(4)}° N, ${p.lon.toFixed(4)}° E`));
    card.appendChild(el('div', null, `Importance : ${p.importance} (1 = majeur)`));
    card.appendChild(el('div', null, 'Source : IGN BD TOPO'));
    if (peakPopup) peakPopup.remove();
    peakPopup = new maplibregl.Popup({ offset: 56, closeButton: false, maxWidth: '240px' })
      .setLngLat([p.lon, p.lat]).setDOMContent(card).addTo(map);
  });
  map.on('mouseenter', 'peaks', () => { map.getCanvas().style.cursor = 'pointer'; });
  map.on('mouseleave', 'peaks', () => { map.getCanvas().style.cursor = ''; });

  // MapLibre changes the camera's elevation while rendering (DEM tiles arriving) and the ground under a marker
  // when its DEM tile loads, both without a move event: the position marker (DOM) would stay where it was drawn,
  // off the block or off screen. It is re-placed after such a frame.
  let markerFrame = 0, lastElevation = null;
  function replaceMarkers() {
    if (markerFrame || !(posAdded || cursorAdded)) return;
    markerFrame = requestAnimationFrame(() => {
      markerFrame = 0;
      if (posAdded) { posMarker.setLngLat(posMarker.getLngLat()); drawAccuracy(); }
      if (cursorAdded) cursorMarker.setLngLat(cursorMarker.getLngLat());
    });
  }
  map.on('render', () => {
    const e = map.transform.elevation;
    if (e !== lastElevation) { lastElevation = e; replaceMarkers(); }
  });
  map.on('sourcedata', (e) => { if (e.sourceId === 'dem' && e.tile) replaceMarkers(); });

  // ---------------------------------------------------------------- buildings

  // In 3D, close up: the cells (<base>buildings/15/x/y, README) nearest the camera's centre, merged into one source,
  // only the buildings wholly inside the block. Each roof gets the colour of the aerial photo under it: the z17 photo
  // tiles of a cell (same origin, so readable) are drawn once into a canvas, then sampled building by building in idle
  // time; until then a roof is neutral. Colours stay in memory per cell, so a cell coming back is coloured at once.
  const bld = {
    cells: new Map(),     // "x/y" -> { x, y, state: 'loading'|'ready'|'failed', items }, least recently used first
    colours: new Map(),   // "x/y" -> Uint32Array of 0xRRGGBB + 1 per building (0: not computed yet)
    wanted: [], drawnKey: '', timer: 0, mergeTimer: 0, loading: 0, nextId: 1,
    queue: [], colouring: null,
    stats: { cells: 0, ms: 0, buildings: 0 },  // read by the dev tools: time to colour a cell
  };
  window.__bldStats = bld.stats;
  function bldMinZoom() {
    const lat = SQ ? (SQ[1] + SQ[3]) / 2 : map.getCenter().lat;
    const width = map.getContainer().clientWidth || 1;
    return Math.min(BLD_MIN_ZOOM, Math.log2(78271.517 * Math.cos(lat * Math.PI / 180) * width / BLD_SPAN_M));
  }
  const bldOn = () => mode === '3d' && shown && !!SQ && !!map.getTerrain() && map.getZoom() >= bldMinZoom();
  function bldVisibility() {
    const on = bldOn();
    vis('bld-walls', on); vis('bld-roofs', on);
    return on;
  }
  function scheduleBuildings() {
    clearTimeout(bld.timer);
    bld.timer = setTimeout(updateBuildings, 250);  // the follow glide moves the camera every frame: once it settles
  }
  map.on('moveend', scheduleBuildings);
  map.on('zoom', bldVisibility);

  /** The cells meeting the block, nearest to the camera's centre first, at most BLD_CELLS. */
  function wantedCells() {
    const n = 2 ** BLD_Z, c = map.getCenter();
    const x0 = Math.floor(mercX(SQ[0]) * n), x1 = Math.floor(mercX(SQ[2]) * n - 1e-9);
    const y0 = Math.floor(mercY(SQ[3]) * n), y1 = Math.floor(mercY(SQ[1]) * n - 1e-9);
    const cx = mercX(c.lng) * n, cy = mercY(c.lat) * n, out = [];
    for (let x = x0; x <= x1; x++) for (let y = y0; y <= y1; y++) out.push({ x, y, d: Math.hypot(x + 0.5 - cx, y + 0.5 - cy) });
    return out.sort((a, b) => a.d - b.d).slice(0, BLD_CELLS);
  }
  function updateBuildings() {
    if (!bldVisibility()) return;  // the cells already drawn stay in the source: back in 3D, they show at once
    bld.wanted = wantedCells().map((c) => `${c.x}/${c.y}`);
    for (const key of bld.wanted) {
      const cell = bld.cells.get(key);
      if (cell) { bld.cells.delete(key); bld.cells.set(key, cell); continue; }  // most recently used last
      const [x, y] = key.split('/').map(Number);
      bld.cells.set(key, { x, y, state: 'loading', items: null });
      loadCell(key, x, y);
    }
    for (const [key, cell] of bld.cells) {
      if (bld.cells.size <= BLD_KEEP) break;
      if (cell.state !== 'loading' && !bld.wanted.includes(key)) bld.cells.delete(key);
    }
    mergeBuildings();
    if (!bld.colouring && bld.queue.length) nextColouring();
  }
  async function loadCell(key, x, y) {
    const cell = bld.cells.get(key);
    try {
      const res = await fetch(`${BASE}buildings/${BLD_Z}/${x}/${y}`);
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      cell.items = decodeCell(await res.json(), key);
      cell.state = 'ready';
    } catch (e) {
      // offline with nothing cached, outside France: no buildings there, the block stays as it is
      console.warn(`smap: no buildings for ${key}`, e);
      cell.state = 'failed';
      return;
    }
    mergeSoon();
    enqueueColouring(key);
  }
  /** The host's compact cell (README): rings of microdegree deltas from the cell's corner, unclosed. A v2 cell
   *  (Tuileries) also has each roof's colour, `c` (0xRRGGBB + 1, 0 = none), used as is: no photo sampling then. */
  function decodeCell(doc, key) {
    const [ox, oy] = doc.o, items = [], pre = doc.v === 2 && Array.isArray(doc.c);
    for (let j = 0; j < (doc.b || []).length; j++) {
      const b = doc.b[j];
      const rings = [];
      let w = Infinity, s = Infinity, e = -Infinity, n = -Infinity;
      for (let r = 1; r < b.length; r++) {
        const flat = b[r], pts = [];
        let px = ox, py = oy;
        for (let i = 0; i + 1 < flat.length; i += 2) {
          px += flat[i]; py += flat[i + 1];
          const lon = px / 1e6, lat = py / 1e6;
          pts.push([lon, lat]);
          if (r === 1) { if (lon < w) w = lon; if (lon > e) e = lon; if (lat < s) s = lat; if (lat > n) n = lat; }
        }
        if (pts.length < 3) { if (r === 1) break; continue; }
        pts.push(pts[0]);
        rings.push(pts);
      }
      if (!rings.length) continue;
      // tiny footprints (sheds, a few square metres) stay as their box: fewer vertices to tile and draw
      const kx = 111320 * Math.cos(s * Math.PI / 180), area = (e - w) * kx * (n - s) * 110540;
      if (area < 4) continue;
      const geom = area < 30 && rings[0].length > 5 ? [ring([w, s, e, n])] : rings;
      items.push({ id: bld.nextId++, h: b[0], rings: geom, box: [w, s, e, n], c: pre ? (doc.c[j] >>> 0) : 0 });
    }
    bld.cells.get(key).pre = pre;
    const known = bld.colours.get(key);
    if (pre) bld.colours.set(key, Uint32Array.from(items, (it) => it.c));
    else if (!known || known.length !== items.length) bld.colours.set(key, new Uint32Array(items.length));
    return items;
  }
  function mergeSoon() {
    if (!bld.mergeTimer) bld.mergeTimer = setTimeout(() => { bld.mergeTimer = 0; mergeBuildings(); }, 300);
  }
  const hex = (v) => '#' + (v - 1).toString(16).padStart(6, '0');
  /** One source for the cells drawn: inside the block only, the nearest cells first, BLD_MAX buildings at most. */
  function mergeBuildings() {
    const sq = SQ, ready = bld.wanted.filter((k) => bld.cells.get(k) && bld.cells.get(k).state === 'ready');
    const drawnKey = sq ? `${sq.join(',')}|${ready.join(';')}` : '';
    if (drawnKey === bld.drawnKey) return;
    bld.drawnKey = drawnKey;
    const features = [];
    if (sq) {
      for (const key of ready) {
        const items = bld.cells.get(key).items, colours = bld.colours.get(key);
        for (let i = 0; i < items.length && features.length < BLD_MAX; i++) {
          const it = items[i], b = it.box;
          if (b[0] < sq[0] || b[2] > sq[2] || b[1] < sq[1] || b[3] > sq[3]) continue;  // would hang over the wall
          const props = { h: it.h };
          if (colours[i]) props.r = hex(colours[i]);
          features.push({ type: 'Feature', id: it.id, properties: props, geometry: { type: 'Polygon', coordinates: it.rings } });
        }
      }
    }
    src('bld').setData({ type: 'FeatureCollection', features });
  }

  // ---- roof colours ----

  function enqueueColouring(key) {
    const colours = bld.colours.get(key), cell = bld.cells.get(key);
    if (!colours || (cell && cell.pre) || colours.every((v) => v !== 0) || bld.queue.includes(key)) return;
    bld.queue.push(key);
    if (!bld.colouring) nextColouring();
  }
  const idle = typeof requestIdleCallback === 'function'
    ? (f) => requestIdleCallback(f, { timeout: 300 })
    : (f) => setTimeout(() => { const t0 = performance.now(); f({ timeRemaining: () => Math.max(0, 8 - (performance.now() - t0)) }); }, 16);
  /** The nearest wanted cell first; cells no longer wanted wait until they are again. */
  function nextColouring() {
    bld.queue.sort((a, b) => (bld.wanted.indexOf(a) + 1 || 1e9) - (bld.wanted.indexOf(b) + 1 || 1e9));
    const key = bld.queue.find((k) => bld.wanted.includes(k) && bld.cells.get(k) && bld.cells.get(k).state === 'ready');
    if (!key) { bld.colouring = null; return; }
    bld.queue.splice(bld.queue.indexOf(key), 1);
    bld.colouring = key;
    colourCell(key).catch((e) => console.warn(`smap: roof colours of ${key}`, e)).then(nextColouring);
  }
  async function colourCell(key) {
    const t0 = performance.now(), cell = bld.cells.get(key), items = cell.items, colours = bld.colours.get(key);
    if (!items.length) return;
    // the photo tiles under the cell's buildings (a building can stick out of its cell by a few metres)
    const N = 2 ** BLD_SAMPLE_Z * 256;
    let w = Infinity, s = Infinity, e = -Infinity, n = -Infinity;
    for (const it of items) { w = Math.min(w, it.box[0]); s = Math.min(s, it.box[1]); e = Math.max(e, it.box[2]); n = Math.max(n, it.box[3]); }
    const tx0 = Math.floor(mercX(w) * N / 256), tx1 = Math.floor(mercX(e) * N / 256);
    const ty0 = Math.floor(mercY(n) * N / 256), ty1 = Math.floor(mercY(s) * N / 256);
    if ((tx1 - tx0 + 1) * (ty1 - ty0 + 1) > 36) throw new Error('cell larger than expected');
    const W = (tx1 - tx0 + 1) * 256, H = (ty1 - ty0 + 1) * 256;
    const canvas = canvasOf(W, H), ctx = canvas.getContext('2d', { willReadFrequently: true });
    const jobs = [];
    for (let tx = tx0; tx <= tx1; tx++) for (let ty = ty0; ty <= ty1; ty++) {
      jobs.push(fetch(`${BASE}t/ortho/${BLD_SAMPLE_Z}/${tx}/${ty}`)
        // only the photo (JPEG from IGN, WebP or AVIF from Tuileries): where there is none, the host answers
        // OpenStreetMap's drawn map (a PNG), no roof colour
        .then((r) => (r.ok && /jpeg|webp|avif/.test(r.headers.get('content-type') || '') ? r.blob() : null))
        .then((b) => (b ? createImageBitmap(b) : null))
        .then((bmp) => { if (bmp) { ctx.drawImage(bmp, (tx - tx0) * 256, (ty - ty0) * 256, 256, 256); bmp.close(); } })
        .catch(() => {}));  // a missing tile leaves its roofs neutral
    }
    await Promise.all(jobs);
    const img = ctx.getImageData(0, 0, W, H), ox = tx0 * 256, oy = ty0 * 256;
    let i = 0;
    await new Promise((resolve) => {
      const step = (deadline) => {
        if (bld.cells.get(key) !== cell) { resolve(); return; }  // evicted meanwhile
        const shown = !!map.getSource('bld');
        do {
          if (!colours[i]) {
            const c = roofColour(items[i], img, ox, oy, N);
            if (c != null) {
              colours[i] = c + 1;
              if (shown) map.setFeatureState({ source: 'bld', id: items[i].id }, { r: hex(c + 1) });
            }
          }
          i++;
        } while (i < items.length && deadline.timeRemaining() > 1);
        if (i < items.length) idle(step); else resolve();
      };
      idle(step);
    });
    bld.stats.cells++; bld.stats.ms += performance.now() - t0; bld.stats.buildings += items.length;
    bld.stats.last = { key, ms: Math.round(performance.now() - t0), buildings: items.length, tiles: jobs.length };
  }
  /** Median colour of the sunlit half of points sampled inside the footprint, shrunk by a pixel (the edges catch walls,
   *  shadows and pavement); the shaded slope would drag a roof towards mauve. 0xRRGGBB, or null without a pixel. */
  function roofColour(it, img, ox, oy, N) {
    const rings = it.rings.map((r) => r.map((p) => [mercX(p[0]) * N - ox, mercY(p[1]) * N - oy]));
    let x0 = Infinity, y0 = Infinity, x1 = -Infinity, y1 = -Infinity;
    for (const p of rings[0]) { x0 = Math.min(x0, p[0]); x1 = Math.max(x1, p[0]); y0 = Math.min(y0, p[1]); y1 = Math.max(y1, p[1]); }
    const stepPx = Math.max(1, Math.sqrt((x1 - x0) * (y1 - y0) / 64));
    const W = img.width, H = img.height, d = img.data;
    const pick = (margin) => {
      const out = [];
      for (let y = y0 + stepPx / 2; y < y1; y += stepPx) for (let x = x0 + stepPx / 2; x < x1; x += stepPx) {
        if (!inside(rings, x, y) || (margin && edgeDistance(rings, x, y) < margin)) continue;
        const px = Math.floor(x), py = Math.floor(y);
        if (px < 0 || py < 0 || px >= W || py >= H) continue;
        const k = (py * W + px) * 4;
        if (d[k + 3] === 0) continue;  // no photo tile there
        out.push([d[k], d[k + 1], d[k + 2]]);
      }
      return out;
    };
    let px = pick(1);
    if (px.length < 3) px = pick(0);
    if (!px.length) return null;
    const lum = (c) => 0.299 * c[0] + 0.587 * c[1] + 0.114 * c[2];
    px.sort((a, b) => lum(a) - lum(b));
    const lit = px.slice(Math.floor(px.length / 2));
    const med = [0, 1, 2].map((ch) => { const v = lit.map((c) => c[ch]).sort((a, b) => a - b); return v[v.length >> 1]; });
    const g = (med[0] + med[1] + med[2]) / 3;
    const out = med.map((v) => Math.round(Math.max(0, Math.min(255, (g + (v - g) * ROOF_SATURATION) * ROOF_BRIGHTNESS))));
    return (out[0] << 16) | (out[1] << 8) | out[2];
  }
  function inside(rings, x, y) {
    let odd = false;
    for (const r of rings) {
      for (let i = 0, j = r.length - 1; i < r.length; j = i++) {
        if ((r[i][1] > y) !== (r[j][1] > y) && x < (r[j][0] - r[i][0]) * (y - r[i][1]) / (r[j][1] - r[i][1]) + r[i][0]) odd = !odd;
      }
    }
    return odd;
  }
  function edgeDistance(rings, x, y) {
    let best = Infinity;
    for (const r of rings) {
      for (let i = 1; i < r.length; i++) {
        const ax = r[i - 1][0], ay = r[i - 1][1], dx = r[i][0] - ax, dy = r[i][1] - ay, len = dx * dx + dy * dy;
        const t = len ? Math.max(0, Math.min(1, ((x - ax) * dx + (y - ay) * dy) / len)) : 0;
        best = Math.min(best, Math.hypot(x - ax - t * dx, y - ay - t * dy));
      }
    }
    return best;
  }

  // ---------------------------------------------------------------- API

  const api = {
    setTrack(t) {
      const coords = (t && t.coords) || [];
      track = coords.length ? { coords, speeds: t.speeds || null, avg: t.avg || null } : null;
      src('track').setData(track ? lineFeature(coords) : EMPTY);
      map.setPaintProperty('track', 'line-gradient', track ? speedGradient(coords, track.speeds, track.avg) : solid('#d7191c'));
      showLegend(track && track.speeds ? track.avg : null);
      show(); refreshSquare(); updatePos(); fit();
    },
    setLines(list) {
      lines = (list || []).filter((l) => l.coords && l.coords.length > 1);
      src('lines').setData({ type: 'FeatureCollection', features: lines.map((l) => ({ type: 'Feature', properties: { color: l.color }, geometry: { type: 'LineString', coordinates: l.coords } })) });
      show(); refreshSquare(); updatePos(); fit();
    },
    setRoute(coords) {
      route = coords && coords.length > 1 ? coords : null;
      src('route').setData(route ? lineFeature(route) : EMPTY);
      show();
      const changed = refreshSquare();
      updatePos();
      if (changed && !track && !lines.length) fit();
    },
    setLive(l) {
      live.coords = []; live.speeds = []; live.smooth = []; live.sum = 0; live.count = 0;
      const coords = (l && l.coords) || [], speeds = (l && l.speeds) || [];
      coords.forEach((c, i) => livePush(c[0], c[1], speeds[i]));
      liveRedraw(); show();
    },
    appendLive(lon, lat, speed) {
      livePush(lon, lat, speed);
      const n = live.coords.length;
      if (n === 2) live.full = true;
      if (n > 2) live.added.push(n - 1);
      if (n >= 2) liveFlushSoon();
      show();
    },
    setDots(list) {
      src('dots').setData({ type: 'FeatureCollection', features: (list || []).map((d) => ({ type: 'Feature', properties: { color: d.color }, geometry: { type: 'Point', coordinates: [d.lon, d.lat] } })) });
    },
    setPos(lon, lat, heading, accuracy) {
      if (lon == null || lat == null) { pos = null; setBanner(null); refreshSquare(); updatePos(); return; }  // cleared by the host
      const next = { lon, lat, heading: heading == null ? null : heading, accuracy: accuracy || null };
      // a PC's location repeats the same fix every second: nothing would change but a redrawn frame
      if (pos && !follow && ['lon', 'lat', 'heading', 'accuracy'].every((k) => pos[k] === next[k])) return;
      pos = next;
      show();
      refreshSquare();
      updatePos();
      if (follow && !followFitted) fit();
      else if (follow && !(anim && anim.kind !== 'glide')) glide([lon, lat]);  // never cut a fit short
    },
    locFail() {
      pos = null; updatePos();
      setBanner('Localisation actuelle impossible');
    },
    setFollow(on) {
      follow = !!on;
      followFitted = false;
      updatePos();
      if (follow && pos) fit(true);
    },
    setLiveSquare(km) {
      liveSquareKm = km > 0 ? km : null; liveSq = null;
      refreshSquare(); updatePos();
    },
    setSquare(box) {
      explicitSquare = box && box.length === 4 ? box : null;
      show(); refreshSquare(); updatePos(); fit();
    },
    setMode(m) {
      const was3d = mode === '3d';
      mode = m === '2d' ? '2d' : '3d';
      if (was3d && mode === '2d' && map.getTerrain()) {
        // down to the flat view while still on the relief, then the relief goes: no jump in the picture
        updatePos();
        fit(true, () => { if (mode === '2d') { applyMode(); updateFrame(); } });
      } else {
        // up from the flat view: the relief comes at a pitch of 0, where it changes nothing on screen, then the camera tilts
        applyMode(); updatePos(); updateFrame(); fit(true);
      }
      hostPost({ type: 'mode', value: mode });
    },
    setExag(v) {
      exag = +v || 1.5;
      applyMode();
    },
    setTheme(t) {
      theme = t === 'dark' ? 'dark' : 'light';
      document.documentElement.dataset.theme = theme;
      const c = T();
      map.setPaintProperty('bg', 'background-color', c.bg);
      map.setPaintProperty('mask', 'fill-color', c.bg);
      map.setPaintProperty('band', 'fill-color', c.band);
      map.setPaintProperty('frame', 'line-color', c.frame);
      map.setLayoutProperty('peaks', 'icon-image', peakImageExpr());
    },
    setCursor(lon, lat) { setCursorAt(lon, lat); },
    fit() { fit(true); },
    reset() { reset(); },
  };

  call = (name, args) => {
    try { api[name](...args); } catch (e) { report(`${name}: ${e && e.message ? e.message : e}`); }
  };
  window.SMap.squareOf = squareOf;  // the documented square rule, for the hosts to check theirs against
  window.__map = map;  // for the dev tools
  map.once('load', () => {
    ready = true;
    applyMode();
    queue.splice(0).forEach(([name, args]) => call(name, args));
    hostPost({ type: 'ready' });
  });
})();
