'use strict';
/*
 * Mycelium terrarium. Plain JS, no build step, no framework. Replaceable: everything the UI needs from the app goes
 * through the Feed object below. Colors come only from packet_types.json (via Feed.getPacketTypes()).
 */

// ---- Feed: the only code that talks to the app. feed.mock.js swaps it for a fixture (index.html?mock=1). ----
const BridgeFeed = {
  _cbs: [],
  _call(method, args) {
    return new Promise((resolve, reject) => {
      try { resolve(JSON.parse(window.MyceliumBridge.call(method, JSON.stringify(args || {})))); } catch (e) { reject(e); }
    });
  },
  getTerrarium(appId) { return this._call('terrarium', appId ? { app_id: appId } : {}); },
  getPresets() { return this._call('presets'); },
  getPacketTypes() { return this._call('packet_types'); },
  ack(appId, stage) { return this._call('terrarium_ack', { app_id: appId, stage: stage }); },
  setPreset(appId, presetId) { return this._call('set_preset', { app_id: appId, preset_id: presetId }); },
  onUpdate(cb) { this._cbs.push(cb); },
  /** The app pushes terrarium_update events here: window.Feed._push({...}). */
  _push(ev) { for (const cb of this._cbs) cb(ev); },
  assetUrl(path) { return '/assets/' + path; },
};
window.Feed = window.Feed || BridgeFeed;
const Feed = window.Feed;

// ---- placeholder art: identical to scripts/make_placeholders.py (frame_pixels). Keep the two in sync. ----
const PH = {
  SOIL: '#6D4C41', SOIL_TOP: '#8D6E63', STEM: '#2E7D32', LEAF: '#66BB6A', KEY: '#FF00FF', POLLEN: '#FFEB3B', INK: '#212121',
  SWAY: [0, 1, 0, -1],
  DIGITS: {
    0: ['111', '101', '101', '101', '111'], 1: ['010', '110', '010', '010', '111'], 2: ['111', '001', '111', '100', '111'],
    3: ['111', '001', '111', '001', '111'], 4: ['101', '101', '111', '001', '001'], 5: ['111', '100', '111', '001', '111'],
    6: ['111', '100', '111', '101', '111'], 7: ['111', '001', '010', '010', '010'], 8: ['111', '101', '111', '101', '111'],
    9: ['111', '101', '111', '001', '111'],
  },
  /** Default strip specs when a preset has none (same sizes as preset01). */
  SPECS: [
    { frame_w: 32, frame_h: 32, frames: 4, fps: 6 },
    { frame_w: 32, frame_h: 48, frames: 6, fps: 8 },
    { frame_w: 48, frame_h: 64, frames: 8, fps: 8 },
  ],
};

function placeholderFrame(ctx, ox, stage, frame, fw, fh) {
  const put = (x, y, c) => { if (x >= 0 && x < fw && y >= 0 && y < fh) { ctx.fillStyle = c; ctx.fillRect(ox + x, y, 1, 1); } };
  for (let x = 0; x < fw; x++) { put(x, fh - 3, PH.SOIL_TOP); put(x, fh - 2, PH.SOIL); put(x, fh - 1, PH.SOIL); }
  const sway = PH.SWAY[frame % 4];
  const height = Math.min([12, 24, 40][stage - 1], fh - 8);
  const baseY = fh - 4;
  const cx = Math.floor(fw / 2);
  for (let i = 0; i < height; i++) put(cx + (i > Math.floor(height * 2 / 3) ? sway : 0), baseY - i, PH.STEM);
  const leaves = [2, 4, 6][stage - 1];
  const len = stage < 3 ? 3 : 4;
  for (let i = 0; i < leaves; i++) {
    const y = baseY - Math.floor((i + 1) * height / (leaves + 1));
    const s = (baseY - y) > Math.floor(height / 2) ? sway : 0;
    const d = i % 2 === 0 ? -1 : 1;
    for (let k = 1; k <= len; k++) {
      put(cx + s + d * k, y, k === len ? PH.KEY : PH.LEAF);
      put(cx + s + d * k, y - 1, k < len ? PH.LEAF : PH.KEY);
    }
  }
  if (stage === 3) {
    const top = baseY - height;
    for (const dx of [-1, 0, 1]) for (const dy of [-1, 0, 1]) put(cx + sway + dx, top + dy, PH.KEY);
    put(cx + sway, top, PH.POLLEN);
  }
  String(frame + 1).split('').forEach((ch, j) => PH.DIGITS[ch].forEach((row, r) => row.split('').forEach((bit, c) => {
    if (bit === '1') put(1 + j * 4 + c, 1 + r, PH.INK);
  })));
}

