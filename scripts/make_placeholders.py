#!/usr/bin/env python3
"""Placeholder organisms for all presets (real art replaces them later). Config-driven: add a row to PRESETS.

For every preset writes engine/src/main/assets/organisms/<id>/stage{1,2,3}.png (horizontal strips of frames) and
<id>/preset.json, then organisms/index.json. Every frame has its number drawn in pixels (top-left). Presets with
tint mode "palette_swap" carry #FF00FF key pixels that the terrarium recolors with the app's dominant packet type.

The "plant" shape with preset01's palette is mirrored by placeholderFrame() in terrarium.js (the runtime fallback
when a PNG fails to load). Keep the two in sync; preset01's strips must not change.

Usage: python3 scripts/make_placeholders.py      then: python3 scripts/check_presets.py
"""
import json
import math
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
ORGANISMS = ROOT / "engine/src/main/assets/organisms"
KEY = "#FF00FF"
INK = "#212121"
SWAY = [0, 1, 0, -1]
DIGITS = {  # 3x5, rows top to bottom
    "0": ["111", "101", "101", "101", "111"], "1": ["010", "110", "010", "010", "111"], "2": ["111", "001", "111", "100", "111"],
    "3": ["111", "001", "111", "001", "111"], "4": ["101", "101", "111", "001", "001"], "5": ["111", "100", "111", "001", "111"],
    "6": ["111", "100", "111", "101", "111"], "7": ["111", "001", "010", "010", "010"], "8": ["111", "101", "111", "101", "111"],
    "9": ["111", "101", "111", "001", "111"],
}
SOIL = {"top": "#8D6E63", "base": "#6D4C41"}
SAND = {"top": "#E6D3A3", "base": "#C8B07A"}

