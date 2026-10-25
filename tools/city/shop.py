"""The city supermarket: pixel-art textures, block models, blockstates, item models and loot tables of its displays, tills and
fittings, the cashier's and the shop guard's work clothes, and the furnishing of the «Универсам» template.

    python3 -I tools/city/shop.py                    # assets + data, and furnishes the supermarket template
    python3 -I tools/city/shop.py preview OUT.png    # also renders every model from two sides for a quick look

The blocks are registered in io.github.mobtalk.game.ShopContent (ids and outlines must match); the goods on the displays are
drawn by the client's ShopDisplayRenderer at the tiers set out here. Run after tools/city/pieces.py (it is called at the end of it).
"""
import json
import os
import random
import sys

from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import decor  # noqa: E402
from decor import bevel, box, mix, new, noise_fill, rect, rng, shade  # noqa: E402

ROOT = decor.ROOT
ASSETS = decor.ASSETS
DATA = decor.DATA
TEX = {}
MODELS = {}


def tex(name, img):
    TEX[name] = img
    decor.TEX[name] = img  # for decor.render
    return img


# ---------------------------------------------------------------- textures

def paint(name, base, amount=0.04, edge=True):
    r = rng(name)
    img = new()
    noise_fill(img, base, amount, r)
    if edge:
        bevel(img, (0, 0, 16, 16), 1.1, 0.82)
    return tex(name, img)


