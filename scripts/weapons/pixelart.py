"""Draws the weapons' item sprites by code: 32×32, in the pose of the vanilla swords and bow (grip bottom left, point top
right, light up-left and shadow down-right), but richer, as befits the strongest arms in the game: wrapped grips, gold
guards, gems, engraved fullers, and for the holy and the fiery a second «glow» sprite drawn at full brightness over the
first (flames and light that flicker, frame by frame). GPL-3.0, no outside art.
Run: python scripts/weapons/pixelart.py [preview.png] — the preview shows every frame (base + glow) six times enlarged."""
import json
import math
import os
import random
import sys

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
ASSETS = os.path.join(ROOT, "src", "main", "resources", "assets", "divineblock")
TEX = os.path.join(ASSETS, "textures", "item")
N = 32

# Palettes: (highlight, light, mid, dark, deep)
STEEL = ((244, 248, 252), (214, 220, 230), (164, 172, 186), (104, 112, 128), (56, 62, 76))
GOLD = ((255, 244, 180), (250, 214, 92), (222, 168, 44), (160, 110, 26), (96, 62, 12))
WOOD = ((178, 132, 80), (142, 98, 54), (108, 70, 36), (72, 44, 20), (40, 24, 10))
LEATHER = ((160, 110, 70), (124, 80, 48), (92, 56, 32), (60, 34, 18), (34, 18, 8))
BONE = ((250, 246, 230), (236, 226, 198), (206, 192, 156), (156, 140, 104), (100, 88, 62))
FLAME = ((255, 252, 210), (255, 230, 100), (255, 160, 36), (220, 76, 16), (130, 30, 8))
COPPER = ((246, 184, 136), (218, 132, 80), (178, 94, 54), (120, 58, 32), (66, 30, 16))
CLAY = ((214, 150, 108), (186, 114, 76), (150, 84, 54), (104, 56, 34), (60, 30, 18))
HOLY = ((240, 250, 255), (176, 220, 255), (104, 170, 250), (48, 110, 210), (20, 60, 140))
BLOOD = ((236, 80, 76), (190, 28, 38), (130, 12, 24), (80, 4, 14), (40, 0, 6))
BLACK_STEEL = ((196, 196, 208), (138, 140, 152), (88, 90, 104), (48, 50, 60), (20, 22, 28))
ASPEN = ((250, 246, 234), (232, 220, 194), (204, 188, 154), (160, 142, 108), (104, 90, 64))
BARK = ((190, 192, 184), (156, 158, 150), (116, 118, 110), (76, 78, 72), (40, 42, 38))
STONE = ((210, 212, 218), (170, 172, 180), (128, 130, 140), (86, 88, 98), (50, 52, 60))
STRING = ((252, 248, 236), (230, 224, 204), (196, 188, 166), (140, 134, 116), (90, 86, 74))
RED_CLOTH = ((244, 110, 96), (206, 46, 46), (156, 26, 32), (100, 12, 18), (54, 4, 8))
RUBY = ((255, 150, 150), (230, 40, 60), (160, 10, 34), (90, 2, 18), (40, 0, 8))
SAPPHIRE = ((200, 230, 255), (90, 150, 250), (30, 80, 200), (14, 40, 120), (6, 18, 60))
EMERALD = ((200, 255, 210), (80, 220, 110), (20, 150, 60), (8, 90, 36), (2, 44, 16))
OUTLINE = (22, 18, 20)