function placeholderStrip(stage, spec) {
  const c = document.createElement('canvas');
  c.width = spec.frame_w * spec.frames;
  c.height = spec.frame_h;
  const ctx = c.getContext('2d');
  for (let f = 0; f < spec.frames; f++) placeholderFrame(ctx, f * spec.frame_w, Math.min(Math.max(stage, 1), 3), f, spec.frame_w, spec.frame_h);
  return c;
}

// ---- state ----
const T = {
  types: [], typeById: {}, neutral: null, presets: {}, orgs: new Map(),
  strips: new Map(),      // "preset|kind|n" -> {spec, canvas, state}
  tinted: new Map(),      // strip key + "|" + color + "|" + mode -> canvas
  selected: null, refreshTimer: 0,
};

const colorOf = (id) => (T.typeById[id] && T.typeById[id].color) || T.neutral;

function rgb(hex) {
  const h = (hex || '').replace('#', '');
  return [parseInt(h.slice(0, 2), 16), parseInt(h.slice(2, 4), 16), parseInt(h.slice(4, 6), 16)];
}

// ---- sprites ----
function stripSpec(presetId, kind, n) {
  const p = T.presets[presetId] || {};
  if (kind === 'upgrade') return (p.upgrade || [])[n - 1] || null;
  const list = p.stages || PH.SPECS;
  return list[Math.min(n, list.length) - 1];
}

/** A strip canvas, ready now: the PNG when loaded, else the placeholder (never a broken image). */
function strip(presetId, kind, n) {
  const key = presetId + '|' + kind + '|' + n;
  let s = T.strips.get(key);
  if (s) return s;
  const spec = stripSpec(presetId, kind, n);
  if (!spec) return null;
  s = { key: key, spec: spec, canvas: placeholderStrip(n, spec), state: 'placeholder' };
  T.strips.set(key, s);
  const hasFile = T.presets[presetId] && spec.file;
  if (hasFile && kind === 'stage') {
    const img = new Image();
    img.onload = () => {
      const c = document.createElement('canvas');
      c.width = img.width; c.height = img.height;
      const ctx = c.getContext('2d');
      ctx.drawImage(img, 0, 0);
      try { ctx.getImageData(0, 0, 1, 1); } catch (e) { return; }   // tainted (file://): keep the placeholder
      s.canvas = c; s.state = 'image';
      dropTints(key);
    };
    img.src = Feed.assetUrl('organisms/' + presetId + '/' + spec.file);
  } else if (hasFile && kind === 'upgrade') {
    s.state = 'loading';
    const img = new Image();
    img.onload = () => {
      const c = document.createElement('canvas');
      c.width = img.width; c.height = img.height;
      c.getContext('2d').drawImage(img, 0, 0);
      s.canvas = c; s.state = 'image';
    };
    img.onerror = () => { s.state = 'failed'; };
    img.src = Feed.assetUrl('organisms/' + presetId + '/' + spec.file);
  }
  return s;
}

function dropTints(stripKey) { for (const k of [...T.tinted.keys()]) if (k.startsWith(stripKey + '|')) T.tinted.delete(k); }

