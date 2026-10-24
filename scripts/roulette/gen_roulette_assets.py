"""Draws the punishment roulette's textures: original pixel art drawn here in code, one texel per unit of the machine, in
the manner of vanilla textures (five tones per material, an ordered dither, one-texel outlines).

Run from the repository root: python scripts/roulette/gen_roulette_assets.py
The machine is a kiot in the manner of an iconostasis: three onion domes with eight-pointed crosses, the Eye of God in a
triangle with its radiance on the central drum, carved gilding with vine and grapes, cabochons in the corners, twisted
columns between the reels, a pedestal with seraphim in medallions; the royal doors cover the reels until the spin opens
them. The symbols stand in klejma on gold leaf, like the small scenes round an icon. Everything is shaded as relief: each
shape gets a bevel from its distance field, lit from the upper left, and each texel takes one of its material's tones.

Writes src/main/resources/assets/divineblock/textures/gui/roulette/:
  symbols.png   14 klejma 32x32 in a row, in the order of core/RouletteSymbol
  frame_silver.png, frame_gold.png, frame_hot.png, frame_black.png   the kiot, 176x142 (from machine y -16)
  doors.png     the royal doors, two leaves of 54x60
  lever.png     the lever up and down, two frames of 24x80
  hand.png      the hand of God from a cloud, 32x32
  lamps.png     a lampada of the fury scale, unlit and lit, two frames of 14x12
  banner.png    the ribbon under the machine: crimson, blue (a pardon), black (the ban); 180x28 each, one under another
The light, sparks, stars, feathers, smoke and petals in the game come from the mod's effects atlas (textures/effect/fx.png
through client/fx/FxHud).
"""
import math
import os

import numpy as np
from PIL import Image, ImageDraw
from scipy import ndimage

OUT = os.path.join(os.path.dirname(__file__), "..", "..", "src", "main", "resources", "assets", "divineblock", "textures", "gui", "roulette")
BUILD = os.path.join(os.path.dirname(__file__), "..", "..", "build")
SS = 4  # each texel is decided by the majority of 4x4 samples
BEVEL = 0.5  # the shapes' bevels were given at twice the size
BAYER = np.array([[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]]) / 16.0


def ramp(color):
    """Five tones of a material as in vanilla textures: a deep warm shadow, a shadow, the colour, a light and a highlight."""
    c = np.asarray(color, dtype=float)
    deep = c * np.array([0.5, 0.4, 0.38])
    dark = c * np.array([0.76, 0.66, 0.6])
    light = c + (1 - c) * np.array([0.35, 0.32, 0.22])
    high = c + (1 - c) * np.array([0.72, 0.68, 0.55])
    return np.clip(np.array([deep, dark, c, light, high]), 0, 1)
LIGHT = np.array([-0.55, -0.7, 0.45])
LIGHT = LIGHT / np.linalg.norm(LIGHT)


# ---- The relief toolkit. ----

class Canvas:
    """An RGBA picture built from shaded layers."""

    def __init__(self, w, h, coord=0.5):
        """coord: texels per unit of the drawing's coordinates (the shapes were designed at twice the size)."""
        self.w, self.h, self.coord = w, h, coord
        self.rgb = np.zeros((h, w, 3))
        self.a = np.zeros((h, w))

    def mask(self, draw):
        """A hard pixel mask (0 or 1) drawn by draw(ImageDraw, k), k being texels per unit of its coordinates."""
        img = Image.new("L", (self.w * SS, self.h * SS), 0)
        scale = SS * self.coord
        draw(ImageDraw.Draw(img), int(scale) if scale == int(scale) else scale)
        small = np.asarray(img.resize((self.w, self.h), Image.BOX), dtype=float) / 255
        return (small >= 0.5).astype(float)

    def layer(self, m, color, bevel=3.0, gloss=0.5, depth=1.0, flat=False, alpha=1.0):
        """Lays the shape m in color, raised by a bevel of `bevel` pixels: lit on the upper left, shaded on the lower right."""
        color = np.array(color[:3], dtype=float) / 255
        bevel = bevel * self.coord * 2 * BEVEL
        if flat or bevel <= 0:
            shade = np.ones_like(m)[..., None] * color
        else:
            inside = m > 0.5
            dist = ndimage.distance_transform_edt(inside)
            height = np.clip(dist / bevel, 0, 1)
            height = np.sin(height * math.pi / 2) * depth
            gy, gx = np.gradient(ndimage.gaussian_filter(height, 0.45))
            nx, ny, nz = -gx * bevel, -gy * bevel, np.ones_like(gx)
            norm = np.sqrt(nx * nx + ny * ny + nz * nz)
            nx, ny, nz = nx / norm, ny / norm, nz / norm
            diffuse = np.clip(nx * LIGHT[0] + ny * LIGHT[1] + nz * LIGHT[2], 0, 1)
            rz = 2 * diffuse * nz - LIGHT[2]
            spec = np.clip(rz, 0, 1) ** 14 * gloss
            # Pixel art: the light picks one of five tones of the material, with an ordered dither between neighbours.
            # A flat face is exactly the base tone; slopes to the light climb, slopes away fall.
            level = np.clip(2 + (diffuse - LIGHT[2]) * 5 + spec * 3, 0, 4.4)
            yy, xx = np.indices(m.shape)
            level = np.clip(np.floor(level + (BAYER[yy % 4, xx % 4] - 0.5) * 0.9 + 0.5), 0, 4).astype(int)
            shade = ramp(color)[level]
        k = np.clip(m * alpha, 0, 1)[..., None]
        self.rgb = self.rgb * (1 - k) + np.clip(shade, 0, 1) * k
        self.a = np.maximum(self.a, np.clip(m * alpha, 0, 1))
        return self

    def outline(self, color, width=1.0):
        """A dark line round everything drawn so far."""
        solid = self.a > 0.5
        grown = ndimage.binary_dilation(solid, iterations=max(1, int(round(width))))
        ring = grown & ~solid
        c = np.array(color[:3], dtype=float) / 255
        self.rgb[ring] = c
        self.a[ring] = 1
        return self

    def shadow(self, dx=1, dy=1, strength=0.55):
        """A hard one-texel drop shadow under everything drawn so far."""
        sh = np.zeros_like(self.a)
        sh[dy:, dx:] = self.a[:self.h - dy, :self.w - dx]
        keep = (sh > 0.5) & (self.a < 0.5)
        self.rgb[keep] = 0
        self.a[keep] = strength
        return self

    def image(self):
        out = np.dstack([np.clip(self.rgb, 0, 1) * 255, np.clip(self.a, 0, 1) * 255]).astype(np.uint8)
        return Image.fromarray(out, "RGBA")


