#!/usr/bin/env python3
"""Original Mycelium brand art: a cute pixel mushroom with root threads. Palette-based, nearest-neighbor only.

Writes (all whole-number scales, no blur):
  app/src/main/res/mipmap-*/ic_launcher_fg.png, ic_launcher_bg.png, ic_launcher_mono.png   adaptive icon layers
      54x54 art grid at 2 dp per pixel (108 dp layer); the mushroom stays inside the 66 dp safe circle
  app/src/main/res/drawable-*/splash_icon.png      60x60 grid at 4 dp per pixel (240 dp splash icon)
  app/src/main/res/drawable-nodpi/sprite_*.png     1 px per art pixel, scaled up in-app by whole numbers
  engine/src/main/res/drawable/ic_stat_mycelium.xml  white-on-transparent vector for the VPN notification
  docs/store/icon-512.png                          store icon
Usage: python3 scripts/make_brand.py
"""
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "app/src/main/res"

PAL = {
    "K": "#3B2A1A",  # outline
    "R": "#D0573F",  # cap
    "r": "#A8402F",  # cap shade
    "h": "#E9876B",  # cap highlight
    "W": "#FFF4DC",  # spots
    "U": "#E8C99A",  # underside
    "S": "#F6E7C8",  # stem
    "s": "#E0C79A",  # stem shade
    "E": "#2B1E12",  # eyes
    "P": "#F09A8A",  # blush
    "G": "#E3A73C",  # root threads
    "g": "#B97F2A",  # root threads, dark
    "N": "#2E4A2C",  # icon background
    "n": "#3D6138",  # background spores
    "m": "#24391F",  # background soil
    "Y": "#F2C14E",  # moon / sparkle
    "B": "#8CC7DE",  # magnifier glass
}


def mushroom(face="happy"):
    """32x32 dict (x, y) -> palette key. Everything lies within a circle of radius 15.5 around the center."""
    px = {}
    cx, cy, rx, ry = 15.5, 13.0, 12.5, 10.0
    for y in range(3, 14):  # cap: top half of an ellipse
        for x in range(2, 30):
            if ((x - cx) / rx) ** 2 + ((y - cy) / ry) ** 2 <= 1.0:
                px[(x, y)] = "R"
    for (x, y), _ in list(px.items()):  # shade the lower-right, highlight the upper-left
        if x > cx + 4 and y > 8:
            px[(x, y)] = "r"
        if x < cx - 3 and y < 8 and ((x - cx + 6) ** 2 + (y - 6) ** 2) < 6:
            px[(x, y)] = "h"
    for sx, sy, w, h in [(9, 6, 3, 2), (17, 4, 3, 2), (20, 9, 2, 2), (6, 10, 2, 1), (13, 10, 2, 1)]:  # spots
        for x in range(sx, sx + w):
            for y in range(sy, sy + h):
                if (x, y) in px:
                    px[(x, y)] = "W"
    for x in range(5, 27):  # underside rim
        px[(x, 13)] = "U"
    cap = {p for p, c in px.items()}
    for (x, y) in cap:  # outline around the cap
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            q = (x + dx, y + dy)
            if q not in cap:
                px[q] = "K"
    for y in range(15, 25):  # stem, with rounded bottom corners
        for x in range(11, 21):
            if y == 24 and x in (11, 20):
                continue
            px[(x, y)] = "s" if x >= 18 else "S"
    for y in range(15, 25):
        px[(10, y)] = "K"
        px[(21, y)] = "K"
    for x in range(12, 20):
        px[(x, 25)] = "K"
    px[(11, 24)] = "K"
    px[(20, 24)] = "K"
    # face
    if face == "sleepy":
        for x in (13, 14, 17, 18):
            px[(x, 18)] = "E"
    elif face == "wow":
        for x, y in ((13, 17), (13, 18), (18, 17), (18, 18)):
            px[(x, y)] = "E"
        px[(15, 20)] = "E"
        px[(16, 20)] = "E"
        px[(15, 21)] = "E"
        px[(16, 21)] = "E"
    else:
        for x, y in ((13, 17), (13, 18), (18, 17), (18, 18)):
            px[(x, y)] = "E"
        px[(15, 20)] = "E"
        px[(16, 20)] = "E"
    px[(12, 19)] = "P"
    px[(19, 19)] = "P"
    # root threads (mycelium) under the stem
    roots = [
        [(14, 26), (13, 27), (12, 28), (11, 28), (10, 29)],
        [(17, 26), (18, 27), (19, 28), (20, 28), (21, 29)],
        [(15, 26), (15, 27), (15, 28), (16, 29), (16, 30)],
        [(12, 26), (11, 26), (10, 27), (9, 27), (8, 28)],
        [(19, 26), (20, 26), (21, 27), (22, 27), (23, 28)],
    ]
    for path in roots:
        for i, p in enumerate(path):
            px[p] = "g" if i % 2 else "G"
    if face == "sleepy":  # a little "z"
        for p in ((25, 1), (26, 1), (27, 1), (28, 1), (27, 2), (26, 3), (25, 4), (26, 4), (27, 4), (28, 4)):
            px[p] = "Y"
    return px


