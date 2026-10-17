'use strict';
/*
 * Mycelium terrarium. Plain JS, no build step, no framework. Replaceable: everything the UI needs from the app goes
 * through Feed (engine data) and Host (words and settings from the app). Colors come only from packet_types.json.
 */

// ---- Feed: the only code that talks to the engine. feed.mock.js swaps it for a fixture (index.html?mock=1). ----
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
  /** Today's Snoops (tracker companies) per app, and whether each one was shooed away (a company block rule). */
  async getSnoops(appIds, fromMs) {
    const rules = ((await this._call('rules')).rules || []).filter((r) => r.scope === 'company');
    const out = {};
    for (const id of appIds) {
      const s = await this._call('stats', { fn: 'appSummary', package: id, from_ms: fromMs });
      out[id] = {
        contacts: s.lookups || 0, blocked: s.blocked || 0,
        snoops: (s.trackers || []).filter((t) => t.company).map((t) => ({
          company: t.company, category: t.category || 'unknown', hits: t.hits || 0,
          shooed: rules.some((r) => r.value.toLowerCase() === t.company.toLowerCase() && (r.package === id || r.package === '*')),
        })),
      };
    }
    return out;
  },
  /** Shoo a Snoop away from one app: a company block rule for that app only. */
  shoo(appId, company) { return this._call('block', { scope: 'company', value: company, package: appId }); },
  onUpdate(cb) { this._cbs.push(cb); },
  /** The app pushes terrarium_update events here: window.Feed._push({...}). */
  _push(ev) { for (const cb of this._cbs) cb(ev); },
  assetUrl(path) { return '/assets/' + path; },
};
window.Feed = window.Feed || BridgeFeed;
const Feed = window.Feed;

// ---- Host: words (from the app's strings.xml, so they translate) and the user's motion settings. ----
const Host = window.MyceliumHost;
const WORDS = (() => { try { return JSON.parse(Host.strings()); } catch (e) { return {}; } })();
const CFG = (() => { try { return JSON.parse(Host.settings()); } catch (e) { return {}; } })();
CFG.still = !!CFG.reduce_motion;
CFG.saver = !!CFG.battery_saver;

const fmt = (t, args) => String(t).replace(/%(\d)\$[sd]/g, (m, i) => (args[i - 1] == null ? '' : args[i - 1]));
/** A sentence by key; unknown keys show the key itself (never a blank). */
function S(key, ...args) { return fmt(WORDS[key] || key, args); }
/** A counted sentence ("1 Snoop" / "3 Snoops"); args fill %1$d (the count) and onwards. */
function P(key, n, ...rest) {
  try { return Host.plural(key, n, JSON.stringify([n, ...rest])); } catch (e) { return key + ' ' + n; }
}

// The host app picks the panel colors: ?theme=day (warm paper) or night (default). The sky follows the phone clock.
if (/[?&]theme=day/.test(location.search)) document.documentElement.classList.add('day');

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
  refreshTimer: 0,
  snoops: {},             // app_id -> {contacts, blocked, snoops:[{company, category, hits, shooed}]}
  dialog: null,           // {kind:'org'|'snoop'|'confirm'|'presets', appId, company}
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
  el.innerHTML = '<div class="stagebox"><canvas class="org"></canvas><div class="snoops"></div></div>' +
    '<canvas class="bar" width="32" height="1"></canvas><div class="label"></div><canvas class="stars"></canvas>';
  el.setAttribute('role', 'button');
  el.tabIndex = 0;
  el.addEventListener('click', (ev) => { if (!ev.target.closest('.snoop')) openDialog({ kind: 'org', appId: app.app_id }); });
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
  const empty = document.getElementById('empty');
  empty.textContent = S('terr_empty');
  empty.hidden = T.orgs.size > 0;
  layout();
  renderSnoops();
  if (T.dialog) renderDialog();
}

