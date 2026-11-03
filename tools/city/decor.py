"""The city's decoration blocks: pixel-art textures, block models, blockstates, item models, loot tables and names.

    python3 -I tools/city/decor.py            # writes the assets and data
    python3 -I tools/city/decor.py preview OUT.png   # also renders every model from two sides for a quick look

The blocks themselves are registered in io.github.mobtalk.game.CityContent (ids, outlines and light must match the list there).
Textures are drawn here in code, in Minecraft's 16-pixels-a-block style with shading and wear; the models are boxes in the
usual 0..16 block space (a car and a slide reach into the neighbouring cells, -16..32).
"""
import json
import math
import os
import random
import sys

from PIL import Image

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
ASSETS = os.path.join(ROOT, "src", "main", "resources", "assets", "mobtalk")
DATA = os.path.join(ROOT, "src", "main", "resources", "data", "mobtalk")
TEX = {}  # name -> PIL image


# ---------------------------------------------------------------- textures

def rng(name):
    return random.Random(name)


def shade(c, k):
    return tuple(max(0, min(255, int(v * k))) for v in c[:3]) + ((c[3],) if len(c) > 3 else (255,))


def mix(a, b, t):
    return tuple(int(a[i] * (1 - t) + b[i] * t) for i in range(3)) + (255,)


def new(w=16, h=16, color=(0, 0, 0, 0)):
    return Image.new("RGBA", (w, h), color)


def noise_fill(img, base, amount, r, box=None):
    x0, y0, x1, y1 = box or (0, 0, img.width, img.height)
    px = img.load()
    for y in range(y0, y1):
        for x in range(x0, x1):
            px[x, y] = shade(base, 1 + r.uniform(-amount, amount))


def rect(img, box, color):
    px = img.load()
    x0, y0, x1, y1 = box
    for y in range(y0, y1):
        for x in range(x0, x1):
            px[x, y] = color


def bevel(img, box, light=1.15, dark=0.75):
    """Lighter top/left edge, darker bottom/right edge of a box: a pressed metal or painted plank look."""
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


def painted_metal(name, base, ribs=False, rust=0.04):
    r = rng(name)
    img = new()
    noise_fill(img, base, 0.05, r)
    if ribs:  # vertical pressed ribs of a dumpster wall
        for x in (2, 6, 10, 14):
            for y in range(1, 15):
                img.putpixel((x, y), shade(base, 1.18))
                img.putpixel((x + 1, y), shade(base, 0.8))
    bevel(img, (0, 0, 16, 16), 1.12, 0.78)
    px = img.load()
    for _ in range(int(256 * rust)):  # chipped paint and rust spots
        x, y = r.randrange(16), r.randrange(16)
        px[x, y] = r.choice([(120, 72, 40, 255), (96, 60, 36, 255), shade(base, 0.6)])
    return tex(name, img)


def concrete(name, base=(178, 176, 170)):
    r = rng(name)
    img = new()
    noise_fill(img, base, 0.06, r)
    px = img.load()
    for _ in range(14):
        x, y = r.randrange(16), r.randrange(16)
        px[x, y] = shade(base, r.choice([0.7, 0.8, 1.12]))
    for x in range(16):  # a cast seam
        if r.random() < 0.8:
            px[x, 11] = shade(px[x, 11], 0.88)
    return tex(name, img)


def planks(name, paint, wood=(150, 110, 70)):
    """Painted bench slats: two boards with a gap, chipped paint showing the wood."""
    r = rng(name)
    img = new()
    for y0 in (0, 8):
        noise_fill(img, paint, 0.05, r, (0, y0, 16, y0 + 7))
        rect(img, (0, y0 + 7, 16, y0 + 8), shade(paint, 0.45))
        bevel(img, (0, y0, 16, y0 + 7), 1.15, 0.8)
        for _ in range(2):
            x, y = r.randrange(16), y0 + r.randrange(1, 6)
            img.putpixel((x, y), shade(wood, r.uniform(0.85, 1.1)))
            if x + 1 < 16 and r.random() < 0.6:
                img.putpixel((x + 1, y), shade(wood, 0.9))
        for x in (1, 14):  # bolts
            img.putpixel((x, y0 + 3), (70, 70, 72, 255))
    return tex(name, img)


def glow(name, core=(255, 244, 196), edge=(232, 196, 112)):
    img = new()
    for y in range(16):
        for x in range(16):
            d = math.hypot(x - 7.5, y - 7.5) / 10.6
            img.putpixel((x, y), mix(core, edge, min(1, d)))
    return tex(name, img)


def pavers(name, base, grout=(70, 70, 70), border=None):
    """Concrete pavers, the "кирпичик" of every Russian town since the 2010s: 4x2 bricks in a running bond with dark joints."""
    r = rng(name)
    img = new()
    px = img.load()
    for row in range(8):
        y0 = row * 2
        off = 2 if row % 2 else 0
        for col in range(-1, 5):
            x0 = col * 4 + off
            tone = r.uniform(0.9, 1.08)
            for y in range(y0, y0 + 2):
                for x in range(max(0, x0), min(16, x0 + 4)):
                    c = shade(base, tone * r.uniform(0.96, 1.04))
                    if y == y0 + 1 or x == x0 + 3:
                        c = shade(grout + (255,), r.uniform(0.9, 1.1))
                    px[x, y] = c
    if border:  # a red edging course along one side
        for y in range(16):
            for x in range(3):
                px[x, y] = shade(border, r.uniform(0.92, 1.06)) if x < 2 else shade(grout + (255,), 1)
    return tex(name, img)