def poly(points, scale):
    return [(x * scale, y * scale) for x, y in points]


def ellipse(d, k, box, fill=255):
    x0, y0, x1, y1 = box
    d.ellipse([x0 * k, y0 * k, x1 * k, y1 * k], fill=fill)


def rect(d, k, box, fill=255, radius=0):
    x0, y0, x1, y1 = box
    if radius:
        d.rounded_rectangle([x0 * k, y0 * k, x1 * k, y1 * k], radius=radius * k, fill=fill)
    else:
        d.rectangle([x0 * k, y0 * k, x1 * k, y1 * k], fill=fill)


GOLD = (222, 176, 74)
GOLD_LIGHT = (246, 214, 128)
GOLD_DEEP = (176, 128, 46)
PALETTES = {
    "silver": dict(metal=(196, 202, 214), deep=(140, 148, 164), gem=(70, 110, 220), velvet=(30, 38, 66), leaf=(214, 220, 232), accent=(150, 190, 255)),
    "gold": dict(metal=GOLD, deep=GOLD_DEEP, gem=(190, 24, 40), velvet=(92, 16, 22), leaf=(232, 196, 104), accent=(255, 210, 110)),
    "hot": dict(metal=(250, 190, 90), deep=(214, 110, 40), gem=(255, 70, 30), velvet=(120, 18, 10), leaf=(255, 214, 120), accent=(255, 120, 50)),
    "black": dict(metal=(70, 58, 60), deep=(40, 32, 34), gem=(170, 10, 24), velvet=(18, 10, 12), leaf=(110, 70, 60), accent=(210, 30, 40)),
}


# ---- The klejma (symbols). ----

S = 32  # texels; the designs below are in 64ths


def klejmo(paint, black=False):
    """A small gold-leaf field with a dark-red rule and a thin gilded edge, and the scene painted into it."""
    c = Canvas(S, S)
    c.layer(c.mask(lambda d, k: rect(d, k, (0, 0, 64, 64), radius=4)), (90, 60, 24), bevel=2, gloss=0.3)
    c.layer(c.mask(lambda d, k: rect(d, k, (3, 3, 61, 61), radius=4)), (120, 24, 24), bevel=1.5, gloss=0.2)
    leaf = (60, 44, 40) if black else (226, 184, 92)
    c.layer(c.mask(lambda d, k: rect(d, k, (6, 6, 58, 58), radius=3)), leaf, bevel=6, depth=0.4, gloss=0.25)
    paint(c)
    return c.image()


def p_lightning(c):
    bolt = [(36, 8), (16, 34), (28, 34), (20, 56), (48, 24), (35, 24), (44, 8)]
    c.layer(c.mask(lambda d, k: d.polygon(poly(bolt, k), fill=255)), (255, 236, 90), bevel=4, gloss=0.5)
    c.outline((70, 30, 0))


def p_flame(c):
    outer = [(32, 6), (41, 20), (48, 16), (52, 34), (47, 52), (32, 58), (17, 52), (12, 34), (17, 18), (23, 26)]
    mid = [(32, 18), (39, 31), (43, 28), (45, 40), (40, 52), (24, 52), (19, 40), (22, 30), (26, 34)]
    core = [(32, 32), (37, 42), (35, 52), (29, 52), (27, 42)]
    c.layer(c.mask(lambda d, k: d.polygon(poly(outer, k), fill=255)), (214, 44, 24), bevel=5, gloss=0.4)
    c.layer(c.mask(lambda d, k: d.polygon(poly(mid, k), fill=255)), (255, 140, 30), bevel=4, gloss=0.5)
    c.layer(c.mask(lambda d, k: d.polygon(poly(core, k), fill=255)), (255, 236, 140), bevel=3, gloss=0.6)
    c.outline((70, 10, 4))


def p_meteor(c):
    c.layer(c.mask(lambda d, k: d.polygon(poly([(58, 4), (36, 26), (26, 18)], k), fill=255)), (255, 130, 30), bevel=3)
    c.layer(c.mask(lambda d, k: d.polygon(poly([(60, 12), (40, 34), (48, 42)], k), fill=255)), (255, 196, 70), bevel=3)
    c.layer(c.mask(lambda d, k: ellipse(d, k, (8, 28, 38, 58))), (108, 80, 66), bevel=9, gloss=0.3)
    for x, y, r in ((16, 38, 3), (26, 48, 2.5), (28, 36, 2), (16, 50, 2)):
        c.layer(c.mask(lambda d, k, x=x, y=y, r=r: ellipse(d, k, (x - r, y - r, x + r, y + r))), (66, 46, 38), bevel=1.5, depth=-0.6)
    c.layer(c.mask(lambda d, k: d.arc([10 * k, 30 * k, 36 * k, 56 * k], 210, 300, fill=255, width=2 * k)), (255, 160, 60), flat=True)
    c.outline((40, 18, 8))


def p_crack(c):
    c.layer(c.mask(lambda d, k: rect(d, k, (8, 18, 56, 56), radius=2)), (130, 96, 64), bevel=4, gloss=0.2)
    c.layer(c.mask(lambda d, k: rect(d, k, (8, 14, 56, 22), radius=2)), (96, 150, 58), bevel=2.5, gloss=0.3)
    for x, y in ((14, 30), (44, 28), (18, 46), (48, 48), (30, 40)):
        c.layer(c.mask(lambda d, k, x=x, y=y: ellipse(d, k, (x, y, x + 4, y + 3))), (160, 150, 140), bevel=1.5)
    crack = [(31, 14), (27, 26), (35, 32), (25, 42), (33, 50), (29, 58)]
    c.layer(c.mask(lambda d, k: d.line(poly(crack, k), fill=255, width=4 * k)), (20, 10, 6), bevel=2, depth=-0.8)
    c.layer(c.mask(lambda d, k: d.line(poly([(35, 32), (45, 38)], k), fill=255, width=2 * k)), (20, 10, 6), flat=True)
    c.outline((40, 26, 14))


