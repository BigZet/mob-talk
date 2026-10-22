"""Draws the church's own textures and writes its block models, blockstates and item models.

Run from the repository root: python scripts/gen_church_assets.py
Everything is original pixel art drawn here in code (no images taken from elsewhere): gilded dome crosses, bronze bells,
icons in the Russian manner (Pantocrator, Theotokos, St Nicholas, Archangel Michael, John the Baptist, the Trinity), the
royal doors, brass candle stands, the panikadilo and the analoy.
"""
import json
import math
import os
import random

from PIL import Image, ImageDraw

ROOT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "divineblock")
TEX = os.path.join(ROOT, "textures", "block")
MODELS = os.path.join(ROOT, "models", "block")
ITEMS = os.path.join(ROOT, "models", "item")
STATES = os.path.join(ROOT, "blockstates")
rng = random.Random(1721)

GOLD, GOLD_HI, GOLD_LO, GOLD_DK = (214, 170, 60), (250, 222, 120), (160, 116, 34), (110, 74, 20)


def save(img, name):
    img.save(os.path.join(TEX, name + ".png"))


def noise_tex(name, base, spread, size=16, specks=None):
    img = Image.new("RGB", (size, size))
    px = img.load()
    for y in range(size):
        for x in range(size):
            k = rng.uniform(-spread, spread)
            c = [max(0, min(255, int(v + k))) for v in base]
            if specks and rng.random() < specks[1]:
                c = list(specks[0])
            px[x, y] = tuple(c)
    save(img, name)
    return img


def gold_tex():
    img = Image.new("RGB", (16, 16))
    px = img.load()
    for y in range(16):
        for x in range(16):
            t = 0.5 + 0.5 * math.sin((x + y) * 0.55) + rng.uniform(-0.15, 0.15)
            c = tuple(int(GOLD_LO[i] + (GOLD_HI[i] - GOLD_LO[i]) * max(0, min(1, t))) for i in range(3))
            px[x, y] = c
    save(img, "church_gold")


