#!/usr/bin/env python3
"""Original pixel art for the floating-island terrarium. Seeded, palette-locked (PAL only), nearest-neighbor only.

Writes app/src/main/assets/sprites/<name>.png (horizontal frame strips), sprites/atlas.json
({name, file, w, h, anchor:[x,y], frames, fps}) and docs/sprites_contact.png (a contact sheet).
Hand-drawn PNGs can replace any file later: keep the file name, frame size and frame count.
Light comes from the top-left; shadows lean purple/blue, highlights lean yellow-green; outlines are dark tints, never black.
Usage: python3 scripts/gen_sprites.py
"""
import json
import math
import random
import zlib
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "app/src/main/assets/sprites"

PAL = {
    "sky0": "#5FA8F0", "sky1": "#8CC4F7", "sky2": "#A9D8FF",
    "cl0": "#9CC4E8", "cl1": "#C9E2F7", "cl2": "#F4FAFF",
    "g0": "#2F7A2E", "g1": "#46A83A", "g2": "#6DCB45", "g3": "#A8E85A",
    "e0": "#3A2A22", "e1": "#5B3A2A", "e2": "#7F5232", "e3": "#A8723F", "e4": "#C99A5B",
    "r0": "#2B2842", "r1": "#433B63", "r2": "#5F5385", "r3": "#8A74A6", "r4": "#B59BC9",
    "w0": "#5A3B28", "w1": "#8A5A36", "w2": "#B98450",
    "b0": "#E7679A", "b1": "#F59BB8", "b2": "#FFD3E1", "b3": "#B9E6E8", "b4": "#DFF6F4",
    "v0": "#1E4A2B", "v1": "#2F7A3A",
    "pa": "#F1E4C3", "ink": "#2B2233",
}
RGB = {k: tuple(int(v[i:i + 2], 16) for i in (1, 3, 5)) for k, v in PAL.items()}

# Ramps: dark (outline tint) -> shadow -> mid -> light -> highlight
RAMP = {
    "grass": ["v0", "g0", "g1", "g2", "g3"],
    "earth": ["e0", "e1", "e2", "e3", "e4"],
    "rock": ["r0", "r1", "r2", "r3", "r4"],
    "wood": ["e0", "w0", "w1", "w2", "e4"],
    "pink": ["r1", "b0", "b1", "b2", "pa"],
    "teal": ["r1", "cl0", "b3", "b4", "cl2"],
    "cloud": ["r2", "cl0", "cl1", "cl2", "cl2"],
    "sky": ["r1", "sky0", "sky1", "sky2", "cl2"],
    "cream": ["e1", "e4", "pa", "cl2", "cl2"],
    "amber": ["e0", "e2", "e3", "e4", "pa"],
    "violet": ["r0", "r2", "r3", "r4", "b2"],
    "stem": ["e1", "e3", "e4", "pa", "pa"],
}

ATLAS = []


class Grid:
    """A sparse pixel grid of palette keys; later writes win."""

    def __init__(self, w, h):
        self.w, self.h, self.px = w, h, {}

    def put(self, x, y, k):
        x, y = int(round(x)), int(round(y))
        if 0 <= x < self.w and 0 <= y < self.h and k:
            self.px[(x, y)] = k

    def get(self, x, y):
        return self.px.get((x, y))

    def has(self, x, y):
        return (x, y) in self.px

    def rect(self, x0, y0, w, h, k):
        for y in range(y0, y0 + h):
            for x in range(x0, x0 + w):
                self.put(x, y, k)

    def copy(self):
        g = Grid(self.w, self.h)
        g.px = dict(self.px)
        return g


# ---------------------------------------------------------------- shading helpers

def blob(g, cx, cy, rx, ry, ramp, rng=None, rough=0.0, outline=True, light=(-1, -1)):
    """An ellipse shaded from the top-left: highlight crescent, mid, shadow at bottom-right. Returns its pixel set."""
    r = RAMP[ramp]
    pts = set()
    for y in range(int(cy - ry - 1), int(cy + ry + 2)):
        for x in range(int(cx - rx - 1), int(cx + rx + 2)):
            d = ((x + 0.5 - cx) / max(rx, 0.5)) ** 2 + ((y + 0.5 - cy) / max(ry, 0.5)) ** 2
            if d <= 1.0 - (rng.random() * rough if rng and d > 0.6 else 0):
                pts.add((x, y))
    lx, ly = light
    for (x, y) in pts:
        nx, ny = (x + 0.5 - cx) / max(rx, 0.5), (y + 0.5 - cy) / max(ry, 0.5)
        lit = (nx * lx + ny * ly) / math.sqrt(2)
        dist = math.hypot(nx, ny)
        if lit > 0.45 and dist > 0.45:
            k = r[4] if lit > 0.75 and dist > 0.7 else r[3]
        elif lit < -0.35:
            k = r[1]
        else:
            k = r[2]
        g.put(x, y, k)
    if outline:
        outline_set(g, pts, r[0])
    return pts


def outline_set(g, pts, k, skip_lit=False):
    """Dark tinted outline around a pixel set (only where empty)."""
    for (x, y) in list(pts):
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            q = (x + dx, y + dy)
            if q not in pts and not g.has(*q):
                if skip_lit and dx <= 0 and dy <= 0:
                    continue
                g.put(q[0], q[1], k)


def silhouette_outline(g, k_dark, k_lit=None):
    """Outline everything drawn so far; edges facing the light get the softer color (selective outline)."""
    pts = set(g.px)
    for (x, y) in list(pts):
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            q = (x + dx, y + dy)
            if q not in pts and q not in g.px:
                g.put(q[0], q[1], k_lit if (k_lit and (dx < 0 or dy < 0)) else k_dark)


def dither(g, pts, a, b, phase=0):
    for (x, y) in pts:
        if (x + y + phase) % 2 == 0:
            g.put(x, y, b)
        else:
            g.put(x, y, a)


