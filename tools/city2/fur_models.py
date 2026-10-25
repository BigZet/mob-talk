"""The pieces themselves: materials, pictures and boxes of every model (see furniture.py for the conventions).

North space: the front looks north (low z), the back stands at z = 16; x grows to the viewer's LEFT when he faces the front,
so a picture on a north face is painted left to right from x1 down to x0. A room is 2 blocks high: y 0..32 is floor to ceiling.
"""
import math
import random

import fur_paint as P
from fur_paint import rgb, shade, mix, ramp
from furniture import Furn, Model, Pic, mat

# ---------------------------------------------------------------- colours

WALNUT = rgb("6a4127")
LIGHTWOOD = rgb("c4935c")
OAK = rgb("8c5c34")
ENAMEL = rgb("ecebe3")
CREAM = rgb("e9dfc4")
CHROME = rgb("c9cdd2")
BRASS = rgb("c7a048")
BLACK = rgb("2a2a2e")
STEEL = rgb("8d9399")
FABRICS = {"red": rgb("8a2a2c"), "green": rgb("4e6a3c"), "brown": rgb("8d6a3e"), "blue": rgb("3d5a8a"), "beige": rgb("bfae8a")}
RU = {"red": "бордовый", "green": "зелёный", "brown": "горчичный", "blue": "синий", "beige": "бежевый"}
MAPC = {"red": "RED", "green": "GREEN", "brown": "BROWN", "blue": "BLUE", "beige": "PALE_YELLOW"}


def materials():
    mat("walnut", P.wood(32, 32, WALNUT, "walnut", polish=1))
    mat("walnut_v", P.wood(32, 32, WALNUT, "walnut_v", vertical=True, polish=1))
    mat("lightwood", P.wood(32, 32, LIGHTWOOD, "lightwood", polish=0.6))
    mat("lightwood_v", P.wood(32, 32, LIGHTWOOD, "lightwood_v", vertical=True, polish=0.6))
    mat("oak", P.wood(32, 32, OAK, "oak"))
    mat("oak_v", P.wood(32, 32, OAK, "oak_v", vertical=True))
    mat("enamel", P.enamel(32, 32, ENAMEL))
    mat("cream", P.enamel(32, 32, CREAM))
    mat("chrome", P.metal(32, 32, CHROME))
    mat("chrome_h", P.metal(32, 32, CHROME, vertical=False))
    mat("brass", P.metal(32, 32, BRASS))
    mat("black", P.enamel(32, 32, BLACK))
    mat("steel", P.enamel(32, 32, STEEL))
    mat("iron_black", P.enamel(32, 32, rgb("303034")))
    mat("rubber", P.Canvas(32, 32, rgb("232325")))
    mat("worktop", worktop())
    mat("alu", P.metal(32, 32, rgb("b4b8bc"), vertical=False))
    for k, c in FABRICS.items():
        mat("fabric_" + k, P.fabric(32, 32, c, "fabric_" + k))
    mat("linen", linen())
    mat("glass", P.glass(32, 32))
    mat("bulb", P.Canvas(32, 32, rgb("fff4c8")))
    mat("bulb_off", P.Canvas(32, 32, rgb("c8c2a8")))
    mat("shade_white", P.enamel(32, 32, rgb("f2eee2")))
    mat("shade_white_lit", P.Canvas(32, 32, rgb("fffbe8")))
    mat("lampshade", lampshade(rgb("e6c890")))
    mat("lampshade_lit", lampshade(rgb("ffe7a8"), lit=True))
    mat("plastic_beige", P.enamel(32, 32, rgb("d8ccb0")))
    mat("plastic_red", P.enamel(32, 32, rgb("b03028")))
    mat("terracotta", P.enamel(32, 32, rgb("a85a38")))
    mat("soil", P.fabric(32, 32, rgb("4a3524"), "soil", weave=1, nap=False))
    mat("leaf", leaves(rgb("3f7a36"), "leaf"))
    mat("leaf_dark", leaves(rgb("2f5e2c"), "leafd"))
    mat("paper", P.enamel(32, 32, rgb("eeeadc")))
    mat("screen_off", P.Canvas(32, 32, rgb("2c3430")))


def worktop():
    cv = P.Canvas(32, 32, rgb("c9c3b4"))
    r = random.Random("worktop")
    t = ramp(rgb("c9c3b4"), 5, 0.82, 1.08)
    for y in range(32):
        for x in range(32):
            if (x * 7 + y * 13) % 23 == 0:
                cv.set(x, y, t[1])
            elif (x * 5 + y * 3) % 19 == 0:
                cv.set(x, y, t[4])
    for _ in range(6):
        cv.set(r.randrange(32), r.randrange(32), t[0])
    return cv


def linen():
    cv = P.Canvas(32, 32, rgb("f1f0ea"))
    for y in range(32):
        if y % 8 in (2, 3):
            cv.hline(0, 32, y, rgb("d6dde8"))
    cv.dither(0, 0, 32, 32, rgb("f1f0ea"), rgb("e6e6e0"), 0.15)
    for y in range(32):
        if y % 8 in (2, 3):
            cv.hline(0, 32, y, rgb("cfd8e6"))
    return cv


def lampshade(c, lit=False):
    t = ramp(c, 5, 0.8, 1.12)
    cv = P.Canvas(32, 32, t[3] if not lit else t[4])
    for x in range(0, 32, 4):
        cv.vline(x, 0, 32, t[2] if not lit else t[3])
    cv.hline(0, 32, 0, t[1])
    cv.hline(0, 32, 31, t[1])
    for x in range(0, 32, 2):
        cv.set(x, 30, t[1])
    return cv


def leaves(c, seed):
    r = random.Random(seed)
    t = ramp(c, 5, 0.65, 1.25)
    cv = P.Canvas(32, 32, t[1])
    for _ in range(26):
        x, y = r.randrange(32), r.randrange(32)
        cv.ellipse(x, y, r.uniform(2, 3.5), r.uniform(1.2, 2.2), t[r.choice((2, 3, 3))])
        cv.set(x, y, t[4])
    return cv


# ---------------------------------------------------------------- picture helpers

def wood_canvas(cv, base, seed, vertical=True, polish=0.8):
    cv.paste(P.wood(cv.w, cv.h, base, seed, vertical=vertical, polish=polish, spacing=5), 0, 0)


def panel(cv, x0, y0, x1, y1, base, seed, vertical=True, polish=0.8, frame=2):
    """A veneered door or drawer front: its own grain, a raised edge, a dark joint round it."""
    t = ramp(base, 6, 0.62, 1.2)
    sub = P.wood(x1 - x0, y1 - y0, base, seed, vertical=vertical, polish=polish, spacing=5)
    cv.paste(sub, x0, y0)
    cv.outline(x0, y0, x1, y1, t[0])
    cv.bevel(x0 + 1, y0 + 1, x1 - 1, y1 - 1, t[5], t[1])
    if frame > 1:
        cv.bevel(x0 + 2, y0 + 2, x1 - 2, y1 - 2, shade(t[4], 1.0), t[2])


def knob(cv, x, y, c=BRASS, w=2, h=2):
    t = ramp(c, 4, 0.6, 1.3)
    cv.rect(x, y, x + w, y + h, t[2])
    cv.set(x, y, t[3])
    cv.set(x + w - 1, y + h - 1, t[0])


def handle_bar(cv, x0, y, x1, c=CHROME):
    t = ramp(c, 4, 0.6, 1.3)
    cv.hline(x0, x1, y, t[3])
    cv.hline(x0, x1, y + 1, t[1])