# id, name, shape, palette (dark, mid, light, accent), stage sizes [(w, h)], frames per stage, fps, tint mode, extra
PRESETS = [
    ("preset01", "Moss Sprout", "plant", ("#2E7D32", "#66BB6A", "#A5D6A7", "#FFEB3B"), [(32, 32), (32, 48), (48, 64)], (4, 6, 8), (6, 8, 8), "palette_swap", {}),
    ("preset02", "Ink Cap", "mushroom", ("#424242", "#9E9E9E", "#EEEEEE", "#FFF59D"), [(32, 32), (40, 40), (48, 56)], (4, 4, 6), (5, 6, 6), "palette_swap", {}),
    ("preset03", "Brain Coral", "coral", ("#AD1457", "#F06292", "#F8BBD0", "#FFF176"), [(32, 32), (40, 48), (56, 64)], (6, 6, 8), (6, 8, 8), "palette_swap", {}),
    ("preset04", "Morning Glory", "vine", ("#1B5E20", "#4CAF50", "#C8E6C9", "#7E57C2"), [(24, 40), (28, 56), (32, 72)], (8, 8, 8), (8, 8, 10), "palette_swap", {}),
    ("preset05", "Barrel Cactus", "cactus", ("#1B5E20", "#43A047", "#C5E1A5", "#FFCA28"), [(24, 32), (32, 44), (40, 56)], (2, 4, 4), (2, 3, 3), "palette_swap", {}),
    ("preset06", "Bog Slime", "slime", ("#33691E", "#9CCC65", "#F1F8E9", "#FFFFFF"), [(32, 24), (40, 32), (56, 40)], (6, 6, 8), (8, 8, 10), "multiply", {}),
    ("preset07", "Moon Jelly", "jelly", ("#1565C0", "#90CAF9", "#E3F2FD", "#FFFFFF"), [(32, 40), (40, 56), (48, 72)], (6, 8, 8), (6, 8, 8), "palette_swap", {}),
    ("preset08", "Quartz Cluster", "crystal", ("#5E35B1", "#B39DDB", "#F3E5F5", "#FFFFFF"), [(32, 32), (40, 40), (48, 56)], (4, 4, 6), (4, 4, 6), "palette_swap", {}),
    ("preset09", "Hart's Tongue", "fern", ("#1B5E20", "#388E3C", "#A5D6A7", "#DCE775"), [(32, 32), (40, 48), (56, 64)], (4, 6, 6), (5, 6, 6), "palette_swap", {}),
    ("preset10", "Cushion Moss", "moss", ("#33691E", "#7CB342", "#DCEDC8", "#FFEE58"), [(40, 20), (56, 24), (72, 32)], (4, 4, 4), (4, 4, 4), "palette_swap", {}),
    ("preset11", "Sea Anemone", "anemone", ("#BF360C", "#FF7043", "#FFCCBC", "#FFF59D"), [(32, 32), (40, 40), (48, 56)], (6, 8, 8), (8, 8, 10), "palette_swap", {}),
    ("preset12", "Bonsai", "tree", ("#4E342E", "#558B2F", "#AED581", "#E53935"), [(32, 32), (40, 48), (56, 64)], (4, 4, 6), (3, 4, 4), "palette_swap", {}),
    ("preset13", "Slow Moss", "moss", ("#1B5E20", "#2E7D32", "#81C784", "#B2FF59"), [(40, 20), (56, 28), (72, 36)], (3, 3, 4), (2, 2, 3), "palette_swap",
     {"stage_thresholds": [0, 80, 300], "description": "Grows slowly: needs more activity than other organisms to reach each stage."}),
    ("preset14", "Fly Agaric", "mushroom", ("#B71C1C", "#E53935", "#FFFDE7", "#FFFFFF"), [(32, 32), (40, 44), (56, 56)], (4, 6, 6), (5, 6, 6), "palette_swap", {}),
    ("preset15", "Fire Coral", "coral", ("#BF360C", "#FF5722", "#FFAB91", "#FFEB3B"), [(32, 40), (40, 56), (56, 72)], (4, 6, 8), (6, 6, 8), "palette_swap", {}),
    ("preset16", "Ivy", "vine", ("#1B5E20", "#2E7D32", "#81C784", "#004D40"), [(28, 40), (32, 60), (40, 80)], (6, 6, 8), (6, 6, 8), "palette_swap", {}),
    ("preset17", "Prickly Pear", "cactus", ("#00695C", "#26A69A", "#B2DFDB", "#EC407A"), [(32, 32), (40, 44), (48, 56)], (3, 4, 6), (2, 3, 4), "palette_swap", {}),
    ("preset18", "Cave Slime", "slime", ("#4A148C", "#AB47BC", "#F3E5F5", "#FFFFFF"), [(32, 24), (48, 32), (64, 44)], (4, 6, 6), (6, 8, 8), "multiply", {}),
    ("preset19", "Lion's Mane Jelly", "jelly", ("#E65100", "#FFB74D", "#FFF3E0", "#FFFFFF"), [(32, 48), (40, 64), (56, 80)], (8, 8, 8), (8, 8, 10), "palette_swap", {}),
    ("preset20", "Amethyst", "crystal", ("#4A148C", "#8E24AA", "#E1BEE7", "#FFFFFF"), [(28, 36), (36, 48), (48, 64)], (6, 6, 8), (6, 6, 8), "palette_swap", {}),
    ("preset21", "Tree Fern", "fern", ("#004D40", "#00897B", "#80CBC4", "#C6FF00"), [(32, 40), (48, 56), (64, 72)], (4, 4, 6), (4, 5, 6), "palette_swap", {}),
    ("preset22", "Tube Worms", "anemone", ("#B71C1C", "#EF5350", "#FFFFFF", "#FFCDD2"), [(28, 40), (36, 56), (48, 72)], (6, 6, 8), (6, 6, 8), "palette_swap", {}),
    ("preset23", "Willow", "tree", ("#5D4037", "#9CCC65", "#DCEDC8", "#FFF176"), [(32, 40), (48, 56), (64, 72)], (4, 6, 8), (3, 4, 5), "palette_swap", {}),
    ("preset24", "Sunflower Sprout", "plant", ("#33691E", "#8BC34A", "#DCEDC8", "#FFC107"), [(28, 36), (32, 52), (40, 72)], (4, 4, 6), (5, 6, 6), "palette_swap", {}),
    ("preset25", "Lion's Mane", "mushroom", ("#BCAAA4", "#EFEBE9", "#FFFFFF", "#FFE0B2"), [(32, 32), (40, 40), (48, 52)], (6, 6, 6), (4, 4, 5), "multiply", {}),
]
AQUATIC = {"coral", "jelly", "anemone"}