/** Text, bar, and the state machine: idle(n) -> upgrade(n -> n+1) once per gained stage -> idle -> ack. */
function sync(o) {
  const d = o.data;
  o.el.dataset.stage = d.stage;                 // state on the DOM, for debugging (and headless checks)
  o.el.dataset.shown = o.shown;
  o.el.dataset.anim = o.anim ? o.anim.from + '>' + o.anim.to : '';
  o.el.querySelector('.label').textContent = d.label;
  o.el.setAttribute('aria-label', d.label + '. ' + S('terr_org_stage', d.stage, d.stage_count));
  drawBar(o);
  drawStars(o);
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
  const idleFrame = (s) => (CFG.still ? 0 : Math.floor(now / 1000 * (s.spec.fps || 6)));
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

// ---- stage stars under each organism: filled for each stage reached ----
const STAR = ['..X..', '.XXX.', 'XXXXX', '.XXX.', '.X.X.'];

function drawStars(o) {
  const c = o.el.querySelector('canvas.stars');
  const n = o.data.stage_count || 3;
  const dpr = window.devicePixelRatio || 1;
  const k = Math.max(1, Math.round(2 * dpr));
  c.width = n * 6 * k; c.height = 5 * k;
  c.style.width = (c.width / dpr) + 'px'; c.style.height = (c.height / dpr) + 'px';
  const ctx = c.getContext('2d');
  for (let i = 0; i < n; i++) {
    ctx.fillStyle = i < o.data.stage ? css('--gold') : css('--star-off');
    STAR.forEach((row, y) => row.split('').forEach((b, x) => { if (b === 'X') ctx.fillRect((i * 6 + x) * k, y * k, k, k); }));
  }
}

// ---- main loop: organisms, Snoops bobbing, the scene. Slower in battery-saver mode, paused when hidden. ----
let lastFrame = 0;
function loop(now) {
  requestAnimationFrame(loop);
  if (document.hidden) return;
  if (CFG.saver && now - lastFrame < 200) return;   // about 5 frames a second
  lastFrame = now;
  document.body.dataset.frames = (+document.body.dataset.frames || 0) + 1;
  const t = performance.now();                       // same clock as anim.t0 (rAF timestamps can differ from it)
  for (const o of T.orgs.values()) {
    try { drawOrg(o, t); } catch (e) { o.anim = null; reportError(e); }   // one organism never freezes the rest
  }
  bobSnoops(t);
  drawScene(t);
  drawFx(t);
}

function reportError(e) {
  if (T.lastError === String(e)) return;
  T.lastError = String(e);
  console.error(e);
  showError();
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
    drawStars(o);
  }
  for (const id of ['scene', 'fx']) {
    const c = document.getElementById(id);
    // Size from innerWidth/innerHeight: Android WebView can resolve 100vh to 0 when it starts at zero height.
    c.style.width = innerWidth + 'px';
    c.style.height = innerHeight + 'px';
    c.width = Math.round(innerWidth * dpr);
    c.height = Math.round(innerHeight * dpr);
  }
  T.sceneDrawn = 0;
  drawScene(performance.now());
}

/** A scene color from terrarium.css. */
const css = (name) => getComputedStyle(document.documentElement).getPropertyValue(name).trim();

// ---- the scene: sky from the phone clock, sun or moon, stars, clouds, hills, soil and swaying grass ----
const SKY = {   // top, horizon, hills
  night: ['#12152A', '#2A2F55', '#262D52'],
  dawn: ['#5D6AA8', '#F4B183', '#4E5A7A'],
  day: ['#6DB8DD', '#CDEBF5', '#7FAE6A'],
  dusk: ['#4B3F7A', '#F08A5D', '#4A3D5E'],
};

function phaseOf(h) { return h < 5 || h >= 21 ? 'night' : h < 7 ? 'dawn' : h < 18 ? 'day' : 'dusk'; }

