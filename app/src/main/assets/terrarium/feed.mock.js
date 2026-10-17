'use strict';
/*
 * Fixture Feed for working on the UI in a desktop browser, without a phone.
 * It reads the real packet_types.json, presets and sprites from engine/src/main/assets, so serve the repo root:
 *   python3 -m http.server      (in the repo root)
 *   open http://localhost:8000/app/src/main/assets/terrarium/index.html?mock=1
 * Live traffic is simulated: Chrome gains a dns or tracker packet every 300 ms and crosses stage thresholds.
 * Acks are kept in localStorage, so a reload never replays an acked upgrade (as with the real engine).
 * The fixture Host has no translations: sentences show their string keys and arguments.
 */
(function () {
  const ASSETS = '../../../../../engine/src/main/assets/';
  const THRESHOLDS = [0, 50, 200];
  const WEIGHTS = { tracker: 2, os_packets: 0.01 };
  const stored = JSON.parse(localStorage.getItem('mycelium.mock.shown') || '{}');
  const apps = [
    { app_id: 'com.android.chrome', label: 'Chrome', preset_id: 'preset01', counts: { dns: 30, tracker: 8, https: 1, os_packets: 600 } },
    { app_id: 'com.zhiliaoapp.musically', label: 'TikTok', preset_id: 'preset01', counts: { dns: 40, tracker: 90, https: 3 } },
    { app_id: 'org.mozilla.firefox', label: 'Firefox', preset_id: 'preset07', counts: { dns: 12, tcp: 1, other: 2 } },
  ];
  let types = [];
  const shooed = new Set();
  window.MyceliumHost = {
    strings: () => '{}',
    settings: () => JSON.stringify({ reduce_motion: /[?&]still=1/.test(location.search), battery_saver: false }),
    plural: (key, n, args) => key + ' ' + args,
  };
  const cbs = [];

  function growth(c) { return Object.entries(c).reduce((s, [t, n]) => s + n * (WEIGHTS[t] == null ? 1 : WEIGHTS[t]), 0); }
  function view(a) {
    const counts = {};
    for (const t of types) counts[t.id] = 0;                 // every listed type, like the engine
    Object.assign(counts, a.counts);
    const g = Math.round(growth(a.counts) * 100) / 100;
    let i = 0;
    THRESHOLDS.forEach((th, k) => { if (th <= g) i = k; });
    return {
      app_id: a.app_id, label: a.label, preset_id: a.preset_id, packet_counts_by_type: counts,
      total_packets: Object.values(a.counts).reduce((s, n) => s + n, 0), growth: g, stage: i + 1, stage_count: THRESHOLDS.length,
      next_threshold: THRESHOLDS[i + 1] == null ? null : THRESHOLDS[i + 1], stage_shown: stored[a.app_id] || 1, sanctuary_pending: 0,
    };
  }
  const getJson = (path) => fetch(ASSETS + path).then((r) => { if (!r.ok) throw new Error(path + ': HTTP ' + r.status); return r.json(); });

  window.Feed = {
    getTerrarium: () => Promise.resolve({ apps: apps.map(view), stage_thresholds: THRESHOLDS }),
    getPresets: () => getJson('organisms/index.json').then((idx) => Promise.all(idx.presets.map((p) =>
      getJson(p.file).then((preset) => Object.assign({}, p, { preset: preset }))))).then((list) => ({ presets: list })),
    getPacketTypes: () => getJson('config/packet_types.json').then((t) => { types = t.types; return t; }),
    ack(appId, stage) {
      stored[appId] = stage;
      localStorage.setItem('mycelium.mock.shown', JSON.stringify(stored));
      console.log('ack', appId, stage);
      return Promise.resolve({ ok: true });
    },
    getSnoops(appIds) {
      const out = {};
      for (const id of appIds) {
        out[id] = { contacts: 120, blocked: shooed.size, snoops: (id === 'com.android.chrome' ? ['Google', 'Meta', 'AppsFlyer', 'Criteo'] : ['Adjust'])
          .map((c, i) => ({ company: c, category: ['ads', 'social', 'analytics', 'ads'][i % 4], hits: 10 - i, shooed: shooed.has(id + '|' + c) })) };
      }
      return Promise.resolve(out);
    },
    shoo(appId, company) { shooed.add(appId + '|' + company); return Promise.resolve({ ok: true }); },
    setPreset(appId, presetId) { apps.find((a) => a.app_id === appId).preset_id = presetId; return Promise.resolve({ ok: true }); },
    onUpdate(cb) { cbs.push(cb); },
    _push(ev) { cbs.forEach((cb) => cb(ev)); },
    assetUrl: (path) => ASSETS + path,
  };

  // Simulated live traffic for Chrome (and "?reset=1" forgets acks).
  if (/[?&]reset=1/.test(location.search)) { localStorage.removeItem('mycelium.mock.shown'); for (const k in stored) delete stored[k]; }
  setInterval(() => {
    const chrome = apps[0];
    const t = Math.random() < 0.35 ? 'tracker' : 'dns';
    chrome.counts[t] = (chrome.counts[t] || 0) + 1;
    const v = view(chrome);
    window.Feed._push({ type: 'terrarium_update', app_id: v.app_id, stage: v.stage, stage_shown: v.stage_shown, counts: v.packet_counts_by_type });
  }, 300);
})();