class Canvas:
    def __init__(self):
        self.px = {}

    def put(self, x, y, c):
        if 0 <= x < N and 0 <= y < N and c is not None:
            self.px[(x, y)] = c

    def get(self, x, y):
        return self.px.get((x, y))

    def line(self, x0, y0, x1, y1, c):
        dx, dy = abs(x1 - x0), -abs(y1 - y0)
        sx, sy = (1 if x0 < x1 else -1), (1 if y0 < y1 else -1)
        err = dx + dy
        while True:
            self.put(x0, y0, c)
            if x0 == x1 and y0 == y1: return
            e2 = 2 * err
            if e2 >= dy: err += dy; x0 += sx
            if e2 <= dx: err += dx; y0 += sy

    def run(self, x0, y0, length, c, step=(1, -1)):
        """A 45° run from (x0, y0), `length` cells, up-right by default."""
        for i in range(length):
            self.put(x0 + step[0] * i, y0 + step[1] * i, c)

    def blade(self, x0, y0, length, pal, width=5, taper=4, fuller=True):
        """A blade on the up-right diagonal: `width` parallel runs (offsets along the down-right diagonal), the up-left
        ones light, the down-right ones dark, shrinking toward the point; an engraved fuller on the axis."""
        hi, light, mid, dark, deep = pal
        half = width // 2
        for k in range(-half, half + 1):
            c = hi if k == -half else light if k < 0 else mid if k == 0 else dark if k < half else deep
            if width >= 7 and k == -half + 1: c = light
            n = length - taper * abs(k) // max(1, half) - (1 if k == half else 0)
            self.run(x0 + k, y0 + k, n, c)
        tx, ty = x0 + length, y0 - length
        self.put(tx - 1, ty + 1, light); self.put(tx, ty, hi)
        if fuller:
            for i in range(3, length - taper - 2):
                self.put(x0 + i, y0 - i, dark if i % 2 else mid)

    def disc(self, cx, cy, r, pal, gem=False):
        hi, light, mid, dark, deep = pal
        for y in range(int(cy - r - 1), int(cy + r + 2)):
            for x in range(int(cx - r - 1), int(cx + r + 2)):
                d = math.hypot(x - cx, y - cy)
                if d <= r:
                    t = (x - cx) + (y - cy)  # light from up-left
                    self.put(x, y, hi if t < -r * 0.9 else light if t < -r * 0.3 else mid if t < r * 0.3 else dark)
        if gem: self.put(int(cx - r * 0.5), int(cy - r * 0.5), (255, 255, 255))

    def outline(self, c=OUTLINE):
        edge = {}
        for (x, y) in list(self.px):
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                if (x + dx, y + dy) not in self.px and 0 <= x + dx < N and 0 <= y + dy < N:
                    edge[(x + dx, y + dy)] = c
        self.px.update(edge)

    def image(self):
        img = Image.new("RGBA", (N, N), (0, 0, 0, 0))
        for (x, y), c in self.px.items():
            img.putpixel((x, y), tuple(c) + (255,) if len(c) == 3 else tuple(c))
        return img


def grip(c, x0=4, y0=27, length=7, wrap=LEATHER, metal=GOLD, gem=RUBY):
    """A wrapped grip on the bottom-left diagonal, three wide, with a round pommel set with a gem below it."""
    hi, light, mid, dark, deep = wrap
    c.run(x0, y0, length, mid); c.run(x0 - 1, y0, length, light); c.run(x0 + 1, y0, length, dark)
    for i in range(0, length, 2):  # the bands of the wrap
        c.put(x0 + i, y0 - i, dark); c.put(x0 - 1 + i, y0 - i, mid); c.put(x0 + 1 + i, y0 - i, deep)
    # a gold collar where the grip meets the guard
    c.put(x0 + length - 1, y0 - length + 1, metal[1]); c.put(x0 + length, y0 - length + 1, metal[2]); c.put(x0 + length - 1, y0 - length + 2, metal[2])
    c.disc(x0 - 1, y0 + 1.5, 2.2, metal)
    c.put(x0 - 1, y0 + 1, gem[1]); c.put(x0 - 2, y0 + 1, gem[0]); c.put(x0 - 1, y0 + 2, gem[2])


