"""The building blocks of the rebuilt cities' houses (spec §3-§4): walls papered per room, facade panels, floors and
ceilings, windows, doors, balconies, stairs, roof things. Draws the 16-px textures in code, writes models, blockstates,
item models, loot tables, names and the generated Java list io.github.mobtalk.game.CityBuildBlocks.

    python3 -I tools/city2/blocks.py [preview OUT.png]

A wall cell is one block, `wall_lo` (the lower room row, with skirting) or `wall_hi` (the upper row, with the cornice);
each of its four sides carries its own finish, so a partition shows the bedroom's wallpaper on one side and the kitchen's
tiles on the other. A facade cell has the panel outside and the room's finish inside.
"""
import json
import math
import os
import random
import sys

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "city"))
import decor as D  # noqa: E402  (texture helpers and the element builder)

ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
ASSETS = os.path.join(ROOT, "src", "main", "resources", "assets", "mobtalk")
DATA = os.path.join(ROOT, "src", "main", "resources", "data", "mobtalk")
VANILLA_JAR = os.path.expanduser("~/.gradle/caches/fabric-loom/1.21.1/minecraft-client.jar")

TEX = {}
MODELS = {}
NAMES = {}
FINISHES = ["plaster", "paper_beige", "paper_green", "paper_blue", "paper_pink", "paper_stripe", "paper_roses", "paper_damask",
            "paper_geo", "paper_wood", "tile_bath", "tile_kitchen", "paint_stair"]
COLORS = {"grey": (176, 174, 168), "beige": (214, 196, 160), "white": (226, 226, 220), "blue": (150, 178, 204)}
FACADE_KINDS = ("lo", "mid", "hi", "jlo", "jmid", "jhi")
ROWS = ("lo", "mid", "hi")

# Java registration lists, filled as blocks are defined
CUBES = []       # (id, map colour)
FACADES = []     # id
DECOR = []       # (id, mount, boxes, light, map colour, sound)
DOORS = []       # (id, map colour, sound)
STEPS = []       # stair steps: a slab with a facing and a railing
TRANSLUCENT = []
CUTOUT = []


def tex(name, img):
    TEX[name] = img
    return img


def rnd(name):
    return random.Random("city2:" + name)


def fill(img, base, amount, r, box=None):
    D.noise_fill(img, base + (255,) if len(base) == 3 else base, amount, r, box)


def put(img, x, y, c):
    if 0 <= x < img.width and 0 <= y < img.height:
        img.putpixel((x, y), c if len(c) == 4 else c + (255,))


# ---------------------------------------------------------------- textures: room finishes

