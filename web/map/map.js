// Activity map shared by the Android (WebView) and Windows (Qt WebEngine) apps: Plan IGN on the DEM, in 3D as a
// square block cut out of the terrain, or flat in 2D. The host serves every URL under <base> (tiles, peaks) and
// drives the page through window.SMap; the page reports back through hostPost (README, activities: the map).
'use strict';
(function () {
  const params = new URLSearchParams(location.search);
  const BASE = new URL('../', location.href).href;  // the page lives at <base>map/index.html
  const PITCH = 65;          // the block seen from the side, on a landscape view
  const PITCH_TALL = 45;     // on a tall narrow view, so the whole square's surface stays in sight
  const FOLLOW_PITCH = 55;
  const FOLLOW_WIDTH_M = 1750;  // what the screen's width spans around the position while following, in 3D
  const ATTRIBUTION = '© IGN · © OpenStreetMap · Terrarium';
  const THEMES = {
    light: { bg: '#ffffff', band: '#d6d8da', frame: '#3a3f45', route: '#7d848d', casing: 'rgba(0,0,0,0.55)',
             brightness: 1, saturation: 0 },
    dark: { bg: '#18191c', band: '#43474e', frame: '#c9ccd1', route: '#a3aab3', casing: 'rgba(0,0,0,0.85)',
            brightness: 0.78, saturation: -0.25 },
  };
  const EMPTY = { type: 'FeatureCollection', features: [] };

  let mode = params.get('mode') === '2d' ? '2d' : '3d';
  let theme = params.get('theme') === 'dark' ? 'dark' : 'light';
  const interactive = params.get('interactive') !== '0';
  let exag = 1.5;

  let SQ = null;            // current square [w,s,e,n]; the DEM protocol reads it, so it is set before a rebuild
  let built3d = undefined;  // the square the 3D sources were built for (undefined: not built)
  let relief = null;        // {sq, min, max, centre} in metres for SQ, once measured (3D only; for the camera)
  let fitPending = null;    // a 3D fit waiting for the relief (its duration)
  let shown = false;        // nothing is fetched until there is something to show (the page is preloaded empty)
  let track = null, lines = [], route = null, dots = [];
  let explicitSquare = null, liveSquareKm = null, liveSq = null;
  let pos = null, follow = false, followFitted = false, banner = null, bannerTimer = 0;
  const live = { coords: [], speeds: [], smooth: [], sum: 0, count: 0, colouredAvg: 0, colouredAt: 0, pending: 0 };

  // ---------------------------------------------------------------- API stubs

  // window.SMap exists from the first line: calls made before the style is loaded (or on a machine without
  // WebGL, where the map never comes) are queued, then replayed in order.
  const API = ['setTrack', 'setLines', 'setRoute', 'setLive', 'appendLive', 'setDots', 'setPos', 'locFail', 'setFollow',
    'setLiveSquare', 'setSquare', 'setMode', 'setExag', 'setTheme', 'setCursor', 'fit'];
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
  const rasterPaint = () => ({ 'raster-fade-duration': 0, 'raster-brightness-max': T().brightness, 'raster-saturation': T().saturation });
  const lineLayout = { 'line-cap': 'round', 'line-join': 'round' };
  const solid = (c) => ['interpolate', ['linear'], ['line-progress'], 0, c, 1, c];
  function style() {
    const t = T(), geo = (extra) => Object.assign({ type: 'geojson', data: EMPTY }, extra || {});
    return {
      version: 8,
      sources: {
        plan: { type: 'raster', tiles: [BASE + 't/plan/{z}/{x}/{y}'], tileSize: 256, maxzoom: 18 },
        band: geo(), mask: geo(), frame: geo(), route: geo(), lines: geo(), track: geo({ lineMetrics: true }),
        live: geo(), acc: geo(), dots: geo(), cursor: geo(),
      },
      layers: [
        { id: 'bg', type: 'background', paint: { 'background-color': t.bg } },
        { id: 'plan', type: 'raster', source: 'plan', layout: { visibility: 'none' }, paint: rasterPaint() },
        { id: 'band', type: 'fill', source: 'band', paint: { 'fill-color': t.band, 'fill-antialias': false } },
        { id: 'mask', type: 'fill', source: 'mask', paint: { 'fill-color': t.bg, 'fill-antialias': false } },
        { id: 'frame', type: 'line', source: 'frame', paint: { 'line-color': t.frame, 'line-width': 1.5 } },
        { id: 'route', type: 'line', source: 'route', layout: lineLayout, paint: { 'line-color': t.route, 'line-width': 10, 'line-opacity': 0.75 } },
        { id: 'lines-case', type: 'line', source: 'lines', layout: lineLayout, paint: { 'line-color': t.casing, 'line-width': 8 } },
        { id: 'lines', type: 'line', source: 'lines', layout: lineLayout, paint: { 'line-color': ['get', 'color'], 'line-width': 5 } },
        { id: 'track-case', type: 'line', source: 'track', layout: lineLayout, paint: { 'line-color': t.casing, 'line-width': 8 } },
        { id: 'track', type: 'line', source: 'track', layout: lineLayout, paint: { 'line-width': 5, 'line-gradient': solid('#d7191c') } },
        { id: 'live-case', type: 'line', source: 'live', layout: lineLayout, paint: { 'line-color': t.casing, 'line-width': 9.5 } },
        { id: 'live', type: 'line', source: 'live', layout: lineLayout, paint: { 'line-color': ['get', 'c'], 'line-width': 6 } },
        { id: 'acc', type: 'fill', source: 'acc', paint: { 'fill-color': '#1a73ff', 'fill-opacity': 0.14, 'fill-outline-color': '#1a73ff' } },
        { id: 'dots', type: 'circle', source: 'dots', paint: { 'circle-radius': 7, 'circle-color': ['get', 'color'], 'circle-stroke-width': 3, 'circle-stroke-color': '#ffffff' } },
        { id: 'cursor', type: 'circle', source: 'cursor', paint: { 'circle-radius': 6, 'circle-color': '#111418', 'circle-stroke-width': 2.5, 'circle-stroke-color': '#ffffff' } },
      ],
    };
  }

  document.documentElement.dataset.theme = theme;
  let map;
  try {
    map = new maplibregl.Map({
      container: 'map', style: style(), center: [2.5, 46.5], zoom: 5, pitch: 0,
      maxPitch: 80, fadeDuration: 0, maxTileCacheSize: 120, antialias: false, refreshExpiredTiles: false,
      pixelRatio: Math.min(2, window.devicePixelRatio || 1), renderWorldCopies: false, validateStyle: false,
      interactive, attributionControl: { compact: true, customAttribution: ATTRIBUTION },
    });
  } catch (e) {
    const b = document.getElementById('banner');
    b.textContent = 'Carte indisponible : cet appareil ne permet pas le rendu WebGL';
    b.hidden = false;
    report(`map: ${e && e.message ? e.message : e}`);
    return;
  }
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
  function updateFrame() {
    if (!SQ) { ['band', 'mask', 'frame'].forEach((id) => src(id).setData(EMPTY)); return; }
    const b = grow(SQ, bandMetres()), w = SQ[2] - SQ[0], h = SQ[3] - SQ[1];
    // a bounded ring, not the whole world: a world polygon tiled badly into a white veil
    const outer = [SQ[0] - 3 * w - 1, Math.max(-85, SQ[1] - 3 * h - 1), SQ[2] + 3 * w + 1, Math.min(85, SQ[3] + 3 * h + 1)];
    const poly = (rings) => ({ type: 'Feature', properties: {}, geometry: { type: 'Polygon', coordinates: rings } });
    src('band').setData(poly([ring(b), hole(SQ)]));
    src('mask').setData(poly([ring(outer), hole(b)]));
    src('frame').setData(lineFeature(ring(SQ)));
  }
  function rebuild3d() {
    map.setTerrain(null);  // the terrain must let go of its source before the source is removed
    if (map.getLayer('plansq')) map.removeLayer('plansq');
    for (const id of ['plansq', 'dem']) if (map.getSource(id)) map.removeSource(id);
    built3d = SQ;
    // sources bounded to the square: MapLibre requests no tile outside it
    const bounds = SQ ? grow(SQ, 200) : undefined;
    if (SQ) {
      map.addSource('plansq', { type: 'raster', tiles: [BASE + 't/plan/{z}/{x}/{y}'], tileSize: 256, maxzoom: 18, bounds });
      map.addLayer({ id: 'plansq', type: 'raster', source: 'plansq', paint: rasterPaint() }, 'band');
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
      if (fitPending != null) { const d = fitPending; fitPending = null; fit(d); }
    });
  }
  function applyMode() {
    const is3d = mode === '3d';
    if (is3d && shown && (SQ || pos) && !sameBox(built3d, SQ)) rebuild3d();  // nothing to show: no unbounded terrain
    if (is3d && shown && SQ && !(relief && relief.sq === SQ)) measureForCamera(SQ);
    vis('plan', shown && (!is3d || !SQ));
    vis('plansq', shown && is3d && !!SQ);
    ['band', 'mask', 'frame'].forEach((id) => vis(id, is3d && !!SQ));
    // setTerrain rebuilds the whole terrain renderer: only when its state really changes
    const want = is3d && shown && !!(SQ || pos) && !!map.getSource('dem'), cur = map.getTerrain();
    if (want && (!cur || cur.exaggeration !== exag)) map.setTerrain({ source: 'dem', exaggeration: exag });
    else if (!want && cur) map.setTerrain(null);
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
  map.on('zoomend', () => { if (SQ && mode === '3d') updateFrame(); });

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
  // So with terrain every move is a jumpTo, and the follow mode glides by jumping each frame.
  let glideFrame = 0, movingUntil = 0;
  function move(camera, duration) {
    cancelAnimationFrame(glideFrame);
    const animate = duration && !map.getTerrain();
    movingUntil = animate ? performance.now() + duration : 0;
    if (animate) map.easeTo(Object.assign({ duration }, camera)); else map.jumpTo(camera);
  }
  function glide(to, duration) {
    cancelAnimationFrame(glideFrame);
    if (!map.getTerrain()) { map.easeTo({ center: to, duration }); return; }
    const from = map.getCenter(), t0 = performance.now();
    const step = (now) => {
      const k = Math.min(1, (now - t0) / duration), e = k * (2 - k);
      map.jumpTo({ center: [from.lng + (to[0] - from.lng) * e, from.lat + (to[1] - from.lat) * e] });
      if (k < 1) glideFrame = requestAnimationFrame(step);
    };
    glideFrame = requestAnimationFrame(step);
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
  function fit(duration) {
    duration = duration || 0;
    const is3d = mode === '3d';
    const box = map.getContainer().getBoundingClientRect();
    const padding = Math.round(Math.max(8, Math.min(48, Math.min(box.width, box.height) * 0.07)));
    if (follow && pos) {
      fitPending = null;
      followFitted = true;
      const cam = followCamera(box, is3d);
      map.setMinZoom(is3d ? Math.max(0, cam.zoom - 5) : 0);
      move(cam, duration);
      return;
    }
    if (is3d && SQ && !(relief && relief.sq === SQ)) { fitPending = duration; return; }
    fitPending = null;
    if (is3d && SQ) {
      const block = blockCamera(SQ, relief, box, padding);
      map.setMinZoom(Math.max(0, block.zoom - 5));  // the block alone in a white world: no further out
      move({ center: block.center, zoom: block.zoom, pitch: block.pitch, bearing: 0, padding: block.padding }, duration);
      return;
    }
    let b = dataBox() || SQ;
    if (!b && pos) b = squareAround(pos.lon, pos.lat, 1);
    if (!b) return;
    if (b[2] - b[0] < 1e-5 && b[3] - b[1] < 1e-5) b = squareAround(b[0], b[1], 0.5);
    const cam = map.cameraForBounds([[b[0], b[1]], [b[2], b[3]]], { padding, maxZoom: 17 });
    if (!cam) return;
    map.setMinZoom(0);
    move({ center: cam.center, zoom: cam.zoom, pitch: is3d ? PITCH : 0, bearing: 0, padding: NO_PADDING }, duration);
  }
  ['dragstart', 'zoomstart', 'rotatestart', 'pitchstart'].forEach((ev) => map.on(ev, (e) => {
    if (!e.originalEvent) return;
    cancelAnimationFrame(glideFrame);
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

  // One short feature per segment, coloured by its own speed: appending is one updateData of one feature,
  // and the whole trail is recoloured at most once a second, only when the mean moved.
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
    const features = [];
    for (let i = 1; i < live.coords.length; i++) features.push(liveFeature(i));
    src('live').setData({ type: 'FeatureCollection', features });
    live.colouredAvg = live.count ? live.sum / live.count : 0;
    live.colouredAt = Date.now();
    if (!track) showLegend(live.colouredAvg || null);
  }
  function liveMaybeRecolour() {
    const avg = live.count ? live.sum / live.count : 0;
    if (!avg || Math.abs(avg - live.colouredAvg) <= live.colouredAvg * 0.03) return;
    const wait = 1000 - (Date.now() - live.colouredAt);
    if (wait <= 0) { liveRedraw(); return; }
    if (!live.pending) live.pending = setTimeout(() => { live.pending = 0; liveRedraw(); }, wait);
  }

  // ---------------------------------------------------------------- position

  let posMarker = null, posEl = null, posAdded = false;
  function ensurePosMarker() {
    if (posMarker) return;
    posEl = document.createElement('div');
    posEl.className = 'smap-pos';
    for (const part of ['halo', 'arrow', 'dot']) {
      const d = document.createElement('div'); d.className = part; posEl.appendChild(d);
    }
    posMarker = new maplibregl.Marker({ element: posEl, anchor: 'center', opacityWhenCovered: '1' });
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
  function circle(lon, lat, r) {
    const kx = 111320 * Math.cos(lat * Math.PI / 180), pts = [];
    for (let i = 0; i <= 48; i++) {
      const a = i / 48 * 2 * Math.PI;
      pts.push([lon + Math.cos(a) * r / kx, lat + Math.sin(a) * r / 110540]);
    }
    return { type: 'Feature', properties: {}, geometry: { type: 'Polygon', coordinates: [pts] } };
  }
  function updatePos() {
    if (!pos) { if (posMarker) posMarker.remove(); posAdded = false; src('acc').setData(EMPTY); return; }
    ensurePosMarker();
    const outside = SQ && mode === '3d' && !follow
      && (pos.lon < SQ[0] || pos.lon > SQ[2] || pos.lat < SQ[1] || pos.lat > SQ[3]);
    if (outside) {
      // looking at an activity done elsewhere: no marker floating off the block, and nothing to warn about
      posMarker.remove(); posAdded = false; src('acc').setData(EMPTY);
      return;
    }
    if (banner) setBanner(null);
    posMarker.setLngLat([pos.lon, pos.lat]);
    if (!posAdded) { posMarker.addTo(map); posAdded = true; }
    posEl.classList.toggle('heading', pos.heading != null);
    orientArrow();
    src('acc').setData(pos.accuracy ? circle(pos.lon, pos.lat, pos.accuracy) : EMPTY);
  }

  // ---------------------------------------------------------------- peaks

  let peakMarkers = [], peakSeq = 0, peakTimer = 0;
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
  function setPeaks(list) {
    peakMarkers.forEach((m) => m.remove());
    peakMarkers = list.map((p) => {
      const root = el('div', 'smap-peak');
      root.appendChild(el('div', 'label', p.ele != null ? `${p.name} · ${p.ele} m` : p.name));
      root.appendChild(el('div', 'stem'));
      const card = el('div');
      card.appendChild(el('b', null, p.name));
      card.appendChild(el('div', null, p.ele != null ? `Altitude : ${p.ele} m` : 'Altitude inconnue'));
      card.appendChild(el('div', null, `Position : ${p.lat.toFixed(4)}° N, ${p.lon.toFixed(4)}° E`));
      card.appendChild(el('div', null, `Importance : ${p.importance} (1 = majeur)`));
      card.appendChild(el('div', null, 'Source : IGN BD TOPO'));
      const popup = new maplibregl.Popup({ offset: 34, closeButton: false, maxWidth: '240px' }).setDOMContent(card);
      return new maplibregl.Marker({ element: root, anchor: 'bottom' }).setLngLat([p.lon, p.lat]).setPopup(popup).addTo(map);
    });
  }

  // MapLibre changes the camera's elevation while rendering (DEM tiles arriving) and the ground under a marker
  // when its DEM tile loads, both without a move event: the DOM markers would stay where they were drawn, off the
  // block or off screen. They are re-placed after such a frame.
  let markerFrame = 0, lastElevation = null;
  function replaceMarkers() {
    if (markerFrame) return;
    markerFrame = requestAnimationFrame(() => {
      markerFrame = 0;
      if (posAdded) posMarker.setLngLat(posMarker.getLngLat());
      peakMarkers.forEach((m) => m.setLngLat(m.getLngLat()));
    });
  }
  map.on('render', () => {
    const e = map.transform.elevation;
    if (e !== lastElevation) { lastElevation = e; replaceMarkers(); }
  });
  map.on('sourcedata', (e) => { if (e.sourceId === 'dem' && e.tile) replaceMarkers(); });

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
      if (n >= 2) {
        const s = src('live');
        if (typeof s.updateData === 'function' && n > 2) s.updateData({ add: [liveFeature(n - 1)] });
        else liveRedraw();
      }
      liveMaybeRecolour(); show();
    },
    setDots(list) {
      src('dots').setData({ type: 'FeatureCollection', features: (list || []).map((d) => ({ type: 'Feature', properties: { color: d.color }, geometry: { type: 'Point', coordinates: [d.lon, d.lat] } })) });
    },
    setPos(lon, lat, heading, accuracy) {
      if (lon == null || lat == null) { pos = null; setBanner(null); refreshSquare(); updatePos(); return; }  // cleared by the host
      pos = { lon, lat, heading: heading == null ? null : heading, accuracy: accuracy || null };
      show();
      refreshSquare();
      updatePos();
      if (follow && !followFitted) fit();
      else if (follow && performance.now() >= movingUntil) glide([lon, lat], 600);  // never cut a fit's zoom short
    },
    locFail() {
      pos = null; updatePos();
      setBanner('Localisation actuelle impossible');
    },
    setFollow(on) {
      follow = !!on;
      followFitted = false;
      updatePos();
      if (follow && pos) fit(400);
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
      mode = m === '2d' ? '2d' : '3d';
      applyMode(); updatePos(); updateFrame(); fit(350);
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
      map.setPaintProperty('route', 'line-color', c.route);
      for (const id of ['lines-case', 'track-case', 'live-case']) map.setPaintProperty(id, 'line-color', c.casing);
      for (const id of ['plan', 'plansq']) {
        if (!map.getLayer(id)) continue;
        map.setPaintProperty(id, 'raster-brightness-max', c.brightness);
        map.setPaintProperty(id, 'raster-saturation', c.saturation);
      }
    },
    setCursor(lon, lat) {
      src('cursor').setData(lon == null ? EMPTY : { type: 'Feature', properties: {}, geometry: { type: 'Point', coordinates: [lon, lat] } });
    },
    fit() { fit(300); },
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
