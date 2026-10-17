# Terrarium: sprites, tuning, and replacing the UI

Each watched app is one organism. The engine only provides data (`call("terrarium")`, `terrarium_update` events);
it knows nothing about sprites or animation. The UI in `app/src/main/assets/terrarium/` draws it and can be replaced.

## Drop in real sprites

```
engine/src/main/assets/organisms/
  index.json                      {"version":1,"presets":[{"id":"preset01","name":"Moss Sprout","file":"organisms/preset01/preset.json"}, ...]}
  preset01/
    preset.json
    stage1.png  stage2.png  stage3.png      one horizontal strip per stage (frames left to right)
    upgrade1.png upgrade2.png               optional: stage 1->2 and 2->3 transitions, played once
```

`preset.json`:
```json
{
  "id": "preset01",
  "name": "Moss Sprout",
  "stages": [
    {"file": "stage1.png", "frame_w": 32, "frame_h": 32, "frames": 4, "fps": 6},
    {"file": "stage2.png", "frame_w": 32, "frame_h": 48, "frames": 6, "fps": 8},
    {"file": "stage3.png", "frame_w": 48, "frame_h": 64, "frames": 8, "fps": 8}
  ],
  "upgrade": [
    {"file": "upgrade1.png", "frame_w": 32, "frame_h": 48, "frames": 8, "fps": 12},
    {"file": "upgrade2.png", "frame_w": 48, "frame_h": 64, "frames": 8, "fps": 12}
  ],
  "tint": {"mode": "palette_swap", "key_colors": ["#FF00FF"]},
  "stage_thresholds": [0, 80, 300]
}
```
- Each strip must be exactly `frame_w * frames` wide and `frame_h` high. Transparent background, no padding.
- `upgrade` is optional. Without it the UI plays a built-in 800 ms transition (white flash, 2-step scale pulse).
- `tint.mode`:
  - `palette_swap`: every pixel in an exact `key_colors` color is recolored with the app's dominant packet type.
    Paint the parts that should "show what the app does" (leaf tips, spots, a ridge) in `#FF00FF`.
  - `multiply`: the whole sprite is multiplied by the dominant type color (paint in light greys).
- `stage_thresholds` is optional and overrides the global thresholds for this preset only.
- Other fields (`kingdom`, `palette`, `shape`, `description`) are optional metadata; the debug app's preset picker
  uses `palette`/`kingdom` for its small thumbnail.
- Sprites are drawn with integer scaling only (pixel art stays crisp); a PNG that fails to load is replaced by a
  drawn placeholder, never a broken image.

After changing assets run:
```
python3 scripts/check_presets.py          # also runs automatically as :engine:checkPresets before every build
```
To regenerate the placeholder art (or add a preset from a config row): `python3 scripts/make_placeholders.py`.
Adding a preset needs no code: a folder, its `preset.json`, its strips, and one line in `index.json`.

## Change thresholds, weights, colors

- `engine/src/main/assets/config/terrarium.json`
  ```json
  {"stage_thresholds": [0, 50, 200], "weights": {"default": 1, "tracker": 2, "os_packets": 0.01},
   "count_os_packets": true, "default_preset": "preset01", "presets_index": "organisms/index.json"}
  ```
  growth = sum over packet types of count * weight (`default` for types not listed). Stage = highest stage whose
  threshold <= growth. The number of stages is the length of `stage_thresholds`; give presets that many strips
  (the UI reuses the last strip if a preset has fewer).
- `engine/src/main/assets/config/packet_types.json` is the only place packet type ids, labels, colors, rules and
  `observed` live. The UI fetches it; never copy colors into UI code. Unknown types use `neutral`.
- These are assets: rebuild and reinstall after editing (`./gradlew :app:assembleDebug`).
  For a quick on-device test, lower `stage_thresholds` to e.g. `[0, 10, 30]`.

## Replace the terrarium UI

Option 1, swap the folder: replace `app/src/main/assets/terrarium/` with your own `index.html` (+ JS/CSS).
It is served by `WebViewAssetLoader` at `https://appassets.androidplatform.net/assets/terrarium/index.html`;
sprites and configs are at `/assets/organisms/...` and `/assets/config/...`.

Option 2, swap only the `Feed` object: all app access in `terrarium.js` goes through one object:
```js
Feed.getTerrarium(appId?)  -> Promise<{apps:[...], stage_thresholds:[...]}>
Feed.getPresets()          -> Promise<{presets:[{id, name, file, preset:{...}}], default_preset}>
Feed.getPacketTypes()      -> Promise<packet_types.json>
Feed.ack(appId, stage)     -> Promise<{ok}>          // after an upgrade animation finished
Feed.setPreset(appId, id)  -> Promise<{ok}>
Feed.onUpdate(cb)                                  // cb({type:"terrarium_update", app_id, stage, stage_shown, counts})
Feed.assetUrl(path)        -> string               // URL for organisms/... files
```
`feed.mock.js` is a fixture version: from the repo root run `python3 -m http.server`, then open
`http://localhost:8000/app/src/main/assets/terrarium/index.html?mock=1` (`&reset=1` forgets acks, `&legend=1`,
`&panel=<app_id>`). The state machine is visible on each cell as `data-stage`, `data-shown`, `data-anim`.

Option 3, any other UI (Compose, Lovable, a different web app): call the engine directly:
```
call("terrarium")                                   per-app counts, growth, stage, next_threshold, stage_shown
call("presets")                                     all presets with preset.json inlined
call("packet_types")                                type ids, labels, colors
call("terrarium_ack", {"app_id": ..., "stage": n})  after playing an upgrade, so it is never replayed
call("set_preset", {"app_id": ..., "preset_id": ...})
```
and listen for `terrarium_update` on `MyceliumEngine.events` (or `WebBridge.attach` in a WebView).

Rules every UI should keep:
- Play an upgrade only while `stage > stage_shown`, one stage at a time, then `terrarium_ack`.
- Colors only from `packet_types.json`; types with `observed:false` are not visible to the DNS-only engine.
- `sanctuary_pending` packets were caught offline and are already counted.
