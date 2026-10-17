#!/usr/bin/env python3
"""Checks the organism presets in engine/src/main/assets/organisms. Exit code 1 on any error.

- every preset listed in index.json has <id>/preset.json whose "id" matches
- every referenced PNG (stages and optional upgrade strips) exists
- each strip's width == frame_w * frames and height == frame_h (read from the PNG header; no dependencies)
- frames/fps > 0, tint mode known, stage_thresholds (if any) start at 0 and ascend
- with Pillow installed: palette_swap presets contain a key color in every frame
Run: python3 scripts/check_presets.py   (Gradle runs it as :engine:checkPresets before every build)
"""
import json
import struct
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ORGANISMS = ROOT / "engine/src/main/assets/organisms"
TINT_MODES = {"palette_swap", "multiply"}


def png_size(path):
    with open(path, "rb") as f:
        head = f.read(24)
    if head[:8] != b"\x89PNG\r\n\x1a\n" or head[12:16] != b"IHDR":
        raise ValueError("not a PNG")
    return struct.unpack(">II", head[16:24])


def main():
    errors, warnings = [], []
    try:
        from PIL import Image  # optional: only for the key-color check
    except ImportError:
        Image = None
        warnings.append("Pillow not installed: key-color check skipped")
    index = json.loads((ORGANISMS / "index.json").read_text())
    entries = index.get("presets", [])
    ids = [e.get("id") for e in entries]
    if len(set(ids)) != len(ids):
        errors.append("index.json: duplicate preset ids")
    strips = 0
    for e in entries:
        pid = e.get("id")
        pfile = ROOT / "engine/src/main/assets" / e.get("file", f"organisms/{pid}/preset.json")
        if not pfile.exists():
            errors.append(f"{pid}: missing {pfile.relative_to(ROOT)}")
            continue
        try:
            p = json.loads(pfile.read_text())
        except json.JSONDecodeError as ex:
            errors.append(f"{pid}: preset.json is not valid JSON ({ex})")
            continue
        if p.get("id") != pid:
            errors.append(f"{pid}: preset.json id is {p.get('id')!r}")
        if not p.get("name"):
            errors.append(f"{pid}: no name")
        if not p.get("stages"):
            errors.append(f"{pid}: no stages")
        tint = (p.get("tint") or {}).get("mode", "palette_swap")
        if tint not in TINT_MODES:
            errors.append(f"{pid}: unknown tint mode {tint!r}")
        th = p.get("stage_thresholds")
        if th is not None and (not th or th[0] != 0 or any(b <= a for a, b in zip(th, th[1:]))):
            errors.append(f"{pid}: stage_thresholds must start at 0 and ascend: {th}")
        keys = [tuple(int(k[i:i + 2], 16) for i in (1, 3, 5)) for k in (p.get("tint") or {}).get("key_colors", ["#FF00FF"])]
        for kind in ("stages", "upgrade"):
            for i, s in enumerate(p.get(kind) or [], start=1):
                where = f"{pid}/{s.get('file')} ({kind}[{i}])"
                png = pfile.parent / str(s.get("file"))
                fw, fh, n, fps = s.get("frame_w", 0), s.get("frame_h", 0), s.get("frames", 0), s.get("fps", 0)
                if min(fw, fh, n, fps) <= 0:
                    errors.append(f"{where}: frame_w, frame_h, frames and fps must be > 0")
                    continue
                if not png.exists():
                    errors.append(f"{where}: missing PNG")
                    continue
                try:
                    w, h = png_size(png)
                except ValueError as ex:
                    errors.append(f"{where}: {ex}")
                    continue
                strips += 1
                if w != fw * n or h != fh:
                    errors.append(f"{where}: is {w}x{h}, expected {fw * n}x{fh} (frame_w*frames x frame_h)")
                elif Image is not None and kind == "stages" and tint == "palette_swap":
                    img = Image.open(png).convert("RGBA")
                    for f in range(n):
                        frame = img.crop((f * fw, 0, (f + 1) * fw, fh))
                        colors = frame.getcolors(maxcolors=fw * fh) or []
                        if not any(c[:3] in keys and c[3] > 0 for _, c in colors):
                            errors.append(f"{where}: frame {f + 1} has no key color for palette_swap")
    listed = set(ids)
    for d in sorted(x.name for x in ORGANISMS.iterdir() if x.is_dir()):
        if d not in listed:
            warnings.append(f"{d}: folder not listed in index.json")
    for w in warnings:
        print("warning:", w)
    for e in errors:
        print("error:", e)
    print(f"presets: {len(entries)} listed, {strips} strips checked, {len(errors)} errors")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