def line(g, x0, y0, x1, y1, k):
    n = int(max(abs(x1 - x0), abs(y1 - y0))) + 1
    for i in range(n):
        t = i / max(n - 1, 1)
        g.put(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, k)


# ---------------------------------------------------------------- plants

def foliage(g, rng, cx, cy, spread_x, spread_y, n, ramp="grass", rmin=2.5, rmax=4.5, chips=6):
    """Overlapping 5-9 px clumps, drawn back to front, each lit from the top-left; loose leaf chips on the edge."""
    clumps = []
    for _ in range(n):
        a = rng.random() * math.tau
        d = rng.random() ** 0.7
        clumps.append((cx + math.cos(a) * spread_x * d, cy + math.sin(a) * spread_y * d, rng.uniform(rmin, rmax)))
    clumps.sort(key=lambda c: c[1])
    allpts = set()
    r = RAMP[ramp]
    for (x, y, rad) in clumps:
        allpts |= blob(g, x, y, rad, rad * rng.uniform(0.8, 1.0), ramp, rng, rough=0.35, outline=False)
    # separation: shadow line under each clump edge for overlap reading
    for (x, y) in list(allpts):
        if (x, y + 1) in allpts and g.get(x, y) in (r[3], r[4]) and g.get(x, y + 1) == r[2] and rng.random() < 0.25:
            g.put(x, y + 1, r[1])
    for _ in range(chips):
        x, y = rng.choice(sorted(allpts))
        dx, dy = rng.choice([(-2, -1), (2, -1), (-2, 1), (2, 1), (0, -2), (1, 2)])
        if (x + dx, y + dy) not in allpts:
            g.put(x + dx, y + dy, r[3] if dy < 0 else r[2])
            if rng.random() < 0.5:
                g.put(x + dx + (1 if dx > 0 else -1), y + dy, r[2])
    return allpts