def palette():
    """Small swatches for tiny parts (4x4 each), so a beak or a bolt needs no texture of its own."""
    colors = {
        "black": (24, 24, 26), "chrome": (205, 210, 214), "chrome_dark": (128, 134, 140), "orange": (230, 120, 30),
        "red": (190, 30, 30), "amber": (240, 160, 30), "white": (238, 238, 232), "gold": (222, 180, 60), "rubber": (40, 40, 42),
        "glass": (120, 160, 180), "lamp": (255, 240, 190), "plate": (236, 236, 236), "dark_red": (110, 20, 20), "sand": (220, 200, 140),
        "grey": (110, 112, 116), "blue": (40, 70, 160),
    }
    img = new(16, 16)
    names = {}
    for i, (n, c) in enumerate(colors.items()):
        x, y = (i % 4) * 4, (i // 4) * 4
        r = rng(n)
        noise_fill(img, c + (255,), 0.03, r, (x, y, x + 4, y + 4))
        names[n] = (x, y)
    tex("palette", img)
    return names


PAL = {}


def flag_texture():
    img = new()
    stripes = [(240, 240, 240), (20, 60, 170), (210, 30, 40)]
    for y in range(16):
        for x in range(16):
            c = stripes[min(2, y * 3 // 16)]
            wave = 1 + 0.09 * math.sin((x + y * 0.3) / 2.2)
            img.putpixel((x, y), shade(c + (255,), wave))
    return tex("flag", img)


def red_cross():
    img = new()
    r = rng("cross")
    noise_fill(img, (246, 246, 240, 255), 0.02, r)
    rect(img, (6, 3, 10, 13), (206, 24, 30, 255))
    rect(img, (3, 6, 13, 10), (206, 24, 30, 255))
    for x in range(16):
        img.putpixel((x, 0), (120, 120, 124, 255)); img.putpixel((x, 15), (90, 90, 94, 255))
    for y in range(16):
        img.putpixel((0, y), (120, 120, 124, 255)); img.putpixel((15, y), (90, 90, 94, 255))
    return tex("red_cross", img)


def notice_board():
    r = rng("board")
    img = new()
    noise_fill(img, (176, 132, 82, 255), 0.08, r)  # cork
    rect(img, (0, 0, 16, 1), (88, 60, 36, 255)); rect(img, (0, 15, 16, 16), (70, 46, 28, 255))
    rect(img, (0, 0, 1, 16), (88, 60, 36, 255)); rect(img, (15, 0, 16, 16), (70, 46, 28, 255))
    for (x0, y0, x1, y1, col) in ((2, 2, 7, 8, (240, 240, 232)), (8, 2, 14, 6, (250, 246, 200)), (9, 7, 14, 13, (240, 240, 232)),
                                  (2, 9, 7, 14, (210, 228, 246))):
        rect(img, (x0, y0, x1, y1), col + (255,))
        for y in range(y0 + 1, y1 - 1, 2):
            for x in range(x0 + 1, x1 - 1):
                if r.random() < 0.8:
                    img.putpixel((x, y), (130, 130, 134, 255))
        img.putpixel(((x0 + x1) // 2, y0), (200, 30, 30, 255))  # a pin
    return tex("notice_board", img)


def ac_front():
    r = rng("ac")
    img = new()
    noise_fill(img, (232, 232, 228, 255), 0.02, r)
    for y in range(2, 14):
        for x in range(8, 15):
            d = math.hypot(x - 11, y - 7.5)
            if d < 3.6:
                img.putpixel((x, y), (60, 62, 66, 255) if (x + y) % 2 else (140, 142, 146, 255))
    for y in range(3, 13, 2):
        for x in range(1, 7):
            img.putpixel((x, y), (180, 180, 178, 255))
    img.putpixel((2, 14), (60, 200, 80, 255))  # a lamp
    bevel(img, (0, 0, 16, 16), 1.05, 0.8)
    return tex("ac_front", img)


def bus_sign():
    img = new()
    noise_fill(img, (246, 206, 40, 255), 0.03, rng("bus"))
    rect(img, (0, 0, 16, 1), (40, 40, 40, 255)); rect(img, (0, 15, 16, 16), (40, 40, 40, 255))
    rect(img, (0, 0, 1, 16), (40, 40, 40, 255)); rect(img, (15, 0, 16, 16), (40, 40, 40, 255))
    # a bus seen from the side: body, windows, wheels; and the letter "A" above
    rect(img, (3, 8, 13, 12), (30, 30, 30, 255))
    for x in (4, 6, 8, 10):
        rect(img, (x, 9, x + 1, 10), (246, 206, 40, 255))
    img.putpixel((5, 12), (30, 30, 30, 255)); img.putpixel((11, 12), (30, 30, 30, 255))
    for (x, y) in ((7, 2), (8, 2), (6, 3), (9, 3), (6, 4), (7, 4), (8, 4), (9, 4), (6, 5), (9, 5), (6, 6), (9, 6)):
        img.putpixel((x, y), (30, 30, 30, 255))
    return tex("bus_sign", img)


def tyre(name, paint, tread=(30, 30, 30)):
    r = rng(name)
    img = new()
    noise_fill(img, paint + (255,), 0.05, r)
    for y in range(16):
        for x in range(16):
            if (x + 2 * (y // 4)) % 5 == 0 and r.random() < 0.5:  # the tread showing through the paint
                img.putpixel((x, y), shade(tread + (255,), r.uniform(0.95, 1.1)))
    return tex(name, img)


def dish():
    img = new()
    for y in range(16):
        for x in range(16):
            d = math.hypot(x - 7.5, y - 7.5)
            c = (200, 202, 206) if d < 7.5 else (150, 152, 156)
            img.putpixel((x, y), shade(c + (255,), 1.1 - d / 30))
    img.putpixel((7, 7), (60, 60, 60, 255)); img.putpixel((8, 8), (60, 60, 60, 255))
    return tex("dish", img)


# Soviet cars, all front to the north, within the three-block footprint (z -16..32). For each: body (z0, z1, top), cabin
# (z0, z1, top), wheel centres (z), front style, paint. The body side texture spans the body, so wheel arches follow the wheels.
CARS = {
    "white": dict(kind="zhiguli", paint=(228, 226, 214)), "red": dict(kind="zhiguli", paint=(172, 36, 34)),
    "green": dict(kind="zhiguli", paint=(58, 108, 70)), "blue": dict(kind="zhiguli", paint=(52, 88, 150)),
    "volga_black": dict(kind="volga", paint=(30, 32, 36)), "volga_beige": dict(kind="volga", paint=(214, 196, 150)),
    "moskvich": dict(kind="moskvich", paint=(214, 120, 36)), "moskvich_grey": dict(kind="moskvich", paint=(150, 156, 150)),
    "zaporozhets": dict(kind="zaz", paint=(120, 176, 200)), "zaporozhets_yellow": dict(kind="zaz", paint=(222, 186, 60)),
    "uaz": dict(kind="uaz", paint=(98, 110, 72)), "uaz_ambulance": dict(kind="uaz", paint=(232, 232, 226), ambulance=True),
}
KINDS = {  # body z0, z1, top; cabin z0, z1, top; wheels; half-width inset of the cabin
    "zhiguli": dict(body=(-15, 31, 9.5), cab=(-5, 13, 16), wheels=(-9, 24), inset=0.6),
    "volga": dict(body=(-15, 31, 9), cab=(-3, 15, 15.5), wheels=(-8, 25), inset=0.8),
    "moskvich": dict(body=(-13, 28, 9.5), cab=(-4, 13, 16), wheels=(-7, 21), inset=0.6),
    "zaz": dict(body=(-8, 24, 9), cab=(-3, 15, 15.5), wheels=(-3, 19), inset=1.0),
    "uaz": dict(body=(-12, 28, 10), cab=(-11.5, 27.5, 21), wheels=(-6, 21), inset=0.3),
}


def car_atlas(name, spec):
    """64x64: body side (48x14) at (0,0), cabin side (20x7) at (0,16), roof (16x20) at (44,40), hood/boot top (16x16) at (44,16),
    front (18x8) at (0,32), rear (18x8) at (20,32), windscreen (16x7) at (0,42), back glass (16x7) at (20,42), wheel (6x6) at (40,32),
    van upper side (40x12) at (0,50)."""
    kind, base = spec["kind"], spec["paint"]
    k = KINDS[kind]
    r = rng("car" + name)
    img = new(64, 64)
    b = base + (255,)
    chrome, black, glass_dark, glass_light = (205, 210, 214, 255), (24, 24, 26, 255), (60, 86, 102), (180, 208, 220)
    z0, z1, _ = k["body"]
    # side: body, door seams, handles, wheel arches
    noise_fill(img, b, 0.025, r, (0, 0, 48, 14))
    for x in range(48):
        img.putpixel((x, 0), shade(b, 1.12)); img.putpixel((x, 13), shade(b, 0.6))
        if kind in ("zhiguli", "volga", "moskvich"):
            img.putpixel((x, 5), chrome)  # chrome moulding
    if spec.get("ambulance"):
        for x in range(48):
            img.putpixel((x, 7), (200, 30, 30, 255)); img.putpixel((x, 8), (200, 30, 30, 255))
    doors = {"zhiguli": (17, 31), "volga": (19, 33), "moskvich": (17, 30), "zaz": (14, 30), "uaz": (6, 20, 30)}[kind]
    for x in doors:
        for y in range(0, 12):
            img.putpixel((x, y), shade(b, 0.62))
        img.putpixel((x + 2, 3), chrome)
    arches = [round((w - z0) / (z1 - z0) * 48) for w in k["wheels"]]
    for cx in arches:
        for x in range(cx - 5, cx + 5):
            for y in range(7, 14):
                if 0 <= x < 48 and math.hypot(x - cx + 0.5, y - 14) < 5.2:
                    img.putpixel((x, y), (20, 20, 22, 255))
    img.putpixel((1, 3), (240, 150, 30, 255))  # side indicator
    if kind == "zaz":  # the engine air intakes behind the doors
        rect(img, (36, 2, 42, 6), (40, 40, 44, 255))
        for x in range(36, 42, 2):
            img.putpixel((x, 3), (90, 90, 96, 255)); img.putpixel((x, 4), (90, 90, 96, 255))
    # cabin side: glass with body-coloured pillars
    noise_fill(img, b, 0.02, r, (0, 16, 20, 23))
    for (x0, x1) in ((1, 9), (10, 19)):
        for x in range(x0, x1):
            for y in range(17, 23):
                t = (x - x0 + (23 - y)) / 12
                img.putpixel((x, y), mix(glass_dark, glass_light, max(0, min(1, 0.25 + 0.5 * math.sin(t * 3.1)))))
    # van upper side: a row of windows, the sliding door, (on the ambulance) a red cross
    noise_fill(img, b, 0.02, r, (0, 50, 40, 62))
    for x in range(40):
        img.putpixel((x, 50), shade(b, 1.15)); img.putpixel((x, 61), shade(b, 0.75))
    for x0, x1 in ((1, 8), (10, 17), (19, 26), (28, 35)):
        if spec.get("ambulance") and x0 in (19, 28):
            continue
        for x in range(x0, x1):
            for y in range(52, 57):
                img.putpixel((x, y), mix(glass_dark, glass_light, 0.3 + 0.4 * math.sin((x - x0 + 57 - y) / 3)))
    if spec.get("ambulance"):
        rect(img, (25, 52, 28, 60), (200, 30, 30, 255)); rect(img, (22, 55, 31, 58), (200, 30, 30, 255))
    # roof and hood
    noise_fill(img, b, 0.025, r, (44, 40, 60, 60))
    bevel(img, (44, 40, 60, 60), 1.12, 0.8)
    if kind == "uaz":  # roof gutter ribs
        for y in range(42, 60, 4):
            for x in range(45, 59):
                img.putpixel((x, y), shade(b, 0.85))
    noise_fill(img, b, 0.025, r, (44, 16, 60, 32))
    for y in range(16, 32):
        img.putpixel((52, y), shade(b, 1.1))  # the hood's crease
    # front
    noise_fill(img, b, 0.02, r, (0, 32, 18, 40))

    def lamp(cx, cy, rad=1.0):
        for x in range(int(cx - rad - 1), int(cx + rad + 2)):
            for y in range(int(cy - rad - 1), int(cy + rad + 2)):
                if 0 <= x < 18 and 32 <= y < 40 and math.hypot(x + 0.5 - cx, y + 0.5 - cy) <= rad + 0.2:
                    img.putpixel((x, y), (255, 246, 210, 255))
    if kind == "zhiguli":  # chrome surround, a fine grille, four round lamps
        rect(img, (0, 33, 18, 37), chrome)
        for x in range(5, 13):
            for y in range(34, 36):
                img.putpixel((x, y), (60, 62, 66, 255) if x % 2 else (150, 156, 160, 255))
        for cx in (2, 4, 14, 16):
            lamp(cx, 35, 0.8)
    elif kind == "volga":  # the wide grille of vertical chrome bars, lamps in square bezels, the deer stands on the hood
        rect(img, (0, 33, 18, 37), chrome)
        for x in range(4, 14):
            for y in range(33, 37):
                img.putpixel((x, y), chrome if x % 2 == 0 else (40, 42, 46, 255))
        rect(img, (0, 33, 4, 37), (170, 176, 180, 255)); rect(img, (14, 33, 18, 37), (170, 176, 180, 255))
        lamp(2, 35, 1.0); lamp(16, 35, 1.0)
    elif kind == "moskvich":  # black grille with a chrome bar, rectangular lamps
        rect(img, (0, 33, 18, 37), (36, 38, 40, 255))
        for x in range(0, 18):
            img.putpixel((x, 35), chrome)
        rect(img, (1, 33, 4, 36), (255, 246, 210, 255)); rect(img, (14, 33, 17, 36), (255, 246, 210, 255))
    elif kind == "zaz":  # no grille (the engine is at the back): a chrome moustache and two round lamps
        for x in range(5, 13):
            img.putpixel((x, 35), chrome)
        img.putpixel((8, 34), chrome); img.putpixel((9, 34), chrome)
        lamp(2.5, 34.5, 1.2); lamp(15.5, 34.5, 1.2)
    elif kind == "uaz":  # flat nose: small grille slats, round lamps wide apart
        for x in range(6, 12):
            for y in range(33, 37):
                if y % 2:
                    img.putpixel((x, y), (40, 42, 44, 255))
        lamp(2.5, 35, 1.2); lamp(15.5, 35, 1.2)
        if spec.get("ambulance"):
            rect(img, (6, 37, 12, 38), (200, 30, 30, 255))
    rect(img, (6, 37, 12, 39), (236, 236, 236, 255))  # the plate
    img.putpixel((7, 38), black); img.putpixel((9, 38), black); img.putpixel((11, 38), black)
    rect(img, (0, 39, 18, 40), (170, 176, 180, 255))
    # rear: tail lights, plate (on the Zaporozhets the louvred engine lid)
    noise_fill(img, b, 0.02, r, (20, 32, 38, 40))
    for x0 in (20, 33):
        rect(img, (x0, 33, x0 + 5, 36), (180, 24, 24, 255))
        rect(img, (x0 + (3 if x0 == 20 else 0), 33, x0 + (5 if x0 == 20 else 2), 34), (240, 150, 30, 255))
    if kind == "zaz":
        for y in range(33, 37, 2):
            for x in range(26, 32):
                img.putpixel((x, y), shade(b, 0.6))
    rect(img, (26, 37 if kind == "zaz" else 35, 32, 39 if kind == "zaz" else 37), (236, 236, 236, 255))
    rect(img, (20, 39, 38, 40), (170, 176, 180, 255))
    # glass front and back
    for (x0, y0) in ((0, 42), (20, 42)):
        for x in range(x0, x0 + 16):
            for y in range(y0, y0 + 7):
                t = (x - x0) / 16 + (y - y0) / 14
                img.putpixel((x, y), mix(glass_dark, glass_light, 0.5 + 0.45 * math.sin(t * 5)))
        for x in range(x0, x0 + 16):
            img.putpixel((x, y0), shade(b, 0.9))
    if kind == "uaz":  # the split windscreen
        for y in range(42, 49):
            img.putpixel((8, y), shade(b, 0.9))
    # wheel: tyre with a hubcap (the UAZ has painted steel wheels)
    hub = shade(b, 0.8) if kind == "uaz" else (200, 205, 210, 255)
    for x in range(40, 46):
        for y in range(32, 38):
            d = math.hypot(x - 42.5, y - 34.5)
            img.putpixel((x, y), hub if d < 1 else (96, 98, 102, 255) if d < 2.2 else (30, 30, 32, 255))
    return tex(f"car_{name}", img)


def make_textures():
    PAL.update(palette())
    concrete("concrete")
    planks("bench_green", (64, 120, 70, 255))
    painted_metal("metal_dark", (62, 64, 68, 255))
    painted_metal("metal_grey", (128, 130, 134, 255), rust=0.02)
    painted_metal("metal_white", (226, 226, 222, 255), rust=0.01)
    painted_metal("dumpster", (48, 104, 64, 255), ribs=True, rust=0.06)
    painted_metal("metal_red", (196, 44, 38, 255))
    painted_metal("metal_blue", (44, 92, 176, 255))
    painted_metal("metal_yellow", (232, 190, 40, 255))
    glow("lamp_glass")
    flag_texture()
    red_cross()
    notice_board()
    ac_front()
    painted_metal("ac_side", (222, 222, 218, 255), rust=0.0)
    bus_sign()
    tyre("tyre_white", (236, 236, 230), (150, 150, 150))
    tyre("tyre_paint", (230, 90, 70))
    dish()
    sand = new()
    noise_fill(sand, (222, 204, 146, 255), 0.07, rng("sand"))
    tex("sand", sand)
    for name, spec in CARS.items():
        car_atlas(name, spec)
    painted_metal("metal_green", (66, 128, 62, 255), rust=0.0)
    painted_metal("metal_green_dark", (40, 84, 44, 255), rust=0.0)
    painted_metal("metal_black", (34, 36, 38, 255), rust=0.02)
    painted_metal("metal_lamp", (146, 150, 152, 255), rust=0.0)
    painted_metal("metal_lamp_dark", (70, 72, 76, 255), rust=0.0)
    painted_metal("bin_brown", (112, 66, 42, 255), ribs=True, rust=0.02)
    planks("bench_wood", (176, 120, 70, 255), (150, 100, 60))
    pavers("paving_grey", (150, 148, 142, 255))
    pavers("paving_red", (150, 70, 58, 255), (90, 60, 55))
    concrete("curb", (168, 166, 160))


# ---------------------------------------------------------------- models

def auto_uv(face, f, t):
    """Blockbench-style automatic UV: the face's own extent in the 16x16 texture, shifted into range when it reaches outside."""
    x0, y0, z0 = f
    x1, y1, z1 = t
    if face in ("north", "south"):
        u0, u1, v0, v1 = x0, x1, 16 - y1, 16 - y0
    elif face in ("east", "west"):
        u0, u1, v0, v1 = z0, z1, 16 - y1, 16 - y0
    else:
        u0, u1, v0, v1 = x0, x1, z0, z1

    def fit(a, b):
        if b - a > 16:
            return 0, 16
        while a < 0:
            a, b = a + 16, b + 16
        while b > 16:
            a, b = a - 16, b - 16
        if a < 0:  # straddles a block edge: take the same span from the texture's start
            a, b = 0, b - a
        return a, b
    u0, u1 = fit(u0, u1)
    v0, v1 = fit(v0, v1)
    return [round(u0, 3), round(v0, 3), round(u1, 3), round(v1, 3)]


FACES = ("north", "south", "east", "west", "up", "down")


def box(f, t, texture, faces=FACES, uv=None, rotation=None, tint=None):
    """An element: from/to in pixels, one texture for its faces (or a dict face -> (texture, uv))."""
    el = {"from": list(f), "to": list(t), "faces": {}}
    for face in faces:
        if isinstance(texture, dict):
            if face not in texture:
                continue
            tx, fuv = texture[face]
        else:
            tx, fuv = texture, (uv.get(face) if isinstance(uv, dict) else uv)
        el["faces"][face] = {"texture": "#" + tx, "uv": list(fuv) if fuv else auto_uv(face, f, t)}
    if rotation:
        el["rotation"] = rotation
    return el


def pal(name, size=2):
    x, y = PAL[name]
    return [x / 1.0, y / 1.0, x + size * 1.0, y + size * 1.0]


def pal_box(f, t, name, faces=FACES, rotation=None):
    return box(f, t, "palette", faces, {fc: pal(name, 2) for fc in faces}, rotation)


MODELS = {}
CUBES = {}  # plain full blocks: name -> texture
FENCES = {}  # railings: name -> paint texture


def model(name, textures, elements, particle=None, ao=True):
    m = {"parent": "minecraft:block/block", "textures": {k: f"mobtalk:block/city/{v}" for k, v in textures.items()},
         "elements": elements}
    m["textures"]["particle"] = m["textures"][particle or next(iter(textures))]
    if not ao:
        m["ambientocclusion"] = False
    MODELS[name] = m


def bench():
    els = []
    for x in (1, 12):  # concrete supports
        els.append(box((x, 0, 4), (x + 3, 7, 13), "concrete"))
    for z in (4, 6.5, 9):  # seat slats
        els.append(box((0, 7, z), (16, 8.5, z + 2), "wood"))
    for y in (10, 13):  # back slats
        els.append(box((0, y, 12), (16, y + 2, 13.5), "wood"))
    for x in (2, 13):  # back irons
        els.append(box((x, 7, 13.5), (x + 1, 15.5, 14.5), "metal"))
    model("bench", {"concrete": "concrete", "wood": "bench_green", "metal": "metal_dark"}, els, "wood")


def urn():
    els = [box((5, 0, 5), (11, 2, 11), "concrete"), box((4, 2, 4), (12, 9, 12), "concrete", ["north", "south", "east", "west", "down"]),
           box((3.5, 9, 3.5), (12.5, 11, 12.5), "concrete", ["north", "south", "east", "west", "down"]),
           box((3.5, 10.9, 3.5), (12.5, 11, 12.5), {"up": ("concrete", [3.5, 3.5, 12.5, 12.5])}),
           box((5, 7, 5), (11, 10.95, 11), {"up": ("palette", pal("black"))})]
    # the rim's top drawn as a frame around a dark opening
    els[3] = box((3.5, 10.9, 3.5), (12.5, 11, 5), "concrete", ["up"])
    els.append(box((3.5, 10.9, 11), (12.5, 11, 12.5), "concrete", ["up"]))
    els.append(box((3.5, 10.9, 5), (5, 11, 11), "concrete", ["up"]))
    els.append(box((11, 10.9, 5), (12.5, 11, 11), "concrete", ["up"]))
    model("urn", {"concrete": "concrete", "palette": "palette"}, els, "concrete")


def lamps():
    model("lamp_pole", {"metal": "metal_grey"}, [box((7, 0, 7), (9, 16, 9), "metal"), box((6.5, 0, 6.5), (9.5, 1.5, 9.5), "metal")])
    els = [box((7, 0, 7), (9, 11, 9), "metal"),
           box((7, 10, 1), (9, 12, 9), "metal"),  # the arm to the north
           box((7.5, 11, 4), (8.5, 13, 5), "metal"),
           box((5, 9.5, -5), (11, 12, 3), "housing", ["north", "south", "east", "west", "up"]),  # housing over the street
           box((5.5, 9, -4.5), (10.5, 9.5, 2.5), {"down": ("glass", [3, 4, 13, 12])})]
    model("street_lamp", {"metal": "metal_grey", "housing": "metal_dark", "glass": "lamp_glass"}, els, "metal", ao=False)


def dumpster():
    els = [box((1, 1.5, 2), (15, 13, 14), "green"),
           box((0.5, 13, 1.5), (15.5, 14, 14.5), "green"),  # lid
           box((0, 9, 3), (1, 10, 13), "metal"), box((15, 9, 3), (16, 10, 13), "metal")]  # side handles
    for x in (2, 12):
        for z in (3, 11):
            els.append(pal_box((x, 0, z), (x + 2, 1.5, z + 2), "rubber"))
    model("dumpster", {"green": "dumpster", "metal": "metal_dark", "palette": "palette"}, els, "green")


def playground():
    # swing: a frame three cells wide, the seat on chains in the middle
    els = [box((-14, 0, 7), (-12, 30, 9), "frame"), box((28, 0, 7), (30, 30, 9), "frame"),
           box((-14, 28, 7), (30, 30, 9), "frame"),
           box((-15, 0, 4), (-11, 1, 12), "frame"), box((27, 0, 4), (31, 1, 12), "frame"),
           box((3, 11, 5), (13, 12, 11), "seat")]
    for x in (3.5, 12):
        els.append(pal_box((x, 12, 7.5), (x + 0.5, 28, 8.5), "chrome_dark", ["north", "south", "east", "west"]))
    model("swing", {"frame": "metal_red", "seat": "bench_green", "palette": "palette"}, els, "frame")
    # slide: ladder at the back (south), platform, the chute down to the north
    els = [box((2, 0, 13), (3, 18, 14), "rail"), box((13, 0, 13), (14, 18, 14), "rail")]
    for y in (3, 7, 11, 15):
        els.append(box((3, y, 13), (13, y + 1, 14), "rail"))
    els += [box((2, 15, 7), (14, 16, 14), "deck"), box((2, 16, 7), (3, 20, 14), "rail"), box((13, 16, 7), (14, 20, 14), "rail"),
            box((3, 15, -14), (13, 16, 8), "chute", rotation={"origin": [8, 15, 8], "axis": "x", "angle": -45}),
            box((2, 15, -14), (3, 17, 8), "deck", rotation={"origin": [8, 15, 8], "axis": "x", "angle": -45}),
            box((13, 15, -14), (14, 17, 8), "deck", rotation={"origin": [8, 15, 8], "axis": "x", "angle": -45}),
            box((3, 0, -9), (13, 1, -6), "chute"),
            box((2, 0, 7), (3, 15, 8), "rail"), box((13, 0, 7), (14, 15, 8), "rail")]
    model("slide", {"rail": "metal_blue", "deck": "metal_yellow", "chute": "metal_white"}, els, "deck")
    els = [box((1, 0, 7), (2.5, 16, 9), "post"), box((13.5, 0, 7), (15, 16, 9), "post"),
           box((1, 14, 7.5), (15, 15, 8.5), "bar")]
    model("chin_up_bar", {"post": "metal_blue", "bar": "metal_grey"}, els, "post")


def flags():
    model("flagpole", {"pole": "metal_white"}, [box((7.25, 0, 7.25), (8.75, 16, 8.75), "pole")])
    els = [box((7.25, 0, 7.25), (8.75, 15, 8.75), "pole"), pal_box((7, 15, 7), (9, 16.5, 9), "gold"),
           box((8.75, 3, 7.75), (24.75, 14, 8.25), {"north": ("flag", [0, 0, 16, 16]), "south": ("flag", [16, 0, 0, 16]),
                                                     "up": ("flag", [0, 0, 16, 1]), "down": ("flag", [0, 15, 16, 16]),
                                                     "east": ("flag", [15, 0, 16, 16])})]
    model("flag", {"pole": "metal_white", "flag": "flag", "palette": "palette"}, els, "flag")


def wall_things():
    model("red_cross", {"plate": "red_cross", "metal": "metal_grey"},
          [box((2, 2, 15), (14, 14, 16), {"north": ("plate", [2, 2, 14, 14]), "south": ("metal", None), "east": ("metal", None),
                                           "west": ("metal", None), "up": ("metal", None), "down": ("metal", None)})], "plate", ao=False)
    model("notice_board", {"board": "notice_board", "frame": "bench_green"},
          [box((1, 2, 15), (15, 14, 16), {"north": ("board", [1, 2, 15, 14]), "south": ("frame", None), "east": ("frame", None),
                                           "west": ("frame", None), "up": ("frame", None), "down": ("frame", None)}),
           box((0.5, 13.5, 14), (15.5, 15, 16), "frame")], "board")
    model("air_conditioner", {"front": "ac_front", "side": "ac_side", "metal": "metal_grey"},
          [box((2, 4, 9), (14, 12, 15), {"north": ("front", [2, 4, 14, 12]), "south": ("side", None), "east": ("side", None),
                                          "west": ("side", None), "up": ("side", None), "down": ("side", None)}),
           box((3, 3, 10), (4, 4, 16), "metal"), box((12, 3, 10), (13, 4, 16), "metal")], "front")
    rot = {"origin": [8, 8, 12], "axis": "x", "angle": 22.5}
    model("satellite_dish", {"dish": "dish", "metal": "metal_grey"},
          [box((7, 7, 12), (9, 9, 16), "metal"), box((3, 3, 10), (13, 13, 11), {"north": ("dish", [0, 0, 16, 16]),
                                                                                 "south": ("metal", None), "east": ("metal", None),
                                                                                 "west": ("metal", None), "up": ("metal", None),
                                                                                 "down": ("metal", None)}, rotation=rot),
           box((7.5, 7.5, 3), (8.5, 8.5, 10), "metal", rotation=rot)], "dish")


def street_things():
    els = [box((7.25, 0, 7.25), (8.75, 16, 8.75), "pole"),
           box((3, 8, 7), (13, 16, 7.5), {"north": ("sign", [0, 0, 16, 16]), "south": ("sign", [0, 0, 16, 16]), "east": ("pole", None),
                                          "west": ("pole", None), "up": ("pole", None), "down": ("pole", None)})]
    model("bus_stop_sign", {"pole": "metal_grey", "sign": "bus_sign"}, els, "sign")
    # a tyre swan: the half-buried tyre, wings cut from it, a neck and a head with a beak
    els = [box((1, 0, 1), (15, 3, 15), "tyre", ["north", "south", "east", "west", "up"]),
           box((3, 2.9, 3), (13, 3, 13), {"up": ("sand", [3, 3, 13, 13])}),
           box((4, 3, 5), (12, 7, 13), "tyre"),
           box((3, 5, 7), (4, 9, 13), "tyre"), box((12, 5, 7), (13, 9, 13), "tyre"),
           box((7, 6, 4), (9, 14, 6), "tyre"), box((6.5, 13, 2), (9.5, 15, 6), "tyre"),
           pal_box((7.25, 13.25, 0.5), (8.75, 14.5, 2), "orange"),
           pal_box((6.4, 14, 3), (6.5, 14.6, 3.6), "black", ["west"]), pal_box((9.5, 14, 3), (9.6, 14.6, 3.6), "black", ["east"])]
    model("tyre_swan", {"tyre": "tyre_white", "sand": "sand", "palette": "palette"}, els, "tyre")


def cars():
    """The Soviet cars of CARS: body, cabin (or the van's upper box), chrome bumpers, four wheels; front to the north."""
    def uv(x0, y0, x1, y1):
        return [x0 / 4, y0 / 4, x1 / 4, y1 / 4]
    for name, spec in CARS.items():
        k, t = KINDS[spec["kind"]], "car"
        bz0, bz1, btop = k["body"]
        cz0, cz1, ctop = k["cab"]
        i = k["inset"]
        els = [box((-1, 2.5, bz0), (17, btop, bz1), {"east": (t, uv(0, 0, 48, 14)), "west": (t, uv(48, 0, 0, 14)),
                                                    "north": (t, uv(0, 32, 18, 40)), "south": (t, uv(20, 32, 38, 40)),
                                                    "up": (t, uv(44, 16, 60, 32)), "down": ("palette", pal("black"))})]
        if spec["kind"] == "uaz":  # the bread-loaf van: one tall box with a row of windows, the windscreen in front
            els.append(box((-1 + i, btop, cz0), (17 - i, ctop, cz1), {"east": (t, uv(0, 50, 40, 62)), "west": (t, uv(40, 50, 0, 62)),
                                                                    "north": (t, uv(0, 42, 16, 49)), "south": (t, uv(20, 42, 36, 49)),
                                                                    "up": (t, uv(44, 40, 60, 60))}))
        else:
            els.append(box((i, btop, cz0), (16 - i, ctop, cz1), {"east": (t, uv(0, 16, 20, 23)), "west": (t, uv(20, 16, 0, 23)),
                                                              "north": (t, uv(0, 42, 16, 49)), "south": (t, uv(20, 42, 36, 49)),
                                                              "up": (t, uv(44, 40, 60, 60))}))
            els.append(box((i + 0.3, ctop, cz0 + 0.5), (16 - i - 0.3, ctop + 0.4, cz1 - 0.5), {"up": (t, uv(44, 40, 60, 60))}))
        bump = "chrome" if spec["kind"] != "uaz" else "black"
        els += [pal_box((-1.5, 2, bz0 - 1), (17.5, 3.5, bz0), bump), pal_box((-1.5, 2, bz1), (17.5, 3.5, bz1 + 1), bump)]
        if spec["kind"] == "volga":  # the deer on the hood
            els += [pal_box((7.5, btop, bz0 + 1), (8.5, btop + 1, bz0 + 3), "chrome"), pal_box((7.6, btop + 1, bz0 + 1), (8.4, btop + 2, bz0 + 1.8), "chrome")]
        if spec["kind"] == "zaz":  # the air-intake ears
            els += [pal_box((-1.8, btop - 1, cz1 - 5), (-1, btop + 2, cz1 - 1), "black"), pal_box((17, btop - 1, cz1 - 5), (17.8, btop + 2, cz1 - 1), "black")]
        if spec.get("ambulance"):  # the blue beacon
            els.append(box((6.5, ctop, cz0 + 2), (9.5, ctop + 2, cz0 + 4), {f: ("palette", pal("blue")) for f in FACES}))
        for wz in k["wheels"]:
            for x, faces in ((-1.5, ["west", "north", "south", "down", "up"]), (15.5, ["east", "north", "south", "down", "up"])):
                els.append(box((x, 0, wz - 3), (x + 2, 6, wz + 3), {f: (t, uv(40, 32, 46, 38)) if f in ("east", "west") else ("palette", pal("rubber"))
                                                                    for f in faces}))
        model(f"car_{name}", {"car": f"car_{name}", "palette": "palette"}, els, "car")


LAMP_PAINTS = (("grey", "metal_lamp", "metal_lamp_dark"), ("green", "metal_green", "metal_green_dark"))


def pushchino_kit():
    """Street furniture after the Pushchino panoramas: the tall green Soviet lamp with one or two curved arms, a granite curb,
    a park bench of light slats on a black frame, a brown metal bin and a bicycle rack."""
    # the same lamp in grey (today's usual paint, after the 2026 panoramas) and in the old green (a 2012 panorama)
    for colour, metal, dark in LAMP_PAINTS:
        model(f"lamp_{colour}_base", {"metal": metal, "dark": dark},
              [box((5.5, 0, 5.5), (10.5, 2, 10.5), "dark"), box((6, 2, 6), (10, 16, 10), "metal"),
               box((5.8, 5, 5.8), (10.2, 9, 6), "dark", ["north"])])  # the service hatch
        model(f"lamp_{colour}_pole", {"metal": metal, "dark": dark},
              [box((6.5, 0, 6.5), (9.5, 16, 9.5), "metal"), box((6.2, 14, 6.2), (9.8, 15, 9.8), "dark")])

    def arm(sign):
        """An arm curving up and out to the east (sign 1) or the west (-1): stepped pipe, then the cobra head."""
        els = []
        steps = [(9, 15, 6, 7.5), (15, 21, 7, 9), (21, 26, 8.5, 10.5)]
        for x0, x1, y0, y1 in steps:
            a, b = (x0, x1) if sign > 0 else (16 - x1, 16 - x0)
            els.append(box((a, y0, 7), (b, y1, 9), "metal"))
        a, b = (24, 32) if sign > 0 else (-16, -8)
        els.append(box((a, 9.5, 5.5), (b, 12, 10.5), "dark", ["north", "south", "east", "west", "up"]))
        els.append(box((a + 0.5, 9.2, 6), (b - 0.5, 9.5, 10), {"down": ("glass", [2, 4, 14, 12])}))
        return els
    base = [box((6.5, 0, 6.5), (9.5, 8, 9.5), "metal"), box((6, 7, 6), (10, 8, 10), "dark")]
    for colour, metal, dark in LAMP_PAINTS:
        model(f"lamp_{colour}_double", {"metal": metal, "dark": dark, "glass": "lamp_glass"}, base + arm(1) + arm(-1), "metal", ao=False)
        model(f"lamp_{colour}_single", {"metal": metal, "dark": dark, "glass": "lamp_glass"}, base + arm(1), "metal", ao=False)
    model("curb", {"stone": "curb"}, [box((0, 0, 0), (16, 5, 3.5), "stone"), box((0, 5, 0.5), (16, 5.5, 3), "stone")])
    els = []
    for x in (1.5, 13):  # black frame legs and arm rests
        els += [box((x, 0, 4), (x + 1.5, 7, 5.5), "frame"), box((x, 0, 10.5), (x + 1.5, 7, 12), "frame"),
                box((x, 7, 4), (x + 1.5, 7.5, 12.5), "frame"), box((x, 7, 12), (x + 1.5, 15, 13), "frame")]
    for z in (4.5, 6.8, 9.1):
        els.append(box((0, 7.5, z), (16, 8.7, z + 2), "wood"))
    for y in (10, 12.5):
        els.append(box((0, y, 12.5), (16, y + 2, 13.5), "wood"))
    model("park_bench", {"wood": "bench_wood", "frame": "metal_black"}, els, "wood")
    model("bin_metal", {"metal": "bin_brown", "dark": "metal_black", "palette": "palette"},
          [box((5, 0, 5), (11, 1, 11), "dark"), box((4.5, 1, 4.5), (11.5, 10, 11.5), "metal", ["north", "south", "east", "west"]),
           box((4.2, 10, 4.2), (11.8, 11, 11.8), "dark", ["north", "south", "east", "west", "up"]),
           box((5.5, 9.5, 5.5), (10.5, 10.95, 10.5), {"up": ("palette", pal("black"))})], "metal")
    els = [box((0, 0, 7.5), (16, 0.6, 8.5), "frame")]
    for x in (2, 7.5, 13):  # three hoops
        els += [box((x, 0, 4), (x + 1, 10, 5), "frame"), box((x, 0, 11), (x + 1, 10, 12), "frame"),
                box((x, 10, 4), (x + 1, 11, 12), "frame")]
    model("bike_rack", {"frame": "metal_black"}, els, "frame")
    # the boulevard post-top lamp (bul. Nasibullina): black pole, a lantern with a round hat; two blocks: pole, then top
    model("lamp_black_pole", {"metal": "metal_black"},
          [box((5.5, 0, 5.5), (10.5, 3, 10.5), "metal"), box((6.5, 3, 6.5), (9.5, 16, 9.5), "metal"),
           box((6.2, 12, 6.2), (9.8, 13, 9.8), "metal")])
    model("lamp_black_top", {"metal": "metal_black", "glass": "lamp_glass"},
          [box((7, 0, 7), (9, 5, 9), "metal"), box((6, 5, 6), (10, 6, 10), "metal"),
           box((5.5, 6, 5.5), (10.5, 11.5, 10.5), "glass"), box((3.5, 11.5, 3.5), (12.5, 12.5, 12.5), "metal"),
           box((5, 12.5, 5), (11, 13.5, 11), "metal"), box((7.5, 13.5, 7.5), (8.5, 15, 8.5), "metal")], "metal", ao=False)
    # the long backless bench of the boulevard: thick wooden slats on concrete blocks
    els = [box((1, 0, 4), (4, 6, 12), "stone"), box((12, 0, 4), (15, 6, 12), "stone")]
    for z in (3, 5.6, 8.2, 10.8):
        els.append(box((0, 6, z), (16, 8, z + 2.3), "wood"))
    model("long_bench", {"wood": "bench_wood", "stone": "curb"}, els, "wood")
    # Soviet yard railings: thin posts, two rails, pickets; joined like fences (a multipart blockstate, see save_all)
    for name, paint in (("fence_green", "metal_green"), ("fence_black", "metal_black")):
        FENCES[name] = paint
        MODELS[name + "_post"] = {"parent": "minecraft:block/block", "textures": {"m": f"mobtalk:block/city/{paint}",
                                  "particle": f"mobtalk:block/city/{paint}"},
                                  "elements": [box((7, 0, 7), (9, 13, 9), "m"), box((6.5, 12.5, 6.5), (9.5, 13.5, 9.5), "m")]}
        side = [box((7.5, 4, 0), (8.5, 5, 7), "m"), box((7.5, 10.5, 0), (8.5, 11.5, 7), "m")]
        for z in (1.5, 4.5):
            side.append(box((7.6, 0.5, z), (8.4, 11.5, z + 0.8), "m"))
        MODELS[name + "_side"] = {"parent": "minecraft:block/block", "textures": {"m": f"mobtalk:block/city/{paint}",
                                  "particle": f"mobtalk:block/city/{paint}"}, "elements": side}


def make_models():
    bench(); urn(); lamps(); dumpster(); playground(); flags(); wall_things(); street_things(); cars(); pushchino_kit()
    for name in ("paving_grey", "paving_red"):
        CUBES[name] = name


# ---------------------------------------------------------------- output

NAMES = {
    "bench": ("Скамейка у подъезда", "Bench"), "urn": ("Бетонная урна", "Concrete bin"), "lamp_pole": ("Фонарный столб", "Lamp post"),
    "street_lamp": ("Уличный фонарь", "Street lamp"), "dumpster": ("Мусорный контейнер", "Dumpster"), "swing": ("Качели", "Swing"),
    "slide": ("Детская горка", "Slide"), "chin_up_bar": ("Турник", "Chin-up bar"), "flagpole": ("Флагшток", "Flagpole"),
    "flag": ("Флаг России", "Russian flag"), "red_cross": ("Знак больницы", "Hospital sign"), "notice_board": ("Доска объявлений", "Notice board"),
    "air_conditioner": ("Кондиционер", "Air conditioner"), "satellite_dish": ("Спутниковая тарелка", "Satellite dish"),
    "bus_stop_sign": ("Знак остановки", "Bus stop sign"), "tyre_swan": ("Лебедь из шины", "Tyre swan"),
    "car_white": ("Жигули (белые)", "Zhiguli (white)"), "car_red": ("Жигули (вишнёвые)", "Zhiguli (cherry)"),
    "car_green": ("Жигули (зелёные)", "Zhiguli (green)"), "car_blue": ("Жигули (синие)", "Zhiguli (blue)"),
    "car_volga_black": ("Волга (чёрная)", "Volga (black)"), "car_volga_beige": ("Волга (бежевая)", "Volga (beige)"),
    "car_moskvich": ("Москвич (оранжевый)", "Moskvich (orange)"), "car_moskvich_grey": ("Москвич (серый)", "Moskvich (grey)"),
    "car_zaporozhets": ("Запорожец (голубой)", "Zaporozhets (light blue)"), "car_zaporozhets_yellow": ("Запорожец (жёлтый)", "Zaporozhets (yellow)"),
    "car_uaz": ("УАЗ «буханка»", "UAZ van"), "car_uaz_ambulance": ("УАЗ «скорая помощь»", "UAZ ambulance"),
    "lamp_green_base": ("Основание фонаря", "Lamp post base"), "lamp_green_pole": ("Опора фонаря", "Lamp post"),
    "lamp_grey_base": ("Основание серого фонаря", "Grey lamp post base"), "lamp_grey_pole": ("Опора серого фонаря", "Grey lamp post"),
    "lamp_grey_double": ("Серый фонарь с двумя плечами", "Grey double-arm street lamp"),
    "lamp_grey_single": ("Серый фонарь с одним плечом", "Grey single-arm street lamp"),
    "lamp_green_double": ("Фонарь с двумя плечами", "Double-arm street lamp"), "lamp_green_single": ("Фонарь с одним плечом", "Single-arm street lamp"),
    "curb": ("Бордюр", "Curb"), "park_bench": ("Парковая скамейка", "Park bench"), "bin_metal": ("Урна", "Litter bin"),
    "bike_rack": ("Велопарковка", "Bike rack"), "fence_green": ("Зелёная оградка", "Green railing"),
    "fence_black": ("Чёрная оградка", "Black railing"), "lamp_black_pole": ("Опора бульварного фонаря", "Boulevard lamp post"),
    "lamp_black_top": ("Бульварный фонарь", "Boulevard lamp"), "long_bench": ("Бульварная скамья", "Boulevard bench"), "paving_grey": ("Тротуарная плитка", "Paving"), "paving_red": ("Красная плитка", "Red paving"),
}


def write(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2, ensure_ascii=False)
        f.write("\n")


def save_all():
    tdir = os.path.join(ASSETS, "textures", "block", "city")
    os.makedirs(tdir, exist_ok=True)
    for name, img in TEX.items():
        img.save(os.path.join(tdir, name + ".png"))
    for name, m in MODELS.items():
        write(os.path.join(ASSETS, "models", "block", "city", name + ".json"), m)
        if name.startswith("fence_"):
            continue
        write(os.path.join(ASSETS, "blockstates", name + ".json"), {"variants": {
            f"facing={f}": ({"model": f"mobtalk:block/city/{name}", "y": y} if y else {"model": f"mobtalk:block/city/{name}"})
            for f, y in (("north", 0), ("east", 90), ("south", 180), ("west", 270))}})
        write(os.path.join(ASSETS, "models", "item", name + ".json"), {"parent": f"mobtalk:block/city/{name}", "display": ITEM_DISPLAY.get(name, DISPLAY)})
        write(os.path.join(DATA, "loot_table", "blocks", name + ".json"), {"type": "minecraft:block", "pools": [{"rolls": 1, "entries": [
            {"type": "minecraft:item", "name": f"mobtalk:{name}"}], "conditions": [{"condition": "minecraft:survives_explosion"}]}]})
    for name, texture in CUBES.items():
        write(os.path.join(ASSETS, "models", "block", "city", name + ".json"),
              {"parent": "minecraft:block/cube_all", "textures": {"all": f"mobtalk:block/city/{texture}"}})
        write(os.path.join(ASSETS, "blockstates", name + ".json"), {"variants": {"": {"model": f"mobtalk:block/city/{name}"}}})
        write(os.path.join(ASSETS, "models", "item", name + ".json"), {"parent": f"mobtalk:block/city/{name}"})
        write(os.path.join(DATA, "loot_table", "blocks", name + ".json"), {"type": "minecraft:block", "pools": [{"rolls": 1, "entries": [
            {"type": "minecraft:item", "name": f"mobtalk:{name}"}], "conditions": [{"condition": "minecraft:survives_explosion"}]}]})
    for name in FENCES:
        post, side = f"mobtalk:block/city/{name}_post", f"mobtalk:block/city/{name}_side"
        write(os.path.join(ASSETS, "blockstates", name + ".json"), {"multipart": [{"apply": {"model": post}}] + [
            {"when": {d: "true"}, "apply": {"model": side, "y": y, "uvlock": True}}
            for d, y in (("north", 0), ("east", 90), ("south", 180), ("west", 270))]})
        inv = {"parent": "minecraft:block/block", "textures": MODELS[name + "_post"]["textures"],
               "elements": MODELS[name + "_post"]["elements"] + MODELS[name + "_side"]["elements"] + [
                   dict(e, **{"from": [e["from"][0], e["from"][1], 16 - e["to"][2]], "to": [e["to"][0], e["to"][1], 16 - e["from"][2]]})
                   for e in MODELS[name + "_side"]["elements"]], "display": DISPLAY}
        write(os.path.join(ASSETS, "models", "item", name + ".json"), inv)
        write(os.path.join(DATA, "loot_table", "blocks", name + ".json"), {"type": "minecraft:block", "pools": [{"rolls": 1, "entries": [
            {"type": "minecraft:item", "name": f"mobtalk:{name}"}], "conditions": [{"condition": "minecraft:survives_explosion"}]}]})
    write(os.path.join(ASSETS, "blockstates", "car_part.json"), {"variants": {"": {"model": "mobtalk:block/city/car_part"}}})
    write(os.path.join(ASSETS, "models", "block", "city", "car_part.json"), {"textures": {"particle": "mobtalk:block/city/metal_grey"}})
    for lang, i in (("ru_ru", 0), ("en_us", 1)):
        p = os.path.join(ASSETS, "lang", lang + ".json")
        with open(p, encoding="utf-8") as f:
            d = json.load(f)
        for name, titles in NAMES.items():
            d[f"block.mobtalk.{name}"] = titles[i]
        d["block.mobtalk.car_part"] = NAMES["car_white"][i].split(" (")[0]
        with open(p, "w", encoding="utf-8") as f:
            json.dump(d, f, indent=2, ensure_ascii=False)
            f.write("\n")


DISPLAY = {"gui": {"rotation": [30, 225, 0], "translation": [0, 0, 0], "scale": [0.625, 0.625, 0.625]},
           "ground": {"rotation": [0, 0, 0], "translation": [0, 3, 0], "scale": [0.25, 0.25, 0.25]},
           "fixed": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [0.5, 0.5, 0.5]},
           "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.375, 0.375, 0.375]},
           "firstperson_righthand": {"rotation": [0, 45, 0], "translation": [0, 0, 0], "scale": [0.4, 0.4, 0.4]},
           "firstperson_lefthand": {"rotation": [0, 225, 0], "translation": [0, 0, 0], "scale": [0.4, 0.4, 0.4]}}
SMALL = {k: dict(v, scale=[s * 0.5 for s in v["scale"]]) for k, v in DISPLAY.items()}
ITEM_DISPLAY = {n: SMALL for n in ("swing", "slide", "flag", "lamp_green_double", "lamp_green_single", "lamp_grey_double", "lamp_grey_single") + tuple("car_" + c for c in CARS)}


# ---------------------------------------------------------------- preview

def render(m, yaw, size=160):
    """A rough isometric look at a model: every face sampled point by point into a z-buffer (no lighting tricks, just side shading)."""
    img = Image.new("RGBA", (size, size), (40, 44, 52, 255))
    zb = [[-1e9] * size for _ in range(size)]
    px = img.load()
    ext = max(max(abs(v - 8) for e in m["elements"] for v in e["from"] + e["to"]), 8)
    scale = size / (ext * 2.6)
    cy, sy = math.cos(math.radians(yaw)), math.sin(math.radians(yaw))
    pitch = math.radians(30)
    cp, sp = math.cos(pitch), math.sin(pitch)

    def project(x, y, z):
        x, z = x - 8, z - 8
        rx = x * cy - z * sy
        rz = x * sy + z * cy
        ry = y - 8
        vy = ry * cp - rz * sp
        depth = ry * sp + rz * cp
        return size / 2 + rx * scale, size * 0.62 - vy * scale, depth
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
            steps = 96
            for i in range(steps + 1):
                for j in range(steps + 1):
                    a, b = i / steps, j / steps
                    if face in ("north", "south"):
                        p = (f[0] + (t[0] - f[0]) * a, t[1] - (t[1] - f[1]) * b, f[2] if face == "north" else t[2])
                        if face == "south":
                            p = (t[0] - (t[0] - f[0]) * a, p[1], p[2])
                    elif face in ("east", "west"):
                        p = (t[0] if face == "east" else f[0], t[1] - (t[1] - f[1]) * b, f[2] + (t[2] - f[2]) * a)
                        if face == "east":
                            p = (p[0], p[1], t[2] - (t[2] - f[2]) * a)
                    else:
                        p = (f[0] + (t[0] - f[0]) * a, t[1] if face == "up" else f[1], f[2] + (t[2] - f[2]) * b)
                    sx, sy2, d = project(*rotate(p))
                    X, Y = int(sx), int(sy2)
                    if 0 <= X < size and 0 <= Y < size and d > zb[Y][X]:
                        uu = u0 + (u1 - u0) * a
                        vv = v0 + (v1 - v0) * b
                        tw, th = tx.size
                        c = tx.getpixel((min(tw - 1, max(0, int(uu / 16 * tw))), min(th - 1, max(0, int(vv / 16 * th)))))
                        if c[3] == 0:
                            continue
                        zb[Y][X] = d
                        px[X, Y] = shade(c, light)
    return img


def preview(out, only=None):
    names = [n for n in MODELS if not only or n in only]
    cell = 256
    sheet = Image.new("RGBA", (cell * 4, cell * ((len(names) + 1) // 2)), (30, 30, 34, 255))
    for i, n in enumerate(names):
        for k, yaw in enumerate((35, 215)):
            im = render(MODELS[n], yaw, cell)
            sheet.paste(im, ((i % 2) * 2 * cell + k * cell, (i // 2) * cell))
    sheet.save(out)


def main():
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import interior
    me = sys.modules[__name__]
    make_textures()
    interior.make_textures(me)
    make_models()
    interior.make_models()
    save_all()
    interior.write_java(ROOT)
    if len(sys.argv) > 2 and sys.argv[1] == "preview":
        preview(sys.argv[2], sys.argv[3:])
    print("decor:", len(MODELS), "models,", len(TEX), "textures")


if __name__ == "__main__":
    main()