class Canvas:
    def __init__(self, fw, fh):
        self.fw, self.fh, self.px = fw, fh, {}

    def put(self, x, y, c):
        x, y = int(round(x)), int(round(y))
        if 0 <= x < self.fw and 0 <= y < self.fh:
            self.px[(x, y)] = c

    def line(self, x0, y0, x1, y1, c):
        n = max(abs(x1 - x0), abs(y1 - y0), 1)
        for i in range(int(n) + 1):
            self.put(x0 + (x1 - x0) * i / n, y0 + (y1 - y0) * i / n, c)

    def disc(self, cx, cy, rx, ry, c, top_only=False):
        for y in range(int(cy - ry), int(cy + ry) + 1):
            for x in range(int(cx - rx), int(cx + rx) + 1):
                if top_only and y > cy:
                    continue
                if ((x - cx) / max(rx, 0.5)) ** 2 + ((y - cy) / max(ry, 0.5)) ** 2 <= 1.0:
                    self.put(x, y, c)


def ground(cv, soil):
    for x in range(cv.fw):
        cv.put(x, cv.fh - 3, soil["top"])
        cv.put(x, cv.fh - 2, soil["base"])
        cv.put(x, cv.fh - 1, soil["base"])


def digits(cv, frame):
    for j, ch in enumerate(str(frame + 1)):
        for r, row in enumerate(DIGITS[ch]):
            for c, bit in enumerate(row):
                if bit == "1":
                    cv.put(1 + j * 4 + c, 1 + r, INK)


# ---- shapes: draw(cv, stage 1..3, frame, palette) on top of the ground (base line y = fh - 4) ----

