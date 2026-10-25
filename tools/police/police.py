"""The city police's look: the policeman's uniform (a villager profession layer), the police post, the cell wall, the baton and the
handcuffs, and three police cars with a light bar that flashes blue and red in an alarm:

    car_police         a white Zhiguli (the VAZ-2107 that served Russia's traffic police for years): a blue stripe, «ПОЛИЦИЯ» on the doors
    car_police_uaz     the white UAZ «буханка» of a patrol (ППС): the stripe, «ПОЛИЦИЯ» under the windows
    car_militsiya      the Soviet militia's yellow Zhiguli: a blue stripe with «МИЛИЦИЯ» in white

    python3 -I tools/police/police.py                  # writes the assets, data and names
    python3 -I tools/police/police.py preview OUT.png  # also renders the models for a quick look

The cars reuse the city's car bodies (tools/city/decor.py: KINDS, car_atlas, box, the palette) and only add their own livery, so the
two stay alike; the blocks are registered in io.github.mobtalk.game.PoliceContent. Unlike the city's cars each side has its own
texture, so the words read the right way round on both sides.
"""
import json
import math
import os
import sys

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "city"))
import decor  # noqa: E402  (the city's helpers: textures, boxes, the palette, the preview renderer)
from decor import new, rect, shade, mix, noise_fill, bevel, rng, box, pal, pal_box, FACES, KINDS  # noqa: E402

ASSETS, DATA = decor.ASSETS, decor.DATA
TEX = {}      # name -> image, saved under textures/block/police
MODELS = {}   # name -> model json, under models/block/police
BLUE, NAVY, WHITE, RED = (36, 78, 168, 255), (30, 40, 70, 255), (238, 238, 234, 255), (196, 34, 34, 255)

# A 4x5 pixel font for the few Cyrillic words on the cars and the post.
FONT = {
    "П": ["1111", "1001", "1001", "1001", "1001"], "О": ["0110", "1001", "1001", "1001", "0110"],
    "Л": ["0111", "0101", "0101", "0101", "1001"], "И": ["1001", "1001", "1011", "1101", "1001"],
    "Ц": ["1010", "1010", "1010", "1111", "0001"], "Я": ["0111", "1001", "0111", "0101", "1001"],
    "М": ["1001", "1111", "1111", "1001", "1001"], "С": ["0111", "1000", "1000", "1000", "0111"],
    "Д": ["0110", "0110", "0110", "1111", "1001"], "0": ["0110", "1001", "1001", "1001", "0110"],
    "2": ["1110", "0001", "0110", "1000", "1111"],
}


def text(img, word, x, y, color, gap=1):
    px = img.load()
    for ch in word:
        for row, bits in enumerate(FONT[ch]):
            for col, bit in enumerate(bits):
                if bit == "1":
                    px[x + col, y + row] = color
        x += 4 + gap
    return x


def width(word, gap=1):
    return len(word) * (4 + gap) - gap


# ---------------------------------------------------------------- the cars

CARS = {
    "police": dict(kind="zhiguli", paint=(236, 236, 232), word="ПОЛИЦИЯ", stripe=BLUE, letters=BLUE, light="light"),
    "police_uaz": dict(kind="uaz", paint=(236, 236, 232), word="ПОЛИЦИЯ", stripe=BLUE, letters=BLUE, light="light"),
    "militsiya": dict(kind="zhiguli", paint=(230, 190, 44), word="МИЛИЦИЯ", stripe=BLUE, letters=WHITE, light="beacon"),
}