def guard(c, x, y, pal, half=4, flare=True):
    """A cross-guard at (x, y) across the blade (a run down-right), two thick, its ends flared and set off."""
    hi, light, mid, dark, deep = pal
    for i in range(-half, half + 1):
        c.put(x + i, y + i, light if i < 0 else mid)
        c.put(x + i + 1, y + i, hi if i < 0 else light)
        c.put(x + i, y + i + 1, mid if i < 0 else dark)
    if flare:
        c.put(x - half - 1, y - half - 1, light); c.put(x - half, y - half - 2, hi); c.put(x - half - 2, y - half, hi)
        c.put(x + half + 1, y + half + 1, dark); c.put(x + half + 2, y + half, mid); c.put(x + half, y + half + 2, mid)
    c.put(x, y, hi)


# ---------------------------------------------------------------- the weapons

def archistrategos_sword():
    """Steel and gold, the fuller alight with holy blue, wings on the guard, a sapphire in the pommel."""
    c = Canvas()
    grip(c, gem=SAPPHIRE)
    guard(c, 11, 20, GOLD, half=4)
    # the wings: feathered gold sweeps from the guard's ends toward the blade
    for i, (x, y) in enumerate(((6, 14), (7, 13), (8, 12), (7, 15), (8, 14))): c.put(x, y, GOLD[1] if i < 3 else GOLD[2])
    for i, (x, y) in enumerate(((17, 25), (18, 24), (19, 23), (16, 24), (17, 23))): c.put(x, y, GOLD[2] if i < 3 else GOLD[3])
    c.blade(12, 19, 18, STEEL, width=5, taper=4)
    # a gold ricasso with filigree
    for i in range(3): c.put(12 + i, 19 - i, GOLD[2]); c.put(11 + i, 19 - i, GOLD[1]); c.put(13 + i, 19 - i, GOLD[3])
    c.outline()
    g = Canvas()  # the glow: holy light in the fuller and a star at the point
    for i in range(3, 13): g.put(12 + i, 19 - i, HOLY[1] if i % 2 else HOLY[2])
    g.put(29, 2, HOLY[0]); g.put(28, 2, HOLY[1]); g.put(29, 3, HOLY[1]); g.put(30, 1, HOLY[0])
    g.put(11, 20, HOLY[0])  # the gem of the guard
    return [c], [g]


def cherub_sword():
    """A gold hilt; the blade is a tongue of fire that flickers, frame by frame, with sparks flying off it."""
    rand = random.Random(7)
    base, glows = [], []
    c = Canvas()
    grip(c, wrap=GOLD, metal=GOLD, gem=RUBY)
    guard(c, 11, 20, GOLD, half=4)
    c.blade(12, 19, 18, (FLAME[4], FLAME[3], FLAME[3], FLAME[4], (70, 14, 4)), width=5, taper=4, fuller=False)
    c.outline((80, 20, 0))
    base.append(c)
    for f in range(4):
        g = Canvas()
        # the fire's body: a core of white-yellow on the axis, orange round it
        for i in range(0, 17):
            g.put(12 + i, 19 - i, FLAME[0] if i > 2 else FLAME[1]); g.put(11 + i, 19 - i, FLAME[1]); g.put(13 + i, 19 - i, FLAME[2])
            if rand.random() < 0.6: g.put(10 + i, 19 - i, FLAME[2])
            if rand.random() < 0.5: g.put(14 + i, 19 - i, FLAME[3])
        # tongues licking off the up-left edge, different every frame
        for i in range(2, 17):
            if rand.random() < 0.5:
                h = rand.randint(1, 3)
                for k in range(h): g.put(9 + i - k, 19 - i - 1 - k, FLAME[1] if k == 0 else FLAME[2] if k == 1 else FLAME[3])
        # sparks flying off
        for _ in range(5):
            x, y = rand.randint(8, 31), rand.randint(0, 20)
            if g.get(x, y) is None and c.get(x, y) is None: g.put(x, y, FLAME[1] if rand.random() < 0.5 else FLAME[2])
        glows.append(g)
    return base, glows


