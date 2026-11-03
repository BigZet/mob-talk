"""The grand casino's own furniture (tools/casino/decor.py calls these): the big neon letters of the facade sign, the running-bulb
marquee that frames it, the three-tier crystal chandelier of the hall and the cashier's cage. Same pixel-art rules as decor.py:
16x16 textures on one texel grid, a limited palette; the bulbs and letters are animated textures (a .mcmeta beside the png).
"""
import math

D = None  # the tools/casino/decor.py module, set by it before any call (it runs as __main__)

# The sign's alphabet: «КАЗИНО», each letter two blocks wide and two tall (four cells of a 32x32 board), and a gold star one block
# wide between the words. 5x6 glyphs; the neon tube runs through the middles of the lit cells.
GLYPHS = {
    "К": ["#...#", "#..#.", "###..", "#..#.", "#...#", "#...#"],
    "А": [".###.", "#...#", "#...#", "#####", "#...#", "#...#"],
    "З": ["####.", "....#", ".###.", "....#", "....#", "####."],
    "И": ["#...#", "#..##", "#.#.#", "##..#", "#...#", "#...#"],
    "Н": ["#...#", "#...#", "#####", "#...#", "#...#", "#...#"],
    "О": [".###.", "#...#", "#...#", "#...#", "#...#", ".###."],
    "Ф": ["..#..", ".###.", "#.#.#", "#.#.#", ".###.", "..#.."],
    "Р": ["####.", "#...#", "#...#", "####.", "#....", "#...."],
    "Т": ["#####", "..#..", "..#..", "..#..", "..#..", "..#.."],
    "У": ["#...#", "#...#", ".#..#", "..##.", "..#..", "##..."],
}
LETTERS = "КАЗИНО★ФРТУ"  # the star stays at 6: the old signs keep their letters
STAR = 6
NEON = (255, 64, 84)
NEON_HOT = (255, 186, 190)
NEON_GLOW = (122, 18, 40)
BOARD = (26, 16, 24)
BULB = (255, 244, 196)
BULB_DIM = (150, 96, 40)


def animated(name, frames, frametime):
    """A vertical strip of 16x16 frames: Minecraft plays it top to bottom."""
    img = D.new(16, 16 * len(frames))
    for i, f in enumerate(frames):
        img.paste(f, (0, 16 * i))
    D.TEX[name] = img
    D.ANIM[name] = {"animation": {"frametime": frametime, "interpolate": False}}
    return img


def bulb(px, x, y, lit):
    """A 2x2 bulb: a hot highlight on the top-left when lit, a dark amber glass when not."""
    if lit:
        px[x, y] = D.rgba((255, 255, 236))
        px[x + 1, y] = D.rgba(BULB)
        px[x, y + 1] = D.rgba(BULB)
        px[x + 1, y + 1] = D.rgba((236, 196, 110))
    else:
        px[x, y] = D.rgba((178, 120, 52))
        px[x + 1, y] = px[x, y + 1] = D.rgba(BULB_DIM)
        px[x + 1, y + 1] = D.rgba((104, 64, 28))


def board(w, h, phase, seed):
    """A black lacquer board with a gold frame and bulbs round it every four texels, the two halves lit in turn."""
    img = D.new(w, h)
    D.noise_fill(img, BOARD, 0.08, D.rng(seed))
    px = img.load()
    for x in range(w):
        px[x, 0] = D.rgba(D.GOLD)
        px[x, h - 1] = D.rgba(D.GOLD_DARK)
    for y in range(h):
        px[0, y] = D.rgba(D.GOLD)
        px[w - 1, y] = D.rgba(D.GOLD_DARK)
    spots = [(x, 0) for x in range(0, w - 1, 4)] + [(w - 2, y) for y in range(4, h - 1, 4)] + \
            [(x, h - 2) for x in range(w - 6, -1, -4)] + [(0, y) for y in range(h - 6, 3, -4)]
    for i, (x, y) in enumerate(spots):
        bulb(px, min(x, w - 2), min(y, h - 2), (i + phase) % 2 == 0)
    return img


