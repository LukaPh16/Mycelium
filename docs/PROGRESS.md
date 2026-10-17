# Progress (unattended terrarium rebuild, branch ui-polish)
- Done: T1 theme swap (island palette day/night, Auto = phone clock 20-06 night, translucent top bar on Home, slim status pill, type bar + Colors legend hidden).
- Done: T2 scripts/gen_sprites.py (201 sprites, atlas.json, docs/SPRITES.md, docs/sprites_contact.png), garden/Sprites.kt atlas loader + fallback.
- Done: T3 native scene (garden/World.kt layout, Scene.kt baked terrain + Bayer sky + parallax + night tint, GardenView 10 fps lifecycle clock). Web module stays in assets, no longer hosted.
- Next: T4 creatures from garden+terrarium data, Snoops, shoo poof, sleeping, camera pinch/pan/reset.
- Then: T3 native scene, T4 creatures + camera, T5 quick-info sheet, T6 polish + docs/REPORT.md.
- Blockers: see docs/NEEDS_ME.md.
- Tools: JAVA_HOME=~/.local/jdk-17, adb=~/Android/Sdk/platform-tools/adb, open a screen: adb shell am start -n app.mycelium/app.mycelium.app.MainActivity --es route <route>.