def p_wave(c):
    body = [(6, 58), (6, 42), (12, 30), (24, 18), (38, 12), (50, 14), (58, 22), (56, 30), (48, 26), (40, 28), (38, 36), (44, 44), (58, 46), (58, 58)]
    foam = [(38, 12), (50, 14), (58, 22), (56, 30), (48, 24), (40, 24), (34, 30), (30, 22)]
    c.layer(c.mask(lambda d, k: d.polygon(poly(body, k), fill=255)), (40, 104, 196), bevel=6, gloss=0.7)
    c.layer(c.mask(lambda d, k: d.polygon(poly(foam, k), fill=255)), (236, 246, 255), bevel=3, gloss=0.6)
    c.layer(c.mask(lambda d, k: d.line(poly([(10, 50), (20, 42), (28, 46)], k), fill=255, width=2 * k)), (120, 186, 240), flat=True)
    c.outline((12, 30, 70))


def p_locust(c):
    c.layer(c.mask(lambda d, k: ellipse(d, k, (10, 28, 46, 42))), (116, 160, 46), bevel=5, gloss=0.5)
    c.layer(c.mask(lambda d, k: ellipse(d, k, (40, 22, 56, 40))), (136, 182, 58), bevel=5, gloss=0.5)
    c.layer(c.mask(lambda d, k: d.polygon(poly([(14, 30), (44, 28), (38, 20), (18, 22)], k), fill=255)), (186, 206, 110), bevel=3, gloss=0.6)
    c.layer(c.mask(lambda d, k: d.polygon(poly([(24, 36), (12, 16), (18, 14), (32, 32)], k), fill=255)), (96, 140, 36), bevel=3)
    c.layer(c.mask(lambda d, k: d.line(poly([(14, 16), (6, 54)], k), fill=255, width=3 * k)), (80, 120, 30), bevel=1.5)
    for a, b in (((36, 42), (34, 54)), ((44, 40), (48, 54))):
        c.layer(c.mask(lambda d, k, a=a, b=b: d.line(poly([a, b], k), fill=255, width=2 * k)), (80, 120, 30), flat=True)
    c.layer(c.mask(lambda d, k: d.line(poly([(50, 24), (36, 8), (24, 4)], k), fill=255, width=2 * k)), (60, 84, 20), flat=True)
    c.layer(c.mask(lambda d, k: ellipse(d, k, (48, 28, 52, 32))), (20, 20, 10), bevel=1)
    c.outline((26, 40, 8))


def p_skull(c):
    c.layer(c.mask(lambda d, k: ellipse(d, k, (12, 8, 52, 46))), (236, 228, 204), bevel=10, gloss=0.5)
    c.layer(c.mask(lambda d, k: rect(d, k, (20, 38, 44, 56), radius=3)), (226, 218, 194), bevel=5, gloss=0.4)
    for box in ((17, 22, 29, 34), (35, 22, 47, 34)):
        c.layer(c.mask(lambda d, k, b=box: ellipse(d, k, b)), (36, 20, 20), bevel=4, depth=-1)
    c.layer(c.mask(lambda d, k: d.polygon(poly([(32, 34), (28, 42), (36, 42)], k), fill=255)), (60, 40, 36), bevel=2, depth=-1)
    for x in (24, 29, 34, 39):
        c.layer(c.mask(lambda d, k, x=x: d.line(poly([(x, 47), (x, 56)], k), fill=255, width=1 * k)), (100, 88, 76), flat=True)
    for x in (22, 40):
        c.layer(c.mask(lambda d, k, x=x: ellipse(d, k, (x, 26, x + 3, 29))), (220, 40, 30), flat=True)
    c.outline((40, 30, 24))


def p_rider(c):
    head = [(28, 60), (30, 48), (32, 36), (24, 36), (16, 42), (8, 40), (6, 32), (18, 20), (26, 10), (30, 4), (36, 8), (44, 16), (52, 36), (52, 60)]
    c.layer(c.mask(lambda d, k: d.polygon(poly(head, k), fill=255)), (218, 222, 200), bevel=7, gloss=0.4)
    c.layer(c.mask(lambda d, k: d.polygon(poly([(36, 8), (42, 2), (42, 12)], k), fill=255)), (218, 222, 200), bevel=2)
    c.layer(c.mask(lambda d, k: d.polygon(poly([(44, 16), (50, 14), (56, 26), (58, 44), (58, 60), (52, 60), (52, 36)], k), fill=255)), (44, 36, 44), bevel=4)
    c.layer(c.mask(lambda d, k: ellipse(d, k, (24, 16, 30, 21))), (220, 30, 30), bevel=1.5, gloss=0.9)
    c.layer(c.mask(lambda d, k: d.line(poly([(18, 28), (40, 44)], k), fill=255, width=2 * k)), (120, 86, 40), flat=True)
    c.outline((20, 18, 16))


def p_scroll(c):
    c.layer(c.mask(lambda d, k: rect(d, k, (14, 10, 50, 52))), (238, 216, 164), bevel=4, gloss=0.2)
    for box in ((8, 6, 20, 18), (44, 44, 56, 56)):
        c.layer(c.mask(lambda d, k, b=box: ellipse(d, k, b)), (206, 178, 120), bevel=4)
    for y in (20, 26, 32, 38):
        c.layer(c.mask(lambda d, k, y=y: d.line(poly([(20, y), (44 if y != 38 else 34, y)], k), fill=255, width=1 * k)), (100, 60, 40), flat=True)
    c.layer(c.mask(lambda d, k: ellipse(d, k, (34, 36, 48, 50))), (176, 20, 22), bevel=4, gloss=0.7)
    c.outline((60, 40, 20))


def p_anvil(c):
    shape = [(4, 18), (52, 18), (60, 22), (52, 28), (40, 28), (36, 36), (44, 44), (48, 54), (16, 54), (20, 44), (28, 36), (24, 28), (12, 28)]
    c.layer(c.mask(lambda d, k: d.polygon(poly(shape, k), fill=255)), (96, 98, 108), bevel=5, gloss=0.9)
    c.layer(c.mask(lambda d, k: rect(d, k, (14, 54, 50, 58))), (66, 68, 76), bevel=2)
    c.outline((16, 16, 20))