def war_sword():
    """The second horseman's great sword: a broad black blade with a channel of blood that pulses, a dark gold guard with
    the broken seal, a ruby in the pommel."""
    c = Canvas()
    grip(c, wrap=((110, 100, 110), (74, 66, 78), (46, 40, 50), (24, 20, 28), (10, 8, 12)), metal=(GOLD[2], GOLD[3], GOLD[4], (60, 36, 8), (30, 16, 2)), gem=RUBY)
    guard(c, 11, 20, (GOLD[1], GOLD[2], GOLD[3], GOLD[4], (50, 30, 6)), half=5)
    c.blade(12, 19, 18, BLACK_STEEL, width=7, taper=5, fuller=False)
    for i in range(2, 13): c.put(12 + i, 19 - i, BLOOD[3])  # the blood channel
    c.put(11, 20, RUBY[1]); c.put(10, 20, RUBY[0]); c.put(11, 21, RUBY[2])  # the broken seal
    c.put(12, 21, RUBY[2]); c.put(10, 19, RUBY[2])
    c.outline()
    glows = []
    for f in range(4):
        g = Canvas()
        for i in range(2, 13):
            on = (i + f) % 4 < 2
            g.put(12 + i, 19 - i, BLOOD[0] if on else BLOOD[1])
        g.put(11, 20, RUBY[0] if f % 2 else RUBY[1])
        glows.append(g)
    return [c], glows


def george_spear():
    """A long shaft of dark wood bound in gold, a broad leaf of steel, a red pennant with gold fringe streaming below."""
    c = Canvas()
    hi, light, mid, dark, deep = WOOD
    c.run(2, 30, 18, mid); c.run(1, 30, 18, light); c.run(3, 30, 17, dark); c.run(0, 30, 17, hi)
    for i in (2, 3, 9, 10, 15, 16): c.put(1 + i, 30 - i, GOLD[2]); c.put(2 + i, 30 - i, GOLD[1]); c.put(3 + i, 30 - i, GOLD[3])
    # the ferrule and the socket
    for i in range(3): c.put(18 + i, 13 - i, GOLD[1]); c.put(19 + i, 13 - i, GOLD[2]); c.put(20 + i, 13 - i, GOLD[3]); c.put(17 + i, 13 - i, GOLD[0])
    # the head: a leaf of steel, broad at the shoulders
    c.blade(21, 11, 9, STEEL, width=5, taper=3, fuller=True)
    c.put(20, 10, STEEL[1]); c.put(21, 12, STEEL[3]); c.put(20, 9, STEEL[0]); c.put(22, 12, STEEL[4])
    # the pennant: a long forked tongue of red cloth streaming down-left from the socket, fringed with gold
    for row, (x0, x1) in enumerate(((13, 18), (11, 17), (9, 15), (8, 13), (7, 11), (6, 9), (5, 7))):
        y = 15 + row
        for x in range(x0, x1 + 1):
            c.put(x, y, RED_CLOTH[0] if x == x1 and row < 2 else RED_CLOTH[1] if (x + row) % 3 else RED_CLOTH[2])
        c.put(x0 - 1, y, GOLD[1] if row % 2 else GOLD[2])
    c.put(13, 14, RED_CLOTH[1]); c.put(14, 14, RED_CLOTH[0])
    c.outline()
    g = Canvas()
    g.put(30, 2, HOLY[0]); g.put(29, 3, HOLY[1]); g.put(29, 2, HOLY[0]); g.put(30, 3, HOLY[1])
    return [c], [g]