/** Palette swap (key colors -> type color) or multiply tint, per the preset's "tint". Cached per color. */
function tinted(presetId, s, color, white) {
  const tint = (T.presets[presetId] || {}).tint || { mode: 'palette_swap', key_colors: ['#FF00FF'] };
  const key = s.key + '|' + (white ? 'white' : color + '|' + tint.mode) + '|' + s.state;
  let c = T.tinted.get(key);
  if (c) return c;
  c = document.createElement('canvas');
  c.width = s.canvas.width; c.height = s.canvas.height;
  const ctx = c.getContext('2d');
  ctx.drawImage(s.canvas, 0, 0);
  try {
    if (white) {
      ctx.globalCompositeOperation = 'source-in';
      ctx.fillStyle = '#FFFFFF';
      ctx.fillRect(0, 0, c.width, c.height);
    } else if (tint.mode === 'multiply') {
      ctx.globalCompositeOperation = 'multiply';
      ctx.fillStyle = color;
      ctx.fillRect(0, 0, c.width, c.height);
      ctx.globalCompositeOperation = 'destination-in';
      ctx.drawImage(s.canvas, 0, 0);
    } else {
      const keys = (tint.key_colors || []).map(rgb);
      const [r, g, b] = rgb(color);
      const img = ctx.getImageData(0, 0, c.width, c.height);
      const d = img.data;
      for (let i = 0; i < d.length; i += 4) {
        if (d[i + 3] === 0) continue;
        for (const k of keys) if (d[i] === k[0] && d[i + 1] === k[1] && d[i + 2] === k[2]) { d[i] = r; d[i + 1] = g; d[i + 2] = b; break; }
      }
      ctx.putImageData(img, 0, 0);
    }
  } catch (e) { /* tainted canvas: show untinted */ }
  T.tinted.set(key, c);
  return c;
}

// ---- organisms ----
/** Count types that say something about the app; os_packets (type unknown) only when nothing else was seen. */
function typeMix(counts) {
  const entries = Object.entries(counts || {}).filter(([, n]) => n > 0);
  const known = entries.filter(([t]) => t !== 'os_packets');
  return known.length ? known : entries;
}

function dominantColor(o) {
  const mix = typeMix(o.data.packet_counts_by_type);
  if (!mix.length) return T.neutral;
  mix.sort((a, b) => b[1] - a[1]);
  return colorOf(mix[0][0]);
}

function createOrg(app) {
  const el = document.createElement('div');
  el.className = 'cell';
  el.innerHTML = '<canvas class="org"></canvas><canvas class="bar" width="32" height="1"></canvas>' +
    '<div class="label"></div><div class="total"></div>';
  el.addEventListener('click', () => openPanel(app.app_id));
  document.getElementById('grid').appendChild(el);
  const o = {
    id: app.app_id, data: app, el: el,
    canvas: el.querySelector('canvas.org'), bar: el.querySelector('canvas.bar'),
    shown: Math.max(1, app.stage_shown || 1), anim: null, scale: 1,
  };
  T.orgs.set(o.id, o);
  return o;
}

function applyTerrarium(t) {
  const seen = new Set();
  for (const app of t.apps || []) {
    seen.add(app.app_id);
    const o = T.orgs.get(app.app_id) || createOrg(app);
    if (o.data.preset_id !== app.preset_id) o.anim = null;
    o.data = app;
    sync(o);
  }
  for (const [id, o] of T.orgs) if (!seen.has(id)) { o.el.remove(); T.orgs.delete(id); }
  document.getElementById('empty').hidden = T.orgs.size > 0;
  layout();
  if (T.selected) renderPanel();
}