def books(cv, x0, y0, x1, y1, seed):
    """A shelf of books: spines of different height, colour and width, a title band or two in gold."""
    r = random.Random(seed)
    cols = [rgb(h) for h in ("7a2420", "2c4a6e", "2f5a32", "6b4a26", "8a6a2a", "3a2e4e", "a0662a", "5a1c24", "2a3a3e", "c8b88a")]
    x = x0
    while x < x1 - 1:
        w = r.choice((2, 2, 3, 3, 4))
        if x + w > x1:
            w = x1 - x
        h = r.randint(max(3, (y1 - y0) * 6 // 10), y1 - y0)
        c = r.choice(cols)
        t = ramp(c, 4, 0.7, 1.2)
        cv.rect(x, y1 - h, x + w, y1, t[2])
        cv.vline(x, y1 - h, y1, t[3])
        cv.vline(x + w - 1, y1 - h, y1, t[1])
        if h > 5:
            gy = y1 - h + 2
            cv.hline(x, x + w, gy, rgb("d8b860"))
            if h > 9:
                cv.hline(x, x + w, y1 - 3, rgb("d8b860"))
        x += w
        if r.random() < 0.12 and x < x1 - 4:  # a book leaning
            x += 1


def crystal(cv, x0, y0, x1, y1, seed):
    """A glass shelf with cut crystal: vases, a fruit bowl, wine glasses, a gilded tea set."""
    r = random.Random(seed)
    glass_t = [rgb("9fb8c4"), rgb("cfe2ea"), rgb("f4fbff")]
    x = x0 + 1
    while x < x1 - 3:
        k = r.choice(("vase", "glass", "glass", "bowl", "cup"))
        if k == "vase":
            h = min(y1 - y0 - 1, r.randint(7, 10))
            for yy in range(h):
                w = 2 if yy < h * 0.3 else 3
                cv.hline(x + (3 - w) // 2, x + (3 - w) // 2 + w, y1 - 1 - yy, glass_t[(yy + x) % 2])
            cv.set(x + 1, y1 - h + 1, glass_t[2])
            x += 5
        elif k == "glass":
            cv.set(x, y1 - 1, glass_t[1]); cv.set(x + 1, y1 - 1, glass_t[1])
            cv.vline(x, y1 - 3, y1 - 1, glass_t[0])
            cv.rect(x - 1, y1 - 6, x + 2, y1 - 3, glass_t[0])
            cv.set(x, y1 - 5, glass_t[2])
            x += 4
        elif k == "bowl":
            cv.hline(x, x + 6, y1 - 3, glass_t[1]); cv.hline(x + 1, x + 5, y1 - 2, glass_t[0]); cv.hline(x + 2, x + 4, y1 - 1, glass_t[1])
            cv.set(x + 1, y1 - 4, rgb("c83a2a")); cv.set(x + 3, y1 - 4, rgb("e0b030")); cv.set(x + 4, y1 - 4, rgb("4a8a30"))
            x += 7
        else:
            cv.rect(x, y1 - 3, x + 3, y1, rgb("f4f2ea")); cv.hline(x, x + 3, y1 - 3, rgb("d0a840")); cv.set(x + 3, y1 - 2, rgb("f4f2ea"))
            cv.set(x + 1, y1 - 2, rgb("2a4a9a"))
            x += 5


def tufted(cv, base, seed, step=8):
    """Upholstery front with buttoned tufting: a diamond of seams and a button at each crossing."""
    cv.paste(P.fabric(cv.w, cv.h, base, seed), 0, 0)
    t = ramp(base, 5, 0.6, 1.2)
    for y in range(cv.h):
        for x in range(cv.w):
            if (x + y) % step == 0 or (x - y) % step == 0:
                cv.set(x, y, t[1] if (x + y) % 2 else t[2])
    for y in range(step // 2, cv.h, step):
        for x in range(0 if (y // (step // 2)) % 2 else step // 2, cv.w, step):
            cv.set(x, y, t[0])
            cv.set(x + 1, y, t[0])


def piping(cv, base, y):
    t = ramp(base, 5, 0.6, 1.2)
    cv.hline(0, cv.w, y, t[1])
    cv.hline(0, cv.w, y + 1, t[4])


# ---------------------------------------------------------------- living room

def legs(m, x0, z0, x1, z1, h, material="walnut", w=1.5, splay=0):
    for x in (x0, x1 - w):
        for z in (z0, z1 - w):
            m.box((x, 0, z), (x + w, h, z + w), material, faces=("north", "south", "east", "west"))
    return m


def sofa_book(col):
    base = FABRICS[col]

    def build():
        m = Model()
        # wooden side panels, the plinth, the seat with a piped front edge, the tufted back in two cushions
        for x0 in (0, 30.5):
            m.box((x0, 0, 2), (x0 + 1.5, 11, 15.5), "walnut_v", down=None,
                  north=Pic("side_front", lambda cv, W, H: panel(cv, 0, 0, cv.w, cv.h, WALNUT, "sbs", frame=1)))
        m.box((1.5, 0, 4), (30.5, 2, 14), "black", down=None)
        m.box((1.5, 2, 1.5), (30.5, 7, 13), "fabric_" + col, down=None,
              north=Pic("seat_front", lambda cv, W, H: (cv.paste(P.fabric(cv.w, cv.h, base, "sf" + col), 0, 0), piping(cv, base, 1),
                                                       cv.vline(cv.w // 2, 0, cv.h, ramp(base)[0]))),
              up=Pic("seat_top", lambda cv, W, H: (cv.paste(P.fabric(cv.w, cv.h, base, "st" + col), 0, 0),
                                                  cv.vline(cv.w // 2, 0, cv.h, ramp(base)[1]), cv.hline(0, cv.w, 1, ramp(base)[4]))))
        m.box((1.5, 2, 13), (30.5, 15, 15.5), "fabric_" + col, down=None,
              north=Pic("back", lambda cv, W, H: (tufted(cv, base, "bk" + col), cv.vline(cv.w // 2, 0, cv.h, ramp(base)[0]),
                                                  cv.hline(0, cv.w, 0, ramp(base)[4]))))
        m.box((1.5, 15, 13.5), (30.5, 16, 15.5), "fabric_" + col, down=None)
        # two small cushions with a fringe
        return m
    return build


def armchair(col):
    base = FABRICS[col]

    def build():
        m = Model()
        legs(m, 1.5, 2.5, 14.5, 14.5, 3, "walnut", 1.2)
        m.box((1, 3, 1.5), (15, 4, 15), "walnut", down=None)
        m.box((2.5, 4, 1.5), (13.5, 7.5, 13), "fabric_" + col, down=None,
              north=Pic("seat", lambda cv, W, H: (cv.paste(P.fabric(cv.w, cv.h, base, "as" + col), 0, 0), piping(cv, base, 1))))
        m.box((2.5, 4, 13), (13.5, 15.5, 15), "fabric_" + col, down=None,
              north=Pic("back", lambda cv, W, H: (tufted(cv, base, "ab" + col, 10), cv.hline(0, cv.w, 0, ramp(base)[4]))))
        for x0 in (1, 13.5):
            m.box((x0, 4, 2.5), (x0 + 1.5, 10, 15), "fabric_" + col, down=None)
            m.box((x0 - 0.25, 10, 1.5), (x0 + 1.75, 11, 15.5), "walnut", down=None)
            m.box((x0 + 0.25, 4, 1.5), (x0 + 1.25, 10, 2.5), "walnut_v", down=None, up=None)
        return m
    return build


def stool():
    m = Model()
    for x in (3.5, 11):
        for z in (3.5, 11):
            m.box((x, 0, z), (x + 1.5, 7, z + 1.5), "lightwood_v", faces=("north", "south", "east", "west"))
    m.box((5, 2, 3.75), (11, 3, 4.75), "lightwood", up=None, down=None)
    m.box((5, 2, 11.25), (11, 3, 12.25), "lightwood", up=None, down=None)
    m.box((3.75, 4, 5), (4.75, 5, 11), "lightwood", up=None, down=None)
    m.box((11.25, 4, 5), (12.25, 5, 11), "lightwood", up=None, down=None)
    m.box((3, 7, 3), (13, 8, 13), "lightwood", up=Pic("top", lambda cv, W, H: stool_top(cv)))
    return m


def stool_top(cv):
    wood_canvas(cv, LIGHTWOOD, "stooltop", vertical=False, polish=0.5)
    t = ramp(LIGHTWOOD, 6, 0.6, 1.2)
    cv.outline(0, 0, cv.w, cv.h, t[1])
    cv.disc(cv.w / 2, cv.h / 2, 2.2, t[1])  # the hand hole
    cv.disc(cv.w / 2, cv.h / 2, 1.2, t[0])


def chair():
    m = Model()
    for x in (3, 11.5):
        m.box((x, 0, 3), (x + 1.5, 7, 4.5), "walnut_v", faces=("north", "south", "east", "west"))
        m.box((x, 0, 11.5), (x + 1.5, 16, 13), "walnut_v", down=None)
    m.box((2.5, 7, 2.5), (13.5, 8, 12.75), "walnut", down=None)
    m.box((3.25, 8, 3), (12.75, 9.5, 12.25), "fabric_brown", down=None,
          up=Pic("seat", lambda cv, W, H: (cv.paste(P.fabric(cv.w, cv.h, FABRICS["brown"], "chs"), 0, 0),
                                          cv.outline(0, 0, cv.w, cv.h, ramp(FABRICS["brown"])[1]))))
    m.box((4.5, 10.5, 11.75), (11.5, 15, 12.75), "walnut",
          north=Pic("back", lambda cv, W, H: chair_back(cv)))
    m.box((4.5, 4, 3.25), (11.5, 5, 4.25), "walnut", up=None, down=None)
    return m


def chair_back(cv):
    wood_canvas(cv, WALNUT, "chback", vertical=False)
    t = ramp(WALNUT, 6, 0.6, 1.2)
    cv.bevel(0, 0, cv.w, cv.h, t[5], t[1])
    for x in range(4, cv.w - 3, 5):
        cv.vline(x, 3, cv.h - 3, t[0])


def coffee_table():
    m = Model()
    legs(m, 2, 3, 14, 13, 6.5, "walnut_v", 1.2)
    m.box((2.5, 2.5, 3.5), (13.5, 3.2, 12.5), "walnut", down=None)
    m.box((1, 6.5, 2), (15, 7.5, 14), "walnut",
          up=Pic("top", lambda cv, W, H: (wood_canvas(cv, WALNUT, "cttop", vertical=False, polish=1.4), lace_doily(cv, cv.w // 2, cv.h // 2, 7))))
    # a vase with gladioli and a magazine "Огонёк"
    m.box((7, 7.5, 7), (9, 10, 9), "glass")
    m.box((7.5, 10, 7.5), (8.5, 14, 8.5), "leaf", faces=("north", "south", "east", "west"))
    m.box((7, 12, 7), (9, 15, 9), "plastic_red", down=None)
    m.box((10.5, 7.5, 4), (14, 8, 8), "paper", down=None,
          up=Pic("mag", lambda cv, W, H: magazine(cv)))
    return m


def lace_doily(cv, cx, cy, r):
    for y in range(cv.h):
        for x in range(cv.w):
            d = math.hypot(x + 0.5 - cx, y + 0.5 - cy)
            if d <= r:
                a = math.atan2(y - cy, x - cx)
                if d > r - 1.5 and int(a * 8 / math.pi) % 2:
                    continue
                if (int(d) % 3 == 1 and (x + y) % 2) or d < 1.5:
                    continue
                cv.set(x, y, rgb("f4f1e6"))


def magazine(cv):
    cv.rect(0, 0, cv.w, cv.h, rgb("e8e2d0"))
    cv.rect(0, 0, cv.w, 3, rgb("c03028"))
    cv.rect(1, 4, cv.w - 1, cv.h - 3, rgb("6a8ab0"))
    cv.rect(2, 6, cv.w - 2, cv.h - 5, rgb("d8b080"))
    for x in range(1, cv.w - 1, 2):
        cv.set(x, 1, rgb("f0f0f0"))


def tv(on):
    def screen(cv, W, H):
        wood_canvas(cv, WALNUT, "tvbody", vertical=False, polish=1.2)
        t = ramp(WALNUT, 6, 0.6, 1.2)
        cv.bevel(0, 0, cv.w, cv.h, t[5], t[1])
        sx0, sy0, sx1, sy1 = 2, 2, cv.w - 7, cv.h - 3
        cv.rect(sx0, sy0, sx1, sy1, rgb("1c1c1e"))
        # rounded CRT
        for (x, y) in ((sx0, sy0), (sx1 - 1, sy0), (sx0, sy1 - 1), (sx1 - 1, sy1 - 1)):
            cv.set(x, y, t[2])
        ix0, iy0, ix1, iy1 = sx0 + 1, sy0 + 1, sx1 - 1, sy1 - 1
        if on:
            test_card(cv, ix0, iy0, ix1, iy1)
        else:
            cv.vgrad(ix0, iy0, ix1, iy1, [rgb("4a5650"), rgb("38423e"), rgb("2c3430")])
            cv.set(ix0 + 1, iy0 + 1, rgb("8a9a94")); cv.set(ix0 + 2, iy0 + 1, rgb("6a7a74")); cv.set(ix0 + 1, iy0 + 2, rgb("6a7a74"))
        # the panel on the right: speaker cloth, two knobs, the channel switch
        px0 = cv.w - 6
        cv.rect(px0, 2, cv.w - 1, cv.h - 3, rgb("b8a888"))
        for y in range(2, cv.h // 2):
            for x in range(px0, cv.w - 1):
                if (x + y) % 2 == 0:
                    cv.set(x, y, rgb("968668"))
        knob(cv, px0 + 1, cv.h // 2 + 1, rgb("e0e0dc"))
        knob(cv, px0 + 1, cv.h // 2 + 4, rgb("e0e0dc"))
        cv.rect(px0, cv.h - 5, px0 + 4, cv.h - 4, rgb("202020"))
        cv.set(px0 + 4, cv.h - 5, rgb("e04020") if on else rgb("602018"))

    def build():
        m = Model()
        for x in (3.5, 11.5):
            for z in (5, 12):
                m.box((x, 0, z), (x + 1, 7, z + 1), "black", faces=("north", "south", "east", "west"),
                      rot=None)
        m.box((3, 6.5, 4.5), (13, 7, 13.5), "black", down=None)
        m.box((2.5, 7, 4), (13.5, 15, 11), "walnut", north=Pic("screen_" + ("on" if on else "off"), screen))
        m.box((4, 7.5, 11), (12, 14, 14.5), "black", north=None)
        # the room aerial
        m.box((6, 15, 8), (10, 15.5, 10), "black")
        m.box((7.5, 15.5, 8.5), (8, 22, 9), "chrome", rot={"origin": [7.75, 15.5, 8.75], "axis": "z", "angle": 22.5})
        m.box((8, 15.5, 8.5), (8.5, 22, 9), "chrome", rot={"origin": [8.25, 15.5, 8.75], "axis": "z", "angle": -22.5})
        return m
    return build


def test_card(cv, x0, y0, x1, y1):
    """The Soviet test card: a grey grid, a circle, colour bars and the clock-like centre."""
    cv.rect(x0, y0, x1, y1, rgb("6c6c6c"))
    for x in range(x0, x1):
        for y in range(y0, y1):
            if (x - x0) % 4 == 0 or (y - y0) % 4 == 0:
                cv.set(x, y, rgb("d8d8d8"))
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    r = min(x1 - x0, y1 - y0) / 2 - 0.5
    bars = [rgb(h) for h in ("f0f0f0", "e8e030", "30d0d0", "30c040", "d040c0", "d03030", "3040d0", "101010")]
    for y in range(y0, y1):
        for x in range(x0, x1):
            d = math.hypot(x + 0.5 - cx, y + 0.5 - cy)
            if d <= r:
                if abs(y + 0.5 - cy) < r * 0.35:
                    cv.set(x, y, bars[min(7, int((x - (cx - r)) / (2 * r) * 8))])
                elif d > r - 1:
                    cv.set(x, y, rgb("f8f8f8"))
                else:
                    cv.set(x, y, rgb("303030") if y < cy else rgb("b0b0b0"))
    cv.set(int(cx), int(cy), rgb("ffffff"))


# ---------------------------------------------------------------- wall unit ("стенка")

def stenka():
    def front(cv, W, H):
        """48 x 30 px: three sections. Left: a wardrobe door; centre: cupboards, crystal behind glass, cupboards;
        right: drawers, the book shelf (cut deeper in the model), cupboards."""
        wood_canvas(cv, WALNUT, "stenka", vertical=True, polish=1.2)
        t = ramp(WALNUT, 6, 0.6, 1.2)
        S = 32  # texels per section
        brass = BRASS
        # left section: one tall door with a long brass handle and a key
        panel(cv, 1, 2, S - 1, cv.h - 3, WALNUT, "st_l", polish=1.4)
        cv.vline(S - 5, 18, 34, ramp(brass)[3]); cv.vline(S - 4, 18, 34, ramp(brass)[1])
        cv.set(S - 5, 36, rgb("1a1a1a")); cv.set(S - 5, 37, rgb("1a1a1a"))
        # centre: two lower doors, glass with crystal and glass shelves, two upper doors
        c0 = S
        panel(cv, c0 + 1, cv.h - 22, c0 + S // 2, cv.h - 3, WALNUT, "st_c1")
        panel(cv, c0 + S // 2, cv.h - 22, c0 + S - 1, cv.h - 3, WALNUT, "st_c2")
        knob(cv, c0 + S // 2 - 3, cv.h - 14, brass); knob(cv, c0 + S // 2 + 1, cv.h - 14, brass)
        gy0, gy1 = 15, cv.h - 23
        cv.rect(c0 + 1, gy0, c0 + S - 1, gy1, rgb("3a2a22"))
        cv.outline(c0 + 1, gy0, c0 + S - 1, gy1, t[0])
        mid = (gy0 + gy1) // 2
        cv.hline(c0 + 2, c0 + S - 2, mid, rgb("a8c0c8"))
        crystal(cv, c0 + 2, gy0 + 1, c0 + S - 2, mid, "cr1")
        crystal(cv, c0 + 2, mid + 1, c0 + S - 2, gy1 - 1, "cr2")
        for y in range(gy0 + 1, gy1 - 1):  # the glass doors' glint
            x = c0 + 3 + (y - gy0) // 2
            if x < c0 + S - 2 and y % 7 < 3:
                cv.set(x, y, rgb("dcecf0"))
        cv.vline(c0 + S // 2, gy0, gy1, t[1])
        panel(cv, c0 + 1, 2, c0 + S // 2, gy0 - 1, WALNUT, "st_c3")
        panel(cv, c0 + S // 2, 2, c0 + S - 1, gy0 - 1, WALNUT, "st_c4")
        # right: three drawers, books (the model cuts this part 2 px deeper), two upper doors
        r0 = 2 * S
        for i in range(3):
            y0 = cv.h - 3 - (i + 1) * 7
            panel(cv, r0 + 1, y0, r0 + S - 1, y0 + 7, WALNUT, f"st_d{i}", vertical=False, frame=1)
            handle_bar(cv, r0 + 12, y0 + 3, r0 + 20, brass)
        cv.rect(r0 + 1, 15, r0 + S - 1, cv.h - 24, rgb("2a1c14"))
        panel(cv, r0 + 1, 2, r0 + S // 2, 14, WALNUT, "st_r1")
        panel(cv, r0 + S // 2, 2, r0 + S - 1, 14, WALNUT, "st_r2")
        # cornice and plinth
        cv.rect(0, 0, cv.w, 2, t[4]); cv.hline(0, cv.w, 1, t[2])
        cv.rect(0, cv.h - 2, cv.w, cv.h, t[0])
        for k in (S, 2 * S):
            cv.vline(k - 1, 2, cv.h - 2, t[0]); cv.vline(k, 2, cv.h - 2, t[2])

    def shelf(cv, W, H):
        cv.rect(0, 0, cv.w, cv.h, rgb("3a2618"))
        mid = cv.h // 2
        books(cv, 1, 1, cv.w - 1, mid - 1, "bk1")
        cv.hline(0, cv.w, mid - 1, ramp(WALNUT)[3]); cv.hline(0, cv.w, mid, ramp(WALNUT)[1])
        books(cv, 1, mid + 1, cv.w - 10, cv.h, "bk2")
        # a framed photo and a figurine on the lower shelf
        cv.rect(cv.w - 8, cv.h - 7, cv.w - 3, cv.h, rgb("c8a050")); cv.rect(cv.w - 7, cv.h - 6, cv.w - 4, cv.h - 1, rgb("8a7a6a"))

    def build():
        m = Model()
        # body x -16..32 (the right section, as you face it, at low x), y 0..30, 0.45 m deep; a front skin carries one
        # picture over the three sections except where the right section's book shelf is cut in 1.5 px
        fr = Pic("front", front, area=(-16, -14, 32, 16))
        m.box((-16, 0, 9), (32, 30, 16), "walnut", north=None, down=None)
        m.box((-16, 30, 8.5), (32, 30.5, 16), "walnut", down=None)
        skin = dict(north=fr, south=None, down=None)
        m.box((0, 0, 8.5), (32, 30, 9), "walnut", **skin, west=None)
        m.box((-16, 0, 8.5), (0, 12, 9), "walnut", **skin, east=None)
        m.box((-16, 22.5, 8.5), (0, 30, 9), "walnut", **skin, east=None)
        m.box((-16, 12, 8.5), (-15.5, 22.5, 9), "walnut", north=fr, faces=("north", "east"))
        m.box((-0.5, 12, 8.5), (0, 22.5, 9), "walnut", north=fr, faces=("north", "west"))
        m.box((-15.5, 12, 10.5), (-0.5, 22.5, 11), "walnut", faces=("north",), north=Pic("shelf", shelf))
        m.box((-1, 12, 9), (-0.5, 22.5, 10.5), "walnut", faces=("west",))
        m.box((-16, 12, 9), (-15.5, 22.5, 10.5), "walnut", faces=("east",))
        m.box((-15.5, 11.5, 9), (-0.5, 12, 10.5), "walnut", faces=("up",))
        m.box((-15.5, 22.5, 9), (-0.5, 23, 10.5), "walnut", faces=("down",))
        return m
    return build


# ---------------------------------------------------------------- kitchen

def worktop_edge(cv):
    cv.rect(0, 0, cv.w, cv.h, rgb("b8bcc0"))
    cv.hline(0, cv.w, 0, rgb("e0e4e8"))
    cv.hline(0, cv.w, cv.h - 1, rgb("7c8084"))


def kitchen_doors(cv, W, H, colour, two=True):
    base = rgb("e8e6dc") if colour == "white" else LIGHTWOOD
    t = ramp(base, 6, 0.62, 1.15)
    cv.rect(0, 0, cv.w, cv.h, t[2])
    halves = ((1, cv.w // 2), (cv.w // 2, cv.w - 1)) if two else ((1, cv.w - 1),)
    for x0, x1 in halves:
        if colour == "white":
            cv.rect(x0, 1, x1, cv.h - 1, t[4])
            cv.dither(x0, 1, x1, cv.h // 3, t[4], t[5], 0.2)
            cv.outline(x0, 1, x1, cv.h - 1, t[1])
            cv.hline(x0, x1, 1, t[5])
        else:
            panel(cv, x0, 1, x1, cv.h - 1, base, f"kd{x0}{cv.h}", polish=0.5, frame=1)
    # chrome handles near the middle joint
    hx = (cv.w // 2 - 3, cv.w // 2 + 2) if two else (cv.w - 5,)
    for x in hx:
        cv.vline(x, 4, 9, rgb("e4e8ec")); cv.vline(x + 1, 4, 9, rgb("8c9096"))


def kitchen_base(colour, top=None):
    body = "enamel" if colour == "white" else "lightwood"

    def build():
        m = Model()
        m.box((0.5, 0, 2.5), (15.5, 1.5, 15.5), "black", down=None, up=None)
        m.box((0, 1.5, 1.5), (16, 13, 16), body, down=None,
              north=Pic("doors", lambda cv, W, H: kitchen_doors(cv, W, H, colour)))
        m.box((0, 13, 0.5), (16, 14, 16), "worktop", down=None,
              north=Pic("edge", lambda cv, W, H: worktop_edge(cv)))
        if top == "jars":  # three-litre jars of pickles and compote, a bread bin
            for x, c in ((10.5, "pickles"), (13, "compote")):
                m.box((x, 14, 9), (x + 2.5, 18.5, 11.5), "glass", north=Pic(c, jar_painter(c)), up="jar_lid")
            m.box((1, 14, 7), (9, 18, 14), "cream", north=Pic("breadbin", bread_bin))
        if top == "drainer":
            pass
        return m
    return build


def jar_painter(kind):
    def p(cv, W, H):
        g = rgb("a9c2b4")
        cv.rect(0, 0, cv.w, cv.h, g)
        if kind == "pickles":
            cv.rect(0, 2, cv.w, cv.h, rgb("8ea86a"))
            for (x, y) in ((1, 3), (3, 5), (1, 8), (3, 10), (2, 13), (1, 15)):
                cv.ellipse(x + 0.5, y + 0.5, 1.2, 1.8, rgb("3e6a2a"))
            cv.set(3, 7, rgb("e8e0c0")); cv.set(1, 12, rgb("c03a2a"))
        else:
            cv.rect(0, 2, cv.w, cv.h, rgb("a8303e"))
            for (x, y) in ((1, 4), (3, 7), (2, 11), (1, 14)):
                cv.disc(x + 0.5, y + 0.5, 1.1, rgb("e8a050"))
        cv.vline(1, 2, cv.h - 1, rgb("d8eae4"))
        cv.hline(0, cv.w, 0, rgb("6a7a74"))
    return p


def bread_bin(cv, W, H):
    t = ramp(CREAM, 5, 0.7, 1.1)
    cv.rect(0, 0, cv.w, cv.h, t[3])
    cv.rect(1, 1, cv.w - 1, cv.h - 2, t[3])
    cv.hline(1, cv.w - 1, 1, t[4]); cv.hline(1, cv.w - 1, cv.h - 3, t[1])
    # «ХЛЕБ» in red
    P.text(cv, (cv.w - P.text_width("ХЛЕБ")) // 2, 3, "ХЛЕБ", rgb("b8302a"))
    cv.hline(5, 9, cv.h - 5, rgb("9a9a98"))


def kitchen_sink(colour):
    body = "enamel" if colour == "white" else "lightwood"

    def top(cv, W, H):
        cv.paste(P.Canvas(cv.w, cv.h, (0, 0, 0, 0)), 0, 0)
        tt = MATS_TOP()
        for y in range(cv.h):
            for x in range(cv.w):
                cv.set(x, y, tt.get(x % 32, y % 32))
        # the steel bowl
        st = ramp(rgb("b0b6bc"), 5, 0.55, 1.2)
        bx0, by0, bx1, by1 = 5, 4, cv.w - 5, cv.h - 10
        cv.rect(bx0, by0, bx1, by1, st[4])
        cv.rect(bx0 + 1, by0 + 1, bx1 - 1, by1 - 1, st[2])
        cv.hline(bx0 + 1, bx1 - 1, by0 + 1, st[0])
        cv.vline(bx0 + 1, by0 + 1, by1 - 1, st[1])
        cv.rect(bx0 + 2, by0 + 2, bx1 - 2, by1 - 2, st[3])
        cv.disc((bx0 + bx1) / 2, (by0 + by1) / 2 + 1, 1.6, st[0], ring=st[1])

    def build():
        m = Model()
        m.box((0.5, 0, 2.5), (15.5, 1.5, 15.5), "black", down=None, up=None)
        m.box((0, 1.5, 1.5), (16, 13, 16), body, down=None,
              north=Pic("doors", lambda cv, W, H: kitchen_doors(cv, W, H, colour)))
        m.box((0, 13, 0.5), (16, 14, 16), "worktop", down=None, north=Pic("edge", lambda cv, W, H: worktop_edge(cv)),
              up=Pic("sink", top))
        # the mixer tap on the back
        m.box((7, 14, 13.5), (9, 15, 15), "chrome")
        m.box((7.5, 15, 13.75), (8.5, 19, 14.75), "chrome", up=None, down=None)
        m.box((7.5, 18, 9), (8.5, 19, 13.75), "chrome", south=None)
        m.box((7.5, 17, 9), (8.5, 18, 10), "chrome", up=None, south=None)
        for x in (4.5, 10.5):
            m.box((x, 14, 13.5), (x + 1, 16, 14.5), "chrome", down=None)
            m.box((x - 0.5, 16, 13.25), (x + 1.5, 16.5, 14.75), "plastic_red")
        return m
    return build


def MATS_TOP():
    import furniture
    return furniture.MATS["worktop"]


def wall_cupboard(colour, drainer=False):
    body = "enamel" if colour == "white" else "lightwood"

    def build():
        m = Model()
        # hangs at 1.37..2.0 m: placed in the upper cell of the room, y 6..16
        if drainer:  # an open rack: frame, back, plates and cups on the wire
            m.box((0, 15, 10), (16, 16, 16), body, down=None, south=None).els[-1].faces["down"] = body
            m.box((0, 6, 10), (16, 6.5, 16), body, south=None)
            m.box((0, 6.5, 10), (0.5, 15, 16), body, up=None, down=None, south=None)
            m.box((15.5, 6.5, 10), (16, 15, 16), body, up=None, down=None, south=None)
            m.box((0.5, 6.5, 15.5), (15.5, 15, 16), body, faces=("north",))
            for x in (2, 3.5, 5, 6.5, 8):
                m.box((x, 7, 10.5), (x + 0.5, 13, 15), "plate_rim", west="plate", east="plate", down=None)
            for x in (10.5, 12.5):
                m.box((x, 6.5, 11), (x + 1.5, 9.5, 13.5), "cup_enamel", down=None)
            m.box((1, 9.5, 10.5), (15, 10, 15.5), "chrome", faces=("up", "down"))
        else:
            m.box((0, 6, 10), (16, 16, 16), body, south=None,
                  north=Pic("doors", lambda cv, W, H: kitchen_doors(cv, W, H, colour)))
        return m
    return build


def zil():
    def door(cv, W, H):
        t = ramp(ENAMEL, 6, 0.72, 1.06)
        cv.rect(0, 0, cv.w, cv.h, t[4])
        cv.dither(0, 0, cv.w, cv.h // 4, t[4], t[5], 0.25)
        cv.outline(0, 0, cv.w, cv.h, t[2])
        cv.hline(1, cv.w - 1, 1, t[5])
        cv.vline(1, 1, cv.h - 1, t[5])
        cv.vline(cv.w - 2, 2, cv.h - 1, t[3])
        # the chrome «ЗИЛ» badge and the «Москва» script line
        bw = P.text_width("ЗИЛ") + 5
        bx, by = (cv.w - bw) // 2, 5
        cv.rect(bx, by, bx + bw, by + 9, rgb("d8dce0"))
        cv.outline(bx, by, bx + bw, by + 9, rgb("8a9096"))
        P.text(cv, bx + 3, by + 2, "ЗИЛ", rgb("b02020"))
        for i in range(12):
            cv.set(bx + 2 + i, by + 11 + (i % 4 == 1), rgb("8a9096"))
        # the long lever handle sits in the model; its shadow here
        cv.rect(cv.w - 6, 16, cv.w - 4, 30, t[2])

    def build():
        m = Model()
        m.box((3, 0, 5), (13, 1, 15.5), "black", down=None, up=None)
        m.box((3, 1, 5), (13, 22, 15.5), "enamel", down=None, north=Pic("door", door))
        m.box((3.5, 22, 5.5), (12.5, 23, 15), "enamel", down=None)
        # the chrome lever handle
        m.box((10.5, 11, 3.5), (11.5, 17.5, 4.25), "chrome")
        m.box((10.75, 11.5, 4.25), (11.25, 12.5, 5), "chrome", north=None, south=None)
        m.box((10.75, 16, 4.25), (11.25, 17, 5), "chrome", north=None, south=None)
        return m
    return build


def gas_stove(kettle=False):
    def front(cv, W, H):
        t = ramp(ENAMEL, 6, 0.70, 1.06)
        cv.rect(0, 0, cv.w, cv.h, t[4])
        cv.rect(0, 0, cv.w, 5, t[3])
        cv.hline(0, cv.w, 5, t[1])
        for i, x in enumerate(range(3, cv.w - 2, 5)):  # the four taps
            cv.disc(x + 0.5, 2.5, 1.5, rgb("2a2a2a"))
            cv.set(x, 1, rgb("e0e0e0"))
        # the oven door with its window and a chrome rail
        cv.rect(2, 8, cv.w - 2, cv.h - 3, t[4])
        cv.outline(2, 8, cv.w - 2, cv.h - 3, t[2])
        cv.hline(3, cv.w - 3, 9, rgb("dfe2e6"))
        cv.hline(3, cv.w - 3, 10, rgb("8c9096"))
        cv.rect(5, 13, cv.w - 5, cv.h - 7, rgb("2a2c30"))
        cv.outline(5, 13, cv.w - 5, cv.h - 7, rgb("8c9096"))
        cv.set(6, 14, rgb("6a7280")); cv.set(7, 14, rgb("4a5260"))
        cv.rect(0, cv.h - 2, cv.w, cv.h, rgb("2a2a2c"))
        # «Электа» in chrome script
        for x in range(cv.w // 2 - 4, cv.w // 2 + 4):
            cv.set(x, cv.h - 5, rgb("b0b4b8") if x % 2 else rgb("e0e4e8"))

    def top(cv, W, H):
        t = ramp(ENAMEL, 6, 0.7, 1.06)
        cv.rect(0, 0, cv.w, cv.h, t[4])
        cv.outline(0, 0, cv.w, cv.h, t[2])
        for cx, cy in ((cv.w * 0.28, cv.h * 0.3), (cv.w * 0.72, cv.h * 0.3), (cv.w * 0.28, cv.h * 0.72), (cv.w * 0.72, cv.h * 0.72)):
            cv.disc(cx, cy, 4.2, rgb("3a3a3c"))
            cv.disc(cx, cy, 2.6, rgb("1e1e20"), ring=rgb("5a5a5c"))
            cv.disc(cx, cy, 1.0, rgb("6a6a6c"))

    def build():
        m = Model()
        # the counter edges either side keep the worktop line of the kitchen set
        m.box((0, 0, 2), (2, 13, 16), "enamel", down=None)
        m.box((14, 0, 2), (16, 13, 16), "enamel", down=None)
        m.box((0, 13, 0.5), (2, 14, 16), "worktop", down=None)
        m.box((14, 13, 0.5), (16, 14, 16), "worktop", down=None)
        m.box((2, 0, 1.5), (14, 13.5, 15.5), "enamel", down=None, north=Pic("front", front), up=Pic("top", top))
        # pan supports over the burners and the lid at the back
        for z in (4.5, 10.5):
            m.box((3, 13.5, z), (13, 14, z + 0.5), "iron_black", down=None)
        for x in (5, 10.5):
            m.box((x, 14, 2.5), (x + 0.5, 14.5, 13.5), "iron_black", down=None)
        m.box((2, 13.5, 14.5), (14, 19, 15.5), "enamel")
        m.box((2, 0.5, 1), (14, 1.5, 1.5), "black", up=None, down=None)
        if kettle:  # the whistling kettle, white enamel with red poppies, and a pot on the back burner
            m.box((3.5, 14, 3.5), (8, 18, 8), "kettle", up="enamel", down=None)
            m.box((4.5, 18, 4.5), (7, 18.5, 7), "enamel", down=None)
            m.box((5.5, 18.5, 5.5), (6, 19, 6), "black", down=None)
            m.box((8, 15.5, 5.5), (10, 16.5, 6), "enamel", rot={"origin": [8, 15.5, 5.75], "axis": "z", "angle": 45})
            m.box((5.5, 18.5, 3.75), (6, 20, 4.25), "black", down=None)
            m.box((5.5, 18.5, 7.25), (6, 20, 7.75), "black", down=None)
            m.box((5.5, 20, 3.75), (6, 20.5, 7.75), "black")
            m.box((9, 14.5, 9), (13, 17, 13), "pot", down=None)
            m.box((8.5, 16.5, 10.5), (9, 17, 11.5), "black", down=None)
            m.box((13, 16.5, 10.5), (13.5, 17, 11.5), "black", down=None)
            m.box((9.25, 17, 9.25), (12.75, 17.5, 12.75), "pot", down=None)
        return m
    return build


def kettle_tex():
    cv = P.enamel(32, 32, ENAMEL)
    for (x, y) in ((6, 12), (20, 18), (12, 22), (26, 8)):
        cv.disc(x, y, 2.2, rgb("c8302a"))
        cv.set(x, y, rgb("2a1a1a"))
        cv.set(x - 3, y + 2, rgb("4a7a2a")); cv.set(x - 2, y + 3, rgb("4a7a2a"))
    return cv


def pot_tex():
    cv = P.enamel(32, 32, rgb("e8a040"))
    cv.hline(0, 32, 2, rgb("f0f0e8")); cv.hline(0, 32, 3, rgb("f0f0e8"))
    for x in range(0, 32, 8):
        cv.disc(x + 4, 16, 2, rgb("f4f0e0"))
    return cv


def kitchen_table():
    def oilcloth(cv, W, H):
        a, b = rgb("f2ede2"), rgb("c43a34")
        for y in range(cv.h):
            for x in range(cv.w):
                c = (x // 4 + y // 4) % 2
                both = (x // 4) % 2 and (y // 4) % 2
                cv.set(x, y, mix(b, rgb("801818"), 0.4) if both else (b if c else a))
        for y in range(cv.h):  # small blue flowers on the white squares
            for x in range(cv.w):
                if (x // 4 + y // 4) % 2 == 0 and x % 4 == 2 and y % 4 == 2 and ((x // 4) % 2 == 0):
                    cv.set(x, y, rgb("3a5aa0"))

    def build():
        m = Model()
        legs(m, 1.5, 3, 14.5, 13, 11.5, "lightwood_v", 1.5)
        m.box((2, 9.5, 3.5), (14, 11.5, 12.5), "lightwood", down=None,
              north=Pic("drawer", lambda cv, W, H: (wood_canvas(cv, LIGHTWOOD, "ktd", vertical=False, polish=0.4),
                                                   cv.outline(0, 0, cv.w, cv.h, ramp(LIGHTWOOD)[1]), knob(cv, cv.w // 2 - 1, cv.h // 2 - 1, rgb("e0e0e0")))))
        m.box((1, 11.5, 2.5), (15, 12, 13.5), "oilcloth_side", down=None, up=Pic("oilcloth", oilcloth))
        # sugar bowl and a cup of tea in a glass holder
        m.box((3, 12, 5), (5, 13.5, 7), "glass_tea", down=None)
        m.box((2.75, 12, 4.75), (5.25, 12.5, 7.25), "brass", down=None)
        m.box((10, 12, 8), (12.5, 14, 10.5), "enamel", down=None)
        m.box((10.5, 14, 8.5), (12, 14.5, 10), "enamel", down=None)
        return m
    return build


# ---------------------------------------------------------------- bedroom

def bed_single(kind):
    def blanket(cv, W, H):
        if kind == "wood":
            a, b, c = rgb("8a3a3a"), rgb("c0904a"), rgb("e6d6b0")
            for y in range(cv.h):
                for x in range(cv.w):
                    v = a
                    if (x % 12) in (0, 1) or (y % 12) in (0, 1):
                        v = b
                    if (x % 12) == 6 or (y % 12) == 6:
                        v = c if (x + y) % 2 else b
                    cv.set(x, y, v)
        else:
            cv.paste(P.fabric(cv.w, cv.h, rgb("e8e2d2"), "bedspread"), 0, 0)
            for y in range(cv.h):
                for x in range(cv.w):
                    if ((x - y) % 8 == 0) or ((x + y) % 8 == 0):
                        cv.set(x, y, rgb("d6ccb6"))
            cv.outline(0, 0, cv.w, cv.h, rgb("c8b898"))

    def build():
        m = Model()
        if kind == "wood":
            # polished headboard and footboard, side rails, mattress, blanket over it, a pillow
            m.box((0.5, 0, -16), (15.5, 15, -14.5), "walnut", down=None,
                  north=Pic("head_back", lambda cv, W, H: panel(cv, 0, 0, cv.w, cv.h, WALNUT, "hb1", vertical=False)),
                  south=Pic("head", lambda cv, W, H: panel(cv, 0, 0, cv.w, cv.h, WALNUT, "hb2", vertical=False)))
            m.box((0.5, 0, 14.5), (15.5, 9, 16), "walnut", down=None,
                  north=Pic("foot", lambda cv, W, H: panel(cv, 0, 0, cv.w, cv.h, WALNUT, "fb", vertical=False)))
            for x in (0.5, 14.5):
                m.box((x, 2.5, -14.5), (x + 1, 4.5, 14.5), "walnut", down=None)
            m.box((1.5, 3, -14.5), (14.5, 6.5, 14.5), "linen", down=None)
            m.box((1, 6.5, -9), (15, 7.5, 14.25), "blanket_edge", down=None, up=Pic("blanket", blanket))
            m.box((1, 4, -9), (1.5, 6.5, 14.25), "blanket_edge", up=None, down=None, east=None)
            m.box((14.5, 4, -9), (15, 6.5, 14.25), "blanket_edge", up=None, down=None, west=None)
            m.box((1.5, 6.5, -14), (14.5, 7, -9), "linen", down=None)
            m.box((3, 7, -14), (13, 9.5, -9.5), "pillow", down=None)
        else:
            # the iron bed: black tubes with nickel knobs, a lace valance to the floor, a tower of pillows under a tulle cover
            for x in (0.5, 14.5):
                for z, h in ((-15.75, 14.5), (14.5, 11)):
                    m.box((x, 0, z), (x + 1, h, z + 1), "iron_black", down=None)
                    m.box((x - 0.25, h, z - 0.25), (x + 1.25, h + 1.5, z + 1.25), "chrome")
            for z, h in ((-15.75, 14.5), (14.5, 11)):
                m.box((1.5, h - 1.5, z + 0.25), (14.5, h - 0.5, z + 0.75), "chrome", down=None)
                m.box((1.5, 4, z + 0.25), (14.5, 5, z + 0.75), "iron_black")
                for x in (3.5, 6, 8.5, 11):
                    m.box((x, 5, z + 0.25), (x + 0.5, h - 1.5, z + 0.75), "iron_black", up=None, down=None)
            m.box((1, 4, -15), (15, 7.5, 14.5), "bedspread_side", down=None, up=Pic("spread", blanket))
            m.box((1, 0.5, -15.25), (15, 4, 14.75), "valance", up=None, down=None)
            for i, (w, h, z0) in enumerate(((12, 3, -15), (10, 2.5, -14.5), (8, 2, -14))):
                x0 = 8 - w / 2
                y0 = 7.5 + sum((3, 2.5, 2)[:i])
                m.box((x0, y0, z0), (x0 + w, y0 + h, z0 + 2.5 - i * 0.25), "pillow", down=None)
            m.box((2, 7.5, -12), (14, 8, -6), "lace_cover", down=None)
        return m
    return build


def blanket_edge():
    cv = P.fabric(32, 32, rgb("8a3a3a"), "bedge")
    return cv


def pillow_tex():
    cv = P.Canvas(32, 32, rgb("f4f2ec"))
    cv.dither(0, 0, 32, 32, rgb("f4f2ec"), rgb("e8e6e0"), 0.2)
    cv.outline(0, 0, 32, 32, rgb("dcdad2"))
    for x in range(0, 32, 3):
        cv.set(x, 1, rgb("d0ccc0"))
    return cv


def valance_tex():
    """Crocheted lace with holes (cut out), white with a scalloped lower edge."""
    cv = P.Canvas(32, 32, rgb("f6f4ee"))
    for y in range(32):
        for x in range(32):
            if (x % 6 in (2, 3) and y % 6 in (2, 3)) or (y > 26 and (x % 8 - 4) ** 2 + (y - 26) ** 2 * 2 > 16):
                cv.px[x, y] = (0, 0, 0, 0)
            elif (x + y) % 6 == 0:
                cv.set(x, y, rgb("e2dfd4"))
    return cv


def lace_cover_tex():
    cv = P.Canvas(32, 32, (0, 0, 0, 0))
    for y in range(32):
        for x in range(32):
            if (x + y) % 4 == 0 or (x - y) % 4 == 0:
                cv.set(x, y, rgb("f8f6f0"))
    return cv


def wardrobe():
    def front(cv, W, H):
        wood_canvas(cv, WALNUT, "wardrobe", vertical=True, polish=1.2)
        t = ramp(WALNUT, 6, 0.6, 1.2)
        half = cv.w // 2
        panel(cv, 1, 2, half, cv.h - 10, WALNUT, "wr1", polish=1.3)
        # the right door carries a mirror
        panel(cv, half, 2, cv.w - 1, cv.h - 10, WALNUT, "wr2", polish=1.3)
        cv.rect(half + 4, 6, cv.w - 5, cv.h - 14, rgb("b8c8d0"))
        cv.outline(half + 4, 6, cv.w - 5, cv.h - 14, rgb("6a5a40"))
        for y in range(7, cv.h - 15):
            for x in range(half + 5, cv.w - 6):
                if (x - y) % 13 in (0, 1):
                    cv.set(x, y, rgb("e4eef2"))
        cv.vline(half - 2, 24, 32, ramp(BRASS)[3]); cv.vline(half + 1, 24, 32, ramp(BRASS)[3])
        panel(cv, 1, cv.h - 10, cv.w - 1, cv.h - 3, WALNUT, "wrd", vertical=False, frame=1)
        handle_bar(cv, half - 4, cv.h - 7, half + 4, BRASS)
        cv.rect(0, 0, cv.w, 2, t[4]); cv.rect(0, cv.h - 2, cv.w, cv.h, t[0])

    def build():
        m = Model()
        m.box((0.5, 0, 6), (15.5, 30, 16), "walnut", north=Pic("front", front))
        m.els[-1].faces.pop("down")
        m.box((0, 30, 5.5), (16, 30.5, 16), "walnut")
        return m
    return build


# ---------------------------------------------------------------- bathroom

def bathtub():
    def build():
        m = Model()
        # cast iron, 1.75 x 0.75 m, white enamel inside and out, on four short feet; the inside is real (walls and bottom)
        m.box((1.5, 1.5, -14), (14.5, 3, 14), "enamel", down=None, up="enamel_in")
        for f, t in (((1.5, 3, -14), (3, 9, 14)), ((13, 3, -14), (14.5, 9, 14)), ((3, 3, -14), (13, 9, -12.5)), ((3, 3, 12.5), (13, 9, 14))):
            m.box(f, t, "enamel", up=None, down=None)
        m.els[1].faces["east"] = "enamel_in"; m.els[2].faces["west"] = "enamel_in"
        m.els[3].faces["south"] = "enamel_in"; m.els[4].faces["north"] = "enamel_in"
        m.els[1].faces.pop("north"); m.els[1].faces.pop("south"); m.els[2].faces.pop("north"); m.els[2].faces.pop("south")
        for f, t in (((1, 9, -14.5), (3, 10, 14.5)), ((13, 9, -14.5), (15, 10, 14.5)), ((3, 9, -14.5), (13, 10, -12.5)), ((3, 9, 12.5), (13, 10, 14.5))):
            m.box(f, t, "enamel", down=None)
        m.els[-4].faces.pop("east"); m.els[-3].faces.pop("west")
        m.els[-2].faces.pop("east"); m.els[-2].faces.pop("west"); m.els[-1].faces.pop("east"); m.els[-1].faces.pop("west")
        m.els[-4].faces["east"] = "enamel"
        m.els[-4].faces.pop("east")
        for x in (2.5, 12.5):
            for z in (-13, 12):
                m.box((x, 0, z), (x + 1, 1.5, z + 1), "iron_black", down=None, up=None)
        # the mixer and the shower hose on the wall at the foot end (z 16)
        m.box((6, 12, 15), (10, 13, 16), "chrome")
        m.box((7.5, 11, 13), (8.5, 12, 15.5), "chrome", south=None)
        m.box((5.5, 12, 14.5), (6.5, 13, 15), "chrome", faces=("north", "up", "down", "west"))
        m.box((9.5, 12, 14.5), (10.5, 13, 15), "chrome", faces=("north", "up", "down", "east"))
        return m
    return build


def bath_inside_tex():
    cv = P.enamel(32, 32, ENAMEL)
    return cv


def water_tex():
    cv = P.Canvas(32, 32, rgb("a8d0dc"))
    for y in range(32):
        for x in range(32):
            if (x + 2 * (y // 4)) % 9 == 0:
                cv.set(x, y, rgb("d4ecf2"))
    return cv


def toilet():
    def build():
        m = Model()
        # a "compact": bowl with a pedestal, plastic seat and lid, the cistern on the bowl's back
        m.box((5, 0, 3), (11, 6, 11), "enamel", down=None)
        m.box((4, 6, 1.5), (12, 8, 12), "enamel", down=None, up="toilet_top")
        m.box((3.75, 8, 1.25), (12.25, 8.5, 12.25), "plastic_beige", down=None, up="seat_ring")
        m.box((4, 8.5, 11.5), (12, 15.5, 12.5), "plastic_beige", down=None)
        m.box((3, 8, 12), (13, 16, 15.5), "enamel", down=None)
        m.box((2.5, 16, 11.5), (13.5, 17, 16), "enamel", down=None)
        m.box((7, 17, 13), (9, 17.5, 14.5), "chrome", down=None)
        return m
    return build


def seat_ring_tex():
    cv = P.Canvas(32, 32, rgb("d8ccb0"))
    cv.ellipse(16, 14, 9, 11, rgb("f0f0ec"))
    cv.ellipse(16, 14, 7, 9, rgb("6a7a82"))
    return cv


def toilet_top_tex():
    cv = P.enamel(32, 32, ENAMEL)
    cv.ellipse(16, 13, 9, 11, rgb("c8d8e0"))
    cv.ellipse(16, 13, 6, 8, rgb("8ab0c0"))
    return cv


def washbasin():
    def top(cv, W, H):
        t = ramp(ENAMEL, 6, 0.6, 1.06)
        cv.rect(0, 0, cv.w, cv.h, t[4])
        cv.outline(0, 0, cv.w, cv.h, t[3])
        cv.ellipse(cv.w / 2, cv.h / 2 - 1, cv.w / 2 - 3, cv.h / 2 - 4, t[2])
        cv.ellipse(cv.w / 2, cv.h / 2 - 1, cv.w / 2 - 5, cv.h / 2 - 6, t[3])
        cv.disc(cv.w / 2, cv.h / 2, 1.2, rgb("5a5a5c"))

    def build():
        m = Model()
        # the "tulip": a pedestal, the basin, two taps, a soap dish, a mirror cabinet above
        m.box((6, 0, 9), (10, 11, 13), "enamel", down=None)
        m.box((2.5, 11, 6), (13.5, 14, 16), "enamel", down=None, up=Pic("basin", top))
        for x in (5, 10):
            m.box((x, 14, 14), (x + 1, 15.5, 15), "chrome", down=None)
            m.box((x - 0.5, 15.5, 13.5), (x + 1.5, 16, 15.5), "chrome")
        m.box((7.5, 14, 13.5), (8.5, 15.5, 14.5), "chrome", down=None)
        m.box((7.5, 15, 11.5), (8.5, 15.5, 13.5), "chrome", south=None)
        # mirror cabinet with a shelf (upper cell, from 1.15 to 1.8 m)
        m.box((2.5, 18.5, 14), (13.5, 29, 16), "enamel", north=Pic("mirror", mirror_pic))
        m.box((2.5, 17.5, 12.5), (13.5, 18.5, 16), "glass_shelf")
        m.box((3.5, 18.5, 13), (5, 21, 14), "glass", down=None)
        m.box((3.75, 21, 13.25), (4.25, 22.5, 13.75), "plastic_red", down=None)
        m.box((4.25, 21, 13.25), (4.75, 22, 13.75), "plastic_blue", down=None)
        m.box((10, 18.5, 12.75), (12.5, 19.5, 14), "soap", down=None)
        return m
    return build


def mirror_pic(cv, W, H):
    t = ramp(ENAMEL, 5, 0.75, 1.05)
    cv.rect(0, 0, cv.w, cv.h, t[3])
    cv.rect(2, 2, cv.w - 2, cv.h - 2, rgb("aebcc4"))
    for y in range(2, cv.h - 2):
        for x in range(2, cv.w - 2):
            if (x - y) % 11 in (0, 1, 2):
                cv.set(x, y, rgb("d8e4ea"))
            elif (x - y) % 11 == 5:
                cv.set(x, y, rgb("c4d2da"))
    cv.outline(1, 1, cv.w - 1, cv.h - 1, t[1])


def vyatka():
    def front(cv, W, H):
        t = ramp(ENAMEL, 6, 0.7, 1.06)
        cv.rect(0, 0, cv.w, cv.h, t[4])
        cv.outline(0, 0, cv.w, cv.h, t[2])
        cv.rect(1, 1, cv.w - 1, 7, t[3])
        cv.hline(1, cv.w - 1, 7, t[1])
        # the program dial, buttons, the «Вятка-автомат» name
        cv.disc(cv.w - 6, 4, 2.4, rgb("d8d8d4"), ring=rgb("6a6a6c"))
        cv.set(cv.w - 6, 2, rgb("c02020"))
        for x in (3, 6, 9):
            cv.rect(x, 3, x + 2, 5, rgb("2a2a2c"))
        cv.hline(12, cv.w - 10, 4, rgb("2a5a9a"))
        # the round door: chrome ring, dark glass, a little foam
        cx, cy, r = cv.w / 2, (cv.h + 7) / 2, min(cv.w, cv.h - 7) / 2 - 2
        cv.disc(cx, cy, r, rgb("a0a6ac"), ring=rgb("6a7076"))
        cv.disc(cx, cy, r - 2, rgb("3a4a56"), ring=rgb("dfe3e7"))
        cv.disc(cx, cy, r - 4, rgb("4a6070"))
        cv.ellipse(cx, cy + r - 6, r - 5, 2, rgb("e8f0f2"))
        cv.set(int(cx - r / 2), int(cy - r / 2), rgb("d8e8f0"))
        cv.rect(1, cv.h - 3, cv.w - 1, cv.h - 1, t[2])

    def build():
        m = Model()
        m.box((2, 0, 3), (14, 14, 15.5), "enamel", down=None, north=Pic("front", front), up="vyatka_top")
        return m
    return build


def towel_rail():
    def build():
        m = Model()
        # the chrome coil on the riser (wall, 1.0..1.6 m in the upper cell) and a striped towel over it
        m.box((3, 0, 14.5), (4, 14, 15.5), "chrome", up=None, down=None)
        m.box((12, 0, 14.5), (13, 14, 15.5), "chrome", up=None, down=None)
        for y in (2, 6, 10):
            m.box((3, y, 12.5), (13, y + 1, 13.5), "chrome_h")
            m.box((3, y, 13.5), (4, y + 1, 14.5), "chrome_h", up=None, down=None)
            m.box((12, y, 13.5), (13, y + 1, 14.5), "chrome_h", up=None, down=None)
        m.box((5, 3.5, 12), (11, 11.5, 12.5), "towel", up=None)
        m.box((5, 11.5, 12), (11, 12, 14), "towel", down=None)
        m.box((5, 6, 13.5), (11, 11.5, 14), "towel", up=None)
        return m
    return build


def towel_tex():
    cv = P.fabric(32, 32, rgb("e8dcc0"), "towel", weave=1)
    for y in range(32):
        if y % 10 in (4, 5, 6):
            cv.hline(0, 32, y, rgb("c0503a") if y % 10 != 5 else rgb("e0805a"))
    return cv


# ---------------------------------------------------------------- lights

def chandelier(on):
    sh = "shade_white_lit" if on else "shade_white"

    def build():
        m = Model()
        # a brass cup and four arms with frosted tulips turned upwards; the lowest point stays at 1.85 m (y 13.6 here)
        m.box((6.5, 15, 6.5), (9.5, 16, 9.5), "brass")
        m.box((7.25, 14, 7.25), (8.75, 15, 8.75), "brass", up=None)
        m.box((2.5, 14.25, 7.5), (7.25, 14.75, 8.5), "brass", faces=("north", "south", "up", "down"))
        m.box((8.75, 14.25, 7.5), (13.5, 14.75, 8.5), "brass", faces=("north", "south", "up", "down"))
        m.box((7.5, 14.25, 2.5), (8.5, 14.75, 7.25), "brass", faces=("east", "west", "up", "down"))
        m.box((7.5, 14.25, 8.75), (8.5, 14.75, 13.5), "brass", faces=("east", "west", "up", "down"))
        for cx, cz in ((2, 8), (14, 8), (8, 2), (8, 14)):
            m.box((cx - 1.75, 13.75, cz - 1.75), (cx + 1.75, 15.75, cz + 1.75), sh, shade=not on, up=None)
            m.box((cx - 1, 15.5, cz - 1), (cx + 1, 15.5, cz + 1), "bulb" if on else "bulb_off", faces=("up",), shade=not on)
        return m
    return build


def ceiling_plate(on):
    def build():
        m = Model()
        m.box((3, 15, 3), (13, 16, 13), "shade_white_lit" if on else "shade_white", shade=not on,
              down=Pic("plate_" + ("on" if on else "off"), lambda cv, W, H: plate_pattern(cv, on)))
        m.box((7, 14.5, 7), (9, 15, 9), "brass", up=None)
        return m
    return build


def plate_pattern(cv, on):
    base = rgb("fffae6") if on else rgb("e8e4d8")
    t = ramp(base, 5, 0.8, 1.05)
    cv.rect(0, 0, cv.w, cv.h, t[3])
    cx, cy = cv.w / 2, cv.h / 2
    for y in range(cv.h):
        for x in range(cv.w):
            d = math.hypot(x + 0.5 - cx, y + 0.5 - cy)
            if int(d) % 4 == 0:
                cv.set(x, y, t[2])
            a = math.atan2(y - cy, x - cx)
            if int(d) in (6, 7) and int(a * 12 / math.pi) % 2:
                cv.set(x, y, t[1])
    cv.outline(0, 0, cv.w, cv.h, t[1])


def floor_lamp(on):
    shade_m = "lampshade_lit" if on else "lampshade"

    def build():
        m = Model()
        # 1.7 m: a round wooden foot, a brass stem, a pleated shade open at the top and bottom (thin walls, seen inside too)
        m.box((5, 0, 5), (11, 1, 11), "walnut", down=None)
        m.box((7.5, 1, 7.5), (8.5, 21, 8.5), "brass", down=None)
        lo, hi, a, b = 19, 27, 3, 13
        sh = not on
        m.box((a, lo, a), (b, hi, a + 0.5), shade_m, shade=sh, faces=("north", "south", "up", "down"))
        m.box((a, lo, b - 0.5), (b, hi, b), shade_m, shade=sh, faces=("north", "south", "up", "down"))
        m.box((a, lo, a + 0.5), (a + 0.5, hi, b - 0.5), shade_m, shade=sh, faces=("east", "west", "up", "down"))
        m.box((b - 0.5, lo, a + 0.5), (b, hi, b - 0.5), shade_m, shade=sh, faces=("east", "west", "up", "down"))
        m.box((6.5, 21, 6.5), (9.5, 23.5, 9.5), "bulb" if on else "bulb_off", shade=sh)
        # the wire spider holding the shade
        m.box((a + 0.5, 25.5, 7.75), (b - 0.5, 26, 8.25), "brass")
        m.box((7.75, 25.5, a + 0.5), (8.25, 26, 7.75), "brass")
        m.box((7.75, 25.5, 8.25), (8.25, 26, b - 0.5), "brass")
        m.box((7.75, 23.5, 7.75), (8.25, 25.5, 8.25), "brass", up=None, down=None)
        return m
    return build


def sconce(on):
    def build():
        m = Model()
        # a brass wall plate, a curved arm and a frosted tulip opening upwards, at 1.6 m by the bed or the mirror
        m.box((6.5, 2, 15), (9.5, 8, 16), "brass", south=None)
        m.box((7.5, 4, 12), (8.5, 5, 15), "brass", south=None)
        m.box((7.5, 5, 12), (8.5, 7, 13), "brass", down=None)
        sh = "shade_white_lit" if on else "shade_white"
        m.box((6.5, 7, 11), (9.5, 8.5, 14), sh, shade=not on, up=None)
        # the wider rim: thin walls so the bulb shows inside
        lo, hi, a0, a1, c0, c1 = 8.5, 11.5, 5.5, 10.5, 10, 15
        m.box((a0, lo, c0), (a1, hi, c0 + 0.5), sh, shade=not on, faces=("north", "south", "up", "east", "west"))
        m.box((a0, lo, c1 - 0.5), (a1, hi, c1), sh, shade=not on, faces=("north", "south", "up", "east", "west"))
        m.box((a0, lo, c0 + 0.5), (a0 + 0.5, hi, c1 - 0.5), sh, shade=not on, faces=("east", "west", "up"))
        m.box((a1 - 0.5, lo, c0 + 0.5), (a1, hi, c1 - 0.5), sh, shade=not on, faces=("east", "west", "up"))
        m.box((a0 + 0.5, lo, c0 + 0.5), (a1 - 0.5, lo, c1 - 0.5), sh, shade=not on, faces=("up",))
        m.box((7, 8.5, 11.5), (9, 10.5, 13.5), "bulb" if on else "bulb_off", shade=not on, down=None)
        return m
    return build


def radiator():
    def build():
        m = Model()
        # cast-iron MS-140 sections under the window sill, painted many times over: 0.1..0.5 m
        for i in range(10):
            x = 0.3 + i * 1.55
            m.box((x, 1.5, 12.5), (x + 1.2, 8, 15.5), "radiator", down=None)
        m.box((0, 2.5, 13.5), (16, 3.5, 14.5), "radiator")
        m.box((0, 6.5, 13.5), (16, 7.5, 14.5), "radiator")
        return m
    return build


def radiator_tex():
    cv = P.enamel(32, 32, rgb("e6e2d6"), "rad", chips=3)
    for y in range(32):
        if y % 6 == 0:
            cv.hline(0, 32, y, rgb("c8c2b2"))
    return cv


# ---------------------------------------------------------------- curtains

CURTAINS = {"wine": (rgb("7a2232"), "бордовые", "wine"), "olive": (rgb("6a6a32"), "оливковые", "olive"),
            "gold": (rgb("b48a3a"), "золотистые", "golden"), "blue": (rgb("2e4a7a"), "синие", "blue")}


def curtain_tex(c, seed):
    """Heavy curtain cloth: vertical folds (light crest, dark valley) and a woven pattern of small medallions."""
    t = ramp(c, 6, 0.55, 1.2)
    cv = P.Canvas(32, 32, t[3])
    fold = (1, 2, 3, 4, 4, 3, 3, 2)
    for x in range(32):
        cv.vline(x, 0, 32, t[fold[x % 8]])
    for y in range(2, 32, 8):
        for x in range(3, 32, 8):
            cv.set(x, y, t[5]); cv.set(x + 1, y + 1, t[5]); cv.set(x - 1, y + 1, t[5]); cv.set(x, y + 2, t[5])
    return cv


def tulle_tex():
    """Net curtain: half see-through white with a denser thread every few texels and small embroidered flowers (translucent layer)."""
    cv = P.Canvas(32, 32, (0, 0, 0, 0))
    for y in range(32):
        for x in range(32):
            a = 70
            if x % 4 == 0 or y % 4 == 0:
                a = 120
            if ((x - 8) % 16 in (0, 1) and (y - 6) % 16 in (0, 1)) or ((x - 8) % 16 in (15, 2) and (y - 6) % 16 in (0, 1)) \
                    or ((x - 8) % 16 in (0, 1) and (y - 6) % 16 in (15, 2)):
                a = 210
            cv.px[x, y] = (248, 247, 242, a)
    return cv


def tulle_hem_tex():
    cv = tulle_tex()
    for y in range(22, 32):
        for x in range(32):
            if (x % 8 - 3.5) ** 2 + (y - 27) ** 2 < 10:
                cv.px[x, y] = (250, 249, 245, 225)
            elif y > 29:
                cv.px[x, y] = (0, 0, 0, 0)
    return cv


def curtains(key):
    c, ru, en = CURTAINS[key]
    mat(f"curtain_{key}", curtain_tex(c, key))
    mat(f"curtain_{key}_dark", curtain_tex(shade(c, 0.85), key + "d"))

    def pieces():
        cl, dk = f"curtain_{key}", f"curtain_{key}_dark"
        # depth in the cell in front of the window: rod and curtains z 9.5..12, net curtain z 12.5, a radiator (if the state says so)
        # behind it z 13..15.5 under the sill, which belongs to the wall's window block
        rod = Model()
        rod.box((0, 30.25, 10), (16, 31.25, 11), "brass")
        for x in (1, 5, 9, 13):
            rod.box((x, 29.75, 9.75), (x + 1.5, 30.25, 11.25), "brass", up=None)
        end_l = Model().box((14.5, 29.75, 9.5), (16, 31.75, 11.5), "brass")
        end_r = Model().box((0, 29.75, 9.5), (1.5, 31.75, 11.5), "brass")
        tulle = Model()
        tulle.box((0, 16, 12.5), (16, 30, 12.5), "tulle", faces=("north", "south"))
        tulle.box((0, 0.5, 12.5), (16, 16, 12.5), "tulle_hem", faces=("north", "south"))
        closed = Model()
        # four pleats a cell, alternately forward and back: real depth, not just a painted fold
        for i in range(4):
            x0 = i * 4
            z0 = 10.5 if i % 2 == 0 else 11.25
            closed.box((x0, 1, z0), (x0 + 4, 29.75, z0 + 0.75), cl, down=None, south=None, east=dk, west=dk)
        open_l, open_r = Model(), Model()
        for model, x0, sgn in ((open_l, 11, 1), (open_r, 0, -1)):
            # gathered at the window's edge: narrow, thick, tied back at 0.85 m with a cord
            for a, b, z0 in ((0, 1.75, 10), (1.75, 3.5, 10.75), (3.5, 5, 10.25)):
                xa, xb = x0 + a, x0 + b
                model.box((xa, 15.5, z0), (xb, 29.75, z0 + 1.5), cl, down=None, south=None, east=dk, west=dk)
                model.box((xa, 1, z0 - 0.5), (xb, 13, z0 + 1.5), cl, south=None, east=dk, west=dk)
                model.box((xa + 0.25, 13, z0 + 0.25), (xb - 0.25, 15.5, z0 + 1.25), cl, south=None, up=None, down=None, east=dk, west=dk)
            model.box((x0 - 0.25, 13.5, 9.25), (x0 + 5.25, 14.5, 9.75), "cord", south=None)
            model.box((x0 + (5.25 if sgn > 0 else -0.5), 13.5, 9.75), (x0 + (5.75 if sgn > 0 else 0), 14.5, 12), "cord")
        rad = radiator()()
        for e in rad.els:
            e.f[2] += 0.5 if e.f[2] >= 13 else 0.5
            e.t[2] = min(15.5, e.t[2])
        return {"rod": rod, "rod_end_l": end_l, "rod_end_r": end_r, "tulle": tulle, "closed": closed, "open_l": open_l, "open_r": open_r,
                "radiator": rad}
    Furn(f"apt_curtain_{key}", f"Шторы с тюлем ({ru})", f"Curtains with net ({en})", pieces, mount="WALL", kind="curtain",
         color={"wine": "RED", "olive": "GREEN", "gold": "GOLD", "blue": "BLUE"}[key], sound="WOOL", solid=False, cutout=True)


# ---------------------------------------------------------------- the list

def make():
    materials()
    mat("kettle", kettle_tex())
    mat("pot", pot_tex())
    mat("jar_lid", P.metal(32, 32, rgb("c8c4b0"), vertical=False))
    mat("oilcloth_side", P.enamel(32, 32, rgb("c43a34")))
    mat("glass_tea", P.glass(32, 32, rgb("c48a3a")))
    mat("plate", plate_tex())
    mat("plate_rim", P.enamel(32, 32, rgb("f2f0ea")))
    mat("cup_enamel", P.enamel(32, 32, rgb("f0eee6")))
    mat("blanket_edge", blanket_edge())
    mat("pillow", pillow_tex())
    mat("valance", valance_tex())
    mat("lace_cover", lace_cover_tex())
    mat("bedspread_side", P.fabric(32, 32, rgb("e8e2d2"), "bss"))
    mat("bath_side", P.enamel(32, 32, rgb("e4e2da")))
    mat("enamel_in", bath_inside_tex())
    mat("water", water_tex())
    mat("toilet_top", toilet_top_tex())
    mat("seat_ring", seat_ring_tex())
    mat("plastic_blue", P.enamel(32, 32, rgb("3a6ab0")))
    mat("soap", P.enamel(32, 32, rgb("e8c070")))
    mat("glass_shelf", P.glass(32, 32, rgb("b8d0d4")))
    mat("vyatka_top", P.enamel(32, 32, ENAMEL))
    mat("towel", towel_tex())
    mat("radiator", radiator_tex())
    mat("tulle", tulle_tex())
    mat("tulle_hem", tulle_hem_tex())
    mat("cord", P.metal(32, 32, rgb("c8a040")))

    # living room
    for col in ("red", "green", "blue"):
        Furn(f"apt_sofa_{col}", f"Диван-книжка ({RU[col]})", f"Sofa bed ({col})", sofa_book(col), seat=7, color=MAPC[col], sound="WOOL")
    for col in ("red", "green", "brown"):
        Furn(f"apt_armchair_{col}", f"Кресло ({RU[col]})", f"Armchair ({col})", armchair(col), seat=7.5, color=MAPC[col], sound="WOOL")
    Furn("apt_wall_unit", "Стенка", "Wall unit", stenka(), color="BROWN")
    Furn("apt_tv", "Телевизор «Рубин»", "Rubin TV", tv, toggle=True, light=4, color="BROWN")
    Furn("apt_coffee_table", "Журнальный столик", "Coffee table", coffee_table, color="BROWN")
    Furn("apt_chair", "Стул", "Chair", chair, seat=8, color="BROWN")
    # kitchen
    Furn("apt_stool", "Табурет", "Stool", stool, seat=8, color="OAK_TAN")
    Furn("apt_fridge", "Холодильник «ЗиЛ-Москва»", "ZIL fridge", zil(), color="WHITE", sound="METAL")
    Furn("apt_stove", "Газовая плита", "Gas stove", gas_stove(), color="WHITE", sound="METAL")
    Furn("apt_stove_kettle", "Газовая плита с чайником", "Gas stove with a kettle", gas_stove(True), color="WHITE", sound="METAL")
    for colour, ru in (("white", "белая"), ("wood", "под дерево")):
        Furn(f"apt_kitchen_base_{colour}", f"Кухонная тумба ({ru})", f"Kitchen base unit ({colour})", kitchen_base(colour),
             color="WHITE" if colour == "white" else "OAK_TAN")
        Furn(f"apt_kitchen_jars_{colour}", f"Тумба с банками и хлебницей ({ru})", f"Kitchen unit with jars ({colour})",
             kitchen_base(colour, "jars"), color="WHITE" if colour == "white" else "OAK_TAN")
        Furn(f"apt_kitchen_sink_{colour}", f"Мойка ({ru})", f"Kitchen sink ({colour})", kitchen_sink(colour),
             color="WHITE" if colour == "white" else "OAK_TAN", sound="METAL")
        Furn(f"apt_wall_cupboard_{colour}", f"Навесной шкафчик ({ru})", f"Wall cupboard ({colour})", wall_cupboard(colour),
             mount="WALL", color="WHITE" if colour == "white" else "OAK_TAN")
        Furn(f"apt_dish_rack_{colour}", f"Шкафчик-сушилка ({ru})", f"Dish rack cupboard ({colour})", wall_cupboard(colour, True),
             mount="WALL", color="WHITE" if colour == "white" else "OAK_TAN")
    Furn("apt_kitchen_table", "Кухонный стол с клеёнкой", "Kitchen table", kitchen_table(), color="RED")
    # bedroom
    Furn("apt_bed", "Кровать", "Bed", bed_single("wood"), kind="bed", color="BROWN", sound="WOOL")
    Furn("apt_bed_iron", "Железная кровать с подушками", "Iron bed with pillows", bed_single("iron"), kind="bed", color="WHITE",
         sound="WOOL", cutout=True)
    Furn("apt_wardrobe", "Шифоньер", "Wardrobe", wardrobe(), color="BROWN")
    # bathroom
    Furn("apt_bathtub", "Ванна", "Bathtub", bathtub(), color="WHITE", sound="METAL")
    Furn("apt_toilet", "Унитаз", "Toilet", toilet(), seat=8, color="WHITE", sound="STONE")
    Furn("apt_washbasin", "Раковина с зеркалом", "Washbasin with a mirror", washbasin(), color="WHITE", sound="STONE")
    Furn("apt_washer", "Стиральная машина «Вятка»", "Vyatka washing machine", vyatka(), color="WHITE", sound="METAL")
    Furn("apt_towel_rail", "Полотенцесушитель", "Towel rail", towel_rail(), mount="WALL", color="WHITE", sound="METAL")
    # lights
    Furn("apt_chandelier", "Люстра", "Chandelier", chandelier, mount="CEILING", toggle=True, light=15, color="GOLD", sound="GLASS", solid=False)
    Furn("apt_ceiling_plate", "Плафон-тарелка", "Ceiling light", ceiling_plate, mount="CEILING", toggle=True, light=14, color="WHITE",
         sound="GLASS", solid=False)
    Furn("apt_floor_lamp", "Торшер", "Floor lamp", floor_lamp, toggle=True, light=13, color="YELLOW", sound="WOOL")
    Furn("apt_sconce", "Бра", "Wall lamp", sconce, mount="WALL", toggle=True, light=12, color="WHITE", sound="GLASS")
    Furn("apt_radiator", "Чугунная батарея", "Cast-iron radiator", radiator(), mount="WALL", color="WHITE", sound="METAL")
    for key in CURTAINS:
        curtains(key)
    import fur_more
    fur_more.make()


def plate_tex():
    cv = P.Canvas(32, 32, rgb("f4f2ec"))
    cv.disc(16, 16, 15, rgb("f4f2ec"), ring=rgb("2a5aa8"))
    cv.disc(16, 16, 9, rgb("ecead8"), ring=rgb("c8c4b0"))
    return cv
