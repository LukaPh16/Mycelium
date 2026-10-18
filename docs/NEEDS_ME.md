# Needs you (one line each)
- Phone blocks adb taps: enable Developer options > USB debugging (Security settings) so dialogs/sheets can be tested by script.
- Start protection once (accept Android's VPN dialog) and use 2-3 watched apps so the garden has live data.
- CLAUDE.md says the Compose app never draws organisms; the native terrarium (your T3-T5 request) changes that. Update CLAUDE.md if you agree.
- Test by hand on the Terrarium: pinch / double-tap zoom, drag to pan (fling), tap a creature or Snoop (ring + hop), the reset button. Scripted taps are blocked on this phone.
- Check the Terrarium sheet by hand: drag it to half/full, tap a creature (card opens, camera centers), Shoo away a Snoop (confirm, poof), Change creature.
- Font 200% check could not run: this phone refuses `settings put system font_scale` from adb (WRITE_SETTINGS). Checked with the app's XL text size instead. Please try Android font size max by hand.
- Pixel size: you asked for ~2 device px per art px; on this 1280 px phone that made creatures ~20 dp, so base is width/320 (4 px). Say if you want it smaller.
- Art is generated placeholders; drop hand-drawn PNGs into app/src/main/assets/sprites/ with the same names and frame sizes (docs/SPRITES.md).
- Try on the Terrarium: hold a creature ~0.4 s, drag it, let go (it lands on the nearest moss, ledge, branch or island and stays there).