def p_tower(c):
    tiers = [(8, 50, 56, 58), (14, 40, 50, 50), (20, 30, 44, 40), (26, 20, 38, 30), (30, 10, 34, 20)]
    for i, box in enumerate(tiers):
        c.layer(c.mask(lambda d, k, b=box: rect(d, k, b)), (200 - i * 8, 158 - i * 6, 100 - i * 4), bevel=3, gloss=0.3)
    for x0, y0, x1, y1 in tiers[:-1]:
        for x in range(x0 + 4, x1 - 2, 7):
            c.layer(c.mask(lambda d, k, x=x, y=y0: rect(d, k, (x, y + 3, x + 2, y + 6))), (70, 46, 26), bevel=1, depth=-1)
    c.layer(c.mask(lambda d, k: d.line(poly([(54, 8), (44, 22), (48, 24)], k), fill=255, width=2 * k)), (255, 230, 70), flat=True)
    c.outline((50, 34, 18))


def p_seven(c):
    seven = [(8, 8), (56, 8), (56, 20), (34, 58), (18, 58), (40, 20), (8, 20)]
    c.layer(c.mask(lambda d, k: d.polygon(poly([(6, 6), (58, 6), (58, 21), (35, 60), (16, 60), (38, 22), (6, 22)], k), fill=255)), (176, 18, 32), bevel=3, gloss=0.8)
    inner = [(12, 11), (52, 11), (52, 19), (31, 55), (22, 55), (43, 17), (12, 17)]
    c.layer(c.mask(lambda d, k: d.polygon(poly(inner, k), fill=255)), (236, 168, 34), bevel=6, gloss=0.5, depth=1.2)
    c.layer(c.mask(lambda d, k: d.polygon(poly([(14, 12), (50, 12), (46, 15), (16, 15)], k), fill=255)), (255, 246, 190), flat=True, alpha=0.8)
    c.layer(c.mask(lambda d, k: d.polygon(poly([(48, 16), (51, 13), (31, 53), (29, 50)], k), fill=255)), (255, 236, 160), flat=True, alpha=0.6)
    c.outline((70, 6, 8))


def p_gate(c):
    c.layer(c.mask(lambda d, k: (d.rectangle([8 * k, 20 * k, 56 * k, 58 * k], fill=255), d.pieslice([8 * k, 2 * k, 56 * k, 38 * k], 180, 360, fill=255))), (54, 50, 58), bevel=4, gloss=0.4)
    for x in range(13, 56, 7):
        c.layer(c.mask(lambda d, k, x=x: d.line(poly([(x, 10), (x, 58)], k), fill=255, width=2 * k)), (120, 116, 128), bevel=1, gloss=0.6)
    c.layer(c.mask(lambda d, k: d.line(poly([(8, 30), (56, 30)], k), fill=255, width=2 * k)), (120, 116, 128), bevel=1)
    c.layer(c.mask(lambda d, k: d.line(poly([(32, 2), (32, 58)], k), fill=255, width=3 * k)), (24, 22, 26), flat=True)
    c.layer(c.mask(lambda d, k: d.arc([24 * k, 24 * k, 40 * k, 40 * k], 180, 360, fill=255, width=3 * k)), (180, 180, 190), bevel=1.5, gloss=0.8)
    c.layer(c.mask(lambda d, k: rect(d, k, (22, 32, 42, 48), radius=2)), (176, 140, 50), bevel=3, gloss=0.8)
    c.layer(c.mask(lambda d, k: ellipse(d, k, (30, 36, 34, 41))), (20, 16, 10), bevel=1, depth=-1)
    c.outline((10, 8, 12))


def p_dove(c):
    c.layer(c.mask(lambda d, k: ellipse(d, k, (14, 26, 46, 46))), (248, 250, 255), bevel=7, gloss=0.4)
    c.layer(c.mask(lambda d, k: ellipse(d, k, (38, 18, 54, 34))), (248, 250, 255), bevel=5, gloss=0.4)
    c.layer(c.mask(lambda d, k: d.polygon(poly([(16, 32), (4, 24), (8, 38)], k), fill=255)), (228, 234, 244), bevel=2)
    c.layer(c.mask(lambda d, k: d.polygon(poly([(22, 30), (28, 6), (40, 8), (36, 30)], k), fill=255)), (224, 230, 242), bevel=4, gloss=0.3)
    c.layer(c.mask(lambda d, k: d.polygon(poly([(54, 24), (62, 26), (54, 28)], k), fill=255)), (240, 170, 60), bevel=1)
    c.layer(c.mask(lambda d, k: ellipse(d, k, (46, 22, 49, 25))), (20, 20, 30), flat=True)
    c.layer(c.mask(lambda d, k: d.line(poly([(52, 30), (60, 40)], k), fill=255, width=2 * k)), (80, 130, 40), flat=True)
    for x, y in ((56, 36), (59, 38)):
        c.layer(c.mask(lambda d, k, x=x, y=y: ellipse(d, k, (x - 2, y - 1, x + 2, y + 2))), (110, 170, 60), bevel=1)
    c.outline((70, 80, 100))


SYMBOLS = [p_lightning, p_flame, p_meteor, p_crack, p_wave, p_locust, p_skull, p_rider, p_scroll, p_anvil, p_tower, p_seven, p_gate, p_dove]


# ---- The kiot. ----

FW, FH = 176, 142  # the texture, one texel per unit; machine y = 0 lies 16 texels down (the crest rises above it)


def Y(y):
    """Machine y to texture units before the factor 2."""
    return y + 16


def onion(cx, base, width, height):
    """An onion dome's outline: a bulb swelling out of the drum and narrowing into a point."""
    pts = []
    for i in range(41):
        u = i / 40
        y = base - u * height
        swell = math.sin(min(1, u * 1.35) * math.pi) ** 0.8
        taper = (1 - u) ** 0.9
        r = width / 2 * (0.55 * taper + 0.75 * swell * taper + 0.05)
        pts.append((cx + r, y))
    return pts + [(2 * cx - x, y) for x, y in reversed(pts)]