def plant(cv, stage, frame, pal):
    """preset01's plant; mirrored exactly by placeholderFrame() in terrarium.js."""
    dark, mid, _light, accent = pal
    fw, fh = cv.fw, cv.fh
    sway = SWAY[frame % 4]
    height = min([12, 24, 40][stage - 1], fh - 8)
    base_y, cx = fh - 4, fw // 2
    for i in range(height):
        cv.put(cx + (sway if i > height * 2 // 3 else 0), base_y - i, dark)
    leaves, length = [2, 4, 6][stage - 1], (3 if stage < 3 else 4)
    for i in range(leaves):
        y = base_y - (i + 1) * height // (leaves + 1)
        s = sway if (base_y - y) > height // 2 else 0
        d = -1 if i % 2 == 0 else 1
        for k in range(1, length + 1):
            cv.put(cx + s + d * k, y, KEY if k == length else mid)
            cv.put(cx + s + d * k, y - 1, mid if k < length else KEY)
    if stage == 3:
        top = base_y - height
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                cv.put(cx + sway + dx, top + dy, KEY)
        cv.put(cx + sway, top, accent)


def mushroom(cv, stage, frame, pal):
    dark, mid, light, _accent = pal
    base_y = cv.fh - 4
    spots = [(-0.3, -0.5), (0.25, -0.6), (0.0, -0.2)]
    for m in range(stage):
        cx = cv.fw * (m + 1) / (stage + 1) + (0 if stage == 1 else (m - 1) * 2)
        size = 1.0 if m == stage // 2 else 0.7
        r = max(3, cv.fw * 0.16 * size + stage)
        stem_h = max(4, cv.fh * 0.35 * size)
        bob = [0, 0, 1, 1, 0, 0][frame % 6] if (m + frame) % 2 == 0 else 0
        cap_y = base_y - stem_h + bob
        cv.line(cx, base_y, cx, cap_y, light)
        cv.line(cx + 1, base_y, cx + 1, cap_y, light)
        cv.disc(cx + 0.5, cap_y, r, r * 0.7, mid, top_only=True)
        cv.line(cx + 0.5 - r, cap_y, cx + 0.5 + r, cap_y, dark)
        for sx, sy in spots:
            cv.put(cx + 0.5 + sx * r, cap_y + sy * r * 0.7, KEY)


def coral(cv, stage, frame, pal):
    dark, mid, light, _accent = pal
    base_y, cx = cv.fh - 4, cv.fw // 2
    depth = stage + 1
    sway = SWAY[frame % 4]

    def branch(x, y, length, angle, d):
        x2 = x + math.sin(angle) * length + (sway * 0.5 if d == depth else 0)
        y2 = y - math.cos(angle) * length
        cv.line(x, y, x2, y2, mid if d > 1 else dark)
        if d == 1:
            cv.put(x2, y2, KEY)
            cv.put(x2, y2 - 1, light)
            return
        for a in (-0.5, 0.45):
            branch(x2, y2, length * 0.72, angle + a, d - 1)

    branch(cx, base_y, cv.fh * 0.28, 0.0, depth)


def vine(cv, stage, frame, pal):
    dark, mid, light, accent = pal
    base_y, cx = cv.fh - 4, cv.fw / 2
    height = int((cv.fh - 8) * [0.4, 0.7, 0.95][stage - 1])
    for i in range(height):
        x = cx + 3 * math.sin((i + frame * 2) / 4.0)
        cv.put(x, base_y - i, dark)
        if i % 6 == 3:
            d = 1 if (i // 6) % 2 == 0 else -1
            cv.put(x + d, base_y - i, mid)
            cv.put(x + 2 * d, base_y - i, mid)
            cv.put(x + 2 * d, base_y - i - 1, KEY)
        if stage >= 2 and i % 12 == 9:
            cv.disc(x, base_y - i, 1.5, 1.5, accent)
            cv.put(x, base_y - i, light)


def cactus(cv, stage, frame, pal):
    dark, mid, light, accent = pal
    base_y, cx = cv.fh - 4, cv.fw // 2
    w = [3, 4, 5][stage - 1]
    h = int((cv.fh - 8) * [0.45, 0.7, 0.85][stage - 1])
    top = base_y - h
    for y in range(top, base_y + 1):
        for x in range(cx - w // 2, cx + (w + 1) // 2):
            cv.put(x, y, mid)
        cv.put(cx - w // 2, y, dark)
        cv.put(cx, y, KEY if y % 2 == 0 else mid)       # ridge = palette-swap key
    cv.disc(cx - 0.5, top, w / 2, 1.5, mid, top_only=True)
    for y in range(top + 2, base_y, 3):                  # spines
        cv.put(cx - w // 2 - 1, y, light)
        cv.put(cx + (w + 1) // 2, y + 1, light)
    arms = [] if stage == 1 else ([-1] if stage == 2 else [-1, 1])
    for side in arms:
        ay = top + h // 2 + (2 if side > 0 else 0)
        ax = cx + side * (w // 2 + 3)
        cv.line(cx + side * (w // 2), ay, ax, ay, mid)
        cv.line(ax, ay, ax, ay - h // 4, mid)
    if stage >= 2 and frame % 2 == 0:                    # flower blinks
        cv.disc(cx - 0.5, top - 2, 1.5, 1.5, accent)


def slime(cv, stage, frame, pal):
    dark, mid, light, accent = pal
    base_y, cx = cv.fh - 3, cv.fw / 2
    squash = math.sin(frame / max(1, 6) * 2 * math.pi)
    rx = cv.fw * [0.25, 0.32, 0.38][stage - 1] * (1 + 0.08 * squash)
    ry = cv.fh * [0.3, 0.38, 0.42][stage - 1] * (1 - 0.1 * squash)
    cv.disc(cx, base_y, rx, ry * 2, mid, top_only=True)
    for x in range(int(cx - rx), int(cx + rx) + 1):
        cv.put(x, base_y, dark)
    cv.put(cx - rx * 0.4, base_y - ry * 1.3, light)
    cv.put(cx - rx * 0.4 + 1, base_y - ry * 1.3, light)
    eye_y = base_y - ry * 0.9
    for ex in (cx - rx * 0.3, cx + rx * 0.3):
        cv.put(ex, eye_y, INK if frame % 6 != 5 else mid)  # blink
    if stage == 3:
        cv.put(cx, base_y - ry * 1.9, accent)


def jelly(cv, stage, frame, pal):
    dark, mid, light, _accent = pal
    bob = [0, 1, 2, 1][frame % 4]
    cx = cv.fw / 2
    r = cv.fw * [0.25, 0.3, 0.34][stage - 1]
    bell_y = 4 + r * 0.8 + bob
    cv.disc(cx, bell_y, r, r * 0.8, mid, top_only=True)
    cv.line(cx - r, bell_y, cx + r, bell_y, dark)
    cv.put(cx - r * 0.4, bell_y - r * 0.5, light)
    n = 2 * stage + 1
    length = (cv.fh - 6 - bell_y) * [0.5, 0.7, 0.9][stage - 1]
    for t in range(n):
        x0 = cx - r + 1 + t * (2 * r - 2) / max(1, n - 1)
        for i in range(int(length)):
            x = x0 + math.sin((i + frame * 2 + t) / 3.0)
            cv.put(x, bell_y + 1 + i, light if i % 3 else mid)
        cv.put(x0 + math.sin((length + frame * 2 + t) / 3.0), bell_y + 1 + length, KEY)


def crystal(cv, stage, frame, pal):
    dark, mid, light, accent = pal
    base_y = cv.fh - 4
    n = 2 * stage - 1
    for s in range(n):
        cx = cv.fw * (s + 1) / (n + 1)
        h = (cv.fh - 10) * (0.5 + 0.5 * (1 - abs(s - n // 2) / max(1, n)))
        w = 2 + stage
        for y in range(int(h)):
            half = w * min(1.0, (h - y) / (w + 1))
            for x in range(int(cx - half), int(cx + half) + 1):
                cv.put(x, base_y - y, mid if x < cx else light)
            cv.put(cx, base_y - y, KEY if y % 3 else dark)   # facet line = key
    sx = cv.fw * ((frame * 7) % (n + 1) + 1) / (n + 2)
    cv.put(sx, base_y - (cv.fh - 10) * 0.6, accent)            # sparkle walks between shards


def fern(cv, stage, frame, pal):
    dark, mid, light, accent = pal
    base_y, cx = cv.fh - 4, cv.fw // 2
    fronds = [2, 3, 5][stage - 1]
    sway = SWAY[frame % 4]
    for f in range(fronds):
        angle = -0.9 + 1.8 * f / max(1, fronds - 1) if fronds > 1 else 0.0
        length = (cv.fh - 8) * (0.6 + 0.4 * (1 - abs(angle)))
        px, py = cx, base_y
        for i in range(int(length)):
            a = angle * (i / length) + sway * 0.02 * i
            px, py = px + math.sin(a), py - math.cos(a)
            cv.put(px, py, dark)
            if i > 2 and i % 2 == 0:
                cv.put(px - 1, py, mid)
                cv.put(px + 1, py, mid)
        cv.put(px, py - 1, KEY)
        if stage < 3:                                            # fiddlehead
            cv.put(px + 1, py - 1, light)
            cv.put(px + 1, py, accent)


def moss(cv, stage, frame, pal):
    dark, mid, light, _accent = pal
    base_y = cv.fh - 4
    mounds = 2 + 2 * stage
    for m in range(mounds):
        cx = cv.fw * (m + 0.5) / mounds
        r = cv.fw / mounds * 0.7
        h = (cv.fh - 6) * (0.35 + 0.15 * stage) * (0.7 + 0.3 * ((m * 7) % 3) / 2)
        cv.disc(cx, base_y, r, h, mid, top_only=True)
        cv.put(cx, base_y - h + 1, dark)
        for k in range(3):
            if (m + k + frame) % 3 == 0:                          # shimmer
                cv.put(cx - r * 0.5 + k * r * 0.5, base_y - h * 0.6, light)
        cv.put(cx + r * 0.3, base_y - h * 0.4, KEY)


def anemone(cv, stage, frame, pal):
    dark, mid, light, _accent = pal
    base_y, cx = cv.fh - 4, cv.fw / 2
    stalk = (cv.fh - 8) * [0.25, 0.3, 0.35][stage - 1]
    for y in range(int(stalk)):
        for x in range(int(cx - 2), int(cx + 2)):
            cv.put(x, base_y - y, dark)
    n = 3 + 2 * stage
    top = base_y - stalk
    length = (cv.fh - 8 - stalk) * [0.5, 0.75, 0.95][stage - 1]
    for t in range(n):
        angle = -1.1 + 2.2 * t / (n - 1)
        x, y = cx, top
        for i in range(int(length)):
            a = angle * 0.6 + 0.25 * math.sin((i + frame * 2 + t) / 3.0)
            x, y = x + math.sin(a), y - math.cos(a)
            cv.put(x, y, mid if i < length - 2 else light)
        cv.put(x, y - 1, KEY)


def tree(cv, stage, frame, pal):
    dark, mid, light, accent = pal
    base_y, cx = cv.fh - 4, cv.fw // 2
    trunk = (cv.fh - 8) * [0.3, 0.4, 0.45][stage - 1]
    for y in range(int(trunk)):
        cv.put(cx, base_y - y, dark)
        cv.put(cx + 1, base_y - y, dark)
    r = min(cv.fw * 0.42, (cv.fh - 8 - trunk) * 0.6) * [0.6, 0.85, 1.0][stage - 1]
    cy = base_y - trunk - r * 0.6
    cv.disc(cx + 0.5, cy, r, r * 0.75, mid)
    for k in range(6):                                            # rustling highlights
        a = k + frame * 0.8
        cv.put(cx + 0.5 + math.cos(a) * r * 0.6, cy - abs(math.sin(a)) * r * 0.5, light)
    for k in range(stage + 1):                                    # fruit = key
        cv.put(cx + 0.5 + (k - stage / 2) * r * 0.5, cy + r * 0.2, KEY)
    if stage == 3:
        cv.put(cx + 0.5, cy - r * 0.7, accent)


SHAPES = {f.__name__: f for f in (plant, mushroom, coral, vine, cactus, slime, jelly, crystal, fern, moss, anemone, tree)}


def frame_pixels(shape, stage, frame, fw, fh, pal):
    cv = Canvas(fw, fh)
    ground(cv, SAND if shape in AQUATIC else SOIL)
    SHAPES[shape](cv, stage, frame, pal)
    digits(cv, frame)
    return cv.px


def rgba(color):
    return tuple(int(color[i:i + 2], 16) for i in (1, 3, 5)) + (255,)


def main():
    index = []
    for pid, name, shape, pal, sizes, frames, fps, tint, extra in PRESETS:
        out = ORGANISMS / pid
        out.mkdir(parents=True, exist_ok=True)
        stages = []
        for stage, ((fw, fh), n, rate) in enumerate(zip(sizes, frames, fps), start=1):
            img = Image.new("RGBA", (fw * n, fh), (0, 0, 0, 0))
            for f in range(n):
                for (x, y), c in frame_pixels(shape, stage, f, fw, fh, pal).items():
                    img.putpixel((f * fw + x, y), rgba(c))
            img.save(out / f"stage{stage}.png")
            stages.append({"file": f"stage{stage}.png", "frame_w": fw, "frame_h": fh, "frames": n, "fps": rate})
        preset = {
            "id": pid,
            "name": name,
            "kingdom": shape,
            "palette": list(pal[:3]),
            "shape": {"branches": 3 + int(pid[-2:]) * 7 % 5, "curl": round(int(pid[-2:]) * 13 % 10 / 10, 1), "cap": shape == "mushroom"},
            "stage_names": ["spore", "sprout", "bloom"],
            "description": extra.get("description", f"A {shape} that grows with every lookup its app makes."),
            "stages": stages,
            "tint": {"mode": tint, "key_colors": [KEY]} if tint == "palette_swap" else {"mode": tint},
            "note": "Placeholder strips from scripts/make_placeholders.py. Optional \"upgrade\": [{file, frame_w, frame_h, frames, fps}, ...] "
                    "per stage transition; without it the UI plays its built-in 800 ms transition.",
        }
        if "stage_thresholds" in extra:
            preset["stage_thresholds"] = extra["stage_thresholds"]
        (out / "preset.json").write_text(json.dumps(preset, indent=1) + "\n")
        index.append({"id": pid, "name": name, "file": f"organisms/{pid}/preset.json"})
    (ORGANISMS / "index.json").write_text(json.dumps({"version": 1, "presets": index}, indent=1) + "\n")
    print(f"wrote {len(index)} presets to {ORGANISMS.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
