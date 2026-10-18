#!/usr/bin/env python3
"""Original pixel art for the Mycelium terrarium. Seeded, palette-locked (PAL only), nearest-neighbor only.

Mood: a glass terrarium on a wooden shelf by a window, Owlboy-like richness (hue-shifted ramps, rim light,
colored outlines, soft depth). Everything is original; nothing is traced or copied.

Writes app/src/main/assets/sprites/<name>.png (horizontal frame strips), sprites/atlas.json
({name, file, w, h, anchor:[x,y], frames, fps, head?}), sprites/terrarium_layout.json (where things go in the scene),
docs/SPRITES.md, docs/sprites_contact.png and docs/terrarium_preview.png. Hand-drawn PNGs can replace any file:
same name, frame size, frame count. Creature sprites paint their main color in the KEY ramp; the app swaps it for one
of RECOLOR (picked from the app's seed).
Usage: python3 scripts/gen_sprites.py
"""
import json
import math
import random
import zlib
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "app/src/main/assets/sprites"

RAMPS = {   # dark outline tint -> shadow -> mid -> light -> highlight; hue shifts toward purple in shadow, yellow in light
    "grass": ["#1E3A2B", "#2F6B33", "#46A83A", "#7FD04A", "#C6EC6A"],
    "moss": ["#1E3A2B", "#3E6B2A", "#5E9A33", "#8CC43F", "#C6EC6A"],
    "earth": ["#2A1C22", "#4A2E2A", "#6E4430", "#9A6438", "#C99A5B"],
    "soil": ["#1F1620", "#352330", "#4E3230", "#6E4430", "#8E5A3E"],
    "rock": ["#221F36", "#3A3354", "#5F5385", "#8A74A6", "#C2A9D6"],
    "pebble": ["#262B3A", "#3F4A5C", "#6B7A8C", "#9AA7B4", "#D0D8DE"],
    "wood": ["#2E1F2B", "#4A2E30", "#6B4136", "#8E5A3E", "#B47E52"],
    "cork": ["#3A2418", "#6E4428", "#A06A3A", "#C99A5B", "#EAD0A0"],
    "bark": ["#24161E", "#4A2E30", "#6B4136", "#8E5A3E", "#B47E52"],
    "blossom": ["#5A1F3D", "#B0446E", "#E7679A", "#F59BB8", "#FFE1EA"],
    "glow": ["#1F3E4A", "#3E8A96", "#6FD1D6", "#B9F2EE", "#F2FFFC"],
    "water": ["#14304A", "#1F4E6E", "#2F7A9E", "#58B4CF", "#A6E6F0"],
    "cloud": ["#7A8FB8", "#9CB4DA", "#C9E2F7", "#E8F4FD", "#FFFFFF"],
    "flyred": ["#3E1222", "#7E1A2E", "#C2303C", "#EE5E52", "#FFB2A0"],
    "sun": ["#5A3A12", "#B07A22", "#E8B83A", "#FFDC6E", "#FFF4C2"],
    "ink": ["#120E1A", "#22182E", "#3A2E4A", "#5A4A6E", "#8A7AA0"],
    "infect": ["#3A0F22", "#7A1530", "#B8203A", "#E8455A", "#FF8C8C"],
    "mite": ["#2A0F24", "#5E1838", "#9A2A4E", "#D24A66", "#FF9AA6"],
    "moth": ["#2A2236", "#4E405E", "#7E6C84", "#B0A0A8", "#E2D6CC"],
    "leech": ["#1A1424", "#33263E", "#4E3A5A", "#6E5A7A", "#A890B0"],
    "KEY": ["#3D0B3D", "#7A1A7A", "#B32BB3", "#E055E0", "#FF9CFF"],   # swapped at runtime, never shown as is
}
RECOLOR = {   # per-app looks, same 5 steps as KEY
    "mint": ["#173D38", "#2F7A6E", "#4FB09A", "#86D9BE", "#D2F7E6"],
    "rose": ["#4A1730", "#9A3560", "#D45A86", "#F28FB0", "#FFD8E6"],
    "lilac": ["#26203E", "#5F5385", "#8A74A6", "#B59BC9", "#EADCF4"],
    "sky": ["#16325C", "#2F68A8", "#5FA8F0", "#8CC4F7", "#DCEFFF"],
    "amber": ["#3E2410", "#8A5422", "#C98A3A", "#F2BE5E", "#FFEAB4"],
    "leaf": ["#183E24", "#2F7A2E", "#46A83A", "#7FD04A", "#D2F07A"],
    "coral": ["#4A1E14", "#A0422A", "#E0703F", "#F7A06A", "#FFDCBC"],
    "cream": ["#4A3022", "#A8723F", "#D9B98A", "#F1E4C3", "#FFFAEE"],
}
EXTRA = {
    "eye": "#22182E", "eyew": "#FFFFFF", "blush": "#F59BB8", "pus": "#D9E36A",
    "sky0": "#3B6FC2", "sky1": "#5FA8F0", "sky2": "#8CC4F7", "sky3": "#A9D8FF", "sky4": "#D7EEFF",
    "dusk0": "#2B2252", "dusk1": "#5A3D7A", "dusk2": "#A0558A", "dusk3": "#F08A6A", "dusk4": "#FFC98A",
    "night0": "#0E0D1F", "night1": "#1B1A33", "night2": "#2B2842", "night3": "#3B3560", "night4": "#5A4E86",
    "star": "#F4FAFF", "star2": "#FFE9A8", "moon": "#F1E4C3", "moon2": "#C9B98E",
    "wall0": "#2A1E2E", "wall1": "#3E2A36", "wall2": "#5A3B3A", "wall3": "#7A5244", "wall4": "#9E6E52",
    "glass0": "#F2FFFC", "glass1": "#B9F2EE", "glass2": "#6FAFC0", "glass3": "#3E6E86", "glass4": "#24425A",
    "twine": "#C9A66B", "twine2": "#8A6A3A", "flame": "#FFC96B", "flame2": "#FFF4C2",
}
PAL = {c for r in list(RAMPS.values()) + list(RECOLOR.values()) for c in r} | set(EXTRA.values())


def rgb(h):
    return tuple(int(h[i:i + 2], 16) for i in (1, 3, 5))


ATLAS = []


class Canvas:
    """Sparse RGBA pixels (hex color + alpha 0..255); later writes win."""

    def __init__(self, w, h):
        self.w, self.h, self.px = w, h, {}

    def put(self, x, y, c, a=255):
        x, y = int(round(x)), int(round(y))
        if c and 0 <= x < self.w and 0 <= y < self.h:
            assert c in PAL, c
            self.px[(x, y)] = (c, a)

    def get(self, x, y):
        return self.px.get((int(round(x)), int(round(y))), (None, 0))[0]

    def has(self, x, y):
        return (int(round(x)), int(round(y))) in self.px

    def image(self):
        img = Image.new("RGBA", (self.w, self.h), (0, 0, 0, 0))
        for (x, y), (c, a) in self.px.items():
            img.putpixel((x, y), rgb(c) + (a,))
        return img


# ---------------------------------------------------------------- shapes (sets of pixels)

def ellipse(cx, cy, rx, ry):
    return {(x, y) for y in range(int(cy - ry - 1), int(cy + ry + 2)) for x in range(int(cx - rx - 1), int(cx + rx + 2))
            if ((x + 0.5 - cx) / max(rx, 0.5)) ** 2 + ((y + 0.5 - cy) / max(ry, 0.5)) ** 2 <= 1.0}


def poly(pts):
    ys = [p[1] for p in pts]
    out = set()
    for y in range(int(math.floor(min(ys))), int(math.ceil(max(ys))) + 1):
        yc = y + 0.5
        cross = []
        for i in range(len(pts)):
            (x0, y0), (x1, y1) = pts[i], pts[(i + 1) % len(pts)]
            if (y0 <= yc < y1) or (y1 <= yc < y0):
                cross.append(x0 + (yc - y0) * (x1 - x0) / (y1 - y0))
        cross.sort()
        for a, b in zip(cross[::2], cross[1::2]):
            for x in range(int(math.ceil(a - 0.5)), int(math.floor(b - 0.5)) + 1):
                out.add((x, y))
    return out


def capsule(x0, y0, x1, y1, r0, r1=None):
    r1 = r0 if r1 is None else r1
    out = set()
    n = int(max(abs(x1 - x0), abs(y1 - y0)) * 2) + 2
    for i in range(n + 1):
        t = i / n
        out |= ellipse(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, r0 + (r1 - r0) * t, r0 + (r1 - r0) * t)
    return out


def rect(x, y, w, h):
    return {(i, j) for j in range(y, y + h) for i in range(x, x + w)}


# ---------------------------------------------------------------- the volume shader

def shade(cv, mask, ramp, light=(-0.6, -0.8), outline=True, rim=True, ao=0.18, flat=0.0, inner=True, alpha=255):
    """Paint [mask] with a 5-step ramp as a soft volume: distance-to-edge gives a dome, its slope meets a top-left key
    light, the bottom gets ambient occlusion, edges facing the light get a rim highlight. Outlines use the ramp's own
    dark tint (lighter on the lit top), with a softer inner line where the part overlaps earlier parts."""
    r = RAMPS[ramp] if isinstance(ramp, str) else ramp
    mask = {(int(x), int(y)) for x, y in mask}
    if not mask:
        return
    dist = {}
    frontier = [p for p in mask if any((p[0] + dx, p[1] + dy) not in mask for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)))]
    for p in frontier:
        dist[p] = 1
    while frontier:
        nxt = []
        for (x, y) in frontier:
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                q = (x + dx, y + dy)
                if q in mask and q not in dist:
                    dist[q] = dist[(x, y)] + 1
                    nxt.append(q)
        frontier = nxt
    dmax = max(dist.values())
    ys = [p[1] for p in mask]
    y0, y1 = min(ys), max(ys)
    lx, ly = light
    ln = math.hypot(lx, ly)
    lx, ly = lx / ln, ly / ln
    covered = {p for p in mask if cv.has(*p)}
    for (x, y) in mask:
        d = dist[(x, y)]
        gx = dist.get((x + 1, y), 0) - dist.get((x - 1, y), 0)
        gy = dist.get((x, y + 1), 0) - dist.get((x, y - 1), 0)
        n = math.hypot(gx, gy) or 1
        nx, ny = -gx / n, -gy / n           # outward normal
        facing = nx * lx + ny * ly         # > 0: this side faces the light
        h = (d / dmax) ** 0.6
        v = 0.5 + 0.4 * facing * (1 - h) + 0.2 * h - ao * (y - y0) / max(1, y1 - y0) + flat
        k = 1 if v < 0.36 else (2 if v < 0.6 else (3 if v < 0.84 else 4))
        if rim and d == 1 and facing > 0.5:
            k = 4
        cv.put(x, y, r[k], alpha)
    if outline:
        for (x, y) in mask:
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                q = (x + dx, y + dy)
                if q in mask:
                    continue
                if not cv.has(*q):
                    cv.put(q[0], q[1], r[1] if dy < 0 else r[0], alpha)
                elif inner and q in covered or (inner and cv.has(*q) and q not in mask):
                    cv.put(x, y, r[1], alpha)   # soft inner line where this part sits on something drawn earlier


