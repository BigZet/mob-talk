"""Draws the punishment roulette's textures (GUI, original pixel art drawn here in code).

Run from the repository root: python scripts/roulette/gen_roulette_assets.py
Writes src/main/resources/assets/divineblock/textures/gui/roulette/:
  symbols.png  14 symbols 32x32 in a row, in the order of core/RouletteSymbol
  frame_silver.png, frame_gold.png, frame_hot.png, frame_black.png  the machine, 176x112, its reel window left open
  lever.png    the lever up and down, 2 frames of 24x80
  hand.png     the golden hand from a cloud, 32x32
  lamps.png    a lampada of the fury scale, unlit and lit, 2 frames of 14x12
  effects: rays.png (god rays behind the machine, 256), vignette.png (256), glow.png (soft light, 64), coin.png (a spinning gold
  coin with a cross, 8 frames of 16x16), spark.png (a four-pointed star, 2 frames of 9x9), feather.png (16x16), shine.png (the glint
  that sweeps over the glass, 32x64)
"""
import math
import os

from PIL import Image, ImageDraw

OUT = os.path.join(os.path.dirname(__file__), "..", "..", "src", "main", "resources", "assets", "divineblock", "textures", "gui", "roulette")
CLEAR = (0, 0, 0, 0)


def rgba(c, a=255):
    return (c[0], c[1], c[2], a) if len(c) == 3 else c


def shade(c, k):
    return tuple(max(0, min(255, int(v * k))) for v in c[:3]) + (255,)


