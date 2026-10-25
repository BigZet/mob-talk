"""Draws the gilded carving of the stone iconostasis and writes its block models, blockstates and item models.

Run from the repository root: python scripts/gen_gilded_assets.py
Original pixel art drawn here in code, in the palette of scripts/gen_church_assets.py: the carved column with a grapevine
winding up it (the usual carving of Russian iconostasis columns), the gold-leaf ground laid in square sheets with a raised
frame, the cornice with a row of beads between two mouldings. Each tiles with itself: the column up and down, the ground
every way, the cornice along its length.
"""
import json
import math
import os
import random

from PIL import Image

ROOT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "divineblock")
TEX = os.path.join(ROOT, "textures", "block")
MODELS = os.path.join(ROOT, "models", "block")
ITEMS = os.path.join(ROOT, "models", "item")
STATES = os.path.join(ROOT, "blockstates")
rng = random.Random(1724)

HI, LIGHT, GOLD, MID, LO, DK, DEEP = (255, 236, 150), (246, 210, 104), (222, 176, 62), (190, 140, 42), (156, 108, 30), (112, 72, 18), (74, 44, 12)


def jitter(c, k=8):
    d = rng.randint(-k, k)
    return tuple(max(0, min(255, v + d)) for v in c)


def leaf_ground(px, x0, y0, w, h):
    """Gold leaf in 4 x 4 sheets, each a little brighter or darker, a fine seam where they meet."""
    for y in range(h):
        for x in range(w):
            sheet = ((x0 + x) // 4 * 7 + (y0 + y) // 4 * 3) % 4
            base = (GOLD, LIGHT, MID, GOLD)[sheet]
            c = jitter(base, 6)
            if (x0 + x) % 4 == 3 and (y0 + y) % 4 == 3:
                c = MID
            px[x0 + x, y0 + y] = c


def save(img, name):
    img.save(os.path.join(TEX, name + ".png"))


def ground():
    """The iconostasis' gilded ground: gold leaf in a raised frame (light on the top and left, shade on the bottom and right)."""
    img = Image.new("RGB", (16, 16))
    px = img.load()
    leaf_ground(px, 0, 0, 16, 16)
    for i in range(16):
        px[i, 0] = HI if i < 15 else LIGHT
        px[0, i] = HI if i < 15 else LIGHT
        px[i, 15] = LO
        px[15, i] = LO
        px[i, 1] = LIGHT if 0 < i < 15 else px[i, 1]
        px[1, i] = LIGHT if 0 < i < 15 else px[1, i]
        px[i, 14] = MID if 0 < i < 15 else px[i, 14]
        px[14, i] = MID if 0 < i < 15 else px[14, i]
    # A carved rosette in each corner of the frame.
    for cx, cy in ((2, 2), (13, 2), (2, 13), (13, 13)):
        px[cx, cy] = DK
    save(img, "gilded_ground")


def column():
    """The carved column: a deep ground with a grapevine winding up it (one turn a block, so it runs on unbroken), bunches
    of grapes and leaves on the vine, a gilded fillet down both edges."""
    img = Image.new("RGB", (16, 16))
    px = img.load()
    for y in range(16):
        for x in range(16):
            px[x, y] = jitter(DK if 2 <= x <= 13 else MID, 5)
    # Both edges: a rounded fillet, lit on the left.
    for y in range(16):
        px[0, y], px[1, y] = LIGHT, GOLD
        px[14, y], px[15, y] = MID, LO
    # The vine: a thick stem drawn as a sine across the shaft, lit on its upper side.
    for y in range(16):
        t = y / 16 * 2 * math.pi
        cx = 7.5 + 4.6 * math.sin(t)
        for x in range(2, 14):
            d = abs(x - cx)
            if d < 1.2:
                px[x, y] = LIGHT if x < cx else GOLD
            elif d < 1.9:
                px[x, y] = MID
    # Grapes in the bends (little bunches of round berries) and a vine leaf on the other side of each bend.
    for gx, gy, side in ((3, 4, 1), (11, 12, -1)):
        for dx, dy in ((0, 0), (1, 0), (0, 1), (1, 1), (2, 1), (0, 2), (1, 2), (1, 3)):
            x, y = gx + dx * side, gy + dy - 1
            if 2 <= x <= 13:
                px[x, y % 16] = HI if (dx + dy) % 3 == 0 else LIGHT
    for lx, ly, side in ((10, 3, 1), (4, 11, -1)):
        for dx, dy in ((0, 0), (1, 0), (-1, 0), (0, 1), (1, 1), (0, -1), (2, 0)):
            x, y = lx + dx * side, ly + dy
            if 2 <= x <= 13:
                px[x, y % 16] = GOLD if dy < 1 else MID
        px[lx, ly] = HI
    save(img, "gilded_column")
    # The column's top and bottom: a round capital seen from above.
    top = Image.new("RGB", (16, 16))
    tp = top.load()
    for y in range(16):
        for x in range(16):
            r = math.hypot(x - 7.5, y - 7.5)
            tp[x, y] = jitter(LIGHT if r < 3 else GOLD if r < 5.5 else MID if r < 7 else LO, 5)
    tp[7, 7] = tp[8, 8] = HI
    save(top, "gilded_column_top")


def cornice():
    """The cornice between the tiers: a moulding above and below, a row of beads between them, a shadow under the top."""
    img = Image.new("RGB", (16, 16))
    px = img.load()
    for y in range(16):
        for x in range(16):
            if y <= 1:
                c = HI if y == 0 else LIGHT
            elif y == 2:
                c = DK
            elif y <= 4:
                c = GOLD if y == 3 else MID
            elif y <= 10:
                c = DEEP if y in (5, 10) else DK
            elif y <= 12:
                c = LIGHT if y == 11 else GOLD
            elif y == 13:
                c = DK
            else:
                c = MID if y == 14 else LO
            px[x, y] = jitter(c, 4)
    # The beads (pearls), four along the block, each lit on its top left.
    for bx in (1, 5, 9, 13):
        for dx in range(3):
            for dy in range(4):
                if (dx, dy) in ((0, 0), (2, 0), (0, 3), (2, 3)):
                    continue
                px[bx + dx, 6 + dy] = HI if (dx, dy) == (0, 1) else LIGHT if dy <= 1 else GOLD if dy == 2 else MID
    save(img, "gilded_cornice")
    flat = Image.new("RGB", (16, 16))
    leaf_ground(flat.load(), 0, 0, 16, 16)
    save(flat, "gilded_leaf")


def write(path, data):
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2)
        f.write("\n")


def models():
    write(os.path.join(MODELS, "gilded_ground.json"), {"parent": "minecraft:block/cube_all", "textures": {"all": "divineblock:block/gilded_ground"}})
    write(os.path.join(MODELS, "gilded_column.json"), {"parent": "minecraft:block/cube_column",
                                                        "textures": {"side": "divineblock:block/gilded_column", "end": "divineblock:block/gilded_column_top"}})
    write(os.path.join(MODELS, "gilded_cornice.json"), {"parent": "minecraft:block/cube_column",
                                                         "textures": {"side": "divineblock:block/gilded_cornice", "end": "divineblock:block/gilded_leaf"}})
    for name in ("gilded_ground", "gilded_column", "gilded_cornice"):
        write(os.path.join(STATES, name + ".json"), {"variants": {"": {"model": "divineblock:block/" + name}}})
        write(os.path.join(ITEMS, name + ".json"), {"parent": "divineblock:block/" + name})


if __name__ == "__main__":
    ground()
    column()
    cornice()
    models()