def leaf_cluster(cv, rng, cx, cy, rx, ry, n, ramp, leaf=(3, 2)):
    """Owlboy-style foliage: many small leaf stamps layered back to front, each lit from the top-left."""
    r = RAMPS[ramp]
    stamps = []
    for _ in range(n):
        a = rng.random() * math.tau
        d = rng.random() ** 0.6
        stamps.append((cx + math.cos(a) * rx * d, cy + math.sin(a) * ry * d))
    stamps.sort(key=lambda p: p[1])
    mass = set()
    for (x, y) in stamps:
        mass |= ellipse(x, y, leaf[0] * 0.9, leaf[1] * 0.9)
    shade(cv, mass, ramp, inner=False, ao=0.25)
    for (x, y) in stamps:   # leaf tips: highlight crescent top-left, shadow notch bottom-right
        if (int(x), int(y)) in mass and rng.random() < 0.6:
            cv.put(x - 1, y - 1, r[4] if y < cy else r[3])
        if (int(x + 1), int(y + 1)) in mass and rng.random() < 0.5:
            cv.put(x + 1, y + 1, r[1])
    for _ in range(max(2, n // 4)):   # loose leaf chips on the edge
        a = rng.random() * math.tau
        cv.put(cx + math.cos(a) * (rx + 2), cy + math.sin(a) * (ry + 2), r[3] if math.sin(a) < 0 else r[2])
    return mass


def canopy(cv, rng, cx, cy, rx, ry, ramp, lobes=9):
    """A tree crown made of distinct clumps: each lobe is its own lit volume, drawn back (top) to front (bottom), so
    clumps overlap with shadowed seams; then leaf texture (lit crescents, dark pockets) and leafy edge chips."""
    r = RAMPS[ramp]
    clumps = [(cx, cy - ry * 0.25, rx * 0.5, ry * 0.5)]
    for i in range(lobes):
        a = i / lobes * math.tau + rng.random() * 0.5
        d = rng.uniform(0.45, 0.75)
        clumps.append((cx + math.cos(a) * rx * d, cy + math.sin(a) * ry * d * 0.85,
                       rx * rng.uniform(0.28, 0.4), ry * rng.uniform(0.32, 0.46)))
    clumps.sort(key=lambda c: c[1])
    mass = set()
    for (x, y, a, b2) in clumps:
        m = ellipse(x, y, a, b2)
        edge = [p for p in m if any((p[0] + dx, p[1] + dy) not in m for dx, dy in ((2, 0), (-2, 0), (0, 2), (0, -2)))]
        for (ex, ey) in edge[::5]:   # leafy, bumpy rim on every clump
            m |= ellipse(ex + rng.uniform(-1, 1), ey + rng.uniform(-1, 1), 1.8, 1.3)
        shade(cv, m, ramp, ao=0.3, inner=True)
        mass |= m
    for (x, y) in sorted(mass):
        c = cv.get(x, y)
        h = (x * 73856093 ^ y * 19349663) % 97
        if c == r[3] and h < 20:         # leaf crescents catching the light
            cv.put(x, y, r[4])
            if (x - 1, y + 1) in mass:
                cv.put(x - 1, y + 1, r[3])
        elif c == r[2] and h < 14:       # leaf edges in the mid tones
            cv.put(x, y, r[3]); cv.put(x + 1, y + 1, r[1])
        elif c == r[1] and h < 16:       # dark pockets between leaves
            cv.put(x, y, r[0])
    edge = [p for p in mass if any((p[0] + dx, p[1] + dy) not in mass for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)))]
    for _ in range(len(edge) // 8):   # loose leaf chips around the edge
        x, y = rng.choice(edge)
        cv.put(x + rng.choice((-2, 2)), y + rng.choice((-1, 1, 2)), r[3] if y < cy else r[2])
    return mass


# ---------------------------------------------------------------- creatures

KINDS = {}   # preset id -> kingdom (read from the engine's presets)
STAGE = {1: (30, 32), 2: (38, 42), 3: (46, 54), 4: (52, 60), 5: (56, 64), 6: (60, 68), 7: (64, 72), 8: (68, 80)}   # frame w, h
STAGES = 8   # matches the engine's stage_thresholds (assets/config/terrarium.json)


def face(cv, cx, cy, size, blink, sleep):
    """Big Owlboy-ish eyes: dark ovals with a white glint, blush, a tiny mouth."""
    ew, eh = (2, 3) if size == 1 else ((2, 4) if size == 2 else (3, 4))
    gap = {1: 2, 2: 3, 3: 4}[size]
    for side in (-1, 1):
        ex = cx + (gap if side > 0 else -gap - ew)
        if sleep or blink:
            for i in range(-1 if side < 0 else 0, ew + (1 if side > 0 else 0)):
                cv.put(ex + i, cy + eh - 1, EXTRA["eye"])
            if sleep:
                cv.put(ex - (1 if side < 0 else -ew), cy + eh - 2, EXTRA["eye"])
        else:
            for i in range(ew):
                for j in range(eh):
                    cv.put(ex + i, cy + j, EXTRA["eye"])
            cv.put(ex, cy, EXTRA["eyew"])
            if size == 3:
                cv.put(ex + 1, cy, EXTRA["eyew"])
                cv.put(ex + ew - 1, cy + eh - 1, RAMPS["ink"][3])
        bx = ex - 1 if side < 0 else ex + ew
        cv.put(bx, cy + eh, EXTRA["blush"])
        if size > 1:
            cv.put(bx + (-1 if side < 0 else 1), cy + eh, EXTRA["blush"])
    my = cy + eh + 1
    if sleep:
        cv.put(cx, my, EXTRA["eye"])
    else:
        cv.put(cx - 1, my, EXTRA["eye"]); cv.put(cx, my + 1, EXTRA["eye"]); cv.put(cx + 1, my, EXTRA["eye"])


def feet(cv, cx, by, spread, ramp):
    for side in (-1, 1):
        shade(cv, ellipse(cx + side * spread, by - 1, 2.4, 1.5), ramp, rim=False, inner=False)


def creature(pid, stage, frame, sleep):
    """One frame of a creature at any of the 8 stages: the body keeps growing, and from stage 4 on each stage adds a mark
    of age: moss tufts (4), flowers and drifting leaves (5), companion mushrooms (6), glowing spores (7), a halo (8)."""
    cv, head = creature_body(pid, stage, frame, sleep)
    if stage < 4:
        return cv, head
    w, h = STAGE[stage]
    cx, base = w // 2, h - 2
    rng = random.Random(zlib.crc32(f"{pid}deco".encode()))
    sway = 0 if sleep else (0, 1, 0, -1)[frame]
    for side in (-1, 1):   # 4: moss tufts at the feet
        x0 = cx + side * (w // 2 - 7)
        for k in range(5):
            hgt = 2 + (k * 7 + side) % 3
            for j in range(hgt):
                cv.put(x0 + k - 2 + (sway if j == hgt - 1 else 0), base - j, RAMPS["moss"][3 if j == hgt - 1 else 2])
    if stage >= 5:   # flowers at the feet, two leaves drifting near the head
        for i, dx in enumerate((-w // 2 + 3, w // 2 - 4, -w // 2 + 9)):
            col = ("blossom", "sun", "cloud")[i]
            for ddx, ddy in ((0, -1), (-1, 0), (1, 0), (0, 1)):
                cv.put(cx + dx + ddx, base - 4 + ddy, RAMPS[col][3])
            cv.put(cx + dx, base - 4, RAMPS["sun"][2])
        for i, (lx, ly) in enumerate(((-10, 6), (11, 2))):
            bob = 0 if sleep else (0, -1, -2, -1)[(frame + i) % 4]
            shade(cv, ellipse(head[0] + lx, head[1] + ly + bob, 1.8, 1.1), "grass", rim=False, inner=False)
    if stage >= 6:   # companion mushrooms
        for dx, hh in ((-w // 2 + 5, 4), (w // 2 - 6, 6)):
            for k in range(hh):
                cv.put(cx + dx, base - k, RECOLOR["cream"][3])
            shade(cv, {p for p in ellipse(cx + dx + 0.5, base - hh, 2.6, 1.9) if p[1] <= base - hh}, "flyred", inner=False)
            cv.put(cx + dx, base - hh - 1, RAMPS["cloud"][4])
    if stage >= 7:   # glowing spores drifting around the body
        for i in range(7):
            a = i / 7 * math.tau + (0 if sleep else frame * 0.4)
            r = w * 0.42
            x, y = cx + math.cos(a) * r, (head[1] + base) / 2 + math.sin(a) * r * 0.6
            cv.put(x, y, RAMPS["glow"][4], 230)
            cv.put(x + 1, y, RAMPS["glow"][3], 140)
    if stage >= 8:   # a golden halo that shimmers
        hy = max(3, head[1] - 4)
        for deg in range(0, 360, 12):
            a = math.radians(deg)
            k = 4 if (deg // 12 + frame) % 4 == 0 else 3
            cv.put(head[0] + math.cos(a) * 7, hy + math.sin(a) * 2, RAMPS["sun"][k])
        head = [head[0], hy - 2]
    return cv, head


def creature_body(pid, stage, frame, sleep):
    """The creature itself. The KEY ramp marks the per-app color. Returns (canvas, head anchor [x, y])."""
    w, h = STAGE[stage]
    cv = Canvas(w, h)
    kind = KINDS.get(pid, "plant")
    rng = random.Random(zlib.crc32(f"{pid}{stage}".encode()))
    bob = 0 if sleep else (0, -1, 0, 0)[frame]
    sq = 1 if (frame == 2 and not sleep) else 0          # squash on the down beat
    sway = 0 if sleep else (0, 1, 0, -1)[frame]
    blink = frame == 3
    cx = w // 2
    base = h - 2
    s = min(stage, 3)            # feature tier (adult features from stage 3 on)
    grow = max(0, stage - 3)     # older stages keep getting bigger
    bw = {1: 7, 2: 9, 3: 11}[s] + grow
    bh = {1: 6.5, 2: 8, 3: 9.5}[s] + grow * 0.7 - (1 if sleep else 0)
    by = base - bh + bob + sq * 0.5
    head = [cx, int(by - bh)]

    def body(ramp="KEY", rx=bw, ry=bh, y=None):
        m = ellipse(cx, (by if y is None else y), rx + sq * 0.6, ry - sq * 0.6)
        shade(cv, m, ramp)
        return m

    if kind == "mushroom":
        feet(cv, cx, base, bw * 0.45, "cork")
        body("KEY", bw * 0.72, bh * 0.85, by + 2)
        cap_ramp = {"preset14": "flyred", "preset02": "ink", "preset25": "cloud"}.get(pid, "flyred")
        cap_h = {1: 6, 2: 8, 3: 10}[s] + (3 if pid == "preset02" else 0)
        cap_w = bw + {1: 3, 2: 4, 3: 5}[s]
        ctop = by - bh - cap_h + 5
        pts = [(cx - cap_w, ctop + cap_h), (cx - cap_w + 2, ctop + cap_h * 0.35), (cx - cap_w * 0.45, ctop),
               (cx + cap_w * 0.45, ctop), (cx + cap_w - 2, ctop + cap_h * 0.35), (cx + cap_w, ctop + cap_h)]
        if pid == "preset02":   # ink cap: tall bell
            pts = [(cx - cap_w + 2, ctop + cap_h), (cx - cap_w + 3, ctop + 3), (cx - 2, ctop - 2), (cx + 2, ctop - 2),
                   (cx + cap_w - 3, ctop + 3), (cx + cap_w - 2, ctop + cap_h)]
        cap = poly([(x + sway * 0.5, y) for x, y in pts])
        shade(cv, cap, cap_ramp)
        for x in range(int(cx - cap_w + 2), int(cx + cap_w - 1)):   # gills under the rim
            cv.put(x + sway * 0.5, ctop + cap_h, RAMPS["cork"][3] if x % 2 else RAMPS["cork"][2])
        if pid == "preset14":   # fly agaric: white warts
            for (dx, dy, r) in ((-5, 3, 1.6), (2, 2, 1.3), (6, 5, 1.1), (-1, 6, 1.0), (-8, 6, 0.9)):
                for (x, y) in ellipse(cx + dx * s / 2.2 + sway * 0.5, ctop + dy * s / 2.4, r * (1 + s / 3), r * (1 + s / 4)):
                    if (x, y) in cap:
                        cv.put(x, y, RAMPS["cloud"][4] if y < ctop + cap_h / 2 else RAMPS["cloud"][2])
        if pid == "preset02" and s > 1:   # inky drips
            for dx in (-cap_w + 3, 0, cap_w - 4):
                for k in range(s):
                    cv.put(cx + dx, ctop + cap_h + 1 + k, RAMPS["ink"][1 + (k % 2)])
        if pid == "preset25":   # shaggy fringe
            for x in range(int(cx - cap_w + 1), int(cx + cap_w)):
                for k in range(1 + (x * 7 % 3)):
                    cv.put(x + sway * 0.5, ctop + cap_h + 1 + k, RAMPS["cloud"][2 + (k % 2)])
        face(cv, cx, int(by), s, blink, sleep)
        return cv, [cx, int(ctop)]

    if kind == "jelly":
        fy = base - 10 - s * 3 + bob * 2
        tent_r = "glow" if pid == "preset07" else "sun"
        n = 3 + s
        for i in range(n):
            x0 = cx - bw + 3 + i * (2 * bw - 6) / max(n - 1, 1)
            ln = 5 + s * 3 + (i % 2) * 2
            for k in range(ln):
                x = x0 + math.sin(k * 0.6 + i + frame * 1.2) * (1 + k / 6)
                cv.put(x, fy + bh * 0.5 + k, RAMPS[tent_r][2 if k % 3 else 3], 220)
        bell = {p for p in ellipse(cx, fy, bw + 1, bh) if p[1] < fy + bh * 0.55}
        shade(cv, bell, "KEY", alpha=235)
        if pid == "preset07":   # moon jelly: four rings
            for (dx, dy) in ((-3, -2), (3, -2), (-1, -4), (1, 0)):
                for (x, y) in ellipse(cx + dx * s / 2, fy + dy * s / 2.5, 1.2 + s * 0.3, 1.0 + s * 0.2):
                    if (x, y) in bell:
                        cv.put(x, y, RECOLOR["cream"][3])
        face(cv, cx, int(fy - 1), s, blink, sleep)
        return cv, [cx, int(fy - bh)]

    feet(cv, cx, base, bw * 0.5, "earth" if kind in ("tree", "cactus") else "moss")

    if kind == "slime":
        m = {p for p in ellipse(cx, base - bh + 1 + bob, bw + 1 + sq, bh + 1 - sq) if p[1] <= base}
        shade(cv, m, "KEY", alpha=235, ao=0.1)
        for (dx, dy) in ((-3, -2), (2, 1), (4, -3)):
            cv.put(cx + dx * s / 2, base - bh + 1 + dy + bob, RECOLOR["cream"][4], 200)
        if pid == "preset18":   # cave slime carries crystals
            for dx in (-bw + 2, bw - 3):
                shade(cv, poly([(cx + dx - 2, base - bh * 0.9), (cx + dx, base - bh * 1.7 - s), (cx + dx + 2, base - bh * 0.9)]), "glow")
        face(cv, cx, int(base - bh + bob), s, blink, sleep)
        return cv, [cx, int(base - bh * 2 + bob)]

    if kind == "crystal":
        n = 2 + s
        cr = "glow" if pid == "preset08" else "rock"
        for i in range(n):
            x = cx - bw + 2 + i * (2 * bw - 4) / max(n - 1, 1)
            hh = 4 + s * 3 + (4 if i == n // 2 else (i % 2) * 2)
            shade(cv, poly([(x - 2, by - bh + 3), (x, by - bh + 2 - hh), (x + 2, by - bh + 3)]), cr)
        m = poly([(cx - bw, base), (cx - bw - 1, by), (cx - bw * 0.5, by - bh), (cx + bw * 0.5, by - bh), (cx + bw + 1, by), (cx + bw, base)])
        shade(cv, m, "KEY", light=(-0.8, -0.6))
        face(cv, cx, int(by - 1), s, blink, sleep)
        return cv, [cx, int(by - bh - 8 - s * 2)]

    if kind == "tree":
        trunk = capsule(cx, base - 1, cx + sway * 0.5, by - bh * 0.4, bw * 0.75, bw * 0.55)
        shade(cv, trunk, "KEY", light=(-0.9, -0.4))
        for (x, y) in trunk:   # bark scales
            if (x + y) % 4 == 0 and cv.get(x, y) == RAMPS["KEY"][2]:
                cv.put(x, y, RAMPS["KEY"][1])
        crown_y = by - bh - 2 - s
        if pid == "preset23":   # willow: hanging strands
            leaf_cluster(cv, rng, cx + sway * 0.5, crown_y, bw + 2, 4 + s, 10 + s * 6, "grass")
            for i in range(4 + s * 2):
                x = cx - bw - 1 + i * (2 * bw + 2) / (3 + s * 2)
                for k in range(3 + s * 3):
                    cv.put(x + sway * k * 0.1, crown_y + 3 + k, RAMPS["grass"][2 + (k % 2)])
        else:
            leaf_cluster(cv, rng, cx + sway * 0.5, crown_y, bw + 1 + s, 4 + s * 1.5, 10 + s * 7, "blossom" if s == 3 else "grass")
        face(cv, cx, int(by + 1), s, blink, sleep)
        return cv, [cx, int(crown_y - 4 - s)]

    m = body()
    if kind == "cactus":
        for (x, y) in m:   # ribs
            if x % 3 == cx % 3 and cv.get(x, y) in RAMPS["KEY"][2:4]:
                cv.put(x, y, RAMPS["KEY"][1])
        edge = sorted(p for p in m if any((p[0] + dx, p[1] + dy) not in m for dx, dy in ((1, 0), (-1, 0), (0, -1))))
        for (x, y) in edge[::3]:   # spines
            cv.put(x + (1 if x > cx else -1), y - 1, RECOLOR["cream"][4])
        if pid == "preset17" and s > 1:   # prickly pear: ear pads
            for side in (-1, 1):
                shade(cv, ellipse(cx + side * (bw - 1), by - bh + 1, 3 + s * 0.5, 4 + s * 0.6), "KEY")
        if s == 3:
            shade(cv, ellipse(cx + sway * 0.4, by - bh - 1, 3, 2.5), "blossom")
    elif kind in ("plant", "fern", "vine", "moss"):
        if kind == "moss":
            leaf_cluster(cv, rng, cx, by - bh * 0.4, bw + 1, bh * 0.6, 12 + s * 6, "moss", leaf=(2, 2))
            if s == 3:
                for dx in (-5, 1, 6):
                    cv.put(cx + dx, by - bh - 1, RAMPS["blossom"][3]); cv.put(cx + dx, by - bh, RAMPS["grass"][4])
        n = {1: 2, 2: 3, 3: 4}[s] if kind != "moss" else 0
        tip_y = by - bh
        for i in range(n):
            a = -math.pi / 2 + (i - (n - 1) / 2) * 0.55
            ln = 4 + s * 2 + (i % 2)
            x, y = cx, by - bh + 1
            for k in range(ln):
                x += math.cos(a) + sway * 0.06 * k
                y += math.sin(a)
                a += (0.08 if i % 2 else -0.08) * (2 if kind == "vine" else 1)
                cv.put(x, y, RAMPS["grass"][1])
            tip_y = min(tip_y, y)
            if kind == "fern":
                for k in range(3):
                    shade(cv, ellipse(x - math.cos(a) * k * 2, y - math.sin(a) * k * 2, 1.6, 1.0), "grass", rim=False)
            elif kind == "vine" and pid == "preset16":   # ivy: heart leaves
                shade(cv, poly([(x - 2.5, y), (x, y - 2.5), (x + 2.5, y), (x, y + 2.5)]), "grass")
            else:
                shade(cv, ellipse(x, y, 2.4, 1.6), "grass")
        head = [cx, int(tip_y - 2)]
        if s >= 2 and kind in ("plant", "vine"):
            fr = {"preset24": "sun", "preset04": "water"}.get(pid, "blossom")
            fx, fy = cx + sway * 0.6, by - bh - 3 - s * 2
            for k in range(6):
                a = k / 6 * math.tau
                shade(cv, ellipse(fx + math.cos(a) * (1.6 + s * 0.5), fy + math.sin(a) * (1.6 + s * 0.5), 1.3 + s * 0.2, 1.3 + s * 0.2), fr, rim=False, inner=False)
            cv.put(fx, fy, RAMPS["earth"][3] if pid == "preset24" else RAMPS["sun"][3])
            head = [cx, int(fy - 4)]
    elif kind in ("coral", "anemone"):
        n = 3 + s
        tr = {"preset03": "blossom", "preset15": "sun", "preset11": "blossom", "preset22": "flyred"}.get(pid, "blossom")
        for i in range(n):
            x0 = cx - bw + 2 + i * (2 * bw - 4) / max(n - 1, 1)
            ln = 3 + s * 2 + (i % 2) * 2
            if kind == "anemone":
                shade(cv, capsule(x0, by - bh + 2, x0 + math.sin(frame + i) * 1.5, by - bh + 2 - ln, 1.1, 0.8), tr, inner=False)
                if pid == "preset22":   # tube worms: plumes
                    cv.put(x0 + math.sin(frame + i) * 1.5, by - bh + 1 - ln, RAMPS["flyred"][4])
            else:
                shade(cv, capsule(x0, by - bh + 3, x0 + (i - n / 2) * 0.6, by - bh + 2 - ln, 1.4, 1.0), tr, inner=False)
        if pid == "preset03":   # brain coral: wrinkles
            for (x, y) in m:
                if (math.sin(x * 0.9) + math.cos(y * 1.3)) > 1.2:
                    cv.put(x, y, RAMPS["KEY"][1])
        head = [cx, int(by - bh - 4 - s * 2)]
    face(cv, cx, int(by - (1 if s > 1 else 0)), s, blink, sleep)
    return cv, head


# ---------------------------------------------------------------- accessories (worn on the head anchor)

def accessory(kind):
    cv = Canvas(14, 10)
    if kind == "acorn":
        shade(cv, {p for p in ellipse(7, 7, 5, 3.5) if p[1] <= 7}, "cork")
        for x in range(3, 12):
            if x % 2:
                cv.put(x, 5, RAMPS["cork"][1])
        cv.put(7, 2, RAMPS["earth"][2]); cv.put(7, 1, RAMPS["earth"][2]); cv.put(8, 1, RAMPS["earth"][3])
    elif kind == "leaf":
        shade(cv, poly([(2, 9), (6, 3), (12, 2), (10, 7)]), "grass")
        for k in range(7):
            cv.put(3 + k, 8 - k * 0.8, RAMPS["grass"][1])
    elif kind == "flowers":
        for i, x in enumerate((3, 7, 11)):
            col = ("blossom", "sun", "cloud")[i]
            for dx, dy in ((0, -1), (-1, 0), (1, 0), (0, 1)):
                cv.put(x + dx, 7 + dy, RAMPS[col][3])
            cv.put(x, 7, RAMPS["sun"][2])
        for x in range(2, 13):
            cv.put(x, 9, RAMPS["grass"][2])
    elif kind == "bow":
        shade(cv, poly([(1, 3), (7, 6), (1, 9)]) | poly([(13, 3), (7, 6), (13, 9)]), "blossom")
        shade(cv, ellipse(7, 6, 1.6, 1.6), "blossom", inner=False)
    elif kind == "crown":
        shade(cv, poly([(2, 9), (2, 3), (5, 6), (7, 2), (9, 6), (12, 3), (12, 9)]), "sun")
        cv.put(7, 6, RAMPS["blossom"][3]); cv.put(4, 7, RAMPS["water"][3]); cv.put(10, 7, RAMPS["water"][3])
    elif kind == "beret":
        shade(cv, ellipse(7, 7, 6, 2.6), RECOLOR["rose"])
        cv.put(7, 4, RECOLOR["rose"][1])
    elif kind == "sprout":
        for k in range(5):
            cv.put(7, 9 - k, RAMPS["grass"][2])
        shade(cv, ellipse(4.5, 4, 2.5, 1.5), "grass", inner=False)
        shade(cv, ellipse(9.5, 3, 2.5, 1.5), "grass", inner=False)
    elif kind == "shroom":
        shade(cv, {p for p in ellipse(7, 7, 6, 4.5) if p[1] <= 7}, "flyred")
        for (x, y) in ((4, 5), (8, 4), (10, 6)):
            cv.put(x, y, RAMPS["cloud"][4])
    return cv


ACCESSORIES = ["acorn", "leaf", "flowers", "bow", "crown", "beret", "sprout", "shroom"]


# ---------------------------------------------------------------- snoops and fx

def snoop(variant, frame):
    cv = Canvas(18, 16)
    bob = (0, -1, 0, 1)[frame]
    if variant == 0:   # tick-mite: bloated, greedy eyes
        for i, lx in enumerate((-5, -2, 2, 5)):
            kick = 1 if (i + frame) % 2 else 0
            cv.put(9 + lx, 12 + bob + kick, RAMPS["mite"][0]); cv.put(9 + lx + (1 if lx > 0 else -1), 13 + bob + kick, RAMPS["mite"][0])
        shade(cv, ellipse(9, 8 + bob, 6, 4.6), "mite")
        shade(cv, ellipse(9, 4 + bob, 3, 2.2), "mite")
        for dx in (-1, 2):
            cv.put(8 + dx, 4 + bob, EXTRA["pus"]); cv.put(8 + dx, 3 + bob, EXTRA["eyew"])
        cv.put(9, 6 + bob, RAMPS["infect"][4])
    elif variant == 1:   # dusty moth with a data-sucking proboscis
        flap = (0, 1, 2, 1)[frame]
        for side in (-1, 1):
            shade(cv, ellipse(9 + side * (4.5 - flap * 0.6), 7 - flap + bob, 4 - flap * 0.5, 3.4 + flap * 0.3), "moth", inner=False)
            cv.put(9 + side * 5, 7 - flap + bob, RAMPS["mite"][3])
        shade(cv, capsule(9, 4 + bob, 9, 11 + bob, 1.5, 1.2), "leech", inner=False)
        cv.put(8, 4 + bob, EXTRA["pus"]); cv.put(10, 4 + bob, EXTRA["pus"])
        cv.put(9, 12 + bob, RAMPS["infect"][3]); cv.put(9, 13 + bob, RAMPS["infect"][2])
    else:   # leech: slick, one wide mouth
        wig = (0, 1, 0, -1)[frame]
        shade(cv, capsule(4, 10 + bob, 14, 8 + bob + wig, 2.6, 3.2), "leech")
        for k in range(3):
            cv.put(6 + k * 3, 8 + bob + (wig if k == 2 else 0), RAMPS["leech"][4])
        cv.put(15, 8 + bob + wig, EXTRA["pus"]); cv.put(16, 9 + bob + wig, RAMPS["infect"][3])
    return cv


def poof(frame):
    cv = Canvas(20, 20)
    rng = random.Random(7)
    r = 2 + frame * 1.8
    for i in range(9):
        a = i / 9 * math.tau + rng.random() * 0.3
        d = r * rng.uniform(0.7, 1.0)
        s = max(0.9, 3.0 - frame * 0.5)
        shade(cv, ellipse(10 + math.cos(a) * d, 10 + math.sin(a) * d, s, s), "cloud", outline=frame < 3, inner=False)
    return cv


def sparkle(frame):
    cv = Canvas(9, 9)
    c = [RAMPS["grass"][4], RAMPS["sun"][4], EXTRA["eyew"]][frame]
    arm = [1, 4, 2][frame]
    for d in range(-arm, arm + 1):
        cv.put(4 + d, 4, c); cv.put(4, 4 + d, c)
    cv.put(4, 4, EXTRA["eyew"])
    return cv


def zzz(frame):
    cv = Canvas(11, 12)
    for (x, y, s) in [(0, 8, 3), (3, 4, 3), (6, 0, 4)][: frame + 1]:
        for i in range(s):
            cv.put(x + i, y, EXTRA["star"]); cv.put(x + i, y + s - 1, EXTRA["star"]); cv.put(x + s - 1 - i, y + i, EXTRA["star"])
    return cv


def mood(kind, frame):
    """A little parchment speech bubble with the creature's mood: a heart, a storm cloud, or a sick swirl."""
    cv = Canvas(15, 15)
    bub = ellipse(7, 6, 6.5, 5.5)
    shade(cv, bub, RECOLOR["cream"], rim=False, ao=0.1, inner=False)
    for (x, y) in ((4, 11), (3, 12), (4, 12), (2, 13)):   # tail
        cv.put(x, y, RECOLOR["cream"][3])
    cv.put(2, 14, RECOLOR["cream"][0]); cv.put(1, 13, RECOLOR["cream"][0]); cv.put(5, 12, RECOLOR["cream"][0])
    pulse = frame == 1
    if kind == "happy":
        m = ellipse(5.5, 5, 1.8, 1.8) | ellipse(8.5, 5, 1.8, 1.8) | poly([(3.6, 5.6), (10.4, 5.6), (7, 9.4 + (0.6 if pulse else 0))])
        shade(cv, m, "blossom", rim=True, inner=False, outline=False)
        if pulse:
            cv.put(11, 2, RAMPS["sun"][4]); cv.put(12, 3, RAMPS["sun"][3])
    elif kind == "grumpy":
        shade(cv, ellipse(5, 5, 2.4, 1.8) | ellipse(8.5, 4.5, 2.8, 2.2) | ellipse(10, 6, 1.8, 1.4), "ink", rim=False, inner=False, outline=False)
        for (x, y) in ((8, 7), (7, 8), (8, 8), (7, 9), (6, 10)):
            cv.put(x + (1 if pulse else 0), y, RAMPS["sun"][3 if pulse else 2])
    else:   # sick
        r = RAMPS["moss"]
        for i in range(14):
            a = i * 0.75 + (0.6 if pulse else 0)
            rr = 0.6 + i * 0.28
            cv.put(7 + math.cos(a) * rr, 6 + math.sin(a) * rr * 0.9, r[2] if i % 2 else EXTRA["pus"])
    return cv


def heart():
    cv = Canvas(9, 9)
    shade(cv, ellipse(3, 3, 2, 2) | ellipse(6, 3, 2, 2) | poly([(1, 4), (8, 4), (4.5, 8)]), "blossom", inner=False)
    return cv


def firefly(frame):
    cv = Canvas(7, 7)
    if frame == 0:
        for (x, y) in ellipse(3.5, 3.5, 2.6, 2.6):
            cv.put(x, y, RAMPS["sun"][3], 90)
    cv.put(3, 3, RAMPS["sun"][4]); cv.put(3, 4, RAMPS["grass"][4])
    return cv


def mote():
    cv = Canvas(3, 3)
    cv.put(1, 1, RAMPS["sun"][4], 200)
    return cv


# ---------------------------------------------------------------- the terrarium scene

W, H = 320, 600          # world in art pixels
JAR = dict(x0=26, x1=294, top=116, bottom=556, neck0=78, neck1=242, lid_top=74)
BAYER = [0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5]


def jar_mask():
    """The inside of the glass: a tall rounded jar with shoulders and a neck."""
    j = JAR
    out = set()
    for y in range(j["top"] - 18, j["bottom"]):
        if y < j["top"]:                       # neck
            x0, x1 = j["neck0"], j["neck1"]
        elif y < j["top"] + 30:                # shoulders curve out
            e = math.sin((y - j["top"]) / 30 * math.pi / 2)
            x0 = j["neck0"] + (j["x0"] - j["neck0"]) * e
            x1 = j["neck1"] + (j["x1"] - j["neck1"]) * e
        elif y > j["bottom"] - 14:              # rounded base
            t = (y - (j["bottom"] - 14)) / 14
            inset = 14 - math.sqrt(max(0, 1 - t * t)) * 14
            x0, x1 = j["x0"] + inset, j["x1"] - inset
        else:
            x0, x1 = j["x0"], j["x1"]
        for x in range(int(x0), int(x1) + 1):
            out.add((x, y))
    return out


def ground_y(x):
    """Top of the moss inside the jar: a hill on the left, a dip for the pond, a rise to the right."""
    left = 446 - 26 * math.exp(-((x - 86) / 46) ** 2)
    pond = 9 * math.exp(-((x - 172) / 26) ** 2)
    right = -12 * math.exp(-((x - 252) / 36) ** 2)
    return int(left + pond + right + 2 * math.sin(x * 0.21))


def tree_path():
    x, y, a = 86.0, float(ground_y(86)), -math.pi / 2 - 0.1
    path = []
    for i in range(130):
        a += math.sin(i * 0.09) * 0.03
        x += math.cos(a); y += math.sin(a)
        path.append((x, y))
    return path


def room(phase):
    """Wall, window with sky (day/dusk/night), shelf and props. Drawn behind the glass; the sky shows through the jar."""
    cv = Canvas(W, H + 120)
    rng = random.Random(3)
    sky = {"day": ["sky0", "sky1", "sky2", "sky3", "sky4"], "dusk": ["dusk0", "dusk1", "dusk2", "dusk3", "dusk4"],
           "night": ["night0", "night1", "night2", "night3", "night4"]}[phase]
    lift = {"day": 2, "dusk": 1, "night": 0}[phase]
    walls = ["wall0", "wall1", "wall2", "wall3", "wall4"]
    for y in range(cv.h):
        for x in range(W):
            plank = x // 20
            k = 1 + lift if (x % 20) not in (0, 19) else lift
            if (x * 3 + plank * 17 + y // 9) % 23 == 0:
                k = max(0, k - 1)
            cv.put(x, y, EXTRA[walls[min(4, k)]])
    wx0, wx1, wy0, wy1 = 18, 302, 18, 470
    win = set()
    r = (wx1 - wx0) / 2
    for y in range(wy0, wy1):
        for x in range(wx0, wx1):
            if y < wy0 + 140 and (x - (wx0 + wx1) / 2) ** 2 + (y - (wy0 + 140)) ** 2 > r * r:
                continue
            win.add((x, y))
    skycv = Canvas(W, H + 120)   # the sky is its own layer, so the sun and moon can move behind clouds and the frame
    for p in win:
        cv.px.pop(p, None)
    top, bot = min(p[1] for p in win), max(p[1] for p in win)
    for (x, y) in win:
        f = (y - top) / (bot - top) * 4
        b = min(3, int(f))
        th = (BAYER[(y % 4) * 4 + x % 4] + 0.5) / 16
        skycv.put(x, y, EXTRA[sky[b + 1 if (f - b) > th else b]])
    haze = {"day": [RAMPS["rock"][2], RAMPS["rock"][3], RECOLOR["lilac"][3], RAMPS["rock"][4], RECOLOR["lilac"][4]],
            "dusk": [EXTRA["dusk0"], EXTRA["dusk1"], EXTRA["dusk1"], EXTRA["dusk2"], EXTRA["dusk3"]],
            "night": [EXTRA["night1"], EXTRA["night2"], EXTRA["night2"], EXTRA["night3"], EXTRA["night4"]]}[phase]
    top_c = {"day": [RAMPS["moss"][3], RAMPS["moss"][4]], "dusk": [EXTRA["dusk2"], EXTRA["dusk3"]], "night": [EXTRA["night3"], EXTRA["night4"]]}[phase]
    for (ix, iy, iw) in ((178, 150, 16), (262, 338, 22), (150, 236, 12)):   # far islands (Owlboy nod), hazy with depth
        irng = random.Random(ix)
        pts = [(ix - iw, iy), (ix + iw, iy)]
        for k in range(1, 8):
            t = k / 8
            pts.append((ix + iw - t * 2 * iw, iy + math.sin(t * math.pi) ** 0.55 * iw * (0.55 + irng.random() * 0.2) + (2 if k == 4 else 0)))
        isl = {p for p in poly(pts) if p in win}
        shade(cv, isl, haze, ao=0.4, outline=False)
        for x in range(ix - iw, ix + iw + 1):
            for d in range(2 + (x % 3 == 0)):
                if (x, iy - 1 + d) in win:
                    cv.put(x, iy - 1 + d, top_c[1] if d == 0 else top_c[0])
        for k in range(3):
            rx = ix - iw // 2 + k * iw // 2
            for t in range(4 + (rx % 5)):
                if (rx, iy + iw // 3 + t) in win:
                    cv.put(rx, iy + iw // 3 + t, haze[1])
    if phase == "night":
        for i in range(70):
            x, y = rng.randrange(wx0, wx1), rng.randrange(wy0, 380)
            if (x, y) in win:
                skycv.put(x, y, EXTRA["star"] if i % 5 else EXTRA["star2"])
    else:
        for (cx, cy, s) in ((80, 120, 1.0), (220, 170, 1.3), (130, 230, 0.8)):
            for (px_, py_, rr) in ((-14, 4, 8), (-3, -2, 11), (10, 2, 9), (20, 5, 6)):
                for (x, y) in ellipse(cx + px_ * s, cy + py_ * s, rr * s, rr * s * 0.75):
                    if (x, y) in win:
                        v = (y - (cy - 12 * s)) / (20 * s)
                        cv.put(x, y, RAMPS["cloud"][4 if v < 0.3 else (3 if v < 0.65 else 2)] if phase == "day"
                               else EXTRA["dusk4" if v < 0.4 else "dusk3"])
    frame = set()
    for (x, y) in win:
        if any((x + dx, y + dy) not in win for dx, dy in ((3, 0), (-3, 0), (0, 3), (0, -3))) or abs(x - 160) < 3 or abs(y - 300) < 3:
            frame.add((x, y))
    shade(cv, frame, "wood", inner=False, ao=0.0)
    shade(cv, rect(0, 556, W, 26), "wood", light=(-0.2, -1), ao=0.3)
    for x in range(W):
        cv.put(x, 556, RAMPS["wood"][4]); cv.put(x, 557, RAMPS["wood"][3])
        if x % 37 == 0:
            for y in range(560, 580):
                cv.put(x, y, RAMPS["wood"][1])
    for y in range(582, cv.h):
        for x in range(W):
            cv.put(x, y, EXTRA[walls[max(0, lift - 1)]])
    shade(cv, rect(298, 520, 10, 36), RECOLOR["cream"], ao=0.2)   # candle, lit after dark
    if phase != "day":
        for (x, y) in ellipse(303, 514, 2, 4):
            cv.put(x, y, EXTRA["flame"])
        cv.put(303, 515, EXTRA["flame2"])
    y0 = 555
    for i, (bh_, col) in enumerate(((7, "rose"), (6, "sky"), (8, "leaf"))):   # books
        shade(cv, rect(2, y0 - bh_, 22 - i * 3, bh_), RECOLOR[col], ao=0.1)
        y0 -= bh_ + 1
    return cv, skycv


def sun(frame, dusk):
    """The sun: a warm shaded disc with soft rays that shimmer (two frames); redder and bigger-looking at dusk."""
    cv = Canvas(28, 28)
    r = "sun" if not dusk else RECOLOR["coral"]
    rays = RAMPS["sun"][3] if not dusk else EXTRA["dusk4"]
    for k in range(8):
        a = k / 8 * math.tau + (math.pi / 8 if frame else 0)
        ln = 4 if (k + frame) % 2 == 0 else 3
        for d in range(ln):
            cv.put(14 + math.cos(a) * (10 + d), 14 + math.sin(a) * (10 + d), rays, 220 - d * 50)
    shade(cv, ellipse(14, 14, 7.5, 7.5), r, ao=0.0, inner=False, outline=False)
    for (x, y) in ellipse(14, 14, 9.2, 9.2):   # a soft glow ring
        if not cv.has(x, y):
            cv.put(x, y, RAMPS["sun"][4] if not dusk else EXTRA["dusk4"], 90)
    return cv


def moon_phase(k):
    """The moon in phase k of 8 (0 new ... 4 full ... 7), lit side on the right while waxing."""
    cv = Canvas(22, 22)
    disc = ellipse(11, 11, 8.5, 8.5)
    lit = set()
    f = k / 8.0
    for (x, y) in disc:
        nx = (x + 0.5 - 11) / 8.5
        ny = (y + 0.5 - 11) / 8.5
        edge = math.sqrt(max(0.0, 1 - ny * ny))
        term = edge * math.cos(f * math.tau)   # terminator position across the disc
        if (f <= 0.5 and nx > term) or (f > 0.5 and nx < -term):
            lit.add((x, y))
    for (x, y) in disc:
        if (x, y) in lit:
            cv.put(x, y, EXTRA["moon"])
        else:
            cv.put(x, y, EXTRA["night3"], 150)   # earthshine: the dark part stays faintly visible
    for (x, y) in ((8, 8), (13, 13), (9, 14), (14, 7)):   # craters
        if (x, y) in lit:
            cv.put(x, y, EXTRA["moon2"])
    for (x, y) in ellipse(11, 11, 10.2, 10.2):
        if not cv.has(x, y):
            cv.put(x, y, EXTRA["moon"], 50)
    return cv


def terrain():
    """Everything inside the jar that does not move: soil strata, moss hills, rocks, tree, floating island, plants."""
    cv = Canvas(W, H)
    rng = random.Random(11)
    inside = jar_mask()
    j = JAR
    for (x, y) in inside:   # strata through the glass: charcoal then soil under the moss
        g = ground_y(x)
        if y >= 526:
            continue
        if y >= 514:
            cv.put(x, y, RAMPS["ink"][1] if (x * 7 + y * 3) % 5 else RAMPS["ink"][2])
        elif y > g + 4:   # soil gets darker with depth and toward the glass walls
            depth = (y - g) / max(1, 514 - g)
            wall = min(x - j["x0"], j["x1"] - x)
            n = rng.random() + (0.25 if depth < 0.3 else 0) - (0.3 if depth > 0.75 else 0) - (0.3 if wall < 6 else 0)
            cv.put(x, y, RAMPS["soil"][3] if n > 0.92 else (RAMPS["soil"][2] if n > 0.3 else (RAMPS["soil"][1] if n > 0.0 else RAMPS["soil"][0])))
    for y in range(526, 556):   # drainage pebbles
        for x in range(j["x0"], j["x1"] + 1):
            if (x, y) in inside:
                cv.put(x, y, RAMPS["pebble"][1])
    pebbles = [(rng.randrange(j["x0"] + 4, j["x1"] - 4), rng.randrange(528, 553), rng.uniform(2.2, 4.2)) for _ in range(90)]
    for (x, y, r) in sorted(pebbles, key=lambda p: p[1]):
        shade(cv, {p for p in ellipse(x, y, r, r * 0.8) if p in inside}, "pebble", ao=0.2)
    for _ in range(26):   # stones in the soil
        x, y = rng.randrange(j["x0"] + 6, j["x1"] - 6), rng.randrange(462, 508)
        if y > ground_y(x) + 6:
            shade(cv, ellipse(x, y, rng.uniform(1.5, 3), rng.uniform(1.2, 2.2)), "pebble", ao=0.2, inner=False)
    for (wx, wy) in ((70, 480), (236, 470)):   # two little earthworms
        for k in range(6):
            cv.put(wx + k, wy + (1 if k in (2, 3) else 0), RAMPS["blossom"][2 if k % 2 else 3])
        cv.put(wx + 5, wy - 1 + 1, RAMPS["blossom"][1])
    for _ in range(9):   # roots in the soil
        x = rng.randrange(j["x0"] + 10, j["x1"] - 10)
        y = ground_y(x) + 6
        for k in range(rng.randrange(10, 30)):
            x += rng.choice((-1, 0, 1))
            y += 1 if rng.random() < 0.7 else 0
            if (x, y) in inside and y < 512:
                cv.put(x, y, RAMPS["earth"][4] if k % 3 else RAMPS["earth"][3])
    for x in range(j["x0"], j["x1"] + 1):   # moss surface: lit top, darker lip over the soil
        g = ground_y(x)
        for d in range(0, 6):
            if (x, g + d) in inside:
                cv.put(x, g + d, RAMPS["moss"][4 if d == 0 else (3 if d == 1 else (2 if d < 4 else 1))])
        if rng.random() < 0.3 and (x, g - 1) in inside:
            cv.put(x, g - 1, RAMPS["moss"][3])
    for x in range(146, 201):   # pond in the dip
        for y in range(ground_y(x) - 1, 458):
            if (x - 173) ** 2 / 27 ** 2 + (y - 455) ** 2 / 6 ** 2 <= 1:
                cv.put(x, y, RAMPS["water"][2])
    rock = poly([(214, ground_y(214) + 2), (222, 410), (236, 390), (262, 384), (278, 398), (286, ground_y(286) + 2)])
    shade(cv, rock, "rock", ao=0.25)
    for x in range(234, 270):
        cv.put(x, 386, RAMPS["moss"][4]); cv.put(x, 387, RAMPS["moss"][3])
    path = tree_path()
    trunk = set()
    for i, (x, y) in enumerate(path):
        trunk |= ellipse(x, y, 10.5 - i * 0.065, 1.6)
    tx, ty = 86, ground_y(86)
    for side in (-1, 1):   # flared roots
        trunk |= capsule(tx, ty - 4, tx + side * 20, ty + 2, 4, 1)
        trunk |= capsule(tx, ty - 2, tx + side * 11, ty + 4, 3, 1)
    bx, by = path[70]
    trunk |= capsule(bx, by, bx + 64, by - 10, 4.2, 2.0)
    shade(cv, trunk, "bark", light=(-0.9, -0.45))
    for (x, y) in trunk:   # diagonal bark scales
        if (x + y) % 5 == 0 and cv.get(x, y) == RAMPS["bark"][2]:
            cv.put(x, y, RAMPS["bark"][1])
    top_x, top_y = path[-1]
    canopy(cv, rng, top_x + 6, top_y - 8, 58, 34, "blossom", lobes=11)
    canopy(cv, rng, bx + 58, by - 16, 20, 12, "blossom", lobes=5)
    for _ in range(14):   # fallen petals
        x = rng.randrange(40, 230)
        cv.put(x, ground_y(x), RAMPS["blossom"][3])
    floating_island(cv, rng, 202, 278, 232, 30)
    floating_island(cv, rng, 40, 116, ISLAND2_TOP, 24)
    leaf_cluster(cv, rng, 58, ISLAND2_TOP - 2, 7, 3, 9, "moss", leaf=(2, 2))
    for i, (dx, hh) in enumerate(((100, 5), (104, 7))):   # two little red caps on the new island
        for k in range(hh):
            cv.put(dx, ISLAND2_TOP - 1 - k, RECOLOR["cream"][3])
        shade(cv, {p for p in ellipse(dx + 0.5, ISLAND2_TOP - hh, 2.4, 1.8) if p[1] <= ISLAND2_TOP - hh}, "flyred", inner=False)
    rock_set = rock
    for (x, y) in rock_set:   # cracks and moss patches on the outcrop
        if (x * 7 + y * 11) % 53 == 0:
            for k in range(4):
                cv.put(x + k * 0.5, y + k, RAMPS["rock"][0])
        elif (x * 5 + y * 3) % 151 == 0 and y < 415:
            leaf_cluster(cv, rng, x, y, 3, 2, 3, "moss", leaf=(2, 1))
    for k in range(12):   # moss hanging from the ledge
        cv.put(236 + k * 2, 388 + (k % 3), RAMPS["moss"][2]); cv.put(236 + k * 2, 389 + (k % 3) + (k % 2), RAMPS["moss"][1])
    for x in range(j["x0"] + 2, j["x1"] - 1):   # grass blades all along the surface
        g = ground_y(x)
        if (x, g) not in inside or 147 < x < 199:
            continue
        hgt = (x * 7) % 5
        if hgt >= 2 and (x * 13) % 3:
            for k in range(hgt):
                cv.put(x + (1 if k == hgt - 1 and x % 2 else 0), g - 1 - k, RAMPS["grass"][3 if k == hgt - 1 else 2])
    for x in (32, 70, 112, 136, 222, 290):   # tiny flowers
        g = ground_y(x)
        col = ("blossom", "cloud", "sun")[x % 3]
        for k in range(4):
            cv.put(x, g - 1 - k, RAMPS["grass"][1])
        for dx, dy in ((0, -1), (-1, 0), (1, 0), (0, 1)):
            cv.put(x + dx, g - 6 + dy, RAMPS[col][3])
        cv.put(x, g - 6, RAMPS["sun"][3])
    for x in (48, 104):   # little red-capped mushrooms
        g = ground_y(x)
        for i, (dx, hh) in enumerate(((0, 4), (4, 6), (7, 3))):
            for k in range(hh):
                cv.put(x + dx, g - 1 - k, RECOLOR["cream"][3])
            shade(cv, {p for p in ellipse(x + dx + 0.5, g - hh, 2.4, 1.8) if p[1] <= g - hh}, "flyred", inner=False)
            cv.put(x + dx, g - hh - 1, RAMPS["cloud"][4])
    for x in (20 + 40, 270):   # ferns
        g = ground_y(x)
        for i in range(5):
            a = math.radians(-160 + i * 35)
            fx, fy = x, g
            for k in range(9):
                fx += math.cos(a); fy += math.sin(a) * 0.9
                a += 0.07 if a < -math.pi / 2 else -0.07
                cv.put(fx, fy, RAMPS["grass"][2])
                if k % 2 and k > 1:
                    cv.put(fx - math.sin(a), fy + math.cos(a) * 0.6, RAMPS["grass"][3])
                    cv.put(fx + math.sin(a), fy - math.cos(a) * 0.6, RAMPS["grass"][1])
    for x in (40, 128, 206, 276):   # moss cushions
        leaf_cluster(cv, rng, x, ground_y(x) - 2, 8, 3, 10, "moss", leaf=(2, 2))
    for x in (144, 202):   # reeds by the pond
        for i in range(5):
            hgt = rng.randrange(10, 18)
            for k in range(hgt):
                cv.put(x + i * 2 + k * 0.1, ground_y(x) - k, RAMPS["grass"][2 if k < hgt - 4 else 3])
            if i % 2:
                for k in range(4):
                    cv.put(x + i * 2 + hgt * 0.1, ground_y(x) - hgt + k, RAMPS["earth"][3])
    for x in (190, 278):   # little crystals
        shade(cv, poly([(x - 3, ground_y(x)), (x, ground_y(x) - 9), (x + 3, ground_y(x))]), "glow")
    return cv


ISLAND2_TOP = 190


def floating_island(cv, rng, x0, x1, top, depth):
    """A floating mini island (Owlboy nod): rounded rock with strata, a moss cap that drips, hanging roots."""
    w = x1 - x0
    pts = [(x0, top + 1), (x1, top + 1)]
    for k in range(1, 12):
        t = k / 12
        pts.append((x1 - t * w, top + 2 + math.sin(t * math.pi) ** 0.6 * depth + (6 if k == 6 else (3 if k % 3 == 0 else 0))))
    isl = poly(pts)
    shade(cv, isl, "rock", ao=0.4)
    for (x, y) in isl:   # strata lines
        if (y - top) % 7 == 3 and (x * 13 + y) % 9 != 0 and cv.get(x, y) in RAMPS["rock"][2:4]:
            cv.put(x, y, RAMPS["rock"][1])
    for x in range(x0, x1 + 1):
        cv.put(x, top - 1, RAMPS["moss"][4]); cv.put(x, top, RAMPS["moss"][3])
        cv.put(x, top + 1, RAMPS["moss"][2]); cv.put(x, top + 2, RAMPS["moss"][1])
        if x % 5 == 0:
            for k in range(1 + (x % 3)):
                cv.put(x, top + 3 + k, RAMPS["moss"][2])
    for rx in range(x0 + 12, x1 - 6, 16):
        for k in range(rng.randrange(10, 22)):
            cv.put(rx + math.sin(k * 0.5 + rx) * 1.4, top + depth * 0.7 + k, RAMPS["earth"][3 if k % 3 else 2])


def glass_front():
    """Glass edge, highlights, condensation, rim, cork lid and twine: drawn over everything inside the jar."""
    cv = Canvas(W, H)
    inside = jar_mask()
    j = JAR
    for (x, y) in inside:
        if any((x + dx, y + dy) not in inside for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
            cv.put(x, y, EXTRA["glass2"], 230)
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                if (x + dx, y + dy) not in inside:
                    cv.put(x + dx, y + dy, EXTRA["glass4"])
    for (x, y) in inside:   # faint tint so the glass reads as glass
        if not cv.has(x, y) and (x * 5 + y * 3) % 7 == 0 and (x + y) % 2 == 0:
            cv.put(x, y, EXTRA["glass1"], 28)
    for y in range(j["top"] + 26, j["bottom"] - 30):   # tall highlight streaks
        for k in range(3):
            cv.put(j["x0"] + 6 + k, y, EXTRA["glass0"], 150 - k * 40)
        if y % 3:
            cv.put(j["x0"] + 12, y, EXTRA["glass1"], 70)
        if j["top"] + 60 < y < j["bottom"] - 120:
            cv.put(j["x1"] - 8, y, EXTRA["glass1"], 90)
    for i in range(36):   # shoulder glint
        t = i / 36
        cv.put(j["neck0"] - 16 - t * 30, j["top"] + 6 + math.sin(t * math.pi / 2) * 22, EXTRA["glass0"], 200)
    for x in range(j["x0"] + 10, j["x1"] - 9):   # thick base
        cv.put(x, j["bottom"] - 2, EXTRA["glass1"], 160)
        if x % 9 < 4:
            cv.put(x, j["bottom"] - 4, EXTRA["glass0"], 110)
    rng = random.Random(5)
    for _ in range(40):   # condensation
        x, y = rng.randrange(j["neck0"] - 20, j["neck1"] + 30), rng.randrange(j["top"] + 4, j["top"] + 70)
        if (x, y) in inside:
            cv.put(x, y, EXTRA["glass0"], 160); cv.put(x, y + 1, EXTRA["glass2"], 120)
    for x in range(j["neck0"] - 4, j["neck1"] + 5):   # rim of the opening
        for y in range(j["top"] - 22, j["top"] - 16):
            cv.put(x, y, EXTRA["glass1"] if y < j["top"] - 19 else EXTRA["glass2"], 220)
        cv.put(x, j["top"] - 23, EXTRA["glass4"])
    lx0, lx1, ly0, ly1 = j["neck0"] + 4, j["neck1"] - 4, j["lid_top"], j["top"] - 22
    lid = poly([(lx0 + 3, ly0), (lx1 - 3, ly0), (lx1, ly0 + 4), (lx1 - 1, ly1), (lx0 + 1, ly1), (lx0, ly0 + 4)])
    shade(cv, lid, "cork", light=(-0.7, -0.7), ao=0.2)
    lrng = random.Random(9)
    lid_list = sorted(lid)
    for _ in range(60):
        x, y = lrng.choice(lid_list)
        cv.put(x, y, RAMPS["cork"][lrng.choice((1, 3))])
    for x in range(j["neck0"] + 2, j["neck1"] - 1):   # twine
        cv.put(x, j["top"] - 12, EXTRA["twine2"] if x % 4 == 0 else EXTRA["twine"]); cv.put(x, j["top"] - 11, EXTRA["twine2"])
    for k in range(10):
        cv.put(j["neck1"] - 2 + k * 0.4, j["top"] - 11 + k, EXTRA["twine"])
        cv.put(j["neck1"] + 1 + k * 0.5, j["top"] - 11 + k, EXTRA["twine2"])
    return cv


def light(phase):
    """Sunbeams by day, a warm low glow at dusk; empty at night (the app tints)."""
    cv = Canvas(W, H)
    if phase == "night":
        return cv
    for y in range(470):
        for x in range(W):
            if (x + y * 0.6) % 90 < 18 and (x + y) % 2 == 0:
                cv.put(x, y, RAMPS["sun"][4] if phase == "day" else EXTRA["dusk4"], 26 if phase == "day" else 22)
    return cv


def animated():
    """Moving bits in the scene, each with where it goes."""
    out = []
    frames = []
    for f in range(3):   # pond shimmer
        cv = Canvas(56, 8)
        for x in range(56):
            for y in range(8):
                if (x - 27) ** 2 / 27 ** 2 + (y - 4) ** 2 / 4 ** 2 <= 1:
                    k = 3 if y < 3 else 2
                    if (x + f * 4) % 11 in (0, 1):
                        k = 4 if y < 4 else 3
                    cv.put(x, y, RAMPS["water"][k])
        frames.append(cv)
    out.append(("pond", frames, 4, [0, 0], (146, 451)))
    for i, x in enumerate((60, 118, 226, 270)):   # swaying grass tufts
        frames = []
        for f in range(3):
            cv = Canvas(16, 14)
            rng = random.Random(40 + i)
            for b in range(7):
                bx = 2 + b * 2 + rng.random()
                hgt = rng.randrange(6, 13)
                bend = (0, 1, 0)[f] * (1 if b % 2 else -1)
                for k in range(hgt):
                    cv.put(bx + (bend if k > hgt * 0.6 else 0) + (k * 0.08 if b % 3 else 0), 13 - k, RAMPS["grass"][2 if k < hgt - 3 else 4])
            frames.append(cv)
        out.append((f"tuft_{i + 1}", frames, 3, [8, 13], (x, ground_y(x) + 1)))
    for i, (x, y) in enumerate(((158, ground_y(158)), (262, 232), (282, ground_y(282)))):   # glowing caps
        frames = []
        for f in range(2):
            cv = Canvas(18, 16)
            for (dx, hgt, r) in ((4, 9, 3), (10, 12, 4), (14, 7, 2)):
                for k in range(hgt - 3):
                    cv.put(dx, 15 - k, RAMPS["glow"][4]); cv.put(dx + 1, 15 - k, RAMPS["glow"][3])
                shade(cv, {p for p in ellipse(dx + 0.5, 16 - hgt, r, r * 0.7) if p[1] <= 16 - hgt}, "glow", inner=False)
                if f == 1:
                    for a in range(0, 360, 45):
                        cv.put(dx + 0.5 + math.cos(math.radians(a)) * (r + 2), 16 - hgt + math.sin(math.radians(a)) * (r + 1), RAMPS["glow"][3], 120)
            frames.append(cv)
        out.append((f"glowcap_{i + 1}", frames, 2, [9, 15], (x, y + 1)))
    for i, x in enumerate((112, 168, 206)):   # vines hanging under the lid
        frames = []
        for f in range(2):
            cv = Canvas(12, 70)
            ln = 40 + i * 10
            for k in range(ln):
                xx = 6 + math.sin(k * 0.18 + i) * 2 + f * k / ln * 2
                cv.put(xx, k, RAMPS["grass"][1])
                if k % 4 == 1:
                    shade(cv, ellipse(xx + (2 if (k // 4) % 2 else -2), k, 1.8, 1.2), "grass", rim=False, inner=False)
            frames.append(cv)
        out.append((f"vine_{i + 1}", frames, 1, [6, 0], (x, JAR["top"] - 8)))
    return out


# ---------------------------------------------------------------- output

def add(name, frames, fps=0, anchor=None, priority="P2", head=None):
    w, h = frames[0].w, frames[0].h
    strip = Image.new("RGBA", (w * len(frames), h), (0, 0, 0, 0))
    for i, f in enumerate(frames):
        strip.paste(f.image(), (i * w, 0))
    OUT.mkdir(parents=True, exist_ok=True)
    strip.save(OUT / f"{name}.png")
    e = {"name": name, "file": f"{name}.png", "w": w, "h": h, "anchor": anchor or [w // 2, h - 1], "frames": len(frames),
         "fps": fps, "priority": priority}
    if head:
        e["head"] = head
    ATLAS.append(e)


def main():
    for old in OUT.glob("*.png"):
        old.unlink()
    presets = json.loads((ROOT / "engine/src/main/assets/organisms/index.json").read_text())["presets"]
    for p in presets:
        KINDS[p["id"]] = json.loads((ROOT / "engine/src/main/assets" / p["file"]).read_text()).get("kingdom", "plant")
    for p in presets:
        for s in range(1, STAGES + 1):
            frames, heads = zip(*[creature(p["id"], s, f, False) for f in range(4)])
            add(f"creature_{p['id']}_s{s}", list(frames), 6, priority="P1", head=list(heads[0]))
            sl, _ = zip(*[creature(p["id"], s, f, True) for f in range(2)])
            add(f"creature_{p['id']}_s{s}_sleep", list(sl), 2, priority="P2", head=list(heads[0]))
    for v in range(3):
        add(f"snoop_{v + 1}", [snoop(v, f) for f in range(4)], 8, priority="P1")
    for a in ACCESSORIES:
        add(f"hat_{a}", [accessory(a)], anchor=[7, 9], priority="P2")
    add("sun", [sun(f, False) for f in range(2)], 1, anchor=[14, 14], priority="P1")
    add("sun_dusk", [sun(f, True) for f in range(2)], 1, anchor=[14, 14], priority="P1")
    add("moon", [moon_phase(k) for k in range(8)], 0, anchor=[11, 11], priority="P1")
    add("poof", [poof(f) for f in range(5)], 12, anchor=[10, 10], priority="P1")
    add("sparkle", [sparkle(f) for f in range(3)], 10, anchor=[4, 4], priority="P2")
    add("heart", [heart()], anchor=[4, 8], priority="P2")
    for kind in ("happy", "grumpy", "sick"):
        add(f"mood_{kind}", [mood(kind, f) for f in range(2)], 3, anchor=[2, 14], priority="P2")
    add("zzz", [zzz(f) for f in range(3)], 2, anchor=[0, 11], priority="P2")
    add("firefly", [firefly(f) for f in range(2)], 4, anchor=[3, 3], priority="P2")
    add("mote", [mote()], anchor=[1, 1], priority="P3")
    for ph in ("day", "dusk", "night"):
        rm, sk = room(ph)
        add(f"room_{ph}", [rm], anchor=[0, 0], priority="P1")
        add(f"sky_{ph}", [sk], anchor=[0, 0], priority="P1")
        add(f"light_{ph}", [light(ph)], anchor=[0, 0], priority="P3")
    add("terrain", [terrain()], anchor=[0, 0], priority="P1")
    add("glass_front", [glass_front()], anchor=[0, 0], priority="P1")
    decor = []
    for name, frames, fps, anchor, at in animated():
        add(name, frames, fps, anchor=anchor, priority="P2")
        decor.append({"sprite": name, "x": at[0], "y": at[1]})
    path = tree_path()
    bx, by = path[70]
    slots = [   # where creatures stand (feet); the order is the order new apps fill them
        (124, ground_y(124)), (226, ground_y(226)), (244, 386), (58, ground_y(58)), (int(bx + 30), int(by - 6)),
        (232, 232), (70, ISLAND2_TOP), (100, ground_y(100) + 1), (262, ground_y(262)), (256, 232), (int(bx + 52), int(by - 9)),
        (92, ISLAND2_TOP), (36, ground_y(36)), (264, 386), (140, ground_y(140)), (206, ground_y(206)),
    ]
    surfaces = [   # walkable lines: a dropped creature lands on the nearest point
        [[x, ground_y(x)] for x in range(JAR["x0"] + 10, JAR["x1"] - 9, 2) if not (146 <= x <= 200) and not (216 <= x <= 284)],
        [[x, 386] for x in range(234, 272, 2)],
        [[int(bx + k), int(by - 4 - k * 10 / 64)] for k in range(8, 62, 2)],
        [[x, 232] for x in range(206, 276, 2)],
        [[x, ISLAND2_TOP] for x in range(44, 114, 2)],
    ]
    layout = {
        "world": [W, H], "jar": JAR, "mouth": [(JAR["neck0"] + JAR["neck1"]) // 2, JAR["lid_top"]],
        "slots": [{"x": sx, "y": sy} for sx, sy in slots],
        "surfaces": surfaces,
        "decor": decor,
        "fireflies": [JAR["x0"] + 10, JAR["top"] + 20, JAR["x1"] - 10, 440],
        "window": {"x0": 18, "x1": 302, "y0": 18, "y1": 470, "arch_cy": 158, "arch_r": 142},   # the sky pane (weather is clipped to it)
        "glows": [{"x": d["x"], "y": d["y"]} for d in decor if d["sprite"].startswith("glowcap")],
    }
    (OUT / "terrarium_layout.json").write_text(json.dumps(layout, indent=1))
    (OUT / "atlas.json").write_text(json.dumps({"version": 2, "key_ramp": RAMPS["KEY"], "recolor": RECOLOR, "hats": ACCESSORIES,
                                                "infect": RAMPS["infect"], "sprites": ATLAS}, indent=1))
    contact_sheet()
    sprites_md()
    print(f"{len(ATLAS)} sprites, {len(PAL)} palette colors")


def sprites_md():
    rows = ["# Sprites", "",
            "Generated by `python3 scripts/gen_sprites.py` into `app/src/main/assets/sprites/` (atlas: `atlas.json`,",
            "scene placement: `terrarium_layout.json`, preview: `docs/terrarium_preview.png`). Replace any PNG with your own",
            "art: same file name, same frame size (w x h), frames side by side left to right. Anchor = the pixel placed on the",
            "ground (x, y inside one frame); `head` = where hats and Snoops attach. Creature PNGs paint their main color in the",
            "KEY ramp (atlas `key_ramp`); the app swaps it per app for one of `recolor`. Missing files get a drawn fallback.",
            "Priority: P1 = seen first / most important.", "",
            "| Sprite | File | Frame w x h | Frames | fps | Anchor | Priority |", "|---|---|---|---|---|---|---|"]
    for e in ATLAS:
        rows.append(f"| {e['name']} | {e['file']} | {e['w']} x {e['h']} | {e['frames']} | {e['fps']} | {e['anchor'][0]},{e['anchor'][1]} | {e['priority']} |")
    (ROOT / "docs/SPRITES.md").write_text("\n".join(rows) + "\n")


def contact_sheet():
    """Creatures (in sample colors) and small sprites at 3x, plus the composed scene preview at 2x."""
    key = [rgb(c) for c in RAMPS["KEY"]]
    looks = list(RECOLOR.values())
    small = [e for e in ATLAS if not e["name"].startswith(("room_", "light_", "terrain", "glass_"))]
    scale, pad, width = 3, 6, 1200
    x = y = pad
    row = 0
    placed = []
    for i, e in enumerate(small):
        im = Image.open(OUT / e["file"]).convert("RGBA")
        swap = dict(zip(key, [rgb(c) for c in looks[i // 2 % len(looks)]]))
        px = im.load()
        for a in range(im.width):
            for b in range(im.height):
                r, g, bl, al = px[a, b]
                if al and (r, g, bl) in swap:
                    px[a, b] = swap[(r, g, bl)] + (al,)
        w, h = im.width * scale, im.height * scale
        if x + w > width:
            x, y, row = pad, y + row + pad, 0
        placed.append((x, y, im))
        x += w + pad
        row = max(row, h)
    sheet = Image.new("RGBA", (width, y + row + pad), rgb(EXTRA["night2"]) + (255,))
    for (x, y, im) in placed:
        sheet.alpha_composite(im.resize((im.width * scale, im.height * scale), Image.NEAREST), (x, y))
    sheet.save(ROOT / "docs/sprites_contact.png")
    scene = Image.open(OUT / "sky_day.png").convert("RGBA").crop((0, 0, W, H))
    for n in ("room_day", "terrain", "glass_front", "light_day"):
        scene.alpha_composite(Image.open(OUT / f"{n}.png").convert("RGBA").crop((0, 0, W, H)))
    scene.resize((W * 2, H * 2), Image.NEAREST).save(ROOT / "docs/terrarium_preview.png")


if __name__ == "__main__":
    main()