def cross8(cx, top, size):
    """The eight-pointed Orthodox cross: the title bar, the long bar, the slanted foot (its right end lower, as on icons)."""
    s = size
    return [
        [(cx - s * 0.08, top), (cx + s * 0.08, top), (cx + s * 0.08, top + s), (cx - s * 0.08, top + s)],
        [(cx - s * 0.2, top + s * 0.12), (cx + s * 0.2, top + s * 0.12), (cx + s * 0.2, top + s * 0.2), (cx - s * 0.2, top + s * 0.2)],
        [(cx - s * 0.36, top + s * 0.3), (cx + s * 0.36, top + s * 0.3), (cx + s * 0.36, top + s * 0.4), (cx - s * 0.36, top + s * 0.4)],
        [(cx - s * 0.26, top + s * 0.68), (cx + s * 0.26, top + s * 0.8), (cx + s * 0.26, top + s * 0.88), (cx - s * 0.26, top + s * 0.76)],
    ]


def frame(name):
    p = PALETTES[name]
    c = Canvas(FW, FH, coord=0.5)

    def shape(fn):
        return c.mask(lambda d, k: fn(d, k * 2))

    def rays(d, k):
        cx, cy = 88 * k, Y(4) * k
        for i in range(24):
            a = i * math.pi / 12
            r1, r2 = 10 * k, (26 if i % 2 == 0 else 19) * k
            w = 0.07
            d.polygon([(cx + math.cos(a - w) * r1, cy + math.sin(a - w) * r1), (cx + math.cos(a) * r2, cy + math.sin(a) * r2),
                       (cx + math.cos(a + w) * r1, cy + math.sin(a + w) * r1)], fill=255)
    c.layer(shape(rays), p["leaf"], bevel=1.5, gloss=0.6, alpha=0.9)
    for cx, drum_w, drum_top, bulb_w, bulb_h, cross_size in ((88, 30, 10, 34, 22, 14), (30, 16, 15, 18, 13, 9), (146, 16, 15, 18, 13, 9)):
        c.layer(shape(lambda d, k, cx=cx, w=drum_w, t=drum_top: rect(d, k, (cx - w / 2, Y(t), cx + w / 2, Y(24)))), p["metal"], bevel=3, gloss=0.5)
        for wx in ((cx - drum_w / 3, cx + drum_w / 3) if drum_w > 20 else (cx,)):
            c.layer(shape(lambda d, k, wx=wx, t=drum_top: (rect(d, k, (wx - 1.6, Y(t + 4), wx + 1.6, Y(22))),
                                                            d.pieslice([(wx - 1.6) * k, Y(t + 2.4) * k, (wx + 1.6) * k, Y(t + 5.6) * k], 180, 360, fill=255))),
                    (30, 22, 40), bevel=1.5, depth=-1)
        c.layer(shape(lambda d, k, cx=cx, t=drum_top, bw=bulb_w, bh=bulb_h: d.polygon(poly(onion(cx, Y(t) + 0.5, bw, bh), k), fill=255)), p["metal"], bevel=6, gloss=0.9)
        for f in (-0.25, 0.25):
            c.layer(shape(lambda d, k, cx=cx, t=drum_top, bw=bulb_w, bh=bulb_h, f=f: d.line([((cx + f * bw * 0.6) * k, Y(t) * k),
                                                                                               ((cx + f * bw * 0.25) * k, Y(t - bh * 0.75) * k), (cx * k, Y(t - bh) * k)],
                                                                                              fill=255, width=k)), p["deep"], flat=True, alpha=0.7)
        top = drum_top - bulb_h - cross_size + 1
        for bar in cross8(cx, Y(top), cross_size):
            c.layer(shape(lambda d, k, bar=bar: d.polygon(poly(bar, k), fill=255)), GOLD_LIGHT if name != "black" else p["metal"], bevel=1.2, gloss=0.9)
    # The Eye of God in a triangle on the central drum (the light of the final comes from it).
    tri = [(88, Y(12.5)), (81.5, Y(23)), (94.5, Y(23))]
    c.layer(shape(lambda d, k: d.polygon(poly(tri, k), fill=255)), GOLD_LIGHT if name != "black" else (150, 40, 40), bevel=1.5, gloss=0.9)
    c.layer(shape(lambda d, k: ellipse(d, k, (85, Y(17.4), 91, Y(20.6)))), (250, 246, 236), bevel=1, gloss=0.4)
    c.layer(shape(lambda d, k: ellipse(d, k, (86.9, Y(17.9), 89.1, Y(20.1)))), (40, 60, 120), bevel=0.8, gloss=1.0)
    # Candles on the top edge, three on each side: wax in a gilded socket (the game draws their flames).
    for cx in (48, 56, 64, 112, 120, 128):
        c.layer(shape(lambda d, k, cx=cx: rect(d, k, (cx - 0.9, Y(13), cx + 0.9, Y(23)))), (244, 230, 196), bevel=0.8, gloss=0.4)
        c.layer(shape(lambda d, k, cx=cx: rect(d, k, (cx - 2, Y(22.5), cx + 2, Y(24.5)))), p["metal"], bevel=1, gloss=0.7)
    # The body.
    c.layer(shape(lambda d, k: rect(d, k, (0, Y(24), 176, Y(112)), radius=3)), p["deep"], bevel=2, gloss=0.3)
    c.layer(shape(lambda d, k: rect(d, k, (1, Y(25), 175, Y(111)), radius=3)), p["metal"], bevel=4, gloss=0.8)
    c.layer(shape(lambda d, k: rect(d, k, (7, Y(31), 169, Y(105)), radius=2)), p["deep"], bevel=1.5, depth=-1)
    c.layer(shape(lambda d, k: rect(d, k, (8, Y(32), 168, Y(104)), radius=2)), p["velvet"], bevel=3, depth=0.3, gloss=0.1)

    def damask(d, k):
        for yy in range(36, 102, 8):
            for xx in range(12 + (yy // 8 % 2) * 4, 166, 8):
                d.polygon(poly([(xx, Y(yy - 2)), (xx + 2, Y(yy)), (xx, Y(yy + 2)), (xx - 2, Y(yy))], k), fill=255)
    c.layer(shape(damask), tuple(int(v * 0.7) for v in p["velvet"]), flat=True, alpha=0.6)

    def vine(d, k, y0):
        pts = [(x, Y(y0 + 1.3 * math.sin(x / 7.5))) for x in np.arange(10, 167, 0.5)]
        d.line(poly(pts, k), fill=255, width=max(1, int(0.9 * k)))
        for x in range(14, 164, 15):
            yy = y0 + 1.3 * math.sin(x / 7.5)
            d.ellipse([(x - 1.6) * k, Y(yy - 2.6) * k, (x + 1.6) * k, Y(yy - 0.2) * k], fill=255)

    def grapes(d, k, y0):
        for x in range(21, 160, 15):
            yy = y0 + 1.3 * math.sin(x / 7.5) + 1
            for gx, gy in ((0, 0), (1.3, 0), (0.65, 1.1), (-0.65, 1.1), (1.95, 1.1), (0.65, 2.2)):
                d.ellipse([(x + gx - 0.75) * k, Y(yy + gy - 0.75) * k, (x + gx + 0.75) * k, Y(yy + gy + 0.75) * k], fill=255)
    for y0 in (28, 108):
        c.layer(shape(lambda d, k, y0=y0: vine(d, k, y0)), p["deep"], bevel=0.8, gloss=0.6)
        c.layer(shape(lambda d, k, y0=y0: grapes(d, k, y0 - 1.6)), (110, 40, 90) if name != "black" else (90, 10, 20), bevel=0.7, gloss=0.9)
    for gx, gy in ((4, 28), (172, 28), (4, 108), (172, 108)):
        c.layer(shape(lambda d, k, gx=gx, gy=gy: ellipse(d, k, (gx - 3.2, Y(gy - 3.2), gx + 3.2, Y(gy + 3.2)))), p["metal"], bevel=1.5, gloss=0.7)
        c.layer(shape(lambda d, k, gx=gx, gy=gy: ellipse(d, k, (gx - 2.2, Y(gy - 2.2), gx + 2.2, Y(gy + 2.2)))), p["gem"], bevel=2.2, gloss=1.4, depth=1.3)
    for gy in range(40, 104, 12):
        for gx in (4, 172):
            c.layer(shape(lambda d, k, gx=gx, gy=gy: ellipse(d, k, (gx - 1.6, Y(gy - 1.6), gx + 1.6, Y(gy + 1.6)))), p["gem"], bevel=1.2, gloss=1.2)
    # The cartouche for the title on the top band.
    c.layer(shape(lambda d, k: rect(d, k, (58, Y(25.2), 118, Y(32.4)), radius=2)), p["deep"], bevel=1.2, depth=-0.6)
    c.layer(shape(lambda d, k: rect(d, k, (59, Y(26), 117, Y(31.6)), radius=2)), tuple(int(v * 0.55) for v in p["velvet"]), bevel=1, gloss=0.1)
    # The fury scale's niche on the left, the lever's on the right.
    c.layer(shape(lambda d, k: (rect(d, k, (10, Y(40), 28, Y(100)), radius=2), d.pieslice([10 * k, Y(33) * k, 28 * k, Y(47) * k], 180, 360, fill=255))), p["deep"], bevel=1.5, depth=-1)
    c.layer(shape(lambda d, k: (rect(d, k, (11, Y(41), 27, Y(99)), radius=2), d.pieslice([11 * k, Y(34) * k, 27 * k, Y(48) * k], 180, 360, fill=255))),
            tuple(int(v * 0.5) for v in p["velvet"]), bevel=2, depth=-0.5)
    c.layer(shape(lambda d, k: rect(d, k, (148, Y(58), 166, Y(80)), radius=2)), p["deep"], bevel=1.5, depth=-1)
    # The window's moulding with twisted columns between the reels; the window itself stays open.
    l, t, r, b = 34, 38, 142, 98
    c.layer(shape(lambda d, k: rect(d, k, (l - 5, Y(t - 5), r + 5, Y(b + 5)), radius=3)), p["metal"], bevel=3, gloss=0.9)
    c.layer(shape(lambda d, k: rect(d, k, (l - 2, Y(t - 2), r + 2, Y(b + 2)), radius=1)), p["deep"], bevel=1, depth=-1)
    hole = shape(lambda d, k: rect(d, k, (l, Y(t), r, Y(b))))
    c.a = c.a * (1 - hole)
    for x in (l + 34, l + 71):
        c.layer(shape(lambda d, k, x=x: d.rectangle([x * k, Y(t) * k, (x + 3) * k, Y(b) * k], fill=255)), p["metal"], bevel=2, gloss=0.9)

        def twist(d, k, x=x):
            for yy in np.arange(t + 1, b, 3.0):
                d.line([(x * k, Y(yy) * k), ((x + 3) * k, Y(yy + 1.6) * k)], fill=255, width=max(1, int(0.6 * k)))
        c.layer(shape(twist), p["deep"], flat=True, alpha=0.8)
        for cap in (t, b - 3):
            c.layer(shape(lambda d, k, x=x, cap=cap: rect(d, k, (x - 1, Y(cap), x + 4, Y(cap + 3)))), p["metal"], bevel=1, gloss=0.8)
    mid = (t + b) / 2
    c.layer(shape(lambda d, k: d.polygon(poly([(l - 9, Y(mid - 3)), (l - 5, Y(mid)), (l - 9, Y(mid + 3))], k), fill=255)), p["accent"], bevel=1, gloss=0.8)
    c.layer(shape(lambda d, k: d.polygon(poly([(r + 9, Y(mid - 3)), (r + 5, Y(mid)), (r + 9, Y(mid + 3))], k), fill=255)), p["accent"], bevel=1, gloss=0.8)
    # The pedestal: two steps, a dark plaque for the running psalm, seraphim in medallions at its ends.
    c.layer(shape(lambda d, k: d.polygon(poly([(10, Y(112)), (166, Y(112)), (160, Y(119)), (16, Y(119))], k), fill=255)), p["metal"], bevel=2.5, gloss=0.8)
    c.layer(shape(lambda d, k: d.polygon(poly([(16, Y(119)), (160, Y(119)), (156, Y(126)), (20, Y(126))], k), fill=255)), p["deep"], bevel=2, gloss=0.5)
    c.layer(shape(lambda d, k: rect(d, k, (40, Y(113.5), 136, Y(122.5)), radius=1)), (20, 12, 10), bevel=1, depth=-1)
    for mx in (26, 150):
        c.layer(shape(lambda d, k, mx=mx: ellipse(d, k, (mx - 7, Y(112.5), mx + 7, Y(126.5)))), p["metal"], bevel=2, gloss=0.8)
        c.layer(shape(lambda d, k, mx=mx: ellipse(d, k, (mx - 5.5, Y(114), mx + 5.5, Y(125)))), (226, 184, 92), bevel=2, depth=0.4)
        seraph(c, shape, mx, Y(119.5))
    c.outline(tuple(int(v * 0.35) for v in p["deep"]))
    c.shadow(1, 1, 0.5)
    if name == "hot":
        # A one-texel rim of red-hot light round the kiot.
        solid = c.a > 0.3
        ring = ndimage.binary_dilation(solid) & ~solid
        c.rgb[ring] = np.array([1.0, 0.36, 0.1])
        c.a[ring] = 0.85
    return c.image()


def seraph(c, shape, cx, cy):
    """A seraph: a face with a nimbus amid six red wings (two above, two spread, two below), as on icons."""
    for a in (-60, -120, -10, -170, 40, 140):
        rad = math.radians(a)
        c.layer(shape(lambda d, k, rad=rad: d.polygon(poly([(cx, cy), (cx + math.cos(rad - 0.35) * 5.2, cy + math.sin(rad - 0.35) * 5.2),
                                                             (cx + math.cos(rad) * 6.2, cy + math.sin(rad) * 6.2),
                                                             (cx + math.cos(rad + 0.35) * 5.2, cy + math.sin(rad + 0.35) * 5.2)], k), fill=255)),
                (200, 40, 34), bevel=0.8, gloss=0.5)
    c.layer(shape(lambda d, k: ellipse(d, k, (cx - 2.6, cy - 2.6, cx + 2.6, cy + 2.6))), (250, 214, 110), bevel=0.8, gloss=0.8)
    c.layer(shape(lambda d, k: ellipse(d, k, (cx - 1.6, cy - 1.7, cx + 1.6, cy + 1.7))), (236, 196, 160), bevel=0.8, gloss=0.3)


def doors():
    """The royal doors: two gilded leaves with a lattice over crimson, a medallion with a cross on each, the crown arch
    sloping to the middle where they meet."""
    out = Image.new("RGBA", (108, 60), (0, 0, 0, 0))
    for leaf in range(2):
        c = Canvas(54, 60)
        c.layer(c.mask(lambda d, k: rect(d, k, (1, 1, 107, 119), radius=2)), GOLD_DEEP, bevel=2, gloss=0.4)
        c.layer(c.mask(lambda d, k: rect(d, k, (4, 4, 104, 116), radius=2)), GOLD, bevel=4, gloss=0.9)
        c.layer(c.mask(lambda d, k: rect(d, k, (8, 30, 100, 112))), (110, 20, 24), bevel=2, depth=-0.8)

        def lattice(d, k):
            for i in range(-120, 220, 12):
                d.line([(i * k, 6 * k), ((i + 110) * k, 116 * k)], fill=255, width=2 * k)
                d.line([((i + 110) * k, 6 * k), (i * k, 116 * k)], fill=255, width=2 * k)
        c.layer(c.mask(lattice) * c.mask(lambda d, k: rect(d, k, (8, 30, 100, 112))), GOLD_LIGHT, bevel=1.2, gloss=0.9)
        arch = [(4, 26), (104, 8), (104, 26)] if leaf == 0 else [(4, 8), (104, 26), (4, 26)]
        c.layer(c.mask(lambda d, k: d.polygon(poly(arch, k), fill=255)), GOLD, bevel=3, gloss=0.8)
        mx = 60 if leaf == 0 else 48
        c.layer(c.mask(lambda d, k: ellipse(d, k, (mx - 20, 50, mx + 20, 90))), GOLD_DEEP, bevel=2, gloss=0.6)
        c.layer(c.mask(lambda d, k: ellipse(d, k, (mx - 17, 53, mx + 17, 87))), (40, 50, 110), bevel=3, depth=0.5)
        for bar in cross8(mx, 56, 28):
            c.layer(c.mask(lambda d, k, bar=bar: d.polygon(poly(bar, k), fill=255)), GOLD_LIGHT, bevel=1.5, gloss=1.0)
        out.paste(c.image(), (54 * leaf, 0))
    return out


def lever():
    img = Image.new("RGBA", (48, 80), (0, 0, 0, 0))
    for f, angle in ((0, -12), (1, 62)):
        c = Canvas(24, 80)
        c.layer(c.mask(lambda d, k: rect(d, k, (8, 88, 40, 124), radius=4)), GOLD_DEEP, bevel=3, gloss=0.6)
        c.layer(c.mask(lambda d, k: rect(d, k, (11, 91, 37, 121), radius=3)), GOLD, bevel=3, gloss=0.9)
        c.layer(c.mask(lambda d, k: rect(d, k, (19, 96, 29, 116), radius=2)), (40, 24, 8), bevel=2, depth=-1)
        px, py, length = 24, 106, 80
        rad = math.radians(-90 + angle)
        ex, ey = px + math.cos(rad) * length * 0.25, py + math.sin(rad) * length
        c.layer(c.mask(lambda d, k: d.line([(px * k, py * k), (ex * k, ey * k)], fill=255, width=5 * k)), (200, 200, 210), bevel=2, gloss=1.2)
        c.layer(c.mask(lambda d, k: ellipse(d, k, (ex - 11, ey + 4, ex + 11, ey + 9))), GOLD, bevel=2, gloss=1.0)
        c.layer(c.mask(lambda d, k: ellipse(d, k, (ex - 10, ey - 10, ex + 10, ey + 10))), (190, 20, 30), bevel=9, gloss=1.6, depth=1.3)
        img.paste(c.image(), (24 * f, 0))
    return img


def hand():
    """The hand of God from a cloud, with a nimbus: two fingers straight, the others folded, as in the blessing on icons."""
    c = Canvas(32, 32)
    c.layer(c.mask(lambda d, k: ellipse(d, k, (14, 0, 50, 34))), (250, 214, 110), bevel=3, gloss=0.6, alpha=0.6)
    for x, y, r in ((14, 12, 11), (30, 8, 13), (48, 12, 11), (22, 18, 9), (40, 18, 9)):
        c.layer(c.mask(lambda d, k, x=x, y=y, r=r: ellipse(d, k, (x - r, y - r, x + r, y + r))), (236, 240, 252), bevel=6, gloss=0.3)
    c.layer(c.mask(lambda d, k: rect(d, k, (24, 20, 40, 36), radius=3)), (176, 40, 40), bevel=3, gloss=0.4)
    c.layer(c.mask(lambda d, k: rect(d, k, (20, 34, 44, 52), radius=6)), (232, 190, 150), bevel=5, gloss=0.4)
    for x in (22, 28):
        c.layer(c.mask(lambda d, k, x=x: rect(d, k, (x, 48, x + 5, 62), radius=2)), (232, 190, 150), bevel=2.5, gloss=0.3)
    for x in (34, 39):
        c.layer(c.mask(lambda d, k, x=x: rect(d, k, (x, 48, x + 5, 56), radius=2)), (222, 178, 140), bevel=2.5, gloss=0.3)
    c.layer(c.mask(lambda d, k: d.line([(42 * k, 40 * k), (37 * k, 54 * k)], fill=255, width=4 * k)), (226, 184, 144), bevel=2)
    c.outline((90, 50, 30))
    return c.image()


def lamps():
    img = Image.new("RGBA", (28, 12), (0, 0, 0, 0))
    for i, glass in enumerate(((80, 30, 30), (230, 40, 34))):
        c = Canvas(14, 12)
        c.layer(c.mask(lambda d, k: d.line([(14 * k, 0), (14 * k, 4 * k)], fill=255, width=k)), GOLD_DEEP, flat=True)
        c.layer(c.mask(lambda d, k: rect(d, k, (4, 4, 24, 7), radius=1)), GOLD, bevel=1.5, gloss=0.9)
        c.layer(c.mask(lambda d, k: d.polygon(poly([(5, 7), (23, 7), (19, 19), (9, 19)], k), fill=255)), glass, bevel=3, gloss=1.2)
        c.layer(c.mask(lambda d, k: rect(d, k, (10, 19, 18, 23), radius=1)), GOLD_DEEP, bevel=1.5, gloss=0.6)
        img.paste(c.image(), (14 * i, 0))
    return img


def banner():
    """The ribbon: a cloth with a gilded hem, embroidered crosses and a fringe, folded at its ends; crimson, blue, black."""
    out = Image.new("RGBA", (180, 84), (0, 0, 0, 0))
    for i, (cloth, hem) in enumerate((((140, 18, 26), GOLD), ((30, 70, 130), (220, 230, 250)), ((22, 14, 16), (150, 30, 36)))):
        c = Canvas(180, 28)
        dark = tuple(int(v * 0.7) for v in cloth)
        c.layer(c.mask(lambda d, k: d.polygon(poly([(0, 8), (30, 8), (30, 46), (0, 46), (12, 27)], k), fill=255)), dark, bevel=4, gloss=0.2)
        c.layer(c.mask(lambda d, k: d.polygon(poly([(360, 8), (330, 8), (330, 46), (360, 46), (348, 27)], k), fill=255)), dark, bevel=4, gloss=0.2)
        c.layer(c.mask(lambda d, k: rect(d, k, (22, 2, 338, 44))), cloth, bevel=8, depth=0.5, gloss=0.25)
        for y in (4, 40):
            c.layer(c.mask(lambda d, k, y=y: rect(d, k, (22, y, 338, y + 2.5))), hem, bevel=1, gloss=0.9)
        for x in range(36, 330, 20):
            for y in (9, 35):
                c.layer(c.mask(lambda d, k, x=x, y=y: (rect(d, k, (x - 0.6, y - 2, x + 0.6, y + 2)), rect(d, k, (x - 2, y - 0.6, x + 2, y + 0.6)))),
                        hem, bevel=0.6, gloss=0.6, alpha=0.8)
        for x in np.arange(24, 337, 4):
            c.layer(c.mask(lambda d, k, x=x: d.line([(x * k, 44 * k), ((x + 0.5) * k, 54 * k)], fill=255, width=max(1, int(0.9 * k)))), hem, flat=True, alpha=0.95)
        out.paste(c.image(), (0, 28 * i))
    return out


def main():
    os.makedirs(OUT, exist_ok=True)
    os.makedirs(BUILD, exist_ok=True)
    strip = Image.new("RGBA", (S * len(SYMBOLS), S), (0, 0, 0, 0))
    for i, paint in enumerate(SYMBOLS):
        strip.paste(klejmo(paint, black=paint is p_gate), (S * i, 0))
    strip.save(os.path.join(OUT, "symbols.png"))
    frames = {name: frame(name) for name in PALETTES}
    for name, img in frames.items():
        img.save(os.path.join(OUT, "frame_" + name + ".png"))
    parts = {"doors": doors(), "lever": lever(), "hand": hand(), "lamps": lamps(), "banner": banner()}
    for name, img in parts.items():
        img.save(os.path.join(OUT, name + ".png"))
    for stale in ("coin.png",):
        if os.path.exists(os.path.join(OUT, stale)):
            os.remove(os.path.join(OUT, stale))
    # Contact sheets for eyes only (not shipped).
    sheet = Image.new("RGBA", (FW * 2 + 12, FH * 2 + 12), (54, 70, 50, 255))
    for i, img in enumerate(frames.values()):
        sheet.alpha_composite(img, ((i % 2) * (FW + 12), (i // 2) * (FH + 12)))
    sheet.resize((sheet.width * 3, sheet.height * 3), Image.NEAREST).save(os.path.join(BUILD, "roulette-sheet.png"))
    big = Image.new("RGBA", strip.size, (54, 70, 50, 255))
    big.alpha_composite(strip)
    big.resize((big.width * 4, big.height * 4), Image.NEAREST).save(os.path.join(BUILD, "roulette-symbols.png"))
    misc = Image.new("RGBA", (190, 170), (54, 70, 50, 255))
    misc.alpha_composite(parts["banner"], (0, 0))
    misc.alpha_composite(parts["doors"], (0, 86))
    misc.alpha_composite(parts["lever"], (110, 86))
    misc.alpha_composite(parts["hand"], (158, 86))
    misc.alpha_composite(parts["lamps"], (158, 120))
    misc.resize((misc.width * 4, misc.height * 4), Image.NEAREST).save(os.path.join(BUILD, "roulette-misc.png"))


if __name__ == "__main__":
    main()