def side(img, x0, y0, spec, front_left):
    """One body side (48x14) at (x0, y0): paint, the door seams and handles, the wheel arches where the wheels are, the stripe and
    the word. `front_left`: the car's front is at the left of this face (the west face; the east face has it at the right)."""
    kind, k = spec["kind"], KINDS[spec["kind"]]
    b = spec["paint"] + (255,)
    r = rng("police-side" + kind + str(front_left))
    z0, z1, _ = k["body"]
    at = (lambda z: round((z - z0) / (z1 - z0) * 48)) if front_left else (lambda z: round((z1 - z) / (z1 - z0) * 48))
    noise_fill(img, b, 0.02, r, (x0, y0, x0 + 48, y0 + 14))
    px = img.load()
    for x in range(48):
        px[x0 + x, y0] = shade(b, 1.1); px[x0 + x, y0 + 13] = shade(b, 0.6)
    # the stripe: on the white cars a band low on the doors, on the militia's a wide band that carries the word
    rows = range(8, 11) if spec["letters"] != WHITE else range(4, 11)
    for x in range(48):
        for y in rows:
            px[x0 + x, y0 + y] = shade(spec["stripe"], 1.0 + r.uniform(-0.03, 0.03))
    if spec["letters"] != WHITE:
        for x in range(48):
            px[x0 + x, y0 + 11] = RED  # the thin red line under the blue (today's livery)
    doors = {"zhiguli": (-4, 13), "uaz": (-11, 2, 14)}[kind]  # door seams, in the model's z
    for z in doors:
        x = at(z)
        for y in range(0, 12):
            if 0 <= x < 48:
                px[x0 + x, y0 + y] = shade(px[x0 + x, y0 + y], 0.62)
    for z in doors[:-1] if kind == "zhiguli" else doors[:1]:  # handles just behind the front seams
        x = at(z + 3)
        if 0 <= x < 48:
            px[x0 + x, y0 + 3] = (205, 210, 214, 255)
    for w in k["wheels"]:
        cx = at(w)
        for x in range(cx - 5, cx + 5):
            for y in range(7, 14):
                if 0 <= x < 48 and math.hypot(x - cx + 0.5, y - 14) < 5.2:
                    px[x0 + x, y0 + y] = (20, 20, 22, 255)
    fx = at(z0 + 1.5)
    px[x0 + min(47, max(0, fx)), y0 + 3] = (240, 150, 30, 255)  # the indicator on the front wing
    if kind == "zhiguli":  # the word on the doors, between the wheel arches
        lo, hi = sorted((at(k["wheels"][0]), at(k["wheels"][1])))
        word = spec["word"]
        x = (lo + hi) // 2 - width(word) // 2
        y = 5 if spec["letters"] == WHITE else 2
        text(img, word, x0 + x, y0 + y, spec["letters"])