/** Text, bar, and the state machine: idle(n) -> upgrade(n -> n+1) once per gained stage -> idle -> ack. */
function sync(o) {
  const d = o.data;
  o.el.dataset.stage = d.stage;                 // state on the DOM, for debugging (and headless checks)
  o.el.dataset.shown = o.shown;
  o.el.dataset.anim = o.anim ? o.anim.from + '>' + o.anim.to : '';
  o.el.querySelector('.label').textContent = d.label;
  o.el.querySelector('.total').textContent = d.total_packets + ' packets';
  drawBar(o);
  if (o.anim) return;
  if (o.shown < d.stage) startUpgrade(o);
  else if (o.shown > d.stage) {                 // fewer stages now (wipe or new thresholds): show it, no animation
    o.shown = d.stage;
    Feed.ack(o.id, o.shown);
  }
}

function startUpgrade(o) {
  const from = o.shown;
  const up = strip(o.data.preset_id, 'upgrade', from);
  o.anim = { from: from, to: from + 1, t0: performance.now(), sprite: up && up.state === 'image' ? up : null };
  o.el.dataset.anim = from + '>' + (from + 1);
}

function finishUpgrade(o) {
  o.shown = o.anim.to;
  o.anim = null;
  Feed.ack(o.id, o.shown);
  sync(o);                                       // several stages gained while closed: next upgrade in order
}

/** One-row pixel bar: the mix of packet types, each in its packet_types.json color. */
function drawBar(o) {
  const ctx = o.bar.getContext('2d');
  const w = o.bar.width;
  ctx.clearRect(0, 0, w, 1);
  const mix = typeMix(o.data.packet_counts_by_type);
  const total = mix.reduce((s, [, n]) => s + n, 0);
  if (!total) { ctx.fillStyle = T.neutral; ctx.fillRect(0, 0, w, 1); return; }
  const parts = mix.map(([t, n]) => ({ t: t, exact: n * w / total }));
  parts.forEach((p) => { p.px = Math.floor(p.exact); });
  let left = w - parts.reduce((s, p) => s + p.px, 0);
  parts.slice().sort((a, b) => (b.exact - b.px) - (a.exact - a.px)).forEach((p) => { if (left > 0) { p.px++; left--; } });
  let x = 0;
  for (const p of parts) { ctx.fillStyle = colorOf(p.t); ctx.fillRect(x, 0, p.px, 1); x += p.px; }
}

function drawStrip(o, ctx, s, frame, scale, color, white) {
  const fw = s.spec.frame_w, fh = s.spec.frame_h;
  const src = tinted(o.data.preset_id, s, color, white);
  const w = fw * scale, h = fh * scale;
  const dx = Math.floor((o.canvas.width - w) / 2);
  const dy = o.canvas.height - h;
  ctx.drawImage(src, (frame % s.spec.frames) * fw, 0, fw, fh, dx, dy, w, h);
}

const BUILTIN_MS = 800;

function drawOrg(o, now) {
  const ctx = o.canvas.getContext('2d');
  ctx.imageSmoothingEnabled = false;
  ctx.clearRect(0, 0, o.canvas.width, o.canvas.height);
  const color = dominantColor(o);
  const stageStrip = (n) => strip(o.data.preset_id, 'stage', Math.min(n, o.data.stage_count || n));
  const idleFrame = (s) => Math.floor(now / 1000 * (s.spec.fps || 6));
  if (!o.anim) {
    const s = stageStrip(o.shown);
    if (s) drawStrip(o, ctx, s, idleFrame(s), o.scale, color, false);
    return;
  }
  const t = now - o.anim.t0;
  if (o.anim.sprite) {                           // the preset's own upgrade strip, once
    const s = o.anim.sprite;
    const f = Math.floor(t / 1000 * (s.spec.fps || 12));
    if (f >= s.spec.frames) { finishUpgrade(o); return drawOrg(o, now); }
    drawStrip(o, ctx, s, f, o.scale, color, false);
    return;
  }
  if (t >= BUILTIN_MS) { finishUpgrade(o); return drawOrg(o, now); }   // built-in: white flash, 2-step pulse, new stage
  const oldS = stageStrip(o.anim.from), newS = stageStrip(o.anim.to);
  if (t < 250) drawStrip(o, ctx, oldS, idleFrame(oldS), o.scale, color, true);
  else {
    const pulse = (t < 450 || (t >= 550 && t < 700)) ? 1 : 0;
    drawStrip(o, ctx, newS, idleFrame(newS), o.scale + pulse, color, false);
  }
}