def nimrod_bow(pull=0):
    """Nimrod's great bow in the vanilla bow's pose: a recurve of dark wood with Babel brick and gold, a red string on
    the up-left chord, drawn into a V toward the archer with the arrow nocked as it is pulled."""
    c = Canvas()
    hi, light, mid, dark, deep = WOOD
    brick = ((236, 164, 132), (200, 108, 76), (150, 70, 46), (100, 42, 26), (56, 20, 12))
    # the stave: an arc bulging down-right, three thick, recurved tips
    arc = []
    for k in range(0, 101):
        a = math.radians(-10 + 110 * k / 100)
        arc.append((round(6.5 + 22 * math.cos(a)), round(6.5 + 22 * math.sin(a))))
    seen = set()
    for x, y in arc:
        if (x, y) in seen: continue
        seen.add((x, y))
        c.put(x, y, light); c.put(x - 1, y, mid) if y > x else c.put(x, y - 1, mid)
        c.put(x + 1, y, dark) if y <= x else c.put(x, y + 1, dark)
        c.put(x + 1, y + 1, deep)
    # the grip bound in brick, gold bands up and down the limbs, gold tips
    for x, y in ((21, 21), (22, 20), (23, 19), (20, 22), (22, 21), (23, 20), (21, 22), (22, 22), (23, 21), (24, 20)): c.put(x, y, brick[1] if x + y <= 42 else brick[2])
    c.put(21, 20, brick[0]); c.put(22, 19, brick[0])
    for x, y in ((27, 12), (28, 12), (27, 13), (28, 13), (12, 27), (13, 27), (12, 28), (13, 28)): c.put(x, y, GOLD[1] if x + y < 40 else GOLD[2])
    for x, y in ((28, 3), (29, 3), (28, 4), (3, 28), (3, 29), (4, 28)): c.put(x, y, GOLD[1])
    c.outline()
    # the string after the outline, so the daylight between it and the stave stays
    v = [(15, 15), (13, 13), (11, 11), (9, 9)][pull]
    c.line(28, 2, v[0], v[1], RED_CLOTH[1]); c.line(v[0], v[1], 2, 28, RED_CLOTH[1])
    if pull:
        n = 9
        c.run(v[0] - 1, v[1] - 1, n, WOOD[2], (-1, -1)); c.run(v[0] - 2, v[1] - 1, n - 1, WOOD[1], (-1, -1))
        tx, ty = v[0] - n - 1, v[1] - n - 1
        for k in range(3): c.put(tx - k, ty - k, GOLD[1] if k else GOLD[0]); c.put(tx - k + 1, ty - k, GOLD[2]); c.put(tx - k, ty - k + 1, GOLD[3])
        for k in range(3): c.put(v[0] + k + 1, v[1] + k - 1, STRING[1]); c.put(v[0] + k - 1, v[1] + k + 1, STRING[1]); c.put(v[0] + k + 2, v[1] + k - 2, STRING[2]); c.put(v[0] + k - 2, v[1] + k + 2, STRING[2])
    return c


def david_sling(spin=0):
    """A wooden grip bound in gold, two braided leather cords with daylight between them, the pouch with its river stone;
    as it is wound the pouch swings up, then down."""
    c = Canvas()
    hi, light, mid, dark, deep = LEATHER
    c.run(3, 28, 6, WOOD[2]); c.run(2, 28, 6, WOOD[1]); c.run(4, 28, 5, WOOD[3]); c.run(1, 28, 5, WOOD[0])
    for i in (1, 4): c.put(2 + i, 28 - i, GOLD[1]); c.put(3 + i, 28 - i, GOLD[2]); c.put(4 + i, 28 - i, GOLD[3])
    c.disc(2, 30, 2, GOLD)
    c.outline()
    px, py = [(24, 7), (8, 5), (26, 22)][spin]
    ends = [((8, 22), (10, 24)), ((6, 21), (9, 22)), ((9, 23), (10, 25))][spin]
    for (ax, ay), k in zip(ends, (0, 1)):
        bx, by = (px - 3 + 5 * k, py + 3) if spin != 1 else (px - 2 + 4 * k, py + 4)
        c.line(ax, ay, bx, by, mid if k == 0 else dark)
        c.line(ax + (1 if k else -1), ay, bx + (1 if k else -1), by, light if k == 0 else mid)  # braided: a second strand
    cup = Canvas()
    for dx in range(-3, 4): cup.put(px + dx, py + 1, light if dx < -1 else mid if dx < 2 else dark)
    for dx in range(-2, 3): cup.put(px + dx, py + 2, mid if dx < 0 else dark)
    for dx in range(-1, 2): cup.put(px + dx, py + 3, deep)
    cup.put(px - 3, py, light); cup.put(px + 3, py, dark); cup.put(px - 4, py, GOLD[1]); cup.put(px + 4, py, GOLD[2])
    cup.outline()
    c.px.update(cup.px)
    c.disc(px, py - 0.5, 1.8, STONE)
    return c