def neon(img, tube_px, colours):
    """Neon tubes: a dark glow two texels out, a brighter one next to the tube, the tube, its hot upper-left edge."""
    tube, hot, glow = colours
    px = img.load()
    w, h = img.size
    near = {}
    for (x, y) in tube_px:
        for dx in range(-2, 3):
            for dy in range(-2, 3):
                p = (x + dx, y + dy)
                if 2 <= p[0] < w - 2 and 2 <= p[1] < h - 2 and p not in tube_px:
                    near[p] = min(near.get(p, 9), max(abs(dx), abs(dy)))
    for p, d in near.items():
        px[p] = D.rgba(glow) if d == 1 else D.shade(glow, 0.55)
    for (x, y) in tube_px:
        px[x, y] = D.rgba(tube)
        if (x - 1, y) not in tube_px or (x, y - 1) not in tube_px:
            px[x, y] = D.rgba(hot)


def brush(points, x, y):
    for dx in (0, 1):
        for dy in (0, 1):
            points.add((x + dx, y + dy))


def letter_board(ch, phase):
    """A 32x32 board with one letter: tubes two texels thick joining the middles of neighbouring lit cells (diagonal cells too,
    where no straight neighbour already joins them)."""
    img = board(32, 32, phase, "letter" + ch)
    grid = GLYPHS[ch]
    lit = {(gx, gy) for gy, row in enumerate(grid) for gx, c in enumerate(row) if c == "#"}
    ox, oy, cell = 6, 4, 4

    def centre(gx, gy):
        return ox + gx * cell + 1, oy + gy * cell + 1
    tube = set()
    for (gx, gy) in lit:
        cx, cy = centre(gx, gy)
        brush(tube, cx, cy)
        for (dx, dy) in ((1, 0), (0, 1), (1, 1), (-1, 1)):
            n = (gx + dx, gy + dy)
            if n not in lit:
                continue
            if dx and dy and ((gx + dx, gy) in lit or (gx, gy + dy) in lit):
                continue
            nx, ny = centre(*n)
            for k in range(cell + 1):
                brush(tube, cx + (nx - cx) * k // cell, cy + (ny - cy) * k // cell)
    neon(img, tube, (NEON, NEON_HOT, NEON_GLOW))
    return img


def star_board(phase):
    """A 16x32 board with a gold five-pointed star."""
    img = board(16, 32, phase, "star")
    tube = set()
    cx, cy, r_out, r_in = 7.5, 15.5, 7.0, 3.0
    pts = [(cx + (r_out if k % 2 == 0 else r_in) * math.sin(math.pi * k / 5), cy - (r_out if k % 2 == 0 else r_in) * math.cos(math.pi * k / 5))
           for k in range(10)]

    def inside(x, y):
        c = False
        for i in range(10):
            (x0, y0), (x1, y1) = pts[i], pts[(i + 1) % 10]
            if (y0 > y) != (y1 > y) and x < (x1 - x0) * (y - y0) / (y1 - y0) + x0:
                c = not c
        return c
    for x in range(16):
        for y in range(32):
            if inside(x + 0.5, y + 0.5):
                tube.add((x, y))
    neon(img, tube, ((250, 200, 60), (255, 248, 200), (120, 76, 14)))
    return img


def quarter(img, part):
    """The 16x16 cell of a board: part 0 top left, 1 top right, 2 bottom left, 3 bottom right (a one-wide board has 0 and 2)."""
    x = 16 * (part % 2) if img.width > 16 else 0
    y = 16 * (part // 2)
    return img.crop((x, y, x + 16, y + 16))


def marquee_frame(phase):
    """Running lights: a black lacquer plate with three rows of three bulbs in brass sockets; two of every three burn, and the dark
    gap walks diagonally from frame to frame."""
    img = D.new()
    r = D.rng("marquee")
    D.noise_fill(img, (34, 20, 22), 0.08, r)
    px = img.load()
    for i in range(16):
        px[i, 0] = D.shade(D.GOLD, 1.15)
        px[0, i] = D.shade(D.GOLD, 1.05)
        px[i, 15] = D.rgba(D.GOLD_DARK)
        px[15, i] = D.rgba(D.GOLD_DARK)
    for gy, y in enumerate((2, 7, 12)):
        for gx, x in enumerate((2, 7, 12)):
            lit = (gx + gy + phase) % 3 != 0
            for (sx, sy) in ((x - 1, y - 1), (x + 3, y - 1), (x - 1, y + 3), (x + 3, y + 3)):
                if 0 < sx < 15 and 0 < sy < 15:
                    px[sx, sy] = D.rgba((150, 104, 40))  # the socket's brass corners
            for dx in range(3):
                for dy in range(3):
                    edge = dx in (0, 2) and dy in (0, 2)
                    if lit:
                        c = (255, 236, 170) if edge else (255, 252, 230)
                    else:
                        c = (92, 52, 24) if edge else (140, 86, 34)
                    px[x + dx, y + dy] = D.rgba(c)
            if lit:
                px[x, y] = D.rgba((255, 255, 255))
                for (gx2, gy2) in ((x + 1, y - 1), (x - 1, y + 1), (x + 3, y + 1), (x + 1, y + 3)):
                    if 0 < gx2 < 15 and 0 < gy2 < 15:
                        px[gx2, gy2] = D.rgba((196, 120, 40))  # a warm halo
    return img


def parts(letter):
    return (0, 2) if letter == STAR else (0, 1, 2, 3)


def textures():
    for i, ch in enumerate(LETTERS):
        boards = [star_board(p) if i == STAR else letter_board(ch, p) for p in (0, 1)]
        for part in parts(i):
            animated(f"letter_{i}_{part}", [quarter(b, part) for b in boards], 10)
    animated("marquee", [marquee_frame(p) for p in range(3)], 5)


# ---------------------------------------------------------------- models

def letter_models():
    """A cell of a neon letter board for a facade, its back on the wall (south side), as deep as a sign."""
    for i in range(len(LETTERS)):
        for part in parts(i):
            t = f"letter_{i}_{part}"
            face = {"north": t, "south": "walnut", "east": "gold_trim", "west": "gold_trim", "up": "gold_trim", "down": "gold_trim"}
            D.model(f"casino_{t}", {t: t, "walnut": "walnut", "gold_trim": "gold_trim"}, [D.box((0, 0, 12.5), (16, 16, 16), face)],
                    particle="gold_trim", ao=False)


def marquee_model():
    face = {"north": "marquee", "south": "gold_trim", "east": "gold_trim", "west": "gold_trim", "up": "gold_trim", "down": "gold_trim"}
    D.model("casino_marquee", {"marquee": "marquee", "gold_trim": "gold_trim"}, [D.box((0, 0, 14), (16, 16, 16), face)],
            particle="gold_trim", ao=False)


def ring(half, y, thick, name, els, garland=True, w=1.5):
    """A square ring around the block's centre and the same ring turned 45 degrees: an eight-pointed gold star seen from below.
    Under it hangs a garland of crystal beads along every bar."""
    lo, hi = 8 - half, 8 + half
    if garland:
        ring(half - 0.3, y - 1.4, 0.8, "crystal", els, False, 0.9)
    for rot in (None, {"origin": [8, y, 8], "axis": "y", "angle": 45}):
        for (f, t) in (((lo, y, lo), (hi, y + thick, lo + w)), ((lo, y, hi - w), (hi, y + thick, hi)),
                       ((lo, y, lo + w), (lo + w, y + thick, hi - w)), ((hi - w, y, lo + w), (hi, y + thick, hi - w))):
            els.append(D.pal_box(f, t, name, rotation=rot))


def points(half, n, turn=0.0):
    """n points evenly round a circle of the ring's size (the drops and candles stand on it)."""
    out = []
    for k in range(n):
        a = math.radians(turn + 360.0 * k / n)
        out.append((8 + half * math.cos(a), 8 + half * math.sin(a)))
    return out


def star(half, n=16):
    """Sixteen points on the star of two rings: the middles of the eight sides, and between them, 22.5 degrees off, the crossings
    of the two squares (a little further out)."""
    out = []
    for k in range(n):
        a = math.radians(360.0 * k / n)
        r = half if k % 2 == 0 else half / math.cos(math.radians(22.5))
        out.append((8 + r * math.cos(a), 8 + r * math.sin(a)))
    return out


def candle(x, z, y, els):
    els.append(D.pal_box((x - 0.7, y, z - 0.7), (x + 0.7, y + 0.6, z + 0.7), "gold"))
    els.append(D.pal_box((x - 0.4, y + 0.6, z - 0.4), (x + 0.4, y + 2.8, z + 0.4), "candle"))
    els.append(D.pal_box((x - 0.3, y + 2.8, z - 0.3), (x + 0.3, y + 3.7, z + 0.3), "flame"))
    els.append(D.pal_box((x - 0.15, y + 2.9, z - 0.15), (x + 0.15, y + 3.3, z + 0.15), "flame_core"))


def drop(x, z, y, length, els, big=False):
    """A hanging crystal: a thread of beads and a faceted drop at the end."""
    w = 0.45
    els.append(D.pal_box((x - w, y - length, z - w), (x + w, y, z + w), "crystal"))
    s = 1.2 if big else 0.85
    els.append(D.pal_box((x - s, y - length - 1.6, z - s), (x + s, y - length, z + s), "crystal_glint"))
    els.append(D.pal_box((x - s * 0.5, y - length - 2.3, z - s * 0.5), (x + s * 0.5, y - length - 1.6, z + s * 0.5), "crystal"))


def grand_chandelier():
    """Three tiers of gold rings, candles and hanging crystals, three blocks across; it hangs from the ceiling block above it
    and reaches into the block below (the model spans y -16..16)."""
    els = [D.pal_box((4, 15, 4), (12, 16, 12), "gold"), D.pal_box((5.5, 13.5, 5.5), (10.5, 15, 10.5), "gold_dark"),
           D.pal_box((7.3, -9, 7.3), (8.7, 13.5, 8.7), "gold_dark")]
    for y in (10, 3, -5):  # knots on the stem
        els.append(D.pal_box((6.5, y, 6.5), (9.5, y + 1.5, 9.5), "gold"))
    # top tier: small ring, eight short drops
    ring(5, 8, 0.8, "gold", els)
    els.append(D.pal_box((3, 8.1, 7.6), (13, 8.7, 8.4), "gold_dark"))
    els.append(D.pal_box((7.6, 8.1, 3), (8.4, 8.7, 13), "gold_dark"))
    for (x, z) in points(4.5, 8):
        drop(x, z, 8, 2.2, els)
    # middle tier: candles on the ring, a curtain of drops under it
    ring(12, 1, 1, "gold", els)
    for (f, t) in (((-4, 1.1, 7.5), (20, 1.8, 8.5)), ((7.5, 1.1, -4), (8.5, 1.8, 20))):
        els.append(D.pal_box(f, t, "gold_dark"))
    for (x, z) in points(11.5, 8):
        candle(x, z, 2, els)
    for k, (x, z) in enumerate(star(11.5, 16)):
        drop(x, z, 1, 2.5 + (k % 2) * 1.8, els)
    # bottom tier: the widest ring, candles and long drops
    ring(19, -7, 1.2, "gold", els)
    for (f, t) in (((-11, -6.9, 7.4), (27, -6.2, 8.6)), ((7.4, -6.9, -11), (8.6, -6.2, 27))):
        els.append(D.pal_box(f, t, "gold_dark"))
    for (x, z) in points(18.4, 8):
        candle(x, z, -5.8, els)
    for k, (x, z) in enumerate(star(18.4, 16)):
        drop(x, z, -7, 2.5 + (k % 3) * 1.4, els, big=k % 2 == 0)
    # the crystal ball under it all
    els.append(D.pal_box((6, -13, 6), (10, -9, 10), "crystal_glint"))
    els.append(D.pal_box((6.6, -15, 6.6), (9.4, -13, 9.4), "crystal"))
    els.append(D.pal_box((7.4, -16, 7.4), (8.6, -15, 8.6), "crystal_glint"))
    D.model("casino_grand_chandelier", {"palette": "palette"}, els, ao=False)


def cashier():
    """The cashier's window: a mahogany counter, a gold grille with a small window in it, chips and coin rolls behind the bars."""
    els = [
        D.box((0, 0, 2), (16, 10, 16), {"north": "bar_front", "south": "mahogany", "east": "mahogany", "west": "mahogany",
                                        "down": "mahogany"}),
        D.box((0, 10, 1), (16, 11, 16), "gold_trim"),
        D.pal_box((0, 15, 4), (16, 16, 5), "gold"),
        D.pal_box((0, 11, 4), (1, 15, 5), "gold"),
        D.pal_box((15, 11, 4), (16, 15, 5), "gold"),
        D.pal_box((5.5, 13, 3.6), (10.5, 13.6, 5), "gold_dark"),  # the window's arch
        D.pal_box((6, 11, 2), (10, 11.3, 4), "brass"),  # the money tray under the window
    ]
    for x in (2, 3.8, 11.6, 13.4):
        els.append(D.pal_box((x, 11, 4.2), (x + 0.6, 15, 4.8), "gold"))
    for x in (6.2, 7.7, 9.2):
        els.append(D.pal_box((x, 13.6, 4.2), (x + 0.6, 15, 4.8), "gold"))
    els += D.chip_stack(2.5, 8, 4, "red", y=11)
    els += D.chip_stack(6.5, 9, 3, "black", y=11)
    els += D.chip_stack(11, 7.5, 5, "blue", y=11)
    els.append(D.pal_box((12, 11, 11), (15, 12.5, 14), "brass"))  # coin rolls
    els.append(D.pal_box((3, 11, 12), (6, 12, 15), "green"))  # banded notes
    tx = {"bar_front": "bar_front", "mahogany": "mahogany", "gold_trim": "gold_trim", "palette": "palette"}
    tx.update({k: k for k in D.chip_tex()})
    D.model("casino_cashier", tx, els, particle="mahogany")


def models():
    letter_models()
    marquee_model()
    grand_chandelier()
    cashier()


NAMES = {
    "casino_letter": ("Неоновая буква вывески", "Neon sign letter"),
    "casino_marquee": ("Бегущие огни", "Marquee lights"),
    "casino_grand_chandelier": ("Хрустальная люстра", "Crystal chandelier"),
    "casino_cashier": ("Касса казино", "Casino cashier"),
}


def save():
    """Blockstates, item models and loot of the grand pieces (decor.save_all writes the textures and models)."""
    variants = {}
    for i in range(len(LETTERS)):
        for part in range(4):
            m = D.NS + f"casino_letter_{i}_{part if part in parts(i) else part - 1}"
            for f, y in D.FACINGS:
                variants[f"facing={f},letter={i},part={part}"] = {"model": m, "y": y} if y else {"model": m}
    D.write(D.os.path.join(D.ASSETS, "blockstates", "casino_letter.json"), {"variants": variants})
    D.write(D.os.path.join(D.ASSETS, "models", "item", "casino_letter.json"), {"parent": D.NS + "casino_letter_0_0", "display": D.DISPLAY})
    D.loot("casino_letter")
    for name in ("casino_marquee", "casino_grand_chandelier", "casino_cashier"):
        D.write(D.os.path.join(D.ASSETS, "blockstates", name + ".json"), {"variants": D.facing_variants(name)})
        small = name == "casino_grand_chandelier"
        D.write(D.os.path.join(D.ASSETS, "models", "item", name + ".json"), {"parent": D.NS + name, "display": D.SMALL if small else D.DISPLAY})
        D.loot(name)
