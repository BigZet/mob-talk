"""The casino's furniture: pixel-art textures, block models, blockstates, item models, loot tables and names.

    python3 -I tools/casino/decor.py                  # writes the assets and data
    python3 -I tools/casino/decor.py preview OUT.png  # also renders every model from two sides for a quick look

The blocks are registered in io.github.mobtalk.game.CasinoContent (ids, footprints, outlines and light must match the list there).
Textures are drawn here in code at 16 texels a block, in Minecraft's style: a small palette, shading by hand, a light top-left edge.
Models are boxes in the usual 0..16 space with the front to the north (the side that faces whoever placed the block); the big tables
reach into the neighbouring cells (-16..32), which CasinoContent fills with invisible parts.
"""
import json
import math
import os
import random
import sys

from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import grand_decor as G  # noqa: E402 (the grand casino's pieces)


ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
ASSETS = os.path.join(ROOT, "src", "main", "resources", "assets", "mobtalk")
DATA = os.path.join(ROOT, "src", "main", "resources", "data", "mobtalk")
TEX = {}  # name -> PIL image
ANIM = {}  # name -> the .mcmeta of an animated texture (its png is a strip of 16x16 frames)
NS = "mobtalk:block/casino/"


# ---------------------------------------------------------------- colours

FELT = (38, 112, 62)
FELT_BLUE = (36, 70, 128)
MAHOGANY = (112, 46, 30)
WALNUT = (74, 44, 28)
GOLD = (232, 182, 58)
GOLD_DARK = (168, 116, 34)
CHROME = (196, 202, 210)
LEATHER = (40, 30, 30)
VELVET = (158, 22, 34)
LACQUER = (150, 18, 24)
IVORY = (238, 232, 214)

# Swatches of the shared palette texture: name -> colour; each is a 2x2 cell, so boxes painted one colour need no own texture.
SWATCHES = {
    "gold": GOLD, "gold_dark": GOLD_DARK, "chrome": CHROME, "chrome_dark": (128, 134, 144), "black": (24, 22, 26),
    "white": IVORY, "red": (196, 32, 36), "red_dark": (120, 16, 22), "velvet": VELVET, "leather": LEATHER,
    "mahogany": MAHOGANY, "walnut": WALNUT, "felt": FELT, "green": (54, 170, 72), "blue": (44, 92, 196),
    "purple": (120, 52, 168), "orange": (232, 128, 32), "yellow": (250, 214, 64), "flame": (255, 226, 120),
    "flame_core": (255, 250, 214), "candle": (236, 228, 204), "glass_green": (64, 128, 72), "glass_amber": (176, 104, 34),
    "glass_clear": (196, 220, 224), "glass_red": (128, 26, 40), "glass_blue": (52, 84, 150), "label": (226, 214, 176),
    "crystal": (214, 236, 246), "crystal_glint": (244, 252, 255), "cork": (150, 112, 70), "brass": (204, 150, 60), "screen": (16, 20, 34), "card_red": (200, 40, 40),
}
PAL = {}


def rng(name):
    return random.Random("casino:" + name)


def shade(c, k):
    return tuple(max(0, min(255, int(v * k))) for v in c[:3]) + ((c[3],) if len(c) > 3 else (255,))


def rgba(c):
    return tuple(c[:3]) + (c[3] if len(c) > 3 else 255,)


def new(w=16, h=16, color=(0, 0, 0, 0)):
    return Image.new("RGBA", (w, h), color)


def rect(img, box, color):
    px = img.load()
    x0, y0, x1, y1 = box
    for y in range(max(0, y0), min(img.height, y1)):
        for x in range(max(0, x0), min(img.width, x1)):
            px[x, y] = rgba(color)


def noise_fill(img, base, amount, r, box=None):
    x0, y0, x1, y1 = box or (0, 0, img.width, img.height)
    px = img.load()
    for y in range(y0, y1):
        for x in range(x0, x1):
            px[x, y] = shade(base, 1 + r.uniform(-amount, amount))


def bevel(img, box, light=1.15, dark=0.75):
    px = img.load()
    x0, y0, x1, y1 = box
    for x in range(x0, x1):
        px[x, y0] = shade(px[x, y0], light)
        px[x, y1 - 1] = shade(px[x, y1 - 1], dark)
    for y in range(y0, y1):
        px[x0, y] = shade(px[x0, y], light)
        px[x1 - 1, y] = shade(px[x1 - 1, y], dark)


def tex(name, img):
    TEX[name] = img
    return img