def samson_jawbone():
    """The jawbone of an ass: a thick curve of old bone up-right from the grip, a row of molars standing out of the
    up-left edge, the ramus turning up at the end to the condyle, a leather wrap and gold ring at the grip."""
    c = Canvas()
    hi, light, mid, dark, deep = BONE
    body = [(x, 29 - x) for x in range(5, 23)]
    for i, (x, y) in enumerate(body):
        w = 4 + (i > 8)
        c.put(x, y, light)
        for k in range(1, w): c.put(x + k, y, mid if k < w - 1 else dark)
        c.put(x + w, y + 1, deep); c.put(x + w - 1, y + 1, dark); c.put(x + 1, y - 1, hi)
    # the ramus: a vertical plate rising from the back of the body to the condyle, with the coronoid notch
    for y in range(2, 9):
        x0 = 23 + y // 4
        for k in range(5 - (y < 2)): c.put(x0 + k, y, hi if k == 0 else light if k < 3 else mid if k < 4 else dark)
    for x, y in ((24, 2), (25, 2), (26, 3)): c.put(x, y, mid)  # the notch
    c.put(27, 2, light); c.put(28, 2, light); c.put(29, 3, mid); c.put(28, 3, light); c.put(29, 4, dark)  # the condyle
    # the teeth: molars standing out of the up-left edge, two wide, with dark gaps between
    for i in range(3, 16, 3):
        x, y = body[i]
        for dx, dy in ((0, -2), (1, -2), (0, -3), (1, -3), (-1, -2), (-1, -3)): c.put(x + dx, y + dy, hi if dx >= 0 and dy == -3 else light if dy == -3 else mid)
        c.put(x + 2, y - 2, (54, 44, 34)); c.put(x - 2, y - 2, (54, 44, 34))
    # the grip: leather bound, a gold ring
    for x in range(3, 9):
        for y in range(25, 31):
            if 28 <= x + y <= 34: c.put(x, y, LEATHER[2] if (x + y) % 2 else LEATHER[3])
    for x, y in ((8, 23), (9, 23), (8, 24), (9, 22), (10, 23), (10, 22), (7, 24)): c.put(x, y, GOLD[1] if x + y < 32 else GOLD[2])
    c.outline()
    return [c], []

