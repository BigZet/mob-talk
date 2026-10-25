"""Indoor furniture of the city: a Soviet flat (kitchen set, ZIL fridge, gas stove, "стенка", sofa with a carpet on the wall,
CRT television on legs, chandelier), an office (polished desk with green baize, filing cabinets, a safe), a hospital ward,
a school class (парта, blackboard) and a shop (shelves with tins, bread and three-litre jars, glass counters, scales, till).

Called from decor.py (textures, models, names and the block list in io.github.mobtalk.game.CityInterior); tools/city/furnish.py
sets them out inside the buildings. Models face north like the street decor: the front at low z, the back against z=16.
"""
import math

D = None  # the decor module (decor.py run as __main__ is not the importable "decor")
SPECS = []  # (id, mount, light, map colour, sound)


# ---------------------------------------------------------------- textures

def T(name, img):
    return D.tex(name, img)


def veneer(name, base, streak=0.86, sheen=False):
    """Wood veneer: fine grain streaks across, the odd knot; polished walnut gets a soft sheen."""
    r = D.rng(name)
    img = D.new()
    D.noise_fill(img, base, 0.035, r)
    px = img.load()
    for _ in range(7):
        y = r.randrange(16)
        x0 = r.randrange(-4, 8)
        for x in range(max(0, x0), min(16, x0 + r.randrange(6, 16))):
            yy = min(15, max(0, y + (1 if (x // 5) % 2 and r.random() < 0.3 else 0)))
            px[x, yy] = D.shade(base, streak * r.uniform(0.96, 1.02))
    if sheen:
        for k in range(16):
            x, y = k, 15 - k
            if 0 <= x < 16 and r.random() < 0.7:
                px[x, y] = D.shade(px[x, y], 1.18)
    return T(name, img)


def fabric(name, base):
    """Upholstery: a woven check of two tones with a little fluff."""
    r = D.rng(name)
    img = D.new()
    px = img.load()
    for y in range(16):
        for x in range(16):
            k = 1.06 if (x + y) % 2 == 0 else 0.95
            if (x // 4 + y // 4) % 2 == 0:
                k *= 1.03
            px[x, y] = D.shade(base, k * r.uniform(0.97, 1.03))
    return T(name, img)


def flat(name, base, amount=0.03):
    img = D.new()
    D.noise_fill(img, base, amount, D.rng(name))
    return T(name, img)


def doors(name, body, knob, count=2, drawer=False, mirror=None):
    """A cabinet front: `count` doors with a dark joint and a bevel each, small knobs; a drawer row at the bottom if asked."""
    img = D.new()
    img.paste(D.TEX[body], (0, 0))
    px = img.load()
    y1 = 12 if drawer else 16
    w = 16 // count
    for i in range(count):
        x0 = i * w
        D.bevel(img, (x0, 0, x0 + w, y1), 1.12, 0.72)
        kx = x0 + w - 2 if i % 2 == 0 else x0 + 1
        for ky in (y1 // 2 - 1, y1 // 2):
            px[kx, ky] = knob
    if mirror is not None:
        for y in range(2, y1 - 2):
            for x in range(2, w - 2):
                c = D.mix(mirror, (250, 252, 255), 0.35 if (x - y) % 7 in (0, 1) else 0.0)
                px[x, y] = c
    if drawer:
        D.bevel(img, (0, 12, 16, 16), 1.12, 0.72)
        px[7, 14] = knob
        px[8, 14] = knob
    return T(name, img)


def drawers(name, body, knob, rows=3):
    img = D.new()
    img.paste(D.TEX[body], (0, 0))
    px = img.load()
    h = 16 // rows
    for i in range(rows):
        D.bevel(img, (0, i * h, 16, i * h + h), 1.12, 0.7)
        for x in (4, 5, 10, 11):
            px[x, i * h + h // 2] = knob
    return T(name, img)


def stove():
    enamel = (236, 236, 228, 255)
    top = flat("stove_top", enamel, 0.02)
    px = top.load()
    for cx, cy in ((4, 4), (11, 4), (4, 11), (11, 11)):
        for y in range(16):
            for x in range(16):
                d = math.hypot(x - cx + 0.5, y - cy + 0.5)
                if d < 3.4:
                    px[x, y] = (46, 46, 48, 255) if d > 1.2 else (90, 90, 92, 255)
        for k in range(-3, 3):  # the grate's cross
            px[cx + k, cy] = (24, 24, 26, 255)
            px[cx, cy + k] = (24, 24, 26, 255)
    front = flat("stove_front", enamel, 0.02)
    px = front.load()
    D.rect(front, (0, 0, 16, 3), (214, 214, 206, 255))
    for x in (3, 6, 9, 12):  # knobs
        px[x, 1] = (30, 30, 32, 255)
        px[x + 1, 1] = (60, 60, 62, 255)
    D.rect(front, (2, 5, 14, 15), (220, 220, 212, 255))
    D.bevel(front, (2, 5, 14, 15), 1.05, 0.7)
    D.rect(front, (4, 8, 12, 12), (40, 46, 50, 255))
    px[5, 8] = (120, 130, 140, 255)
    px[6, 8] = (90, 100, 110, 255)
    for x in range(3, 13):  # the chrome handle
        px[x, 6] = (196, 200, 206, 255)
    D.rect(front, (0, 15, 16, 16), (60, 60, 62, 255))


def fridge():
    white = (240, 240, 234, 255)
    lo = flat("fridge_lower", white, 0.015)
    D.bevel(lo, (0, 0, 16, 14), 1.03, 0.8)
    D.rect(lo, (0, 14, 16, 16), (70, 70, 72, 255))
    px = lo.load()
    for x in range(1, 15, 2):
        px[x, 14] = (40, 40, 42, 255)
    up = flat("fridge_upper", white, 0.015)
    D.bevel(up, (0, 0, 16, 16), 1.03, 0.85)


def kitchen_fronts():
    flat("enamel", (236, 236, 228, 255), 0.02)
    veneer("veneer_light", (196, 152, 100, 255))
    veneer("veneer_dark", (98, 58, 34, 255), 0.8, sheen=True)
    veneer("veneer_mid", (150, 98, 58, 255))
    flat("cab_white", (232, 234, 228, 255), 0.015)
    chrome = (190, 196, 202, 255)
    doors("cab_white_front", "cab_white", chrome)
    doors("cab_wood_front", "veneer_light", (90, 60, 34, 255))
    doors("cup_white_front", "cab_white", chrome)
    doors("cup_wood_front", "veneer_light", (90, 60, 34, 255))
    top = flat("counter_top", (184, 180, 170, 255), 0.03)
    r = D.rng("counter_top")
    for _ in range(26):
        top.putpixel((r.randrange(16), r.randrange(16)), (140, 136, 128, 255))
    sink = flat("sink_top", (184, 180, 170, 255), 0.03)
    D.rect(sink, (2, 3, 14, 13), (168, 172, 176, 255))
    D.rect(sink, (3, 4, 13, 12), (140, 146, 152, 255))
    D.bevel(sink, (3, 4, 13, 12), 0.8, 1.15)
    sink.putpixel((8, 8), (50, 50, 52, 255))
    sink.putpixel((7, 8), (70, 70, 72, 255))
    stove()
    fridge()


def carpet_texture():
    """The wall carpet, 32x16, cut into a left and a right half: red field, dark blue and ivory border, a central medallion."""
    from PIL import Image
    img = Image.new("RGBA", (32, 16))
    px = img.load()
    r = D.rng("carpet")
    red, blue, ivory, gold = (152, 30, 36), (32, 38, 86), (222, 204, 162), (206, 150, 60)
    for y in range(16):
        for x in range(32):
            c = red
            e = min(x, 31 - x, y, 15 - y)
            if e == 0:
                c = blue
            elif e == 1:
                c = ivory if (x + y) % 2 == 0 else gold
            elif e == 2:
                c = blue
            else:
                d = abs(x - 15.5) / 1.6 + abs(y - 7.5)
                if d < 3.2:
                    c = ivory
                elif d < 4.3:
                    c = blue
                elif d < 5.3:
                    c = gold
                elif (x % 6 == 4 and y % 5 == 3) or (abs(x - 15.5) > 9 and (x + y) % 4 == 0 and y in (5, 10)):
                    c = ivory
            px[x, y] = D.shade(c + (255,), r.uniform(0.93, 1.05))
    T("carpet_left", img.crop((0, 0, 16, 16)))
    T("carpet_right", img.crop((16, 0, 32, 16)))


def rug(name, field, border, motif):
    """One corner of a floor rug: border along the top (north) and left (west) edges, the field patterned."""
    r = D.rng(name)
    img = D.new()
    px = img.load()
    for y in range(16):
        for x in range(16):
            e = min(x, y)
            if e == 0:
                c = border
            elif e == 1:
                c = motif if (x + y) % 3 == 0 else border
            elif e == 2:
                c = motif
            else:
                c = motif if (x % 5 == 2 and y % 5 == 2) or ((x + y) % 10 == 0 and x > 4 and y > 4) else field
            px[x, y] = D.shade(c + (255,), r.uniform(0.93, 1.05))
    T(name, img)


def tv_front(name, body):
    img = D.new()
    img.paste(D.TEX[body], (0, 0))
    px = img.load()
    for y in range(2, 13):
        for x in range(1, 11):
            corner = (x in (1, 10)) and (y in (2, 12))
            if corner:
                continue
            g = 66 + int(10 * math.hypot(x - 5, y - 7) / 6)
            px[x, y] = (g - 6, g + 8, g + 2, 255)
    for x, y in ((3, 4), (4, 4), (3, 5)):
        px[x, y] = (150, 170, 160, 255)
    D.rect(img, (12, 2, 15, 14), D.shade(D.TEX[body].getpixel((12, 2)), 0.8))
    for y in (4, 8):
        px[13, y] = (30, 30, 30, 255)
        px[13, y + 1] = (200, 200, 200, 255)
    for y in range(11, 14, 1):
        px[12, y] = (40, 40, 40, 255)
        px[14, y] = (40, 40, 40, 255)
    T(name, img)


def radio_front():
    img = D.new()
    img.paste(D.TEX["veneer_mid"], (0, 0))
    px = img.load()
    D.rect(img, (1, 2, 8, 14), (176, 160, 122, 255))
    for y in range(2, 14):
        for x in range(1, 8):
            if (x + y) % 2 == 0:
                px[x, y] = (150, 134, 100, 255)
    D.rect(img, (9, 3, 15, 8), (228, 212, 150, 255))
    for x in range(9, 15):
        px[x, 5] = (60, 40, 30, 255) if x % 2 else (200, 60, 40, 255)
    for x in (10, 13):
        px[x, 11] = (30, 24, 20, 255)
        px[x, 12] = (90, 70, 50, 255)
    T("radio_front", img)


def shelves(name, frame, items, rows=3):
    """A shelf unit seen from the front: a frame, dark back, goods on `rows` shelves (items: list of row painters)."""
    r = D.rng(name)
    img = D.new()
    D.noise_fill(img, frame, 0.03, r)
    px = img.load()
    h = 16 // rows
    for i in range(rows):
        y0 = i * h
        D.rect(img, (1, y0 + 1, 15, y0 + h - 1), (44, 36, 30, 255))
        items[i % len(items)](px, r, 1, y0 + 1, 15, y0 + h - 1)
    D.bevel(img, (0, 0, 16, 16), 1.1, 0.75)
    T(name, img)


def books(px, r, x0, y0, x1, y1):
    x = x0
    while x < x1:
        w = r.choice((1, 1, 2))
        c = r.choice([(150, 30, 30), (40, 70, 130), (60, 110, 60), (190, 160, 70), (110, 70, 40), (220, 210, 190), (90, 40, 90)])
        top = y0 + r.choice((0, 0, 1))
        for xx in range(x, min(x1, x + w)):
            for y in range(top, y1):
                px[xx, y] = D.shade(c + (255,), r.uniform(0.9, 1.08))
            if y1 - top > 3:
                px[xx, top + 1] = (230, 200, 90, 255)
        x += w


def crystal(px, r, x0, y0, x1, y1):
    for x in range(x0, x1):
        for y in range(y0, y1):
            if x % 3 != 0 and y > y0:
                px[x, y] = (200, 220, 230, 255) if (x + y) % 2 else (150, 175, 190, 255)
    for x in range(x0, x1):
        px[x, y0] = (230, 240, 245, 255) if x % 4 == 1 else px[x, y0]


def folders(px, r, x0, y0, x1, y1):
    for x in range(x0, x1):
        c = r.choice([(214, 196, 150), (120, 140, 170), (176, 170, 160), (190, 120, 90)])
        for y in range(y0, y1):
            px[x, y] = D.shade(c + (255,), 0.95 if x % 2 else 1.04)
        px[x, y0 + 2] = (250, 250, 245, 255)


def tins(px, r, x0, y0, x1, y1):
    label = r.choice([(200, 40, 40), (40, 80, 160), (220, 180, 40)])
    for x in range(x0, x1):
        if x % 3 == 0:
            continue
        for y in range(y0 + 1, y1):
            px[x, y] = (180, 184, 190, 255) if y in (y0 + 1, y1 - 1) else D.shade(label + (255,), 1.08 if x % 3 == 1 else 0.9)


def breads(px, r, x0, y0, x1, y1):
    for x in range(x0, x1):
        for y in range(y0 + 1, y1):
            if (x - x0) % 5 != 4:
                px[x, y] = D.shade((196, 136, 66, 255) if (x // 5) % 2 else (160, 100, 50, 255), r.uniform(0.92, 1.08))
        px[x, y0 + 1] = D.shade(px[x, y0 + 1], 1.15)


def bottles(px, r, x0, y0, x1, y1):
    for x in range(x0, x1):
        if x % 2:
            continue
        c = r.choice([(60, 120, 60), (240, 240, 236), (120, 70, 40)])
        for y in range(y0 + 2, y1):
            px[x, y] = D.shade(c + (255,), r.uniform(0.92, 1.05))
        px[x, y0 + 1] = (200, 200, 200, 255) if c[0] == 240 else (220, 190, 60, 255)


def jars(px, r, x0, y0, x1, y1):
    for x in range(x0, x1):
        if (x - x0) % 4 == 3:
            continue
        for y in range(y0 + 1, y1):
            inside = (90, 130, 50) if (x + y) % 3 else (200, 60, 40)
            px[x, y] = D.shade(inside + (255,), r.uniform(0.9, 1.1))
        px[x, y0] = (200, 200, 205, 255)


def bottles_med(px, r, x0, y0, x1, y1):
    for x in range(x0, x1):
        if x % 2:
            continue
        c = r.choice([(120, 70, 30), (230, 230, 230), (90, 140, 180)])
        for y in range(y0 + 2, y1):
            px[x, y] = c + (255,)


def picture(name, painter):
    img = D.new()
    px = img.load()
    painter(px)
    for i in range(16):
        for c in ((i, 0), (i, 15), (0, i), (15, i)):
            px[c] = (176, 132, 52, 255)
        for c in ((i, 1), (i, 14), (1, i), (14, i)):
            if 0 < c[0] < 15 and 0 < c[1] < 15:
                px[c] = (120, 84, 30, 255)
    T(name, img)


def forest(px):
    r = D.rng("forest")
    for y in range(16):
        for x in range(16):
            px[x, y] = (150, 190, 220, 255) if y < 6 else ((90, 140, 60, 255) if y > 11 else (70, 110, 50, 255))
    for x in (4, 7, 11):  # birches
        for y in range(4, 13):
            px[x, y] = (236, 236, 230, 255) if r.random() < 0.8 else (40, 40, 40, 255)


def sea(px):
    for y in range(16):
        for x in range(16):
            px[x, y] = (180, 210, 236, 255) if y < 8 else ((40, 90, 150, 255) if y < 12 else (220, 200, 140, 255))
    for x in (9, 10, 11):
        px[x, 7] = (236, 236, 236, 255)
    px[10, 5] = (236, 236, 236, 255)
    px[10, 6] = (236, 236, 236, 255)


def clock_face():
    img = D.new()
    px = img.load()
    for y in range(16):
        for x in range(16):
            d = math.hypot(x - 7.5, y - 7.5)
            px[x, y] = (110, 70, 40, 255) if d > 6.5 else (240, 236, 222, 255)
    for k in range(12):
        a = k / 12 * 2 * math.pi
        px[int(7.5 + 5.3 * math.sin(a) + 0.5), int(7.5 - 5.3 * math.cos(a) + 0.5)] = (30, 30, 30, 255)
    for y in range(4, 8):
        px[7, y] = (20, 20, 20, 255)
    for x in range(8, 11):
        px[x, 8] = (20, 20, 20, 255)
    T("clock_face", img)


def office_textures():
    top = D.new()
    top.paste(D.TEX["veneer_dark"], (0, 0))
    D.rect(top, (2, 2, 14, 13), (46, 96, 58, 255))
    r = D.rng("baize")
    px = top.load()
    for y in range(2, 13):
        for x in range(2, 14):
            px[x, y] = D.shade((46, 96, 58, 255), r.uniform(0.94, 1.06))
    T("desk_top", top)
    drawers("desk_drawers", "veneer_dark", (200, 160, 70, 255), 3)
    shelves("folders_front", (96, 58, 34, 255), [folders, folders, books])
    safe = flat("safe_front", (86, 104, 90, 255), 0.03)
    D.bevel(safe, (0, 0, 16, 16), 1.1, 0.7)
    D.bevel(safe, (2, 2, 14, 14), 1.1, 0.7)
    px = safe.load()
    for y in range(16):
        for x in range(16):
            if math.hypot(x - 6, y - 7) < 2.2:
                px[x, y] = (200, 196, 180, 255)
    px[6, 7] = (40, 40, 40, 255)
    for y in range(6, 11):
        px[11, y] = (200, 196, 180, 255)
    flat("safe_side", (80, 98, 84, 255), 0.03)
    tube = D.new()
    D.rect(tube, (0, 0, 16, 16), (200, 202, 204, 255))
    D.rect(tube, (1, 1, 15, 15), (250, 250, 244, 255))
    for x in range(1, 15):
        tube.putpixel((x, 5), (232, 232, 226, 255))
        tube.putpixel((x, 10), (232, 232, 226, 255))
    T("lamp_tube", tube)


def school_textures():
    flat("paint_green", (62, 104, 70, 255), 0.03)
    for side in ("left", "right"):
        img = D.new()
        r = D.rng("board_" + side)
        D.noise_fill(img, (40, 70, 52, 255), 0.04, r)
        px = img.load()
        frame = 0 if side == "left" else 15
        for y in range(16):
            px[frame, y] = (120, 80, 44, 255)
            px[frame + (1 if frame == 0 else -1), y] = (90, 60, 34, 255)
        for x in range(16):
            px[x, 0] = (120, 80, 44, 255)
            px[x, 15] = (120, 80, 44, 255)
        # chalk: a sum on the left half, wavy lines of a dictation on the right
        if side == "left":
            for x, y in ((3, 4), (4, 4), (5, 4), (4, 3), (4, 5), (8, 3), (8, 4), (8, 5), (9, 5), (10, 3), (10, 4), (10, 5), (10, 6), (10, 7),
                         (3, 9), (4, 9), (5, 9), (6, 9), (3, 11), (4, 11), (5, 11), (6, 11)):
                px[x, y] = (228, 232, 226, 255)
        for y in (4, 7, 10) if side == "right" else (13,):
            for x in range(2, 14):
                if (x + y) % 3 and r.random() < 0.8:
                    px[x, y + (x % 2)] = (210, 214, 208, 255)
        T("blackboard_" + side, img)


def shop_textures():
    cream = (226, 220, 200, 255)
    shelves("shelf_tins", cream, [tins, tins, tins], 4)
    shelves("shelf_bread", (150, 110, 70, 255), [breads], 3)
    shelves("shelf_bottles", cream, [bottles, bottles, bottles], 3)
    shelves("shelf_jars", cream, [jars, jars], 2)
    counter = D.new()
    r = D.rng("counter")
    D.noise_fill(counter, cream, 0.03, r)
    px = counter.load()
    D.rect(counter, (1, 1, 15, 8), (150, 176, 186, 255))
    for x in range(2, 14):  # sausages and cheese behind the glass
        for y in range(4, 8):
            px[x, y] = (170, 70, 60, 255) if x < 8 else (230, 196, 90, 255)
    for k in range(1, 7):
        px[k + 1, k] = (210, 230, 236, 255)
    D.bevel(counter, (0, 0, 16, 9), 1.1, 0.7)
    D.bevel(counter, (0, 9, 16, 16), 1.1, 0.7)
    T("counter_front", counter)
    flat("counter_side", cream, 0.03)
    fr = flat("freezer_front", (238, 238, 234, 255), 0.015)
    D.rect(fr, (0, 6, 16, 9), (50, 110, 190, 255))
    D.bevel(fr, (0, 0, 16, 16), 1.05, 0.75)
    ice = D.new()
    px = ice.load()
    r = D.rng("ice")
    for y in range(16):
        for x in range(16):
            px[x, y] = r.choice([(240, 240, 250), (230, 190, 80), (200, 60, 70), (120, 170, 220)]) + (255,) if (x // 3 + y // 4) % 2 else (
                (200, 220, 236, 255))
    for x in range(16):
        px[x, 0] = (200, 200, 204, 255)
        px[x, 15] = (200, 200, 204, 255)
    T("freezer_top", ice)
    dial = D.new()
    px = dial.load()
    for y in range(16):
        for x in range(16):
            px[x, y] = (240, 238, 228, 255) if math.hypot(x - 7.5, y - 9) < 7 else (230, 230, 226, 255)
    for k in range(9):
        a = math.pi * (0.15 + 0.7 * k / 8)
        px[int(7.5 - 6 * math.cos(a)), int(9 - 6 * math.sin(a))] = (30, 30, 30, 255)
    for k in range(5):
        px[7 + k // 2, 9 - k] = (200, 30, 30, 255)
    T("scales_dial", dial)


def hospital_textures():
    sheet = flat("sheet", (240, 242, 240, 255), 0.02)
    px = sheet.load()
    for x in range(16):
        px[x, 4] = (200, 210, 226, 255)
        px[x, 11] = (200, 210, 226, 255)
    blanket = flat("blanket", (120, 134, 150, 255), 0.03)
    px = blanket.load()
    for x in range(16):
        for y in (2, 3, 12, 13):
            px[x, y] = (230, 230, 226, 255)
    shelves("med_glass", (238, 238, 232, 255), [bottles_med, bottles_med, bottles_med], 3)
    med = D.TEX["med_glass"]
    px = med.load()
    for k in range(5, 11):
        px[k, 7] = (200, 30, 30, 255)
        px[k, 8] = (200, 30, 30, 255)
        px[7, k] = (200, 30, 30, 255)
        px[8, k] = (200, 30, 30, 255)
    leather = flat("leather", (104, 60, 40, 255), 0.04)
    px = leather.load()
    for x, y in ((3, 3), (11, 3), (7, 8), (3, 12), (11, 12)):
        px[x, y] = (60, 34, 22, 255)
    cloth = flat("screen_cloth", (236, 236, 228, 255), 0.02)
    px = cloth.load()
    for x in range(0, 16, 3):
        for y in range(16):
            px[x, y] = (214, 214, 206, 255)


def living_textures():
    for n, c in (("fabric_red", (150, 44, 44)), ("fabric_green", (74, 104, 64)), ("fabric_brown", (124, 86, 58)),
                 ("fabric_blue", (60, 80, 120))):
        fabric(n, c + (255,))
    brass = (196, 160, 70, 255)
    doors("stenka_doors", "veneer_dark", brass)
    shelves("stenka_glass", (98, 58, 34, 255), [crystal, crystal], 2)
    shelves("stenka_books", (98, 58, 34, 255), [books, books], 2)
    shelves("bookcase_front", (150, 98, 58, 255), [books, books, books], 3)
    doors("wardrobe_lower", "veneer_mid", brass, 2, drawer=True)
    doors("wardrobe_upper", "veneer_mid", brass, 2, mirror=(170, 186, 196))
    drawers("dresser_front", "veneer_mid", brass, 3)
    doors("nightstand_front", "veneer_mid", brass, 1, drawer=True)
    tv_front("tv_front", "veneer_dark")
    radio_front()
    carpet_texture()
    rug("rug_red", (150, 34, 38), (40, 40, 80), (222, 200, 150))
    rug("rug_green", (70, 100, 70), (120, 60, 40), (226, 206, 150))
    picture("picture_forest", forest)
    picture("picture_sea", sea)
    clock_face()
    cloth = flat("tablecloth", (244, 242, 236, 255), 0.015)
    px = cloth.load()
    for i in range(16):
        for c in ((i, 1), (i, 14), (1, i), (14, i)):
            px[c] = (190, 200, 220, 255)
    oil = D.new()
    px = oil.load()
    for y in range(16):
        for x in range(16):
            px[x, y] = (200, 50, 50, 255) if (x // 2 + y // 2) % 2 else (240, 236, 228, 255)
    T("oilcloth", oil)
    lace = flat("tablecloth_side", (244, 242, 236, 255), 0.015)
    px = lace.load()
    for x in range(16):
        if x % 2:
            px[x, 15] = (210, 210, 204, 255)
    flat("lacquer_black", (28, 24, 22, 255), 0.04)
    keys = D.new()
    px = keys.load()
    for y in range(16):
        for x in range(16):
            px[x, y] = (244, 242, 234, 255) if x % 2 == 0 or y > 9 else (230, 228, 220, 255)
            if y <= 9 and x % 4 in (1, 2) and x % 8 != 6:
                px[x, y] = (24, 22, 22, 255)
    T("piano_keys", keys)
    shade = D.glow("shade_cream", (255, 240, 200), (230, 200, 140))
    px = shade.load()
    for x in range(0, 16, 2):
        px[x, 15] = (200, 150, 80, 255)
    D.glow("shade_glass", (255, 252, 240), (226, 220, 200))
    D.glow("shade_green", (90, 170, 100), (40, 110, 60))


IPAL = {}


def ipalette():
    colors = {"brass": (200, 160, 60), "cream": (236, 228, 200), "paper": (246, 246, 240), "ink": (40, 50, 110),
              "wood_dark": (90, 56, 34), "wood_light": (186, 138, 86), "chrome": (205, 210, 214), "steel": (140, 144, 150),
              "black": (26, 26, 28), "white": (240, 240, 236), "green": (40, 110, 60), "red": (180, 34, 34),
              "glass": (180, 210, 220), "bread": (196, 140, 70), "teal": (60, 150, 150), "grey": (110, 112, 116)}
    img = D.new()
    for i, (n, c) in enumerate(colors.items()):
        x, y = (i % 4) * 4, (i // 4) * 4
        D.noise_fill(img, c + (255,), 0.03, D.rng("ip" + n), (x, y, x + 4, y + 4))
        IPAL[n] = (x, y)
    T("ipalette", img)


def make_textures(decor):
    global D
    D = decor
    ipalette()
    kitchen_fronts()
    living_textures()
    office_textures()
    school_textures()
    shop_textures()
    hospital_textures()


# ---------------------------------------------------------------- models

def ip(name, f, t, faces=None):
    x, y = IPAL[name]
    faces = faces or D.FACES
    return D.box(f, t, "ip", faces, {fc: [x, y, x + 2, y + 2] for fc in faces})


def front_uv(f, t):
    """The north face's uv for the part of a 16x16 front texture it covers, as the viewer sees it (left = high x)."""
    return [16 - t[0], 16 - t[1], 16 - f[0], 16 - f[1]]


def fbox(f, t, front, side, top=None, bottom=None, full=False):
    """A box with its own texture on the front (north) face, `side` on the rest (`top` on the up face if given)."""
    tx = {fc: (side, None) for fc in D.FACES}
    tx["north"] = (front, [0, 0, 16, 16] if full else front_uv(f, t))
    if top:
        tx["up"] = (top, None)
    if bottom:
        tx["down"] = (bottom, None)
    return D.box(f, t, tx)


def add(name, textures, elements, light=0, color="OAK_TAN", sound="WOOD", mount="GROUND", ao=True):
    textures = dict(textures)
    textures.setdefault("ip", "ipalette")
    D.model(name, textures, elements, next(k for k in textures if k != "ip"), ao=ao)
    SPECS.append((name, mount, light, color, sound))


def legs(f, t, h, wood="wood_dark", w=1.5):
    x0, z0 = f
    x1, z1 = t
    return [ip(wood, (x, 0, z), (x + w, h, z + w)) for x in (x0, x1 - w) for z in (z0, z1 - w)]


def kitchen():
    for c in ("white", "wood"):
        body = "cab_white" if c == "white" else "veneer_light"
        tx = {"front": f"cab_{c}_front", "side": body, "top": "counter_top"}
        base = [ip("black", (0.5, 0, 2), (15.5, 1, 15)), fbox((0, 1, 1), (16, 14, 16), "front", "side"),
                D.box((0, 14, 0), (16, 16, 16), "top")]
        add(f"kitchen_cabinet_{c}", tx, base, color="WHITE" if c == "white" else "OAK_TAN")
        add(f"kitchen_sink_{c}", dict(tx, sink="sink_top"),
            base[:2] + [D.box((0, 14, 0), (16, 16, 16), {fc: ("sink" if fc == "up" else "top", [0, 0, 16, 16] if fc == "up" else None)
                                                          for fc in D.FACES}),
                        ip("chrome", (7.5, 16, 13), (8.5, 21, 14)), ip("chrome", (7.5, 20, 8), (8.5, 21, 13)),
                        ip("chrome", (5.5, 16.5, 13.5), (6.5, 17.5, 14.5)), ip("chrome", (9.5, 16.5, 13.5), (10.5, 17.5, 14.5))],
            color="WHITE" if c == "white" else "OAK_TAN", sound="METAL")
        add(f"wall_cupboard_{c}", {"front": f"cup_{c}_front", "side": body},
            [fbox((0, 2, 8.5), (16, 14, 16), "front", "side")], color="WHITE" if c == "white" else "OAK_TAN", mount="WALL")
    add("gas_stove", {"front": "stove_front", "side": "enamel", "top": "stove_top"},
        [fbox((0.5, 0, 1), (15.5, 14, 16), "front", "side", full=True), D.box((0.5, 14, 1), (15.5, 14.5, 16), {"up": ("top", [0, 0, 16, 16])}),
         D.box((0.5, 14, 14.5), (15.5, 19, 16), "side"),
         ip("red", (2.5, 14.5, 3), (6.5, 17.5, 7)), ip("black", (4, 17.5, 4.5), (5, 18, 5.5)), ip("red", (6.5, 16, 4.5), (8, 16.7, 5.5))],
        color="WHITE", sound="METAL")
    add("fridge_bottom", {"front": "fridge_lower", "side": "enamel"},
        [ip("black", (2, 0, 3), (14, 1, 15)), fbox((1, 1, 1.5), (15, 16, 16), "front", "side")], color="WHITE", sound="METAL")
    add("fridge_top", {"front": "fridge_upper", "side": "enamel"},
        [fbox((1, 0, 1.5), (15, 7, 16), "front", "side"), D.box((1.5, 7, 2), (14.5, 8, 15.5), "side"),
         ip("chrome", (12, 0, 0.5), (13, 5, 1.5)), ip("chrome", (3, 4, 1.2), (7, 5, 1.5))], color="WHITE", sound="METAL")


def seating():
    for col in ("red", "green", "brown", "blue"):
        fab = "fabric_" + col
        seat = [D.box((0, 2, 2), (16, 7, 16), "f"), D.box((0, 7, 3), (16, 9, 13), "f"), D.box((0, 7, 12), (16, 16, 16), "f")]
        for side, arm in (("left", (13, 2, 2)), ("right", (0, 2, 2))):
            els = seat + [D.box(arm, (arm[0] + 3, 11, 16), "f"), ip("wood_dark", (arm[0], 11, 2), (arm[0] + 3, 11.8, 16))]
            els += [ip("wood_dark", (x, 0, z), (x + 1.5, 2, z + 1.5)) for x in (1, 13.5) for z in (3, 14)]
            add(f"sofa_{side}_{col}", {"f": fab}, els, color={"red": "RED", "green": "GREEN", "brown": "BROWN", "blue": "BLUE"}[col],
                sound="WOOL")
        els = [D.box((1, 3, 2), (15, 7, 15), "f"), D.box((3, 7, 3), (13, 9, 13), "f"), D.box((3, 7, 12), (13, 16, 15), "f"),
               D.box((1, 3, 2), (3, 11, 15), "f"), D.box((13, 3, 2), (15, 11, 15), "f"),
               ip("wood_light", (1, 11, 1.5), (3, 11.8, 15)), ip("wood_light", (13, 11, 1.5), (15, 11.8, 15))]
        els += legs((1.5, 2.5), (14.5, 14.5), 3, "wood_light")
        add(f"armchair_{col}", {"f": fab}, els, color={"red": "RED", "green": "GREEN", "brown": "BROWN", "blue": "BLUE"}[col], sound="WOOL")
    els = legs((2.5, 2.5), (13.5, 13.5), 8) + [D.box((2, 8, 2), (14, 9.5, 14), "seat"),
                                                 ip("wood_dark", (2.5, 9.5, 12), (4, 17, 13.5)), ip("wood_dark", (12, 9.5, 12), (13.5, 17, 13.5)),
                                                 D.box((2.5, 13, 12.2), (13.5, 17, 13.3), "wood")]
    add("chair", {"seat": "fabric_brown", "wood": "veneer_mid"}, els)
    # a stool for the kitchen
    add("stool", {"wood": "veneer_light"}, legs((4, 4), (12, 12), 9, "wood_light") + [D.box((3.5, 9, 3.5), (12.5, 10.5, 12.5), "wood")])


def tables():
    cloth = [D.box((0, 13.5, 0), (16, 15, 16), {fc: ("cloth", [0, 0, 16, 16] if fc == "up" else None) for fc in D.FACES}),
             D.box((-0.3, 10, -0.3), (16.3, 13.5, 16.3), "lace", ["north", "south", "east", "west"])]
    base = legs((1.5, 1.5), (14.5, 14.5), 10)
    add("dining_table", {"cloth": "tablecloth", "lace": "tablecloth_side"}, base + cloth)
    samovar = [ip("brass", (5, 15, 9), (10, 16, 14)), ip("brass", (5.5, 16, 9.5), (9.5, 21, 13.5)), ip("brass", (6.5, 21, 10.5), (8.5, 22, 12.5)),
               ip("black", (7, 22, 11), (8, 24, 12)), ip("brass", (7, 17, 8), (8, 18, 9.5)), ip("black", (4.5, 18, 11), (5.5, 20, 12)),
               ip("black", (9.5, 18, 11), (10.5, 20, 12)), ip("white", (11, 15, 3), (13, 16.5, 5)), ip("white", (3, 15, 3), (5, 16.5, 5))]
    add("table_samovar", {"cloth": "tablecloth", "lace": "tablecloth_side"}, base + cloth + samovar)
    add("kitchen_table", {"cloth": "oilcloth", "wood": "veneer_light"},
        legs((1.5, 1.5), (14.5, 14.5), 13.5, "wood_light") + [D.box((0, 13.5, 0), (16, 15, 16), {fc: ("cloth" if fc == "up" else "wood",
                                                                                                    [0, 0, 16, 16] if fc == "up" else None)
                                                                                               for fc in D.FACES}),
                                                           ip("white", (6, 15, 6), (10, 16, 10)), ip("red", (6.5, 16, 6.5), (9.5, 16.3, 9.5))])
    add("coffee_table", {"wood": "veneer_dark"},
        legs((2, 2), (14, 14), 7) + [D.box((1, 7, 1), (15, 8.5, 15), "wood"), ip("teal", (7, 8.5, 7), (9, 12, 9)),
                                     ip("red", (6.5, 12, 6.5), (9.5, 13.5, 9.5)), ip("green", (7.5, 11.5, 6), (8.5, 12.5, 10))])


def cabinets():
    add("crt_tv", {"front": "tv_front", "side": "veneer_dark"},
        [ip("black", (x, 0, z), (x + 1, 6, z + 1)) for x in (3, 12) for z in (5, 12)] +
        [ip("black", (2, 5, 4), (14, 6, 14)), fbox((1, 6, 3), (15, 16, 13), "front", "side", full=True),
         D.box((3.5, 7.5, 13), (12.5, 14.5, 16), "side"),
         ip("chrome", (6, 16, 8), (6.6, 22, 8.6)), ip("chrome", (9.4, 16, 8), (10, 22, 8.6)), ip("black", (6, 16, 7.5), (10, 16.5, 9))])
    add("dresser", {"front": "dresser_front", "side": "veneer_mid"},
        [fbox((0, 1, 1), (16, 13, 16), "front", "side", full=True), D.box((0, 13, 0.5), (16, 14, 16), "side")] + legs((0.5, 1.5), (15.5, 15.5), 1))
    add("dresser_tv", {"front": "dresser_front", "side": "veneer_mid", "tv": "tv_front", "tvs": "veneer_dark"},
        [fbox((0, 0, 1), (16, 8, 16), "front", "side"), D.box((0, 8, 0.5), (16, 9, 16), "side"),
         D.box((3, 9, 5), (13, 16, 13), {fc: ("tv" if fc == "north" else "tvs", [0, 0, 16, 16] if fc == "north" else None) for fc in D.FACES}),
         D.box((5, 10, 13), (11, 15, 15), "tvs")])
    add("dresser_radio", {"front": "dresser_front", "side": "veneer_mid", "radio": "radio_front"},
        [fbox((0, 1, 1), (16, 13, 16), "front", "side", full=True), D.box((0, 13, 0.5), (16, 14, 16), "side"),
         D.box((2, 14, 6), (14, 21, 15), {fc: ("radio" if fc == "north" else "side", [0, 0, 16, 16] if fc == "north" else None) for fc in D.FACES})]
        + legs((0.5, 1.5), (15.5, 15.5), 1))
    stand = [fbox((2, 1, 3), (14, 11, 16), "front", "side", full=True), D.box((1.5, 11, 2.5), (14.5, 12, 16), "side")] + legs((2.5, 3.5), (13.5, 15.5), 1)
    add("nightstand", {"front": "nightstand_front", "side": "veneer_mid"}, stand)
    add("nightstand_lamp", {"front": "nightstand_front", "side": "veneer_mid", "shade": "shade_cream"},
        stand + [ip("brass", (6.5, 12, 8.5), (9.5, 13, 11.5)), ip("brass", (7.5, 13, 9.5), (8.5, 16, 10.5)),
                 D.box((5, 16, 7), (11, 21, 13), "shade")], light=10, ao=False)
    add("wardrobe_bottom", {"front": "wardrobe_lower", "side": "veneer_mid"},
        [fbox((0, 1, 1), (16, 16, 16), "front", "side", full=True)] + legs((0.5, 1.5), (15.5, 15.5), 1))
    add("wardrobe_top", {"front": "wardrobe_upper", "side": "veneer_mid"},
        [fbox((0, 0, 1), (16, 14, 16), "front", "side", full=True), D.box((-0.5, 14, 0.5), (16.5, 15.5, 16), "side")])
    for name in ("stenka_doors", "stenka_glass", "stenka_books"):
        add(name, {"front": name, "side": "veneer_dark"}, [fbox((0, 0, 0.5), (16, 16, 16), "front", "side", full=True)])
    add("bookcase", {"front": "bookcase_front", "side": "veneer_mid"}, [fbox((0, 0, 1), (16, 16, 16), "front", "side", full=True)])
    add("piano", {"black": "lacquer_black", "keys": "piano_keys"},
        [D.box((0, 0, 6), (16, 17, 16), "black"), D.box((0, 7, 1.5), (16, 8.5, 6), {fc: ("keys" if fc == "up" else "black",
                                                                                        [0, 0, 16, 16] if fc == "up" else None) for fc in D.FACES}),
         D.box((0.5, 0, 2), (1.5, 7, 3), "black"), D.box((14.5, 0, 2), (15.5, 7, 3), "black"),
         ip("brass", (4, 0.5, 5.5), (5, 1.5, 6)), ip("brass", (11, 0.5, 5.5), (12, 1.5, 6)),
         ip("brass", (2, 17, 10), (4, 19, 12)), ip("white", (6, 11, 5.5), (10, 15, 6))])


def walls():
    for side in ("left", "right"):
        add(f"wall_carpet_{side}", {"c": f"carpet_{side}"},
            [D.box((0, 0, 15.2), (16, 16, 16), {fc: ("c", [0, 0, 16, 16] if fc == "north" else [0, 0, 1, 1]) for fc in D.FACES})],
            color="RED", sound="WOOL", mount="WALL")
    for col in ("red", "green"):
        add(f"rug_{col}", {"r": f"rug_{col}"}, [D.box((0, 0, 0), (16, 0.5, 16), {fc: ("r", [0, 0, 16, 16] if fc == "up" else [0, 0, 16, 0.5])
                                                                             for fc in D.FACES})],
            color="RED" if col == "red" else "GREEN", sound="WOOL")
    add("wall_clock", {"face": "clock_face", "wood": "veneer_mid"},
        [D.box((3.5, 3.5, 14.8), (12.5, 12.5, 16), {fc: ("face" if fc == "north" else "wood", [0, 0, 16, 16] if fc == "north" else None)
                                                     for fc in D.FACES}), ip("brass", (7.5, 1, 15.3), (8.5, 3.5, 15.8))], mount="WALL")
    for name in ("picture_forest", "picture_sea"):
        add(name, {"p": name}, [D.box((1, 2, 15), (15, 14, 16), {fc: ("p", [0, 0, 16, 16] if fc == "north" else [0, 0, 1, 1]) for fc in D.FACES})],
            mount="WALL")
    add("radiator", {"m": "enamel"},
        [D.box((x, 1, 12.5), (x + 1.6, 12, 15.5), "m") for x in (0.2, 2.2, 4.2, 6.2, 8.2, 10.2, 12.2, 14.2)] +
        [D.box((0, 2, 13.3), (16, 3, 14.7), "m"), D.box((0, 10, 13.3), (16, 11, 14.7), "m")], color="WHITE", sound="METAL", mount="WALL")


def lights():
    add("chandelier", {"glass": "shade_glass"},
        [ip("brass", (6.5, 15.5, 6.5), (9.5, 16, 9.5)), ip("brass", (7.5, 10, 7.5), (8.5, 15.5, 8.5)), ip("brass", (2.5, 10, 7.5), (13.5, 11, 8.5)),
         ip("brass", (7.5, 10, 2.5), (8.5, 11, 13.5)),
         D.box((1.5, 8, 6), (5, 11.5, 10), "glass"), D.box((11, 8, 6), (14.5, 11.5, 10), "glass"),
         D.box((6, 8, 1.5), (10, 11.5, 5), "glass"), D.box((6, 8, 11), (10, 11.5, 14.5), "glass")], light=15, color="GOLD", sound="GLASS", ao=False)
    add("sconce", {"glass": "shade_glass"},
        [ip("brass", (6.5, 7, 15.2), (9.5, 12, 16)), ip("brass", (7.5, 9, 12), (8.5, 10, 15.2)), D.box((5, 9, 9), (11, 14, 15), "glass")],
        light=12, color="GOLD", sound="GLASS", mount="WALL", ao=False)
    add("floor_lamp", {"shade": "shade_cream"},
        [ip("black", (5, 0, 5), (11, 1, 11)), ip("brass", (7.5, 1, 7.5), (8.5, 11, 8.5)), D.box((4, 11, 4), (12, 17, 12), "shade"),
         D.box((5, 17, 5), (11, 18, 11), "shade")], light=13, color="GOLD", sound="METAL", ao=False)
    add("table_lamp", {"shade": "shade_green"},
        [ip("brass", (5, 0, 5), (11, 1, 11)), ip("brass", (7.5, 1, 7.5), (8.5, 7, 8.5)), D.box((4, 7, 5), (12, 10, 11), "shade"),
         ip("brass", (4, 6.5, 5), (12, 7, 11))], light=12, color="GREEN", sound="GLASS", ao=False)
    add("office_lamp", {"tube": "lamp_tube", "m": "enamel"},
        [D.box((1.5, 14.5, 3), (14.5, 16, 13), {fc: ("tube" if fc == "down" else "m", [0, 0, 16, 16] if fc == "down" else None) for fc in D.FACES})],
        light=15, color="WHITE", sound="GLASS", ao=False)


def office():
    desk = [D.box((0, 12, 0), (16, 14, 16), {fc: ("top" if fc == "up" else "wood", [0, 0, 16, 16] if fc == "up" else None) for fc in D.FACES}),
            fbox((9, 0, 1), (16, 12, 15), "drawers", "wood", full=True), D.box((0, 0, 1), (1.5, 12, 15), "wood"),
            D.box((1.5, 3, 13.5), (9, 12, 15), "wood")]
    tx = {"top": "desk_top", "wood": "veneer_dark", "drawers": "desk_drawers", "shade": "shade_green"}
    add("office_desk", tx, desk + [ip("paper", (3, 14, 4), (8, 14.2, 10)), ip("ink", (9, 14, 9), (10, 15.5, 10))])
    add("office_desk_lamp", tx, desk + [ip("brass", (11, 14, 10), (14, 14.6, 13)), ip("brass", (12.2, 14.6, 11.2), (12.8, 18, 11.8)),
                                        D.box((10, 18, 9.5), (15, 20, 13), "shade"), ip("paper", (3, 14, 4), (8, 14.2, 10)),
                                        ip("black", (2, 14, 11), (6, 15.5, 14)), ip("black", (2.5, 15.5, 11.5), (5.5, 16.3, 13.5))], light=10)
    add("office_desk_typewriter", tx, desk + [ip("black", (4, 14, 3), (12, 16, 9)), ip("black", (4, 16, 7), (12, 17.5, 10)),
                                              ip("paper", (5, 17, 8.5), (11, 21, 9)), ip("grey", (4.5, 16, 3.2), (11.5, 16.4, 6))])
    add("filing_cabinet", {"front": "folders_front", "side": "veneer_dark"}, [fbox((0, 0, 0.5), (16, 16, 16), "front", "side", full=True)])
    add("safe", {"front": "safe_front", "side": "safe_side"}, [fbox((2, 0, 2), (14, 14, 16), "front", "side", full=True)],
        color="GREEN", sound="METAL")


def hospital():
    tx = {"sheet": "sheet", "blanket": "blanket", "m": "enamel"}
    frame = [D.box((x, 0, z), (x + 1, 6, z + 1), "m") for x in (0.5, 14.5) for z in (0.5, 14.5)] + [D.box((0.5, 5, 0.5), (15.5, 6, 15.5), "m")]
    add("hospital_bed_head", tx, frame + [D.box((1, 6, 0), (15, 8, 16), "sheet"), D.box((1, 8, 0), (15, 8.6, 10), "blanket"),
                                          D.box((2, 8, 11), (14, 10, 15), "sheet"), D.box((0.5, 0, 15), (15.5, 14, 16), "m")],
        color="WHITE", sound="METAL")
    add("hospital_bed_foot", tx, frame + [D.box((1, 6, 0), (15, 8, 16), "sheet"), D.box((1, 8, 0), (15, 8.6, 16), "blanket"),
                                          D.box((0.5, 0, 0), (15.5, 11, 1), "m")], color="WHITE", sound="METAL")
    add("iv_stand", {"m": "enamel"}, [ip("chrome", (4, 0, 7.5), (12, 1, 8.5)), ip("chrome", (7.5, 0, 4), (8.5, 1, 12)),
                                      ip("chrome", (7.6, 1, 7.6), (8.4, 26, 8.4)), ip("chrome", (5, 25, 7.6), (11, 26, 8.4)),
                                      ip("glass", (4.5, 19, 7), (6.5, 24, 9)), ip("glass", (9.5, 20, 7), (11.5, 24, 9))],
        color="LIGHT_GRAY", sound="METAL")
    add("medicine_cabinet", {"front": "med_glass", "side": "enamel"}, [fbox((1, 0, 4), (15, 16, 16), "front", "side", full=True)],
        color="WHITE", sound="METAL")
    add("med_screen", {"c": "screen_cloth"}, [D.box((0, 1, 7), (16, 15, 8.2), "c")] +
        [ip("white", (x, 0, 6.5), (x + 1, 16, 8.7)) for x in (0, 15)] + [ip("white", (0, 15, 6.5), (16, 16, 8.7))], color="WHITE", sound="WOOL")
    add("couch", {"l": "leather"}, legs((1, 2), (15, 15), 5, "white") + [D.box((0, 5, 2), (16, 8, 15), "l"), D.box((12, 8, 2), (16, 10.5, 15), "l")],
        color="BROWN", sound="WOOL")


def school():
    el = [D.box((0, 0, 1), (1.5, 11, 15), "wood"), D.box((14.5, 0, 1), (16, 11, 15), "wood"), D.box((1.5, 0.5, 6), (14.5, 1.5, 7.5), "wood"),
          D.box((0, 11, 0), (16, 12.5, 7), {fc: ("green", None) for fc in D.FACES}, rotation={"origin": [8, 11, 7], "axis": "x", "angle": 22.5}),
          D.box((1.5, 6.5, 9), (14.5, 7.5, 14), "wood"), D.box((1.5, 7.5, 14), (14.5, 12, 15), "wood"),
          D.box((1.5, 7, 1), (14.5, 8, 6), "wood"), ip("ink", (12, 12.5, 6.5), (13, 13, 7))]
    add("school_desk", {"wood": "veneer_mid", "green": "paint_green"}, el, color="GREEN")
    for side in ("left", "right"):
        add(f"blackboard_{side}", {"b": f"blackboard_{side}", "wood": "veneer_mid"},
            [D.box((0, 0, 15), (16, 16, 16), {fc: ("b" if fc == "north" else "wood", [0, 0, 16, 16] if fc == "north" else None) for fc in D.FACES}),
             D.box((0, 0, 13.5), (16, 1, 15), "wood"), ip("white", (4, 1, 14) if side == "left" else (10, 1, 14), (6, 1.6, 14.6) if side == "left"
                                                        else (12, 1.6, 14.6))], color="GREEN", mount="WALL")


def shop():
    for name, tx in (("shop_shelf_tins", "shelf_tins"), ("shop_shelf_bread", "shelf_bread"), ("shop_shelf_bottles", "shelf_bottles"),
                     ("shop_shelf_jars", "shelf_jars")):
        add(name, {"front": tx, "side": "counter_side"}, [fbox((0, 0, 1), (16, 16, 16), "front", "side", full=True)], sound="WOOD")
    counter = [fbox((0, 0, 2), (16, 14, 16), "front", "side", full=True), D.box((0, 14, 1), (16, 15, 16), "top")]
    tx = {"front": "counter_front", "side": "counter_side", "top": "counter_top", "dial": "scales_dial"}
    add("shop_counter", tx, counter)
    add("shop_counter_scales", tx, counter + [ip("white", (4, 15, 6), (12, 17, 12)),
                                              D.box((5, 17, 8), (11, 23, 9.5), {fc: ("dial" if fc == "north" else "side",
                                                                                     [0, 0, 16, 16] if fc == "north" else None) for fc in D.FACES}),
                                              ip("steel", (2, 17, 3), (9, 17.5, 8)), ip("steel", (11, 17, 9), (15, 17.5, 13))])
    add("cash_register", tx, counter + [ip("grey", (3, 15, 5), (13, 19, 13)), ip("grey", (3, 19, 9), (13, 22, 13)),
                                        ip("black", (4, 19, 5), (12, 19.4, 9)), ip("white", (5, 20, 8.8), (11, 21.5, 9.2)),
                                        ip("chrome", (13, 17, 7), (14.5, 18, 8)), ip("chrome", (14, 16, 7), (15, 21, 8))], sound="METAL")
    add("freezer", {"front": "freezer_front", "side": "enamel", "top": "freezer_top"},
        [fbox((0, 0, 2), (16, 12, 15), "front", "side", full=True), D.box((0, 12, 2), (16, 12.5, 15), {"up": ("top", [0, 0, 16, 16])}),
         D.box((0, 12, 2), (16, 13, 2.8), "side"), D.box((0, 12, 14.2), (16, 13, 15), "side")], color="WHITE", sound="METAL")


NAMES = {
    "kitchen_cabinet_white": ("Кухонный шкафчик (белый)", "Kitchen cabinet (white)"),
    "kitchen_cabinet_wood": ("Кухонный шкафчик (под дерево)", "Kitchen cabinet (wood)"),
    "kitchen_sink_white": ("Мойка (белая)", "Kitchen sink (white)"), "kitchen_sink_wood": ("Мойка (под дерево)", "Kitchen sink (wood)"),
    "wall_cupboard_white": ("Навесной шкафчик (белый)", "Wall cupboard (white)"),
    "wall_cupboard_wood": ("Навесной шкафчик (под дерево)", "Wall cupboard (wood)"),
    "gas_stove": ("Газовая плита", "Gas stove"), "fridge_bottom": ("Холодильник ЗИЛ (низ)", "ZIL fridge (bottom)"),
    "fridge_top": ("Холодильник ЗИЛ (верх)", "ZIL fridge (top)"),
    "chair": ("Стул", "Chair"), "stool": ("Табуретка", "Stool"), "dining_table": ("Стол со скатертью", "Table with a tablecloth"),
    "table_samovar": ("Стол с самоваром", "Table with a samovar"), "kitchen_table": ("Кухонный стол", "Kitchen table"),
    "coffee_table": ("Журнальный столик", "Coffee table"), "crt_tv": ("Телевизор «Рубин»", "Rubin TV"), "dresser": ("Комод", "Dresser"),
    "dresser_tv": ("Комод с телевизором", "Dresser with a TV"), "dresser_radio": ("Комод с радиолой", "Dresser with a radio"),
    "nightstand": ("Тумбочка", "Nightstand"), "nightstand_lamp": ("Тумбочка с ночником", "Nightstand with a lamp"),
    "wardrobe_bottom": ("Шифоньер (низ)", "Wardrobe (bottom)"), "wardrobe_top": ("Шифоньер (верх)", "Wardrobe (top)"),
    "stenka_doors": ("Стенка: шкафчики", "Wall unit: cupboards"), "stenka_glass": ("Стенка: хрусталь", "Wall unit: crystal"),
    "stenka_books": ("Стенка: книги", "Wall unit: books"), "bookcase": ("Книжный шкаф", "Bookcase"), "piano": ("Пианино", "Piano"),
    "wall_carpet_left": ("Ковёр на стену (левый)", "Wall carpet (left)"), "wall_carpet_right": ("Ковёр на стену (правый)", "Wall carpet (right)"),
    "rug_red": ("Палас (красный)", "Rug (red)"), "rug_green": ("Палас (зелёный)", "Rug (green)"), "wall_clock": ("Настенные часы", "Wall clock"),
    "picture_forest": ("Картина «Берёзы»", "Painting: birches"), "picture_sea": ("Картина «Море»", "Painting: the sea"),
    "radiator": ("Чугунная батарея", "Cast-iron radiator"), "chandelier": ("Люстра", "Chandelier"), "sconce": ("Бра", "Wall lamp"),
    "floor_lamp": ("Торшер", "Floor lamp"), "table_lamp": ("Настольная лампа", "Table lamp"), "office_lamp": ("Потолочный светильник", "Ceiling light"),
    "office_desk": ("Письменный стол", "Desk"), "office_desk_lamp": ("Стол с лампой и телефоном", "Desk with a lamp and a phone"),
    "office_desk_typewriter": ("Стол с пишущей машинкой", "Desk with a typewriter"), "filing_cabinet": ("Шкаф с папками", "Filing cabinet"),
    "safe": ("Сейф", "Safe"), "hospital_bed_head": ("Больничная койка (изголовье)", "Hospital bed (head)"),
    "hospital_bed_foot": ("Больничная койка (ноги)", "Hospital bed (foot)"), "iv_stand": ("Капельница", "IV stand"),
    "medicine_cabinet": ("Медицинский шкаф", "Medicine cabinet"), "med_screen": ("Ширма", "Screen"), "couch": ("Кушетка", "Examination couch"),
    "school_desk": ("Парта", "School desk"), "blackboard_left": ("Доска (левая)", "Blackboard (left)"),
    "blackboard_right": ("Доска (правая)", "Blackboard (right)"), "shop_shelf_tins": ("Стеллаж с консервами", "Shelf with tins"),
    "shop_shelf_bread": ("Стеллаж с хлебом", "Bread shelf"), "shop_shelf_bottles": ("Стеллаж с бутылками", "Shelf with bottles"),
    "shop_shelf_jars": ("Стеллаж с соленьями", "Shelf with pickle jars"), "shop_counter": ("Прилавок-витрина", "Glass counter"),
    "shop_counter_scales": ("Прилавок с весами", "Counter with scales"), "cash_register": ("Касса", "Till"),
    "freezer": ("Ларь с мороженым", "Ice cream freezer"),
}
COLOURS = {"red": "красный", "green": "зелёный", "brown": "коричневый", "blue": "синий"}
for _c, _ru in COLOURS.items():
    NAMES[f"sofa_left_{_c}"] = (f"Диван, левая половина ({_ru})", f"Sofa, left half ({_c})")
    NAMES[f"sofa_right_{_c}"] = (f"Диван, правая половина ({_ru})", f"Sofa, right half ({_c})")
    NAMES[f"armchair_{_c}"] = (f"Кресло ({_ru})", f"Armchair ({_c})")


def make_models():
    kitchen(); seating(); tables(); cabinets(); walls(); lights(); office(); hospital(); school(); shop()
    missing = [s[0] for s in SPECS if s[0] not in NAMES]
    assert not missing, missing
    D.NAMES.update(NAMES)


def outline(name):
    """The block's outline: the bounding box of its model, kept inside the cell."""
    els = D.MODELS[name]["elements"]
    lo = [max(0.0, min(e["from"][i] for e in els)) for i in range(3)]
    hi = [min(16.0, max(e["to"][i] for e in els)) for i in range(3)]
    hi = [max(h, l + 0.5) for l, h in zip(lo, hi)]
    return lo + hi


def write_java(root):
    """The block list for io.github.mobtalk.game.CityInterior: ids, mounts, outlines, light, map colours, sounds."""
    def num(v):
        return f"{v:g}"
    rows = []
    for name, mount, light, color, sound in SPECS:
        b = outline(name)
        rows.append(f'            new CityContent.Spec("{name}", CityContent.Mount.{mount}, new double[][] {{{{{", ".join(num(v) for v in b)}}}}}, '
                    f'{light}, MapColor.{color}, BlockSoundGroup.{sound}, true)')
    src = ('package io.github.mobtalk.game;\n\nimport java.util.List;\nimport net.minecraft.block.MapColor;\n'
           'import net.minecraft.sound.BlockSoundGroup;\n\n'
           '/** Indoor furniture of the city: flats, offices, the hospital, the school, shops. Generated by tools/city/decor.py\n'
           ' * (tools/city/interior.py draws the models); tools/city/furnish.py puts them in the buildings. Do not edit by hand. */\n'
           'final class CityInterior {\n    private CityInterior() { }\n\n    static final List<CityContent.Spec> SPECS = List.of(\n'
           + ",\n".join(rows) + ");\n}\n")
    path = f"{root}/src/main/java/io/github/mobtalk/game/CityInterior.java"
    with open(path, "w", encoding="utf-8") as f:
        f.write(src)
