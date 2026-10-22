"""Generates the cemetery blocks' textures, models, blockstates, item models, loot tables, tool tags and recipes into
src/main/resources. Everything is drawn here from scratch (seeded noise and hand-placed pixels), no game assets are copied.
Run from the repository root: python scripts/cemetery/assets.py"""
import json
import math
import os
import random

from PIL import Image, ImageDraw

ROOT = os.path.join(os.path.dirname(__file__), "..", "..", "src", "main", "resources")
ASSETS = os.path.join(ROOT, "assets", "divineblock")
DATA = os.path.join(ROOT, "data")
NS = "divineblock"


def save_json(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(obj, f, ensure_ascii=False, indent=2)
        f.write("\n")


# ---------------------------------------------------------------- textures

def clamp(v): return max(0, min(255, int(round(v))))


def shade(c, k):
    return tuple(clamp(x * k) for x in c[:3]) + ((c[3],) if len(c) == 4 else (255,))


def mix(a, b, t):
    return tuple(clamp(a[i] * (1 - t) + b[i] * t) for i in range(3)) + (255,)


def hexc(h):
    h = h.lstrip("#"); return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), 255)


def new(): return Image.new("RGBA", (16, 16), (0, 0, 0, 0))


def noise(base, spread, seed, img=None):
    rnd = random.Random(seed); img = img or new()
    for y in range(16):
        for x in range(16):
            img.putpixel((x, y), shade(base, 1 + rnd.uniform(-spread, spread)))
    return img


def wood(base, seed, weathered=False):
    """Vertical grain: each column has its own tone, dark grain lines wander down, a knot or two."""
    rnd = random.Random(seed); img = new()
    cols = [1 + rnd.uniform(-0.08, 0.08) for _ in range(16)]
    for x in range(16):
        for y in range(16):
            k = cols[x] + rnd.uniform(-0.05, 0.05)
            img.putpixel((x, y), shade(base, k))
    for _ in range(4):
        x = rnd.randrange(16)
        for y in range(16):
            if rnd.random() < 0.8: img.putpixel((x, y), shade(base, 0.72 + rnd.uniform(-0.04, 0.04)))
            if rnd.random() < 0.15: x = (x + rnd.choice((-1, 1))) % 16
    kx, ky = rnd.randrange(2, 14), rnd.randrange(2, 14)
    for dx, dy in ((0, 0), (1, 0), (0, 1), (1, 1)):
        img.putpixel((kx + dx, ky + dy), shade(base, 0.62))
    if weathered:
        for _ in range(10):
            x, y = rnd.randrange(16), rnd.randrange(16)
            img.putpixel((x, y), mix(img.getpixel((x, y)), hexc("#8f9a58"), 0.55))  # lichen
        for _ in range(3):
            x = rnd.randrange(16); y0 = rnd.randrange(10)
            for y in range(y0, min(16, y0 + rnd.randrange(3, 7))): img.putpixel((x, y), shade(base, 0.45))  # cracks
    return img


def painted(base, seed, chips=None, rust=0, sheen=False):
    rnd = random.Random(seed); img = noise(base, 0.05, seed)
    if sheen:
        for y in range(16):
            for x in range(16):
                if (x * 7 + y * 3) % 23 == 0: img.putpixel((x, y), shade(img.getpixel((x, y)), 1.12))
    for _ in range(rust):
        x, y = rnd.randrange(16), rnd.randrange(16)
        img.putpixel((x, y), hexc(rnd.choice(["#7a3e1d", "#8b4a22", "#5e2f17"])))
    if chips:
        for _ in range(6):
            x, y = rnd.randrange(16), rnd.randrange(16)
            img.putpixel((x, y), chips)
    return img


def speckled(base, seed, flecks, amount):
    rnd = random.Random(seed); img = noise(base, 0.06, seed)
    for _ in range(amount):
        x, y = rnd.randrange(16), rnd.randrange(16)
        img.putpixel((x, y), hexc(rnd.choice(flecks)))
    return img


def marble(seed):
    rnd = random.Random(seed); img = noise(hexc("#e7e5df"), 0.025, seed)
    for _ in range(3):
        x, y = rnd.uniform(0, 16), 0.0
        dx = rnd.uniform(-0.8, 0.8)
        while y < 16:
            px, py = int(x) % 16, int(y)
            img.putpixel((px, py), mix(img.getpixel((px, py)), hexc("#9ea3a8"), rnd.uniform(0.35, 0.6)))
            x += dx + rnd.uniform(-0.5, 0.5); y += 1
    return img


def soil(seed, grass=0.0):
    rnd = random.Random(seed); img = new()
    tones = [hexc("#4b3423"), hexc("#3f2c1e"), hexc("#5a4030"), hexc("#35261a")]
    for y in range(16):
        for x in range(16):
            img.putpixel((x, y), shade(rnd.choice(tones), rnd.uniform(0.92, 1.06)))
    for _ in range(7):
        x, y = rnd.randrange(16), rnd.randrange(16)
        img.putpixel((x, y), hexc(rnd.choice(["#77706a", "#8a837a", "#5f5953"])))  # pebbles
    if grass:
        greens = [hexc("#5d8a35"), hexc("#4d7a2b"), hexc("#6f9a3f"), hexc("#3f6b25")]
        for y in range(16):
            for x in range(16):
                if rnd.random() < grass: img.putpixel((x, y), rnd.choice(greens))
    return img


def gravel_path(seed):
    rnd = random.Random(seed); img = new()
    for y in range(16):
        for x in range(16):
            img.putpixel((x, y), hexc(rnd.choice(["#8d8781", "#7b756f", "#a29c95", "#6c6661", "#958f86"])))
    return img


def star():
    """A five-pointed red star with a darker rim and a lit upper half, on transparency."""
    big = Image.new("RGBA", (128, 128), (0, 0, 0, 0)); d = ImageDraw.Draw(big)
    cx, cy, ro, ri = 64, 68, 62, 25
    pts = []
    for i in range(10):
        a = -math.pi / 2 + i * math.pi / 5; r = ro if i % 2 == 0 else ri
        pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    d.polygon(pts, fill=(150, 18, 20, 255))
    img = big.resize((16, 16), Image.Resampling.BOX)
    out = new()
    for y in range(16):
        for x in range(16):
            r, g, b, a = img.getpixel((x, y))
            if a < 110: continue
            k = 1.25 if x + y < 15 else 0.95
            edge = any(img.getpixel((x + dx, y + dy))[3] < 110 for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))
                       if 0 <= x + dx < 16 and 0 <= y + dy < 16)
            out.putpixel((x, y), shade((200, 28, 26, 255), 0.7 if edge else k))
    return out


def portrait(img, x0, y0, w, h, frame, seed):
    """An oval enamel photo: a sepia face and shoulders inside a thin metal frame."""
    rnd = random.Random(seed)
    cx, cy = x0 + (w - 1) / 2, y0 + (h - 1) / 2
    for y in range(y0, y0 + h):
        for x in range(x0, x0 + w):
            e = ((x - cx) / (w / 2)) ** 2 + ((y - cy) / (h / 2)) ** 2
            if e > 1.05: continue
            if e > 0.78: img.putpixel((x, y), frame); continue
            fy = (y - y0) / h; fx = abs(x - cx) / (w / 2)
            col = hexc("#d8c7a4")  # background
            if 0.2 < fy < 0.64 and fx < 0.5: col = hexc("#c49a72")  # face
            if fy <= 0.24 and fx < 0.62: col = hexc("#3e3026")  # hair
            if fy >= 0.66: col = hexc("#3c3128")  # shoulders
            img.putpixel((x, y), shade(col, rnd.uniform(0.95, 1.05)))


def engraved_lines(img, rows, color, seed, x0=5, x1=11):
    rnd = random.Random(seed)
    for y, a, b in rows:
        for x in range(a, b):
            if rnd.random() < 0.82: img.putpixel((x, y), shade(color, rnd.uniform(0.85, 1.1)))


def wreath():
    rnd = random.Random(7); img = new()
    greens = [hexc("#2f5a2a"), hexc("#3b6d32"), hexc("#264b23"), hexc("#4a7d3a")]
    for y in range(16):
        for x in range(16):
            r = math.hypot(x - 7.5, y - 7.0)
            if 3.4 <= r <= 7.3 and rnd.random() < 0.93: img.putpixel((x, y), rnd.choice(greens))
    flowers = [hexc("#d12a2a"), hexc("#f2f0ea"), hexc("#e66aa0"), hexc("#d12a2a"), hexc("#f6d33c"), hexc("#f2f0ea"), hexc("#b01e3a")]
    for i, col in enumerate(flowers):
        a = -math.pi / 2 + i * 2 * math.pi / len(flowers) + 0.3
        fx, fy = 7.5 + 5.3 * math.cos(a), 7.0 + 5.3 * math.sin(a)
        for dx, dy in ((0, 0), (1, 0), (0, 1), (1, 1)):
            x, y = int(fx + dx - 0.5), int(fy + dy - 0.5)
            if 0 <= x < 16 and 0 <= y < 16: img.putpixel((x, y), shade(col, 0.82 if (dx, dy) == (1, 1) else 1.0))
    for x, y0 in ((6, 12), (9, 12)):  # black mourning ribbons with a gold edge
        for y in range(y0, 16):
            xx = x + (1 if y > 13 and x == 9 else 0) - (1 if y > 13 and x == 6 else 0)
            img.putpixel((xx, y), hexc("#151515"));
            if y == 15: img.putpixel((xx, y), hexc("#b8963c"))
    return img