function mix(a, b, f) {
  const x = rgb(a), y = rgb(b);
  return 'rgb(' + x.map((v, i) => Math.round(v + (y[i] - v) * f)).join(',') + ')';
}

function drawScene(t) {
  const every = CFG.still ? 60000 : CFG.saver ? 1000 : 125;   // the clock still moves the sky when motion is off
  if (T.sceneDrawn && t - T.sceneDrawn < every) return;
  T.sceneDrawn = t;
  const c = document.getElementById('scene');
  const dpr = window.devicePixelRatio || 1;
  const px = Math.max(2, Math.round(3 * dpr));
  const ctx = c.getContext('2d');
  ctx.imageSmoothingEnabled = false;
  const cols = Math.ceil(c.width / px), rows = Math.ceil(c.height / px);
  const cell = (x, y, color) => { ctx.fillStyle = color; ctx.fillRect(x * px, y * px, px, px); };
  const now = new Date();
  const hour = now.getHours() + now.getMinutes() / 60;
  const phase = phaseOf(now.getHours());
  const [top, horizon, hills] = SKY[phase];
  const ground = rows - 6;
  const sec = CFG.still ? 0 : t / 1000;
  document.documentElement.dataset.sky = phase;
  // sky: 8 bands with a checker dither between them
  const bands = 8;
  for (let y = 0; y < ground; y++) {
    const f = y / ground * bands;
    const b = Math.floor(f);
    const c1 = mix(top, horizon, b / bands), c2 = mix(top, horizon, Math.min(1, (b + 1) / bands));
    for (let x = 0; x < cols; x++) cell(x, y, (f - b > 0.5 && (x + y) % 2 === 0) ? c2 : c1);
  }
  // sun by day, moon at night, moving along an arc with the clock
  const arcF = phase === 'night' ? ((hour + 3) % 24) / 8 : (hour - 5) / 16;
  const bx = Math.round(cols * (0.1 + 0.8 * Math.min(1, Math.max(0, arcF))));
  const by = Math.round(ground * (0.45 - 0.3 * Math.sin(Math.PI * Math.min(1, Math.max(0, arcF)))));
  const r = 3;
  for (let dy = -r; dy <= r; dy++) for (let dx = -r; dx <= r; dx++) {
    if (dx * dx + dy * dy > r * r + 1) continue;
    if (phase === 'night') { if ((dx + 2) * (dx + 2) + dy * dy > 5) cell(bx + dx, by + dy, '#F2E8C8'); }
    else cell(bx + dx, by + dy, phase === 'day' ? '#FFE27A' : '#FFB36B');
  }
  // stars at night (a few twinkle), clouds otherwise
  if (phase === 'night' || phase === 'dawn') {
    const hash = (n) => { const x = Math.sin(n * 127.1) * 43758.5453; return x - Math.floor(x); };   // scattered, same every frame
    for (let i = 0; i < 40; i++) {
      const sx = Math.floor(hash(i + 1) * cols), sy = Math.floor(hash(i + 101) * ground * 0.75);
      const on = phase === 'night' ? ((i % 5) !== 0 || Math.floor(sec + i) % 3 !== 0) : i % 3 === 0;
      if (on) cell(sx, sy, i % 7 === 0 ? '#FFE27A' : '#E8ECFF');
    }
  } else {
    for (let i = 0; i < 3; i++) {
      const w = 7 + i * 2;
      const cx = Math.floor(((i * cols / 3) + sec * (0.4 + i * 0.15)) % (cols + w * 2)) - w;
      const cy = 4 + i * 5;
      for (let x = 0; x < w; x++) { cell(cx + x, cy, '#FFFFFF'); if (x > 1 && x < w - 2) cell(cx + x, cy - 1, '#FFFFFF'); }
      for (let x = 1; x < w - 1; x++) cell(cx + x, cy + 1, '#E6F1F7');
    }
  }
  // rolling hills
  for (let x = 0; x < cols; x++) {
    const h = Math.round(4 + 3 * Math.sin(x / 9) + 2 * Math.sin(x / 4.3 + 1));
    for (let y = ground - h; y < ground; y++) cell(x, y, hills);
  }
  // soil and grass; every few cells a plant that sways
  for (let x = 0; x < cols; x++) for (let y = ground; y < rows; y++) {
    cell(x, y, y === ground ? css('--grass') : ((x * 7 + y * 3) % 5 === 0 ? css('--soil-dark') : css('--soil')));
  }
  for (let x = 2; x < cols; x += 5) {
    const sway = CFG.still ? 0 : Math.round(Math.sin(sec * 1.6 + x * 0.7));
    const tall = 2 + (x * 13) % 3;
    for (let i = 1; i <= tall; i++) cell(x + (i === tall ? sway : 0), ground - i, css('--grass'));
    if ((x * 7) % 4 === 0) cell(x + sway, ground - tall - 1, (x % 3) ? '#F2C14E' : '#E9876B');   // a flower
  }
}