def palette():
    img = new()
    for i, (name, colour) in enumerate(SWATCHES.items()):
        x, y = (i % 8) * 2, (i // 8) * 2
        PAL[name] = (x, y)
        rect(img, (x, y, x + 2, y + 2), colour)
        img.putpixel((x, y), shade(colour, 1.12))
        img.putpixel((x + 1, y + 1), shade(colour, 0.9))
    tex("palette", img)


def felt(name, base):
    """Baize: fine fibre noise, no pattern, so it tiles over a table of any size."""
    r = rng(name)
    img = new()
    noise_fill(img, base, 0.035, r)
    px = img.load()
    for _ in range(18):
        x, y = r.randrange(16), r.randrange(16)
        px[x, y] = shade(base, r.choice([0.9, 1.08]))
    return tex(name, img)


def wood(name, base, polished=True):
    """Fine furniture wood: long grain lines along the texture's width, a lacquer sheen streak."""
    r = rng(name)
    img = new()
    noise_fill(img, base, 0.03, r)
    px = img.load()
    for y in range(16):
        k = 1 + 0.07 * math.sin(y * 1.7 + r.uniform(0, 3))
        for x in range(16):
            px[x, y] = shade(px[x, y], k)
    for _ in range(5):  # grain streaks
        y, x0 = r.randrange(16), r.randrange(16)
        for x in range(x0, min(16, x0 + r.randrange(4, 9))):
            px[x, y] = shade(base, 0.8)
    if polished:
        for x in range(16):
            px[x, 3] = shade(px[x, 3], 1.12)
    return tex(name, img)


def gold_trim(name):
    img = new()
    r = rng(name)
    noise_fill(img, GOLD, 0.04, r)
    px = img.load()
    for x in range(16):
        px[x, 0] = shade(GOLD, 1.25)
        px[x, 1] = shade(GOLD, 1.1)
        px[x, 14] = shade(GOLD, 0.8)
        px[x, 15] = rgba(GOLD_DARK)
    for x in range(0, 16, 4):  # a beaded moulding
        px[x, 7] = shade(GOLD, 1.3)
        px[x + 1, 8] = shade(GOLD, 0.75)
    return tex(name, img)


def leather(name, base=LEATHER):
    """A padded armrest: tufted leather with button dimples."""
    r = rng(name)
    img = new()
    noise_fill(img, base, 0.05, r)
    px = img.load()
    for y in range(16):
        for x in range(16):
            if (x + y) % 8 == 0 or (x - y) % 8 == 0:
                px[x, y] = shade(base, 0.82)
    for (x, y) in ((4, 0), (12, 0), (0, 4), (8, 4), (4, 8), (12, 8), (0, 12), (8, 12)):
        px[x, y] = shade(base, 0.6)
    return tex(name, img)


def velvet(name, base=VELVET):
    r = rng(name)
    img = new()
    noise_fill(img, base, 0.05, r)
    px = img.load()
    for y in range(16):
        for x in range(16):
            if (x + y) % 6 == 0 or (x - y) % 6 == 0:
                px[x, y] = shade(base, 0.85)
    for y in range(0, 16, 6):
        for x in range(0, 16, 6):
            px[x, y] = rgba(GOLD_DARK)
    return tex(name, img)


def chrome():
    img = new()
    for y in range(16):
        for x in range(16):
            k = 0.78 + 0.35 * (0.5 + 0.5 * math.cos((x - 4) / 16 * math.pi * 2))
            img.putpixel((x, y), shade(CHROME, k))
    return tex("chrome", img)


def lacquer(name, base):
    """A glossy painted cabinet with a gold pin stripe."""
    r = rng(name)
    img = new()
    noise_fill(img, base, 0.03, r)
    px = img.load()
    for y in range(16):
        px[1, y] = shade(base, 1.25)
        px[2, y] = shade(base, 1.1)
    rect(img, (0, 0, 16, 1), GOLD)
    rect(img, (0, 15, 16, 16), GOLD_DARK)
    return tex(name, img)


# Pixel font for the sign and the machines: 4x6 letters, '#' is lit.
FONT = {
    "К": ["#..#", "#.#.", "##..", "#.#.", "#..#", "#..#"],
    "А": [".##.", "#..#", "#..#", "####", "#..#", "#..#"],
    "З": ["###.", "...#", ".##.", "...#", "...#", "###."],
    "И": ["#..#", "#..#", "#.##", "##.#", "#..#", "#..#"],
    "Н": ["#..#", "#..#", "####", "#..#", "#..#", "#..#"],
    "О": [".##.", "#..#", "#..#", "#..#", "#..#", ".##."],
}


def neon_sign():
    """«КАЗИНО» in red neon tubes with a warm glow, on a black board with running bulbs on the frame (32x16, two blocks wide)."""
    img = new(32, 16)
    rect(img, (0, 0, 32, 16), (22, 18, 26))
    r = rng("sign")
    px = img.load()
    for y in range(16):
        for x in range(32):
            px[x, y] = shade(px[x, y], 1 + r.uniform(-0.08, 0.08))
    # gold frame with bulbs
    for x in range(32):
        px[x, 0] = rgba(GOLD)
        px[x, 15] = rgba(GOLD_DARK)
    for y in range(16):
        px[0, y] = rgba(GOLD)
        px[31, y] = rgba(GOLD_DARK)
    for x in range(2, 31, 3):
        px[x, 0] = (255, 250, 210, 255)
        px[x, 15] = (255, 236, 160, 255)
    for y in (3, 6, 9, 12):
        px[0, y] = (255, 250, 210, 255)
        px[31, y] = (255, 236, 160, 255)
    lit = set()
    x0 = 2
    for ch in "КАЗИНО":
        for gy, row in enumerate(FONT[ch]):
            for gx, c in enumerate(row):
                if c == "#":
                    lit.add((x0 + gx, 5 + gy))
        x0 += 5
    for (x, y) in lit:  # glow first, tubes on top
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                p = (x + dx, y + dy)
                if 1 <= p[0] < 31 and 1 <= p[1] < 15 and p not in lit:
                    px[p] = (110, 22, 34, 255)
    for (x, y) in lit:
        px[x, y] = (255, 70, 80, 255)
    for (x, y) in lit:  # hot core on the top-left of each stroke
        if (x - 1, y) not in lit or (x, y - 1) not in lit:
            px[x, y] = (255, 168, 168, 255)
    # a gold underline with a diamond in the middle
    for x in range(4, 28):
        px[x, 12] = rgba(GOLD)
    px[15, 11] = px[16, 11] = rgba(GOLD)
    px[15, 13] = px[16, 13] = rgba(GOLD_DARK)
    tex("sign", img)


SEVEN = ["###", "..#", ".#.", ".#.", ".#."]
CHERRY = [".#.", "#.#", "#.#"]
BELL = [".#.", "###", "###"]


def slot_screen():
    """The slot machine's glass: three reels (7, cherries, 7) in white windows, a pay line, a gold bezel."""
    img = new()
    rect(img, (0, 0, 16, 16), (20, 22, 36))
    px = img.load()
    for x in range(16):
        px[x, 0] = rgba(GOLD)
        px[x, 15] = rgba(GOLD_DARK)
    for y in range(16):
        px[0, y] = rgba(GOLD)
        px[15, y] = rgba(GOLD_DARK)
    for i, sym in enumerate(("7", "cherry", "7")):
        x0 = 2 + i * 4
        rect(img, (x0, 3, x0 + 4, 12), (236, 232, 222))
        for y in range(3, 12):  # reel curvature: darker top and bottom
            k = 0.78 if y in (3, 11) else (0.9 if y in (4, 10) else 1.0)
            for x in range(x0, x0 + 4):
                px[x, y] = shade(px[x, y], k)
        if sym == "7":
            for gy, row in enumerate(SEVEN):
                for gx, c in enumerate(row):
                    if c == "#":
                        px[x0 + gx, 5 + gy] = (210, 24, 30, 255)
        else:
            px[x0 + 2, 5] = (60, 140, 50, 255)
            px[x0 + 1, 6] = (60, 140, 50, 255)
            for (x, y) in ((x0, 8), (x0 + 1, 8), (x0, 9), (x0 + 1, 9), (x0 + 2, 7), (x0 + 3, 7), (x0 + 2, 8), (x0 + 3, 8)):
                px[x, y] = (200, 20, 40, 255)
            px[x0, 8] = (255, 120, 120, 255)
            px[x0 + 2, 7] = (255, 120, 120, 255)
        px[x0 + 3, 3] = shade(px[x0 + 3, 3], 0.7)
    for x in range(1, 15):  # the pay line
        if px[x, 7][0] > 200 and px[x, 7][1] > 200:
            px[x, 7] = (250, 200, 70, 255)
    for x in (1, 14):
        px[x, 7] = (255, 220, 90, 255)
    rect(img, (2, 13, 14, 14), (60, 40, 20))
    for x in range(2, 14, 2):  # credit digits
        px[x, 13] = (255, 120, 40, 255)
    return tex("slot_screen", img)


def slot_marquee():
    """The lit topper (used rows 0..5, 14 wide): «777» in gold on a red field, chasing bulbs along the top."""
    img = new()
    rect(img, (0, 0, 16, 16), (176, 22, 30))
    px = img.load()
    for x in range(16):
        for y in range(6):
            px[x, y] = shade(px[x, y], 1.1 - y * 0.06)
    for x in range(1, 15):
        px[x, 0] = (255, 244, 180, 255) if x % 2 else rgba(GOLD_DARK)
    sevens = ["###", "..#", ".#.", ".#."]
    for i in range(3):
        x0 = 2 + i * 4
        for gy, row in enumerate(sevens):
            for gx, c in enumerate(row):
                if c == "#":
                    px[x0 + gx, 1 + gy] = (255, 250, 200, 255) if gy == 0 else (255, 214, 70, 255)
        px[x0 + 2, 5] = (120, 10, 16, 255)
    for x in range(1, 15):
        px[x, 5] = shade(px[x, 5], 0.75)
    return tex("slot_marquee", img)


def slot_body():
    """The cabinet front: lacquered red, a gold coin tray slot and a chrome plate with the coin slot."""
    img = lacquer("slot_body", LACQUER)
    px = img.load()
    rect(img, (3, 3, 13, 8), (190, 196, 204))
    bevel(img, (3, 3, 13, 8), 1.2, 0.7)
    rect(img, (7, 4, 9, 7), (30, 30, 34))  # coin slot
    rect(img, (2, 11, 14, 14), (24, 22, 26))  # the payout tray mouth
    for x in range(2, 14):
        px[x, 10] = rgba(GOLD)
        px[x, 14] = rgba(GOLD_DARK)
    for x in (4, 7, 10):  # coins in the tray
        px[x, 13] = (250, 210, 80, 255)
        px[x + 1, 13] = (200, 150, 40, 255)
    return img


def slot_side():
    img = lacquer("slot_side", (120, 14, 20))
    px = img.load()
    for y in range(2, 14):
        px[8, y] = shade(px[8, y], 0.8)
    return img


def slot_buttons():
    """The button deck: spin, bet and max-bet buttons."""
    img = new()
    rect(img, (0, 0, 16, 16), (36, 34, 40))
    px = img.load()
    for x, colour in ((2, (60, 200, 80)), (6, (250, 210, 60)), (10, (230, 40, 40))):
        rect(img, (x, 6, x + 3, 10), colour)
        px[x, 6] = shade(colour, 1.3)
        px[x + 2, 9] = shade(colour, 0.7)
    for x in range(16):
        px[x, 0] = rgba(GOLD)
        px[x, 15] = rgba(GOLD_DARK)
    return tex("slot_buttons", img)


def roulette_wheel():
    """The wheel seen from above: a wooden bowl, 16 pockets red/black with one green zero, a gold turret in the middle."""
    img = new()
    px = img.load()
    for y in range(16):
        for x in range(16):
            dx, dy = x - 7.5, y - 7.5
            d = math.hypot(dx, dy)
            a = (math.degrees(math.atan2(dy, dx)) + 360) % 360
            if d > 7.6:
                continue
            if d > 6.4:
                px[x, y] = shade(MAHOGANY, 0.9 + 0.2 * math.cos(math.radians(a - 225)))
            elif d > 5.2:
                px[x, y] = shade(WALNUT, 0.8)  # the ball track
            elif d > 3.2:
                k = int(a // 22.5)
                colour = (40, 150, 60) if k == 0 else ((196, 28, 32) if k % 2 else (26, 24, 28))
                px[x, y] = rgba(colour)
            elif d > 2.0:
                px[x, y] = shade(MAHOGANY, 1.1)
            else:
                px[x, y] = shade(GOLD, 1.15 if dx + dy < 0 else 0.85)
    px[12, 4] = (250, 250, 250, 255)  # the ball
    for k in range(16):  # pocket separators in gold
        a = math.radians(k * 22.5)
        x, y = int(round(7.5 + 4.4 * math.cos(a))), int(round(7.5 + 4.4 * math.sin(a)))
        if 0 <= x < 16 and 0 <= y < 16 and px[x, y][3]:
            px[x, y] = shade(px[x, y], 1.35)
    return tex("roulette_wheel", img)


def roulette_layout():
    """The betting layout, two blocks long (32x16): the zero, three rows of red and black numbers, the dozens below, gold lines."""
    img = new(32, 16)
    r = rng("layout")
    noise_fill(img, FELT, 0.03, r)
    px = img.load()
    line = (230, 214, 160, 255)
    # zero at the left, 12 columns of 3 rows: each number a 2x3 cell
    rect(img, (1, 2, 4, 11), (40, 150, 60))
    for col in range(12):
        for row in range(3):
            n = col * 3 + (3 - row)
            red = n in (1, 3, 5, 7, 9, 12, 14, 16, 18, 19, 21, 23, 25, 27, 30, 32, 34, 36)
            x0, y0 = 5 + col * 2, 2 + row * 3
            rect(img, (x0, y0, x0 + 2, y0 + 3), (190, 30, 34) if red else (28, 26, 30))
            px[x0, y0] = shade(px[x0, y0], 1.25)
    for x in range(1, 30):
        px[x, 1] = line
        px[x, 11] = line
        px[x, 14] = line
    for y in range(1, 15):
        px[1 if y < 11 else 5, y] = line
        px[29, y] = line
    for x in (13, 21):  # dozens
        for y in range(11, 15):
            px[x, y] = line
    for x0 in (8, 16, 24):
        px[x0, 12] = px[x0 + 1, 12] = (240, 230, 200, 255)
    return tex("roulette_layout", img)


def bet_spot():
    """A gold-ringed betting box printed on the felt (transparent inside: drawn over the felt as a decal)."""
    img = new()
    for i in range(16):
        for (x, y) in ((i, 0), (i, 15), (0, i), (15, i)):
            img.putpixel((x, y), rgba(GOLD) if (i // 2) % 2 == 0 else shade(GOLD, 0.8))
    for i in range(2, 14):
        for (x, y) in ((i, 2), (i, 13), (2, i), (13, i)):
            img.putpixel((x, y), (214, 200, 150, 200))
    return tex("bet_spot", img)


def chip_side(name, colour):
    """Chips seen from the side: one chip a texel high, a darker seam between chips, a white edge dash on every third chip."""
    img = new()
    px = img.load()
    for y in range(16):
        for x in range(16):
            px[x, y] = shade(colour, 1.0 if y % 2 == 0 else 0.78)
            if y % 2 == 0 and x % 3 == (y // 2) % 3:
                px[x, y] = shade(IVORY, 0.95)
    return tex(name, img)


def chip_top(name, colour):
    """The top chip seen from above for a 3x3 stack (repeats every 3 texels): coloured rim with a white mark, a lighter centre."""
    img = new()
    px = img.load()
    for y in range(16):
        for x in range(16):
            cx, cy = x % 3, y % 3
            if (cx, cy) == (1, 1):
                px[x, y] = shade(colour, 1.3)
            elif (cx, cy) == (1, 0):
                px[x, y] = rgba(IVORY)
            else:
                px[x, y] = rgba(colour)
    return tex(name, img)


CHIPS = {"chip_red": (196, 30, 38), "chip_black": (34, 32, 38), "chip_blue": (40, 84, 196), "chip_green": (40, 150, 70),
         "chip_purple": (120, 50, 168)}


def bar_front():
    """The bar's front: raised mahogany panels with gold edging and a brass kick plate."""
    r = rng("bar_front")
    img = new()
    noise_fill(img, MAHOGANY, 0.03, r)
    px = img.load()
    rect(img, (2, 2, 14, 12), shade(MAHOGANY, 1.12))
    bevel(img, (2, 2, 14, 12), 1.2, 0.7)
    for x in range(3, 13):
        px[x, 6] = shade(MAHOGANY, 0.95)
    for x in range(16):
        px[x, 0] = rgba(GOLD)
        px[x, 13] = rgba(GOLD_DARK)
        px[x, 14] = shade((204, 150, 60), 1.1)
        px[x, 15] = shade((204, 150, 60), 0.8)
    return tex("bar_front", img)


def bottles_back():
    """The mirror behind the bottles: smoky glass with gold pin lines."""
    img = new()
    for y in range(16):
        for x in range(16):
            k = 0.85 + 0.25 * max(0, 1 - abs(x - y - 2) / 4)
            img.putpixel((x, y), shade((70, 66, 74), k))
    px = img.load()
    for x in range(16):
        px[x, 0] = rgba(GOLD_DARK)
    for y in range(16):
        px[0, y] = rgba(GOLD_DARK)
        px[15, y] = rgba(GOLD_DARK)
    return tex("mirror", img)


def card_back():
    """The back of a playing card for the shoe and the dealt cards: red with a white border and lattice."""
    img = new()
    rect(img, (0, 0, 16, 16), IVORY)
    rect(img, (1, 1, 15, 15), (176, 30, 36))
    px = img.load()
    for y in range(2, 14):
        for x in range(2, 14):
            if (x + y) % 3 == 0:
                px[x, y] = (214, 70, 70, 255)
    return tex("card_back", img)


def card_face():
    """A face-up card: the ace of hearts, as in the game of 21."""
    img = new()
    rect(img, (0, 0, 16, 16), (246, 242, 230))
    px = img.load()
    for x in range(16):
        px[x, 15] = (180, 172, 160, 255)
    for (x, y) in ((6, 6), (7, 6), (9, 6), (10, 6), (5, 7), (6, 7), (7, 7), (8, 7), (9, 7), (10, 7), (11, 7), (6, 8), (7, 8), (8, 8),
                   (9, 8), (10, 8), (7, 9), (8, 9), (9, 9), (8, 10)):
        px[x, y] = (200, 30, 36, 255)
    px[6, 7] = (255, 130, 130, 255)
    for (x, y) in ((2, 2), (2, 3), (3, 2), (2, 4), (3, 3)):
        px[x, y] = (200, 30, 36, 255)
    return tex("card_face", img)


def make_textures():
    palette()
    felt("felt", FELT)
    felt("felt_blue", FELT_BLUE)
    wood("mahogany", MAHOGANY)
    wood("walnut", WALNUT)
    gold_trim("gold_trim")
    leather("leather")
    velvet("velvet")
    chrome()
    neon_sign()
    slot_screen()
    slot_marquee()
    slot_body()
    slot_side()
    slot_buttons()
    roulette_wheel()
    roulette_layout()
    bet_spot()
    bar_front()
    bottles_back()
    card_back()
    card_face()
    for name, colour in CHIPS.items():
        chip_side(name + "_side", colour)
        chip_top(name + "_top", colour)
    G.textures()


# ---------------------------------------------------------------- models

FACES = ("north", "south", "east", "west", "up", "down")


def auto_uv(face, f, t):
    """Blockbench-style automatic UV: the face's own extent in the texture, shifted into 0..16 when it reaches outside."""
    x0, y0, z0 = f
    x1, y1, z1 = t
    # Minecraft's own defaults (BlockElement): each side reads unmirrored when seen from outside.
    if face == "north":
        u0, u1, v0, v1 = 16 - x1, 16 - x0, 16 - y1, 16 - y0
    elif face == "south":
        u0, u1, v0, v1 = x0, x1, 16 - y1, 16 - y0
    elif face == "east":
        u0, u1, v0, v1 = 16 - z1, 16 - z0, 16 - y1, 16 - y0
    elif face == "west":
        u0, u1, v0, v1 = z0, z1, 16 - y1, 16 - y0
    elif face == "down":
        u0, u1, v0, v1 = x0, x1, 16 - z1, 16 - z0
    else:
        u0, u1, v0, v1 = x0, x1, z0, z1

    def fit(a, b):
        if b - a > 16:
            return 0, 16
        while a < 0:
            a, b = a + 16, b + 16
        while b > 16:
            a, b = a - 16, b - 16
        return a, b
    u0, u1 = fit(u0, u1)
    v0, v1 = fit(v0, v1)
    return [round(u0, 3), round(v0, 3), round(u1, 3), round(v1, 3)]


def box(f, t, texture, faces=FACES, uv=None, rotation=None):
    """An element: from/to in pixels, one texture for its faces (or a dict face -> texture or (texture, uv))."""
    el = {"from": [round(v, 3) for v in f], "to": [round(v, 3) for v in t], "faces": {}}
    for face in faces:
        if isinstance(texture, dict):
            if face not in texture:
                continue
            spec = texture[face]
            tx, fuv = (spec, None) if isinstance(spec, str) else spec
        else:
            tx, fuv = texture, (uv.get(face) if isinstance(uv, dict) else uv)
        el["faces"][face] = {"texture": "#" + tx, "uv": list(fuv) if fuv else auto_uv(face, f, t)}
    if rotation:
        el["rotation"] = rotation
    return el


def pal(name):
    x, y = PAL[name]
    return [x + 0.5, y + 0.5, x + 1.5, y + 1.5]


def pal_box(f, t, name, faces=FACES, rotation=None):
    return box(f, t, "palette", faces, {fc: pal(name) for fc in faces}, rotation)


MODELS = {}


def model(name, textures, elements, particle=None, ao=True):
    m = {"parent": "minecraft:block/block", "textures": {k: NS + v for k, v in textures.items()}, "elements": elements}
    m["textures"]["particle"] = m["textures"][particle or next(iter(textures))]
    if not ao:
        m["ambientocclusion"] = False
    MODELS[name] = m


def chip_stack(x, z, h, colour, y=0.0, size=3):
    """A stack of chips: a box with the side texture all round and the top chip on its upper face (UVs pinned to the 4x4 chip)."""
    side, top = "chip_" + colour + "_side", "chip_" + colour + "_top"
    sv = [0, 0, size, h]
    t = {"north": (side, sv), "south": (side, sv), "east": (side, sv), "west": (side, sv), "up": (top, [0, 0, size, size]),
         "down": (side, [0, 0, size, size])}
    return [box((x, y, z), (x + size, y + h, z + size), t)]


def ellipse_bands(cx, back, half, depth, step=4, both=False):
    """Bands of a half (or whole) ellipse table top: (x0, x1, z0, z1) per band of width step, the front edge stepped to the curve."""
    bands = []
    x = cx - half
    while x < cx + half - 0.01:
        mid = x + step / 2 - cx
        d = depth * math.sqrt(max(0.0, 1 - (mid / half) ** 2))
        z0 = round(back - d)
        z1 = round(back + d) if both else back
        bands.append((x, x + step, z0, z1))
        x += step
    return bands


def rail_for(bands, y0, y1, texture, width=2, both=False, back=None):
    """The padded armrest along the stepped curved edge(s) of the bands."""
    els = []
    for i, (x0, x1, z0, z1) in enumerate(bands):
        els.append(box((x0, y0, z0), (x1, y1, z0 + width), texture))
        if both:
            els.append(box((x0, y0, z1 - width), (x1, y1, z1), texture))
        for j in (i - 1, i + 1):  # risers between bands of different depth
            if 0 <= j < len(bands):
                oz0 = bands[j][2]
                if oz0 > z0:
                    xr = x0 if j < i else x1 - width
                    els.append(box((xr, y0, z0 + width), (xr + width, y1, oz0 + width), texture))
                    if both:
                        oz1 = bands[j][3]
                        els.append(box((xr, y0, oz1 - width), (xr + width, y1, z1 - width), texture))
    # the ends of the outermost bands
    for (x0, x1, z0, z1), xr in ((bands[0], bands[0][0]), (bands[-1], bands[-1][1] - width)):
        els.append(box((xr, y0, z0 + width), (xr + width, y1, (z1 - width) if both else z1), texture))
    return els


def pedestal(x, z, top=12):
    """A turned table leg: a wide foot, a column with a gold ring, a capital under the top."""
    return [
        box((x - 3, 0, z - 3), (x + 3, 1.5, z + 3), "walnut"),
        box((x - 2, 1.5, z - 2), (x + 2, 2.5, z + 2), "gold_trim"),
        box((x - 1.5, 2.5, z - 1.5), (x + 1.5, top - 1.5, z + 1.5), "mahogany"),
        box((x - 2, 6, z - 2), (x + 2, 7, z + 2), "gold_trim"),
        box((x - 2.5, top - 1.5, z - 2.5), (x + 2.5, top, z + 2.5), "walnut"),
    ]


def card(x, z, y, face=False, rot=0.0):
    t = "card_face" if face else "card_back"
    el = box((x, y, z), (x + 3, y + 0.15, z + 4), {"up": t, "down": "card_back", "north": "card_back", "south": "card_back",
                                                    "east": "card_back", "west": "card_back"})
    if rot:
        el["rotation"] = {"origin": [x + 1.5, y, z + 2], "axis": "y", "angle": rot}
    return el


def blackjack_table():
    """The table for 21: three blocks wide, two deep, a half-moon with the dealer on the straight side (south), players on the curve."""
    back = 14
    bands = ellipse_bands(8, back, 24, 28)
    els = []
    for (x0, x1, z0, z1) in bands:
        els.append(box((x0, 12, z0), (x1, 14, z1), {"up": "felt", "down": "walnut", "north": "mahogany", "south": "mahogany",
                                                       "east": "mahogany", "west": "mahogany"}))
    els += rail_for(bands, 14, 15.5, "leather")
    els += rail_for([(b[0], b[1], b[2], b[3]) for b in bands], 11, 12, "gold_trim")  # gold band under the rail
    # the dealer's edge: a wooden ledge with the chip tray sunk in it
    els.append(box((-16, 12, back), (32, 14.5, 16), {"up": "mahogany", "north": "mahogany", "south": "mahogany", "east": "mahogany",
                                                       "west": "mahogany", "down": "walnut"}))
    els.append(box((1, 14, 10), (15, 14.6, 14), "walnut"))  # the tray
    colours = ["red", "black", "blue", "green", "purple", "red", "black"]
    for i, c in enumerate(colours):
        els.append(box((1.5 + i * 2, 14.6, 10.5), (3.3 + i * 2, 15.2, 13.5), {"up": "chip_" + c + "_side", "north": "chip_" + c + "_top",
                                                                               "south": "chip_" + c + "_side", "east": "chip_" + c + "_side",
                                                                               "west": "chip_" + c + "_side", "down": "chip_" + c + "_side"}))
    # the card shoe at the dealer's left (from the players' side: right)
    els.append(box((21, 14, 8), (26, 16.5, 13), "black_box"))
    els.append(box((21.5, 14, 7), (25.5, 15.5, 8), "black_box"))
    els.append(box((22, 15.5, 7.6), (25, 16.3, 8.2), "card_back", rotation={"origin": [23.5, 16, 8], "axis": "x", "angle": -22.5}))
    els.append(box((21, 16.5, 8), (26, 16.8, 13), "gold_trim"))
    # betting boxes printed on the felt in front of three seats, and a hand dealt to the middle seat
    for (x, z, rot) in ((-9, -2, 22.5), (6, -9, 0), (21, -2, -22.5)):
        el = box((x, 14.02, z), (x + 4, 14.04, z + 4), "bet_spot", faces=("up",))
        if rot:
            el["rotation"] = {"origin": [x + 2, 14, z + 2], "axis": "y", "angle": rot}
        els.append(el)
    els.append(card(5.5, -4, 14.05, True, -22.5))
    els.append(card(8.5, -4.5, 14.2, False, 22.5))
    els.append(card(6.5, 4, 14.05, True, 0))  # the dealer's card
    els += chip_stack(6.5, -8.5, 2, "red", 14.04, 3)
    # three pedestals
    for (x, z) in ((-6, 2), (8, -4), (22, 2)):
        els += pedestal(x, z)
    model("casino_blackjack_table", {"felt": "felt", "mahogany": "mahogany", "walnut": "walnut", "leather": "leather",
                              "gold_trim": "gold_trim", "black_box": "leather", "card_back": "card_back", "card_face": "card_face",
                              "bet_spot": "bet_spot", **chip_tex()}, els, particle="felt")


def chip_tex():
    t = {}
    for c in CHIPS:
        t[c + "_side"] = c + "_side"
        t[c + "_top"] = c + "_top"
    return t


def poker_table():
    """A spare card table for games to come: an oval three blocks long and two deep, blue baize, padded rail all around."""
    back = 0
    bands = ellipse_bands(8, back, 24, 15, both=True)
    els = []
    for (x0, x1, z0, z1) in bands:
        els.append(box((x0, 12, z0), (x1, 14, z1), {"up": "felt", "down": "walnut", "north": "mahogany", "south": "mahogany",
                                                       "east": "mahogany", "west": "mahogany"}))
    els += rail_for(bands, 14, 15.5, "leather", both=True)
    els += rail_for(bands, 11, 12, "gold_trim", both=True)
    els.append(box((4, 14.02, -2), (12, 14.04, 2), "bet_spot", faces=("up",)))  # the pot square in the middle
    els += chip_stack(5, -1, 3, "black", 14.04, 3)
    els += chip_stack(8.5, -0.5, 2, "blue", 14.04, 3)
    for (x, z) in ((-5, 0), (8, 0), (21, 0)):
        els += pedestal(x, z)
    model("casino_poker_table", {"felt": "felt_blue", "mahogany": "mahogany", "walnut": "walnut", "leather": "leather",
                          "gold_trim": "gold_trim", "bet_spot": "bet_spot", **chip_tex()}, els, particle="felt")


def octagon(cx, cz, r, y0, y1, texture):
    """An octagonal disc from a square and a square turned 45 degrees (the widest turn a model allows)."""
    s = r * 0.83
    a = box((cx - s, y0, cz - s), (cx + s, y1, cz + s), texture)
    b = box((cx - s, y0 + 0.01, cz - s), (cx + s, y1 - 0.01, cz + s), texture,
            rotation={"origin": [cx, y0, cz], "axis": "y", "angle": 45})
    return [a, b]


def roulette_table():
    """Roulette: three blocks long, one deep; the wheel in a wooden bowl on the left, the betting layout over the other two."""
    els = []
    # the body: one long top, wooden apron
    els.append(box((-16, 12, 1), (32, 14, 15), {"up": "felt", "down": "walnut", "north": "mahogany", "south": "mahogany",
                                                 "east": "mahogany", "west": "mahogany"}))
    els.append(box((0, 14.01, 2), (32, 14.03, 14), {"up": ("layout", [0, 0, 16, 16])}, faces=("up",)))
    # the padded rail around
    els.append(box((-16, 14, 0), (32, 15.5, 1.8), "leather"))
    els.append(box((-16, 14, 14.2), (32, 15.5, 16), "leather"))
    els.append(box((30.2, 14, 1.8), (32, 15.5, 14.2), "leather"))
    els.append(box((-16, 14, 1.8), (-14.2, 15.5, 14.2), "leather"))
    els.append(box((-16, 11, 0), (32, 12, 16), "gold_trim", faces=("north", "south", "east", "west", "down")))
    # the wheel: a wooden bowl, the spinning head, the turret
    els += octagon(-8, 8, 7, 14, 16, "mahogany")
    wheel = box((-14, 16.01, 2), (-2, 16.03, 14), {"up": "wheel"}, faces=("up",))
    els.append(wheel)
    els.append(box((-9, 16, 7), (-7, 17.5, 9), "gold_trim"))
    els.append(box((-10.5, 17, 7.6), (-5.5, 17.6, 8.4), "gold_trim"))
    els.append(box((-8.4, 17, 5.5), (-7.6, 17.6, 10.5), "gold_trim"))
    # the dolly (the marker on the last number) and a few chips on the layout
    els.append(box((14, 14.03, 6), (15, 15.5, 7), "gold_trim"))
    els += chip_stack(5, 4.5, 2, "red", 14.04, 2)
    els += chip_stack(18, 9, 3, "black", 14.04, 2)
    els += chip_stack(25, 4, 1, "purple", 14.04, 2)
    for (x, z) in ((-8, 8), (8, 8), (24, 8)):
        els += pedestal(x, z)
    model("casino_roulette_table", {"felt": "felt", "layout": "roulette_layout", "wheel": "roulette_wheel", "mahogany": "mahogany",
                             "walnut": "walnut", "leather": "leather", "gold_trim": "gold_trim", **chip_tex()}, els, particle="felt")


def slot_machine():
    """A slot machine two blocks tall: the lower block is the cabinet with the button deck and the payout tray, the upper block
    the reels behind glass, the lit «777» topper and the lever on the right side."""
    lower = [
        box((1, 0, 2), (15, 1.5, 15), "black"),  # plinth
        box((1.5, 1.5, 3), (14.5, 12, 14.5), {"north": "body", "south": "side", "east": "side", "west": "side", "down": "side"}),
        box((1, 12, 0.5), (15, 13.5, 8), {"up": "buttons", "north": "gold_trim", "east": "side", "west": "side", "down": "side",
                                          "south": "side"}),  # the button deck sticks out towards the player
        box((1.5, 13.5, 3), (14.5, 16, 14.5), {"north": "side", "south": "side", "east": "side", "west": "side"}),
        box((1, 11.4, 0.5), (15, 12, 8), "gold_trim"),
    ]
    model("casino_slot_machine_lower", {"body": "slot_body", "side": "slot_side", "buttons": "slot_buttons", "gold_trim": "gold_trim",
                                 "black": "leather"}, lower, particle="side")
    upper = [
        box((1.5, 0, 3), (14.5, 10, 14.5), {"north": "side", "south": "side", "east": "side", "west": "side"}),
        box((2, 0.5, 2.5), (14, 9.5, 3), {"north": ("screen", [2, 3.5, 14, 12.5])}),
        box((1, 0, 2), (15, 0.8, 3), "gold_trim"),
        box((1, 9.5, 2), (15, 10.3, 3), "gold_trim"),
        box((1, 0.8, 2), (2, 9.5, 3), "gold_trim"),
        box((14, 0.8, 2), (15, 9.5, 3), "gold_trim"),
        # the lit topper, slightly wider, with «777»; a red lid edged in gold
        box((1, 10.3, 2.5), (15, 15.5, 14.5), {"north": ("marquee", [1, 0, 15, 5.2]), "south": "side", "east": "side", "west": "side"}),
        box((0.6, 15.5, 2.1), (15.4, 16, 14.9), {"up": "side", "north": "gold_trim", "south": "gold_trim", "east": "gold_trim",
                                                 "west": "gold_trim", "down": "gold_trim"}),
        # the lever: a chrome pivot on the right side, an arm and a red ball
        box((14.5, 2, 7), (16, 4, 10), "chrome"),
        box((15, 4, 8), (16, 12, 9), "chrome"),
        box((14.6, 12, 7.1), (16.4, 13.8, 9.9), "ball"),
    ]
    model("casino_slot_machine_upper", {"side": "slot_side", "screen": "slot_screen", "marquee": "slot_marquee", "gold_trim": "gold_trim",
                                 "chrome": "chrome", "ball": "slot_body"}, upper, particle="side", ao=False)
    # the red ball: a corner of the lacquered side texture is plain red
    upper[-1]["faces"] = {f: {"texture": "#palette", "uv": pal("red")} for f in FACES}
    MODELS["casino_slot_machine_upper"]["textures"]["palette"] = NS + "palette"


def bar_counter():
    els = [
        box((0, 0, 3), (16, 13, 16), {"north": "front", "south": "mahogany", "east": "mahogany", "west": "mahogany", "down": "walnut"}),
        box((0, 13, 0), (16, 16, 16), {"up": "walnut", "north": "walnut", "south": "walnut", "east": "walnut", "west": "walnut",
                                        "down": "walnut"}),
        box((0, 12.4, 0.2), (16, 13, 3), "gold_trim"),
        # the brass foot rail on little brackets
        box((0, 2, 0.5), (16, 3, 1.5), "brass"),
        box((3, 2, 1.5), (4, 3, 3), "brass"),
        box((12, 2, 1.5), (13, 3, 3), "brass"),
    ]
    els[3]["faces"] = {f: {"texture": "#palette", "uv": pal("brass")} for f in FACES}
    for e in els[4:]:
        e["faces"] = {f: {"texture": "#palette", "uv": pal("brass")} for f in FACES}
    model("casino_bar_counter", {"front": "bar_front", "mahogany": "mahogany", "walnut": "walnut", "gold_trim": "gold_trim",
                          "palette": "palette"}, els, particle="walnut")


BOTTLES = ("glass_green", "glass_amber", "glass_clear", "glass_red", "glass_blue")


def bottle(x, y, z, colour, tall=5.0):
    return [
        pal_box((x, y, z), (x + 2, y + tall, z + 2), colour),
        pal_box((x + 0.25, y + tall - 2.2, z - 0.01), (x + 1.75, y + tall - 0.8, z + 0.3), "label", faces=("north",)),
        pal_box((x + 0.5, y + tall, z + 0.5), (x + 1.5, y + tall + 2, z + 1.5), colour),
        pal_box((x + 0.5, y + tall + 2, z + 0.5), (x + 1.5, y + tall + 2.6, z + 1.5), "cork"),
    ]


def bar_shelf():
    """The back bar: a smoky mirror with three shelves of bottles; stands against a wall (its back is the south face)."""
    r = rng("shelf")
    els = [box((0, 0, 14), (16, 16, 16), {"north": "mirror", "south": "walnut", "east": "walnut", "west": "walnut", "up": "walnut",
                                          "down": "walnut"})]
    els.append(box((0, 0, 7), (16, 2, 14), {"up": "walnut", "north": "gold_trim", "east": "walnut", "west": "walnut", "down": "walnut",
                                           "south": "walnut"}))
    for y in (6.5, 11.5):
        els.append(box((0, y - 1, 9), (16, y, 14), {"up": "walnut", "north": "gold_trim", "east": "walnut", "west": "walnut",
                                                     "down": "walnut", "south": "walnut"}))
    for x in (0, 15):
        els.append(box((x, 0, 9), (x + 1, 16, 14), "walnut"))
    for y, z, n in ((2, 10, 5), (6.5, 10.5, 5), (11.5, 10.5, 4)):
        xs = [1.5 + i * (13.0 / n) for i in range(n)]
        for x in xs:
            tall = 4.0 if y > 10 else r.choice([3.5, 4.0, 4.5])
            els += bottle(round(x, 2), y, z, r.choice(BOTTLES), tall)
    model("casino_bar_shelf", {"mirror": "mirror", "walnut": "walnut", "gold_trim": "gold_trim", "palette": "palette"}, els, particle="walnut")


def bar_stool():
    els = [
        pal_box((4, 0, 4), (12, 1, 12), "chrome_dark"),
        box((7, 1, 7), (9, 10, 9), "chrome"),
        pal_box((4.5, 4, 4.5), (11.5, 4.8, 11.5), "brass"),
        box((4, 10, 4), (12, 12, 12), {"up": "velvet", "north": "velvet", "south": "velvet", "east": "velvet", "west": "velvet",
                                        "down": "leather"}),
        pal_box((3.6, 9.6, 3.6), (12.4, 10.2, 12.4), "gold"),
    ]
    model("casino_bar_stool", {"chrome": "chrome", "velvet": "velvet", "leather": "leather", "palette": "palette"}, els, particle="velvet")


def chair():
    """The players' chair at the tables: gold legs, a red velvet seat and a rounded back with a gold top rail. Faces north."""
    els = []
    for (x, z) in ((3, 3), (12, 3), (3, 12), (12, 12)):
        els.append(pal_box((x, 0, z), (x + 1, 7, z + 1), "gold"))
    els.append(box((2.5, 7, 2.5), (13.5, 9.5, 13.5), {"up": "velvet", "north": "velvet", "south": "velvet", "east": "velvet",
                                                      "west": "velvet", "down": "walnut"}))
    els.append(box((3, 9.5, 12.5), (13, 19, 14.5), {"north": "velvet", "south": "walnut", "east": "walnut", "west": "walnut",
                                                     "up": "walnut"}))
    els.append(box((2.5, 19, 12.2), (13.5, 20, 14.8), "gold_trim"))
    els.append(box((2.5, 9.5, 12.2), (3.5, 19, 14.8), "gold_trim"))
    els.append(box((12.5, 9.5, 12.2), (13.5, 19, 14.8), "gold_trim"))
    model("casino_chair", {"velvet": "velvet", "walnut": "walnut", "gold_trim": "gold_trim", "palette": "palette"}, els,
          particle="velvet")


def chips_models():
    """Chips left on a table, one to four stacks (like candles); stand on a table top, a counter, a floor."""
    layout = [(4, 4, 6, "red"), (9, 4.5, 9, "black"), (4.5, 9, 4, "blue"), (9.5, 9.5, 7, "green")]
    for n in range(1, 5):
        els = []
        for (x, z, h, c) in layout[:n]:
            els += chip_stack(x, z, h, c)
        model(f"casino_chips_{n}", chip_tex(), els, particle="chip_red_side")


def sign():
    """The «КАЗИНО» sign on a wall over the door: two blocks wide, its back on the wall (south)."""
    els = [
        box((0, 2, 14.5), (32, 14, 16), {"north": ("sign", [0, 0, 16, 16]), "south": "walnut", "east": "walnut", "west": "walnut",
                                         "up": "walnut", "down": "walnut"}),
        box((0, 1.5, 14), (32, 2, 16), "gold_trim"),
        box((0, 14, 14), (32, 14.5, 16), "gold_trim"),
    ]
    model("casino_sign", {"sign": "sign", "walnut": "walnut", "gold_trim": "gold_trim"}, els, particle="gold_trim", ao=False)


def chandelier():
    """A gold chandelier hanging from the ceiling: chain, two rings, eight candles with flames and a crystal drop."""
    els = [
        pal_box((7.5, 10, 7.5), (8.5, 16, 8.5), "gold_dark"),
        pal_box((7, 5, 7), (9, 10, 9), "gold"),
        pal_box((7.3, 3, 7.3), (8.7, 5, 8.7), "crystal"),
        pal_box((7.6, 2, 7.6), (8.4, 3, 8.4), "crystal"),
    ]
    # the ring: four bars, and a smaller one turned 45 degrees above it
    for (f, t) in (((2, 5, 2), (14, 6, 3)), ((2, 5, 13), (14, 6, 14)), ((2, 5, 3), (3, 6, 13)), ((13, 5, 3), (14, 6, 13))):
        els.append(pal_box(f, t, "gold"))
    for (f, t) in (((4.5, 7, 4.5), (11.5, 7.8, 5.3)), ((4.5, 7, 10.7), (11.5, 7.8, 11.5)), ((4.5, 7, 5.3), (5.3, 7.8, 10.7)),
                   ((10.7, 7, 5.3), (11.5, 7.8, 10.7))):
        els.append(pal_box(f, t, "gold", rotation={"origin": [8, 7, 8], "axis": "y", "angle": 45}))
    # spokes from the centre to the ring
    els.append(pal_box((3, 5.2, 7.6), (13, 5.8, 8.4), "gold_dark"))
    els.append(pal_box((7.6, 5.2, 3), (8.4, 5.8, 13), "gold_dark"))
    for (x, z) in ((2.5, 2.5), (13.5, 2.5), (2.5, 13.5), (13.5, 13.5), (8, 2.5), (8, 13.5), (2.5, 8), (13.5, 8)):
        els.append(pal_box((x - 0.6, 6, z - 0.6), (x + 0.6, 6.5, z + 0.6), "gold"))
        els.append(pal_box((x - 0.4, 6.5, z - 0.4), (x + 0.4, 8.5, z + 0.4), "candle"))
        els.append(pal_box((x - 0.3, 8.5, z - 0.3), (x + 0.3, 9.4, z + 0.3), "flame"))
        els.append(pal_box((x - 0.15, 8.6, z - 0.15), (x + 0.15, 9.0, z + 0.15), "flame_core"))
    model("casino_chandelier", {"palette": "palette"}, els, ao=False)


def rope_post():
    """The bouncer's post at the door: a brass stanchion with a red velvet rope that sags to the next post (lowest at the block's
    edges, so a row of posts reads as one rope). Its point of interest is the bouncer's job site."""
    els = [
        pal_box((5, 0, 5), (11, 1, 11), "brass"),
        pal_box((6, 1, 6), (10, 1.6, 10), "gold"),
        pal_box((7.2, 1.6, 7.2), (8.8, 12, 8.8), "brass"),
        pal_box((6.8, 12, 6.8), (9.2, 13.5, 9.2), "gold"),
        pal_box((7.3, 13.5, 7.3), (8.7, 14.2, 8.7), "gold_dark"),
    ]
    for (x0, x1, y) in ((0, 3.2, 8), (3, 5.7, 8.8), (5.5, 7.2, 9.6), (8.8, 10.5, 9.6), (10.3, 13, 8.8), (12.8, 16, 8)):
        els.append(pal_box((x0, y, 7.3), (x1, y + 1.5, 8.7), "velvet"))
    model("casino_rope", {"velvet": "velvet", "palette": "palette"}, els, particle="velvet")


def make_models():
    rope_post()
    blackjack_table()
    poker_table()
    roulette_table()
    slot_machine()
    bar_counter()
    bar_shelf()
    bar_stool()
    chair()
    chips_models()
    sign()
    chandelier()
    G.models()
    # the invisible part that fills a big table's other cells: only a particle, the table's model draws over it
    MODELS["casino_part"] = {"textures": {"particle": NS + "mahogany"}}


# ---------------------------------------------------------------- blockstates, items, names

FACINGS = (("north", 0), ("east", 90), ("south", 180), ("west", 270))
NAMES = {
    "casino_blackjack_table": ("Стол для игры в 21", "Twenty-one table"),
    "casino_poker_table": ("Карточный стол", "Card table"),
    "casino_roulette_table": ("Стол рулетки", "Roulette table"),
    "casino_slot_machine": ("Игровой автомат «777»", "Slot machine"),
    "casino_bar_counter": ("Барная стойка", "Bar counter"),
    "casino_bar_shelf": ("Барная полка с бутылками", "Back bar shelf"),
    "casino_bar_stool": ("Барный стул", "Bar stool"),
    "casino_chair": ("Кресло игрока", "Player's chair"),
    "casino_chips": ("Фишки казино", "Casino chips"),
    "casino_sign": ("Вывеска «Казино»", "Casino sign"),
    "casino_chandelier": ("Люстра", "Chandelier"),
    "casino_part": ("Часть стола", "Table part"),
    "casino_rope": ("Столбик с бархатным канатом", "Velvet rope post"),
}
DISPLAY = {"gui": {"rotation": [30, 225, 0], "translation": [0, 0, 0], "scale": [0.625, 0.625, 0.625]},
           "ground": {"rotation": [0, 0, 0], "translation": [0, 3, 0], "scale": [0.25, 0.25, 0.25]},
           "fixed": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [0.5, 0.5, 0.5]},
           "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.375, 0.375, 0.375]},
           "firstperson_righthand": {"rotation": [0, 45, 0], "translation": [0, 0, 0], "scale": [0.4, 0.4, 0.4]},
           "firstperson_lefthand": {"rotation": [0, 225, 0], "translation": [0, 0, 0], "scale": [0.4, 0.4, 0.4]}}
SMALL = {k: dict(v, scale=[s * 0.42 for s in v["scale"]], translation=[0, 0, 0] if k != "ground" else [0, 2, 0])
         for k, v in DISPLAY.items()}


def write(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2, ensure_ascii=False)
        f.write("\n")


def loot(name, extra=None):
    entry = {"type": "minecraft:item", "name": f"mobtalk:{name}"}
    if extra:
        entry["functions"] = extra
    write(os.path.join(DATA, "loot_table", "blocks", name + ".json"), {"type": "minecraft:block", "pools": [{"rolls": 1, "entries": [
        entry], "conditions": [{"condition": "minecraft:survives_explosion"}]}]})


def facing_variants(model_name, prefix=""):
    return {prefix + f"facing={f}": ({"model": NS + model_name, "y": y} if y else {"model": NS + model_name}) for f, y in FACINGS}


def save_all():
    tdir = os.path.join(ASSETS, "textures", "block", "casino")
    os.makedirs(tdir, exist_ok=True)
    for name, img in TEX.items():
        img.save(os.path.join(tdir, name + ".png"))
        if name in ANIM:
            write(os.path.join(tdir, name + ".png.mcmeta"), ANIM[name])
    for name, m in MODELS.items():
        write(os.path.join(ASSETS, "models", "block", "casino", name + ".json"), m)
    simple = ("casino_blackjack_table", "casino_poker_table", "casino_roulette_table", "casino_bar_counter", "casino_bar_shelf", "casino_bar_stool", "casino_chair",
              "casino_sign", "casino_chandelier", "casino_rope")
    for name in simple:
        write(os.path.join(ASSETS, "blockstates", name + ".json"), {"variants": facing_variants(name)})
        big = name in ("casino_blackjack_table", "casino_poker_table", "casino_roulette_table", "casino_sign")
        write(os.path.join(ASSETS, "models", "item", name + ".json"), {"parent": NS + name, "display": SMALL if big else DISPLAY})
        loot(name)
    variants = {}
    for f, y in FACINGS:
        for half, m in (("lower", "casino_slot_machine_lower"), ("upper", "casino_slot_machine_upper")):
            variants[f"facing={f},half={half}"] = {"model": NS + m, "y": y} if y else {"model": NS + m}
    write(os.path.join(ASSETS, "blockstates", "casino_slot_machine.json"), {"variants": variants})
    write(os.path.join(ASSETS, "models", "item", "casino_slot_machine.json"), {"parent": "minecraft:block/block", "textures": {
        **MODELS["casino_slot_machine_lower"]["textures"], **MODELS["casino_slot_machine_upper"]["textures"]},
        "elements": MODELS["casino_slot_machine_lower"]["elements"] + [dict(e, **{"from": [e["from"][0], e["from"][1] + 16, e["from"][2]],
                                                                          "to": [e["to"][0], e["to"][1] + 16, e["to"][2]]})
                                                                for e in MODELS["casino_slot_machine_upper"]["elements"] if "rotation" not in e],
        "display": {k: dict(v, scale=[s * 0.6 for s in v["scale"]]) for k, v in DISPLAY.items()}})
    write(os.path.join(DATA, "loot_table", "blocks", "casino_slot_machine.json"), {"type": "minecraft:block", "pools": [{"rolls": 1,
        "entries": [{"type": "minecraft:item", "name": "mobtalk:casino_slot_machine"}], "conditions": [
            {"condition": "minecraft:survives_explosion"},
            {"condition": "minecraft:block_state_property", "block": "mobtalk:casino_slot_machine", "properties": {"half": "lower"}}]}]})
    write(os.path.join(ASSETS, "blockstates", "casino_chips.json"), {"variants": {
        f"chips={n},facing={f}": ({"model": NS + f"casino_chips_{n}", "y": y} if y else {"model": NS + f"casino_chips_{n}"})
        for n in range(1, 5) for f, y in FACINGS}})
    write(os.path.join(ASSETS, "models", "item", "casino_chips.json"), {"parent": NS + "casino_chips_4", "display": DISPLAY})
    loot("casino_chips", [{"function": "minecraft:set_count", "count": n, "add": False, "conditions": [
        {"condition": "minecraft:block_state_property", "block": "mobtalk:casino_chips", "properties": {"chips": str(n)}}]} for n in range(2, 5)])
    write(os.path.join(ASSETS, "blockstates", "casino_part.json"), {"variants": {"": {"model": NS + "casino_part"}}})
    G.save()
    NAMES.update(G.NAMES)
    for lang, i in (("ru_ru", 0), ("en_us", 1)):
        p = os.path.join(ASSETS, "lang", lang + ".json")
        with open(p, encoding="utf-8") as f:
            d = json.load(f)
        for name, titles in NAMES.items():
            d[f"block.mobtalk.{name}"] = titles[i]
        with open(p, "w", encoding="utf-8") as f:
            json.dump(d, f, indent=2, ensure_ascii=False)
            f.write("\n")


# ---------------------------------------------------------------- preview

def render(m, yaw, size=256, pitch_deg=30):
    """A rough look at a model: every face sampled point by point into a z-buffer, with flat side shading."""
    img = Image.new("RGBA", (size, size), (40, 44, 52, 255))
    zb = [[-1e9] * size for _ in range(size)]
    px = img.load()
    ext = max(max(abs(v - 8) for e in m["elements"] for v in e["from"] + e["to"]), 8)
    scale = size / (ext * 2.4)
    cy, sy = math.cos(math.radians(yaw)), math.sin(math.radians(yaw))
    pitch = math.radians(pitch_deg)
    cp, sp = math.cos(pitch), math.sin(pitch)

    def project(x, y, z):
        x, z = x - 8, z - 8
        rx = x * cy - z * sy
        rz = x * sy + z * cy
        ry = y - 8
        vy = ry * cp - rz * sp
        depth = ry * sp + rz * cp
        return size / 2 + rx * scale, size * 0.55 - vy * scale, depth
    for el in m["elements"]:
        f, t = el["from"], el["to"]
        rot = el.get("rotation")

        def rotate(p):
            if not rot:
                return p
            o, a = rot["origin"], math.radians(rot["angle"])
            x, y, z = p[0] - o[0], p[1] - o[1], p[2] - o[2]
            c, s = math.cos(a), math.sin(a)
            if rot["axis"] == "x":
                y, z = y * c - z * s, y * s + z * c
            elif rot["axis"] == "y":
                x, z = x * c + z * s, -x * s + z * c
            else:
                x, y = x * c - y * s, x * s + y * c
            return (x + o[0], y + o[1], z + o[2])
        for face, spec in el["faces"].items():
            tx = TEX[m["textures"][spec["texture"][1:]].split("/")[-1]]
            u0, v0, u1, v1 = spec["uv"]
            light = {"up": 1.0, "down": 0.5, "north": 0.8, "south": 0.8, "east": 0.65, "west": 0.65}[face]
            steps = int(max(abs(t[0] - f[0]), abs(t[1] - f[1]), abs(t[2] - f[2])) * scale * 1.5) + 4
            for i in range(steps + 1):
                for j in range(steps + 1):
                    a, b = i / steps, j / steps
                    if face in ("north", "south"):  # u runs to the viewer's right: -x on the north side, +x on the south
                        p = (t[0] - (t[0] - f[0]) * a, t[1] - (t[1] - f[1]) * b, f[2] if face == "north" else t[2])
                        if face == "south":
                            p = (f[0] + (t[0] - f[0]) * a, p[1], p[2])
                    elif face in ("east", "west"):
                        p = (t[0] if face == "east" else f[0], t[1] - (t[1] - f[1]) * b, f[2] + (t[2] - f[2]) * a)
                        if face == "east":
                            p = (p[0], p[1], t[2] - (t[2] - f[2]) * a)
                    elif face == "up":
                        p = (f[0] + (t[0] - f[0]) * a, t[1], f[2] + (t[2] - f[2]) * b)
                    else:
                        p = (f[0] + (t[0] - f[0]) * a, f[1], t[2] - (t[2] - f[2]) * b)
                    sx, sy2, d = project(*rotate(p))
                    X, Y = int(sx), int(sy2)
                    if 0 <= X < size and 0 <= Y < size and d > zb[Y][X]:
                        uu = u0 + (u1 - u0) * a
                        vv = v0 + (v1 - v0) * b
                        tw, th = tx.size
                        th = min(th, tw)  # an animated strip: its first frame
                        c = tx.getpixel((min(tw - 1, max(0, int(uu / 16 * tw))), min(th - 1, max(0, int(vv / 16 * th)))))
                        if c[3] == 0:
                            continue
                        zb[Y][X] = d
                        px[X, Y] = shade(c, light)
    return img


def preview(out, only=None):
    names = [n for n in MODELS if "elements" in MODELS[n] and (not only or n in only)]
    cell = 320
    sheet = Image.new("RGBA", (cell * 3, cell * len(names)), (30, 30, 34, 255))
    for i, n in enumerate(names):
        for k, yaw in enumerate((200, 20, 135)):
            sheet.paste(render(MODELS[n], yaw, cell), (k * cell, i * cell))
    sheet.save(out)


def main():
    G.D = sys.modules[__name__]
    make_textures()
    make_models()
    save_all()
    if len(sys.argv) > 2 and sys.argv[1] == "preview":
        preview(sys.argv[2], sys.argv[3:])
    print("casino decor:", len(MODELS), "models,", len(TEX), "textures")


if __name__ == "__main__":
    main()