def finish(img, outline=(24, 16, 12), light=1.25, dark=0.72):
    """Pixel-art finish: a dark outline round every shape, a highlight on its upper-left edge and a shadow on its lower-right."""
    w, h = img.size
    src = img.load()
    out = img.copy()
    px = out.load()
    solid = lambda x, y: 0 <= x < w and 0 <= y < h and src[x, y][3] > 0
    for y in range(h):
        for x in range(w):
            if src[x, y][3] == 0:
                if any(solid(x + dx, y + dy) for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                    px[x, y] = rgba(outline)
                continue
            if not solid(x - 1, y) or not solid(x, y - 1):
                px[x, y] = shade(src[x, y], light)
            elif not solid(x + 1, y) or not solid(x, y + 1):
                px[x, y] = shade(src[x, y], dark)
    return out


def canvas(size=32):
    img = Image.new("RGBA", (size, size), CLEAR)
    return img, ImageDraw.Draw(img)


# ---- The symbols. ----

def lightning():
    img, d = canvas()
    d.polygon([(19, 2), (8, 17), (15, 17), (10, 29), (24, 12), (17, 12), (22, 2)], fill=(255, 214, 40))
    d.line([(19, 4), (12, 15)], fill=(255, 250, 200))
    d.line([(16, 18), (13, 25)], fill=(255, 250, 200))
    return finish(img, (70, 40, 0))


def flame():
    img, d = canvas()
    d.polygon([(16, 2), (21, 10), (25, 8), (27, 18), (24, 27), (16, 30), (8, 27), (5, 18), (8, 9), (11, 13)], fill=(220, 40, 20))
    d.polygon([(16, 9), (20, 16), (22, 14), (23, 21), (20, 27), (12, 27), (9, 21), (11, 15), (13, 17)], fill=(255, 140, 20))
    d.polygon([(16, 16), (19, 21), (18, 27), (14, 27), (13, 21)], fill=(255, 236, 120))
    return finish(img, (60, 8, 4))


def meteor():
    img, d = canvas()
    d.polygon([(30, 1), (19, 13), (13, 9)], fill=(255, 120, 20))
    d.polygon([(31, 6), (21, 17), (25, 22)], fill=(255, 190, 40))
    d.polygon([(26, 2), (17, 15), (21, 18)], fill=(255, 236, 140))
    d.ellipse([3, 14, 19, 30], fill=(96, 70, 58))
    for x, y in ((7, 19), (12, 24), (14, 18), (8, 26)):
        d.point((x, y), fill=(60, 42, 34))
        d.point((x + 1, y), fill=(60, 42, 34))
    d.arc([4, 15, 18, 29], 200, 290, fill=(255, 150, 50))
    return finish(img, (40, 16, 8))


def crack():
    img, d = canvas()
    d.rectangle([3, 8, 28, 28], fill=(122, 92, 62))
    d.rectangle([3, 6, 28, 10], fill=(86, 146, 52))
    for x in range(3, 29, 3):
        d.point((x, 11), fill=(86, 146, 52))
    for x, y in ((6, 15), (22, 14), (9, 23), (24, 24), (15, 20)):
        d.point((x, y), fill=(96, 72, 48))
        d.point((x + 1, y + 1), fill=(150, 140, 130))
    d.line([(15, 6), (13, 12), (17, 16), (12, 21), (16, 25), (14, 29)], fill=(16, 8, 4), width=2)
    d.line([(17, 16), (22, 19)], fill=(16, 8, 4))
    d.line([(13, 12), (8, 14)], fill=(16, 8, 4))
    return finish(img, (36, 24, 14))


def wave():
    img, d = canvas()
    d.polygon([(2, 29), (2, 20), (6, 14), (12, 8), (19, 5), (25, 6), (29, 10), (28, 15), (24, 13), (20, 14), (19, 18), (22, 22), (29, 23), (30, 29)],
              fill=(40, 110, 200))
    d.polygon([(19, 5), (25, 6), (29, 10), (28, 15), (24, 12), (20, 12), (17, 15), (15, 11)], fill=(230, 244, 255))
    d.line([(4, 24), (10, 20), (14, 22)], fill=(110, 180, 240))
    d.line([(16, 26), (22, 25), (27, 27)], fill=(110, 180, 240))
    return finish(img, (10, 30, 70))


def locust():
    img, d = canvas()
    # Side view: the long body, a folded wing, the great hind leg and long feelers.
    d.ellipse([5, 15, 24, 21], fill=(110, 160, 40))
    d.ellipse([20, 12, 28, 20], fill=(130, 182, 52))
    d.polygon([(7, 15), (22, 14), (19, 11), (9, 12)], fill=(176, 200, 96))
    d.polygon([(13, 18), (6, 8), (9, 7), (16, 16)], fill=(96, 146, 34))
    d.line([(7, 8), (3, 26)], fill=(80, 124, 28), width=2)
    d.line([(19, 21), (18, 27)], fill=(80, 124, 28))
    d.line([(22, 20), (24, 27)], fill=(80, 124, 28))
    d.line([(25, 13), (18, 4), (12, 2)], fill=(60, 84, 20))
    d.point((25, 15), fill=(20, 20, 10))
    return finish(img, (24, 40, 8))


def skull():
    img, d = canvas()
    d.ellipse([5, 3, 27, 23], fill=(232, 226, 204))
    d.rectangle([10, 19, 22, 28], fill=(232, 226, 204))
    d.ellipse([8, 11, 14, 17], fill=(30, 18, 18))
    d.ellipse([18, 11, 24, 17], fill=(30, 18, 18))
    d.polygon([(16, 17), (14, 21), (18, 21)], fill=(60, 40, 36))
    for x in (12, 15, 18, 21):
        d.line([(x, 24), (x, 28)], fill=(90, 80, 70))
    d.point((10, 13), fill=(200, 30, 30))
    d.point((20, 13), fill=(200, 30, 30))
    return finish(img, (40, 30, 24))


def rider():
    img, d = canvas()
    # The pale horse of the Apocalypse: a head in profile, a dark mane, a red eye.
    d.polygon([(14, 30), (15, 24), (16, 18), (12, 18), (8, 21), (4, 20), (3, 16), (9, 10), (13, 5), (15, 2), (18, 4), (22, 8),
               (26, 18), (26, 30)], fill=(214, 220, 196))
    d.polygon([(18, 4), (22, 2), (21, 6)], fill=(214, 220, 196))
    d.polygon([(22, 8), (25, 7), (28, 13), (29, 22), (29, 30), (26, 30), (26, 18)], fill=(40, 34, 40))
    d.polygon([(4, 17), (8, 19), (4, 20)], fill=(150, 156, 140))
    d.point((13, 9), fill=(220, 30, 30))
    d.point((14, 9), fill=(120, 10, 10))
    d.point((5, 17), fill=(60, 50, 50))
    d.line([(9, 14), (20, 22)], fill=(110, 80, 40))
    return finish(img, (20, 18, 16))


def scroll():
    img, d = canvas()
    d.rectangle([7, 5, 25, 26], fill=(236, 214, 160))
    d.ellipse([4, 3, 10, 9], fill=(206, 180, 120))
    d.ellipse([22, 22, 28, 28], fill=(206, 180, 120))
    d.rectangle([5, 4, 9, 8], fill=(206, 180, 120))
    d.rectangle([23, 23, 27, 27], fill=(206, 180, 120))
    for y in (10, 13, 16, 19):
        d.line([(11, y), (22 if y != 19 else 17, y)], fill=(90, 60, 40))
    d.ellipse([17, 18, 24, 25], fill=(180, 20, 20))
    d.point((20, 21), fill=(240, 120, 120))
    return finish(img, (60, 40, 20))


def anvil():
    img, d = canvas()
    d.polygon([(2, 8), (26, 8), (30, 11), (26, 14), (20, 14), (18, 18), (22, 22), (24, 27), (8, 27), (10, 22), (14, 18), (12, 14), (6, 14)],
              fill=(86, 88, 96))
    d.rectangle([3, 8, 25, 10], fill=(140, 144, 154))
    d.rectangle([8, 27, 24, 29], fill=(60, 62, 70))
    return finish(img, (16, 16, 20))


def tower():
    img, d = canvas()
    tiers = [(4, 24, 28, 30), (7, 18, 25, 24), (10, 12, 22, 18), (13, 6, 19, 12), (15, 2, 17, 6)]
    for i, (x0, y0, x1, y1) in enumerate(tiers):
        d.rectangle([x0, y0, x1, y1], fill=(196 - i * 8, 156 - i * 6, 100 - i * 4))
        for x in range(x0 + 2, x1, 4):
            d.point((x, y0 + 2), fill=(80, 54, 30))
    d.line([(16, 2), (16, 0)], fill=(230, 200, 120))
    d.line([(27, 4), (22, 12)], fill=(255, 230, 60))
    d.line([(22, 12), (24, 13)], fill=(255, 230, 60))
    return finish(img, (50, 34, 18))


def seven():
    img, d = canvas()
    d.polygon([(4, 3), (28, 3), (28, 9), (17, 29), (9, 29), (20, 10), (4, 10)], fill=(240, 186, 40))
    d.line([(6, 5), (25, 5)], fill=(255, 250, 200))
    d.line([(25, 6), (18, 19)], fill=(255, 240, 160))
    d.polygon([(4, 10), (20, 10), (17, 15)], fill=(200, 130, 20))
    img = finish(img, (150, 20, 10), light=1.15, dark=0.78)
    return finish(img, (60, 6, 4), light=1.0, dark=1.0)


def gate():
    img, d = canvas()
    d.rectangle([3, 4, 28, 29], fill=(48, 46, 52))
    d.pieslice([3, -6, 28, 18], 180, 360, fill=(48, 46, 52))
    for x in range(6, 28, 4):
        d.line([(x, 4), (x, 29)], fill=(110, 108, 118), width=1)
    d.line([(3, 14), (28, 14)], fill=(110, 108, 118))
    d.line([(15, 0), (15, 29)], fill=(24, 22, 26), width=2)
    d.rectangle([12, 17, 19, 24], fill=(150, 120, 40))
    d.arc([12, 12, 19, 20], 180, 360, fill=(170, 170, 180), width=2)
    d.point((15, 20), fill=(20, 16, 10))
    d.point((15, 21), fill=(20, 16, 10))
    return finish(img, (10, 8, 12))


def dove():
    img, d = canvas()
    d.ellipse([7, 13, 23, 23], fill=(246, 248, 252))
    d.ellipse([19, 9, 27, 17], fill=(246, 248, 252))
    d.polygon([(8, 16), (2, 12), (4, 19)], fill=(230, 234, 240))
    d.polygon([(11, 15), (14, 3), (20, 4), (18, 15)], fill=(220, 226, 236))
    d.polygon([(27, 12), (31, 13), (27, 14)], fill=(240, 170, 60))
    d.point((24, 12), fill=(20, 20, 30))
    d.line([(26, 15), (30, 20)], fill=(80, 130, 40))
    d.point((28, 18), fill=(120, 170, 60))
    d.point((30, 19), fill=(120, 170, 60))
    return finish(img, (60, 70, 90))


SYMBOLS = [lightning, flame, meteor, crack, wave, locust, skull, rider, scroll, anvil, tower, seven, gate, dove]


# ---- The machine. ----

W, H = 176, 112
WINDOW = (34, 38, 142, 98)  # left, top, right, bottom: three reels of 34 and two posts of 3
LAMPS = (11, 37, 27, 99)

PALETTES = {
    # base, highlight, low, dark, panel, accent
    "silver": ((176, 182, 196), (238, 242, 250), (112, 118, 132), (52, 56, 66), (34, 40, 58), (150, 190, 255)),
    "gold": ((214, 170, 60), (252, 226, 128), (160, 116, 34), (96, 62, 16), (78, 16, 18), (255, 210, 90)),
    "hot": ((255, 186, 80), (255, 246, 206), (226, 96, 30), (150, 30, 10), (110, 10, 6), (255, 80, 30)),
    "black": ((44, 36, 38), (150, 40, 40), (28, 22, 24), (10, 8, 8), (16, 8, 10), (200, 20, 30)),
}


def cross(d, cx, top, color, dark):
    """The eight-pointed cross: a short bar above, the long bar, and the slanted foot."""
    d.rectangle([cx - 1, top, cx, top + 21], fill=color)
    d.rectangle([cx - 3, top + 3, cx + 2, top + 4], fill=color)
    d.rectangle([cx - 6, top + 7, cx + 5, top + 8], fill=color)
    d.line([(cx - 4, top + 16), (cx + 3, top + 13)], fill=color, width=2)
    d.point((cx - 1, top - 1), fill=dark)


def lampada(d, cx, top, glass, metal, dark):
    d.line([(cx, top), (cx, top + 4)], fill=dark)
    d.polygon([(cx - 3, top + 5), (cx + 3, top + 5), (cx + 2, top + 10), (cx - 2, top + 10)], fill=glass)
    d.line([(cx - 4, top + 5), (cx + 4, top + 5)], fill=metal)
    d.point((cx, top + 7), fill=(255, 240, 160))


def frame(name):
    base, hi, lo, dk, panel, accent = PALETTES[name]
    img = Image.new("RGBA", (W, H), CLEAR)
    d = ImageDraw.Draw(img)
    # The crest: a kokoshnik arch with the cross, lampadas hanging at its sides.
    d.pieslice([52, 8, 124, 56], 180, 360, fill=base)
    d.pieslice([58, 13, 118, 51], 180, 360, fill=panel)
    d.pieslice([62, 17, 114, 47], 180, 360, fill=lo)
    d.pieslice([66, 21, 110, 43], 180, 360, fill=panel)
    for a in range(200, 341, 20):
        x = 88 + math.cos(math.radians(a)) * 33
        y = 32 + math.sin(math.radians(a)) * 21
        d.ellipse([x - 1, y - 1, x + 1, y + 1], fill=hi)
    cross(d, 88, 0, hi, dk)
    for cx in (34, 142):
        d.line([(cx, 18), (cx, 24)], fill=dk)
        lampada(d, cx, 14, accent, base, dk)
    # The body: a bevelled gilded border, an ornamented band, a velvet panel.
    d.rectangle([0, 24, W - 1, H - 1], fill=dk)
    d.rectangle([1, 25, W - 2, H - 2], fill=base)
    d.line([(1, 25), (W - 2, 25)], fill=hi)
    d.line([(1, 25), (1, H - 2)], fill=hi)
    d.line([(W - 2, 26), (W - 2, H - 2)], fill=lo)
    d.line([(2, H - 2), (W - 2, H - 2)], fill=lo)
    d.rectangle([7, 31, W - 8, H - 8], fill=dk)
    d.rectangle([8, 32, W - 9, H - 9], fill=panel)
    # The band's ornament: beads and little crosses.
    for x in range(6, W - 4, 6):
        d.point((x, 28), fill=hi)
        d.point((x, H - 5), fill=hi)
    for y in range(32, H - 6, 6):
        d.point((4, y), fill=hi)
        d.point((W - 5, y), fill=hi)
    for x in (12, W - 13):
        for y in (28, H - 5):
            d.line([(x - 1, y), (x + 1, y)], fill=accent)
            d.line([(x, y - 1), (x, y + 1)], fill=accent)
    # The fury scale's recess on the left; the reel window's gilded frame, left open.
    d.rectangle([LAMPS[0] - 1, LAMPS[1] - 1, LAMPS[2] + 1, LAMPS[3] + 1], fill=lo)
    d.rectangle([LAMPS[0], LAMPS[1], LAMPS[2], LAMPS[3]], fill=dk)
    l, t, r, b = WINDOW
    d.rectangle([l - 4, t - 4, r + 3, b + 3], fill=lo)
    d.rectangle([l - 3, t - 3, r + 2, b + 2], fill=hi)
    d.rectangle([l - 2, t - 2, r + 1, b + 1], fill=dk)
    d.rectangle([l, t, r - 1, b - 1], fill=CLEAR)
    for x in (l + 34, l + 71):
        d.rectangle([x, t, x + 2, b - 1], fill=base)
        d.line([(x, t), (x, b - 1)], fill=hi)
        d.line([(x + 2, t), (x + 2, b - 1)], fill=dk)
    # The win line's markers.
    mid = (t + b) // 2
    d.polygon([(l - 8, mid - 3), (l - 4, mid), (l - 8, mid + 3)], fill=accent)
    d.polygon([(r + 7, mid - 3), (r + 3, mid), (r + 7, mid + 3)], fill=accent)
    # Under the window: a little plaque the ribbon rolls out from.
    d.rectangle([60, b + 3, 116, b + 6], fill=lo)
    if name == "hot":
        glow = Image.new("RGBA", (W, H), CLEAR)
        g = ImageDraw.Draw(glow)
        g.rectangle([0, 24, W - 1, H - 1], outline=(255, 60, 20, 200), width=1)
        img = Image.alpha_composite(img, glow)
    if name == "black":
        for x in range(10, W - 10, 9):
            d.line([(x, 26), (x + 3, 30)], fill=(120, 10, 16))
    return img


def lever():
    img = Image.new("RGBA", (48, 80), CLEAR)
    for frame_index, angle in ((0, -12), (1, 62)):
        layer = Image.new("RGBA", (24, 80), CLEAR)
        d = ImageDraw.Draw(layer)
        # The housing.
        d.rectangle([4, 44, 19, 62], fill=(96, 62, 16))
        d.rectangle([5, 45, 18, 61], fill=(214, 170, 60))
        d.rectangle([9, 48, 14, 58], fill=(40, 24, 8))
        # The rod and the red knob, turned about the pivot.
        px, py = 12, 53
        length = 40
        rad = math.radians(-90 + angle)
        ex, ey = px + math.cos(rad) * length * 0.25, py + math.sin(rad) * length
        d.line([(px, py), (ex, ey)], fill=(190, 190, 200), width=3)
        d.line([(px - 1, py), (ex - 1, ey)], fill=(240, 240, 250), width=1)
        d.ellipse([ex - 5, ey - 5, ex + 5, ey + 5], fill=(200, 24, 24))
        d.ellipse([ex - 3, ey - 4, ex, ey - 1], fill=(255, 140, 140))
        img.paste(finish(layer, (30, 16, 6), light=1.1, dark=0.85), (frame_index * 24, 0))
    return img


def hand():
    img, d = canvas()
    # A cloud cuff at the top, a golden hand reaching down, fingers closed on the knob.
    for x, y, r in ((8, 6, 6), (16, 4, 7), (24, 6, 6), (12, 9, 5), (21, 9, 5)):
        d.ellipse([x - r, y - r, x + r, y + r], fill=(236, 240, 250))
    d.rectangle([12, 11, 20, 18], fill=(236, 190, 70))
    d.rounded_rectangle([9, 17, 23, 27], radius=3, fill=(240, 198, 80))
    for x in (10, 13, 16, 19):
        d.line([(x, 26), (x + 1, 30)], fill=(220, 170, 60), width=2)
    d.line([(22, 20), (26, 24)], fill=(220, 170, 60), width=2)
    d.line([(13, 12), (13, 17)], fill=(255, 236, 150))
    return finish(img, (90, 60, 20))


def lamps():
    img = Image.new("RGBA", (28, 12), CLEAR)
    for i, (glass, flame) in enumerate((((70, 30, 30), None), ((230, 40, 30), (255, 220, 90)))):
        layer = Image.new("RGBA", (14, 12), CLEAR)
        d = ImageDraw.Draw(layer)
        d.line([(2, 2), (11, 2)], fill=(214, 170, 60))
        d.polygon([(2, 3), (11, 3), (9, 9), (4, 9)], fill=glass)
        d.rectangle([5, 9, 8, 10], fill=(160, 116, 34))
        if flame:
            d.polygon([(6, 0), (8, 0), (8, 5), (6, 5)], fill=flame)
            d.point((7, 0), fill=(255, 255, 230))
        img.paste(finish(layer, (40, 20, 10), light=1.1, dark=0.8), (i * 14, 0))
    return img


# ---- The effects. ----

def rays():
    size = 256
    img = Image.new("RGBA", (size, size), CLEAR)
    px = img.load()
    c = size / 2
    for y in range(size):
        for x in range(size):
            dx, dy = x - c + 0.5, y - c + 0.5
            r = math.hypot(dx, dy) / c
            if r > 1:
                continue
            a = math.atan2(dy, dx)
            wedge = 0.5 + 0.5 * math.cos(a * 12)
            wedge = wedge ** 3 * (0.6 + 0.4 * (0.5 + 0.5 * math.cos(a * 5 + 1.3)))
            fade = (1 - r) ** 1.4 * min(1, r * 6)
            core = max(0, 1 - r * 3) ** 2 * 0.6
            alpha = min(1, wedge * fade * 1.4 + core)
            px[x, y] = (255, 255, 255, int(alpha * 255))
    return img


def vignette():
    size = 256
    img = Image.new("RGBA", (size, size), CLEAR)
    px = img.load()
    for y in range(size):
        for x in range(size):
            dx, dy = (x + 0.5) / size * 2 - 1, (y + 0.5) / size * 2 - 1
            r = math.hypot(dx * 1.05, dy * 1.2) / math.sqrt(2)
            alpha = max(0, min(1, (r - 0.35) / 0.55)) ** 1.6
            px[x, y] = (0, 0, 0, int(alpha * 255))
    return img


def glow():
    size = 64
    img = Image.new("RGBA", (size, size), CLEAR)
    px = img.load()
    for y in range(size):
        for x in range(size):
            r = math.hypot(x - size / 2 + 0.5, y - size / 2 + 0.5) / (size / 2)
            px[x, y] = (255, 255, 255, int(max(0, 1 - r) ** 2 * 255))
    return img


def coin():
    img = Image.new("RGBA", (128, 16), CLEAR)
    for f in range(8):
        layer = Image.new("RGBA", (16, 16), CLEAR)
        d = ImageDraw.Draw(layer)
        width = max(1, round(6.5 * abs(math.cos(f * math.pi / 8))))
        if width <= 1:
            d.rectangle([7, 1, 8, 14], fill=(200, 150, 40))
        else:
            d.ellipse([8 - width, 1, 7 + width, 14], fill=(240, 196, 60))
            if width >= 4:
                d.ellipse([10 - width, 3, 5 + width, 12], fill=(255, 220, 100))
                d.line([(7, 5), (7, 10)], fill=(170, 110, 20))
                d.line([(5, 7), (9, 7)], fill=(170, 110, 20))
        layer = finish(layer, (110, 70, 10), light=1.2, dark=0.75)
        img.paste(layer, (16 * f, 0))
    return img


def spark():
    img = Image.new("RGBA", (18, 9), CLEAR)
    for f, (core, arm) in enumerate((((255, 255, 240), (255, 220, 120)), ((255, 255, 255), (200, 230, 255)))):
        d = ImageDraw.Draw(img)
        ox = 9 * f
        d.line([(ox + 4, 0), (ox + 4, 8)], fill=arm)
        d.line([(ox, 4), (ox + 8, 4)], fill=arm)
        d.rectangle([ox + 3, 3, ox + 5, 5], fill=core)
        d.point((ox + 4, 4), fill=(255, 255, 255))
    return img


def feather():
    img, d = canvas(16)
    d.line([(3, 14), (13, 2)], fill=(200, 190, 170))
    d.polygon([(4, 12), (11, 2), (14, 3), (8, 12), (5, 14)], fill=(250, 250, 255))
    d.polygon([(4, 12), (7, 5), (11, 2), (6, 11)], fill=(226, 232, 244))
    return finish(img, (110, 120, 140), light=1.05, dark=0.9)


def shine():
    img = Image.new("RGBA", (32, 64), CLEAR)
    px = img.load()
    for y in range(64):
        for x in range(32):
            band = abs((x - 16) + (y - 32) * 0.35) / 10
            px[x, y] = (255, 255, 255, int(max(0, 1 - band) ** 2 * 200))
    return img


def main():
    os.makedirs(OUT, exist_ok=True)
    strip = Image.new("RGBA", (32 * len(SYMBOLS), 32), CLEAR)
    for i, draw in enumerate(SYMBOLS):
        strip.paste(draw(), (32 * i, 0))
    strip.save(os.path.join(OUT, "symbols.png"))
    for name in PALETTES:
        frame(name).save(os.path.join(OUT, "frame_" + name + ".png"))
    lever().save(os.path.join(OUT, "lever.png"))
    hand().save(os.path.join(OUT, "hand.png"))
    lamps().save(os.path.join(OUT, "lamps.png"))
    for name, draw in (("rays", rays), ("vignette", vignette), ("glow", glow), ("coin", coin), ("spark", spark), ("feather", feather), ("shine", shine)):
        draw().save(os.path.join(OUT, name + ".png"))
    # A contact sheet for eyes only (not shipped).
    sheet = Image.new("RGBA", (W * 2 + 24 + 30, H * 2 + 70), (60, 80, 60, 255))
    sheet.paste(strip.resize((strip.width * 2 // 3, 21), Image.NEAREST), (0, 0))
    for i, name in enumerate(PALETTES):
        f = frame(name)
        sheet.paste(f, ((i % 2) * (W + 4), 24 + (i // 2) * (H + 4)), f)
    os.makedirs(os.path.join(os.path.dirname(__file__), "..", "..", "build"), exist_ok=True)
    sheet.resize((sheet.width * 3, sheet.height * 3), Image.NEAREST).save(os.path.join(os.path.dirname(__file__), "..", "..", "build", "roulette-sheet.png"))
    strip.resize((strip.width * 4, 128), Image.NEAREST).save(os.path.join(os.path.dirname(__file__), "..", "..", "build", "roulette-symbols.png"))


if __name__ == "__main__":
    main()