def bronze_tex():
    noise_tex("bell_bronze", (150, 104, 56), 14, specks=((92, 128, 100), 0.06))
    noise_tex("bell_inside", (52, 38, 24), 6)
    # The ornamental band: a row of bosses between two raised lines, and a band of "lettering".
    img = Image.new("RGB", (16, 16), (150, 104, 56))
    d = ImageDraw.Draw(img)
    for y in range(16):
        for x in range(16):
            k = rng.randint(-12, 12)
            img.putpixel((x, y), (150 + k, 104 + k, 56 + k // 2))
    d.line([(0, 2), (15, 2)], fill=(206, 160, 90)); d.line([(0, 13), (15, 13)], fill=(206, 160, 90))
    d.line([(0, 3), (15, 3)], fill=(96, 64, 30)); d.line([(0, 14), (15, 14)], fill=(96, 64, 30))
    for x in range(1, 16, 4):
        d.rectangle([x, 6, x + 1, 9], fill=(200, 150, 80))
        d.point([(x + 2, 7), (x + 2, 8)], fill=(96, 64, 30))
    save(img, "bell_band")


def flat(name, color, spread=6):
    noise_tex(name, color, spread)


def ellipse(d, cx, cy, rx, ry, fill, outline=None):
    d.ellipse([cx - rx, cy - ry, cx + rx, cy + ry], fill=fill, outline=outline)


def frame(d, w, h, s):
    d.rectangle([0, 0, w - 1, h - 1], outline=(96, 44, 22))
    d.rectangle([s, s, w - 1 - s, h - 1 - s], outline=GOLD_DK)


def gilded(w, h):
    img = Image.new("RGB", (w, h))
    px = img.load()
    for y in range(h):
        for x in range(w):
            t = 0.6 + 0.25 * math.sin(x * 0.3 + y * 0.2) + rng.uniform(-0.12, 0.12)
            px[x, y] = tuple(int(GOLD_LO[i] + (GOLD_HI[i] - GOLD_LO[i]) * max(0, min(1, t))) for i in range(3))
    return img


SKIN, SKIN_LO = (214, 166, 116), (160, 108, 66)


def saint(d, cx, top, s, robe, mantle, hair=(70, 44, 24), beard=None, veil=None, book=None, bald=False, omophorion=False):
    """A half-length figure: halo, face, hair or veil, robe and mantle; s is the scale (1 for a 32 px icon)."""
    hx, hy = cx, top + 7 * s
    ellipse(d, hx, hy, 7 * s, 7 * s, (246, 214, 112), (150, 104, 30))
    ellipse(d, hx, hy, 6 * s, 6 * s, (236, 196, 90))
    # Shoulders and robe: a trapezoid down to the bottom.
    bottom = top + 26 * s
    d.polygon([(cx - 5 * s, hy + 5 * s), (cx + 5 * s, hy + 5 * s), (cx + 11 * s, bottom), (cx - 11 * s, bottom)], fill=robe)
    if mantle:
        d.polygon([(cx - 6 * s, hy + 5 * s), (cx - 1 * s, hy + 6 * s), (cx + 3 * s, bottom), (cx - 11 * s, bottom)], fill=mantle)
        d.line([(cx - 1 * s, hy + 6 * s), (cx + 3 * s, bottom)], fill=tuple(max(0, c - 40) for c in mantle), width=max(1, s))
    if omophorion:
        d.polygon([(cx - 6 * s, hy + 6 * s), (cx + 6 * s, hy + 6 * s), (cx + 2 * s, hy + 10 * s), (cx - 2 * s, hy + 10 * s)], fill=(236, 232, 220))
        d.rectangle([cx - 1 * s, hy + 9 * s, cx + 1 * s, bottom], fill=(236, 232, 220))
        for yy in (hy + 12 * s, hy + 17 * s):
            d.line([(cx - 1 * s, yy), (cx + 1 * s, yy)], fill=(20, 20, 20)); d.line([(cx, yy - 1 * s), (cx, yy + 1 * s)], fill=(20, 20, 20))
    if veil:
        ellipse(d, hx, hy + 1 * s, 5 * s, 6 * s, veil)
        d.polygon([(cx - 5 * s, hy + 1 * s), (cx + 5 * s, hy + 1 * s), (cx + 8 * s, hy + 10 * s), (cx - 8 * s, hy + 10 * s)], fill=veil)
        for sx, sy in ((cx - 4 * s, hy + 7 * s), (cx + 4 * s, hy + 7 * s), (cx, hy - 4 * s)):
            d.point([(sx, sy)], fill=(250, 220, 110))
    elif not bald:
        ellipse(d, hx, hy - 0.5 * s, 4 * s, 4.5 * s, hair)
        d.rectangle([hx - 4 * s, hy, hx - 3 * s, hy + 5 * s], fill=hair)
        d.rectangle([hx + 3 * s, hy, hx + 4 * s, hy + 5 * s], fill=hair)
    else:
        ellipse(d, hx, hy - 0.5 * s, 4 * s, 4.5 * s, (200, 200, 196))
    ellipse(d, hx, hy + 0.5 * s, 3 * s, 4 * s, SKIN)
    d.line([(hx - 3 * s, hy + 1 * s), (hx - 3 * s, hy + 3 * s)], fill=SKIN_LO)
    if beard:
        d.polygon([(hx - 3 * s, hy + 2 * s), (hx + 3 * s, hy + 2 * s), (hx + 1 * s, hy + 6 * s), (hx - 1 * s, hy + 6 * s)], fill=beard)
    eye = (34, 22, 16)
    d.point([(hx - 1.4 * s, hy), (hx + 1.4 * s, hy)], fill=eye)
    if s > 1:
        d.rectangle([hx - 2 * s, hy - 0.2 * s, hx - 1 * s, hy + 0.4 * s], fill=eye)
        d.rectangle([hx + 1 * s, hy - 0.2 * s, hx + 2 * s, hy + 0.4 * s], fill=eye)
        d.line([(hx, hy), (hx, hy + 2 * s)], fill=SKIN_LO)
        d.line([(hx - 1 * s, hy + 3 * s), (hx + 1 * s, hy + 3 * s)], fill=(150, 60, 50))
    if book:
        d.rectangle([cx - 9 * s, hy + 12 * s, cx - 3 * s, hy + 19 * s], fill=book, outline=GOLD_HI)
        d.rectangle([cx - 7 * s, hy + 14 * s, cx - 5 * s, hy + 17 * s], fill=GOLD_HI)
    # A hand raised in blessing.
    ellipse(d, cx + 5 * s, hy + 10 * s, 1.5 * s, 1.5 * s, SKIN)


def letters(d, x, y, s, color=(150, 30, 24)):
    """Little abbreviations in red beside the halo (IC XC, MP ΘY): only marks, but they read as an icon."""
    for i in range(2):
        d.rectangle([x + i * 3 * s, y, x + i * 3 * s + 1 * s, y + 2 * s], fill=color)


def icon(name, size, draw):
    s = size // 32
    img = gilded(size, size)
    d = ImageDraw.Draw(img)
    draw(d, s)
    frame(d, size, size, s)
    save(img, name)


def christ(d, s):
    saint(d, 16 * s, 2 * s, s, (150, 38, 28), (36, 58, 118), beard=(70, 44, 24), book=(140, 30, 30))
    # The cross in the halo, only where the head does not cover it.
    d.line([(10 * s, 9 * s), (12 * s, 9 * s)], fill=(150, 30, 24), width=max(1, s))
    d.line([(20 * s, 9 * s), (22 * s, 9 * s)], fill=(150, 30, 24), width=max(1, s))
    d.line([(16 * s, 2 * s), (16 * s, 4 * s)], fill=(150, 30, 24), width=max(1, s))
    letters(d, 3 * s, 4 * s, s); letters(d, 24 * s, 4 * s, s)


def theotokos(d, s):
    saint(d, 15 * s, 2 * s, s, (36, 58, 118), None, veil=(118, 34, 30))
    # The Child in her arms.
    ellipse(d, 20 * s, 18 * s, 3.5 * s, 3.5 * s, (246, 214, 112), (150, 104, 30))
    ellipse(d, 20 * s, 18 * s, 2 * s, 2.5 * s, SKIN)
    d.polygon([(17 * s, 21 * s), (23 * s, 21 * s), (24 * s, 28 * s), (16 * s, 28 * s)], fill=(214, 150, 40))
    letters(d, 3 * s, 4 * s, s); letters(d, 24 * s, 4 * s, s)


def nicholas(d, s):
    saint(d, 16 * s, 2 * s, s, (130, 40, 40), None, beard=(220, 220, 214), bald=True, omophorion=True, book=(40, 70, 40))
    letters(d, 3 * s, 4 * s, s)


def michael(d, s):
    # Wings behind, red cloak, a spear.
    d.polygon([(4 * s, 26 * s), (10 * s, 10 * s), (14 * s, 16 * s)], fill=(70, 96, 80))
    d.polygon([(28 * s, 26 * s), (22 * s, 10 * s), (18 * s, 16 * s)], fill=(70, 96, 80))
    saint(d, 16 * s, 2 * s, s, (60, 90, 70), (170, 40, 30), hair=(150, 96, 40))
    d.line([(25 * s, 3 * s), (25 * s, 30 * s)], fill=(90, 60, 30), width=max(1, s))
    d.polygon([(24 * s, 3 * s), (26 * s, 3 * s), (25 * s, 0)], fill=(200, 200, 200))


def baptist(d, s):
    d.polygon([(3 * s, 24 * s), (9 * s, 9 * s), (13 * s, 17 * s)], fill=(120, 110, 70))
    d.polygon([(29 * s, 24 * s), (23 * s, 9 * s), (19 * s, 17 * s)], fill=(120, 110, 70))
    saint(d, 16 * s, 2 * s, s, (110, 84, 50), (70, 96, 60), hair=(60, 40, 20), beard=(60, 40, 20))


def trinity(d, s):
    d.rectangle([2 * s, 24 * s, 29 * s, 29 * s], fill=(236, 230, 210))
    d.polygon([(14 * s, 22 * s), (18 * s, 22 * s), (17 * s, 25 * s), (15 * s, 25 * s)], fill=(200, 160, 60))
    for cx, robe, mantle in ((7, (60, 90, 140), (170, 120, 60)), (16, (120, 40, 36), (40, 70, 130)), (25, (60, 90, 140), (90, 140, 90))):
        x = cx * s
        d.polygon([(x - 6 * s, 20 * s), (x - 1 * s, 6 * s), (x, 14 * s)], fill=(200, 150, 60))
        ellipse(d, x, 7 * s, 3.5 * s, 3.5 * s, (246, 214, 112), (150, 104, 30))
        ellipse(d, x, 7 * s, 2 * s, 2.5 * s, SKIN)
        d.polygon([(x - 3 * s, 10 * s), (x + 3 * s, 10 * s), (x + 4 * s, 24 * s), (x - 4 * s, 24 * s)], fill=robe)
        d.polygon([(x - 3 * s, 10 * s), (x, 11 * s), (x + 1 * s, 24 * s), (x - 4 * s, 24 * s)], fill=mantle)


def royal_doors():
    # Two gilded leaves in a 96 x 96 sheet, used as 2 x 3 blocks (the right third stays unused): an arched top with the
    # Annunciation, the four evangelists in round medallions below, a carved vine between.
    img = gilded(96, 96)
    d = ImageDraw.Draw(img)
    for leaf in range(2):
        x0 = leaf * 32
        d.rectangle([x0, 0, x0 + 31, 95], outline=GOLD_DK)
        d.rectangle([x0 + 1, 1, x0 + 30, 94], outline=(96, 44, 22))
        # Carved vine: two winding stems.
        for y in range(4, 94):
            for stem in (8, 23):
                x = x0 + stem + int(3 * math.sin(y * 0.35 + stem))
                d.point([(x, y), (x + 1, y)], fill=GOLD_DK)
                if y % 7 == 0: ellipse(d, x + 2, y, 1.5, 1, (232, 196, 96), GOLD_DK)
        # The Annunciation in the arch: Gabriel on the left leaf, the Virgin on the right.
        ellipse(d, x0 + 16, 18, 13, 14, (60, 90, 140), GOLD_DK)
        ellipse(d, x0 + 16, 18, 11, 12, (236, 214, 150))
        if leaf == 0:
            d.polygon([(x0 + 8, 28), (x0 + 12, 12), (x0 + 15, 20)], fill=(200, 150, 60))
            saint(d, x0 + 17, 7, 0.75, (60, 90, 70), (170, 40, 30), hair=(150, 96, 40))
        else:
            saint(d, x0 + 15, 7, 0.75, (36, 58, 118), None, veil=(118, 34, 30))
        d.rectangle([x0 + 3, 31, x0 + 28, 33], fill=GOLD_DK)
        for row in range(2):
            cy = 50 + row * 24
            ellipse(d, x0 + 16, cy, 9, 9, (60, 90, 140), GOLD_DK)
            ellipse(d, x0 + 16, cy, 8, 8, (236, 214, 150))
            saint(d, x0 + 16, cy - 8, 0.55, (150, 38, 28) if row else (40, 90, 60), (36, 58, 118), beard=(70, 44, 24), book=(140, 30, 30))
    d.rectangle([64, 0, 95, 95], fill=GOLD)
    save(img, "royal_doors")


def textures():
    os.makedirs(TEX, exist_ok=True)
    gold_tex()
    bronze_tex()
    flat("church_brass", (184, 140, 70), 12)
    flat("church_wax", (236, 222, 180), 6)
    flat("church_flame", (255, 214, 96), 10)
    flat("church_red_cloth", (140, 24, 30), 10)
    flat("church_dark_wood", (72, 48, 30), 8)
    for name, art in (("icon_christ", christ), ("icon_theotokos", theotokos), ("icon_nicholas", nicholas), ("icon_michael", michael),
                      ("icon_baptist", baptist), ("icon_trinity", trinity)):
        icon(name, 32, art)
    icon("icon_christ_large", 64, christ)
    icon("icon_theotokos_large", 64, theotokos)
    royal_doors()


# ---- Models. ----

def face(tex, uv=None, cull=None):
    f = {"texture": tex}
    if uv: f["uv"] = uv
    return f


def box(frm, to, tex, rot=None, faces=("north", "south", "east", "west", "up", "down"), down=None, up=None, uvs=None):
    e = {"from": [round(v, 3) for v in frm], "to": [round(v, 3) for v in to], "faces": {}}
    span = [abs(to[i] - frm[i]) for i in range(3)]
    dims = {"north": (span[0], span[1]), "south": (span[0], span[1]), "east": (span[2], span[1]), "west": (span[2], span[1]),
            "up": (span[0], span[2]), "down": (span[0], span[2])}
    for f in faces:
        t = tex
        if f == "down" and down: t = down
        if f == "up" and up: t = up
        # Always an explicit UV inside the texture: the automatic one runs off the sprite for parts outside the block.
        uv = uvs.get(f) if uvs and uvs.get(f) else None
        if uv is None:
            a, b = dims[f]
            uv = [0, 0, 16, round(min(16, max(1, b)), 3)] if t == "#band" else [0, 0, round(min(16, max(0.5, a)), 3), round(min(16, max(0.5, b)), 3)]
        e["faces"][f] = face(t, uv)
    if rot: e["rotation"] = rot
    return e


def model(name, elements, textures, ao=False, parent="minecraft:block/block", display=None):
    m = {"parent": parent, "ambientocclusion": ao, "textures": textures, "elements": elements}
    if display: m["display"] = display
    with open(os.path.join(MODELS, name + ".json"), "w", encoding="utf-8") as f:
        json.dump(m, f, indent=1)


def item(name, parent=None):
    with open(os.path.join(ITEMS, name + ".json"), "w", encoding="utf-8") as f:
        json.dump({"parent": parent or "divineblock:block/" + name}, f, indent=1)


def states(name, variants):
    with open(os.path.join(STATES, name + ".json"), "w", encoding="utf-8") as f:
        json.dump({"variants": variants}, f, indent=1)


SMALL_DISPLAY = {"gui": {"rotation": [30, 225, 0], "translation": [0, 0, 0], "scale": [0.3, 0.3, 0.3]},
                 "ground": {"scale": [0.2, 0.2, 0.2]}, "fixed": {"scale": [0.3, 0.3, 0.3]},
                 "thirdperson_righthand": {"rotation": [75, 45, 0], "scale": [0.2, 0.2, 0.2]},
                 "firstperson_righthand": {"rotation": [0, 45, 0], "scale": [0.2, 0.2, 0.2]}}


def dome_cross():
    """The eight-pointed cross three blocks tall, its bars along x: the post, the short top bar (the title), the long bar and
    the slanted foot. The block is the middle third; a crescent (tsata) at the foot of the post."""
    g = "#gold"
    el = [box((6.5, -16, 6.5), (9.5, 32, 9.5), g),
          box((-8, 12, 6.5), (24, 15.5, 9.5), g),
          box((1, 22.5, 6.5), (15, 25.5, 9.5), g),
          box((-3, -5, 6.5), (19, -2, 9.5), g, rot={"origin": [8, -3.5, 8], "axis": "z", "angle": -22.5}),
          # Little trefoil ends on the long bar and the top of the post.
          box((-9.5, 11, 6), (-7, 16.5, 10), g), box((23, 11, 6), (25.5, 16.5, 10), g), box((5.5, 30, 6), (10.5, 32, 10), g),
          # The crescent at the foot.
          box((1, -14, 7), (15, -12, 9), g), box((0, -12, 7), (3, -9, 9), g), box((13, -12, 7), (16, -9, 9), g)]
    model("dome_cross", el, {"gold": "divineblock:block/church_gold", "particle": "divineblock:block/church_gold"}, display=SMALL_DISPLAY)
    states("dome_cross", {"axis=x": {"model": "divineblock:block/dome_cross"}, "axis=z": {"model": "divineblock:block/dome_cross", "y": 90}})
    item("dome_cross")
    # The gilded "apple" on the dome's tip with the foot of the post.
    el = [box((4, 0, 4), (12, 7, 12), g), box((5, 7, 5), (11, 9, 11), g), box((6.5, 9, 6.5), (9.5, 16, 9.5), g),
          box((4, 0, 4), (12, 7, 12), g, rot={"origin": [8, 3.5, 8], "axis": "y", "angle": 45})]
    model("cross_apple", el, {"gold": "divineblock:block/church_gold", "particle": "divineblock:block/church_gold"})
    states("cross_apple", {"": {"model": "divineblock:block/cross_apple"}})
    item("cross_apple")


# The bell's profile, large size: (bottom, top, radius) in pixels around the block's centre, crown at y = 32.
BELL = [(-14, -11, 21), (-11, -6, 19.5), (-6, 0, 17.2), (0, 8, 15.6), (8, 16, 14.6), (16, 22, 13.8), (22, 26, 12.8), (26, 28.5, 10)]
BANDS = {1, 5}


def bell(name, scale, top):
    el = []
    def y(v): return top - (32 - v) * scale
    for i, (b, t, r) in enumerate(BELL):
        rr = r * scale
        tex = "#band" if i in BANDS else "#bronze"
        for angle in (0, 22.5, 45, -22.5):
            rot = None if angle == 0 else {"origin": [8, y(b), 8], "axis": "y", "angle": angle}
            el.append(box((8 - rr, y(b), 8 - rr), (8 + rr, y(t), 8 + rr), tex, rot=rot, down="#inside" if i == 0 else None))
    # The crown (ushi): two loops crossing on the top.
    c = scale
    el.append(box((8 - 6 * c, y(28.5), 8 - 1.2 * c), (8 + 6 * c, y(32), 8 + 1.2 * c), "#bronze"))
    el.append(box((8 - 1.2 * c, y(28.5), 8 - 6 * c), (8 + 1.2 * c, y(32), 8 + 6 * c), "#bronze"))
    # The clapper inside, its ball just showing under the lip.
    el.append(box((8 - 1 * c, y(-12), 8 - 1 * c), (8 + 1 * c, y(10), 8 + 1 * c), "#inside"))
    el.append(box((8 - 3 * c, y(-16), 8 - 3 * c), (8 + 3 * c, y(-11), 8 + 3 * c), "#inside"))
    model(name, el, {"bronze": "divineblock:block/bell_bronze", "band": "divineblock:block/bell_band", "inside": "divineblock:block/bell_inside",
                     "particle": "divineblock:block/bell_bronze"}, display=SMALL_DISPLAY if scale > 0.5 else None)


def bells():
    bell("church_bell_large", 1.0, 32)
    bell("church_bell_medium", 0.6, 16)
    bell("church_bell_small", 0.34, 16)
    states("church_bell", {"size=0": {"model": "divineblock:block/church_bell_small"}, "size=1": {"model": "divineblock:block/church_bell_medium"},
                           "size=2": {"model": "divineblock:block/church_bell_large"}})
    item("church_bell", "divineblock:block/church_bell_medium")


def candles(points, base, height, el):
    for (x, z) in points:
        el.append(box((x - 0.5, base, z - 0.5), (x + 0.5, base + height, z + 0.5), "#wax"))
        el.append(box((x - 0.4, base + height, z - 0.4), (x + 0.4, base + height + 1.2, z + 0.4), "#flame"))


def candle_stand():
    el = [box((4, 0, 4), (12, 1, 12), "#brass"), box((5.5, 1, 5.5), (10.5, 2.5, 10.5), "#brass"),
          box((7, 2.5, 7), (9, 11, 9), "#brass"), box((6, 6, 6), (10, 7, 10), "#brass"),
          box((1, 11, 1), (15, 12.5, 15), "#brass"), box((2, 12.5, 2), (14, 13, 14), "#wax"),
          box((0.5, 12.5, 0.5), (15.5, 13.5, 1.5), "#brass"), box((0.5, 12.5, 14.5), (15.5, 13.5, 15.5), "#brass"),
          box((0.5, 12.5, 1.5), (1.5, 13.5, 14.5), "#brass"), box((14.5, 12.5, 1.5), (15.5, 13.5, 14.5), "#brass")]
    pts = [(3, 3), (6, 4), (10, 3), (13, 5), (4, 8), (8, 7), (12, 9), (3, 12), (7, 11), (10, 13), (13, 12), (8, 10)]
    for i, p in enumerate(pts):
        candles([p], 13, 2 + (i * 7) % 4, el)
    model("candle_stand", el, {"brass": "divineblock:block/church_brass", "wax": "divineblock:block/church_wax", "flame": "divineblock:block/church_flame",
                               "particle": "divineblock:block/church_brass"})
    states("candle_stand", {"": {"model": "divineblock:block/candle_stand"}})
    item("candle_stand")


def chandelier():
    """The panikadilo: three tiers of rings on a rod, candles on every ring, a ball and a cross-finial; the block is the middle
    of a 3 x 3 x 3 space and hangs from a chain."""
    el = [box((7, -12, 7), (9, 32, 9), "#brass"), box((5, -16, 5), (11, -11, 11), "#brass"),
          box((5, -16, 5), (11, -11, 11), "#brass", rot={"origin": [8, -13.5, 8], "axis": "y", "angle": 45})]
    for (yy, r) in ((-6, 15), (6, 10), (17, 5.5)):
        t = 1.4
        # Two squares, one turned: an eight-pointed ring.
        for angle in (0, 45):
            rot = None if angle == 0 else {"origin": [8, yy, 8], "axis": "y", "angle": 45}
            el.append(box((8 - r, yy, 8 - r), (8 + r, yy + t, 8 - r + t), "#brass", rot=rot))
            el.append(box((8 - r, yy, 8 + r - t), (8 + r, yy + t, 8 + r), "#brass", rot=rot))
            el.append(box((8 - r, yy, 8 - r + t), (8 - r + t, yy + t, 8 + r - t), "#brass", rot=rot))
            el.append(box((8 + r - t, yy, 8 - r + t), (8 + r, yy + t, 8 + r - t), "#brass", rot=rot))
        el.append(box((8 - r, yy + 0.2, 7.5), (8 + r, yy + 1, 8.5), "#brass"))
        el.append(box((7.5, yy + 0.2, 8 - r), (8.5, yy + 1, 8 + r), "#brass"))
        d = r * math.sqrt(2)
        pts = [(8 - r + 0.7, 8 - r + 0.7), (8 + r - 0.7, 8 - r + 0.7), (8 - r + 0.7, 8 + r - 0.7), (8 + r - 0.7, 8 + r - 0.7),
               (8 - d + 0.7, 8), (8 + d - 0.7, 8), (8, 8 - d + 0.7), (8, 8 + d - 0.7)]
        pts += [(8 - r + 0.7, 8), (8 + r - 0.7, 8), (8, 8 - r + 0.7), (8, 8 + r - 0.7)]
        candles([(x, z) for x, z in pts if -15.5 <= x <= 31.5 and -15.5 <= z <= 31.5], yy + t, 2.5, el)
    model("chandelier", el, {"brass": "divineblock:block/church_gold", "wax": "divineblock:block/church_wax", "flame": "divineblock:block/church_flame",
                             "particle": "divineblock:block/church_gold"}, display=SMALL_DISPLAY)
    states("chandelier", {"": {"model": "divineblock:block/chandelier"}})
    item("chandelier")


def analoy():
    el = [box((3, 0, 3), (13, 12, 13), "#cloth"), box((2.5, 0, 2.5), (13.5, 1, 13.5), "#gold"),
          box((3, 11, 3), (13, 12, 13), "#gold"),
          box((2, 12, 2), (14, 13.5, 14), "#cloth", up="#icon", rot={"origin": [8, 12, 8], "axis": "x", "angle": 22.5},
              uvs={"up": [0, 0, 16, 16]})]
    model("analoy", el, {"cloth": "divineblock:block/church_red_cloth", "gold": "divineblock:block/church_gold",
                         "icon": "divineblock:block/icon_theotokos", "particle": "divineblock:block/church_red_cloth"})
    states("analoy", {f"facing={f}": ({"model": "divineblock:block/analoy", "y": y} if y else {"model": "divineblock:block/analoy"})
                      for f, y in (("north", 0), ("east", 90), ("south", 180), ("west", 270))})
    item("analoy")


# The pieces an icon panel can show: (texture, uv); index = the block's "art" property.
ART = [("icon_christ", None), ("icon_theotokos", None), ("icon_nicholas", None), ("icon_michael", None), ("icon_baptist", None), ("icon_trinity", None)]
for name in ("icon_christ_large", "icon_theotokos_large"):
    for r in range(2):
        for c in range(2):
            ART.append((name, [8 * c, 8 * r, 8 * c + 8, 8 * r + 8]))
for r in range(3):
    for c in range(2):
        t = 16 / 3
        ART.append(("royal_doors", [round(t * c, 4), round(t * r, 4), round(t * c + t, 4), round(t * r + t, 4)]))


def panels():
    """A painted board one pixel thick against the wall behind it (the model faces north, its back at the south edge)."""
    vs = {}
    for i, (tex, uv) in enumerate(ART):
        el = [box((0, 0, 15), (16, 16, 16), "#gold", faces=("south", "east", "west", "up", "down")),
              {"from": [0, 0, 15], "to": [16, 16, 16], "faces": {"north": {"texture": "#art", "uv": uv or [0, 0, 16, 16]}}}]
        el[1]["from"] = [0, 0, 14.9]
        model(f"icon_panel_{i}", el, {"gold": "divineblock:block/church_gold", "art": "divineblock:block/" + tex, "particle": "divineblock:block/" + tex}, ao=True)
        for f, y in (("north", 0), ("east", 90), ("south", 180), ("west", 270)):
            v = {"model": f"divineblock:block/icon_panel_{i}"}
            if y: v["y"] = y
            vs[f"art={i},facing={f}"] = v
    states("icon_panel", vs)
    item("icon_panel", "divineblock:block/icon_panel_0")
    print("icon pieces:", len(ART))


if __name__ == "__main__":
    for p in (TEX, MODELS, ITEMS, STATES): os.makedirs(p, exist_ok=True)
    textures()
    dome_cross()
    bells()
    candle_stand()
    chandelier()
    analoy()
    panels()