// ---- Snoops: little pests that sit around the app they visit ----
const SNOOP_SPOTS = [[-0.36, -0.55], [0.36, -0.7], [-0.3, -0.18], [0.34, -0.3]];
const snoopImg = new Image();
snoopImg.src = 'snoop.png';

function snoopScale() {
  const dpr = window.devicePixelRatio || 1;
  return Math.max(1, Math.round(34 * dpr / 16));    // whole device pixels per art pixel, about 34 css px wide
}

function renderSnoops() {
  const dpr = window.devicePixelRatio || 1;
  const k = snoopScale();
  for (const o of T.orgs.values()) {
    const box = o.el.querySelector('.snoops');
    const data = T.snoops[o.id];
    const visiting = data ? data.snoops.filter((s) => !s.shooed) : [];
    const shown = visiting.slice(0, 3);
    const key = shown.map((s) => s.company).join('|') + '#' + visiting.length;
    if (box.dataset.key === key) continue;
    box.dataset.key = key;
    box.innerHTML = '';
    shown.forEach((s, i) => {
      const b = document.createElement('button');
      b.className = 'snoop';
      b.dataset.company = s.company;
      b.dataset.phase = String(i * 1.7);
      b.setAttribute('aria-label', S('terr_snoop_cd', s.company));
      const [fx, fy] = SNOOP_SPOTS[i];
      b.style.left = 'calc(50% + ' + (fx * 100) + '% - ' + (8 * k / dpr) + 'px)';
      b.style.top = 'calc(100% + ' + (fy * 100) + '%)';
      const img = document.createElement('img');
      img.src = 'snoop.png';
      img.alt = '';
      img.style.width = (16 * k / dpr) + 'px';
      img.style.height = (16 * k / dpr) + 'px';
      b.appendChild(img);
      b.addEventListener('click', (ev) => { ev.stopPropagation(); openDialog({ kind: 'snoop', appId: o.id, company: s.company }); });
      box.appendChild(b);
    });
    if (visiting.length > 3) {
      const more = document.createElement('span');
      more.className = 'more';
      more.textContent = S('terr_more', visiting.length - 3);
      box.appendChild(more);
    }
  }
}

/** Snoops hover up and down by whole art pixels. */
function bobSnoops(t) {
  if (CFG.still) return;
  const dpr = window.devicePixelRatio || 1;
  const step = snoopScale() / dpr;
  for (const b of document.querySelectorAll('.snoop')) {
    const y = Math.round(Math.sin(t / 420 + +b.dataset.phase) * 1.4) * step;
    b.style.transform = 'translateY(' + y + 'px)';
  }
}