def gideon_jar():
    """A tall pitcher of painted clay, banded ochre and white, the torch hidden within flaring out of its mouth."""
    base, glows = [], []
    c = Canvas()
    hi, light, mid, dark, deep = CLAY
    rows = [(12, 19), (11, 20), (10, 21), (9, 22), (8, 23), (8, 23), (7, 24), (7, 24), (7, 24), (7, 24), (7, 24), (8, 23), (8, 23), (9, 22), (10, 21), (11, 20), (10, 21)]
    for r, (x0, x1) in enumerate(rows):
        y = 12 + r
        for x in range(x0, x1 + 1):
            t = (x - x0) / max(1, x1 - x0)
            col = hi if t < 0.12 else light if t < 0.3 else mid if t < 0.75 else dark if t < 0.92 else deep
            if r in (4, 5, 10, 11): col = (col[0] + 40, col[1] + 36, col[2] + 10) if t < 0.75 else col  # painted ochre bands
            if r in (7, 8) and 0.2 < t < 0.7 and int(x) % 3 == 0: col = (236, 226, 200)  # a white pattern
            c.put(x, y, col)
    for x in range(11, 21): c.put(x, 11, light if x < 15 else mid); c.put(x, 10, hi if x < 14 else light)  # the lip
    for x, y in ((24, 15), (25, 16), (25, 17), (25, 18), (25, 19), (24, 20)): c.put(x, y, mid); c.put(x + 1, y, dark)  # the handle
    c.put(12, 13, hi); c.put(12, 14, hi)
    c.outline((60, 30, 20))
    base.append(c)
    rand = random.Random(3)
    for f in range(4):
        g = Canvas()
        for y in range(0, 10):
            w = 2 + (y // 2) + rand.randint(0, 1)
            for x in range(15 - w // 2, 16 + w // 2 + 1):
                if rand.random() < 0.88:
                    g.put(x, y, FLAME[0] if y >= 7 and abs(x - 15.5) < 1.5 else FLAME[1] if y >= 5 else FLAME[2] if rand.random() < 0.7 else FLAME[3])
        for _ in range(3):
            x, y = rand.randint(10, 22), rand.randint(0, 6)
            if g.get(x, y) is None: g.put(x, y, FLAME[1])
        glows.append(g)
    return base, glows


def jericho_horn():
    """A great ram's horn in copper: a curved tube from the mouthpiece bottom-left, thickening along its arc to the wide
    bell at the top right, ridged as horn is, bound with gold, the bell rimmed in gold."""
    c = Canvas()
    hi, light, mid, dark, deep = COPPER
    cx, cy, r0 = 2.0, 3.0, 16.5
    for k in range(0, 101):
        a = math.radians(100 - 86 * k / 100)       # from the bottom (mouthpiece) round to the right (bell)
        w = 2 + 6 * k / 100                        # thickness grows toward the bell
        ridge = (k % 12) < 2
        for j in range(int(w) + 1):
            rr = r0 - 1 + j
            x, y = round(cx + rr * math.cos(a)), round(cy + rr * math.sin(a))
            t = j / max(1, w)
            col = hi if t < 0.15 else light if t < 0.4 else mid if t < 0.7 else dark if t < 0.9 else deep
            if ridge: col = light if t < 0.4 else dark
            c.put(x, y, col)
    # gold bands
    for kk in (30, 60):
        a = math.radians(100 - 86 * kk / 100)
        for j in range(int(2 + 6 * kk / 100) + 2):
            rr = r0 - 1 + j
            c.put(round(cx + rr * math.cos(a)), round(cy + rr * math.sin(a)), GOLD[1] if j < 2 else GOLD[2])
    # the bell: a flared mouth at the end of the arc, rimmed with gold, dark inside
    bx, by = round(cx + (r0 + 4) * math.cos(math.radians(14))), round(cy + (r0 + 4) * math.sin(math.radians(14)))
    for dy in range(-6, 7):
        for dx in range(-3, 4):
            d = math.hypot(dx / 2.6, dy / 6.0)
            if d <= 1: c.put(bx + dx, by + dy, (40, 20, 12) if d < 0.62 else light if dx < 0 else mid)
            if 1 < d <= 1.3: c.put(bx + dx, by + dy, GOLD[1] if dy < 0 or dx < 0 else GOLD[2])
    c.put(bx - 1, by - 5, hi)
    # the mouthpiece
    for x, y in ((2, 20), (3, 20), (2, 21), (3, 21), (1, 20), (2, 22)): c.put(x, y, GOLD[2] if y < 22 else GOLD[3])
    c.outline()
    return [c], []

def aspen_stake():
    """A stout aspen stake, bark left on the grip and a leather thong round it, the point whittled to pale facets."""
    c = Canvas()
    hi, light, mid, dark, deep = ASPEN
    c.run(5, 26, 20, light); c.run(4, 26, 20, hi); c.run(6, 26, 19, mid); c.run(7, 26, 18, dark); c.run(3, 26, 19, light)
    for i in range(0, 9):  # the bark on the lower half
        for k, col in ((-2, BARK[1]), (-1, BARK[0]), (0, BARK[1]), (1, BARK[2]), (2, BARK[3])):
            if (i + k) % 3 == 0: col = BARK[2] if k < 1 else BARK[4]
            c.put(5 + i + k, 26 - i, col)
    for i in (3, 4, 7, 8):  # a leather thong
        for k in range(-2, 3): c.put(5 + i + k, 26 - i, LEATHER[2] if k else LEATHER[1])
    # the point: whittled facets, paler
    for i in range(16, 20):
        c.put(5 + i, 26 - i, hi); c.put(6 + i, 26 - i, light); c.put(4 + i, 26 - i, hi)
    c.put(25, 6, hi); c.put(26, 5, hi); c.put(27, 4, hi); c.put(28, 3, light)
    c.outline()
    return [c], []


SPRITES = {}


def all_sprites():
    """name -> (base frames, glow frames, frametime)."""
    if SPRITES: return SPRITES
    for name, fn, ft in (("cherub_sword", cherub_sword, 3), ("archistrategos_sword", archistrategos_sword, None), ("george_spear", george_spear, None),
                         ("samson_jawbone", samson_jawbone, None), ("gideon_jar", gideon_jar, 4), ("war_sword", war_sword, 5),
                         ("jericho_horn", jericho_horn, None), ("aspen_stake", aspen_stake, None)):
        base, glow = fn()
        SPRITES[name] = (base, glow, ft)
    for i in range(4): SPRITES["nimrod_bow" + ("" if i == 0 else "_pulling_%d" % (i - 1))] = ([nimrod_bow(i)], [], None)
    for i in range(3): SPRITES["david_sling" + ("" if i == 0 else "_pulling_%d" % (i - 1))] = ([david_sling(i)], [], None)
    return SPRITES


def write(name, frames, frametime=None):
    os.makedirs(TEX, exist_ok=True)
    meta = os.path.join(TEX, name + ".png.mcmeta")
    if len(frames) == 1:
        frames[0].image().save(os.path.join(TEX, name + ".png"))
        if os.path.exists(meta): os.remove(meta)
        return
    strip = Image.new("RGBA", (N, N * len(frames)), (0, 0, 0, 0))
    for i, f in enumerate(frames): strip.paste(f.image(), (0, N * i))
    strip.save(os.path.join(TEX, name + ".png"))
    with open(meta, "w", encoding="utf-8", newline="\n") as out:
        json.dump({"animation": {"frametime": frametime or 3, "interpolate": False}}, out, indent=2); out.write("\n")


def preview(path, scale=6):
    cells = []
    for name, (base, glow, _) in all_sprites().items():
        frames = max(len(base), len(glow))
        for i in range(frames):
            img = base[min(i, len(base) - 1)].image()
            if glow: img.alpha_composite(glow[min(i, len(glow) - 1)].image())
            cells.append(img)
    cols = 8
    rows = (len(cells) + cols - 1) // cols
    out = Image.new("RGBA", (cols * (N + 2) * scale, rows * (N + 2) * scale), (112, 112, 112, 255))
    for i, img in enumerate(cells):
        out.paste(img.resize((N * scale, N * scale), Image.NEAREST), ((i % cols) * (N + 2) * scale + scale, (i // cols) * (N + 2) * scale + scale))
    out.save(path)


if __name__ == "__main__":
    for name, (base, glow, frametime) in all_sprites().items():
        write(name, base, frametime)
        if glow: write(name + "_glow", glow, frametime)
    if len(sys.argv) > 1:
        preview(sys.argv[1])