def pegboard():
    """The gondola's back: white perforated steel, a hole every other pixel row and column."""
    r = rng("shop_pegboard")
    img = new()
    noise_fill(img, (226, 228, 230, 255), 0.03, r)
    for y in range(1, 16, 3):
        for x in range(1 + (y // 3) % 2, 16, 3):
            img.putpixel((x, y), (120, 124, 130, 255))
    return tex("shop_pegboard", img)


def price_strip():
    """The price channel on a shelf edge: white plastic, a grey groove and a red promo stripe now and then."""
    img = new()
    noise_fill(img, (246, 244, 236, 255), 0.02, rng("strip"))
    for x in range(16):
        img.putpixel((x, 0), (150, 150, 150, 255))
        img.putpixel((x, 15), (150, 150, 150, 255))
    for y in range(6, 10):
        for x in range(0, 16):
            if x % 16 < 3:
                img.putpixel((x, y), (200, 40, 40, 255))
    return tex("shop_price_strip", img)


def wood(name, base, grain=0.08):
    """Light varnished boards with dark grain lines (bread rack, produce stand)."""
    r = rng(name)
    img = new()
    noise_fill(img, base, 0.03, r)
    for y in range(16):
        if y % 4 == 3:
            for x in range(16):
                img.putpixel((x, y), shade(base, 0.72))
        else:
            for x in range(16):
                if r.random() < grain:
                    img.putpixel((x, y), shade(base, r.choice([0.85, 0.9, 1.08])))
    return tex(name, img)


def wicker():
    """A bread tray: woven straw-coloured plastic."""
    img = new()
    base = (204, 160, 92, 255)
    for y in range(16):
        for x in range(16):
            k = 1.08 if (x // 2 + y // 2) % 2 == 0 else 0.84
            img.putpixel((x, y), shade(base, k))
    return tex("shop_wicker", img)


def fridge_inner():
    img = new()
    noise_fill(img, (214, 230, 238, 255), 0.02, rng("fi"))
    for y in range(0, 16, 4):  # back-wall vents
        for x in range(2, 14):
            img.putpixel((x, y), (170, 190, 202, 255))
    return tex("shop_fridge_inner", img)


def fridge_canopy():
    """The top of the case: a white fascia with a blue band and a glowing strip."""
    img = new()
    noise_fill(img, (240, 242, 244, 255), 0.02, rng("fc"))
    rect(img, (0, 3, 16, 9), (36, 92, 168, 255))
    rect(img, (0, 12, 16, 16), (255, 250, 226, 255))
    for x in range(1, 15, 3):  # white letters' worth of dots: «МОЛОКО» from afar
        rect(img, (x, 5, x + 2, 7), (236, 240, 246, 255))
    return tex("shop_fridge_canopy", img)


def freezer_body():
    img = new()
    noise_fill(img, (238, 240, 242, 255), 0.02, rng("fb"))
    rect(img, (0, 9, 16, 12), (40, 110, 190, 255))
    for x in range(16):
        img.putpixel((x, 12), (24, 70, 140, 255))
    bevel(img, (0, 0, 16, 16), 1.06, 0.84)
    return tex("shop_freezer_body", img)


def frost():
    r = rng("frost")
    img = new()
    noise_fill(img, (206, 228, 240, 255), 0.04, r)
    for _ in range(26):
        img.putpixel((r.randrange(16), r.randrange(16)), (250, 252, 255, 255))
    return tex("shop_frost", img)


def counter_side():
    """Till counter: beige laminate with a darker plinth."""
    img = new()
    noise_fill(img, (200, 186, 160, 255), 0.03, rng("cs"))
    rect(img, (0, 13, 16, 16), (90, 84, 76, 255))
    for x in range(0, 16, 8):
        for y in range(0, 13):
            img.putpixel((x, y), (172, 158, 134, 255))
    return tex("shop_counter_side", img)


def belt():
    """The conveyor: black rubber with grooves across."""
    img = new()
    noise_fill(img, (34, 34, 36, 255), 0.05, rng("belt"))
    for y in range(0, 16, 2):
        for x in range(16):
            img.putpixel((x, y), (48, 48, 50, 255))
    return tex("shop_belt", img)


def plastic(name, base):
    return paint(name, base, 0.03)


def till_screen():
    """The customer display: green digits on black, «  0.00»."""
    img = new()
    rect(img, (0, 0, 16, 16), (30, 32, 34, 255))
    rect(img, (1, 3, 15, 13), (8, 22, 14, 255))
    digits = [(3, 6), (5, 6), (7, 6), (9, 6), (11, 6)]
    for x, y in digits:
        rect(img, (x, y, x + 1, y + 4), (90, 240, 120, 255))
        img.putpixel((x + 1, y), (90, 240, 120, 255))
        img.putpixel((x + 1, y + 3), (90, 240, 120, 255))
    return tex("shop_till_screen", img)


def keys():
    img = new()
    rect(img, (0, 0, 16, 16), (60, 62, 66, 255))
    for y in range(1, 16, 3):
        for x in range(1, 16, 3):
            rect(img, (x, y, x + 2, y + 2), (200, 202, 206, 255) if (x + y) % 4 else (230, 120, 40, 255))
    return tex("shop_keys", img)


def sco_screen():
    """Self-checkout screen: a blue page with a big green «ОПЛАТИТЬ» button and an emerald."""
    img = new()
    rect(img, (0, 0, 16, 16), (24, 26, 30, 255))
    rect(img, (1, 1, 15, 15), (32, 96, 180, 255))
    rect(img, (2, 2, 14, 4), (230, 236, 246, 255))  # header
    for y in (6, 8):
        rect(img, (2, y, 10, y + 1), (200, 214, 236, 255))  # item lines
    rect(img, (3, 11, 13, 14), (60, 190, 90, 255))  # pay button
    rect(img, (11, 6, 13, 9), (40, 200, 110, 255))  # emerald
    img.putpixel((12, 6), (170, 255, 200, 255))
    return tex("shop_sco_screen", img)


def scanner():
    img = new()
    rect(img, (0, 0, 16, 16), (50, 52, 56, 255))
    rect(img, (3, 3, 13, 13), (40, 20, 22, 255))
    for x in range(3, 13):
        img.putpixel((x, 8), (240, 40, 40, 255))
    return tex("shop_scanner", img)


def basket_red():
    """Red plastic basket wall: a grid of holes."""
    img = new()
    noise_fill(img, (200, 30, 36, 255), 0.04, rng("bk"))
    for y in range(2, 15, 3):
        for x in range(1, 16, 3):
            rect(img, (x, y, x + 2, y + 2), (110, 14, 18, 255))
    for x in range(16):
        img.putpixel((x, 0), (230, 60, 64, 255))
    return tex("shop_basket", img)


def gate_panel():
    """Anti-theft gate: light grey frame, a clear-ish dark panel and a small blue sticker."""
    img = new()
    noise_fill(img, (190, 194, 200, 255), 0.02, rng("gate"))
    rect(img, (2, 2, 14, 14), (150, 160, 170, 255))
    rect(img, (3, 3, 13, 13), (176, 190, 202, 255))
    rect(img, (6, 5, 10, 7), (40, 90, 180, 255))
    return tex("shop_gate", img)


def guard_front():
    """The guard desk's front: brown board with a white «ОХРАНА» plate."""
    img = new()
    noise_fill(img, (110, 78, 50, 255), 0.04, rng("gf"))
    rect(img, (2, 4, 14, 8), (236, 236, 232, 255))
    for x in range(3, 13, 2):  # the letters as dark dashes
        rect(img, (x, 5, x + 1, 7), (40, 40, 44, 255))
    bevel(img, (0, 0, 16, 16), 1.1, 0.8)
    return tex("shop_guard_front", img)


def cctv():
    """The guard's monitor: four grey camera views."""
    img = new()
    rect(img, (0, 0, 16, 16), (28, 28, 30, 255))
    r = rng("cctv")
    for x0, y0 in ((1, 1), (8, 1), (1, 8), (8, 8)):
        noise_fill(img, (130, 138, 132, 255), 0.18, r, (x0, y0, x0 + 7, y0 + 7))
    return tex("shop_cctv", img)


def basket_item():
    """The basket as an item: a red basket with a black handle, side on."""
    img = new()
    red, dark, hole = (206, 34, 40, 255), (130, 16, 20, 255), (90, 10, 14, 255)
    for y in range(7, 15):
        x0 = 2 + (y - 7) // 4
        for x in range(x0, 16 - x0):
            img.putpixel((x, y), red)
    for y in range(8, 14, 2):
        for x in range(4, 12, 2):
            img.putpixel((x, y), hole)
    for x in range(2, 14):
        img.putpixel((x, 7), (236, 70, 76, 255))
    for x in range(4, 12):
        img.putpixel((x, 14), dark)
    for x, y in ((4, 6), (4, 5), (5, 4), (6, 3), (7, 3), (8, 3), (9, 3), (10, 4), (11, 5), (11, 6)):
        img.putpixel((x, y), (30, 30, 32, 255))
    return tex("shop_basket_item", img)


def make_textures():
    paint("shop_metal", (232, 232, 230, 255), 0.03)
    paint("shop_metal_dark", (96, 100, 106, 255), 0.04)
    pegboard()
    price_strip()
    wood("shop_wood", (214, 178, 120, 255))
    wood("shop_crate", (178, 132, 82, 255), 0.12)
    wicker()
    fridge_inner()
    fridge_canopy()
    freezer_body()
    frost()
    counter_side()
    paint("shop_counter_top", (218, 210, 196, 255), 0.02)
    belt()
    plastic("shop_plastic", (70, 72, 78, 255))
    plastic("shop_plastic_light", (196, 198, 202, 255))
    till_screen()
    keys()
    sco_screen()
    scanner()
    basket_red()
    gate_panel()
    guard_front()
    paint("shop_desk", (122, 88, 58, 255), 0.04)
    cctv()
    basket_item()


# ---------------------------------------------------------------- models

def model(name, textures, elements, particle=None, ao=True):
    m = {"parent": "minecraft:block/block", "textures": {k: f"mobtalk:block/shop/{v}" for k, v in textures.items()}, "elements": elements}
    m["textures"]["particle"] = m["textures"][particle or next(iter(textures))]
    if not ao:
        m["ambientocclusion"] = False
    MODELS[name] = m


def gondola():
    els = [box((0, 0, 1.5), (16, 2, 14.5), "dark"),
           box((0, 2, 7.25), (16, 16, 8.75), "peg"),
           box((0, 15.5, 6.75), (16, 16, 9.25), "metal")]
    for x0, x1 in ((0, 0.75), (15.25, 16)):  # uprights at both ends of the bay
        els.append(box((x0, 0, 6.75), (x1, 16, 9.25), "metal"))
    for y in (6.4, 11.4):  # two shelves each side; the plinth is the bottom one
        els.append(box((0.75, y, 1.5), (15.25, y + 0.6, 7.25), "metal"))
        els.append(box((0.75, y, 8.75), (15.25, y + 0.6, 14.5), "metal"))
    for y in (0.4, 5.6, 10.6):  # price channels on both faces
        els.append(box((0, y, 1), (16, y + 1.4, 1.5), "strip", ["north", "up", "down", "east", "west"]))
        els.append(box((0, y, 14.5), (16, y + 1.4, 15), "strip", ["south", "up", "down", "east", "west"]))
    model("shop_shelf", {"metal": "shop_metal", "dark": "shop_metal_dark", "peg": "shop_pegboard", "strip": "shop_price_strip"}, els, "metal")


def bread_rack():
    els = [box((0, 0, 2), (1, 16, 15), "wood"), box((15, 0, 2), (16, 16, 15), "wood"),
           box((1, 0, 14), (15, 16, 15), "wood"), box((0, 15, 2), (16, 16, 15), "wood")]
    for y in (1, 6, 11):
        els.append(box((1, y, 2.5), (15, y + 0.8, 14), "tray", rotation={"origin": [8, y, 2.5], "axis": "x", "angle": 22.5}))
        els.append(box((1, y - 0.6, 2), (15, y + 0.8, 2.8), "strip", ["north", "up", "down", "east", "west"]))
    model("shop_bread_rack", {"wood": "shop_wood", "tray": "shop_wicker", "strip": "shop_price_strip"}, els, "wood")


def fridge():
    els = [box((0, 0, 1), (16, 1.5, 16), "body"), box((0, 0, 1), (1, 16, 16), "body"), box((15, 0, 1), (16, 16, 16), "body"),
           box((1, 1.5, 14.5), (15, 14, 16), "inner"), box((0, 14, 1), (16, 16, 16), "canopy"),
           box((1, 13.7, 1.2), (15, 14, 14.5), "glow", ["down"])]
    for y in (1.5, 6, 10.5):
        if y > 2:
            els.append(box((1, y - 0.5, 2), (15, y, 14.5), "body"))
        els.append(box((1, y - 1.6, 1.6), (15, y, 2), "strip", ["north", "up", "down", "east", "west"]))
    model("shop_fridge", {"body": "shop_metal", "inner": "shop_fridge_inner", "canopy": "shop_fridge_canopy", "glow": "shop_fridge_inner",
                          "strip": "shop_price_strip"}, els, "body", ao=False)


def freezer():
    els = [box((0.5, 0, 1), (15.5, 4, 15), "body", ["north", "south", "east", "west", "down"]),
           box((1.5, 3.9, 2), (14.5, 4, 14), "frost", ["up"]),
           box((0.5, 4, 1), (15.5, 10.5, 2), "body"), box((0.5, 4, 14), (15.5, 10.5, 15), "body"),
           box((0.5, 4, 2), (1.5, 10.5, 14), "body"), box((14.5, 4, 2), (15.5, 10.5, 14), "body"),
           box((7.75, 4, 2), (8.25, 8, 14), "frost"),
           box((0.3, 10.5, 0.8), (15.7, 11, 15.2), "dark", ["up", "north", "south", "east", "west"])]
    # the rim drawn as a frame round the opening
    els[-1] = box((0.3, 10.5, 0.8), (15.7, 11, 2), "dark")
    els += [box((0.3, 10.5, 14), (15.7, 11, 15.2), "dark"), box((0.3, 10.5, 2), (1.5, 11, 14), "dark"), box((14.5, 10.5, 2), (15.7, 11, 14), "dark")]
    for x0, x1, z0, z1 in ((1.5, 14.5, 2, 2.1), (1.5, 14.5, 13.9, 14), (1.5, 1.6, 2, 14), (14.4, 14.5, 2, 14)):  # frosty inner walls
        els.append(box((x0, 4, z0), (x1, 10.5, z1), "frost"))
    model("shop_freezer", {"body": "shop_freezer_body", "frost": "shop_frost", "dark": "shop_metal_dark"}, els, "body")


def produce():
    els = [box((0, 0, 0), (16, 1, 16), "dark"), box((0.5, 1, 0.5), (15.5, 4, 15.5), "crate")]
    for i in range(3):
        x0, x1 = 0.25 + i * 5.25, 5.25 + i * 5.25
        els += [box((x0, 4, 1), (x1, 5, 15), "crate"),
                box((x0, 5, 1), (x1, 7.5, 1.8), "crate"), box((x0, 5, 14.2), (x1, 12, 15), "crate"),
                box((x0, 5, 1.8), (x0 + 0.6, 7.5, 14.2), "crate"), box((x1 - 0.6, 5, 1.8), (x1, 7.5, 14.2), "crate")]
    els.append(box((0, 2, 0.5), (16, 3.4, 0.9), "strip", ["north", "up", "down", "east", "west"]))
    model("shop_produce", {"crate": "shop_crate", "dark": "shop_metal_dark", "strip": "shop_price_strip"}, els, "crate")


def till():
    els = [box((0, 0, 1), (16, 11, 15), "side"), box((0, 11, 0.5), (16, 12, 15.5), {"up": ("belt", None), "north": ("top", None),
           "south": ("top", None), "east": ("top", None), "west": ("top", None), "down": ("top", None)}),
           box((3, 12, 6), (13, 13.5, 13), "plastic"),
           box((4, 13.5, 9), (12, 14.3, 12.5), {"up": ("keys", None), "north": ("plastic", None), "south": ("plastic", None),
                                                 "east": ("plastic", None), "west": ("plastic", None)}),
           box((7.5, 13.5, 6.2), (8.5, 15, 7.2), "plastic"),
           box((5, 15, 5.5), (11, 17.5, 6.8), {"north": ("screen", None), "south": ("plastic", None), "up": ("plastic", None),
                                                "down": ("plastic", None), "east": ("plastic", None), "west": ("plastic", None)}),
           box((4.5, 12, 13), (11.5, 13, 14.5), "light")]  # the cash drawer
    for e in els:
        for f in e["faces"].values():
            if f["uv"] is None:
                f["uv"] = [0, 0, 16, 16]
    for e in els:  # proper uvs for the sided boxes
        for face, f in e["faces"].items():
            if f["uv"] == [0, 0, 16, 16]:
                f["uv"] = decor.auto_uv(face, e["from"], e["to"])
    model("cash_register", {"side": "shop_counter_side", "top": "shop_counter_top", "belt": "shop_belt", "plastic": "shop_plastic",
                            "keys": "shop_keys", "screen": "shop_till_screen", "light": "shop_plastic_light"}, els, "side")


def self_checkout():
    els = [box((2, 0, 4), (14, 9, 13), "cab"), box((1.5, 9, 3), (14.5, 10, 13.5), {"up": ("scan", None), "north": ("cab", None), "south": ("cab", None),
           "east": ("cab", None), "west": ("cab", None), "down": ("cab", None)}),
           box((7.5, 10, 11), (8.5, 13, 12), "dark"),
           box((4, 12, 10.5), (12, 18, 11.5), {"north": ("screen", None), "south": ("dark", None), "up": ("dark", None), "down": ("dark", None),
                                                "east": ("dark", None), "west": ("dark", None)},
               rotation={"origin": [8, 12, 11], "axis": "x", "angle": 22.5}),
           box((1, 0, 2), (2, 12, 14), "dark")]
    for e in els:
        for face, f in e["faces"].items():
            if f["uv"] is None:
                f["uv"] = decor.auto_uv(face, e["from"], e["to"])
    model("self_checkout", {"cab": "shop_plastic_light", "scan": "shop_scanner", "dark": "shop_plastic", "screen": "shop_sco_screen"}, els, "cab", ao=False)


def basket_stack():
    els = []
    for i in range(4):
        y = i * 2.6
        els.append(box((2 + i * 0.15, y, 3 + i * 0.15), (14 - i * 0.15, y + 2.6, 13 - i * 0.15), "red", ["north", "south", "east", "west", "down"]))
    els.append(box((2.6, 10.3, 3.6), (13.4, 10.4, 12.4), "dark", ["up"]))
    for x in (4, 11.5):  # the top basket's handles laid down
        els.append(box((x, 10.4, 3.4), (x + 0.6, 11, 12.6), "dark"))
    model("basket_stack", {"red": "shop_basket", "dark": "shop_plastic"}, els, "red")


def theft_gate():
    els = [box((6, 0, 2.5), (10, 1, 13.5), "dark"), box((7, 1, 3), (9, 16, 13), "panel"),
           box((6.8, 15, 2.8), (9.2, 16, 13.2), "dark")]
    model("theft_gate", {"panel": "shop_gate", "dark": "shop_plastic"}, els, "panel")


def guard_desk():
    els = [box((0, 0, 2), (16, 11, 14), {"north": ("front", None), "south": ("desk", None), "east": ("desk", None), "west": ("desk", None),
                                          "up": ("desk", None), "down": ("desk", None)}),
           box((9, 11, 6), (14, 11.5, 9), "dark"), box((11, 11.5, 7), (12, 12.5, 8), "dark"),
           box((8.5, 12.5, 6.5), (14.5, 16, 7.5), {"south": ("cctv", None), "north": ("dark", None), "up": ("dark", None), "down": ("dark", None),
                                                    "east": ("dark", None), "west": ("dark", None)}),
           box((2, 11, 8), (6, 11.3, 12), "light")]  # the visitors' log
    for e in els:
        for face, f in e["faces"].items():
            if f["uv"] is None:
                f["uv"] = decor.auto_uv(face, e["from"], e["to"])
    model("shop_guard_desk", {"desk": "shop_desk", "front": "shop_guard_front", "dark": "shop_plastic", "cctv": "shop_cctv", "light": "shop_counter_top"}, els, "desk")


def make_models():
    gondola(); bread_rack(); fridge(); freezer(); produce(); till(); self_checkout(); basket_stack(); theft_gate(); guard_desk()


# ---------------------------------------------------------------- the work clothes of the vanilla-looking villager

def profession_textures():
    """Overlay textures (64x64, the villager's layout) for the cashier — a blue smock with a white collar, a name badge and a white
    cap — and the shop guard — a black uniform with a «ОХРАНА» patch on the back and a cap."""
    out = os.path.join(ASSETS, "textures", "entity", "villager", "profession")
    os.makedirs(out, exist_ok=True)

    def outfit(main, trim, cap, badge, back_patch=None):
        img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
        r = random.Random(str(main))

        def fill(box_, color, amount=0.03):
            noise_fill(img, color + (255,), amount, r, box_)
        fill((22, 20, 38, 26), main)                 # body top/bottom
        fill((16, 26, 44, 38), main)                 # body sides
        rect(img, (24, 26, 28, 28), trim + (255,))   # collar
        rect(img, (25, 28, 27, 29), trim + (255,))
        rect(img, (27, 30, 29, 31), badge + (255,))  # the name badge
        if back_patch:
            rect(img, (37, 28, 43, 31), back_patch + (255,))
        fill((6, 38, 22, 44), main)                  # robe top
        fill((0, 44, 28, 58), main)                  # robe sides to the knee
        rect(img, (7, 50, 10, 53), shade(main + (255,), 0.8))  # pocket
        fill((44, 22, 52, 26), main)                 # arms top
        fill((44, 26, 60, 34), main)                 # arms sides
        fill((40, 38, 56, 42), main)
        fill((40, 42, 64, 46), main)
        rect(img, (44, 33, 60, 34), trim + (255,))   # cuffs
        fill((40, 0, 48, 8), cap)                    # cap on the hat layer
        fill((32, 8, 64, 10), cap)
        return img
    outfit((40, 84, 156), (240, 240, 236), (244, 244, 240), (220, 60, 50)).save(os.path.join(out, "cashier.png"))
    outfit((36, 38, 44), (90, 92, 100), (30, 32, 38), (200, 170, 60), (220, 220, 220)).save(os.path.join(out, "shop_guard.png"))


# ---------------------------------------------------------------- output

DISPLAY = decor.DISPLAY


def write(path, data):
    decor.write(path, data)


def save_all():
    tdir = os.path.join(ASSETS, "textures", "block", "shop")
    os.makedirs(tdir, exist_ok=True)
    for name, img in TEX.items():
        if name == "shop_basket_item":
            continue
        img.save(os.path.join(tdir, name + ".png"))
    idir = os.path.join(ASSETS, "textures", "item")
    os.makedirs(idir, exist_ok=True)
    TEX["shop_basket_item"].save(os.path.join(idir, "shopping_basket.png"))
    write(os.path.join(ASSETS, "models", "item", "shopping_basket.json"), {"parent": "minecraft:item/generated", "textures": {"layer0": "mobtalk:item/shopping_basket"}})
    for name, m in MODELS.items():
        write(os.path.join(ASSETS, "models", "block", "shop", name + ".json"), m)
        write(os.path.join(ASSETS, "blockstates", name + ".json"), {"variants": {
            f"facing={f}": ({"model": f"mobtalk:block/shop/{name}", "y": y} if y else {"model": f"mobtalk:block/shop/{name}"})
            for f, y in (("north", 0), ("east", 90), ("south", 180), ("west", 270))}})
        write(os.path.join(ASSETS, "models", "item", name + ".json"), {"parent": f"mobtalk:block/shop/{name}", "display": DISPLAY})
        write(os.path.join(DATA, "loot_table", "blocks", name + ".json"), {"type": "minecraft:block", "pools": [{"rolls": 1, "entries": [
            {"type": "minecraft:item", "name": f"mobtalk:{name}"}], "conditions": [{"condition": "minecraft:survives_explosion"}]}]})
    # the cashier's and the guard's job sites are points of interest a villager may take (as vanilla's job sites are)
    tag = os.path.join(ROOT, "src", "main", "resources", "data", "minecraft", "tags", "point_of_interest_type", "acquirable_job_site.json")
    have = json.load(open(tag, encoding="utf-8")) if os.path.exists(tag) else {"replace": False, "values": []}
    for v in ("mobtalk:cash_register", "mobtalk:shop_guard_desk"):
        if v not in have["values"]:
            have["values"].append(v)
    write(tag, have)


def preview(out):
    names = list(MODELS)
    cell = 256
    sheet = Image.new("RGBA", (cell * 4, cell * ((len(names) + 1) // 2)), (30, 30, 34, 255))
    for i, n in enumerate(names):
        for k, yaw in enumerate((35, 215)):
            im = decor.render(MODELS[n], yaw, cell)
            sheet.paste(im, ((i % 2) * 2 * cell + k * cell, (i // 2) * cell))
    sheet.save(out)


def main():
    import market  # noqa: E402  (the market's stalls and fittings go with the shop's)
    market.bind(sys.modules[__name__])
    import kiosk  # noqa: E402  (the street kiosks: their windows are displays too)
    kiosk.bind(sys.modules[__name__])
    import shop_goods  # noqa: E402  (the cart's textures, which the market's barrel borrows)
    make_textures()
    for k, v in shop_goods.cart_textures().items():
        decor.TEX[k] = v
    market.make_textures()
    kiosk.make_textures()
    make_models()
    market.make_models()
    kiosk.make_models()
    save_all()
    profession_textures()
    market.vendor_texture()
    market.lang()
    kiosk.lang()
    market.acquirable()
    if len(sys.argv) > 2 and sys.argv[1] == "preview":
        preview(sys.argv[2])
        return
    shop_goods.save_all()  # the groceries, the cart and its corral
    kiosk.save_items()
    import pieces  # noqa: E402
    market.add_market(pieces)
    kiosk.add_lot(pieces)
    import supermarket  # noqa: E402  (furnishes the template; skipped for a preview)
    supermarket.furnish()
    print("shop:", len(MODELS), "models,", len(TEX), "textures")


if __name__ == "__main__":
    main()