def plastic_flowers():
    rnd = random.Random(11); img = new()
    stems = [(4, 6), (6, 4), (8, 5), (10, 4), (12, 6), (7, 7), (9, 8)]
    heads = [hexc("#e8202c"), hexc("#ff5fa2"), hexc("#f4f4f4"), hexc("#ffd21f"), hexc("#e8202c"), hexc("#3f7fe0"), hexc("#ff5fa2")]
    for (x, top), col in zip(stems, heads):
        for y in range(top + 2, 15):
            img.putpixel((x, y), hexc("#2f8a2f" if y % 3 else "#246f24"))
        if top + 5 < 14: img.putpixel((x + 1, top + 5), hexc("#3da33d"))  # leaf
        for dx, dy in ((0, 0), (-1, 1), (1, 1), (0, 1), (0, 2)):
            px, py = x + dx, top + dy
            if 0 <= px < 16: img.putpixel((px, py), shade(col, 1.0 if dy < 2 else 0.8))
    return img


def tufts():
    rnd = random.Random(5); img = new()
    greens = [hexc("#5d8a35"), hexc("#4d7a2b"), hexc("#6f9a3f"), hexc("#7fa84a")]
    for x in range(16):
        if rnd.random() < 0.7:
            h = rnd.randrange(3, 9); lean = rnd.choice((-1, 0, 1))
            for i in range(h):
                px = x + (lean if i > h // 2 else 0)
                if 0 <= px < 16: img.putpixel((px, 15 - i), rnd.choice(greens))
    return img


def lampada_glass():
    img = noise(hexc("#a3141c"), 0.06, 21)
    for y in range(16):
        for x in range(16):
            if 6 <= x <= 9 and 9 <= y <= 13: img.putpixel((x, y), hexc("#e0562a"))
            if 7 <= x <= 8 and 10 <= y <= 12: img.putpixel((x, y), hexc("#ffb347"))
            if x == 4 and 3 <= y <= 12: img.putpixel((x, y), hexc("#d9535a"))  # highlight
    return img


def rusty(seed):
    rnd = random.Random(seed); img = new()
    for y in range(16):
        for x in range(16):
            roll = rnd.random()
            c = hexc("#2a2524") if roll < 0.35 else hexc(rnd.choice(["#7a3e1d", "#8b4a22", "#5e2f17", "#9a5a2a"]))
            img.putpixel((x, y), shade(c, rnd.uniform(0.9, 1.08)))
    return img


def stone_bark(seed):
    rnd = random.Random(seed); img = new()
    for x in range(16):
        tone = rnd.uniform(0.9, 1.08)
        for y in range(16):
            img.putpixel((x, y), shade(hexc("#88857d"), tone * rnd.uniform(0.95, 1.04)))
    for x in (2, 7, 12):
        for y in range(16):
            if rnd.random() < 0.85: img.putpixel((x, y), hexc("#5f5c56"))
    for _ in range(9):
        x, y = rnd.randrange(16), rnd.randrange(16)
        img.putpixel((x, y), hexc(rnd.choice(["#5d6b47", "#6f7f4f"])))
    return img


def sector_sign():
    img = noise(hexc("#2f62b8"), 0.04, 25)
    for x in range(16):
        for y in (0, 15): img.putpixel((x, y), hexc("#e6e6e6"))
    for y in range(16):
        for x in (0, 15): img.putpixel((x, y), hexc("#e6e6e6"))
    for ox in (4, 10):  # "1 1"
        for y in range(5, 12): img.putpixel((ox, y), hexc("#f2f2f2"))
        img.putpixel((ox - 1, 6), hexc("#f2f2f2"))
        for x in (ox - 1, ox, ox + 1): img.putpixel((x, 11), hexc("#f2f2f2"))
    return img


def bin_blue(seed):
    img = painted(hexc("#2e6fc4"), seed, chips=hexc("#1d3f70"), rust=2)
    for x in range(16):
        for y in (1, 14): img.putpixel((x, y), hexc("#255aa0"))
    return img


def portrait_engraving(seed):
    """Black granite with a portrait engraved by hand in pale grey, the way the modern stones are: the face drawn in fine
    lines and stippled shade, the shoulders below, two lines of name and dates under it."""
    rnd = random.Random(seed); img = speckled(hexc("#141416"), seed, ["#2c2c31", "#3a3a40"], 18)
    light, mid = hexc("#c9c7c0"), hexc("#7d7b76")
    for y in range(1, 11):
        for x in range(3, 13):
            fx, fy = (x - 7.5) / 3.2, (y - 5.0) / 3.9
            e = fx * fx + fy * fy
            if e <= 1.0:
                if y <= 2 or (y <= 3 and abs(x - 7.5) > 1.5): img.putpixel((x, y), shade(light, rnd.uniform(0.85, 1.0)))  # hair
                elif abs(x - 7.5) > 2.2 and rnd.random() < 0.6: img.putpixel((x, y), mid)  # shade at the cheeks
                elif rnd.random() < 0.18: img.putpixel((x, y), mid)  # stipple
            elif e <= 1.35 and y < 9: img.putpixel((x, y), shade(mid, 0.8))
    for x in (6, 9): img.putpixel((x, 5), light)  # eyes
    img.putpixel((7, 7), mid); img.putpixel((8, 7), mid)
    for x in range(6, 10): img.putpixel((x, 8), shade(light, 0.8))  # mouth
    for y in range(10, 13):  # shoulders
        for x in range(2 + (12 - y), 14 - (12 - y)):
            if rnd.random() < 0.7: img.putpixel((x, y), shade(mid, rnd.uniform(0.85, 1.1)))
    engraved_lines(img, [(13, 3, 13), (14, 5, 11)], light, seed + 1)
    return img


def icon_board():
    """A small icon under the roof of a golubets: a gilded halo and a dark figure on ochre."""
    img = noise(hexc("#c49a4a"), 0.05, 41)
    for x in range(16):
        for y in (0, 15): img.putpixel((x, y), hexc("#6b4a26"))
    for y in range(16):
        for x in (0, 15): img.putpixel((x, y), hexc("#6b4a26"))
    for y in range(3, 8):
        for x in range(5, 11):
            if (x - 7.5) ** 2 + (y - 5) ** 2 <= 6.5: img.putpixel((x, y), hexc("#f0cd5a"))
    for y in range(4, 7):
        for x in range(6, 10): img.putpixel((x, y), hexc("#b77e58"))
    for y in range(7, 14):
        for x in range(4 + max(0, 9 - y) // 2, 12 - max(0, 9 - y) // 2): img.putpixel((x, y), hexc("#7a2a24" if x < 8 else "#5f1f1b"))
    return img


def carnations():
    """Red carnations, a bunch laid on the grave: frilled heads on long stems."""
    rnd = random.Random(43); img = new()
    for x, top in ((3, 3), (5, 2), (7, 4), (9, 2), (11, 3), (13, 5), (6, 6), (10, 6)):
        for y in range(top + 2, 16):
            img.putpixel((x, y), hexc("#3f7a34" if y % 4 else "#2f6128"))
        for dx, dy in ((0, 0), (-1, 0), (1, 0), (0, 1), (-1, 1), (1, 1), (0, -1)):
            px, py = x + dx, top + dy
            if 0 <= px < 16 and 0 <= py < 16: img.putpixel((px, py), shade(hexc("#c3162a"), rnd.uniform(0.8, 1.15)))
    return img


def fir_wreath():
    """A wreath of fir with red and white ribbons across it."""
    rnd = random.Random(44); img = new()
    greens = [hexc("#1f4528"), hexc("#2a5a33"), hexc("#18381f"), hexc("#356b3c")]
    for y in range(16):
        for x in range(16):
            r = math.hypot(x - 7.5, y - 7.5)
            if 3.0 <= r <= 7.6 and rnd.random() < 0.95: img.putpixel((x, y), rnd.choice(greens))
    for i in range(16):  # a red ribbon across, a white one beside it
        for x, y, col in ((i, i, "#b3141f"), (i + 1, i, "#e8e4da")):
            if 0 <= x < 16 and 3.0 <= math.hypot(x - 7.5, y - 7.5) <= 7.6: img.putpixel((x, y), hexc(col))
    for x, y in ((4, 3), (11, 4), (3, 10), (12, 11), (7, 13)):  # cones
        img.putpixel((x, y), hexc("#6b4524")); img.putpixel((x, y + 1), hexc("#563619"))
    return img


def write_textures():
    T = {}
    T["grave_wood"] = wood(hexc("#a8693a"), 1)
    T["grave_wood_bar"] = T["grave_wood"].transpose(Image.Transpose.ROTATE_90)
    T["old_grave_wood"] = wood(hexc("#7b7468"), 2, weathered=True)
    T["old_grave_wood_bar"] = T["old_grave_wood"].transpose(Image.Transpose.ROTATE_90)
    T["painted_iron_black"] = painted(hexc("#202125"), 3, chips=hexc("#3a3b40"), rust=3)
    T["painted_iron_blue"] = painted(hexc("#5d9bd3"), 4, chips=hexc("#2f4d66"), rust=4)
    T["painted_iron_silver"] = painted(hexc("#b6bcc2"), 5, chips=hexc("#80868c"), rust=2)
    T["painted_gold"] = painted(hexc("#c19a3e"), 6, sheen=True)
    T["red_star"] = star()
    T["black_granite"] = speckled(hexc("#19191b"), 7, ["#3d3d42", "#55555b", "#2a2a2e"], 26)
    T["gray_granite"] = speckled(hexc("#6d6b6a"), 8, ["#4a4847", "#8f8c89", "#3a3837", "#a19c98"], 60)
    front = T["black_granite"].copy()
    portrait(front, 5, 3, 6, 6, hexc("#c9a548"), 9)
    engraved_lines(front, [(10, 5, 11), (11, 6, 10)], hexc("#d6d2c4"), 10)
    for x, y in ((8, 12), (8, 13), (8, 14), (7, 13), (9, 13)): front.putpixel((x, y), hexc("#cfc9b8"))  # engraved candle
    front.putpixel((8, 11), hexc("#e8c15a"))
    T["granite_stele_front"] = front
    T["marble"] = marble(12)
    mfront = T["marble"].copy()
    for x, y in ((8, 6), (8, 7), (8, 8), (8, 9), (8, 10), (8, 11), (7, 8), (9, 8), (6, 8), (10, 8), (9, 10), (7, 11)):  # small cross
        mfront.putpixel((x, y), hexc("#8a8f95"))
    engraved_lines(mfront, [(13, 6, 10), (14, 7, 9)], hexc("#8a8f95"), 13)
    T["marble_stone_front"] = mfront
    T["grave_soil"] = soil(14)
    T["grave_soil_grassy"] = soil(15, grass=0.55)
    T["grave_tufts"] = tufts()
    photo = new()
    portrait(photo, 0, 0, 4, 5, hexc("#8a8f95"), 16)
    T["grave_photo"] = photo
    plaque = noise(hexc("#1d1d1f"), 0.04, 17)
    engraved_lines(plaque, [(6, 1, 15), (8, 2, 14), (10, 1, 15)], hexc("#d8d8d8"), 18)
    T["grave_plaque"] = plaque
    T["funeral_wreath"] = wreath()
    T["artificial_flowers"] = plastic_flowers()
    T["vase_green"] = painted(hexc("#2e5b3a"), 19, sheen=True)
    T["grave_lampada_glass"] = lampada_glass()
    T["rusty_iron"] = rusty(20)
    T["painted_iron_white"] = painted(hexc("#e9e9e4"), 21, chips=hexc("#9a9a94"), rust=3)
    T["stone_bark"] = stone_bark(22)
    T["weathered_stone"] = speckled(hexc("#8d8a83"), 23, ["#6f6c66", "#a7a39b", "#5d6b47", "#7a776f"], 50)
    gothic = T["marble"].copy()
    for y in range(3, 15):  # an engraved pointed arch with a cross in it
        half = 3 if y >= 7 else max(0, y - 4)
        for x in (8 - half, 7 + half + 1):
            if 4 <= x <= 12 and (y >= 7 or x in (8 - half, 8 + half)): gothic.putpixel((x, y), hexc("#9aa0a6"))
    for x, y in ((8, 7), (8, 8), (8, 9), (8, 10), (8, 11), (7, 8), (9, 8)): gothic.putpixel((x, y), hexc("#8a8f95"))
    T["gothic_marble_front"] = gothic
    slab = T["black_granite"].copy()
    for y in range(16):  # engraved border along the long sides, so two slabs in a row read as one
        for x in (3, 12): slab.putpixel((x, y), hexc("#8c7a45"))
    T["grave_slab_top"] = slab
    T["sector_sign"] = sector_sign()
    T["painted_bin_blue"] = bin_blue(24)
    # 22 October: more kinds of grave.
    T["dark_grave_wood"] = wood(hexc("#5b3a24"), 31)
    T["dark_grave_wood_bar"] = T["dark_grave_wood"].transpose(Image.Transpose.ROTATE_90)
    T["painted_iron_green"] = painted(hexc("#3f6e4c"), 32, chips=hexc("#22402b"), rust=4)
    T["polished_gray_granite"] = speckled(hexc("#8a8784"), 33, ["#6e6b69", "#a5a19d", "#5c5a58"], 40)
    gfront = T["polished_gray_granite"].copy()
    engraved_lines(gfront, [(6, 4, 12), (8, 5, 11), (11, 4, 12), (13, 6, 10)], hexc("#2e2c2b"), 34)
    T["gray_stele_front"] = gfront
    T["red_granite"] = speckled(hexc("#7a3a2c"), 35, ["#5a271d", "#9a5544", "#3f1d17", "#b0705c"], 50)
    rfront = T["red_granite"].copy()
    for y in range(5, 13): rfront.putpixel((8, y), hexc("#d9b44a"))  # a gilded cross with its footrest
    for x in range(6, 11): rfront.putpixel((x, 7), hexc("#d9b44a"))
    for x in (7, 9): rfront.putpixel((x, 6), hexc("#d9b44a"))
    rfront.putpixel((7, 11), hexc("#d9b44a")); rfront.putpixel((9, 10), hexc("#d9b44a"))
    T["red_granite_front"] = rfront
    T["granite_portrait_front"] = portrait_engraving(38)
    T["icon_board"] = icon_board()
    T["carnations"] = carnations()
    T["fir_wreath"] = fir_wreath()
    out = os.path.join(ASSETS, "textures", "block")
    os.makedirs(out, exist_ok=True)
    for name, img in T.items(): img.save(os.path.join(out, name + ".png"))
    return T


# ---------------------------------------------------------------- models

FACES = ("north", "south", "east", "west", "up", "down")


def auto_uv(frm, to, f):
    x1, y1, z1 = frm; x2, y2, z2 = to
    return {"north": [16 - x2, 16 - y2, 16 - x1, 16 - y1], "south": [x1, 16 - y2, x2, 16 - y1], "east": [16 - z2, 16 - y2, 16 - z1, 16 - y1],
            "west": [z1, 16 - y2, z2, 16 - y1], "up": [x1, z1, x2, z2], "down": [x1, 16 - z2, x2, 16 - z1]}[f]


def inside(uv):
    """The game's own UVs run off the texture for parts above or beside the block, and then sample the neighbouring sprites
    of the atlas. Moves each span back onto the texture, and squeezes one longer than the texture onto all of it."""
    out = []
    for a, b in ((uv[0], uv[2]), (uv[1], uv[3])):
        lo, hi = min(a, b), max(a, b)
        if hi - lo > 16: lo, hi = 0, 16
        else:
            shift = -math.floor(lo / 16) * 16
            lo, hi = lo + shift, hi + shift
            if hi > 16: lo, hi = 16 - (hi - lo), 16
        out.append((lo, hi))
    return [round(out[0][0], 4), round(out[1][0], 4), round(out[0][1], 4), round(out[1][1], 4)]


def el(frm, to, tex, faces=FACES, uv=None, rot=None, shade=True, skip=()):
    e = {"from": [round(v, 4) for v in frm], "to": [round(v, 4) for v in to], "faces": {}}
    for f in faces:
        if f in skip: continue
        face = {"texture": "#" + tex, "uv": uv[f] if uv and f in uv else inside(auto_uv(frm, to, f))}
        e["faces"][f] = face
    if rot: e["rotation"] = {"origin": [round(v, 4) for v in rot[0]], "axis": rot[1], "angle": rot[2]}
    if not shade: e["shade"] = False
    return e


TALL_DISPLAY = {
    "gui": {"rotation": [30, 225, 0], "translation": [0, -2.75, 0], "scale": [0.36, 0.36, 0.36]},
    "ground": {"rotation": [0, 0, 0], "translation": [0, 3, 0], "scale": [0.2, 0.2, 0.2]},
    "fixed": {"rotation": [0, 0, 0], "translation": [0, -4, 0], "scale": [0.36, 0.36, 0.36]},
    "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 1, 0], "scale": [0.3, 0.3, 0.3]},
    "firstperson_righthand": {"rotation": [0, 45, 0], "translation": [0, -2, 0], "scale": [0.3, 0.3, 0.3]},
    "firstperson_lefthand": {"rotation": [0, 225, 0], "translation": [0, -2, 0], "scale": [0.3, 0.3, 0.3]},
}


def model(name, textures, elements, tall=False, ao=True, parent="minecraft:block/block"):
    m = {"parent": parent, "textures": dict(textures), "elements": elements}
    m["textures"].setdefault("particle", "#" + next(iter(textures)))
    if tall: m["display"] = TALL_DISPLAY
    if not ao: m["ambientocclusion"] = False
    save_json(os.path.join(ASSETS, "models", "block", name + ".json"), m)


def tex(name): return f"{NS}:block/{name}"


def rot_point(p, o, deg):
    a = math.radians(deg); x, y = p[0] - o[0], p[1] - o[1]
    return (o[0] + x * math.cos(a) - y * math.sin(a), o[1] + x * math.sin(a) + y * math.cos(a))


def tilted(e, origin, deg):
    """Turns a z-axis element (rotated about its own centre or not at all) about the given point in the xy plane: the centre
    moves, and the element's own angle grows by deg. The sum must stay one of the angles a model may use."""
    frm, to = e["from"], e["to"]
    c = ((frm[0] + to[0]) / 2, (frm[1] + to[1]) / 2)
    n = rot_point(c, origin, deg)
    dx, dy = n[0] - c[0], n[1] - c[1]
    own = e.get("rotation", {}).get("angle", 0) if e.get("rotation", {}).get("axis", "z") == "z" else 0
    out = dict(e)
    out["from"] = [round(frm[0] + dx, 4), round(frm[1] + dy, 4), frm[2]]
    out["to"] = [round(to[0] + dx, 4), round(to[1] + dy, 4), to[2]]
    out["rotation"] = {"origin": [round(n[0], 4), round(n[1], 4), (frm[2] + to[2]) / 2], "axis": "z", "angle": own + deg}
    return out


def cross_elements(post, bar, plaque=True, titulus=True, top=31):
    els = [
        el((7, 0, 7), (9, top, 9), post),
        el((1.5, 21, 7), (14.5, 23, 9), bar),
        el((4, 10, 7.25), (12, 11.5, 8.75), bar, rot=((8, 10.75, 8), "z", 22.5)),
    ]
    if titulus: els.append(el((5, 26, 7.25), (11, 27.5, 8.75), bar))
    if plaque: els.append(el((7.25, 15.5, 6.8), (8.75, 17.5, 7), "plaque", uv={"north": [1, 5, 15, 11]}, skip=("south",)))
    return els


def write_models(shapes):
    w = {"post": tex("grave_wood"), "bar": tex("grave_wood_bar"), "plaque": tex("grave_plaque")}
    model("wooden_cross", w, cross_elements("post", "bar"), tall=True, ao=False)

    roof = cross_elements("post", "bar", top=31)
    ridge = 31.3
    # A small gable roof (голубец): the ridge runs front to back, the two boards slope to the left and the right, so the
    # cross seen from the front stands under a "^".
    roof += [
        el((8, ridge - 0.5, 4), (14.5, ridge + 0.5, 12), "post", rot=((8, ridge, 8), "z", -45)),
        el((1.5, ridge - 0.5, 4), (8, ridge + 0.5, 12), "post", rot=((8, ridge, 8), "z", 45)),
        el((7.4, ridge - 0.2, 3.8), (8.6, ridge + 0.6, 12.2), "bar"),
    ]
    model("roofed_cross", w, roof, tall=True, ao=False)

    old = {"post": tex("old_grave_wood"), "bar": tex("old_grave_wood_bar")}
    # The old cross leans back on its sunken foot. A model element turns about one axis only, so the footrest that used to
    # slant has slipped level; the title board is gone.
    leaning = [el((7, -1, 7), (9, 29, 9), "post"), el((2.5, 19.5, 7), (13, 21.5, 9), "bar"), el((4.5, 8.5, 7.25), (11.5, 10, 8.75), "bar")]
    for e in leaning:
        e["rotation"] = {"origin": [8, 0, 3], "axis": "x", "angle": 22.5}
        for k in ("from", "to"): e[k][2] = round(e[k][2] - 5, 4)
    model("old_cross", old, leaning, tall=True, ao=False)

    i = {"iron": tex("painted_iron_black"), "gold": tex("painted_gold")}
    iron = [
        el((5, 0, 5), (11, 2, 11), "iron"), el((6, 2, 6), (10, 3, 10), "iron"),
        el((7.25, 3, 7.25), (8.75, 30, 8.75), "iron"),
        el((1.5, 21.25, 7.25), (14.5, 22.75, 8.75), "iron"),
        el((5, 26.25, 7.4), (11, 27.5, 8.6), "iron"),
        el((4, 11, 7.4), (12, 12.25, 8.6), "iron", rot=((8, 11.6, 8), "z", 22.5)),
    ]
    c = (8, 22, 8)
    for frm, to in (((5.5, 24, 7.6), (10.5, 24.75, 8.4)), ((5.5, 19.25, 7.6), (10.5, 20, 8.4)),
                    ((5.5, 19.25, 7.6), (6.25, 24.75, 8.4)), ((9.75, 19.25, 7.6), (10.5, 24.75, 8.4))):
        iron.append(el(frm, to, "gold", rot=(c, "z", 45)))
    for kx, ky in ((8, 30.3), (1.3, 22), (14.7, 22)):
        iron.append(el((kx - 0.9, ky - 0.9, 7.5), (kx + 0.9, ky + 0.9, 8.5), "gold", rot=((kx, ky, 8), "z", 45)))
    model("iron_cross", i, iron, tall=True, ao=False)

    s = {"body": tex("painted_iron_silver"), "star": tex("red_star"), "photo": tex("grave_photo"), "base": tex("gray_granite")}
    levels = [(4, 2, 7), (4.5, 7, 12), (5, 12, 16.5), (5.5, 16.5, 20.5), (6, 20.5, 23.5), (6.5, 23.5, 25.5), (7, 25.5, 27)]
    ob = [el((3, 0, 3), (13, 2, 13), "base")]
    for inset, y1, y2 in levels: ob.append(el((inset, y1, inset), (16 - inset, y2, 16 - inset), "body"))
    ob.append(el((6.5, 8, 4.3), (9.5, 12, 4.5), "photo", uv={"north": [0, 0, 4, 5]}, skip=("south",)))
    ob.append(el((7.6, 27, 7.6), (8.4, 28, 8.4), "body"))
    ob.append(el((5, 26.5, 7.9), (11, 32, 8.1), "star", uv={"north": [0, 0, 16, 16], "south": [0, 0, 16, 16]},
                 faces=("north", "south"), shade=False))
    model("star_obelisk", s, ob, tall=True, ao=False)

    g = {"stone": tex("black_granite"), "front": tex("granite_stele_front"), "base": tex("gray_granite")}
    stele = el((3.5, 2, 7), (12.5, 14, 9), "stone")
    stele["faces"]["north"]["texture"] = "#front"
    model("granite_stele", g, [el((2, 0, 4), (14, 2, 12), "base"), stele, el((4.5, 14, 7), (11.5, 15, 9), "stone"),
                               el((6, 15, 7), (10, 15.75, 9), "stone")])

    m = {"stone": tex("marble"), "front": tex("marble_stone_front")}
    ms = el((5, 1.5, 6), (11, 9, 10), "stone")
    ms["faces"]["north"]["texture"] = "#front"
    model("marble_stone", m, [el((4, 0, 4), (12, 1.5, 12), "stone"), ms, el((5.5, 9, 6.5), (10.5, 9.5, 9.5), "stone")])

    for name, side, top in (("grave_mound", "grave_soil", "grave_soil"), ("grave_mound_overgrown", "grave_soil_grassy", "grave_soil_grassy")):
        t = {"soil": tex(side), "top": tex(top), "tufts": tex("grave_tufts")}
        els = []
        for (x1, y1, x2, y2) in ((1, 0, 15, 3), (2.5, 3, 13.5, 5), (4, 5, 12, 6)):
            e = el((x1, y1, 0), (x2, y2, 16), "soil"); e["faces"]["up"]["texture"] = "#top"; els.append(e)
        if "overgrown" in name:
            for xx in (3.5, 12.5, 8):
                els.append(el((xx, 1, 0.5), (xx, 9, 15.5), "tufts", faces=("east", "west"), uv={"east": [0, 7, 15, 15], "west": [0, 7, 15, 15]}, shade=False))
        model(name, t, els)

    for color, metal in (("black", "painted_iron_black"), ("blue", "painted_iron_blue"), ("rusty", "rusty_iron"),
                         ("green", "painted_iron_green"), ("silver", "painted_iron_silver")):
        t = {"iron": tex(metal)}
        ball = lambda x, y, z: el((x - 0.65, y, z - 0.65), (x + 0.65, y + 1.3, z + 0.65), "iron", rot=((x, y + 0.65, z), "y", 45))
        model(f"{color}_grave_fence_post", t, [el((7.5, 0, 7.5), (8.5, 11.5, 8.5), "iron"), ball(8, 11.5, 8)])
        side = [el((7.7, 2, 0), (8.3, 2.8, 7.5), "iron"), el((7.7, 9.6, 0), (8.3, 10.4, 7.5), "iron")]
        for z in (1.6, 3.6, 5.6):
            side.append(el((7.75, 1, z), (8.25, 11, z + 0.5), "iron"))
        if color == "rusty":  # old fences curl between the rails
            side.append(el((7.75, 6, 0.6), (8.25, 6.5, 6.9), "iron", rot=((8, 6.25, 3.75), "x", 22.5)))
        model(f"{color}_grave_fence_side", t, side)
        inv = [el((7.5, 0, 1), (8.5, 11.5, 2), "iron"), el((7.5, 0, 14), (8.5, 11.5, 15), "iron"), ball(8, 11.5, 1.5), ball(8, 11.5, 14.5),
               el((7.7, 2, 2), (8.3, 2.8, 14), "iron"), el((7.7, 9.6, 2), (8.3, 10.4, 14), "iron")]
        for z in (4, 6, 8, 10, 12):
            inv.append(el((7.75, 1, z), (8.25, 11, z + 0.5), "iron"))
        model(f"{color}_grave_fence_inventory", t, inv)

        posts = [el((0, 0, 7.5), (1, 11.5, 8.5), "iron"), el((15, 0, 7.5), (16, 11.5, 8.5), "iron"), ball(0.5, 11.5, 8), ball(15.5, 11.5, 8)]
        closed = list(posts)
        for x1, x2 in ((1, 7.9), (8.1, 15)):
            closed += [el((x1, 2, 7.7), (x2, 2.8, 8.3), "iron"), el((x1, 9.6, 7.7), (x2, 10.4, 8.3), "iron"),
                       el((x2 - 0.6 if x1 < 8 else x1, 1, 7.7), (x2 if x1 < 8 else x1 + 0.6, 10.4, 8.3), "iron")]
        for x in (2.6, 4.6, 6.4, 9.1, 10.9, 12.9):
            closed.append(el((x, 1, 7.75), (x + 0.5, 11, 8.25), "iron"))
        model(f"{color}_grave_gate", t, closed)
        opened = list(posts)
        for x1 in (0.2, 15.2):
            opened += [el((x1, 2, 8.5), (x1 + 0.6, 2.8, 15.4), "iron"), el((x1, 9.6, 8.5), (x1 + 0.6, 10.4, 15.4), "iron"),
                       el((x1, 1, 14.8), (x1 + 0.6, 10.4, 15.4), "iron")]
            for z in (10.4, 12.4):
                opened.append(el((x1 + 0.05, 1, z), (x1 + 0.55, 11, z + 0.5), "iron"))
        model(f"{color}_grave_gate_open", t, opened)

    b = {"wood": tex("grave_wood_bar"), "iron": tex("painted_iron_black")}
    bench = [el((1, 7, 5), (15, 8.25, 7.75), "wood"), el((1, 7, 8.25), (15, 8.25, 11), "wood")]
    for x in (2, 13):
        bench += [el((x, 0, 5.5), (x + 1, 7, 6.5), "iron"), el((x, 0, 9.5), (x + 1, 7, 10.5), "iron"),
                  el((x, 6, 6.5), (x + 1, 7, 9.5), "iron"), el((x, 0, 5), (x + 1, 1, 11), "iron")]
    model("grave_bench", b, bench)
    model("grave_table", b, [
        el((3, 11, 3), (13, 12.25, 13), "wood"), el((7.25, 1, 7.25), (8.75, 11, 8.75), "iron"),
        el((5, 0, 7.25), (11, 1, 8.75), "iron"), el((7.25, 0, 5), (8.75, 1, 11), "iron"),
        el((4, 10.25, 7.5), (12, 11, 8.5), "iron"),
    ])

    model("funeral_wreath", {"wreath": tex("funeral_wreath")}, [
        el((1, 0.5, 8), (15, 14.5, 8.01), "wreath", faces=("north", "south"), uv={"north": [1, 1, 15, 15], "south": [15, 1, 1, 15]},
           rot=((8, 0.5, 8), "x", 22.5), shade=False),
    ])

    f = {"flowers": tex("artificial_flowers"), "vase": tex("vase_green")}
    planes = []
    for ang in (45, -45):
        planes.append(el((2, 1.5, 8), (14, 12.5, 8), "flowers", faces=("north", "south"),
                         uv={"north": [2, 3.5, 14, 14.5], "south": [2, 3.5, 14, 14.5]}, rot=((8, 8, 8), "y", ang), shade=False))
    model("artificial_flowers", f, planes + [el((6.5, 0, 6.5), (9.5, 3, 9.5), "vase"), el((6, 2.5, 6), (10, 3.25, 10), "vase")])

    l = {"glass": tex("grave_lampada_glass"), "iron": tex("painted_iron_black"), "gold": tex("painted_gold")}
    model("grave_lampada", l, [
        el((6, 0, 6), (10, 1, 10), "iron"),
        el((6.5, 1, 6.5), (9.5, 5, 9.5), "glass", uv={f2: [3, 6, 12, 15] for f2 in ("north", "south", "east", "west")}, shade=False),
        el((6, 5, 6), (10, 5.75, 10), "iron"), el((6.75, 5.75, 6.75), (9.25, 6.25, 9.25), "iron"),
        el((7.75, 6.25, 7.75), (8.25, 8, 8.25), "gold"), el((7.25, 7, 7.75), (8.75, 7.5, 8.25), "gold"),
    ])

    gc = {"stone": tex("black_granite"), "base": tex("gray_granite")}
    granite = [el((4, 0, 5), (12, 3, 11), "base"), el((5, 3, 6), (11, 5, 10), "stone"), el((7, 5, 7), (9, 28, 9), "stone"),
               el((2, 19, 7), (14, 21, 9), "stone"), el((5.5, 24, 7.25), (10.5, 25.5, 8.75), "stone"),
               el((4.5, 10, 7.25), (11.5, 11.5, 8.75), "stone", rot=((8, 10.75, 8), "z", 22.5))]
    for cx, cy, along in ((8, 28, "y"), (2, 20, "x-"), (14, 20, "x+")):  # three-lobed ends
        lobes = {"y": ((0, 1.3), (-1.1, 0.2), (1.1, 0.2)), "x-": ((-1.3, 0), (-0.2, 1.1), (-0.2, -1.1)), "x+": ((1.3, 0), (0.2, 1.1), (0.2, -1.1))}[along]
        for dx, dy in lobes:
            granite.append(el((cx + dx - 0.8, cy + dy - 0.8, 7.2), (cx + dx + 0.8, cy + dy + 0.8, 8.8), "stone"))
    model("granite_cross", gc, granite, tall=True, ao=False)

    wm = {"white": tex("painted_iron_white")}
    model("white_metal_cross", wm, [
        el((7.5, 0, 7.5), (8.5, 22, 8.5), "white"), el((4, 15, 7.5), (12, 16, 8.5), "white"), el((6, 18.5, 7.6), (10, 19.25, 8.4), "white"),
        el((5.5, 7, 7.6), (10.5, 8, 8.4), "white", rot=((8, 7.5, 8), "z", 22.5)), el((6, 0, 6.5), (10, 0.75, 9.5), "white"),
        el((6.75, 14.25, 7.6), (9.25, 16.75, 8.4), "white", rot=((8, 15.5, 8), "z", 45)),
    ], tall=True, ao=False)

    st = {"bark": tex("stone_bark"), "stone": tex("weathered_stone")}
    trunk = [el((3, 0, 3), (13, 9, 13), "stone"), el((2.5, 9, 2.5), (13.5, 10.5, 13.5), "stone"),
             el((6.5, 10.5, 6.5), (9.5, 30, 9.5), "bark"), el((3, 23, 6.5), (13, 26, 9.5), "bark"),
             el((5.5, 15, 7.25), (6.5, 16.5, 8.75), "bark"), el((9.5, 18.5, 7.25), (10.8, 19.5, 8.75), "bark"),
             el((4, 26, 7.5), (5, 27.2, 8.5), "bark"), el((11.5, 21.8, 7.5), (12.5, 23, 8.5), "bark"),
             el((7.25, 30, 7.25), (8.75, 30.8, 8.75), "bark")]
    model("stone_trunk_cross", st, trunk, tall=True, ao=False)

    gm = {"stone": tex("marble"), "front": tex("gothic_marble_front")}
    gslab = el((4, 2, 6.5), (12, 12, 9.5), "stone"); gslab["faces"]["north"]["texture"] = "#front"
    model("gothic_marble_stone", gm, [el((3, 0, 4), (13, 2, 12), "stone"), gslab, el((5, 12, 6.5), (11, 13, 9.5), "stone"),
                                      el((6, 13, 6.5), (10, 14, 9.5), "stone"), el((7, 14, 6.5), (9, 15.5, 9.5), "stone")])

    gs = {"stone": tex("black_granite"), "top": tex("grave_slab_top")}
    slab = el((2, 0, 0), (14, 2, 16), "stone"); slab["faces"]["up"]["texture"] = "#top"
    model("grave_slab", gs, [slab])

    ss = {"sign": tex("sector_sign"), "pole": tex("painted_iron_silver")}
    model("sector_sign", ss, [el((7.5, 0, 7.5), (8.5, 28, 8.5), "pole"),
                              el((3.5, 19.5, 7), (12.5, 28.5, 7.5), "sign", uv={"north": [0, 0, 16, 16], "south": [0, 0, 16, 16]})],
          tall=True, ao=False)

    bn = {"bin": tex("painted_bin_blue")}
    model("cemetery_bin", bn, [el((3, 0, 3), (13, 1, 13), "bin"), el((3, 1, 3), (13, 12, 4), "bin"), el((3, 1, 12), (13, 12, 13), "bin"),
                               el((3, 1, 4), (4, 12, 12), "bin"), el((12, 1, 4), (13, 12, 12), "bin"),
                               el((2.5, 11, 2.5), (13.5, 12, 3.5), "bin"), el((2.5, 11, 12.5), (13.5, 12, 13.5), "bin")])

    write_new_models()

    for name in sorted(TALL):
        save_json(os.path.join(ASSETS, "models", "block", name + "_top.json"),
                  {"textures": {"particle": shapes[name]}})


def write_new_models():
    """The grave kinds added on 22 October."""
    # Golubets: a thick post with a little gabled house on top, an icon under its roof.
    gw = {"post": tex("grave_wood"), "bar": tex("grave_wood_bar"), "icon": tex("icon_board"), "roof": tex("dark_grave_wood")}
    ridge = 31.5
    gol = [el((5.5, 0, 5.5), (10.5, 1, 10.5), "post"), el((6.5, 1, 6.5), (9.5, 22, 9.5), "post"),
           el((4, 22, 4.5), (12, 27, 11.5), "post"), el((5, 27, 4.5), (11, 28.5, 11.5), "post"), el((6.5, 28.5, 4.5), (9.5, 30, 11.5), "post"),
           el((5.25, 22.75, 4.3), (10.75, 26.5, 4.5), "icon", uv={"north": [1, 1, 15, 15]}, skip=("south",)),
           el((8, ridge - 0.5, 3.5), (14.5, ridge + 0.5, 12.5), "roof", rot=((8, ridge, 8), "z", -45)),
           el((1.5, ridge - 0.5, 3.5), (8, ridge + 0.5, 12.5), "roof", rot=((8, ridge, 8), "z", 45)),
           el((7.4, ridge - 0.2, 3.3), (8.6, ridge + 0.5, 12.7), "bar")]
    model("golubets", gw, gol, tall=True, ao=False)

    # A forged cross: an openwork ring around the crossing, gilded tips, on a stepped iron foot.
    fi = {"iron": tex("painted_iron_black"), "gold": tex("painted_gold")}
    cy = 21
    forged = [el((5, 0, 5), (11, 2, 11), "iron"), el((6, 2, 6), (10, 3, 10), "iron"),
              el((7.25, 3, 7.25), (8.75, 30, 8.75), "iron"), el((1.5, cy - 0.75, 7.25), (14.5, cy + 0.75, 8.75), "iron"),
              el((4.5, 25.75, 7.4), (11.5, 26.75, 8.6), "iron"),
              el((4, 10.5, 7.4), (12, 11.75, 8.6), "iron", rot=((8, 11.1, 8), "z", 22.5))]
    a, half = 4.0, 1.66
    for cx_, cy_, ang in ((8, cy + a, 0), (8, cy - a, 0), (8 - a, cy, 90), (8 + a, cy, 90)):
        if ang == 0: forged.append(el((cx_ - half, cy_ - 0.35, 7.6), (cx_ + half, cy_ + 0.35, 8.4), "iron"))
        else: forged.append(el((cx_ - 0.35, cy_ - half, 7.6), (cx_ + 0.35, cy_ + half, 8.4), "iron"))
    d = a * math.sqrt(0.5)
    for sx_, sy_ in ((1, 1), (-1, -1), (-1, 1), (1, -1)):
        px, py = 8 + sx_ * d, cy + sy_ * d
        ang = -45 if sx_ == sy_ else 45
        forged.append(el((px - half, py - 0.35, 7.6), (px + half, py + 0.35, 8.4), "iron", rot=((px, py, 8), "z", ang)))
    for kx, ky in ((8, 30.4), (1.2, cy), (14.8, cy)):
        forged.append(el((kx - 0.9, ky - 0.9, 7.5), (kx + 0.9, ky + 0.9, 8.5), "gold", rot=((kx, ky, 8), "z", 45)))
    model("forged_cross", fi, forged, tall=True, ao=False)

    # A big cross of dark stained oak, thick beams, a plaque.
    dw = {"post": tex("dark_grave_wood"), "bar": tex("dark_grave_wood_bar"), "plaque": tex("grave_plaque")}
    big = [el((6.5, 0, 6.5), (9.5, 32, 9.5), "post"), el((0.5, 21, 6.75), (15.5, 23.5, 9.25), "bar"),
           el((4, 27.5, 7), (12, 29.5, 9), "bar"), el((3.5, 10, 7), (12.5, 12, 9), "bar", rot=((8, 11, 8), "z", 22.5)),
           el((6.75, 15, 6.3), (9.25, 18, 6.5), "plaque", uv={"north": [1, 4, 15, 12]}, skip=("south",))]
    model("big_oak_cross", dw, big, tall=True, ao=False)

    # A Soviet pyramid painted sky blue, the red star on top.
    s = {"body": tex("painted_iron_blue"), "star": tex("red_star"), "photo": tex("grave_photo"), "base": tex("gray_granite")}
    levels = [(4, 2, 7), (4.5, 7, 12), (5, 12, 16.5), (5.5, 16.5, 20.5), (6, 20.5, 23.5), (6.5, 23.5, 25.5), (7, 25.5, 27)]
    ob = [el((3, 0, 3), (13, 2, 13), "base")]
    for inset, y1, y2 in levels: ob.append(el((inset, y1, inset), (16 - inset, y2, 16 - inset), "body"))
    ob.append(el((6.5, 8, 4.3), (9.5, 12, 4.5), "photo", uv={"north": [0, 0, 4, 5]}, skip=("south",)))
    ob.append(el((7.6, 27, 7.6), (8.4, 28, 8.4), "body"))
    ob.append(el((5, 26.5, 7.9), (11, 32, 8.1), "star", uv={"north": [0, 0, 16, 16], "south": [0, 0, 16, 16]},
                 faces=("north", "south"), shade=False))
    model("blue_star_obelisk", s, ob, tall=True, ao=False)

    # A grey granite stele with a cross standing on it.
    gs = {"stone": tex("polished_gray_granite"), "front": tex("gray_stele_front"), "base": tex("gray_granite")}
    body = el((3, 2, 6.5), (13, 17, 9.5), "stone", uv={"north": [0, 0, 16, 16]}); body["faces"]["north"]["texture"] = "#front"
    model("cross_top_stele", gs, [el((2, 0, 4), (14, 2, 12), "base"), body, el((6.5, 17, 7), (9.5, 18, 9), "stone"),
                                  el((7.25, 18, 7.25), (8.75, 29.5, 8.75), "stone"), el((4.5, 24, 7.25), (11.5, 25.5, 8.75), "stone"),
                                  el((5.75, 20, 7.4), (10.25, 21, 8.6), "stone", rot=((8, 20.5, 8), "z", 22.5))], tall=True, ao=False)

    # A small eight-pointed cross, a block high: a fresh grave or a child's.
    w = {"post": tex("grave_wood"), "bar": tex("grave_wood_bar")}
    model("small_wooden_cross", w, [el((7.25, 0, 7.25), (8.75, 15, 8.75), "post"), el((3.5, 10, 7.4), (12.5, 11.5, 8.6), "bar"),
                                    el((5.5, 13, 7.4), (10.5, 14, 8.6), "bar"),
                                    el((5, 4.5, 7.4), (11, 5.5, 8.6), "bar", rot=((8, 5, 8), "z", 22.5))])

    # A tall rectangular black granite stone with an engraved portrait.
    rp = {"stone": tex("black_granite"), "front": tex("granite_portrait_front"), "base": tex("gray_granite")}
    rb = el((2.5, 1.5, 7), (13.5, 16, 9), "stone", uv={"north": [0, 0, 16, 16]}); rb["faces"]["north"]["texture"] = "#front"
    model("portrait_stele", rp, [el((1.5, 0, 4.5), (14.5, 1.5, 11.5), "base"), rb])

    # Red granite with an arched top and a gilded cross.
    rg = {"stone": tex("red_granite"), "front": tex("red_granite_front"), "base": tex("gray_granite")}
    rbody = el((3, 2, 7), (13, 12, 9), "stone"); rbody["faces"]["north"]["texture"] = "#front"
    model("red_granite_stele", rg, [el((2, 0, 4), (14, 2, 12), "base"), rbody, el((4, 12, 7), (12, 13.5, 9), "stone"),
                                    el((5.5, 13.5, 7), (10.5, 14.5, 9), "stone"), el((6.75, 14.5, 7), (9.25, 15, 9), "stone")])

    # An old cross fallen in the grass of an abandoned grave.
    fo = {"post": tex("old_grave_wood"), "bar": tex("old_grave_wood_bar"), "tufts": tex("grave_tufts")}
    fallen = [el((7, 0, 0.5), (9, 1.5, 15.5), "post"), el((1.5, 0, 9), (14.5, 1.25, 11), "bar"),
              el((4.5, 0, 3.5), (11.5, 1, 5), "bar", rot=((8, 0.5, 4.25), "y", 22.5))]
    for xx in (3.5, 12.5):
        fallen.append(el((xx, 0, 1), (xx, 7, 15), "tufts", faces=("east", "west"), uv={"east": [1, 8, 15, 15], "west": [1, 8, 15, 15]}, shade=False))
    model("fallen_cross", fo, fallen)

    # A bench with a back.
    b = {"wood": tex("grave_wood_bar"), "iron": tex("painted_iron_black")}
    bench = [el((1, 7, 4), (15, 8.25, 6.75), "wood"), el((1, 7, 7.25), (15, 8.25, 10), "wood"),
             el((1, 10, 10.25), (15, 11.5, 11.25), "wood"), el((1, 12.5, 10.25), (15, 14, 11.25), "wood")]
    for x in (2, 13):
        bench += [el((x, 0, 4.5), (x + 1, 7, 5.5), "iron"), el((x, 0, 9), (x + 1, 7, 10), "iron"),
                  el((x, 6, 5.5), (x + 1, 7, 9), "iron"), el((x, 0, 4), (x + 1, 1, 10), "iron"),
                  el((x, 7, 11.25), (x + 1, 14.5, 12), "iron")]
    model("backed_bench", b, bench)

    # Red carnations laid on the grave, and a fir wreath leaning on its stand.
    cf = {"flowers": tex("carnations")}
    planes = []
    for ang in (45, -45):
        planes.append(el((2, 0, 8), (14, 9, 8), "flowers", faces=("north", "south"),
                         uv={"north": [1, 3, 15, 16], "south": [1, 3, 15, 16]}, rot=((8, 4.5, 8), "y", ang), shade=False))
    model("carnations", cf, planes)
    model("fir_wreath", {"wreath": tex("fir_wreath"), "wood": tex("dark_grave_wood")}, [
        el((1, 0.5, 8), (15, 14.5, 8.01), "wreath", faces=("north", "south"), uv={"north": [0.5, 0.5, 15.5, 15.5], "south": [15.5, 0.5, 0.5, 15.5]},
           rot=((8, 0.5, 8), "x", 22.5), shade=False),
        el((7.5, 0, 10), (8.5, 10, 11), "wood", rot=((8, 0, 10.5), "x", -22.5)),
    ])


# ---------------------------------------------------------------- blockstates, items, data

Y = {"north": 0, "east": 90, "south": 180, "west": 270}


def variant(model_name, facing, **extra):
    v = {"model": f"{NS}:block/{model_name}"}
    if Y[facing]: v["y"] = Y[facing]
    v.update(extra)
    return v


def write_states():
    bs = os.path.join(ASSETS, "blockstates")
    for name in sorted(TALL):
        vs = {}
        for f in Y:
            vs[f"facing={f},half=lower"] = variant(name, f)
            vs[f"facing={f},half=upper"] = variant(name + "_top", f)
        save_json(os.path.join(bs, name + ".json"), {"variants": vs})
    for name in ("granite_stele", "marble_stone", "grave_bench", "grave_table", "funeral_wreath", "artificial_flowers", "grave_lampada",
                 "gothic_marble_stone", "grave_slab", "cemetery_bin") + SINGLE_NEW:
        save_json(os.path.join(bs, name + ".json"), {"variants": {f"facing={f}": variant(name, f) for f in Y}})
    vs = {}
    for f in Y:
        vs[f"facing={f},overgrown=false"] = variant("grave_mound", f)
        vs[f"facing={f},overgrown=true"] = variant("grave_mound_overgrown", f)
    save_json(os.path.join(bs, "grave_mound.json"), {"variants": vs})
    for color in COLORS:
        post, side = f"{NS}:block/{color}_grave_fence_post", f"{NS}:block/{color}_grave_fence_side"
        parts = [{"apply": {"model": post}}]
        for f, y in Y.items():
            a = {"model": side, "uvlock": True}
            if y: a["y"] = y
            parts.append({"when": {f: "true"}, "apply": a})
        save_json(os.path.join(bs, f"{color}_grave_fence.json"), {"multipart": parts})
        gy = {"south": 0, "west": 90, "north": 180, "east": 270}
        vs = {}
        for f, y in gy.items():
            for in_wall in ("false", "true"):
                for open_ in ("false", "true"):
                    v = {"model": f"{NS}:block/{color}_grave_gate" + ("_open" if open_ == "true" else ""), "uvlock": True}
                    if y: v["y"] = y
                    vs[f"facing={f},in_wall={in_wall},open={open_}"] = v
        save_json(os.path.join(bs, f"{color}_grave_gate.json"), {"variants": vs})


BLOCKS = ["wooden_cross", "roofed_cross", "old_cross", "iron_cross", "star_obelisk", "granite_stele", "marble_stone", "grave_mound",
          "granite_cross", "white_metal_cross", "stone_trunk_cross", "gothic_marble_stone", "grave_slab",
          "black_grave_fence", "black_grave_gate", "blue_grave_fence", "blue_grave_gate", "rusty_grave_fence", "rusty_grave_gate",
          "grave_bench", "grave_table", "funeral_wreath", "artificial_flowers", "grave_lampada", "sector_sign", "cemetery_bin"]
TALL_NEW = ("golubets", "forged_cross", "big_oak_cross", "blue_star_obelisk", "cross_top_stele")
SINGLE_NEW = ("small_wooden_cross", "portrait_stele", "red_granite_stele", "fallen_cross", "backed_bench", "carnations", "fir_wreath")
COLORS = ("black", "blue", "rusty", "green", "silver")
BLOCKS += list(TALL_NEW) + list(SINGLE_NEW) + ["green_grave_fence", "green_grave_gate", "silver_grave_fence", "silver_grave_gate"]
TALL = {"wooden_cross", "roofed_cross", "old_cross", "iron_cross", "star_obelisk", "granite_cross", "white_metal_cross",
        "stone_trunk_cross", "sector_sign"} | set(TALL_NEW)


def write_items_and_data():
    for name in BLOCKS:
        parent = f"{NS}:block/{name}"
        if name.endswith("_fence"): parent += "_inventory"
        save_json(os.path.join(ASSETS, "models", "item", name + ".json"), {"parent": parent})
        entry = {"type": "minecraft:item", "name": f"{NS}:{name}"}
        pool = {"rolls": 1, "entries": [entry], "conditions": [{"condition": "minecraft:survives_explosion"}]}
        if name in TALL:
            pool["conditions"].append({"condition": "minecraft:block_state_property", "block": f"{NS}:{name}", "properties": {"half": "lower"}})
        save_json(os.path.join(DATA, NS, "loot_table", "blocks", name + ".json"), {"type": "minecraft:block", "pools": [pool]})

    tags = os.path.join(DATA, "minecraft", "tags", "block")
    axe = [f"{NS}:{n}" for n in ("wooden_cross", "roofed_cross", "old_cross", "grave_bench", "grave_table", "golubets", "big_oak_cross",
                                 "small_wooden_cross", "fallen_cross", "backed_bench")]
    pick = [f"{NS}:{n}" for n in ("iron_cross", "star_obelisk", "granite_stele", "marble_stone", "black_grave_fence", "black_grave_gate",
                                  "blue_grave_fence", "blue_grave_gate", "rusty_grave_fence", "rusty_grave_gate", "grave_lampada",
                                  "granite_cross", "white_metal_cross", "stone_trunk_cross", "gothic_marble_stone", "grave_slab",
                                  "sector_sign", "cemetery_bin", "forged_cross", "blue_star_obelisk", "cross_top_stele", "portrait_stele",
                                  "red_granite_stele", "green_grave_fence", "green_grave_gate", "silver_grave_fence", "silver_grave_gate")]
    save_json(os.path.join(tags, "mineable", "axe.json"), {"replace": False, "values": axe})
    save_json(os.path.join(tags, "mineable", "pickaxe.json"), {"replace": False, "values": ["divineblock:altar"] + pick})
    save_json(os.path.join(tags, "mineable", "shovel.json"), {"replace": False, "values": [f"{NS}:grave_mound"]})
    save_json(os.path.join(tags, "fences.json"), {"replace": False, "values": [f"{NS}:{c}_grave_fence" for c in COLORS]})
    save_json(os.path.join(tags, "fence_gates.json"), {"replace": False, "values": [f"{NS}:{c}_grave_gate" for c in COLORS]})

    def item(i): return {"tag": i[1:]} if i.startswith("#") else {"item": i}

    def shaped(name, pattern, keys, count=1):
        save_json(os.path.join(DATA, NS, "recipe", name + ".json"), {
            "type": "minecraft:crafting_shaped", "category": "decorations", "pattern": pattern,
            "key": {k: item(v) for k, v in keys.items()}, "result": {"id": f"{NS}:{name}", "count": count}})

    def shapeless(name, ingredients, count=1):
        save_json(os.path.join(DATA, NS, "recipe", name + ".json"), {
            "type": "minecraft:crafting_shapeless", "category": "decorations", "ingredients": [item(i) for i in ingredients],
            "result": {"id": f"{NS}:{name}", "count": count}})

    shaped("wooden_cross", [" S ", "PSP", " S "], {"S": "minecraft:stick", "P": "#minecraft:planks"})
    shapeless("roofed_cross", [f"{NS}:wooden_cross", "#minecraft:wooden_slabs"])
    shapeless("old_cross", [f"{NS}:wooden_cross", "minecraft:vine"])
    shaped("iron_cross", [" N ", "IDI", " I "], {"N": "minecraft:gold_nugget", "I": "minecraft:iron_ingot", "D": "minecraft:black_dye"})
    shaped("star_obelisk", [" R ", " I ", "IGI"], {"R": "minecraft:red_dye", "I": "minecraft:iron_ingot", "G": "minecraft:paper"})
    shaped("granite_stele", ["BPB", " B ", "SSS"], {"B": "minecraft:polished_blackstone", "P": "minecraft:paper", "S": "minecraft:stone_slab"})
    shaped("marble_stone", [" C ", "CCC"], {"C": "minecraft:calcite"}, 2)
    shaped("grave_mound", ["DDD"], {"D": "minecraft:dirt"}, 2)
    shaped("black_grave_fence", ["NDN", "NIN"], {"N": "minecraft:iron_nugget", "I": "minecraft:iron_ingot", "D": "minecraft:black_dye"}, 6)
    shaped("blue_grave_fence", ["NDN", "NIN"], {"N": "minecraft:iron_nugget", "I": "minecraft:iron_ingot", "D": "minecraft:light_blue_dye"}, 6)
    shaped("black_grave_gate", ["IDI", "INI"], {"N": "minecraft:iron_nugget", "I": "minecraft:iron_ingot", "D": "minecraft:black_dye"})
    shaped("blue_grave_gate", ["IDI", "INI"], {"N": "minecraft:iron_nugget", "I": "minecraft:iron_ingot", "D": "minecraft:light_blue_dye"})
    shaped("grave_bench", ["SSS", "N N"], {"S": "#minecraft:wooden_slabs", "N": "minecraft:iron_ingot"})
    shaped("grave_table", ["SSS", " N ", " N "], {"S": "#minecraft:wooden_slabs", "N": "minecraft:iron_ingot"})
    shaped("funeral_wreath", [" F ", "FLF", " F "], {"F": "#minecraft:small_flowers", "L": "minecraft:spruce_leaves"}, 2)
    shapeless("artificial_flowers", ["minecraft:paper", "minecraft:red_dye", "minecraft:green_dye", "minecraft:stick"], 2)
    shaped("granite_cross", [" B ", "BBB", " S "], {"B": "minecraft:polished_blackstone", "S": "minecraft:polished_blackstone_slab"})
    shaped("white_metal_cross", [" N ", "NWN", " N "], {"N": "minecraft:iron_nugget", "W": "minecraft:white_dye"})
    shaped("stone_trunk_cross", [" C ", "CCC", "SSS"], {"C": "minecraft:mossy_cobblestone", "S": "minecraft:stone_bricks"})
    shapeless("gothic_marble_stone", [f"{NS}:marble_stone", "minecraft:calcite"])
    shaped("grave_slab", ["BBB"], {"B": "minecraft:polished_blackstone_slab"}, 2)
    shaped("rusty_grave_fence", ["NDN", "NIN"], {"N": "minecraft:iron_nugget", "I": "minecraft:iron_ingot", "D": "minecraft:brown_dye"}, 6)
    shaped("rusty_grave_gate", ["IDI", "INI"], {"N": "minecraft:iron_nugget", "I": "minecraft:iron_ingot", "D": "minecraft:brown_dye"})
    shaped("sector_sign", [" D ", " P ", " S "], {"D": "minecraft:blue_dye", "P": "minecraft:paper", "S": "minecraft:stick"})
    shaped("cemetery_bin", ["N N", "NDN", "NNN"], {"N": "minecraft:iron_nugget", "D": "minecraft:blue_dye"})
    shapeless("grave_lampada", ["#minecraft:candles", "minecraft:red_stained_glass_pane", "minecraft:iron_nugget"])
    shaped("golubets", [" S ", "PLP", " P "], {"S": "#minecraft:wooden_slabs", "P": "#minecraft:planks", "L": "minecraft:paper"})
    shaped("forged_cross", ["NIN", "IDI", " I "], {"N": "minecraft:gold_nugget", "I": "minecraft:iron_ingot", "D": "minecraft:black_dye"})
    shaped("big_oak_cross", [" L ", "LLL", " L "], {"L": "minecraft:dark_oak_log"})
    shaped("blue_star_obelisk", [" R ", " I ", "IBI"], {"R": "minecraft:red_dye", "I": "minecraft:iron_ingot", "B": "minecraft:light_blue_dye"})
    shaped("cross_top_stele", [" S ", "PGP", "GGG"], {"S": "minecraft:stick", "P": "minecraft:paper", "G": "minecraft:polished_andesite"})
    shaped("small_wooden_cross", [" S ", "SSS", " S "], {"S": "minecraft:stick"}, 2)
    shaped("portrait_stele", ["BPB", "BBB", "SSS"], {"B": "minecraft:polished_blackstone", "P": "minecraft:paper", "S": "minecraft:stone_slab"})
    shaped("red_granite_stele", [" G ", "GNG", "SSS"], {"G": "minecraft:polished_granite", "N": "minecraft:gold_nugget", "S": "minecraft:stone_slab"})
    shapeless("fallen_cross", [f"{NS}:old_cross", "minecraft:short_grass"])
    shapeless("backed_bench", [f"{NS}:grave_bench", "#minecraft:wooden_slabs"])
    shapeless("carnations", ["minecraft:poppy", "minecraft:poppy", "minecraft:poppy"], 2)
    shaped("fir_wreath", [" L ", "LRL", " L "], {"L": "minecraft:spruce_leaves", "R": "minecraft:red_dye"}, 2)
    for color, dye in (("green", "minecraft:green_dye"), ("silver", "minecraft:light_gray_dye")):
        shaped(f"{color}_grave_fence", ["NDN", "NIN"], {"N": "minecraft:iron_nugget", "I": "minecraft:iron_ingot", "D": dye}, 6)
        shaped(f"{color}_grave_gate", ["IDI", "INI"], {"N": "minecraft:iron_nugget", "I": "minecraft:iron_ingot", "D": dye})


if __name__ == "__main__":
    write_textures()
    particles = {"wooden_cross": tex("grave_wood"), "roofed_cross": tex("grave_wood"), "old_cross": tex("old_grave_wood"),
                 "iron_cross": tex("painted_iron_black"), "star_obelisk": tex("painted_iron_silver"), "granite_cross": tex("black_granite"),
                 "white_metal_cross": tex("painted_iron_white"), "stone_trunk_cross": tex("stone_bark"), "sector_sign": tex("sector_sign"),
                 "golubets": tex("grave_wood"), "forged_cross": tex("painted_iron_black"), "big_oak_cross": tex("dark_grave_wood"),
                 "blue_star_obelisk": tex("painted_iron_blue"), "cross_top_stele": tex("polished_gray_granite")}
    write_models(particles)
    write_states()
    write_items_and_data()
    print("cemetery assets written")