function loop() {
  requestAnimationFrame(loop);
  document.body.dataset.frames = (+document.body.dataset.frames || 0) + 1;
  const now = performance.now();               // same clock as anim.t0 (rAF timestamps can differ from it)
  for (const o of T.orgs.values()) {
    try { drawOrg(o, now); } catch (e) { o.anim = null; reportError(e); }   // one organism never freezes the rest
  }
}

function reportError(e) {
  if (T.lastError === String(e)) return;
  T.lastError = String(e);
  showError(String(e && e.stack || e));
}
window.addEventListener('error', (ev) => reportError(ev.error || ev.message));

// ---- layout: integer scale only, in device pixels ----
function layout() {
  const dpr = window.devicePixelRatio || 1;
  for (const o of T.orgs.values()) {
    const box = o.canvas.getBoundingClientRect();
    o.canvas.width = Math.max(1, Math.round(box.width * dpr));
    o.canvas.height = Math.max(1, Math.round(box.height * dpr));
    let maxW = 1, maxH = 1;
    for (let n = 1; n <= (o.data.stage_count || 3); n++) {
      const spec = stripSpec(o.data.preset_id, 'stage', n);
      if (spec) { maxW = Math.max(maxW, spec.frame_w); maxH = Math.max(maxH, spec.frame_h); }
    }
    o.scale = Math.max(1, Math.floor(Math.min(o.canvas.width / maxW, o.canvas.height / maxH)));
  }
  drawBackground();
}

/** Glass frame and a pixel ground strip, drawn on one background canvas. */
function drawBackground() {
  const c = document.getElementById('bg');
  const dpr = window.devicePixelRatio || 1;
  const px = Math.max(2, Math.round(3 * dpr));
  const box = c.getBoundingClientRect();
  c.width = Math.round(box.width * dpr);
  c.height = Math.round(box.height * dpr);
  const ctx = c.getContext('2d');
  ctx.imageSmoothingEnabled = false;
  const cols = Math.ceil(c.width / px), rows = Math.ceil(c.height / px);
  const cell = (x, y, color) => { ctx.fillStyle = color; ctx.fillRect(x * px, y * px, px, px); };
  ctx.clearRect(0, 0, c.width, c.height);
  for (let x = 0; x < cols; x++) for (let y = rows - 4; y < rows; y++) {
    cell(x, y, y === rows - 4 ? '#7A5A44' : ((x * 7 + y * 3) % 5 === 0 ? '#4E342E' : '#5D4037'));
  }
  for (let x = 0; x < cols; x++) { cell(x, 0, '#B3E5FC'); cell(x, rows - 5, '#81D4FA'); }
  for (let y = 0; y < rows - 4; y++) { cell(0, y, '#B3E5FC'); cell(cols - 1, y, '#B3E5FC'); }
  for (let i = 2; i < Math.min(12, rows - 6); i++) cell(2, i, 'rgba(255,255,255,0.6)');   // glass highlight
}

// ---- legend ----
function renderLegend() {
  const ul = document.getElementById('legend-list');
  ul.innerHTML = '';
  for (const t of T.types) {
    const li = document.createElement('li');
    if (t.observed === false) li.className = 'dim';
    li.innerHTML = '<span class="sw"></span><span class="lbl"></span>';
    li.querySelector('.sw').style.background = t.color || T.neutral;
    li.querySelector('.lbl').textContent = t.label + (t.observed === false ? ' (not seen by this engine)' : '') +
      (t.observed_note ? ' (' + t.observed_note + ')' : '');
    ul.appendChild(li);
  }
  document.getElementById('legend-toggle').onclick = () => document.getElementById('legend').classList.toggle('open');
}

// ---- detail panel ----
function openPanel(appId) { T.selected = appId; T.choosing = false; renderPanel(); }