// ---- poof: a puff of pixel dust where a Snoop was shooed away ----
T.fx = [];
function poof(x, y) {
  if (CFG.still) return;
  const dpr = window.devicePixelRatio || 1;
  const t0 = performance.now();
  for (let i = 0; i < 14; i++) {
    const a = (i / 14) * Math.PI * 2;
    T.fx.push({ x: x * dpr, y: y * dpr, vx: Math.cos(a) * (0.6 + (i % 3) * 0.35), vy: Math.sin(a) * (0.6 + (i % 2) * 0.4) - 0.3,
      t0: t0, color: ['#FFFFFF', '#D9C8EE', '#B9A2D8'][i % 3] });
  }
}

function drawFx(t) {
  const c = document.getElementById('fx');
  const ctx = c.getContext('2d');
  if (!T.fx.length) { if (T.fxDirty) { ctx.clearRect(0, 0, c.width, c.height); T.fxDirty = false; } return; }
  T.fxDirty = true;
  ctx.clearRect(0, 0, c.width, c.height);
  const k = snoopScale();
  T.fx = T.fx.filter((p) => t - p.t0 < 650);
  for (const p of T.fx) {
    const age = (t - p.t0) / 16;
    const size = k * (age < 18 ? 3 : 2);
    ctx.globalAlpha = Math.max(0, 1 - (t - p.t0) / 650);
    ctx.fillStyle = p.color;
    ctx.fillRect(Math.round((p.x + p.vx * age * k) / k) * k, Math.round((p.y + p.vy * age * k) / k) * k, size, size);
  }
  ctx.globalAlpha = 1;
}

// ---- dialog card: a pixel dialogue box with a name plate ----
function openDialog(d) { T.dialog = d; renderDialog(); }

function closeDialog() { T.dialog = null; document.getElementById('dialog').hidden = true; }

function button(label, kind, onClick) {
  const b = document.createElement('button');
  b.className = 'pbtn ' + kind;
  b.textContent = label;
  b.onclick = onClick;
  return b;
}

function para(text, cls) {
  const p = document.createElement('p');
  if (cls) p.className = cls;
  p.textContent = text;
  return p;
}

function renderDialog() {
  const d = T.dialog;
  const o = d && T.orgs.get(d.appId);
  if (!o) return closeDialog();
  const box = document.getElementById('dialog');
  const plate = box.querySelector('.plate');
  const text = box.querySelector('.text');
  const buttons = box.querySelector('.buttons');
  const portrait = box.querySelector('canvas.portrait');
  text.innerHTML = '';
  buttons.innerHTML = '';
  const label = o.data.label;
  const info = T.snoops[o.id] || { contacts: 0, blocked: 0, snoops: [] };
  const snoop = info.snoops.find((s) => s.company === d.company);
  if (d.kind === 'org') {
    plate.textContent = label;
    drawPortrait(portrait, o, null);
    text.appendChild(para(P('terr_org_contacts', info.contacts, label)));
    const visiting = info.snoops.filter((s) => !s.shooed);
    text.appendChild(para(visiting.length ? P('terr_org_snoops', visiting.length) : S('terr_org_no_snoops')));
    if (info.blocked) text.appendChild(para(P('terr_org_shooed', info.blocked), 'small'));
    if (visiting.length) {
      const list = document.createElement('div');
      list.className = 'chips';
      for (const s of visiting) list.appendChild(button(s.company, 'chip', () => openDialog({ kind: 'snoop', appId: o.id, company: s.company })));
      text.appendChild(list);
    }
    text.appendChild(para(S('terr_org_stage', o.data.stage, o.data.stage_count) + ' · ' + S('terr_org_grow'), 'small'));
    buttons.appendChild(button(S('terr_change'), 'secondary', () => openDialog({ kind: 'presets', appId: o.id })));
    buttons.appendChild(button(S('terr_close'), 'primary', closeDialog));
  } else if (d.kind === 'presets') {
    plate.textContent = S('terr_pick_title', label);
    drawPortrait(portrait, o, null);
    const list = document.createElement('div');
    list.className = 'chips';
    for (const [id, pr] of Object.entries(T.presets)) {
      list.appendChild(button((pr.name || id) + (id === o.data.preset_id ? ' ✓' : ''), id === o.data.preset_id ? 'chip on' : 'chip',
        () => Feed.setPreset(o.id, id).then(refresh).then(() => openDialog({ kind: 'org', appId: o.id }))));
    }
    text.appendChild(list);
    buttons.appendChild(button(S('terr_back'), 'secondary', () => openDialog({ kind: 'org', appId: o.id })));
  } else if (d.kind === 'snoop' && snoop) {
    plate.textContent = S('terr_snoop_title', snoop.company);
    drawPortrait(portrait, null, snoopImg);
    text.appendChild(para(S('terr_kind_' + (['ads', 'analytics', 'fingerprinting', 'social'].includes(snoop.category) ? snoop.category : 'unknown'))));
    text.appendChild(para(P('terr_snoop_visits', snoop.hits, label)));
    if (snoop.shooed) text.appendChild(para(S('terr_snoop_shooed', label), 'small'));
    buttons.appendChild(button(S('terr_close'), 'secondary', closeDialog));
    if (!snoop.shooed) buttons.appendChild(button(S('terr_shoo'), 'danger', () => openDialog({ kind: 'confirm', appId: o.id, company: snoop.company })));
  } else if (d.kind === 'confirm' && snoop) {
    plate.textContent = S('terr_shoo_q', snoop.company);
    drawPortrait(portrait, null, snoopImg);
    text.appendChild(para(S('terr_shoo_body', snoop.company, label)));
    buttons.appendChild(button(S('terr_not_now'), 'secondary', () => openDialog({ kind: 'snoop', appId: o.id, company: snoop.company })));
    buttons.appendChild(button(S('terr_shoo'), 'danger', () => shoo(o, snoop.company)));
  } else {
    return closeDialog();
  }
  box.hidden = false;
  buttons.lastChild && buttons.lastChild.focus();
}