def van_side(img, x0, y0, spec, front_left):
    """The UAZ's upper side (40x12): the row of windows, the sliding door, «ПОЛИЦИЯ» on the white under the rear windows."""
    b = spec["paint"] + (255,)
    r = rng("police-van" + str(front_left))
    noise_fill(img, b, 0.02, r, (x0, y0, x0 + 40, y0 + 12))
    px = img.load()
    for x in range(40):
        px[x0 + x, y0] = shade(b, 1.12); px[x0 + x, y0 + 11] = shade(b, 0.75)
    glass_dark, glass_light = (60, 86, 102), (180, 208, 220)
    windows = ((1, 8), (10, 17), (19, 26), (28, 35))
    for a, z in windows:
        u0, u1 = (a, z) if front_left else (40 - z, 40 - a)
        for x in range(u0, u1):
            for y in range(1, 5):
                px[x0 + x, y0 + y] = mix(glass_dark, glass_light, 0.3 + 0.4 * math.sin((x - u0 + 6 - y) / 3))
    word = spec["word"]
    text(img, word, x0 + (40 - width(word)) // 2, y0 + 6, spec["letters"])


def car_atlas(name, spec):
    """128x64: the city's car atlas (decor.car_atlas, 64x64) at the left, then each body side drawn anew with the livery: the
    east one at (0,0), the west one at (64,0); for the van the upper sides at (0,50) and (64,50). The front gets a blue plate band."""
    base = decor.car_atlas("police_" + name, dict(kind=spec["kind"], paint=spec["paint"]))
    decor.TEX.pop("car_police_" + name, None)
    img = new(128, 64)
    img.paste(base, (0, 0))
    side(img, 0, 0, spec, front_left=False)
    side(img, 64, 0, spec, front_left=True)
    if spec["kind"] == "uaz":
        van_side(img, 0, 50, spec, front_left=False)
        van_side(img, 64, 50, spec, front_left=True)
    for x in range(18):  # the bonnet's edge in the stripe's blue
        img.putpixel((x, 32), shade(spec["stripe"], 1.0))
    TEX[f"car_{name}"] = img


def lightbar():
    """The light bar's lenses, 16x16: blue at the left, red at the right, a white middle. Off: dull glass. On: four frames, blue
    flash, both dim, red flash, both dim (animated, two ticks a frame)."""
    def frame(blue, red):
        img = new(16, 16)
        noise_fill(img, (70, 72, 76, 255), 0.03, rng("bar"), (0, 0, 16, 16))
        for (x0, x1, lit, colour, dim) in ((0, 7, blue, (90, 150, 255), (34, 50, 110)), (9, 16, red, (255, 70, 60), (110, 30, 30))):
            for x in range(x0, x1):
                for y in range(2, 14):
                    c = colour if lit else dim
                    t = 1.0 - 0.25 * abs(y - 7.5) / 6
                    if lit and abs(x - (x0 + x1) / 2) < 1.6 and 5 <= y <= 9:
                        img.putpixel((x, y), (255, 255, 255, 255))
                    else:
                        img.putpixel((x, y), shade(c + (255,), t))
        rect(img, (7, 2, 9, 14), (200, 202, 206, 255))
        return img
    TEX["lightbar_off"] = frame(False, False)
    strip = new(16, 64)
    for i, (b, r) in enumerate(((True, False), (False, False), (False, True), (False, False))):
        strip.paste(frame(b, r), (0, 16 * i))
    TEX["lightbar_on"] = strip


def cars():
    def uv(x0, y0, x1, y1):  # atlas pixels -> model uv (the atlas is 128 wide, 64 high)
        return [x0 / 8, y0 / 4, x1 / 8, y1 / 4]
    for name, spec in CARS.items():
        k, t = KINDS[spec["kind"]], "car"
        bz0, bz1, btop = k["body"]
        cz0, cz1, ctop = k["cab"]
        i = k["inset"]
        els = [box((-1, 2.5, bz0), (17, btop, bz1), {"east": (t, uv(0, 0, 48, 14)), "west": (t, uv(64, 0, 112, 14)),
                                                    "north": (t, uv(0, 32, 18, 40)), "south": (t, uv(20, 32, 38, 40)),
                                                    "up": (t, uv(44, 16, 60, 32)), "down": ("palette", pal("black"))})]
        if spec["kind"] == "uaz":
            els.append(box((-1 + i, btop, cz0), (17 - i, ctop, cz1), {"east": (t, uv(0, 50, 40, 62)), "west": (t, uv(64, 50, 104, 62)),
                                                                    "north": (t, uv(0, 42, 16, 49)), "south": (t, uv(20, 42, 36, 49)),
                                                                    "up": (t, uv(44, 40, 60, 60))}))
            roof = ctop
        else:
            els.append(box((i, btop, cz0), (16 - i, ctop, cz1), {"east": (t, uv(0, 16, 20, 23)), "west": (t, uv(20, 16, 0, 23)),
                                                              "north": (t, uv(0, 42, 16, 49)), "south": (t, uv(20, 42, 36, 49)),
                                                              "up": (t, uv(44, 40, 60, 60))}))
            els.append(box((i + 0.3, ctop, cz0 + 0.5), (16 - i - 0.3, ctop + 0.4, cz1 - 0.5), {"up": (t, uv(44, 40, 60, 60))}))
            roof = ctop + 0.4
        bump = "chrome" if spec["kind"] != "uaz" else "black"
        els += [pal_box((-1.5, 2, bz0 - 1), (17.5, 3.5, bz0), bump), pal_box((-1.5, 2, bz1), (17.5, 3.5, bz1 + 1), bump)]
        # the light bar across the roof, on two short feet; the Soviet militia had one blue beacon instead
        mid = cz0 + (4 if spec["kind"] == "uaz" else 3)
        lens = {f: ("light", [0, 2, 16, 14]) for f in ("north", "south")}
        lens.update({"east": ("light", [9, 2, 16, 14]), "west": ("light", [0, 2, 7, 14]), "up": ("light", [0, 2, 16, 14]),
                     "down": ("palette", pal("black"))})
        if spec["light"] == "light":
            els.append(pal_box((4, roof, mid - 0.5), (5, roof + 0.5, mid + 1.5), "black"))
            els.append(pal_box((11, roof, mid - 0.5), (12, roof + 0.5, mid + 1.5), "black"))
            els.append(box((2.5, roof + 0.5, mid - 1), (13.5, roof + 2.25, mid + 2), lens))
        else:
            els.append(box((6.75, roof, mid - 0.25), (9.25, roof + 2.5, mid + 2.25),
                           {f: ("light", [0, 2, 7, 14]) for f in ("north", "south", "east", "west", "up")}))
        for wz in k["wheels"]:
            for x, faces in ((-1.5, ["west", "north", "south", "down", "up"]), (15.5, ["east", "north", "south", "down", "up"])):
                els.append(box((x, 0, wz - 3), (x + 2, 6, wz + 3), {f: (t, uv(40, 32, 46, 38)) if f in ("east", "west") else ("palette", pal("rubber"))
                                                                    for f in faces}))
        for lit in (False, True):
            MODELS[f"car_{name}" + ("_lit" if lit else "")] = {
                "parent": "minecraft:block/block",
                "textures": {"car": f"mobtalk:block/police/car_{name}", "palette": "mobtalk:block/city/palette",
                             "light": "mobtalk:block/police/lightbar_" + ("on" if lit else "off"),
                             "particle": f"mobtalk:block/police/car_{name}"},
                "elements": els}


# ---------------------------------------------------------------- the post, the cell, the tools

def post():
    """The police post (the policeman's job site): a navy cabinet with «02» on a white plate and a blue beacon on top."""
    s = new()
    noise_fill(s, NAVY, 0.05, rng("post"), (0, 0, 16, 16))
    bevel(s, (0, 0, 16, 16), 1.25, 0.7)
    rect(s, (2, 2, 14, 9), WHITE)
    bevel(s, (2, 2, 14, 9), 1.0, 0.82)
    text(s, "02", 3, 3, BLUE, gap=2)
    rect(s, (2, 11, 14, 12), (200, 30, 30, 255))
    for x in range(3, 14, 2):
        s.putpixel((x, 13), shade(NAVY, 1.4))
    TEX["post_side"] = s
    top = new()
    noise_fill(top, NAVY, 0.05, rng("posttop"), (0, 0, 16, 16))
    bevel(top, (0, 0, 16, 16), 1.25, 0.7)
    TEX["post_top"] = top
    MODELS["police_post"] = {"parent": "minecraft:block/block", "textures": {
        "side": "mobtalk:block/police/post_side", "top": "mobtalk:block/police/post_top", "light": "mobtalk:block/police/lightbar_off",
        "particle": "mobtalk:block/police/post_side"}, "elements": [
        box((0, 0, 0), (16, 14, 16), {"north": ("side", [0, 2, 16, 16]), "south": ("side", [0, 2, 16, 16]), "east": ("side", [0, 2, 16, 16]),
                                      "west": ("side", [0, 2, 16, 16]), "up": ("top", [0, 0, 16, 16]), "down": ("top", [0, 0, 16, 16])}),
        box((6, 14, 6), (10, 16, 10), {f: ("light", [0, 3, 7, 11]) for f in ("north", "south", "east", "west", "up")})]}


def jail_wall():
    """The cell's wall: the institution's paint, dark blue to the shoulder, whitewash above, a thin line between."""
    img = new()
    r = rng("jail")
    noise_fill(img, (214, 212, 204, 255), 0.05, r, (0, 0, 16, 6))
    noise_fill(img, (52, 74, 104, 255), 0.06, r, (0, 7, 16, 16))
    rect(img, (0, 6, 16, 7), (36, 48, 70, 255))
    for _ in range(6):  # scratches and chips
        x, y = r.randrange(16), r.randrange(7, 16)
        img.putpixel((x, y), shade(img.getpixel((x, y)), 1.35))
    TEX["jail_wall"] = img


def item_textures():
    """The rubber baton (ПР-73: a black stick with a guard ring above the grip) and a pair of handcuffs, diagonal like vanilla tools."""
    baton = new()
    black, edge, grip = (34, 34, 36, 255), (78, 80, 86, 255), (58, 50, 44, 255)
    for i in range(2, 14):
        x, y = i, 15 - i
        baton.putpixel((x, y), black)
        baton.putpixel((x + 1, y), edge if i > 4 else grip)
    for i in range(2, 5):  # the grip, a little thicker
        baton.putpixel((i, 14 - i), grip)
    baton.putpixel((4, 10), (120, 122, 128, 255)); baton.putpixel((5, 11), (120, 122, 128, 255)); baton.putpixel((6, 10), (120, 122, 128, 255))
    baton.putpixel((14, 2), edge)
    cuffs = new()
    steel, dark = (196, 200, 208, 255), (96, 100, 108, 255)
    for cx, cy in ((5, 10), (11, 6)):
        for a in range(0, 360, 8):
            x = int(round(cx + 3.2 * math.cos(math.radians(a))))
            y = int(round(cy + 3.2 * math.sin(math.radians(a))))
            cuffs.putpixel((x, y), steel if a < 200 else dark)
    for x, y in ((7, 8), (8, 8), (9, 7)):
        cuffs.putpixel((x, y), dark)
    return {"police_baton": baton, "handcuffs": cuffs}


def uniform():
    """The policeman's clothes over the villager (a 64x64 profession layer): a dark blue peaked cap with a red band and a gold
    badge, a navy jacket with red-piped shoulder straps, a light blue collar, a black belt with a silver buckle, a badge on the
    chest and navy sleeves. The villager model: hat 32,0 (8x10x8), robe 0,38 (8x20x6), arms 44,22 and 40,38, body 16,20."""
    img = new(64, 64)
    px = img.load()
    r = rng("uniform")
    cap, band, peak = (34, 46, 82, 255), (190, 30, 34, 255), (20, 20, 24, 255)
    jacket = (40, 54, 92, 255)

    def fill(x0, y0, x1, y1, c, amount=0.05):
        for y in range(y0, y1):
            for x in range(x0, x1):
                px[x, y] = shade(c, 1 + r.uniform(-amount, amount))
    # the cap on the hat layer: the crown on top, three rows of the sides (crown, crown, band), the peak at the front below the band
    fill(40, 0, 48, 8, cap)
    for x in range(40, 48):
        px[x, 0] = shade(cap, 1.15); px[x, 7] = shade(cap, 0.85)
    for x0 in (32, 40, 48, 56):
        fill(x0, 8, x0 + 8, 10, cap)
        fill(x0, 10, x0 + 8, 11, band, 0.03)
    fill(40, 11, 48, 12, peak, 0.02)           # the peak's shadow over the brow
    px[43, 9] = (230, 190, 70, 255); px[44, 9] = (230, 190, 70, 255)  # the cockade
    px[43, 8] = (250, 220, 120, 255)
    # the robe: navy all over
    fill(6, 38, 14, 44, jacket)                 # top
    for x0, w in ((0, 6), (6, 8), (14, 6), (20, 8)):
        fill(x0, 44, x0 + w, 64, jacket)
    for x in range(6, 14):                      # front: the collar, the zip, the belt, the pockets, the badge
        px[x, 44] = (150, 186, 220, 255)
    px[9, 45] = (30, 34, 44, 255); px[10, 45] = (30, 34, 44, 255)  # the tie's knot
    for y in range(46, 64):
        px[10, y] = shade(jacket, 0.72)
    for x0, w in ((0, 6), (6, 8), (14, 6), (20, 8)):
        for x in range(x0, x0 + w):
            px[x, 55] = (22, 22, 24, 255)
    px[9, 55] = (200, 204, 210, 255); px[10, 55] = (200, 204, 210, 255)
    px[7, 47] = (214, 186, 80, 255); px[7, 48] = (190, 160, 60, 255)  # the badge (жетон)
    for x in (7, 8, 11, 12):
        px[x, 51] = shade(jacket, 0.75)
    for x0 in (20,):                            # the back: «ПОЛИЦИЯ» cannot fit, a reflective strip can
        for x in range(x0, x0 + 8):
            px[x, 49] = (200, 206, 196, 255)
    # the shoulder straps on the robe's top, red-piped
    for x in (6, 7, 12, 13):
        for y in range(39, 43):
            px[x, y] = shade(jacket, 1.12)
        px[x, 38] = band; px[x, 43] = band
    # the sleeves (arms) and the crossed arms
    fill(44, 22, 60, 34, jacket)
    fill(40, 38, 64, 46, jacket)
    for x in range(46, 50):                     # the hands stay bare
        for y in range(42, 46):
            px[x, y] = (0, 0, 0, 0)
    for x in range(44, 60):
        px[x, 33] = shade(jacket, 0.7)
    return img


# ---------------------------------------------------------------- writing

NAMES = {
    "police_post": ("Пост полиции", "Police post"),
    "jail_wall": ("Стена изолятора", "Holding cell wall"),
    "car_police": ("Жигули «Полиция»", "Zhiguli police car"),
    "car_police_uaz": ("УАЗ «буханка» ППС", "UAZ police van"),
    "car_militsiya": ("Жигули «Милиция»", "Zhiguli militia car"),
}
ITEMS = {"police_baton": ("Резиновая дубинка", "Rubber baton"), "handcuffs": ("Наручники", "Handcuffs")}


def write(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2, ensure_ascii=False)
        f.write("\n")


def loot(name, drops=True):
    write(os.path.join(DATA, "loot_table", "blocks", name + ".json"), {"type": "minecraft:block", "pools": [{"rolls": 1, "entries": [
        {"type": "minecraft:item", "name": f"mobtalk:{name}"}], "conditions": [{"condition": "minecraft:survives_explosion"}]}]} if drops
        else {"type": "minecraft:block", "pools": []})


def save_all():
    tdir = os.path.join(ASSETS, "textures", "block", "police")
    os.makedirs(tdir, exist_ok=True)
    for name, img in TEX.items():
        img.save(os.path.join(tdir, name + ".png"))
    write(os.path.join(tdir, "lightbar_on.png.mcmeta"), {"animation": {"frametime": 2, "interpolate": False}})
    for name, img in item_textures().items():
        os.makedirs(os.path.join(ASSETS, "textures", "item"), exist_ok=True)
        img.save(os.path.join(ASSETS, "textures", "item", name + ".png"))
        write(os.path.join(ASSETS, "models", "item", name + ".json"),
              {"parent": "minecraft:item/handheld" if name == "police_baton" else "minecraft:item/generated",
               "textures": {"layer0": f"mobtalk:item/{name}"}})
    pdir = os.path.join(ASSETS, "textures", "entity", "villager", "profession")
    uniform().save(os.path.join(pdir, "police.png"))
    write(os.path.join(pdir, "police.png.mcmeta"), {"villager": {"hat": "full"}})
    for name, m in MODELS.items():
        write(os.path.join(ASSETS, "models", "block", "police", name + ".json"), m)
    turns = (("north", 0), ("east", 90), ("south", 180), ("west", 270))
    for name in CARS:
        car = f"car_{name}"
        write(os.path.join(ASSETS, "blockstates", car + ".json"), {"variants": {
            f"facing={f},lit={str(lit).lower()}": dict({"model": f"mobtalk:block/police/{car}" + ("_lit" if lit else "")}, **({"y": y} if y else {}))
            for f, y in turns for lit in (False, True)}})
        write(os.path.join(ASSETS, "models", "item", car + ".json"), {"parent": f"mobtalk:block/police/{car}", "display": decor.SMALL})
        loot(car)
    write(os.path.join(ASSETS, "blockstates", "police_post.json"), {"variants": {"": {"model": "mobtalk:block/police/police_post"}}})
    write(os.path.join(ASSETS, "models", "item", "police_post.json"), {"parent": "mobtalk:block/police/police_post"})
    loot("police_post")
    write(os.path.join(ASSETS, "models", "block", "police", "jail_wall.json"),
          {"parent": "minecraft:block/cube_all", "textures": {"all": "mobtalk:block/police/jail_wall"}})
    write(os.path.join(ASSETS, "blockstates", "jail_wall.json"), {"variants": {"": {"model": "mobtalk:block/police/jail_wall"}}})
    write(os.path.join(ASSETS, "models", "item", "jail_wall.json"), {"parent": "mobtalk:block/police/jail_wall"})
    loot("jail_wall", drops=False)
    for lang, i in (("ru_ru", 0), ("en_us", 1)):
        p = os.path.join(ASSETS, "lang", lang + ".json")
        with open(p, encoding="utf-8") as f:
            d = json.load(f)
        for name, titles in NAMES.items():
            d[f"block.mobtalk.{name}"] = titles[i]
        for name, titles in ITEMS.items():
            d[f"item.mobtalk.{name}"] = titles[i]
        with open(p, "w", encoding="utf-8") as f:
            json.dump(d, f, indent=2, ensure_ascii=False)
            f.write("\n")


def preview(out):
    """Every model from two sides (the city's renderer, which reads the textures by their last path part)."""
    decor.TEX.update(TEX)
    cell = 256
    names = list(MODELS)
    sheet = Image.new("RGBA", (cell * 4, cell * ((len(names) + 1) // 2)), (30, 30, 34, 255))
    for i, n in enumerate(names):
        for k, yaw in enumerate((35, 215)):
            sheet.paste(decor.render(MODELS[n], yaw, cell), ((i % 2) * 2 * cell + k * cell, (i // 2) * cell))
    sheet.save(out)


def main():
    decor.PAL.update(decor.palette())
    for name, spec in CARS.items():
        car_atlas(name, spec)
    lightbar()
    post()
    jail_wall()
    cars()
    save_all()
    if len(sys.argv) > 2 and sys.argv[1] == "preview":
        preview(sys.argv[2])
    print("police:", len(MODELS), "models,", len(TEX), "textures")


if __name__ == "__main__":
    main()