function closePanel() { T.selected = null; document.getElementById('panel').hidden = true; }

function renderPanel() {
  const o = T.orgs.get(T.selected);
  const p = document.getElementById('panel');
  if (!o) return closePanel();
  const d = o.data;
  const preset = T.presets[d.preset_id] || {};
  const next = d.next_threshold == null ? 'top stage' : String(d.next_threshold);
  let html = '<button class="close" aria-label="Close">×</button>' +
    '<h2></h2><p class="sub"></p>' +
    '<p>Stage <b>' + d.stage + '</b> of ' + d.stage_count + ' · growth <b>' + d.growth + '</b> / ' + next +
    (d.sanctuary_pending ? ' · ' + d.sanctuary_pending + ' waiting in Sanctuary' : '') + '</p><table>';
  for (const t of T.types) {
    const n = (d.packet_counts_by_type || {})[t.id] || 0;
    html += '<tr class="' + (n ? '' : 'zero') + '"><td><span class="sw" style="background:' + (t.color || T.neutral) + '"></span></td>' +
      '<td>' + escapeHtml(t.label) + '</td><td class="n">' + n + '</td></tr>';
  }
  html += '</table><button class="change">Change organism</button><div class="presets"></div>';
  p.innerHTML = html;
  p.querySelector('h2').textContent = d.label;
  p.querySelector('.sub').textContent = (preset.name || d.preset_id) + ' · ' + d.total_packets + ' packets';
  p.querySelector('.close').onclick = closePanel;
  p.querySelector('.change').onclick = () => {
    const box = p.querySelector('.presets');
    box.innerHTML = '';
    for (const [id, pr] of Object.entries(T.presets)) {
      const b = document.createElement('button');
      b.textContent = (pr.name || id) + (id === d.preset_id ? ' ✓' : '');
      b.onclick = () => Feed.setPreset(d.app_id, id).then(refresh);
      box.appendChild(b);
    }
  };
  p.hidden = false;
}

function escapeHtml(s) { return String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]); }

// ---- data flow ----
function refresh() { return Feed.getTerrarium().then(applyTerrarium); }

function onUpdate(ev) {
  const o = T.orgs.get(ev.app_id);
  if (o) {
    o.data.packet_counts_by_type = ev.counts;
    o.data.total_packets = Object.values(ev.counts || {}).reduce((s, n) => s + n, 0);
    o.data.stage = ev.stage;
    sync(o);
  }
  clearTimeout(T.refreshTimer);                  // growth / next_threshold / new apps: one full refresh shortly after
  T.refreshTimer = setTimeout(refresh, 400);
}

function showError(msg) {
  const e = document.getElementById('error');
  e.textContent = msg;
  e.hidden = false;
}

async function boot() {
  try {
    const [types, presets, terrarium] = await Promise.all([Feed.getPacketTypes(), Feed.getPresets(), Feed.getTerrarium()]);
    T.types = types.types || [];
    T.neutral = types.neutral || 'gray';
    for (const t of T.types) T.typeById[t.id] = t;
    for (const p of presets.presets || []) T.presets[p.id] = p.preset || {};
    renderLegend();
    applyTerrarium(terrarium);
    Feed.onUpdate(onUpdate);
    window.addEventListener('resize', layout);
    requestAnimationFrame(loop);
    // Desktop helpers: ?legend=1 opens the legend, ?panel=<app_id> opens that organism's panel.
    const q = new URLSearchParams(location.search);
    if (q.get('legend') === '1') document.getElementById('legend').classList.add('open');
    if (q.get('panel')) openPanel(q.get('panel'));
  } catch (e) {
    showError('Could not load the terrarium: ' + e + (window.Feed === BridgeFeed ? '' :
      '. Mock mode reads engine assets: run "python3 -m http.server" in the repo root and open ' +
      'http://localhost:8000/app/src/main/assets/terrarium/index.html?mock=1'));
  }
}

boot();