def trunk(g, rng, x0, y0, height, width, curl, ramp="wood", lean=0.0):
    """A twisting, tapering trunk from (x0, y0) upward with flared roots and diagonal bark scales. Returns top point."""
    r = RAMP[ramp]
    pts = set()
    path = []
    x, y = float(x0), float(y0)
    ang = -math.pi / 2 + lean
    for i in range(height):
        t = i / max(height - 1, 1)
        ang += math.sin(i * 0.35 + rng.random()) * curl * 0.12
        x += math.cos(ang)
        y += math.sin(ang)
        wdt = width * (1 - 0.65 * t) + 0.6
        path.append((x, y, wdt))
        for dx in range(-int(wdt), int(wdt) + 1):
            if abs(dx) <= wdt:
                pts.add((int(round(x + dx)), int(round(y))))
    # flared roots
    for side in (-1, 1):
        rx, ry = x0, y0
        for i in range(int(width) + 3):
            rx += side * (1 if i % 2 == 0 else 0.5)
            ry += 0.35
            for k in range(max(1, int(width / 2) - i // 2)):
                pts.add((int(round(rx + side * k * 0.5)), int(round(ry - k))))
    xs = {}
    for (px, py) in pts:
        xs.setdefault(py, []).append(px)
    for (px, py) in pts:
        row = xs[py]
        lo, hi = min(row), max(row)
        f = (px - lo) / max(hi - lo, 1)
        k = r[3] if f < 0.3 else (r[1] if f > 0.7 else r[2])
        if (px + py) % 4 == 0 and 0.2 < f < 0.85:   # diagonal leaf-scale bark
            k = r[1]
        g.put(px, py, k)
    outline_set(g, pts, r[0])
    return path


def blossom_tree(rng, variant):
    g = Grid(64, 72)
    path = trunk(g, rng, 32, 70, 34, 4.5, 1.2 + variant * 0.5, lean=(-0.15 if variant else 0.12))
    tx, ty, _ = path[-1]
    # two twisting branches
    for side in (-1, 1):
        bx, by, _ = path[int(len(path) * 0.65)]
        for i in range(12):
            bx += side * 1.0
            by -= 0.6 + 0.2 * math.sin(i)
            for d in (0, 1):
                g.put(bx, by + d, "w1" if d else "w2")
            g.put(bx, by + 2, "e0")
    foliage(g, rng, tx, ty - 2, 20, 11, 14, ramp="pink", rmin=3, rmax=5, chips=12)
    foliage(g, rng, tx - 14, ty + 8, 8, 5, 5, ramp="pink", chips=4)
    foliage(g, rng, tx + 14, ty + 6, 8, 5, 5, ramp="pink", chips=4)
    silhouette_outline(g, "r1", "b0")
    # falling petals
    for _ in range(5):
        g.put(rng.randrange(6, 58), rng.randrange(40, 66), rng.choice(["b1", "b2"]))
    return g


def bonsai(rng, variant):
    g = Grid(28, 28)
    # pot
    for y in range(22, 27):
        for x in range(7, 21):
            if y == 26 and x in (7, 20):
                continue
            g.put(x, y, "e1" if x > 16 else ("e3" if x < 10 else "e2"))
    g.rect(6, 21, 16, 1, "e3")
    path = trunk(g, rng, 14, 21, 12, 2.2, 2.5 + variant, lean=0.35 if variant else -0.35)
    tx, ty, _ = path[-1]
    foliage(g, rng, tx, ty - 1, 9, 4, 5, chips=5)
    foliage(g, rng, 14 - (5 if variant else -5), ty + 4, 5, 2, 3, chips=3)
    silhouette_outline(g, "v0", "g0")
    return g


def fern(rng, variant):
    g = Grid(22, 18)
    for i in range(5 + variant):
        a = math.radians(-160 + i * (140 / (4 + variant)))
        length = rng.uniform(7, 10)
        x, y = 11.0, 17.0
        for s in range(int(length)):
            x += math.cos(a)
            y += math.sin(a) * 0.9
            a += 0.06 * (1 if a > -math.pi / 2 else -1)
            g.put(x, y, "g1")
            if s % 2 == 0 and s > 1:   # leaflets: diagonal scale pattern
                g.put(x - math.sin(a), y + math.cos(a) * 0.6, "g2")
                g.put(x + math.sin(a), y - math.cos(a) * 0.6, "g0")
        g.put(x, y, "g3")
    silhouette_outline(g, "v0", "v1")
    return g


def glow_mushrooms(rng, variant, frame):
    g = Grid(18, 16)
    caps = [(5, 10, 3), (11, 7, 4), (14, 12, 2)] if variant == 0 else [(4, 9, 3), (9, 11, 2), (13, 6, 4)]
    for (x, y, r) in caps:
        for yy in range(y, 15):
            g.put(x, yy, "cl2")
            g.put(x + 1, yy, "cl1")
    for (x, y, r) in caps:
        blob(g, x + 0.5, y, r + 0.5, r * 0.65, "teal", outline=False)
        if frame == 1:
            g.put(x - 1, y - 1, "cl2")
            g.put(x + 1, y - 1, "b4")
    silhouette_outline(g, "r1", "r2")
    if frame == 1:   # glow halo, sparse
        for (x, y, r) in caps:
            for a in range(0, 360, 60):
                g.put(x + 0.5 + math.cos(math.radians(a)) * (r + 2.5), y + math.sin(math.radians(a)) * (r + 1.5), "b3")
    return g


def hanging_vine(rng, variant, frame):
    g = Grid(12, 30)
    x = 6.0
    sway = (0, 1)[frame] * (1 if variant else -1)
    for y in range(0, 28):
        t = y / 28
        x = 6 + math.sin(y * 0.35 + variant) * 1.5 + sway * t * 2
        g.put(x, y, "v1")
        if y % 3 == 1:
            side = 1 if (y // 3) % 2 else -1
            g.put(x + side, y, "g1")
            g.put(x + side * 2, y - 1, "g2")
            g.put(x + side, y + 1, "g0")
    g.put(x, 28, "b1" if variant else "g3")
    silhouette_outline(g, "v0")
    return g


def lily_pads(rng, variant):
    g = Grid(22, 8)
    pads = [(6, 4, 4), (15, 5, 3)] if variant == 0 else [(5, 5, 3), (12, 4, 4), (18, 5, 2)]
    for (x, y, r) in pads:
        pts = blob(g, x, y, r + 1, r * 0.55, "grass", outline=False)
        g.put(x + 1, y, None)
        for k in range(r + 1):   # notch
            g.px.pop((x + k, y), None)
    if variant == 0:
        g.put(7, 2, "b1"); g.put(6, 2, "b2"); g.put(8, 2, "b0")
    silhouette_outline(g, "v0", "v1")
    return g


def reeds(rng, variant):
    g = Grid(12, 22)
    for i in range(4 + variant):
        x0 = 3 + i * 2 + rng.random()
        h = rng.randint(12, 20)
        lean = rng.uniform(-0.15, 0.15)
        for y in range(h):
            g.put(x0 + lean * y, 21 - y, "g1" if y < h - 4 else "g2")
        if i % 2 == 0:   # cattail head
            for y in range(h - 5, h - 1):
                g.put(x0 + lean * y, 21 - y, "e2")
                g.put(x0 + lean * y + 1, 21 - y, "e1")
    silhouette_outline(g, "v0")
    return g


def moss(rng, variant):
    g = Grid(18, 7)
    foliage(g, rng, 9, 4, 7, 1.5, 6, rmin=1.8, rmax=2.6, chips=3)
    if variant:
        g.put(5, 2, "b2"); g.put(12, 1, "pa")
    silhouette_outline(g, "v0", "v1")
    return g


def flowers(rng, variant):
    g = Grid(14, 12)
    petal = ("b1", "b0") if variant == 0 else ("cl2", "sky1")
    for i, (x, h) in enumerate([(3, 7), (7, 10), (11, 6)]):
        for y in range(h):
            g.put(x, 11 - y, "g1")
        g.put(x + (1 if i % 2 else -1), 11 - h // 2, "g2")
        cy = 11 - h
        for dx, dy in ((0, -1), (-1, 0), (1, 0), (0, 1)):
            g.put(x + dx, cy + dy, petal[0])
        g.put(x + 1, cy + 1, petal[1])
        g.put(x, cy, "pa")
    silhouette_outline(g, "v0")
    return g


def grass_tuft(rng, variant, frame):
    g = Grid(12, 8)
    for i in range(5 + variant):
        x = 1 + i * 2 + rng.random()
        h = rng.randint(4, 7)
        bend = (1 if frame else 0) * (1 if i % 2 else 0)
        for y in range(h):
            g.put(x + (bend if y > h // 2 else 0), 7 - y, "g2" if y > h - 3 else "g1")
    silhouette_outline(g, "v0", "v1")
    return g


# ---------------------------------------------------------------- creatures

KINGDOM_LOOK = {   # body ramp per preset id (all from PAL)
    "preset01": "grass", "preset02": "violet", "preset03": "pink", "preset04": "grass", "preset05": "grass",
    "preset06": "grass", "preset07": "teal", "preset08": "teal", "preset09": "grass", "preset10": "grass",
    "preset11": "pink", "preset12": "wood", "preset13": "grass", "preset14": "pink", "preset15": "amber",
    "preset16": "grass", "preset17": "grass", "preset18": "violet", "preset19": "amber", "preset20": "violet",
    "preset21": "grass", "preset22": "pink", "preset23": "wood", "preset24": "grass", "preset25": "cream",
}
STAGE_SIZE = [(32, 32), (32, 40), (40, 48)]


def face(g, cx, cy, frame, sleep, size):
    """Two eyes with a glint, blush, small mouth. Blinks on frame 3, closed lines while sleeping."""
    sp = 3 if size < 2 else 4
    for side in (-1, 1):
        ex = int(cx + side * sp - (1 if side < 0 else 0))
        if sleep or frame == 3:
            g.put(ex, cy + 1, "ink"); g.put(ex + 1, cy + 1, "ink")
        else:
            g.put(ex, cy, "ink"); g.put(ex, cy + 1, "ink")
            g.put(ex + 1, cy, "ink"); g.put(ex + 1, cy + 1, "ink")
            g.put(ex, cy, "cl2")
        g.put(ex + side * 1 + (0 if side < 0 else 1), cy + 3, "b1")
    if sleep:
        g.put(cx, cy + 3, "ink")
    else:
        g.put(cx - 1, cy + 3, "ink"); g.put(cx, cy + 4, "ink"); g.put(cx + 1, cy + 3, "ink")


def creature(pid, kingdom, shape, stage, frame, sleep):
    w, h = STAGE_SIZE[stage]
    g = Grid(w, h)
    rng = random.Random(zlib.crc32(f"{pid}{stage}".encode()))
    ramp = KINGDOM_LOOK.get(pid, "grass")
    branches = shape.get("branches", 4)
    curl = shape.get("curl", 0.4)
    bob = 0 if sleep else (0, -1, 0, 0)[frame]
    sway = 0 if sleep else (0, 1, 0, -1)[frame]
    squash = 1 if sleep else 0
    size = stage
    cx = w / 2
    base = h - 1
    bw = [6.5, 8.0, 10.0][stage]
    bh = [5.5, 7.0, 8.5][stage] - squash
    by = base - bh + bob
    body_ramp = ramp

    def top_parts():
        top = by - bh
        n = [2, 3, 4][stage] + branches // 3
        if kingdom in ("plant", "fern", "vine"):
            for i in range(n):
                a = -math.pi / 2 + (i - (n - 1) / 2) * (0.55 if kingdom != "fern" else 0.45)
                length = [5, 8, 11][stage] + (i % 2) * 2
                x, y = cx, top + 2
                for s in range(length):
                    x += math.cos(a) + sway * 0.08 * s / length
                    y += math.sin(a)
                    a += curl * 0.06 * (1 if i % 2 else -1)
                    g.put(x, y, "g1" if s < length - 2 else "g2")
                    if kingdom == "fern" and s % 2 and s > 1:
                        g.put(x + 1, y, "g2"); g.put(x - 1, y + 1, "g0")
                if kingdom == "plant":
                    blob(g, x, y, 2, 1.5, "grass", outline=False)
                elif kingdom == "vine":
                    g.put(x + 1, y - 1, "g2"); g.put(x + 2, y, "g1")
            if stage == 2 and kingdom in ("plant", "vine"):
                fl = "cream" if pid == "preset24" else ("sky" if pid == "preset04" else "pink")
                blob(g, cx + sway, top - [5, 8, 11][stage] + 1, 3, 3, fl)
                g.put(cx + sway, top - [5, 8, 11][stage] + 1, "e3" if pid == "preset24" else "pa")
        elif kingdom == "mushroom":
            cap_r = bw + 3
            cap_ry = [4, 5, 6][stage] + (2 if pid == "preset02" else 0)
            cap_ramp = {"preset02": "violet", "preset14": "pink", "preset25": "cream"}.get(pid, "pink")
            pts = blob(g, cx + sway * 0.5, by - bh + 1, cap_r, cap_ry, cap_ramp)
            for (x, y) in list(pts):   # flat underside
                if y > by - bh + 1:
                    g.px.pop((x, y), None)
            g.rect(int(cx - cap_r + 1), int(by - bh + 1), int(cap_r * 2) - 1, 1, "e4")
            if pid == "preset14":
                for (sx, sy) in ((-4, -3), (2, -4), (5, -1), (-1, -1)):
                    g.put(cx + sx, by - bh + sy, "pa")
            if pid == "preset25":
                for x in range(int(cx - cap_r + 2), int(cx + cap_r - 1), 2):
                    g.put(x, by - bh + 2, "e4")
        elif kingdom in ("coral", "anemone"):
            for i in range(n + 1):
                x = cx - bw + 2 + i * (2 * bw - 4) / max(n, 1)
                length = [4, 6, 9][stage] + (i % 3)
                for s in range(length):
                    wob = math.sin(s * 0.8 + i + frame * (1 if kingdom == "anemone" else 0)) * curl
                    g.put(x + wob + sway * s / length * (1 if kingdom == "anemone" else 0), by - bh + 2 - s, RAMP[ramp][3 if s > length - 2 else 2])
                if kingdom == "anemone":
                    g.put(x, by - bh + 1 - length, RAMP[ramp][4])
                else:
                    g.put(x - 1, by - bh + 2 - length // 2, RAMP[ramp][3])
        elif kingdom == "cactus":
            if stage >= 1:
                for side in (-1, 1):
                    ax = cx + side * (bw + 1)
                    for s in range([0, 5, 7][stage]):
                        g.put(ax, by - 1 - s, "g1"); g.put(ax + side, by - 1 - s, "g0")
            if stage == 2:
                blob(g, cx, by - bh - 1, 2.5, 2, "pink")
        elif kingdom == "crystal":
            for i in range(n):
                x = cx - bw + 3 + i * (2 * bw - 6) / max(n - 1, 1)
                hh = [4, 7, 10][stage] + (3 if i == n // 2 else 0)
                for s in range(hh):
                    wdt = max(0, 1 - s // max(hh - 2, 1))
                    for d in range(-wdt, wdt + 1):
                        g.put(x + d, by - bh + 2 - s, RAMP[ramp][3 if d < 0 else 2])
                g.put(x, by - bh + 2 - hh, RAMP[ramp][4])
        elif kingdom == "tree":
            ty = by - bh - [3, 6, 9][stage]
            for y in range(int(ty), int(by - bh + 2)):
                g.put(cx, y, "w1"); g.put(cx + 1, y, "w0")
            leaf = "grass"
            foliage(g, rng, cx + sway * 0.5, ty, [5, 8, 12][stage], [3, 4, 6][stage], [4, 7, 11][stage], ramp=leaf, chips=4)
            if pid == "preset23":   # willow strands
                for i in range([3, 5, 7][stage]):
                    x = cx - [5, 8, 12][stage] + i * 2 * [5, 8, 12][stage] / max([3, 5, 7][stage] - 1, 1)
                    for s in range([3, 6, 9][stage]):
                        g.put(x + sway * s * 0.15, ty + 2 + s, "g1" if s % 2 else "g2")
        elif kingdom == "jelly":
            pass
        elif kingdom == "slime":
            pass
        elif kingdom == "moss":
            if stage == 2:
                for x in (cx - 5, cx + 1, cx + 6):
                    g.put(x, by - bh - 1, "b1"); g.put(x, by - bh, "g3")

    if kingdom == "jelly":   # floating bell with tentacles
        float_y = base - 6 - [4, 6, 8][stage] + bob
        for i in range([3, 4, 5][stage]):
            x = cx - bw + 3 + i * (2 * bw - 6) / max([3, 4, 5][stage] - 1, 1)
            for s in range([4, 6, 8][stage]):
                g.put(x + math.sin(s * 0.7 + i + frame) * 1.2, float_y + 2 + s, RAMP[ramp][2 if s % 2 else 3])
        blob(g, cx, float_y, bw, bh, ramp)
        face(g, int(cx), int(float_y), frame, sleep, size)
        return g
    if kingdom == "moss":
        bw2, bh2 = bw + 3, bh - 2
        top_parts()
        pts = foliage(g, rng, cx, base - bh2 + bob, bw2, bh2 * 0.6, 6 + stage * 3, rmin=2, rmax=3.5, chips=4)
        silhouette_outline(g, "v0", "v1")
        face(g, int(cx), int(base - bh2 + bob), frame, sleep, size)
        return g

    if kingdom not in ("mushroom",):
        top_parts()
    if kingdom == "mushroom":
        stem_w = bw - 2
        pts = blob(g, cx, by + 1, stem_w, bh, "stem")
        top_parts()
    elif kingdom == "crystal":
        pts = set()
        for y in range(int(by - bh), base + 1):
            t = (y - (by - bh)) / max(bh * 2, 1)
            half = int(bw * (0.6 + 0.4 * min(1, t * 2)))
            for x in range(int(cx - half), int(cx + half) + 1):
                pts.add((x, y))
                g.put(x, y, RAMP[ramp][3] if x < cx - half / 3 else (RAMP[ramp][1] if x > cx + half / 2 else RAMP[ramp][2]))
        outline_set(g, pts, RAMP[ramp][0])
    elif kingdom == "slime":
        pts = blob(g, cx, base - bh + 1 + bob, bw + 1 + (1 if frame == 1 else 0), bh, ramp)
        g.put(cx - bw / 2, base - bh * 1.5 + bob, "cl2")
        g.put(cx - bw / 2 + 1, base - bh * 1.5 + bob, "cl2")
    else:
        pts = blob(g, cx, by + 1, bw, bh, body_ramp)
        if kingdom == "cactus":
            for (x, y) in list(pts):
                if (x * 3 + y * 5) % 11 == 0:
                    g.put(x, y, "pa")
    fy = int(by + 1 - (0 if kingdom != "mushroom" else 1))
    if kingdom == "slime":
        fy = int(base - bh + 2 + bob)
    face(g, int(cx), fy, frame, sleep, size)
    return g


# ---------------------------------------------------------------- snoops, fx, sky

SNOOP_BUG = [
    "....k......k....",
    ".....k....k.....",
    "......kkkk......",
    "....kVVVVVVk....",
    "...kVwwVVwwVk...",
    "..kVVwEVVwEVVk..",
    "..kVVwwVVwwVVk..",
    "..kVVVVVVVVVVk..",
    "..kvVVVEEVVVVk..",
    "...kvVVVVVVvk...",
    "....kkvvvvkk....",
    "....k.k..k.k....",
]


def snoop(variant, frame):
    g = Grid(16, 16)
    bob = (0, -1, 0, 1)[frame]
    if variant == 0:   # round mite with antennae
        keys = {"k": "r0", "V": "r3", "v": "r2", "w": "cl2", "E": "ink"}
        for y, row in enumerate(SNOOP_BUG):
            for x, c in enumerate(row):
                if c != ".":
                    g.put(x, y + 2 + bob, keys[c])
        g.put(5, 6 + bob, "r4"); g.put(6, 5 + bob, "r4")
        if frame == 2:
            g.put(4, 1 + bob, "g3"); g.put(11, 1 + bob, "g3")
    else:   # moth with flapping wings
        flap = (0, 1, 2, 1)[frame]
        for side in (-1, 1):
            pts = blob(g, 8 + side * (4 - flap * 0.5), 8 - flap + bob, 3.5 - flap * 0.6, 3 + flap * 0.4, "violet", outline=False)
            g.put(8 + side * 4, 8 - flap + bob, "b2")
        for y in range(5, 12):
            g.put(7, y + bob, "e2"); g.put(8, y + bob, "e1")
        g.put(6, 6 + bob, "cl2"); g.put(9, 6 + bob, "cl2"); g.put(6, 7 + bob, "ink"); g.put(9, 7 + bob, "ink")
        g.put(6, 4 + bob, "r1"); g.put(5, 3 + bob, "r1"); g.put(9, 4 + bob, "r1"); g.put(10, 3 + bob, "r1")
        silhouette_outline(g, "r0", "r1")
    return g


def poof(frame):
    g = Grid(16, 16)
    rng = random.Random(7)
    r = 2 + frame * 1.6
    for i in range(10):
        a = i / 10 * math.tau + rng.random() * 0.3
        d = r * rng.uniform(0.7, 1.0)
        s = max(0.8, 2.6 - frame * 0.45)
        blob(g, 8 + math.cos(a) * d, 8 + math.sin(a) * d, s, s, "cloud", outline=False)
    return g


def sparkle(frame):
    g = Grid(7, 7)
    k = ["g3", "pa", "cl2"][frame]
    arm = [1, 3, 2][frame]
    for d in range(-arm, arm + 1):
        g.put(3 + d, 3, k); g.put(3, 3 + d, k)
    g.put(3, 3, "cl2")
    return g


def zzz(frame):
    g = Grid(9, 10)
    zs = [(0, 6, 3), (3, 3, 3), (5, 0, 4)][: frame + 1]
    for (x, y, s) in zs:
        for i in range(s):
            g.put(x + i, y, "cl2"); g.put(x + i, y + s - 1, "cl2"); g.put(x + s - 1 - i, y + i, "cl2")
    return g


def cloud(variant):
    w, h = [(48, 20), (32, 14), (64, 24)][variant]
    g = Grid(w, h)
    rng = random.Random(30 + variant)
    puffs = [(w * f, h * 0.62 - rng.uniform(0, h * 0.25), h * rng.uniform(0.28, 0.42)) for f in (0.22, 0.42, 0.6, 0.8)]
    puffs += [(w * 0.5, h * 0.4, h * 0.42)]
    for (x, y, r) in sorted(puffs, key=lambda p: p[1]):
        blob(g, x, y, r * 1.2, r, "cloud", outline=False)
    for x in range(w):   # flat-ish base in the shadow tone
        for y in range(int(h * 0.72), h):
            if g.has(x, y - 1) and y < h - 1:
                g.put(x, y, "cl0")
    return g


def pillar(variant):
    w, h = [(26, 84), (18, 60)][variant]
    g = Grid(w, h)
    rng = random.Random(50 + variant)
    pts = set()
    for y in range(h):
        t = y / h
        half = w / 2 * (0.55 + 0.35 * math.sin(t * 3.1 + variant) * 0.5 + 0.25 * t) + rng.uniform(-0.6, 0.6)
        for x in range(int(w / 2 - half), int(w / 2 + half)):
            pts.add((x, y))
            f = (x - (w / 2 - half)) / max(2 * half, 1)
            g.put(x, y, "r3" if f < 0.25 else ("r1" if f > 0.7 else "r2"))
    dither(g, {p for p in pts if p[1] > h * 0.75}, "r1", "r2")
    for x in range(w):   # grassy cap
        if (x, 0) in pts or (x, 1) in pts:
            g.put(x, 0, "g0"); g.put(x, 1, "v1" if x % 3 else "g0")
    return g


def firefly(frame):
    g = Grid(5, 5)
    g.put(2, 2, "g3" if frame == 0 else "pa")
    if frame == 0:
        for d in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            g.put(2 + d[0], 2 + d[1], "g2")
    return g


def pollen():
    g = Grid(2, 2)
    g.put(0, 0, "pa"); g.put(1, 1, "e4")
    return g


def leaf(frame):
    g = Grid(6, 6)
    shapes = [[(1, 2), (2, 2), (3, 2), (2, 1), (4, 3)], [(2, 1), (2, 2), (3, 3), (3, 2), (2, 3)],
              [(1, 3), (2, 3), (3, 3), (3, 2), (4, 2)], [(3, 1), (3, 2), (2, 3), (2, 2), (3, 3)]][frame]
    for i, (x, y) in enumerate(shapes):
        g.put(x, y, "g2" if i < 2 else "g1")
    return g


def star(frame):
    g = Grid(5, 5)
    g.put(2, 2, "cl2")
    if frame == 0:
        for d in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            g.put(2 + d[0], 2 + d[1], "cl1")
    return g


def moon():
    g = Grid(16, 16)
    pts = blob(g, 8, 8, 6.5, 6.5, "cream", outline=False)
    for (x, y) in list(pts):
        if (x - 11) ** 2 + (y - 6) ** 2 < 30:
            g.px.pop((x, y), None)
    for (x, y) in ((5, 9), (6, 11), (4, 7)):
        if g.has(x, y):
            g.put(x, y, "e4")
    return g


# ---------------------------------------------------------------- island tiles (16x16)

def tile_grass_top(edge, rng):
    """edge: -1 left corner, 0 middle, 1 right corner."""
    g = Grid(16, 16)
    for y in range(16):
        for x in range(16):
            if edge == -1 and x < 3 - min(y, 3):
                continue
            if edge == 1 and x > 12 + min(y, 3):
                continue
            if y < 4:
                k = "g3" if y == 0 else ("g2" if y == 1 else ("g1" if y == 2 else "g0"))
            else:
                k = "e2"
                n = rng.random()
                if n < 0.08:
                    k = "e3"
                elif n < 0.16:
                    k = "e1"
            g.put(x, y, k)
    # grass lip hanging over the earth
    for x in range(16):
        if g.has(x, 4) and rng.random() < 0.45:
            g.put(x, 4, "g0")
            if rng.random() < 0.3:
                g.put(x, 5, "v1")
    # blades on top
    for x in range(0, 16, 3):
        if g.has(x, 0):
            g.put(x + rng.randint(0, 1), -1 + 1, "g3")
    if edge:
        side_x = 0 if edge == -1 else 15
        for y in range(16):
            xs = [x for x in range(16) if g.has(x, y)]
            if xs:
                ex = xs[0] if edge == -1 else xs[-1]
                g.put(ex, y, "v0" if y < 4 else "e0")
    return g


def tile_fill(rng, side=0):
    g = Grid(16, 16)
    for y in range(16):
        for x in range(16):
            n = rng.random()
            g.put(x, y, "e3" if n < 0.06 else ("e1" if n < 0.2 else "e2"))
    for _ in range(2):   # pebbles
        px, py = rng.randrange(2, 13), rng.randrange(2, 13)
        g.put(px, py, "e4"); g.put(px + 1, py, "e3"); g.put(px, py + 1, "e1"); g.put(px + 1, py + 1, "e1")
    if side:
        x = 0 if side == -1 else 15
        for y in range(16):
            g.put(x, y, "e0")
            g.put(x + (1 if side == -1 else -1), y, "e3" if side == -1 else "e1")
    return g


def tile_rock(kind, rng):
    """kind: 'mid', 'left', 'right', 'tip'. Rock underside with purple shadow and checker dithering."""
    g = Grid(16, 16)
    for y in range(16):
        for x in range(16):
            if kind == "left" and x < y // 1.4:
                continue
            if kind == "right" and x > 15 - y // 1.4:
                continue
            if kind == "tip" and abs(x - 7.5) > 7.5 - y * 0.5:
                continue
            band = y / 16
            if band < 0.3:
                k = "r2" if (x + y) % 2 else "e1"
            elif band < 0.6:
                k = "r2"
                if (x + y) % 2 == 0 and band > 0.45:
                    k = "r1"
            else:
                k = "r1" if (x + y) % 2 else "r0"
            if rng.random() < 0.05:
                k = "r3"
            g.put(x, y, k)
    for y in range(16):   # outline on the open sides
        xs = [x for x in range(16) if g.has(x, y)]
        if xs and kind in ("left", "tip"):
            g.put(xs[0], y, "r0")
        if xs and kind in ("right", "tip"):
            g.put(xs[-1], y, "r0")
    return g


def plank():
    g = Grid(16, 8)
    for x in range(16):
        g.put(x, 2, "w2"); g.put(x, 3, "w1"); g.put(x, 4, "w1"); g.put(x, 5, "w0")
        g.put(x, 1, "e0"); g.put(x, 6, "e0")
    for x in (0, 8):
        for y in range(2, 6):
            g.put(x, y, "e0")
        g.put(x + 4, 3, "e1")
    for (x, y) in ((3, 7), (11, 7)):
        g.put(x, y, "w0")
    return g


def rope_bridge():
    g = Grid(16, 10)
    for x in range(16):
        sag = int(round(math.sin(x / 15 * math.pi) * 1.5))
        g.put(x, 1 + sag, "e3")   # rope rail
        if x % 4 != 3:
            g.put(x, 6 + sag, "w2"); g.put(x, 7 + sag, "w1"); g.put(x, 8 + sag, "e0")
        if x % 4 == 0:
            for y in range(2 + sag, 6 + sag):
                g.put(x, y, "e2")
    return g


def hanging_root(frame):
    g = Grid(8, 24)
    for y in range(24):
        x = 3 + math.sin(y * 0.4) * 1.2 + (frame * y / 24 * 1.5)
        g.put(x, y, "e2" if y < 16 else "e3")
        if y < 10:
            g.put(x + 1, y, "e1")
        if y % 5 == 2:
            g.put(x - 1, y + 1, "e2")
    g.put(3 + frame, 23, "g2")
    return g


def banner(frame):
    g = Grid(12, 22)
    for y in range(22):
        g.put(1, y, "w1"); g.put(2, y, "w0")
    g.put(1, 0, "w2"); g.put(2, 0, "w2")
    for y in range(2, 14):
        wave = int(round(math.sin(y * 0.6 + frame * 1.57) * 1))
        for x in range(3, 11):
            k = "b0" if x < 5 else ("b1" if x < 9 else "b2")
            if y in (2,):
                k = "e0"
            g.put(x + (wave if x > 6 else 0), y, k)
        g.put(11 + wave, y, "r1")
    for x in range(3, 11, 2):   # notched tail
        g.put(x + (1 if frame % 2 else 0), 14, "b0")
    g.put(7, 7, "pa"); g.put(6, 8, "pa"); g.put(8, 8, "pa")
    return g


def water(frame):
    g = Grid(16, 8)
    for y in range(8):
        for x in range(16):
            g.put(x, y, "sky0" if y > 1 else "sky1")
    for x in range(16):
        if (x + frame * 3) % 7 == 0:
            g.put(x, 1, "cl2"); g.put(x + 1, 1, "cl2")
        if (x + frame * 5) % 9 == 3:
            g.put(x, 4, "sky1")
    return g


def waterfall(frame):
    g = Grid(10, 16)
    for y in range(16):
        for x in range(10):
            k = "sky1" if (y + x + frame * 2) % 5 else "cl2"
            if x in (0, 9):
                k = "sky0"
            g.put(x, y, k)
    return g


# ---------------------------------------------------------------- output

def render(g):
    img = Image.new("RGBA", (g.w, g.h), (0, 0, 0, 0))
    for (x, y), k in g.px.items():
        img.putpixel((x, y), RGB[k] + (255,))
    return img


def add(name, frames, fps=0, anchor=None, priority="P2"):
    """frames: list of Grid with the same size. Writes the strip and its atlas entry."""
    w, h = frames[0].w, frames[0].h
    strip = Image.new("RGBA", (w * len(frames), h), (0, 0, 0, 0))
    for i, f in enumerate(frames):
        strip.paste(render(f), (i * w, 0))
    OUT.mkdir(parents=True, exist_ok=True)
    strip.save(OUT / f"{name}.png")
    ATLAS.append({"name": name, "file": f"{name}.png", "w": w, "h": h,
                  "anchor": anchor or [w // 2, h - 1], "frames": len(frames), "fps": fps, "priority": priority})


def main():
    presets = json.loads((ROOT / "engine/src/main/assets/organisms/index.json").read_text())["presets"]
    for p in presets:
        meta = json.loads((ROOT / "engine/src/main/assets" / p["file"]).read_text())
        kingdom, shape = meta.get("kingdom", "plant"), meta.get("shape", {})
        for s in range(3):
            add(f"creature_{p['id']}_s{s + 1}", [creature(p["id"], kingdom, shape, s, f, False) for f in range(4)], 6, priority="P1")
            add(f"creature_{p['id']}_s{s + 1}_sleep", [creature(p["id"], kingdom, shape, s, f, True) for f in range(2)], 2, priority="P2")
    for v in range(2):
        add(f"snoop_{v + 1}", [snoop(v, f) for f in range(4)], 8, priority="P1")
    rng = random.Random(1)
    add("island_top_left", [tile_grass_top(-1, rng)], anchor=[0, 0], priority="P1")
    add("island_top", [tile_grass_top(0, rng)], anchor=[0, 0], priority="P1")
    add("island_top_right", [tile_grass_top(1, rng)], anchor=[0, 0], priority="P1")
    add("island_fill", [tile_fill(rng)], anchor=[0, 0], priority="P1")
    add("island_edge_left", [tile_fill(rng, -1)], anchor=[0, 0], priority="P1")
    add("island_edge_right", [tile_fill(rng, 1)], anchor=[0, 0], priority="P1")
    for kind in ("left", "mid", "right", "tip"):
        add(f"rock_under_{kind}", [tile_rock(kind, rng)], anchor=[0, 0], priority="P1")
    add("plank", [plank()], anchor=[0, 0], priority="P2")
    add("rope_bridge", [rope_bridge()], anchor=[0, 0], priority="P2")
    add("hanging_root", [hanging_root(f) for f in range(2)], 2, anchor=[4, 0], priority="P2")
    add("banner", [banner(f) for f in range(4)], 6, anchor=[1, 21], priority="P3")
    add("water", [water(f) for f in range(3)], 4, anchor=[0, 0], priority="P2")
    add("waterfall", [waterfall(f) for f in range(3)], 8, anchor=[0, 0], priority="P3")
    for v in range(2):
        r = lambda k: random.Random(100 + v * 17 + k)
        add(f"tree_blossom_{v + 1}", [blossom_tree(r(1), v)], priority="P1")
        add(f"fern_{v + 1}", [fern(r(2), v)], priority="P2")
        add(f"mushroom_glow_{v + 1}", [glow_mushrooms(r(3), v, f) for f in range(2)], 2, priority="P2")
        add(f"vine_hanging_{v + 1}", [hanging_vine(r(4), v, f) for f in range(2)], 2, anchor=[6, 0], priority="P2")
        add(f"lily_pads_{v + 1}", [lily_pads(r(5), v)], priority="P3")
        add(f"reeds_{v + 1}", [reeds(r(6), v)], priority="P3")
        add(f"moss_{v + 1}", [moss(r(7), v)], priority="P2")
        add(f"flowers_{v + 1}", [flowers(r(8), v)], priority="P2")
        add(f"grass_tuft_{v + 1}", [grass_tuft(r(9), v, f) for f in range(2)], 2, priority="P2")
        add(f"bonsai_{v + 1}", [bonsai(r(10), v)], priority="P3")
    for v in range(3):
        add(f"cloud_{v + 1}", [cloud(v)], anchor=[0, 0], priority="P1")
    for v in range(2):
        add(f"pillar_{v + 1}", [pillar(v)], anchor=[0, 0], priority="P2")
    add("firefly", [firefly(f) for f in range(2)], 4, anchor=[2, 2], priority="P2")
    add("pollen", [pollen()], anchor=[0, 0], priority="P3")
    add("leaf", [leaf(f) for f in range(4)], 6, anchor=[3, 3], priority="P3")
    add("star", [star(f) for f in range(2)], 1, anchor=[2, 2], priority="P2")
    add("moon", [moon()], anchor=[8, 8], priority="P2")
    add("poof", [poof(f) for f in range(5)], 12, anchor=[8, 8], priority="P1")
    add("sparkle", [sparkle(f) for f in range(3)], 10, anchor=[3, 3], priority="P2")
    add("zzz", [zzz(f) for f in range(3)], 2, anchor=[0, 9], priority="P2")
    (OUT / "atlas.json").write_text(json.dumps({"version": 1, "sprites": ATLAS}, indent=1))
    contact_sheet()
    sprites_md()
    print(f"{len(ATLAS)} sprites written")


def sprites_md():
    rows = ["# Sprites", "",
            "Generated by `python3 scripts/gen_sprites.py` into `app/src/main/assets/sprites/` (atlas: `atlas.json`).",
            "Replace any PNG with your own art: same file name, same frame size (w x h), frames side by side left to right.",
            "Anchor = the pixel placed on the ground/attachment point (x, y inside one frame). The app loads sprites only",
            "through the atlas; a missing file gets a drawn fallback. Priority: P1 = seen first / most important.", "",
            "| Sprite | File | Frame w x h | Frames | fps | Anchor | Priority |", "|---|---|---|---|---|---|---|"]
    for e in ATLAS:
        rows.append(f"| {e['name']} | {e['file']} | {e['w']} x {e['h']} | {e['frames']} | {e['fps']} | {e['anchor'][0]},{e['anchor'][1]} | {e['priority']} |")
    rows += ["", "Creatures: `creature_<preset>_s<stage>` (stage 1 baby, 2 teen, 3 adult = the engine's stages), 4-frame idle;",
             "`..._sleep` 2 frames. Palette: 32 colors listed in PAL at the top of the script; no pure black.", ""]
    (ROOT / "docs/SPRITES.md").write_text("\n".join(rows))


def contact_sheet():
    """All sprites at 3x on a sky-to-indigo split background, for a quick look."""
    scale, pad, cols_w = 3, 6, 1100
    tiles = [(e, Image.open(OUT / e["file"])) for e in ATLAS]
    x = y = pad
    row_h = 0
    placed = []
    for e, im in tiles:
        w, h = im.width * scale, im.height * scale
        if x + w > cols_w:
            x, y = pad, y + row_h + pad + 10
            row_h = 0
        placed.append((x, y, im, e))
        x += w + pad
        row_h = max(row_h, h)
    H = y + row_h + pad + 10
    sheet = Image.new("RGBA", (cols_w, H), RGB["sky1"] + (255,))
    d = ImageDraw.Draw(sheet)
    d.rectangle([0, H // 2, cols_w, H], fill=RGB["r0"] + (255,))
    for (x, y, im, e) in placed:
        big = im.resize((im.width * scale, im.height * scale), Image.NEAREST)
        sheet.alpha_composite(big, (x, y))
    sheet.save(ROOT / "docs/sprites_contact.png")


if __name__ == "__main__":
    main()