/** The organism's current frame, or the Snoop sprite, at a whole-number scale. */
function drawPortrait(c, o, img) {
  const dpr = window.devicePixelRatio || 1;
  const size = Math.round(72 * dpr);
  c.width = size; c.height = size;
  c.style.width = (size / dpr) + 'px'; c.style.height = (size / dpr) + 'px';
  const ctx = c.getContext('2d');
  ctx.imageSmoothingEnabled = false;
  ctx.clearRect(0, 0, size, size);
  if (img) {
    if (!img.complete) { img.onload = () => drawPortrait(c, null, img); return; }
    const k = Math.max(1, Math.floor(size / 16));
    ctx.drawImage(img, 0, 0, 16, 16, Math.floor((size - 16 * k) / 2), Math.floor((size - 16 * k) / 2), 16 * k, 16 * k);
    return;
  }
  const s = strip(o.data.preset_id, 'stage', Math.min(o.shown, o.data.stage_count || o.shown));
  if (!s) return;
  const k = Math.max(1, Math.floor(Math.min(size / s.spec.frame_w, size / s.spec.frame_h)));
  const src = tinted(o.data.preset_id, s, dominantColor(o), false);
  ctx.drawImage(src, 0, 0, s.spec.frame_w, s.spec.frame_h, Math.floor((size - s.spec.frame_w * k) / 2), size - s.spec.frame_h * k,
    s.spec.frame_w * k, s.spec.frame_h * k);
}

async function shoo(o, company) {
  const el = [...o.el.querySelectorAll('.snoop')].find((b) => b.dataset.company === company);
  try {
    const r = await Feed.shoo(o.id, company);
    if (r.error) throw new Error(r.error);
    closeDialog();
    if (el) {
      const box = el.getBoundingClientRect();
      poof(box.left + box.width / 2, box.top + box.height / 2);
      el.remove();
    }
    flashSign(S('terr_shoo_done', company));
    await refreshSnoops();
  } catch (e) {
    console.error(e);
    flashSign(S('terr_shoo_failed'));
  }
}