def wallpaper(name, base, motif, step=(4, 8), stripe=None):
    """Soviet wallpaper: a soft ground with a small repeating motif (a flower, a diamond, a dot pair), staggered rows."""
    r = rnd(name)
    img = D.new()
    fill(img, base, 0.025, r)
    if stripe:
        for x in range(0, 16, stripe[0]):
            for y in range(16):
                put(img, x, y, D.shade(base + (255,), stripe[1]))
    sx, sy = step
    for row, y0 in enumerate(range(1, 16, sy)):
        off = (sx // 2) if row % 2 else 0
        for x0 in range(off, 16 + sx, sx):
            for dx, dy, k in motif:
                put(img, (x0 + dx) % 16, (y0 + dy) % 16, D.shade(base + (255,), k))
    return img


def skirting(img, wood=(110, 72, 44)):
    for x in range(16):
        put(img, x, 15, D.shade(wood + (255,), 0.75))
        put(img, x, 14, D.shade(wood + (255,), 1.0 if x % 5 else 0.9))
        put(img, x, 13, D.shade(wood + (255,), 1.18))
    return img


def cornice(img):
    for x in range(16):
        put(img, x, 0, (236, 234, 228))
        put(img, x, 1, (214, 212, 206))
    return img


def tiles(name, base, size=4, grout=None, accent=None):
    """Glazed tiles: each tile a touch lighter at its top-left, grout only a shade off the glaze (not a dark grid)."""
    r = rnd(name)
    img = D.new()
    grout = grout or D.shade(base + (255,), 0.88)[:3]
    for y in range(16):
        for x in range(16):
            if x % size == size - 1 or y % size == size - 1:
                c = D.shade(grout + (255,), r.uniform(0.98, 1.02))
            else:
                tile = (x // size, y // size)
                k = random.Random(f"{name}{tile}").uniform(0.97, 1.03)
                c = D.shade((accent if accent and tile[1] == 0 else base) + (255,), k * r.uniform(0.98, 1.02))
                if x % size == 0 and y % size == 0:
                    c = D.shade(c, 1.08)
            put(img, x, y, c)
    return img


def pattern_paper(name, ground, motif, step, noise=0.025, stagger=True):
    """Wallpaper with a coloured motif: (dx, dy, rgb) pixels repeated every step, odd rows shifted by half a step."""
    r = rnd(name)
    img = D.new()
    fill(img, ground, noise, r)
    sx, sy = step
    for row, y0 in enumerate(range(0, 16, sy)):
        off = (sx // 2) if (row % 2 and stagger) else 0
        for x0 in range(off - sx, 16 + sx, sx):
            for dx, dy, c in motif:
                put(img, (x0 + dx) % 16, (y0 + dy) % 16, D.shade(c + (255,), r.uniform(0.97, 1.03)))
    return img


def three_rows(name, plain, lo=None, hi=None):
    """The finish over a room's three rows: lo with skirting, mid plain, hi with the cornice (or the given images)."""
    tex(f"{name}_lo", lo if lo is not None else skirting(plain("lo")))
    tex(f"{name}_mid", plain("mid"))
    tex(f"{name}_hi", hi if hi is not None else cornice(plain("hi")))


def finish_textures():
    plaster = (232, 230, 222)

    def plain_plaster(row):
        img = D.new()
        fill(img, plaster, 0.02, rnd("plaster" + row))
        return img
    three_rows("plaster", plain_plaster, lo=plain_plaster("lo"))
    papers = {
        "paper_beige": ((222, 205, 170), [(0, 0, 0.82), (1, 1, 0.86), (-1, 1, 0.86), (0, 2, 0.82), (0, 1, 1.08)], (8, 6), None),
        "paper_green": ((168, 190, 150), [(0, 0, 0.84), (1, 0, 0.84), (0, 1, 0.9), (2, 2, 1.07)], (8, 8), (8, 0.95)),
        "paper_blue": ((170, 190, 214), [(0, 0, 1.1), (0, 1, 0.84), (0, 2, 1.1)], (4, 8), (4, 0.93)),
        "paper_pink": ((226, 196, 190), [(0, 0, 0.8), (1, 1, 0.8), (-1, 1, 0.8), (0, 2, 0.8), (0, 1, 1.1), (3, 4, 0.9)], (8, 8), None),
    }
    for name, (base, motif, step, stripe) in papers.items():
        three_rows(name, lambda row, name=name, base=base, motif=motif, step=step, stripe=stripe:
                   wallpaper(f"{name}_{row}", base, motif, step, stripe))
    # four more of the 70s-80s: a wine stripe, roses on cream, a gold damask on burgundy, brown rings on ochre
    def stripe(row):
        r = rnd("paper_stripe" + row)
        img = D.new()
        fill(img, (226, 214, 186), 0.02, r)
        for x in range(16):
            band = x % 8
            for y in range(16):
                if band in (0, 1):
                    put(img, x, y, D.shade((168, 84, 84, 255), r.uniform(0.95, 1.04) * (0.9 if band == 1 else 1.0)))
                elif band == 4:
                    put(img, x, y, D.shade((198, 172, 120, 255), r.uniform(0.96, 1.03)))
                elif band in (3, 5) and y % 4 == 0:
                    put(img, x, y, (206, 190, 156))
        return img
    three_rows("paper_stripe", stripe)
    R, r_, G, g = (190, 72, 84), (140, 42, 56), (104, 140, 88), (84, 112, 70)
    rose = [(1, 0, R), (2, 0, R), (0, 1, R), (1, 1, r_), (2, 1, R), (3, 1, R), (1, 2, R), (2, 2, r_), (0, 3, G), (3, 3, G),
            (-1, 4, g), (4, 4, g), (1, 3, G)]
    three_rows("paper_roses", lambda row: pattern_paper("paper_roses" + row, (234, 224, 200), rose, (8, 8)))
    gold = (182, 142, 82)
    damask = [(3, 0, gold), (2, 1, gold), (4, 1, gold), (1, 2, gold), (5, 2, gold), (0, 3, gold), (6, 3, gold), (3, 3, (204, 168, 104)),
              (1, 4, gold), (5, 4, gold), (2, 5, gold), (4, 5, gold), (3, 6, gold), (3, 2, (150, 70, 66)), (3, 4, (150, 70, 66))]
    three_rows("paper_damask", lambda row: pattern_paper("paper_damask" + row, (112, 40, 48), damask, (8, 8), noise=0.035))
    ring = [(x, y, (124, 78, 40)) for x in range(6) for y in range(6) if 1.6 <= ((x - 2.5) ** 2 + (y - 2.5) ** 2) ** 0.5 <= 2.7]
    ring += [(2, 2, (222, 126, 54)), (3, 2, (222, 126, 54)), (2, 3, (222, 126, 54)), (3, 3, (200, 106, 44))]
    three_rows("paper_geo", lambda row: pattern_paper("paper_geo" + row, (212, 176, 108), ring, (8, 8), noise=0.03))

    def wood(row):  # the hall: "wood" wallpaper, narrow boards with grain
        r = rnd("paper_wood" + row)
        img = D.new()
        base = (176, 132, 92)
        for x in range(16):
            k = 0.9 if x % 4 == 0 else r.uniform(0.98, 1.05)
            for y in range(16):
                put(img, x, y, D.shade(base + (255,), k * (0.96 if (y + x * 3) % 7 == 0 else 1.0)))
        return img
    three_rows("paper_wood", wood, lo=skirting(wood("lo"), (84, 56, 36)))
    # the bathroom: tiles to about 1.6 m, oil paint above, a blue line between
    bath = (206, 226, 232)
    mid = tiles("tile_bath_mid", bath)
    fill(mid, (222, 232, 226), 0.02, rnd("bathpaint"), (0, 0, 16, 5))
    for x in range(16):
        put(mid, x, 5, (120, 150, 170))
    hi = D.new()
    fill(hi, (222, 232, 226), 0.02, rnd("bathpaint_hi"))
    tex("tile_bath_lo", tiles("tile_bath_lo", bath))
    tex("tile_bath_mid", mid)
    tex("tile_bath_hi", cornice(hi))
    # the kitchen: light green oil paint below the worktop, the apron of small white tiles over it, cream paint above
    tile = tiles("tile_kitchen_band", (238, 238, 230), size=4)
    lo = D.new()
    fill(lo, (190, 214, 196), 0.025, rnd("kitchenlo"))
    for x in range(16):
        put(lo, x, 15, (150, 172, 156))
    for y in range(0, 3):
        for x in range(16):
            put(lo, x, y, tile.getpixel((x, y + 4)))
    mid = D.new()
    fill(mid, (236, 228, 196), 0.02, rnd("kitchenpaint"))
    for y in range(6, 16):
        for x in range(16):
            put(mid, x, y, tile.getpixel((x, y)))
    for x in range(16):
        put(mid, x, 5, (80, 120, 170))
    hi = D.new()
    fill(hi, (236, 228, 196), 0.02, rnd("kitchenpaint_hi"))
    tex("tile_kitchen_lo", lo)
    tex("tile_kitchen_mid", mid)
    tex("tile_kitchen_hi", cornice(hi))
    # the stairwell: green oil paint to 1.5 m, whitewash above, a dark line between
    green = (92, 140, 120)
    lo = D.new()
    fill(lo, green, 0.04, rnd("stairlo"))
    r = rnd("scuff")
    for _ in range(6):
        put(lo, r.randrange(16), r.randrange(8, 16), D.shade(green + (255,), 0.8))
    mid = D.new()
    fill(mid, (226, 226, 216), 0.03, rnd("stairmid"))
    fill(mid, green, 0.04, rnd("stairmid2"), (0, 9, 16, 16))
    for x in range(16):
        put(mid, x, 8, (60, 90, 78))
    hi = D.new()
    fill(hi, (226, 226, 216), 0.03, rnd("stairhi"))
    tex("paint_stair_lo", lo)
    tex("paint_stair_mid", mid)
    tex("paint_stair_hi", hi)


# ---------------------------------------------------------------- textures: facade, floors, ceiling

def panel(name, base):
    """A facade panel's face. Grey is concrete with exposed gravel, the others are faced with small ceramic tiles."""
    r = rnd(name)
    img = D.new()
    if name.startswith("grey"):
        fill(img, base, 0.05, r)
        for _ in range(26):
            x, y = r.randrange(16), r.randrange(16)
            put(img, x, y, D.shade(base + (255,), r.choice([0.72, 0.82, 1.12, 1.18])))
    elif name.startswith("beige"):
        fill(img, base, 0.035, r)
        for _ in range(12):
            put(img, r.randrange(16), r.randrange(16), D.shade(base + (255,), r.choice([0.86, 1.1])))
    else:
        for y in range(16):
            for x in range(16):
                grout = x % 2 == 1 and y % 2 == 1
                k = 0.86 if grout else random.Random(f"{name}{x // 2},{y // 2}").uniform(0.94, 1.06)
                put(img, x, y, D.shade(base + (255,), k))
    for _ in range(3):  # a few streaks of weather
        x = r.randrange(16)
        for y in range(r.randrange(6), 16):
            put(img, x, y, D.shade(img.getpixel((x, y)), 0.95))
    return img


def facade_textures():
    for c, base in COLORS.items():
        p = panel(c, base)
        tex(f"panel_{c}", p)
        j = p.copy()
        for y in range(16):
            put(j, 7, y, D.shade(base + (255,), 0.55))
            put(j, 8, y, D.shade(base + (255,), 1.12))
        tex(f"panel_{c}_joint", j)
        b = p.copy()  # the floor line: a horizontal joint, a little darker band of the slab edge
        for x in range(16):
            put(b, x, 14, D.shade(base + (255,), 0.55))
            put(b, x, 15, D.shade(base + (255,), 1.1))
            for y in range(0, 14):
                put(b, x, y, D.shade(b.getpixel((x, y)), 0.97))
        tex(f"panel_{c}_band", b)
        bj = b.copy()
        for y in range(16):
            put(bj, 7, y, D.shade(base + (255,), 0.55))
            put(bj, 8, y, D.shade(base + (255,), 1.12))
        tex(f"panel_{c}_cross", bj)
    r = rnd("plinth")
    img = D.new()
    fill(img, (96, 96, 98), 0.07, r)
    for _ in range(18):
        put(img, r.randrange(16), r.randrange(16), D.shade((96, 96, 98, 255), r.choice([0.7, 1.25])))
    for x in range(16):
        put(img, x, 0, (130, 130, 132))
    tex("plinth", img)
    r = rnd("ceiling")
    img = D.new()
    fill(img, (240, 238, 232), 0.015, r)
    tex("ceiling", img)
    r = rnd("slab_side")
    img = D.new()
    fill(img, (160, 158, 152), 0.05, r)
    tex("slab_side", img)
    # floors
    img = D.new()  # parquet "ёлочка": 4x1 planks in a herringbone
    r = rnd("parquet")
    for y in range(16):
        for x in range(16):
            d = (x + y) % 8
            plank = ((x + y) // 4 + (0 if d < 4 else 7)) % 3
            base = [(150, 96, 52), (166, 108, 60), (138, 88, 48)][plank]
            k = 0.88 if (x + y) % 4 == 0 else r.uniform(0.97, 1.04)
            put(img, x, y, D.shade(base + (255,), k))
    tex("floor_parquet", img)
    for name, base, pat in (("floor_lino_brown", (128, 84, 58), 0.9), ("floor_lino_beige", (196, 170, 128), 0.92)):
        r = rnd(name)
        img = D.new()
        fill(img, base, 0.03, r)
        for y in range(0, 16, 4):  # printed tiles
            for x in range(16):
                put(img, x, y, D.shade(base + (255,), pat))
        for x in range(0, 16, 4):
            for y in range(16):
                put(img, x, y, D.shade(base + (255,), pat))
        for y in range(2, 16, 4):
            for x in range(2, 16, 4):
                put(img, x, y, D.shade(base + (255,), 1.1))
        tex(name, img)
    tex("floor_tile", tiles("floor_tile", (196, 150, 120), size=4, grout=(168, 128, 104)))
    r = rnd("terrazzo")
    img = D.new()
    fill(img, (150, 146, 138), 0.04, r)
    for _ in range(40):
        put(img, r.randrange(16), r.randrange(16), r.choice([(110, 60, 50), (70, 70, 72), (220, 216, 206), (176, 150, 110)]))
    tex("terrazzo", img)
    r = rnd("concrete")
    img = D.new()
    fill(img, (168, 166, 160), 0.05, r)
    tex("concrete", img)
    r = rnd("bitumen")
    img = D.new()
    fill(img, (52, 50, 50), 0.08, r)
    for _ in range(30):
        put(img, r.randrange(16), r.randrange(16), D.shade((120, 118, 110, 255), r.uniform(0.7, 1.2)))
    tex("roof_bitumen", img)
    r = rnd("asphalt")
    img = D.new()
    fill(img, (72, 72, 74), 0.035, r)
    for _ in range(12):
        put(img, r.randrange(16), r.randrange(16), D.shade((92, 92, 94, 255), r.uniform(0.92, 1.06)))
    tex("asphalt", img)
    street_textures()


def street_textures():
    """The street (stage 2): a road marking line, pavement slabs, the granite kerb, the square's granite paving."""
    asphalt = TEX["asphalt"]
    for axis in ("x", "z"):
        r = rnd("line_" + axis)
        img = asphalt.copy()
        for a in range(16):
            for b in range(6, 10):
                c = D.shade((226, 224, 214, 255), r.uniform(0.9, 1.04)) if r.random() > 0.06 else asphalt.getpixel((a, b))
                put(img, *((a, b) if axis == "x" else (b, a)), c)
        tex("asphalt_line_" + axis, img)
    r = rnd("sidewalk")
    img = D.new()
    for y in range(16):
        for x in range(16):
            slab = (x // 8, y // 8)
            k = random.Random(f"sidewalk{slab}").uniform(0.95, 1.04)
            if x % 8 == 7 or y % 8 == 7:
                c = D.shade((118, 116, 110, 255), r.uniform(0.95, 1.05))
            else:
                c = D.shade((164, 162, 154, 255), k * r.uniform(0.95, 1.05))
            put(img, x, y, c)
    for _ in range(3):
        x, y = r.randrange(16), r.randrange(16)
        put(img, x, y, (128, 126, 120))
    tex("sidewalk", img)
    r = rnd("kerb")
    img = D.new()
    fill(img, (176, 174, 170), 0.05, r)
    for x in range(16):
        put(img, x, 0, D.shade((200, 198, 194, 255), r.uniform(0.97, 1.03)))
        put(img, x, 15, D.shade((200, 198, 194, 255), r.uniform(0.97, 1.03)))
        for y in (7,):
            put(img, x, y, D.shade((150, 148, 144, 255), r.uniform(0.97, 1.03)) if x % 16 == 15 else img.getpixel((x, y)))
    for y in range(16):
        put(img, 15, y, (140, 138, 134))
    tex("kerb", img)
    tex("plaza", tiles("plaza", (170, 140, 132), size=8, grout=(132, 110, 104)))


# ---------------------------------------------------------------- textures: windows, doors, rails, roof things

def window_textures():
    """The glazing of a window three rows high: each block column one sash, the frame's stiles at the block edges; the top
    row has the transom and the fortochka. Also the frames of glazed balconies: white PVC, brown wood, bare aluminium."""
    glass, shine = (150, 190, 214, 96), (220, 236, 246, 150)
    for name, frame in (("glass", (236, 236, 230, 255)), ("glass_wood", (128, 82, 50, 255)), ("glass_alu", (176, 180, 184, 255))):
        dark = D.shade(frame, 0.82)
        for part in ("lo", "mid", "hi"):
            img = D.new(16, 16, (0, 0, 0, 0))
            for y in range(16):
                for x in range(16):
                    put(img, x, y, glass)
            for y in range(16):
                put(img, 0, y, frame)
                put(img, 15, y, dark)
            if part == "hi":
                for x in range(16):
                    put(img, x, 3, frame)      # the frame's head (the row shows from y=3 down)
                    put(img, x, 9, frame)      # the transom
                    put(img, x, 10, dark)
                for y in range(3, 9):          # the fortochka: a small pane in the left half
                    put(img, 7, y, frame)
            if part == "lo":
                for x in range(16):
                    put(img, x, 15, frame)
            if part == "mid":
                for k in range(5):
                    put(img, 9 + k, 3 + k, shine)
            else:
                for k in range(3):
                    put(img, 10 + k, (11 if part == "hi" else 13) + k % 3, shine)
            tex(f"{name}_{part}", img)
    for name, base in (("frame_white", (238, 238, 232)), ("frame_wood", (128, 82, 50)), ("frame_alu", (176, 180, 184))):
        img = D.new()
        fill(img, base, 0.02, rnd(name))
        tex(name, img)
    img = D.new()
    fill(img, (242, 240, 234), 0.015, rnd("sill"))
    for x in range(16):
        put(img, x, 15, (200, 198, 192))
    tex("sill", img)
    img = D.new()
    fill(img, (150, 154, 158), 0.04, rnd("tin"))
    tex("tin", img)


def door_texture(name, body, kind):
    """Bottom and top halves of a door as 16x16 each, plus a 16x16 item sprite."""
    r = rnd(name)
    halves = {}
    for half in ("bottom", "top"):
        img = D.new()
        fill(img, body, 0.03, r)
        for y in range(16):
            put(img, 0, y, D.shade(body + (255,), 0.7))
            put(img, 15, y, D.shade(body + (255,), 0.7))
        if half == "top":
            for x in range(16):
                put(img, x, 0, D.shade(body + (255,), 0.7))
        if kind == "room":  # two recessed panels, upper one glazed with frosted glass
            box = (3, 3, 13, 15) if half == "top" else (3, 1, 13, 13)
            for y in range(box[1], box[3]):
                for x in range(box[0], box[2]):
                    if half == "top":
                        put(img, x, y, (206, 216, 220, 200) if (x + y) % 3 else (230, 236, 240, 210))
                    else:
                        put(img, x, y, D.shade(body + (255,), 0.93))
            D.bevel(img, box, 0.85, 1.1)
        elif kind == "flat":  # leatherette (dermantin) quilted with studs
            for y in range(1 if half == "top" else 0, 16):
                for x in range(1, 15):
                    if (x + y) % 4 == 0 or (x - y) % 4 == 0:
                        put(img, x, y, D.shade(body + (255,), 0.84))
                    if (x + y) % 4 == 0 and (x - y) % 4 == 0:
                        put(img, x, y, (200, 180, 110))
            if half == "top":
                put(img, 7, 4, (30, 30, 34))  # the peephole
                for x in range(6, 10):  # the flat number plate
                    for y in range(7, 9):
                        put(img, x, y, (210, 190, 120))
        elif kind == "steel":  # a steel flat door of the 90s: hammer paint, an embossed frame, two locks, a peephole
            box = (3, 2, 13, 16) if half == "top" else (3, 0, 13, 14)
            D.bevel(img, box, 1.12, 0.8)
            for y in range(box[1] + 2, box[3] - 2, 3):
                for x in range(box[0] + 2, box[2] - 2, 3):
                    put(img, x, y, D.shade(body + (255,), 1.06))
            if half == "top":
                put(img, 7, 5, (24, 24, 28))
                put(img, 8, 5, (24, 24, 28))
                for x in range(6, 10):
                    for y in (9, 10):
                        put(img, x, y, (206, 186, 116))
            else:
                put(img, 12, 6, (200, 196, 180))
                put(img, 12, 10, (40, 40, 40))
        elif kind == "veneer":  # a lacquered veneer door with mouldings
            for y in range(16):
                for x in range(1, 15):
                    put(img, x, y, D.shade(img.getpixel((x, y)), 0.95 if (x * 7 + y) % 9 == 0 else 1.0))
            for box in ([(3, 3, 13, 15)] if half == "top" else [(3, 1, 13, 7), (3, 9, 13, 15)]):
                D.bevel(img, box, 1.14, 0.78)
            if half == "top":
                put(img, 7, 5, (26, 26, 28))
                for x in range(6, 10):
                    for y in (9, 10):
                        put(img, x, y, (212, 192, 120))
        elif kind == "room_wood":  # a room door in light veneer with a ribbed amber glass
            box = (3, 3, 13, 15) if half == "top" else (3, 1, 13, 13)
            for y in range(box[1], box[3]):
                for x in range(box[0], box[2]):
                    if half == "top":
                        put(img, x, y, (214, 168, 96, 190) if y % 2 else (236, 196, 120, 200))
                    else:
                        put(img, x, y, D.shade(body + (255,), 0.92))
            D.bevel(img, box, 0.82, 1.12)
        elif kind == "entrance":  # a steel door with the intercom's code panel
            for x in range(1, 15):
                put(img, x, 8, D.shade(body + (255,), 0.8))
            if half == "top":
                for y in range(4, 10):
                    for x in range(10, 14):
                        put(img, x, y, (170, 174, 178))
                for y in (5, 6, 7, 8):
                    for x in (11, 12):
                        put(img, x, y, (60, 60, 64))
        elif kind == "balcony":  # glazed above, a panel below
            box = (2, 2, 14, 16) if half == "top" else (2, 0, 14, 7)
            for y in range(box[1], box[3]):
                for x in range(box[0], box[2]):
                    put(img, x, y, (150, 190, 214, 110))
            if half == "bottom":
                D.bevel(img, (2, 9, 14, 15), 0.85, 1.1)
        if half == "bottom" and kind != "balcony":  # the handle
            put(img, 12 if kind != "flat" else 13, 1, (190, 190, 186))
            put(img, 13 if kind != "flat" else 12, 1, (150, 150, 146))
            if kind in ("steel", "veneer", "flat"):
                put(img, 12, 3, (40, 40, 40))   # the keyhole under the handle
        halves[half] = img
        tex(f"{name}_{half}", img)
    item = D.new(16, 16, (0, 0, 0, 0))
    small_top = halves["top"].resize((8, 8), Image.NEAREST)
    small_bottom = halves["bottom"].resize((8, 8), Image.NEAREST)
    item.paste(small_top, (4, 0))
    item.paste(small_bottom, (4, 8))
    TEX[f"item_{name}"] = item


# id, ru, en, body colour, texture kind, map colour, sound
DOOR_SPECS = [
    ("door_room", "Межкомнатная дверь", "Room door", (232, 230, 220), "room", "WHITE", "WOOD"),
    ("door_room_wood", "Межкомнатная дверь (шпон)", "Room door (veneer)", (196, 150, 98), "room_wood", "OAK_TAN", "WOOD"),
    ("door_flat", "Дверь квартиры (коричневый дерматин)", "Flat door (brown leatherette)", (96, 52, 36), "flat", "BROWN", "WOOD"),
    ("door_flat_wine", "Дверь квартиры (бордовый дерматин)", "Flat door (wine leatherette)", (112, 34, 42), "flat", "DARK_RED", "WOOD"),
    ("door_flat_black", "Дверь квартиры (чёрный дерматин)", "Flat door (black leatherette)", (44, 40, 40), "flat", "BLACK", "WOOD"),
    ("door_flat_wood", "Дверь квартиры (лакированная)", "Flat door (lacquered)", (150, 96, 54), "veneer", "BROWN", "WOOD"),
    ("door_flat_steel", "Дверь квартиры (стальная, серая)", "Flat door (grey steel)", (112, 112, 116), "steel", "IRON_GRAY", "METAL"),
    ("door_flat_steel_brown", "Дверь квартиры (стальная, коричневая)", "Flat door (brown steel)", (98, 64, 46), "steel", "BROWN", "METAL"),
    ("door_entrance", "Дверь подъезда", "Entrance door", (70, 52, 44), "entrance", "BROWN", "METAL"),
    ("door_entrance_green", "Дверь подъезда (зелёная)", "Entrance door (green)", (56, 90, 70), "entrance", "DARK_GREEN", "METAL"),
    ("door_balcony", "Балконная дверь", "Balcony door", (236, 236, 230), "balcony", "WHITE", "WOOD"),
]


def misc_textures():
    for name, _, _, body, kind, _, _ in DOOR_SPECS:
        door_texture(name, body, kind)
    for name, base in (("metal_rail", (60, 92, 72)), ("metal_dark", (52, 52, 56)), ("metal_grey", (150, 152, 156))):
        img = D.new()
        fill(img, base, 0.05, rnd(name))
        tex(name, img)
    img = D.new()  # the handrail: polished wood
    fill(img, (120, 74, 40), 0.04, rnd("handrail"))
    for x in range(16):
        put(img, x, 0, (160, 104, 60))
    tex("handrail", img)
    img = D.new()  # corrugated balcony sheet, painted
    r = rnd("rail_sheet")
    base = (186, 204, 214)
    for x in range(16):
        k = (1.1, 1.0, 0.86, 0.95)[x % 4]
        for y in range(16):
            put(img, x, y, D.shade(base + (255,), k * r.uniform(0.97, 1.03)))
    for x in range(16):
        put(img, x, 0, (90, 96, 100))
        put(img, x, 1, (120, 128, 132))
    for _ in range(4):  # rust under the rail
        x = r.randrange(16)
        for y in range(2, 2 + r.randrange(2, 6)):
            put(img, x, y, (150, 100, 70))
    tex("rail_sheet", img)
    img = D.new()  # red brick of the vent stacks
    r = rnd("brick")
    for y in range(16):
        for x in range(16):
            row = y // 4
            off = 4 if row % 2 else 0
            if y % 4 == 3 or (x + off) % 8 == 7:
                c = (190, 186, 176)
            else:
                c = D.shade((150, 64, 48, 255), random.Random(f"b{row}{(x + off) // 8}").uniform(0.88, 1.1))[:3]
            put(img, x, y, c)
    tex("brick", img)
    img = D.new()  # louvres
    fill(img, (140, 140, 138), 0.04, rnd("louvre"))
    for y in range(2, 16, 3):
        for x in range(1, 15):
            put(img, x, y, (60, 60, 62))
    tex("louvre", img)
    img = D.new()  # the plafond: frosted glass, glowing
    for y in range(16):
        for x in range(16):
            d = math.hypot(x - 7.5, y - 7.5) / 10.6
            put(img, x, y, D.mix((255, 252, 236), (226, 220, 200), min(1, d)))
    tex("plafond", img)
    img = D.new()  # post boxes: blue painted steel, 3 x 3 doors with slots and numbers
    for y in range(16):
        for x in range(16):
            edge = x % 5 == 0 or y % 5 == 0 or x == 15 or y == 15
            put(img, x, y, (40, 60, 120) if edge else (64, 96, 170))
    for cy in (1, 6, 11):
        for cx in (1, 6, 11):
            for x in range(cx, cx + 3):
                put(img, x, cy + 1, (24, 30, 50))
            put(img, cx + 3, cy + 3, (220, 220, 220))
    tex("mailboxes", img)


def sheet(name, base, rib=4, rust=4, horizontal=False):
    """Profiled sheet steel, painted: ribs every `rib` pixels (vertical, or horizontal like vinyl siding), a little rust."""
    r = rnd(name)
    img = D.new()
    ks = {4: (1.1, 1.0, 0.86, 0.95), 3: (1.08, 0.97, 0.84)}[rib]
    for x in range(16):
        for y in range(16):
            k = ks[(y if horizontal else x) % rib]
            put(img, x, y, D.shade(base + (255,), k * r.uniform(0.98, 1.02)))
    for _ in range(rust):
        x = r.randrange(16)
        for y in range(r.randrange(10), 16):
            if r.random() < 0.6:
                put(img, x, y, D.mix(img.getpixel((x, y))[:3], (150, 100, 70), 0.5))
    return tex(name, img)


def balcony_textures():
    for name, base in (("blue", (186, 204, 214)), ("white", (226, 226, 220)), ("green", (120, 160, 120))):
        img = sheet(f"rail_{name}", base)
        for x in range(16):
            put(img, x, 0, (90, 96, 100))
            put(img, x, 1, (120, 128, 132))
    sheet("par_siding", (226, 220, 204), rib=3, rust=0, horizontal=True)
    sheet("par_brown", (120, 80, 56), rust=0)
    sheet("par_green", (96, 140, 104), rust=2)
    r = rnd("par_brick")   # a sand-lime brick parapet, laid by the owner
    img = D.new()
    for y in range(16):
        for x in range(16):
            row = y // 4
            off = 4 if row % 2 else 0
            if y % 4 == 3 or (x + off) % 8 == 7:
                c = (170, 166, 156)
            else:
                c = D.shade((224, 220, 206, 255), random.Random(f"sb{row}{(x + off) // 8}").uniform(0.92, 1.05) * r.uniform(0.98, 1.02))[:3]
            put(img, x, y, c)
    tex("par_brick", img)
    img = D.new()   # the entrance's plate: blue enamel, the entrance's number and the flats' range in white
    fill(img, (40, 70, 150), 0.02, rnd("plate"))
    for x in range(16):
        put(img, x, 0, (230, 230, 230))
        put(img, x, 15, (230, 230, 230))
    for y in range(16):
        put(img, 0, y, (230, 230, 230))
        put(img, 15, y, (230, 230, 230))
    for x, y in ((3, 4), (4, 3), (4, 4), (4, 5), (4, 6), (4, 7), (4, 8), (3, 8), (5, 8)):   # "1"
        put(img, x, y, (240, 240, 240))
    for y in (4, 7, 10):
        for x in range(8, 14):
            put(img, x, y, (220, 224, 236) if (x + y) % 3 else (150, 160, 200))
    for x in range(3, 14):
        put(img, x, 12, (220, 224, 236) if x % 2 else (150, 160, 200))
    tex("entrance_plate", img)


def make_textures():
    finish_textures()
    facade_textures()
    window_textures()
    misc_textures()
    balcony_textures()


# ---------------------------------------------------------------- models

def model(name, textures, elements, particle=None, ao=True, parent="minecraft:block/block"):
    m = {"parent": parent, "textures": {k: (v if ":" in v else f"mobtalk:block/city2/{v}") for k, v in textures.items()}}
    if elements is not None:
        m["elements"] = elements
    m["textures"].setdefault("particle", m["textures"][particle or next(iter(textures))])
    if not ao:
        m["ambientocclusion"] = False
    MODELS[name] = m


def face_box(f, t, faces):
    """An element whose faces each name (texture key, uv or None, cullface or None)."""
    el = {"from": list(f), "to": list(t), "faces": {}}
    for face, (key, uv, cull) in faces.items():
        spec = {"texture": "#" + key, "uv": list(uv) if uv else D.auto_uv(face, f, t)}
        if cull:
            spec["cullface"] = cull
        el["faces"][face] = spec
    return el


def walls():
    """wall_lo / wall_hi: four sides, each a finish property; one face model per finish, turned to the side by the blockstate."""
    for row in ROWS:
        model(f"wall_{row}_core", {"c": "slab_side"}, [face_box((0, 0, 0), (16, 16, 16), {"up": ("c", None, "up"), "down": ("c", None, "down")})])
        for f in FINISHES:
            model(f"wall_{row}_{f}", {"f": f"{f}_{row}"}, [face_box((0, 0, 0), (16, 16, 16), {"north": ("f", None, "north")})])
        parts = [{"apply": {"model": f"mobtalk:block/city2/wall_{row}_core"}}]
        for side, y in (("north", 0), ("east", 90), ("south", 180), ("west", 270)):
            for f in FINISHES:
                apply = {"model": f"mobtalk:block/city2/wall_{row}_{f}"}
                if y:
                    apply["y"] = y
                parts.append({"when": {side: f}, "apply": apply})
        BLOCKSTATES[f"wall_{row}"] = {"multipart": parts}
        ITEMS[f"wall_{row}"] = {"parent": "minecraft:block/cube_all", "textures": {"all": f"mobtalk:block/city2/paper_beige_{row}"}}
        NAMES[f"wall_{row}"] = (f"Стена квартиры ({ROW_RU[row]})", f"Flat wall ({ROW_EN[row]})")


ROW_RU = {"lo": "низ", "mid": "середина", "hi": "верх"}
ROW_EN = {"lo": "lower", "mid": "middle", "hi": "upper"}


def facades():
    """A facade cell is panel outside and the room's finish inside; its sides are the panel for the outer 6 px and plaster
    for the rest, so the window openings next to it have plastered reveals inside."""
    for c in COLORS:
        for kind in FACADE_KINDS:
            row = kind.lstrip("j")
            outer = f"panel_{c}_joint" if kind.startswith("j") else f"panel_{c}"
            name = f"facade_{c}_{kind}"
            variants = {}
            for f in FINISHES:
                mname = f"{name}_{f}"
                model(mname, {"o": outer, "s": f"panel_{c}", "i": f"{f}_{row}", "rv": "plaster_mid", "c": "slab_side"},
                      [face_box((0, 0, 0), (16, 16, 6), {"north": ("o", None, "north"), "east": ("s", None, "east"), "west": ("s", None, "west"),
                                                         "up": ("c", None, "up"), "down": ("c", None, "down")}),
                       face_box((0, 0, 6), (16, 16, 16), {"south": ("i", None, "south"), "east": ("rv", None, "east"), "west": ("rv", None, "west"),
                                                          "up": ("c", None, "up"), "down": ("c", None, "down")})], particle="o")
                for facing, y in (("north", 0), ("east", 90), ("south", 180), ("west", 270)):
                    v = {"model": f"mobtalk:block/city2/{mname}"}
                    if y:
                        v["y"] = y
                    variants[f"facing={facing},inner={f}"] = v
            BLOCKSTATES[name] = {"variants": variants}
            ITEMS[name] = {"parent": f"mobtalk:block/city2/{name}_plaster"}
            FACADES.append(name)
            ru = {"grey": "серая", "beige": "бежевая", "white": "белая плитка", "blue": "голубая плитка"}[c]
            NAMES[name] = (f"Панель фасада, {ru} ({'стык, ' if kind.startswith('j') else ''}{ROW_RU[row]})",
                           f"Facade panel, {c} ({'joint, ' if kind.startswith('j') else ''}{ROW_EN[row]})")
        for kind, texture, ru in (("band", f"panel_{c}_band", "пояс перекрытия"), ("cross", f"panel_{c}_cross", "стык у перекрытия"),
                                  ("edge", f"panel_{c}_joint", "угол")):
            cube(f"facade_{c}_{kind}", texture, f"Панель фасада, {ru}", f"Facade panel, {kind}", "STONE_GRAY")


def cube(name, texture, ru, en, color, bottom=None, side=None):
    if bottom or side:
        model(name, {"top": texture, "bottom": bottom or texture, "side": side or texture}, None, particle="side",
              parent="minecraft:block/cube_bottom_top")
    else:
        model(name, {"all": texture}, None, parent="minecraft:block/cube_all")
    BLOCKSTATES[name] = {"variants": {"": {"model": f"mobtalk:block/city2/{name}"}}}
    ITEMS[name] = {"parent": f"mobtalk:block/city2/{name}"}
    NAMES[name] = (ru, en)
    CUBES.append((name, color))


def floors():
    for name, ru, en, color in (("floor_parquet", "Перекрытие с паркетом", "Slab with parquet", "OAK_TAN"),
                                ("floor_lino_brown", "Перекрытие с линолеумом (коричневым)", "Slab with brown linoleum", "BROWN"),
                                ("floor_lino_beige", "Перекрытие с линолеумом (бежевым)", "Slab with beige linoleum", "PALE_YELLOW"),
                                ("floor_tile", "Перекрытие с плиткой", "Slab with tiles", "TERRACOTTA_WHITE"),
                                ("floor_terrazzo", "Перекрытие с мраморной крошкой", "Slab with terrazzo", "STONE_GRAY"),
                                ("floor_concrete", "Бетонное перекрытие", "Concrete slab", "STONE_GRAY")):
        cube(name, "terrazzo" if name == "floor_terrazzo" else "concrete" if name == "floor_concrete" else name, ru, en, color,
             bottom="ceiling", side="slab_side")
    cube("roof_bitumen", "roof_bitumen", "Кровля (рубероид)", "Roofing felt", "BLACK", bottom="ceiling", side="slab_side")
    cube("plinth", "plinth", "Цоколь", "Plinth", "GRAY")
    cube("asphalt", "asphalt", "Асфальт", "Asphalt", "GRAY")
    cube("asphalt_line_x", "asphalt_line_x", "Асфальт с разметкой (вдоль X)", "Asphalt with a marking line (along X)", "GRAY", bottom="asphalt", side="asphalt")
    cube("asphalt_line_z", "asphalt_line_z", "Асфальт с разметкой (вдоль Z)", "Asphalt with a marking line (along Z)", "GRAY", bottom="asphalt", side="asphalt")
    cube("sidewalk", "sidewalk", "Тротуарная плитка", "Pavement slabs", "STONE_GRAY")
    cube("kerb", "kerb", "Бордюр (гранитный)", "Granite kerb", "LIGHT_GRAY")
    cube("plaza", "plaza", "Гранитная плитка площади", "Granite paving of the square", "TERRACOTTA_PINK")


def decor(name, textures, elements, ru, en, boxes=None, light=0, color="STONE_GRAY", sound="STONE", mount="GROUND", layer=None, ao=True):
    model(name, textures, elements, ao=ao)
    BLOCKSTATES[name] = {"variants": {
        f"facing={f}": ({"model": f"mobtalk:block/city2/{name}", "y": y} if y else {"model": f"mobtalk:block/city2/{name}"})
        for f, y in (("north", 0), ("east", 90), ("south", 180), ("west", 270))}}
    ITEMS[name] = {"parent": f"mobtalk:block/city2/{name}"}
    NAMES[name] = (ru, en)
    if boxes is None:
        lo = [max(0.0, min(e["from"][i] for e in elements)) for i in range(3)]
        hi = [min(16.0, max(e["to"][i] for e in elements)) for i in range(3)]
        boxes = [lo + [max(h, l + 0.5) for l, h in zip(lo, hi)]]
    DECOR.append((name, mount, boxes, light, color, sound))
    if layer == "translucent":
        TRANSLUCENT.append(name)
    elif layer == "cutout":
        CUTOUT.append(name)


WINDOWS = []     # id: facing + inner finish, see-through


def windows():
    """A window three rows high in a panel: the lower part is the wall to the sill (panel outside, the room's finish inside)
    with the tin drip outside and the white sill inside, then glass; the middle is all glass; the upper part is glass to
    13 px under the lintel. The glazing stands in the middle of the wall's depth."""
    for c in COLORS:
        def walls_lo(i):
            return [face_box((0, 0, 0), (16, 12, 6), {"north": ("p", None, "north"), "east": ("p", None, "east"), "west": ("p", None, "west"),
                                                     "down": ("p", None, "down")}),
                    face_box((0, 0, 6), (16, 12, 16), {"south": ("in", None, "south"), "east": ("rv", None, "east"), "west": ("rv", None, "west"),
                                                      "down": ("rv", None, "down")}),
                    face_box((0, 11.5, -1.5), (16, 12.5, 6), {"up": ("tin", None, None), "north": ("tin", None, None), "down": ("tin", None, None)}),
                    face_box((0, 12, 6), (16, 13.5, 10), {"up": ("fr", None, None), "north": ("fr", None, None), "south": ("fr", None, None)}),
                    face_box((0, 12, 10), (16, 13, 17.5), {"up": ("sill", None, None), "south": ("sill", None, None), "down": ("sill", None, None),
                                                          "east": ("sill", None, None), "west": ("sill", None, None)}),
                    face_box((0, 13.5, 7.5), (16, 16, 8.5), {"north": ("g", None, None), "south": ("g", None, None)})]
        mid = [face_box((0, 0, 7.5), (16, 16, 8.5), {"north": ("g", None, None), "south": ("g", None, None)})]
        hi = [face_box((0, 0, 7.5), (16, 13, 8.5), {"north": ("g", None, None), "south": ("g", None, None)}),
              face_box((0, 13, 0), (16, 16, 6), {"north": ("p", None, "north"), "down": ("p", None, None), "up": ("p", None, "up"),
                                                 "east": ("p", None, "east"), "west": ("p", None, "west")}),
              face_box((0, 13, 6), (16, 16, 10), {"down": ("fr", None, None), "north": ("fr", None, None), "south": ("fr", None, None),
                                                  "up": ("c", None, "up"), "east": ("rv", None, "east"), "west": ("rv", None, "west")}),
              face_box((0, 13, 10), (16, 16, 16), {"south": ("in", None, "south"), "down": ("rv", None, None), "up": ("c", None, "up"),
                                                   "east": ("rv", None, "east"), "west": ("rv", None, "west")})]
        for part, ru, en in (("lo", "нижняя часть, с подоконником", "lower part, with the sill"), ("mid", "середина", "middle"),
                             ("hi", "верхняя часть, с фрамугой", "upper part, with the transom")):
            name = f"window_{c}_{part}"
            base = {"p": f"panel_{c}", "g": f"glass_{part}", "fr": "frame_white", "rv": "plaster_mid", "tin": "tin", "sill": "sill",
                    "c": "slab_side"}
            variants = {}
            for f in (FINISHES if part != "mid" else [None]):
                mname = f"{name}_{f}" if f else name
                els = walls_lo(f) if part == "lo" else hi if part == "hi" else mid
                model(mname, dict(base, **({"in": f"{f}_{part}"} if f else {})), els, particle="p" if part != "mid" else "fr", ao=part != "mid")
                for facing, y in (("north", 0), ("east", 90), ("south", 180), ("west", 270)):
                    v = {"model": f"mobtalk:block/city2/{mname}"}
                    if y:
                        v["y"] = y
                    variants[f"facing={facing}" + (f",inner={f}" if f else "")] = v
            BLOCKSTATES[name] = {"variants": variants}
            ITEMS[name] = {"parent": f"mobtalk:block/city2/{name}_plaster" if part != "mid" else f"mobtalk:block/city2/{name}"}
            NAMES[name] = (f"Окно, {ru}", f"Window, {en}")
            WINDOWS.append(name)
            TRANSLUCENT.append(name)


def doors():
    with open_vanilla("assets/minecraft/blockstates/oak_door.json") as f:
        vanilla = f.read().decode()
    for name, ru, en, _, kind, color, sound in DOOR_SPECS:
        for part in ("bottom_left", "bottom_left_open", "bottom_right", "bottom_right_open", "top_left", "top_left_open", "top_right",
                     "top_right_open"):
            model(f"{name}_{part}", {"bottom": f"{name}_bottom", "top": f"{name}_top"}, None, particle="bottom",
                  parent=f"minecraft:block/door_{part}")
        BLOCKSTATES[name] = json.loads(vanilla.replace("minecraft:block/oak_door", f"mobtalk:block/city2/{name}"))
        ITEMS[name] = {"parent": "minecraft:item/generated", "textures": {"layer0": f"mobtalk:block/city2/item_{name}"}}
        NAMES[name] = (ru, en)
        DOORS.append((name, color, sound))
        (TRANSLUCENT if kind in ("room", "room_wood", "balcony") else CUTOUT).append(name)


def open_vanilla(path):
    import zipfile
    return zipfile.ZipFile(VANILLA_JAR).open(path)


STEP_RAILS = ("left", "right", "front")


def steps():
    """A stair step is a terrazzo half-slab; its `rail` puts a thin railing on it: steel balusters and a wooden handrail on
    the left or right edge (seen going up the flight, which climbs towards `facing`), sloped with the flight, or along the
    front edge of a landing. The handrail is 14 px over the step's tread."""
    name = "step_terrazzo"
    model(name, {"top": "terrazzo", "bottom": "ceiling", "side": "slab_side"}, None, parent="minecraft:block/slab")
    model(name + "_top", {"top": "terrazzo", "bottom": "ceiling", "side": "slab_side"}, None, parent="minecraft:block/slab_top")
    model(name + "_double", {"top": "terrazzo", "bottom": "terrazzo", "side": "slab_side"}, None, particle="side",
          parent="minecraft:block/cube_bottom_top")
    m = lambda f, t: face_box(f, t, {fc: ("m", None, None) for fc in D.FACES})  # noqa: E731
    h = lambda f, t: face_box(f, t, {fc: ("h", None, None) for fc in D.FACES})  # noqa: E731
    for half, tread in (("bottom", 8), ("top", 16)):
        rail = tread + 14
        for side in ("left", "right"):
            x0 = 14.5 if side == "right" else 0.5
            els = [m((x0, tread, 3.5), (x0 + 1, rail + 2, 4.5)), m((x0, tread, 11.5), (x0 + 1, rail - 2, 12.5))]
            hr = h((x0 - 0.5, rail - 0.75, -0.7), (x0 + 1.5, rail + 0.75, 16.7))
            hr["rotation"] = {"origin": [x0 + 0.5, rail, 8], "axis": "x", "angle": 22.5}
            els.append(hr)
            model(f"{name}_rail_{half}_{side}", {"m": "metal_dark", "h": "handrail"}, els, ao=False)
        els = [m((x, tread, 0.5), (x + 1, rail, 1.5)) for x in (1.5, 5.5, 9.5, 13.5)]
        els.append(h((0, rail, 0), (16, rail + 1.5, 2)))
        model(f"{name}_rail_{half}_front", {"m": "metal_dark", "h": "handrail"}, els, ao=False)
    parts = [{"when": {"type": t}, "apply": {"model": f"mobtalk:block/city2/{name}{suffix}"}}
             for t, suffix in (("bottom", ""), ("top", "_top"), ("double", "_double"))]
    for half in ("bottom", "top"):
        for side in STEP_RAILS:
            for facing, y in (("north", 0), ("east", 90), ("south", 180), ("west", 270)):
                apply = {"model": f"mobtalk:block/city2/{name}_rail_{half}_{side}"}
                if y:
                    apply["y"] = y
                parts.append({"when": {"type": half, "rail": side, "facing": facing}, "apply": apply})
    BLOCKSTATES[name] = {"multipart": parts}
    ITEMS[name] = {"parent": f"mobtalk:block/city2/{name}"}
    NAMES[name] = ("Ступень лестницы", "Stair step")
    STEPS.append(name)
    CUTOUT.append(name)


def balconies():
    """Balcony pieces, one block each along the balcony's front (l, m, r: the left end, the middle, the right end, looking out;
    the ends carry the side). Open: a sheet railing in one of three paints. Glazed by the owner: a parapet (vinyl siding,
    brown or green profiled sheet, sand-lime brick), then two rows of frames (white PVC, brown wood, bare aluminium)."""
    def faces(key):
        return {fc: (key, None, None) for fc in D.FACES}
    ru_paint = {"blue": "голубое", "white": "белое", "green": "зелёное"}
    ru_pos = {"l": "левый край", "m": "середина", "r": "правый край"}
    en_pos = {"l": "left end", "m": "middle", "r": "right end"}
    for paint in ("blue", "white", "green"):
        for pos in ("l", "m", "r"):
            els = [face_box((0, 0, 0.5), (16, 14, 1.5), faces("r")), face_box((0, 14, 0), (16, 15.5, 2), faces("m"))]
            boxes = [[0, 0, 0, 16, 16, 2]]
            for side, x0 in (("l", 0.5), ("r", 14.5)):
                if pos == side:
                    els += [face_box((x0, 0, 1.5), (x0 + 1, 14, 16), faces("r")), face_box((x0 - 0.5, 14, 2), (x0 + 1.5, 15.5, 16), faces("m"))]
                    boxes.append([x0 - 0.5, 0, 0, x0 + 1.5, 16, 16])
            decor(f"balrail_{paint}_{pos}", {"r": f"rail_{paint}", "m": "metal_dark"}, els,
                  f"Ограждение балкона ({ru_paint[paint]}, {ru_pos[pos]})", f"Balcony railing ({paint}, {en_pos[pos]})",
                  boxes=boxes, color="LIGHT_BLUE", sound="METAL")
    ru_mat = {"siding": "сайдинг", "brown": "коричневый профлист", "green": "зелёный профлист", "brick": "силикатный кирпич"}
    for mat in ("siding", "brown", "green", "brick"):
        t = 3 if mat == "brick" else 1.5
        for pos in ("l", "m", "r"):
            els = [face_box((0, 0, 0), (16, 15, t), faces("p")), face_box((0, 15, -0.5), (16, 16, t + 3), faces("s"))]
            boxes = [[0, 0, 0, 16, 16, t + 3]]
            for side, x0 in (("l", 0), ("r", 16 - t)):
                if pos == side:
                    els += [face_box((x0, 0, t), (x0 + t, 15, 16), faces("p")), face_box((x0, 15, t + 3), (x0 + t, 16, 16), faces("s"))]
                    boxes.append([x0, 0, 0, x0 + t, 16, 16])
            decor(f"balpar_{mat}_{pos}", {"p": f"par_{mat}", "s": "sill"}, els, f"Парапет балкона ({ru_mat[mat]}, {ru_pos[pos]})",
                  f"Balcony parapet ({mat}, {en_pos[pos]})", boxes=boxes, color="WHITE_GRAY" if mat == "siding" else "BROWN",
                  sound="STONE" if mat == "brick" else "METAL")
    for fr in ("pvc", "wood", "alu"):
        g = {"pvc": "glass", "wood": "glass_wood", "alu": "glass_alu"}[fr]
        f = {"pvc": "frame_white", "wood": "frame_wood", "alu": "frame_alu"}[fr]
        for row in ("glass", "top"):
            for pos in ("l", "m", "r"):
                h = 16 if row == "glass" else 12
                els = [face_box((0, 0, 1), (16, h, 2), {"north": ("g", [0, 16 - h, 16, 16], None), "south": ("g", [0, 16 - h, 16, 16], None)}),
                       face_box((0, 0, 0.5), (1.5, h, 2.5), faces("f")), face_box((14.5, 0, 0.5), (16, h, 2.5), faces("f"))]
                if row == "glass":
                    els.append(face_box((0, 0, 0.5), (16, 1.5, 2.5), faces("f")))
                else:
                    els += [face_box((0, 12, 0), (16, 16, 3), faces("f")), face_box((0, 7, 0.5), (16, 8.5, 2.5), faces("f"))]
                boxes = [[0, 0, 0, 16, 16, 3]]
                for side, x0 in (("l", 0.5), ("r", 14.5)):
                    if pos == side:
                        els += [face_box((x0 + 0.25, 0, 2.5), (x0 + 0.75, h, 15.5), {"east": ("g", None, None), "west": ("g", None, None)}),
                                face_box((x0 - 0.5, 0, 15), (x0 + 1.5, h, 16), faces("f"))]
                        if row == "top":
                            els.append(face_box((x0 - 0.5, 12, 3), (x0 + 1.5, 16, 15), faces("f")))
                        boxes.append([x0 - 0.5, 0, 0, x0 + 1.5, 16, 16])
                ru = {"pvc": "белый пластик", "wood": "дерево", "alu": "алюминий"}[fr]
                decor(f"bal{row}_{fr}_{pos}", {"g": f"{g}_mid", "f": f}, els,
                      f"Остекление балкона ({ru}, {'рамы' if row == 'glass' else 'верх'}, {ru_pos[pos]})",
                      f"Balcony glazing ({fr}, {'frames' if row == 'glass' else 'top'}, {en_pos[pos]})", boxes=boxes, color="WHITE",
                      sound="GLASS", layer="translucent", ao=False)
    roof = face_box((-0.5, 1, -1.5), (16.5, 2, 16), {fc: ("t", None, None) for fc in D.FACES})
    roof["rotation"] = {"origin": [8, 1.5, 16], "axis": "x", "angle": -22.5}
    decor("balcony_roof", {"t": "tin"}, [roof, face_box((0, 0, 14), (16, 2, 16), faces("t"))], "Крыша балкона", "Balcony roof",
          boxes=[[0, 0, 0, 16, 6, 16]], color="LIGHT_GRAY", sound="METAL")


def rails_and_roof():
    decor("balcony_slab", {"c": "concrete", "s": "slab_side"},
          [face_box((0, 10, 0), (16, 16, 16), {"up": ("c", None, None), "down": ("s", None, None), "north": ("s", None, None),
                                               "east": ("s", None, None), "west": ("s", None, None), "south": ("s", None, "south")})],
          "Плита балкона", "Balcony slab", color="STONE_GRAY")
    decor("canopy", {"c": "concrete", "s": "slab_side", "t": "tin"},
          [face_box((0, 0, 0), (16, 4, 16), {"up": ("t", None, None), "down": ("c", None, None), "north": ("s", None, None),
                                             "east": ("s", None, None), "west": ("s", None, None), "south": ("s", None, "south")})],
          "Козырёк подъезда", "Entrance canopy")
    decor("ceiling_lamp", {"g": "plafond", "m": "metal_grey"},
          [face_box((5, 15.25, 5), (11, 16, 11), {fc: ("m", None, None) for fc in D.FACES}),
           face_box((4, 13.5, 4), (12, 15.25, 12), {fc: ("g", None, None) for fc in D.FACES}),
           face_box((5, 12.5, 5), (11, 13.5, 11), {fc: ("g", None, None) for fc in D.FACES}),
           face_box((6.5, 12, 6.5), (9.5, 12.5, 9.5), {fc: ("g", None, None) for fc in D.FACES})],
          "Плафон", "Ceiling lamp", light=14, color="WHITE", sound="GLASS", ao=False)
    decor("wall_lamp_out", {"g": "plafond", "m": "metal_dark"},
          [face_box((6, 4, 15), (10, 13, 16), {fc: ("m", None, None) for fc in D.FACES}),
           face_box((7.25, 11, 11), (8.75, 12, 15), {fc: ("m", None, None) for fc in D.FACES}),
           face_box((5.5, 10.5, 7.5), (10.5, 11.5, 12.5), {fc: ("m", None, None) for fc in D.FACES}),
           face_box((6, 5.5, 8), (10, 10.5, 12), {fc: ("g", None, None) for fc in D.FACES}),
           face_box((6.5, 5, 8.5), (9.5, 5.5, 11.5), {fc: ("m", None, None) for fc in D.FACES})],
          "Фонарь у подъезда", "Entrance lamp", light=13, color="WHITE", sound="GLASS", mount="WALL", ao=False)
    decor("vent_shaft", {"b": "brick", "c": "concrete"},
          [face_box((2, 0, 2), (14, 16, 14), {fc: ("b", None, "down" if fc == "down" else None) for fc in D.FACES})],
          "Вентшахта", "Vent stack", color="RED")
    decor("vent_cap", {"b": "brick", "l": "louvre", "c": "concrete"},
          [face_box((2, 0, 2), (14, 9, 14), {"north": ("l", None, None), "south": ("l", None, None), "east": ("l", None, None),
                                              "west": ("l", None, None)}),
           face_box((1, 9, 1), (15, 11, 15), {fc: ("c", None, None) for fc in D.FACES})],
          "Оголовок вентшахты", "Vent cap", color="RED")
    m = lambda f, t: face_box(f, t, {fc: ("m", None, None) for fc in D.FACES})  # noqa: E731
    decor("tv_antenna", {"m": "metal_grey"},
          [m((7.25, 0, 7.25), (8.75, 16, 8.75)), m((1, 12, 7.5), (15, 12.75, 8.5)), m((3, 8, 7.5), (13, 8.75, 8.5)),
           m((7.5, 12.75, 2), (8.5, 13.5, 14)), m((5, 4, 7.5), (11, 4.75, 8.5))],
          "Телеантенна", "TV aerial", boxes=[[7, 0, 7, 9, 16, 9]], color="LIGHT_GRAY", sound="METAL", layer="cutout")
    decor("entrance_plate", {"p": "entrance_plate", "m": "metal_dark"},
          [face_box((4, 4, 15.25), (12, 12, 16), {"north": ("p", [0, 0, 16, 16], None), "east": ("m", None, None), "west": ("m", None, None),
                                                 "up": ("m", None, None), "down": ("m", None, None)})],
          "Табличка подъезда", "Entrance plate", color="BLUE", sound="METAL", mount="WALL")
    decor("mailboxes", {"b": "mailboxes", "m": "metal_dark"},
          [face_box((1, 2, 12), (15, 14, 16), {"north": ("b", [0, 0, 16, 16], None), "east": ("m", None, None), "west": ("m", None, None),
                                                "up": ("m", None, None), "down": ("m", None, None)})],
          "Почтовые ящики", "Post boxes", color="BLUE", sound="METAL", mount="WALL")


BLOCKSTATES = {}
ITEMS = {}


def make_models():
    walls()
    facades()
    floors()
    windows()
    doors()
    steps()
    balconies()
    rails_and_roof()


# ---------------------------------------------------------------- writing

def write(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=1, ensure_ascii=False)
        f.write("\n")


def loot(name):
    if name.startswith("door_"):
        entry = {"type": "minecraft:item", "name": f"mobtalk:{name}", "conditions": [
            {"condition": "minecraft:block_state_property", "block": f"mobtalk:{name}", "properties": {"half": "lower"}}]}
        return {"type": "minecraft:block", "pools": [{"rolls": 1, "entries": [entry], "conditions": [{"condition": "minecraft:survives_explosion"}]}]}
    if name.startswith("step_"):
        return {"type": "minecraft:block", "pools": [{"rolls": 1, "entries": [{"type": "minecraft:item", "name": f"mobtalk:{name}", "functions": [
            {"function": "minecraft:set_count", "count": 2, "add": False, "conditions": [
                {"condition": "minecraft:block_state_property", "block": f"mobtalk:{name}", "properties": {"type": "double"}}]}]}]}]}
    return {"type": "minecraft:block", "pools": [{"rolls": 1, "entries": [{"type": "minecraft:item", "name": f"mobtalk:{name}"}],
                                                  "conditions": [{"condition": "minecraft:survives_explosion"}]}]}


def save_all():
    tdir = os.path.join(ASSETS, "textures", "block", "city2")
    os.makedirs(tdir, exist_ok=True)
    for name, img in TEX.items():
        img.save(os.path.join(tdir, name + ".png"))
    for name, m in MODELS.items():
        write(os.path.join(ASSETS, "models", "block", "city2", name + ".json"), m)
    for name, bs in BLOCKSTATES.items():
        write(os.path.join(ASSETS, "blockstates", name + ".json"), bs)
        item = dict(ITEMS[name])
        if "display" not in item and item.get("parent", "").startswith("mobtalk:"):
            pass
        write(os.path.join(ASSETS, "models", "item", name + ".json"), item)
        write(os.path.join(DATA, "loot_table", "blocks", name + ".json"), loot(name))
    for lang, i in (("ru_ru", 0), ("en_us", 1)):
        p = os.path.join(ASSETS, "lang", lang + ".json")
        with open(p, encoding="utf-8") as f:
            d = json.load(f)
        for name, titles in NAMES.items():
            d[f"block.mobtalk.{name}"] = titles[i]
        with open(p, "w", encoding="utf-8") as f:
            json.dump(d, f, indent=2, ensure_ascii=False)
            f.write("\n")
    tags = os.path.join(ROOT, "src", "main", "resources", "data", "minecraft", "tags", "block")
    doors = [f"mobtalk:{d[0]}" for d in DOORS]
    write(os.path.join(tags, "wooden_doors.json"), {"replace": False, "values": doors})
    pick = os.path.join(tags, "mineable", "pickaxe.json")
    have = json.load(open(pick)) if os.path.exists(pick) else {"replace": False, "values": []}
    add = [f"mobtalk:{n}" for n, _ in CUBES] + [f"mobtalk:{n}" for n in FACADES + WINDOWS + STEPS] + [f"mobtalk:wall_{r}" for r in ROWS]
    have["values"] = [v for v in have["values"] if v not in add] + add
    write(pick, have)


def write_java():
    def num(v):
        return f"{v:g}"
    def strs(xs):
        return ", ".join(f'"{x}"' for x in xs)
    decor_rows = []
    for name, mount, boxes, light, color, sound in DECOR:
        b = ", ".join("{" + ", ".join(num(v) for v in box) + "}" for box in boxes)
        decor_rows.append(f'            new CityContent.Spec("{name}", CityContent.Mount.{mount}, new double[][] {{{b}}}, {light}, '
                          f'MapColor.{color}, BlockSoundGroup.{sound}, true)')
    src = f"""package io.github.mobtalk.game;

import java.util.List;
import java.util.Map;
import net.minecraft.block.MapColor;
import net.minecraft.sound.BlockSoundGroup;

/** The house blocks of the rebuilt cities. Generated by tools/city2/blocks.py, do not edit by hand. */
final class CityBuildBlocks {{
    private CityBuildBlocks() {{ }}

    /** Room finishes, in the order of {{@link CityBuild.Finish}}. */
    static final List<String> FINISHES = List.of({strs(FINISHES)});
    static final Map<String, MapColor> CUBES = Map.ofEntries(
{(","+chr(10)).join(f'            Map.entry("{n}", MapColor.{c})' for n, c in CUBES)});
    static final List<String> FACADES = List.of({strs(FACADES)});
    static final List<String> DOORS = List.of({strs(d[0] for d in DOORS)});
    static final Map<String, MapColor> DOOR_COLORS = Map.ofEntries(
{(","+chr(10)).join(f'            Map.entry("{n}", MapColor.{c})' for n, c, _ in DOORS)});
    static final List<String> METAL_DOORS = List.of({strs(d[0] for d in DOORS if d[2] == "METAL")});
    static final List<String> WALLS = List.of({strs(f"wall_{r}" for r in ROWS)});
    static final List<String> WINDOWS = List.of({strs(WINDOWS)});
    static final List<String> STEPS = List.of({strs(STEPS)});
    static final List<String> TRANSLUCENT = List.of({strs(TRANSLUCENT)});
    static final List<String> CUTOUT = List.of({strs(CUTOUT)});
    static final List<CityContent.Spec> DECOR = List.of(
{(","+chr(10)).join(decor_rows)});
}}
"""
    with open(os.path.join(ROOT, "src", "main", "java", "io", "github", "mobtalk", "game", "CityBuildBlocks.java"), "w", encoding="utf-8") as f:
        f.write(src)


def main():
    make_textures()
    make_models()
    save_all()
    write_java()
    print("city2 blocks:", len(BLOCKSTATES), "blocks,", len(MODELS), "models,", len(TEX), "textures")


if __name__ == "__main__":
    main()
