# Terrarium rebuild: report (unattended run, branch ui-polish)

## Done
- T1 Theme: island palette for every screen (day = parchment on mint, night = warm wood on deep indigo), Auto follows
  the phone clock, translucent top bar over the scene, slim status pill instead of the big brown banner. The blue/red bar
  under each plant was the app's packet-type mix and "Colors" was its legend: both removed from the main surface.
- T2 Sprites: `scripts/gen_sprites.py` (seeded, palette-locked, nearest-neighbor) writes 201 sprites + `atlas.json`;
  `docs/SPRITES.md` lists file, size, frames, anchor, priority; contact sheet `docs/sprites_contact.png`.
  25 presets x 3 stages (4-frame idle + 2-frame sleep), 2 Snoops, island tiles, plank, rope bridge, roots, banner,
  water/waterfall, plants (2 variants each), clouds, pillars, firefly, pollen, leaf, star, moon, poof, sparkle, zzz.
  The app loads sprites only through the atlas (`garden/Sprites.kt`), with a drawn fallback for missing files.
- T3 Scene: native Compose Canvas, six tile-built floating islands with purple-shaded rock, rope bridge, pool and
  waterfall, blossom trees, glowing mushrooms; terrain baked once; dithered day/dusk/night sky from the clock; parallax
  pillars and clouds; stars, moon, fireflies or pollen. One ~10 fps clock, only while resumed; Reduce motion and
  Battery-saver garden draw static frames.
- T4 Creatures: one per watched app from engine data (preset, stage), seeded placement on the islands (max 12 + "+N"),
  name tags, Snoops hover over their host, naps with "zzz" after 3 quiet hours, Shoo away = poof + sparkle + hop.
  Camera: pinch / double-tap zoom 1x-2x in whole-number steps, pan with inertia, clamped, reset button; tap = hop + ring.
- T5 Sheet: peek (today's Snoop line + pets / Snoops / shooed), half/full (creature list with status chips, creature card
  with growth bar and "i", today's contacts, Snoop list, Shoo away with confirm, Change creature). Back closes in steps.
- T6 Polish: 8 dp grid, 24 dp icons, 48 dp touch targets (info buttons, links, chips, Details), "i" pinned right in every
  header; docs/ui-plan.md updated.
- Every step: engine unit tests + debug build green, installed on the phone, committed. Engine logic and API unchanged.

## Screenshots checked (on the phone, Android 16)
Home at night (scene, islands, creature + Snoop, peek sheet), Settings and My Apps in the new palette, Insights,
Activity and Setup Check at XL text, sprite contact sheet.

## Not done / not verified
- Gestures, the half/full sheet, the creature card, Shoo away and the poof were built but not tapped: this phone blocks
  scripted input (see NEEDS_ME).
- Day and dusk skies were not seen on the phone (it was night); they share the same code path.
- Font scale 200% could not be set from adb on this phone; checked with the app's XL text size instead.
- Art is generated: good enough to ship as a prototype, made to be replaced by hand-drawn PNGs.
- CLAUDE.md still says the Compose app never draws organisms; the native terrarium now does (your request).

## Needs you
See docs/NEEDS_ME.md.