// ---- the wooden sign: one friendly line about today ----
function renderSign() {
  const sign = document.getElementById('sign');
  if (T.signFlash && performance.now() < T.signFlash) return;
  const all = new Set(), shooed = new Set();
  for (const v of Object.values(T.snoops)) for (const s of v.snoops) { all.add(s.company); if (s.shooed) shooed.add(s.company); }
  let line = all.size ? P('terr_sign_some', all.size) : S('terr_sign_none');
  if (shooed.size) line += ' · ' + P('terr_sign_shooed', shooed.size);
  sign.textContent = line;
  sign.hidden = T.orgs.size === 0;
}

function flashSign(text) {
  const sign = document.getElementById('sign');
  sign.textContent = text;
  T.signFlash = performance.now() + 2500;
  setTimeout(renderSign, 2600);
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
    li.querySelector('.lbl').textContent = t.label + (t.observed === false ? ' ' + S('terr_unseen') : '');
    ul.appendChild(li);
  }
  const toggle = document.getElementById('legend-toggle');
  toggle.textContent = S('terr_colors');
  toggle.onclick = () => document.getElementById('legend').classList.toggle('open');
}


// ---- data flow ----
function midnight() { const d = new Date(); d.setHours(0, 0, 0, 0); return d.getTime(); }

async function refreshSnoops() {
  try { T.snoops = await Feed.getSnoops([...T.orgs.keys()], midnight()); } catch (e) { console.error(e); }
  renderSnoops();
  renderSign();
  if (T.dialog && T.dialog.kind !== 'confirm') renderDialog();
}

function refresh() { return Feed.getTerrarium().then(applyTerrarium).then(refreshSnoops); }

function onUpdate(ev) {
  const o = T.orgs.get(ev.app_id);
  if (o) {
    o.data.packet_counts_by_type = ev.counts;
    o.data.total_packets = Object.values(ev.counts || {}).reduce((s, n) => s + n, 0);
    o.data.stage = ev.stage;
    sync(o);
  }
  clearTimeout(T.refreshTimer);                  // growth / Snoops / new apps: one full refresh shortly after
  T.refreshTimer = setTimeout(refresh, 600);
}

function showError() {
  const e = document.getElementById('error');
  e.textContent = S('terr_error');
  e.hidden = false;
}

async function boot() {
  document.getElementById('dialog').querySelector('.backdrop').onclick = closeDialog;
  document.addEventListener('keydown', (ev) => { if (ev.key === 'Escape') closeDialog(); });
  try {
    const [types, presets, terrarium] = await Promise.all([Feed.getPacketTypes(), Feed.getPresets(), Feed.getTerrarium()]);
    T.types = types.types || [];
    T.neutral = types.neutral || 'gray';
    for (const t of T.types) T.typeById[t.id] = t;
    for (const p of presets.presets || []) T.presets[p.id] = p.preset || {};
    renderLegend();
    applyTerrarium(terrarium);
    await refreshSnoops();
    Feed.onUpdate(onUpdate);
    setInterval(refreshSnoops, 30000);
    window.addEventListener('resize', layout);
    if (window.ResizeObserver) {                    // the WebView often starts at 0 px: redraw whenever the viewport settles
      const ro = new ResizeObserver(() => layout());
      ro.observe(document.documentElement);
      ro.observe(document.getElementById('grid'));
    }
    requestAnimationFrame(loop);
    // Desktop helpers: ?legend=1 opens the legend, ?panel=<app_id> opens that organism's card.
    const q = new URLSearchParams(location.search);
    if (q.get('legend') === '1') document.getElementById('legend').classList.add('open');
    if (q.get('panel')) openDialog({ kind: 'org', appId: q.get('panel') });
  } catch (e) {
    console.error(e);
    showError();
  }
}

boot();
