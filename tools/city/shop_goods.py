"""The supermarket's own goods and its carts: 16x16 pixel-art sprites of Russian everyday groceries (a loaf, kefir, pelmeni,
condensed milk...), the shopping cart (a 3D item model) and the cart corral by the entrance, the pelmeni cooking recipes and the
names. The items are registered in io.github.mobtalk.game.ShopFoods / ShopContent, priced in io.github.mobtalk.core.ShopCatalog.

    python3 -I tools/city/shop_goods.py                 # writes the assets and data
    python3 -I tools/city/shop_goods.py preview OUT.png # also a sheet of every sprite, eight times larger

Sprites are drawn in code: organic food as lit shapes (a light from the upper left, four tones and a dark rim, as Minecraft's own
food), packaging in 3/4 view as a box or a can with its label. Run by tools/city/shop.py.
"""
import json
import math
import os
import sys

from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import decor  # noqa: E402
from decor import box, mix, rng, shade  # noqa: E402

ASSETS = decor.ASSETS
DATA = decor.DATA
LIGHT = (-0.62, -0.78)  # from the upper left


class Sprite:
    def __init__(self):
        self.img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
        self.px = self.img.load()

    def put(self, x, y, c):
        if 0 <= x < 16 and 0 <= y < 16 and c is not None:
            self.px[x, y] = c if len(c) == 4 else tuple(c) + (255,)

    def get(self, x, y):
        return self.px[x, y] if 0 <= x < 16 and 0 <= y < 16 else (0, 0, 0, 0)

    def lit(self, inside, ramp, rim=None, shine=True):
        """Fills a shape: inside(x, y) gives None or its surface normal (nx, ny) at the pixel's centre; four tones from the light,
        the shape's edge one tone darker than its darkest (a rim), a highlight where it faces the light squarely."""
        cells = {}
        for y in range(16):
            for x in range(16):
                n = inside(x + 0.5, y + 0.5)
                if n is not None:
                    cells[(x, y)] = n
        rim = rim or shade(ramp[0], 0.62)
        for (x, y), (nx, ny) in cells.items():
            edge = any((x + dx, y + dy) not in cells for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)))
            if edge:
                self.put(x, y, rim)
                continue
            t = nx * LIGHT[0] + ny * LIGHT[1]  # -1 facing away .. 1 facing the light
            k = max(0, min(len(ramp) - 1, int((t * 0.5 + 0.5) * len(ramp) * 0.999 + 0.35)))
            if not shine and k == len(ramp) - 1:
                k -= 1
            self.put(x, y, ramp[k])
        return set(cells)

    def line(self, x0, y0, x1, y1, c):
        n = max(abs(x1 - x0), abs(y1 - y0), 1)
        for i in range(n + 1):
            self.put(round(x0 + (x1 - x0) * i / n), round(y0 + (y1 - y0) * i / n), c)

    def rect(self, x0, y0, x1, y1, c):
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                self.put(x, y, c)

    def outline(self, c):
        """A dark line around everything drawn (outside it), for packaging drawn by hand."""
        add = []
        for y in range(16):
            for x in range(16):
                if self.get(x, y)[3] == 0 and any(self.get(x + dx, y + dy)[3] for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                    add.append((x, y))
        for x, y in add:
            self.put(x, y, c)


def ellipse(cx, cy, rx, ry, angle=0.0, bulge=1.0):
    ca, sa = math.cos(math.radians(angle)), math.sin(math.radians(angle))

    def inside(x, y):
        dx, dy = x - cx, y - cy
        u, v = dx * ca + dy * sa, -dx * sa + dy * ca
        d = (u / rx) ** 2 + (v / ry) ** 2
        if d > 1:
            return None
        nu, nv = u / rx, v / ry
        r = math.sqrt(nu * nu + nv * nv) or 1
        # a dome: the middle faces the viewer, the rim faces outwards
        k = min(1, r) ** bulge
        return ((nu / r * ca - nv / r * sa) * k, (nu / r * sa + nv / r * ca) * k)
    return inside


def ring(cx, cy, rx, ry, hole):
    outer = ellipse(cx, cy, rx, ry)

    def inside(x, y):
        n = outer(x, y)
        if n is None:
            return None
        u, v = (x - cx) / rx, (y - cy) / ry
        r = math.sqrt(u * u + v * v)
        if r < hole:
            return None
        mid = (1 + hole) / 2
        k = (r - mid) / (1 - mid)  # -1 at the hole's edge .. 1 at the rim: a tube
        return (u / (r or 1) * k, v / (r or 1) * k)
    return inside


def union(*shapes):
    def inside(x, y):
        for s in shapes:
            n = s(x, y)
            if n is not None:
                return n
        return None
    return inside


# ---------------------------------------------------------------- the goods

GOLD = [(150, 84, 30), (190, 118, 46), (222, 156, 70), (244, 196, 116)]


def baton():
    s = Sprite()
    s.lit(ellipse(8, 8.4, 7.3, 3.4, -40, 0.6), GOLD)
    ax, ay = math.cos(math.radians(-40)), math.sin(math.radians(-40))
    for t in (-4.2, -1.4, 1.4, 4.2):  # the slashes across the top
        cx, cy = 8 + ax * t - 0.9 * ay * -1, 8.4 + ay * t - 0.9 * ax
        for k in (-1, 0, 1):
            x, y = round(cx + k * 0.8 + ax * k * 0.4), round(cy + k * 0.8 - 0.2)
            s.put(x, y, (250, 226, 168))
            s.put(x + 1, y + 1, (168, 98, 36))
    return s


def borodinsky():
    s = Sprite()
    dark = [(48, 26, 16), (74, 42, 24), (98, 58, 34), (122, 76, 46)]

    def loaf(x, y):
        if not (1.5 <= x <= 14.5 and 3.5 <= y <= 13.5):
            return None
        top = 3.5 + 2.6 * ((x - 8) / 6.5) ** 2  # an arched crust
        if y < top:
            return None
        nx = (x - 8) / 6
        ny = -1 if y < top + 1.4 else (0.9 if y > 11.8 else 0.1)
        return (nx * 0.8, ny)
    cells = s.lit(loaf, dark)
    r = rng("borodinsky")
    for (x, y) in sorted(cells):  # coriander on the crust
        if 4 <= y <= 7 and 3 <= x <= 13 and r.random() < 0.3 and s.get(x, y)[:3] != shade(dark[0], 0.62)[:3]:
            s.put(x, y, (176, 140, 80))
    return s


def sushki():
    s = Sprite()
    ramp = [(150, 92, 40), (196, 132, 62), (226, 168, 92), (246, 206, 140)]
    for cx, cy in ((10.6, 5), (5, 8.2), (10.4, 11.4)):
        cells = s.lit(ring(cx, cy, 4.6, 3.8, 0.4), ramp, rim=(122, 70, 28))
        r = rng("sushki%s" % cx)
        for (x, y) in cells:  # poppy seeds on the top of each ring
            if y < cy - 1.5 and r.random() < 0.12:
                s.put(x, y, (40, 36, 50))
    return s


def pryanik():
    s = Sprite()
    s.lit(ellipse(8, 8.5, 6.6, 5.6, 0, 0.5), [(96, 50, 24), (138, 78, 38), (170, 106, 54), (196, 134, 74)])
    glaze = (250, 244, 228)
    for a in range(0, 360, 24):  # a dotted ring of icing
        s.put(round(8 + 4.2 * math.cos(math.radians(a))), round(8.5 + 3.4 * math.sin(math.radians(a))), glaze)
    for x, y in ((8, 7), (7, 8), (9, 8), (8, 9), (8, 8)):
        s.put(x, y, glaze)
    s.put(7, 7, (226, 120, 120))
    s.put(9, 9, (226, 120, 120))
    return s


def carton(colour, band, label):
    """A milk/kefir carton in 3/4 view: white front, shaded side, a gable top with its seal."""
    s = Sprite()
    white, side, roof, roof_side = (244, 244, 240), (196, 200, 204), (226, 228, 230), (170, 176, 182)
    s.rect(4, 5, 10, 14, white)
    s.rect(11, 4, 12, 13, side)
    for y in range(1, 5):  # the gable: front slope and side
        w = 4 - y if y < 4 else 0
        s.rect(4 + max(0, 3 - y), 5 - y + 0, 10 - max(0, 3 - y), 5 - y, roof)
    s.rect(5, 2, 9, 4, roof)
    s.rect(6, 1, 8, 1, roof_side)
    s.rect(11, 2, 12, 3, roof_side)
    s.rect(7, 0, 8, 0, (210, 210, 214))
    s.rect(4, 8, 10, 12, colour)
    s.rect(11, 7, 12, 11, shade(colour, 0.78))
    s.rect(4, 8, 10, 8, band)
    s.rect(11, 7, 12, 7, shade(band, 0.78))
    for x, y in label:
        s.put(x, y, white)
    s.outline((54, 58, 66))
    return s


def milk_carton():
    # blue carton, a white cow's head
    return carton((60, 110, 200), (230, 60, 60), ((6, 9), (7, 9), (8, 9), (6, 10), (7, 10), (8, 10), (7, 11)))


def kefir():
    return carton((70, 160, 80), (250, 250, 250), ((5, 10), (6, 10), (7, 10), (8, 10), (9, 10)))


def cylinder(s, x0, x1, top, bottom, ramp, cap=None, cap_ramp=None):
    """A tin or a tub in 3/4 view: shaded columns (lit on the left), an oval top, a rounded bottom."""
    w = x1 - x0
    for x in range(x0, x1 + 1):
        t = (x - x0) / max(1, w)
        c = ramp[3] if t < 0.2 else ramp[2] if t < 0.5 else ramp[1] if t < 0.8 else ramp[0]
        for y in range(top + 1, bottom + 1):
            s.put(x, y, c)
    s.put(x0, bottom, None if False else ramp[1])
    if cap:
        s.rect(x0, top - 1, x1, top, cap)
        s.rect(x0 + 1, top - 2, x1 - 1, top - 2, cap_ramp or shade(cap, 1.15))


def sgushchenka():
    """A tin of condensed milk: blue and white label, «МОЛОКО» stripes."""
    s = Sprite()
    tin = [(110, 116, 124), (160, 166, 172), (204, 208, 212), (236, 238, 240)]
    cylinder(s, 3, 12, 4, 13, tin, (190, 194, 200), (226, 228, 232))
    blue = [(30, 60, 140), (44, 84, 178), (60, 110, 210), (90, 140, 230)]
    for x in range(3, 13):
        t = (x - 3) / 9
        c = blue[3] if t < 0.2 else blue[2] if t < 0.5 else blue[1] if t < 0.8 else blue[0]
        for y in (6, 7, 11, 12):
            s.put(x, y, c)
        for y in (8, 9, 10):
            s.put(x, y, (246, 246, 242) if t < 0.8 else (206, 206, 202))
    for x in (5, 7, 9):  # the word as blue dashes on the white band
        s.put(x, 9, (44, 84, 178))
        s.put(x + 1, 9, (44, 84, 178))
    s.outline((46, 50, 58))
    return s


def smetana():
    """A tub of sour cream: white cup, a red foil lid seen from above."""
    s = Sprite()
    cup = [(176, 180, 186), (214, 216, 220), (238, 238, 236), (250, 250, 248)]
    for y in range(7, 15):  # a cup narrowing to the bottom
        inset = (y - 7) // 4
        for x in range(3 + inset, 13 - inset):
            t = (x - 3) / 9
            s.put(x, y, cup[3] if t < 0.25 else cup[2] if t < 0.55 else cup[1] if t < 0.85 else cup[0])
    s.lit(ellipse(8, 6, 5.6, 2.6, 0, 3), [(150, 20, 24), (196, 32, 36), (226, 58, 60), (246, 120, 116)])
    s.rect(4, 10, 11, 11, (226, 58, 60))
    s.put(7, 10, (250, 250, 248))
    s.put(8, 10, (250, 250, 248))
    s.outline((60, 50, 52))
    return s


def tvorog():
    """Cottage cheese in a paper brick: white and blue wrapper, a pat of it showing."""
    s = Sprite()
    s.rect(2, 7, 11, 13, (240, 240, 236))
    s.rect(12, 6, 13, 12, (196, 198, 202))
    for i in range(2):
        s.rect(3 + i, 6 - i, 12 + i, 6 - i, (220, 222, 224))
    s.rect(2, 9, 11, 11, (52, 96, 186))
    s.rect(12, 8, 13, 10, (40, 76, 150))
    s.rect(4, 10, 9, 10, (246, 246, 242))
    s.outline((50, 56, 70))
    return s


def cheese():
    """A wedge of cheese with holes, light from the left."""
    s = Sprite()
    top, front, side = (250, 220, 96), (236, 192, 62), (206, 160, 44)
    for y in range(4, 9):  # the top slope
        for x in range(2 + (8 - y), 14):
            s.put(x, y, top)
    for y in range(9, 14):
        for x in range(2, 11):
            s.put(x, y, front)
        for x in range(11, 14):
            s.put(x, y, side)
    s.rect(2, 13, 13, 13, (200, 150, 40))
    for x, y in ((5, 10), (8, 12), (9, 10), (4, 12), (12, 11), (9, 6), (12, 5)):
        s.put(x, y, (190, 140, 36))
        s.put(x + 1, y, shade((190, 140, 36), 1.2))
    s.rect(2, 9, 13, 9, (252, 236, 150))
    s.outline((120, 84, 20))
    return s


def doktorskaya():
    """Boiled sausage: a fat pink stick in its casing, a slice cut off at the front."""
    s = Sprite()
    pink = [(170, 84, 92), (206, 116, 122), (230, 150, 152), (246, 184, 182)]
    s.lit(ellipse(7, 7.5, 6.4, 3.6, -35, 0.5), pink)
    s.line(12, 2, 13, 1, (220, 200, 150))  # the tied end
    s.put(12, 3, (120, 60, 64))
    flat = (238, 170, 168)
    s.lit(ellipse(11, 11.5, 3.6, 3.4, 0, 9), [flat, flat, flat, flat], rim=(150, 70, 78))
    for x, y in ((10, 10), (12, 12), (11, 13), (12, 10), (9, 12)):
        s.put(x, y, (252, 236, 232))
    return s


def sosiski():
    s = Sprite()
    ramp = [(150, 70, 70), (196, 102, 96), (222, 134, 124), (240, 170, 158)]
    for cy, cx in ((5, 8.5), (8.6, 7.5), (12.2, 8.5)):
        s.lit(ellipse(cx, cy, 6.8, 2.3, -10, 0.6), ramp)
    return s


def pelmeni(cooked):
    s = Sprite()
    if not cooked:
        # a frozen pack: blue bag, a white window with dumplings, a frosty corner
        s.rect(2, 3, 13, 14, (44, 90, 176))
        s.rect(2, 2, 13, 2, (210, 220, 236))
        for x in range(2, 14, 2):
            s.put(x, 1, (210, 220, 236))
        s.rect(4, 7, 11, 12, (238, 242, 246))
        ramp = [(196, 198, 190), (226, 226, 214), (244, 242, 230), (252, 252, 246)]
        s.lit(ellipse(6, 10, 2.2, 1.6), ramp)
        s.lit(ellipse(9.5, 9.4, 2.2, 1.6), ramp)
        s.rect(4, 4, 11, 5, (232, 60, 52))
        for x, y in ((12, 13), (13, 12), (13, 13), (3, 3)):
            s.put(x, y, (220, 236, 250))
        s.outline((26, 40, 80))
        return s
    # a plate of boiled pelmeni with sour cream and dill
    s.lit(ellipse(8, 10, 7.4, 4.6, 0, 6), [(120, 130, 150), (60, 100, 170), (60, 100, 170), (90, 130, 200)], rim=(40, 60, 110))
    ramp = [(170, 146, 100), (214, 196, 150), (238, 228, 196), (252, 248, 232)]
    for cx, cy in ((5, 8.2), (10.4, 7.6), (6.6, 11.4), (11, 11.2)):
        s.lit(ellipse(cx, cy, 2.7, 1.9), ramp, rim=(186, 160, 112))
    s.rect(8, 9, 9, 10, (252, 252, 248))
    s.put(8, 8, (252, 252, 248))
    for x, y in ((6, 8), (10, 7), (8, 12), (12, 9)):
        s.put(x, y, (70, 150, 60))
    return s


def plombir():
    """A waffle cup of plombir."""
    s = Sprite()
    waffle = [(150, 96, 40), (196, 136, 64), (220, 170, 96), (236, 196, 124)]
    for y in range(8, 15):
        inset = (y - 8) // 3
        for x in range(4 + inset, 12 - inset):
            c = waffle[2] if (x + y) % 3 else waffle[0]
            s.put(x, y, c if x < 10 - inset else shade(c, 0.85))
    s.rect(3, 7, 12, 8, waffle[3])
    s.lit(union(ellipse(8, 5.4, 4.6, 3.4), ellipse(8, 7, 5.2, 1.6)), [(206, 210, 214), (232, 234, 236), (246, 246, 246), (255, 255, 252)])
    s.put(9, 2, (120, 60, 40))  # a crumb of chocolate on top
    s.outline((86, 58, 30))
    return s


def chocolate():
    """A bar of chocolate, the wrapper torn back: brown squares and a red and gold wrapper."""
    s = Sprite()
    for y in range(4, 13):  # the bar, at a slant
        for x in range(1 + (12 - y) // 3, 13 + (12 - y) // 3):
            s.put(x, y, (110, 62, 36))
    for y in range(4, 13):
        for x in range(1 + (12 - y) // 3, 13 + (12 - y) // 3):
            if (y - 4) % 3 == 0 or (x - (12 - y) // 3) % 3 == 0:
                s.put(x, y, (78, 40, 22))
            elif (y - 4) % 3 == 1 and (x - (12 - y) // 3) % 3 == 1:
                s.put(x, y, (146, 90, 56))
    for y in range(7, 14):  # the wrapper over the right part
        for x in range(6 + (12 - y) // 3, 15 + (12 - y) // 3):
            s.put(x, y, (200, 34, 40))
    for y in range(7, 14):
        x = 6 + (12 - y) // 3
        s.put(x, y, (232, 196, 90))
    s.rect(9, 10, 12, 10, (246, 230, 200))
    s.outline((50, 26, 18))
    return s


def semechki():
    """A paper cone of sunflower seeds."""
    s = Sprite()
    paper = [(150, 128, 92), (196, 172, 128), (222, 202, 160), (240, 226, 192)]
    for y in range(6, 15):
        half = max(0, 6 - (y - 6) * 6 // 9)
        for x in range(8 - half, 8 + half + 1):
            t = (x - (8 - half)) / max(1, 2 * half)
            s.put(x, y, paper[3] if t < 0.25 else paper[2] if t < 0.6 else paper[1])
    s.line(5, 7, 9, 12, paper[0])
    r = rng("semechki")
    for y in range(2, 7):  # seeds heaped out of the cone
        for x in range(3, 14):
            if (x - 8) ** 2 / 30 + (y - 6) ** 2 / 14 <= 1 and r.random() < 0.85:
                s.put(x, y, (34, 30, 30) if (x + y) % 3 else (226, 222, 210))
    s.outline((60, 46, 30))
    return s


def pickles():
    """A three-litre jar of pickled cucumbers: green through the glass, dill, a gold lid."""
    s = Sprite()
    s.rect(3, 4, 12, 14, (168, 196, 150))
    s.rect(4, 3, 11, 3, (168, 196, 150))
    gherkin = [(40, 90, 40), (60, 120, 50), (90, 150, 60)]
    for cx in (5, 8, 11):
        for y in range(5, 14):
            for dx in (-1, 0):
                s.put(cx + dx, y, gherkin[(y + dx) % 3])
    for x, y in ((6, 6), (7, 9), (10, 11), (4, 12), (9, 5)):  # dill and garlic
        s.put(x, y, (170, 200, 110) if (x + y) % 2 else (240, 236, 216))
    s.rect(3, 5, 3, 13, (226, 240, 222))  # the glass's shine
    s.rect(4, 1, 11, 2, (214, 170, 60))
    s.rect(5, 1, 10, 1, (240, 206, 100))
    s.outline((50, 70, 50))
    return s


def kvass():
    """A plastic bottle of kvass: brown, a cream label, a gold cap."""
    s = Sprite()
    brown = [(70, 30, 10), (110, 52, 20), (150, 78, 30), (196, 120, 60)]
    for y in range(5, 15):
        for x in range(5, 11):
            t = (x - 5) / 5
            s.put(x, y, brown[3] if t < 0.2 else brown[2] if t < 0.5 else brown[1] if t < 0.85 else brown[0])
    for y, (a, b) in ((4, (6, 9)), (3, (7, 8)), (2, (7, 8))):
        for x in range(a, b + 1):
            s.put(x, y, brown[2] if x < 8 else brown[1])
    s.rect(7, 1, 8, 1, (220, 180, 60))
    s.rect(5, 8, 10, 11, (238, 222, 180))
    s.rect(5, 9, 10, 9, (190, 40, 30))
    s.rect(5, 14, 10, 14, brown[0])
    s.outline((40, 20, 10))
    return s


def noodles():
    """A cup of instant noodles: a red paper cup, the lid peeled back over curly noodles."""
    s = Sprite()
    red = [(140, 20, 20), (186, 34, 34), (220, 60, 50), (240, 110, 90)]
    for y in range(7, 15):
        inset = (y - 7) // 4
        for x in range(3 + inset, 13 - inset):
            t = (x - 3) / 9
            s.put(x, y, red[3] if t < 0.2 else red[2] if t < 0.5 else red[1] if t < 0.85 else red[0])
    s.rect(4, 10, 11, 11, (250, 220, 70))
    s.lit(ellipse(8, 6.4, 5.4, 1.8), [(200, 156, 70), (226, 190, 100), (240, 212, 130), (250, 230, 160)], shine=False)
    for x in range(4, 12, 2):
        s.put(x, 6, (180, 130, 50))
    s.rect(9, 2, 13, 4, (236, 236, 230))  # the peeled lid
    s.rect(9, 5, 10, 5, (236, 236, 230))
    s.outline((70, 16, 16))
    return s


def cucumber():
    s = Sprite()
    s.lit(ellipse(8, 8, 7, 2.8, -42, 0.5), [(30, 74, 30), (50, 110, 44), (78, 146, 58), (130, 186, 90)])
    r = rng("cucumber")
    for i in range(8):  # pimples
        t = -5 + i * 1.4
        x, y = round(8 + t * 0.743 + r.uniform(-1, 1)), round(8 - t * 0.669 + r.uniform(-1, 1))
        if s.get(x, y)[3]:
            s.put(x, y, (170, 210, 120))
    s.put(13, 2, (240, 200, 60))  # the dried flower
    return s


def tomato():
    s = Sprite()
    s.lit(ellipse(8, 9, 6.4, 5.6, 0, 0.6), [(140, 20, 16), (196, 38, 28), (230, 66, 46), (250, 140, 110)])
    for x, y in ((8, 3), (8, 4), (6, 4), (10, 4), (5, 5), (11, 5), (7, 5), (9, 5)):
        s.put(x, y, (60, 120, 40) if y > 3 else (40, 90, 30))
    return s


def cabbage():
    s = Sprite()
    s.lit(ellipse(8, 8.6, 6.8, 6.2, 0, 0.4), [(70, 120, 50), (110, 160, 74), (150, 196, 104), (196, 226, 150)])
    for a in (-60, -20, 20, 60, 100, 140):  # the leaves' veins from the stalk
        for k in range(2, 6):
            x = round(8 + k * math.cos(math.radians(a + 90)))
            y = round(8.6 + k * math.sin(math.radians(a + 90)) * 0.9)
            if s.get(x, y)[3]:
                s.put(x, y, (214, 236, 180))
    s.put(8, 9, (230, 240, 200))
    return s


def onion():
    s = Sprite()
    s.lit(union(ellipse(8, 9.5, 5.4, 5, 0, 0.5), ellipse(8, 5, 1.6, 2.4)), [(130, 70, 20), (184, 108, 40), (218, 150, 66), (240, 196, 120)])
    for y in range(6, 14):  # the papery stripes
        s.put(6 + (y - 6) // 5, y, (150, 84, 26))
        s.put(10 - (y - 6) // 5, y, (150, 84, 26))
    s.rect(7, 1, 8, 2, (120, 150, 60))
    s.rect(7, 14, 9, 14, (220, 200, 160))
    return s


GOODS = {
    "baton": baton, "borodinsky": borodinsky, "sushki": sushki, "pryanik": pryanik,
    "milk_carton": milk_carton, "kefir": kefir, "smetana": smetana, "tvorog": tvorog, "cheese": cheese,
    "doktorskaya": doktorskaya, "sosiski": sosiski, "pelmeni": lambda: pelmeni(False), "cooked_pelmeni": lambda: pelmeni(True),
    "plombir": plombir, "sgushchenka": sgushchenka, "chocolate": chocolate, "semechki": semechki, "pickles": pickles,
    "kvass": kvass, "noodles": noodles, "cucumber": cucumber, "tomato": tomato, "cabbage": cabbage, "onion": onion,
}

NAMES = {
    "baton": ("Батон", "White loaf"),
    "borodinsky": ("Бородинский хлеб", "Borodinsky rye bread"),
    "sushki": ("Сушки с маком", "Poppy-seed sushki"),
    "pryanik": ("Тульский пряник", "Tula gingerbread"),
    "milk_carton": ("Молоко в пакете", "Carton of milk"),
    "kefir": ("Кефир", "Kefir"),
    "smetana": ("Сметана", "Sour cream"),
    "tvorog": ("Творог", "Cottage cheese"),
    "cheese": ("Сыр", "Cheese"),
    "doktorskaya": ("Докторская колбаса", "Doktorskaya sausage"),
    "sosiski": ("Сосиски", "Frankfurters"),
    "pelmeni": ("Пельмени (замороженные)", "Frozen pelmeni"),
    "cooked_pelmeni": ("Пельмени со сметаной", "Pelmeni with sour cream"),
    "plombir": ("Пломбир в стаканчике", "Plombir in a waffle cup"),
    "sgushchenka": ("Сгущёнка", "Condensed milk"),
    "chocolate": ("Шоколад", "Chocolate bar"),
    "semechki": ("Семечки", "Sunflower seeds"),
    "pickles": ("Солёные огурцы", "Pickled cucumbers"),
    "kvass": ("Квас", "Kvass"),
    "noodles": ("Лапша быстрого приготовления", "Instant noodles"),
    "cucumber": ("Огурец", "Cucumber"),
    "tomato": ("Помидор", "Tomato"),
    "cabbage": ("Капуста", "Cabbage"),
    "onion": ("Лук", "Onion"),
    "shopping_cart": ("Тележка покупателя", "Shopping cart"),
    "cart_corral": ("Стоянка тележек", "Cart corral"),
}

TEXTS = {
    "mobtalk.shop.cart.took": ("Взяли тележку", "Took a cart"),
    "mobtalk.shop.cart.back": ("Вернули тележку", "Put the cart back"),
}


# ---------------------------------------------------------------- the cart

def cart_textures():
    """The cart's wire mesh (see-through, cutout), its red plastic handle and bumper, black wheels."""
    mesh = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = mesh.load()
    for y in range(16):
        for x in range(16):
            if x % 3 == 0 or y % 3 == 0:
                px[x, y] = (196, 202, 210, 255) if (x + y) % 2 else (168, 176, 186, 255)
    for i in range(16):  # the frame round the edge
        for p in ((i, 0), (i, 15), (0, i), (15, i)):
            px[p] = (220, 224, 230, 255)
    red = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    decor.noise_fill(red, (204, 34, 40, 255), 0.04, rng("cart_red"))
    for x in range(16):
        red.putpixel((x, 0), (236, 80, 84, 255))
    wheel = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    decor.noise_fill(wheel, (40, 40, 44, 255), 0.06, rng("cart_wheel"))
    for x, y in ((7, 7), (8, 7), (7, 8), (8, 8)):
        wheel.putpixel((x, y), (150, 150, 156, 255))
    frame = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    decor.noise_fill(frame, (186, 192, 200, 255), 0.05, rng("cart_frame"))
    return {"shop_cart_mesh": mesh, "shop_cart_red": red, "shop_cart_wheel": wheel, "shop_cart_frame": frame}


def cart_elements(dx=0.0, dy=0.0, dz=0.0, wheels=True):
    """One cart, the basket's open end towards north (the handle south), 16 long, about 13 high."""
    def b(f, t, tex, faces=decor.FACES):
        return box((f[0] + dx, f[1] + dy, f[2] + dz), (t[0] + dx, t[1] + dy, t[2] + dz), tex, faces)
    els = []
    # the basket: mesh walls, floor, the child seat folded against the back
    els.append(b((3, 5, 1), (13, 5.2, 13), "mesh", ["up", "down"]))
    els.append(b((3, 5, 1), (3.1, 12, 13), "mesh", ["east", "west"]))
    els.append(b((12.9, 5, 1), (13, 12, 13), "mesh", ["east", "west"]))
    els.append(b((3, 5, 1), (13, 12, 1.1), "mesh", ["north", "south"]))
    els.append(b((3, 5, 12.9), (13, 12, 13), "mesh", ["north", "south"]))
    for f, t in (((2.8, 11.8, 0.8), (13.2, 12.4, 1.4)), ((2.8, 11.8, 12.6), (13.2, 12.4, 13.2)),
                 ((2.8, 11.8, 1.4), (3.4, 12.4, 12.6)), ((12.6, 11.8, 1.4), (13.2, 12.4, 12.6))):  # the top rim
        els.append(b(f, t, "frame"))
    # the chassis: two side rails down to the wheels
    for x in (3.5, 11.5):
        els.append(b((x, 1.4, 2), (x + 1, 2, 14), "frame"))
        els.append(b((x, 2, 12), (x + 1, 5, 13), "frame"))
        els.append(b((x, 2, 2), (x + 1, 5, 3), "frame"))
    els.append(b((3.5, 1.6, 6), (12.5, 2, 12), "mesh", ["up", "down"]))  # the bottom tray
    # the handle with its red grip
    for x in (3.4, 12.1):
        els.append(b((x, 11, 13), (x + 0.5, 14, 15), "frame"))
    els.append(b((3.2, 13.6, 14.6), (12.8, 14.6, 15.6), "red"))
    els.append(b((2.8, 5, 0.4), (13.2, 6, 1), "red"))  # the bumper
    if wheels:
        for x in (3.3, 11.7):
            for z in (2.2, 12.2):
                els.append(b((x, 0, z), (x + 1, 1.6, z + 1.6), "wheel"))
    return els


def cart_model():
    tex = {k: f"mobtalk:block/shop/shop_cart_{v}" for k, v in (("mesh", "mesh"), ("red", "red"), ("wheel", "wheel"), ("frame", "frame"))}
    tex["particle"] = tex["red"]
    display = {"gui": {"rotation": [25, 135, 0], "translation": [0, 0.5, 0], "scale": [0.66, 0.66, 0.66]},
               "ground": {"rotation": [0, 0, 0], "translation": [0, 2, 0], "scale": [0.4, 0.4, 0.4]},
               "fixed": {"rotation": [0, 180, 0], "translation": [0, 0, 0], "scale": [0.6, 0.6, 0.6]},
               # in third person the cart is pushed along the ground (io.github.mobtalk.client.game.ShopCartRenderer), not held
               "thirdperson_righthand": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [0, 0, 0]},
               "thirdperson_lefthand": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [0, 0, 0]},
               # in first person the handle is at the bottom of the screen, the basket ahead
               "firstperson_righthand": {"rotation": [10, 195, 0], "translation": [-2, -2, 0], "scale": [0.8, 0.8, 0.8]},
               "firstperson_lefthand": {"rotation": [10, 165, 0], "translation": [2, -2, 0], "scale": [0.8, 0.8, 0.8]}}
    return {"parent": "minecraft:block/block", "textures": tex, "elements": cart_elements(), "display": display}


def corral_model():
    """Three carts nested into one another by a rail: the cart corral."""
    tex = {k: f"mobtalk:block/shop/shop_cart_{k}" for k in ("mesh", "red", "wheel", "frame")}
    tex["particle"] = tex["red"]
    els = []
    for i, dz in enumerate((-1.5, 0.5, 2.5)):
        els += cart_elements(0, i * 0.35, dz, wheels=(i == 2))
    els = [e for e in els if all(-16 <= v <= 32 for v in e["from"] + e["to"])]
    els.append(box((1, 0, 0), (1.6, 10, 16), "frame"))  # the corral's rail
    els.append(box((1, 9.4, 0), (2.4, 10, 16), "red"))
    return {"parent": "minecraft:block/block", "textures": tex, "elements": els, "ambientocclusion": False}


# ---------------------------------------------------------------- writing

def save_all():
    idir = os.path.join(ASSETS, "textures", "item")
    os.makedirs(idir, exist_ok=True)
    for name, draw in GOODS.items():
        draw().img.save(os.path.join(idir, name + ".png"))
        decor.write(os.path.join(ASSETS, "models", "item", name + ".json"), {"parent": "minecraft:item/generated", "textures": {"layer0": f"mobtalk:item/{name}"}})
    tdir = os.path.join(ASSETS, "textures", "block", "shop")
    os.makedirs(tdir, exist_ok=True)
    for name, img in cart_textures().items():
        img.save(os.path.join(tdir, name + ".png"))
    decor.write(os.path.join(ASSETS, "models", "item", "shopping_cart.json"), cart_model())
    corral = corral_model()
    decor.write(os.path.join(ASSETS, "models", "block", "shop", "cart_corral.json"), corral)
    decor.write(os.path.join(ASSETS, "blockstates", "cart_corral.json"), {"variants": {
        f"facing={f}": ({"model": "mobtalk:block/shop/cart_corral", "y": y} if y else {"model": "mobtalk:block/shop/cart_corral"})
        for f, y in (("north", 0), ("east", 90), ("south", 180), ("west", 270))}})
    decor.write(os.path.join(ASSETS, "models", "item", "cart_corral.json"), {"parent": "mobtalk:block/shop/cart_corral", "display": decor.DISPLAY})
    decor.write(os.path.join(DATA, "loot_table", "blocks", "cart_corral.json"), {"type": "minecraft:block", "pools": [{"rolls": 1, "entries": [
        {"type": "minecraft:item", "name": "mobtalk:cart_corral"}], "conditions": [{"condition": "minecraft:survives_explosion"}]}]})
    for kind, time in (("smelting", 200), ("smoking", 100), ("campfire_cooking", 600)):
        decor.write(os.path.join(DATA, "recipe", f"cooked_pelmeni_from_{kind}.json"), {
            "type": f"minecraft:{kind}", "category": "food", "ingredient": {"item": "mobtalk:pelmeni"},
            "result": {"id": "mobtalk:cooked_pelmeni"}, "experience": 0.35, "cookingtime": time})
    lang()


def lang():
    path = os.path.join(ASSETS, "lang")
    for i, code in enumerate(("ru_ru", "en_us")):
        f = os.path.join(path, code + ".json")
        data = json.load(open(f, encoding="utf-8"))
        for name, pair in NAMES.items():
            data[("block" if name == "cart_corral" else "item") + ".mobtalk." + name] = pair[i]
        for key, pair in TEXTS.items():
            data[key] = pair[i]
        decor.write(f, data)


def preview(out):
    names = list(GOODS)
    cols = 6
    cell = 16 * 8 + 8
    sheet = Image.new("RGBA", (cols * cell, ((len(names) + cols - 1) // cols) * cell), (60, 62, 70, 255))
    for i, n in enumerate(names):
        im = GOODS[n]().img.resize((128, 128), Image.NEAREST)
        sheet.paste(im, ((i % cols) * cell + 4, (i // cols) * cell + 4), im)
    sheet.save(out)
    for k, v in cart_textures().items():
        decor.TEX[k] = v
    models = (cart_model(), corral_model())
    row = Image.new("RGBA", (256 * 4, 256), (30, 30, 34, 255))
    for i, m in enumerate(models):
        m = dict(m, textures={k: v.split("/")[-1] for k, v in m["textures"].items()})
        for k, yaw in enumerate((35, 215)):
            row.paste(decor.render(m, yaw, 256), (i * 512 + k * 256, 0))
    row.save(out.replace(".png", "-cart.png"))


def main():
    save_all()
    if len(sys.argv) > 2 and sys.argv[1] == "preview":
        preview(sys.argv[2])
    print("shop goods:", len(GOODS), "sprites, cart and corral")


if __name__ == "__main__":
    main()