def background(n, step=1):
    """n x n icon background: deep green with soft spores and a soil band."""
    px = {}
    for y in range(n):
        for x in range(n):
            px[(x, y)] = "N"
            if y > n * 0.78:
                px[(x, y)] = "m"
            elif (x * 7 + y * 13) % 29 == 0:
                px[(x, y)] = "n"
    return px


def render(layers, n, scale, out):
    img = Image.new("RGBA", (n * scale, n * scale), (0, 0, 0, 0))
    for layer, ox, oy, mono in layers:
        for (x, y), k in layer.items():
            color = (255, 255, 255, 255) if mono else tuple(int(PAL[k][i:i + 2], 16) for i in (1, 3, 5)) + (255,)
            X, Y = x + ox, y + oy
            if 0 <= X < n and 0 <= Y < n:
                for dy in range(scale):
                    for dx in range(scale):
                        img.putpixel((X * scale + dx, Y * scale + dy), color)
    out.parent.mkdir(parents=True, exist_ok=True)
    img.save(out)


def sprite(px, out, size=32):
    render([(px, 0, 0, False)], size, 1, out)


def vector(px, out):
    """Merge each row's runs into rectangles: an Android vector drawable, white on transparent."""
    rows = {}
    for (x, y) in px:
        rows.setdefault(y, []).append(x)
    parts = []
    for y in sorted(rows):
        xs = sorted(rows[y])
        start = prev = xs[0]
        for x in xs[1:] + [None]:
            if x is not None and x == prev + 1:
                prev = x
                continue
            parts.append(f"M{start},{y}h{prev - start + 1}v1h{-(prev - start + 1)}z")
            if x is not None:
                start = prev = x
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        "<!-- Generated by scripts/make_brand.py: Mycelium mushroom, white on transparent (status bar). -->\n"
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:width="24dp" android:height="24dp" android:viewportWidth="32" android:viewportHeight="32">\n'
        f'    <path android:fillColor="#FFFFFFFF" android:pathData="{"".join(parts)}" />\n'
        "</vector>\n"
    )


def main():
    logo = mushroom()
    densities = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}
    for name, d in densities.items():
        cell = int(2 * d)                       # 2 dp per art pixel; 54 cells = 108 dp
        off = (54 - 32) // 2
        render([(logo, off, off, False)], 54, cell, RES / f"mipmap-{name}/ic_launcher_fg.png")
        render([(background(54), 0, 0, False)], 54, cell, RES / f"mipmap-{name}/ic_launcher_bg.png")
        render([(logo, off, off, True)], 54, cell, RES / f"mipmap-{name}/ic_launcher_mono.png")
        render([(logo, 14, 14, False)], 60, int(4 * d), RES / f"drawable-{name}/splash_icon.png")  # 60 cells = 240 dp
    for face in ("happy", "sleepy", "wow"):
        sprite(mushroom(face), RES / f"drawable-nodpi/sprite_mushroom_{face}.png")
    # status-bar icon: silhouette without the face, so it reads at 24 dp
    silhouette = {p: k for p, k in mushroom().items() if k not in ("E", "P")}
    vector(silhouette, ROOT / "engine/src/main/res/drawable/ic_stat_mycelium.xml")
    # 512 px store icon: background at 4 px per cell, logo at 12 px per art pixel
    store = Image.new("RGBA", (512, 512))
    bg = Image.new("RGBA", (128 * 4, 128 * 4))
    render([(background(128), 0, 0, False)], 128, 4, ROOT / "build/store_bg.png")
    bg = Image.open(ROOT / "build/store_bg.png")
    store.paste(bg, (0, 0))
    render([(logo, 0, 0, False)], 32, 12, ROOT / "build/store_logo.png")
    lg = Image.open(ROOT / "build/store_logo.png")
    store.alpha_composite(lg, (64, 64))
    (ROOT / "docs/store").mkdir(parents=True, exist_ok=True)
    store.save(ROOT / "docs/store/icon-512.png")
    print("brand assets written")


if __name__ == "__main__":
    main()
