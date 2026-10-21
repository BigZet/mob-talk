"""Чёрный Схимник: a towering gaunt monk of the great schema. A deep pointed кукуль with embroidered crosses, a long аналав
down the front with the white Golgotha cross, the skull and red lettering, a layered black ряса and a great mantle in many
folds, wide sleeves, skeletal hands, a void under the hood with two candle-lit eyes, a long prayer rope of beads, a tall
staff crowned with a gold eight-pointed cross and a black candle with a living flame.
Built about 43 units tall and drawn at scale 2 (about five blocks), at 4 pixels per unit."""
from boxtex import *

INK = (20, 18, 23)
ROBE = (28, 25, 31)
ROBE_HL = (88, 82, 100)
MANTLE = (34, 30, 38)
MANTLE_HL = (104, 98, 118)
EMB_WHITE = (236, 230, 214)
EMB_RED = (190, 36, 30)
BONE_PALE = (226, 216, 192)
WOOD = (62, 42, 28)
BEAD = (122, 62, 38)
WAX_BLACK = (30, 26, 32)

# ---- Embroidery: tiny Church Slavonic letters (3-5 px glyphs), the Golgotha cross, braid borders. ----

GLYPHS = {
    "И": ["1..1", "1.11", "11.1", "1..1"], "І": ["1", "1", "1", "1"], "С": [".11", "1..", "1..", ".11"],
    "Х": ["1.1", ".1.", ".1.", "1.1"], "Н": ["1.1", "111", "1.1", "1.1"], "К": ["1.1", "11.", "1.1", "1.1"],
    "А": [".1.", "1.1", "111", "1.1"], "М": ["1...1", "11.11", "1.1.1", "1...1"], "Л": [".11", "1.1", "1.1", "1.1"],
    "Р": ["11.", "1.1", "11.", "1.."], "Б": ["111", "1..", "111", "111"], "Г": ["111", "1..", "1..", "1.."],
    "Т": ["111", ".1.", ".1.", ".1."], " ": ["..", "..", "..", ".."],
}


def text_width(text):
    return sum(len(GLYPHS[c][0]) + 1 for c in text) - 1


def draw_text(p, f, u, v, text, color, titlo=False):
    """Draws `text` with its top-left at (u, v); `titlo` puts the abbreviation mark over it."""
    x = u
    for ch in text:
        g = GLYPHS[ch]
        for dv, row in enumerate(g):
            for du, bit in enumerate(row):
                if bit == "1":
                    p.put(f, x + du, v + dv, color)
        x += len(g[0]) + 1
    if titlo:
        for du in range(u, x - 1):
            p.put(f, du, v - 2, color)


def line(p, f, u0, v0, u1, v1, color, skip=None):
    n = max(abs(u1 - u0), abs(v1 - v0), 1)
    for i in range(n + 1):
        u, v = round(u0 + (u1 - u0) * i / n), round(v0 + (v1 - v0) * i / n)
        if not (skip and skip(u, v)):
            p.put(f, u, v, color)


def rect(p, f, u0, v0, u1, v1, color):
    for v in range(v0, v1 + 1):
        for u in range(u0, u1 + 1):
            p.put(f, u, v, color)


def braid(p, f, width=2):
    """A red cord with a white running stitch along every edge of a face."""
    W, H = f.w, f.h
    for u in range(W):
        for v in range(H):
            d = min(u, v, W - 1 - u, H - 1 - v)
            if d == 0:
                p.put(f, u, v, EMB_RED)
            elif d < width:
                p.put(f, u, v, EMB_WHITE if ((u + v) // 2) % 2 == 0 else EMB_RED)


def weave(base, k=0.06):
    """Dense black wool: a faint twill so the embroidered panels do not read as flat paint."""
    def paint(p, f, opts):
        for v in range(f.h):
            for u in range(f.w):
                p.put(f, u, v, shade(base, p.noise(k) * (0.92 if (u + v) % 3 == 0 else 1.0)))
    return paint


def analav_top(p, f, opts):
    """The upper аналав: the top of the Golgotha cross with its title board, «ІС ХС» and «НИ КА» in red."""
    weave(INK)(p, f, opts)
    if f.side != "front":
        return
    W, H = f.w, f.h
    braid(p, f)
    cx = W // 2
    draw_text(p, f, 3, 5, "ІС", EMB_RED, titlo=True)
    draw_text(p, f, W - 3 - text_width("ХС"), 5, "ХС", EMB_RED, titlo=True)
    rect(p, f, cx - 1, 10, cx, H - 1, EMB_WHITE)
    rect(p, f, cx - 4, 12, cx + 3, 13, EMB_WHITE)
    rect(p, f, 3, 19, W - 4, 20, EMB_WHITE)
    for u in (3, W - 4):
        rect(p, f, u - 1, 18, u, 21, EMB_WHITE)
    draw_text(p, f, 3, 24, "НИ", EMB_RED)
    draw_text(p, f, W - 3 - text_width("КА"), 24, "КА", EMB_RED)


def analav_low(p, f, opts):
    """The lower аналав: the slanted footrest, the spear and the reed, the stepped Golgotha, and in its cave Adam's skull
    over crossed bones, with «ГГ», «К Т» and «МЛРБ» in red."""
    weave(INK)(p, f, opts)
    if f.side != "front":
        return
    W, H = f.w, f.h
    braid(p, f)
    cx = W // 2
    s = H / 64.0
    bar = lambda u, v: cx - 1 <= u <= cx
    hill = int(36 * s)
    # Spear (left to right, upward) and reed with the sponge, behind the cross.
    line(p, f, 3, hill + 2, W - 5, int(4 * s), (196, 186, 166), skip=bar)
    rect(p, f, W - 6, int(4 * s) - 2, W - 4, int(4 * s), (196, 186, 166))
    line(p, f, W - 4, hill + 2, 4, int(4 * s), (196, 186, 166), skip=bar)
    rect(p, f, 3, int(4 * s) - 2, 5, int(4 * s), EMB_RED)
    rect(p, f, cx - 1, 0, cx, hill, EMB_WHITE)
    # The footrest: its raised end on the viewer's left.
    for du in range(-6, 7):
        v = int(10 * s) + (du + 6) * 4 // 12
        p.put(f, cx + du, v, EMB_WHITE)
        p.put(f, cx + du, v + 1, EMB_WHITE)
    draw_text(p, f, 2, int(16 * s), "К", EMB_RED)
    draw_text(p, f, W - 5, int(16 * s), "Т", EMB_RED)
    # Golgotha: three white steps.
    for k, half in enumerate((3, 6, 9)):
        v0 = hill + k * 4
        rect(p, f, cx - half, v0, cx + half - 1, v0 + 3, (26, 24, 28))
        rect(p, f, cx - half, v0, cx + half - 1, v0, EMB_WHITE)
        p.put(f, cx - half, v0 + 1, EMB_WHITE)
        p.put(f, cx + half - 1, v0 + 1, EMB_WHITE)
    draw_text(p, f, 1, hill + 1, "Г", EMB_RED)
    draw_text(p, f, W - 4, hill + 1, "Г", EMB_RED)
    # The cave with the skull and crossed bones.
    top = hill + 13
    for (u0, v0, u1, v1) in ((cx - 8, top, cx + 7, top + 11), (cx + 7, top, cx - 8, top + 11)):
        line(p, f, u0, v0, u1, v1, (210, 200, 180))
        line(p, f, u0, v0 + 1, u1, v1 + 1, (210, 200, 180))
    for v in range(top + 1, top + 9):
        for u in range(cx - 4, cx + 4):
            r = ((u + 0.5 - cx) / 4.0) ** 2 + ((v - top - 4) / 4.2) ** 2
            if r <= 1.0 or (v >= top + 6 and abs(u + 0.5 - cx) < 3):
                p.put(f, u, v, EMB_WHITE)
    for u in (cx - 3, cx - 2, cx + 1, cx + 2):
        p.put(f, u, top + 4, INK)
        p.put(f, u, top + 5, INK)
    p.put(f, cx - 1, top + 6, INK)
    p.put(f, cx, top + 6, INK)
    for u in range(cx - 2, cx + 2, 2):
        p.put(f, u, top + 8, INK)
    draw_text(p, f, (W - text_width("МЛРБ")) // 2, H - 8, "МЛРБ", EMB_RED)


def brow_band(p, f, opts):
    """The brow of the кукуль: a red-and-white braid, a white cross between «ІС» and «ХС»."""
    weave(INK)(p, f, opts)
    if f.side != "front":
        return
    W, H = f.w, f.h
    for u in range(W):
        p.put(f, u, H - 1, EMB_RED)
        p.put(f, u, 0, EMB_RED)
    cx = W // 2
    rect(p, f, cx - 1, 1, cx, H - 2, EMB_WHITE)
    rect(p, f, cx - 3, 2, cx + 2, 3, EMB_WHITE)
    draw_text(p, f, cx - 9, 2, "ІС", EMB_RED)
    draw_text(p, f, cx + 4, 2, "ХС", EMB_RED)


def hood_cloth(p, f, opts):
    """The кукуль: soft black wool in deep folds, a band of red lettering round the crown."""
    drapery(ROBE, highlight=ROBE_HL)(p, f, opts)
    if f.side in ("left", "right", "back") and f.h > 20:
        v0 = f.h // 3
        for u in range(1, f.w - 4, 5):
            ch = "ІСХСНИКА"[(u // 5) % 8]
            draw_text(p, f, u, v0, ch if ch in GLYPHS else "І", EMB_RED)


def kukol_cape(p, f, opts):
    """The cape of the кукуль over the shoulders: black with small white crosses and a red hem."""
    drapery(ROBE, highlight=ROBE_HL)(p, f, opts)
    if f.side not in ("front", "back"):
        return
    W, H = f.w, f.h
    for u in range(W):
        p.put(f, u, H - 3, EMB_RED)
        p.put(f, u, H - 4, EMB_WHITE if u % 3 else EMB_RED)
    if W >= 10 and f.box.size[0] > 3.0:
        cx, top = W // 2, H // 3
        rect(p, f, cx - 1, top, cx, top + 9, EMB_WHITE)
        rect(p, f, cx - 3, top + 3, cx + 2, top + 4, EMB_WHITE)


def lappet(p, f, opts):
    """A lappet of the кукуль falling down the back: a white cross, red letters and a pointed end."""
    weave(INK)(p, f, opts)
    if f.side not in ("front", "back", "left", "right"):
        return
    W, H = f.w, f.h
    if f.side in ("front", "back"):
        braid(p, f, width=1)
        cx = W // 2
        rect(p, f, cx - 1, 4, cx, 16, EMB_WHITE)
        rect(p, f, cx - 3, 7, cx + 2, 8, EMB_WHITE)
        draw_text(p, f, 2, 20, "ІС", EMB_RED)


def pointed(style, start=0.7):
    """Cuts the lower part of a strip into a point (transparent outside)."""
    def paint(p, f, opts):
        style(p, f, opts)
        if f.side not in ("front", "back"):
            return
        for v in range(int(f.h * start), f.h):
            t = (v - f.h * start) / max(1, f.h * (1 - start))
            half = f.w / 2 * (1 - t)
            for u in range(f.w):
                if abs(u + 0.5 - f.w / 2) > half:
                    p.put(f, u, v, None)
    return paint


def ragged(style, depth=1.0, seed=0):
    """Frays the bottom edge of the cloth: an uneven, transparent hem."""
    def paint(p, f, opts):
        style(p, f, opts)
        if f.side not in ("front", "back", "left", "right"):
            return
        d = opts.get("density", 1)
        for u in range(f.w):
            tear = int(depth * d * (0.3 + 0.7 * abs(math.sin(u * 0.53 / d + f.x * 0.11 + seed)))) + (1 if p.rand.random() < 0.3 else 0)
            for v in range(max(0, f.h - tear), f.h):
                p.put(f, u, v, None)
    return paint


def void_face(p, f, opts):
    """The pit under the hood: darkness, a faint warm reflection of the candle from below, two eyes like candle flames."""
    W, H = f.w, f.h
    for v in range(H):
        for u in range(W):
            t = v / max(1, H - 1)
            c = mix((4, 3, 6), (34, 18, 10), max(0.0, t - 0.55) * 1.6) if f.side == "front" else (8, 6, 10)
            p.put(f, u, v, shade(c, p.noise(0.08)))
    if f.side != "front":
        return
    ey = int(H * 0.44)
    for ex in (int(W * 0.3), W - 1 - int(W * 0.3)):
        for du in range(-2, 3):
            for dv in range(-3, 4):
                if du * du + dv * dv <= 6:
                    p.put(f, ex + du, ey + dv, (60, 26, 6), (70, 30, 6))
        for (du, dv, c) in ((0, -2, FLAME), (0, -1, CANDLE), (-1, 0, CANDLE), (0, 0, (255, 248, 210)), (1, 0, CANDLE),
                            (0, 1, (255, 240, 190)), (-1, 1, FLAME), (1, 1, FLAME)):
            p.put(f, ex + du, ey + dv, c, c)


def bone(base=BONE_PALE):
    """Old bone: pale, darker at the joints, a light ridge down the middle."""
    def paint(p, f, opts):
        for v in range(f.h):
            for u in range(f.w):
                t = v / max(1, f.h - 1)
                k = 0.78 + 0.3 * math.sin(t * math.pi)
                if f.w > 2 and abs(u + 0.5 - f.w / 2) < 1:
                    k *= 1.08
                p.put(f, u, v, shade(base, k * p.noise(0.05)))
    return paint


def wood_grain(p, f, opts):
    for v in range(f.h):
        for u in range(f.w):
            k = 0.85 + 0.15 * math.sin(u * 1.7 + math.sin(v * 0.21) * 2)
            p.put(f, u, v, shade(WOOD, k * p.noise(0.06)))


def black_wax(p, f, opts):
    """Black wax with glossy runs; the top is a smouldering ring."""
    for v in range(f.h):
        for u in range(f.w):
            if f.side == "top":
                r = math.hypot(u + 0.5 - f.w / 2, v + 0.5 - f.h / 2) / (f.w / 2)
                c = (190, 70, 20) if r < 0.45 else WAX_BLACK
                p.put(f, u, v, c, c if r < 0.45 else None)
                continue
            k = 1.25 if u % 5 == 1 else 1.0
            p.put(f, u, v, shade(WAX_BLACK, k * p.noise(0.08)))


def flame_tongue(p, f, opts):
    """A candle flame on a thin plane: blue at the root, a white-gold core, orange, red at the tip; transparent around."""
    W, H = f.w, f.h
    for v in range(H):
        for u in range(W):
            t = 1 - v / max(1, H - 1)  # 0 at the root, 1 at the tip
            half = (W / 2) * math.sin(math.pi * min(1.0, 0.25 + t * 0.95)) * (1 - t) ** 0.35
            x = abs(u + 0.5 - W / 2)
            if x > half:
                p.put(f, u, v, None)
                continue
            r = x / max(0.5, half)
            if t < 0.12:
                c = mix((90, 120, 255), (255, 240, 200), r * 0.3)
            elif r < 0.45 and t < 0.6:
                c = (255, 250, 222)
            else:
                c = mix(CANDLE, FLAME, min(1.0, r * 0.6 + t * 0.6))
                if t > 0.85:
                    c = mix(c, EMBER, (t - 0.85) / 0.15)
            p.put(f, u, v, c, c)


# ---- Builders. ----

def ring(name, pivot, top, bottom, height, panels, style, arc=(0.0, 2 * math.pi), sway=0.05, fold=0.45, seed=5,
         length_var=0.8, thickness=0.5, width_k=1.3):
    """Cloth panels round an ellipse (radii `top` at the pivot, `bottom` at the hem), each tilted out like a bell; every other
    panel stands a little further out, so the cloth falls in deep pleats."""
    part = Part(name, pivot=pivot)
    a0, a1 = arc
    full = abs((a1 - a0) - 2 * math.pi) < 1e-6
    rxb, rzb = bottom
    perimeter = math.pi * (3 * (rxb + rzb) - math.sqrt((3 * rxb + rzb) * (rxb + 3 * rzb)))
    width = perimeter * (a1 - a0) / (2 * math.pi) / panels * width_k
    for i in range(panels):
        t = i / panels if full else i / max(1, panels - 1)
        a = a0 + (a1 - a0) * t
        off = fold if i % 2 else 0.0
        xt, zt = math.sin(a) * (top[0] + off), -math.cos(a) * (top[1] + off)
        xb, zb = math.sin(a) * (bottom[0] + off * 1.6), -math.cos(a) * (bottom[1] + off * 1.6)
        tilt = math.atan2(math.hypot(xb, zb) - math.hypot(xt, zt), height)
        length = height + length_var * math.sin(i * 2.3 + seed)
        part.children.append(Part("%s_%d" % (name, i), pivot=(xt, 0, zt),
                                  rotation=(-tilt + sway * math.sin(i * 1.7 + seed), -a, 0),
                                  boxes=[Box((-width / 2, 0, -thickness / 2), (width, length, thickness), style)]))
    return part


def finger(name, pivot, rotation, lengths, thick, curl, style):
    """A bony finger: segments hinged one under another, each bent `curl` further toward the palm (-Z)."""
    root = None
    parent = None
    for k, seg in enumerate(lengths):
        part = Part("%s_%d" % (name, k), pivot=pivot if k == 0 else (0, lengths[k - 1], 0),
                    rotation=rotation if k == 0 else (-curl, 0, 0),
                    boxes=[Box((-thick / 2, 0, -thick / 2), (thick, seg + 0.15, thick), style)])
        if parent:
            parent.children.append(part)
        else:
            root = part
        parent = part
    return root


def skeletal_hand(name, side, curl=0.55, splay=0.12):
    """Wrist, the four long bones of the palm, a knuckle ridge, four fingers of three joints and a thumb."""
    bones = bone()
    knuckles = bone(shade(BONE_PALE, 0.9))
    hand = Part(name, boxes=[
        Box((-0.5, -0.4, -0.5), (1.0, 1.4, 1.0), knuckles),
        Box((-1.25, 2.4, -0.45), (2.5, 0.7, 0.9), knuckles),
    ])
    for i, x in enumerate((-0.9, -0.3, 0.3, 0.9)):
        hand.boxes.append(Box((x - 0.2, 0.8, -0.25), (0.4, 2.0, 0.5), bones))
        hand.children.append(finger("%s_finger%d" % (name, i), (x, 2.9, 0), (-curl * 0.6, 0, (x / 0.9) * splay),
                                    (1.5 - 0.15 * abs(i - 1.5), 1.2, 0.9), 0.42, curl, bones))
    hand.children.append(finger("%s_thumb" % name, (side * 1.0, 1.2, -0.3), (-0.9, 0, -side * 0.5), (1.2, 1.0), 0.45, 0.4, bones))
    return hand


def bead_chain(name, pivot, rotation, count, spacing, size):
    """A prayer rope: beads on a cord, each hinged under the last so the rope can swing; a larger gold bead after every ten,
    and at the end a bone cross over a black tassel."""
    cord = solid((40, 30, 26), k=0.05)
    bead = solid(BEAD, k=0.08)
    big = gold_leaf(ornament=False)
    root = parent = None
    for i in range(count):
        s = size * (1.35 if (i + 1) % 10 == 0 else 1.0)
        part = Part("%s_%d" % (name, i), pivot=pivot if i == 0 else (0, spacing, 0), rotation=rotation if i == 0 else (0, 0, 0),
                    boxes=[Box((-0.1, 0, -0.1), (0.2, spacing, 0.2), cord),
                           Box((-s / 2, spacing / 2 - s / 2, -s / 2), (s, s, s), big if (i + 1) % 10 == 0 else bead)])
        if parent:
            parent.children.append(part)
        else:
            root = part
        parent = part
    tassel = Part("%s_end" % name, pivot=(0, spacing, 0), boxes=[
        Box((-0.15, 0, -0.15), (0.3, 2.4, 0.3), bone()),
        Box((-0.9, 0.6, -0.15), (1.8, 0.3, 0.3), bone()),
        Box((-0.5, 2.3, -0.5), (1.0, 2.4, 1.0), ragged(solid((14, 12, 16)), 0.4, 3)),
    ])
    parent.children.append(tassel)
    return root


def flame(name, pivot, height=3.0, width=1.8):
    """Two crossed planes of flame and a hot core."""
    return Part(name, pivot=pivot, boxes=[
        Box((-width / 2, -height, 0), (width, height, 0.05), flame_tongue, flat=True),
        Box((-0.3, -height * 0.45, -0.3), (0.6, height * 0.4, 0.6), solid((255, 246, 214), glow=True, k=0.02), flat=True),
    ], children=[
        Part(name + "_cross", rotation=(0, math.pi / 2, 0), boxes=[Box((-width / 2, -height, 0), (width, height, 0.05), flame_tongue, flat=True)]),
    ])


# ---- The monk. ----

robe = drapery(ROBE, highlight=ROBE_HL)
mantle = ragged(drapery(MANTLE, highlight=MANTLE_HL, hem=(48, 42, 50), hem_width=1), 0.7, 1)
gold = gold_leaf(ornament=False)


def sleeve(prefix, drape_pitch):
    """A wide monastic sleeve: a bell of folds round the forearm and a long fall of cloth hanging under it."""
    return [
        ring(prefix + "_sleeve", pivot=(0, -0.6, 0), top=(1.9, 1.9), bottom=(3.4, 3.4), height=7.6, panels=9,
             style=ragged(robe, 0.5, 2), sway=0.04, fold=0.25, seed=3, length_var=0.5),
        Part(prefix + "_drape", pivot=(0, 0.6, 1.6), rotation=(drape_pitch, 0, 0),
             boxes=[Box((-2.2, 0, -0.25), (4.4, 9.5, 0.5), pointed(ragged(robe, 0.5, 4), 0.75))]),
    ]


staff = Part("staff", pivot=(0, 9.6, -1.9), rotation=(0.45, 0, -0.14), boxes=[
    Box((-0.55, -27, -0.55), (1.1, 40.2, 1.1), wood_grain),
    Box((-0.75, -27.6, -0.75), (1.5, 1.6, 1.5), gold),
    Box((-0.7, -14, -0.7), (1.4, 1.0, 1.4), gold),
    Box((-0.7, -1.2, -0.7), (1.4, 0.8, 1.4), gold),
    Box((-0.7, 12.6, -0.7), (1.4, 0.8, 1.4), solid(BRONZE, k=0.06)),
], children=[
    Part("staff_cross", pivot=(0, -27.6, 0), boxes=[
        Box((-1.3, -1.6, -1.3), (2.6, 1.6, 2.6), gold_leaf()),
        Box((-0.45, -9.5, -0.45), (0.9, 8.2, 0.9), gold),
        Box((-1.3, -8.6, -0.45), (2.6, 0.8, 0.9), gold),
        Box((-3.4, -7.0, -0.45), (6.8, 1.0, 0.9), gold),
        Box((-3.9, -7.15, -0.55), (1.0, 1.3, 1.1), gold),
        Box((2.9, -7.15, -0.55), (1.0, 1.3, 1.1), gold),
        Box((-0.7, -10.1, -0.55), (1.4, 0.9, 1.1), gold),
    ], children=[
        Part("staff_footrest", pivot=(0, -4.2, 0), rotation=(0, 0, -0.38), boxes=[Box((-2.0, -0.4, -0.45), (4.0, 0.8, 0.9), gold)]),
    ]),
])

candle = Part("candle", pivot=(0, 10.0, -1.1), rotation=(1.4, 0, 0), boxes=[
    Box((-1.4, -0.5, -1.4), (2.8, 0.5, 2.8), gold_leaf(ornament=False)),
    Box((-0.7, -6.5, -0.7), (1.4, 6.0, 1.4), black_wax),
    Box((-0.95, -6.1, -0.3), (0.35, 2.2, 0.6), black_wax),
    Box((0.55, -5.8, -0.2), (0.35, 1.4, 0.5), black_wax),
    Box((-0.1, -7.2, -0.1), (0.2, 0.8, 0.2), solid((20, 14, 10))),
], children=[flame("candle_flame", (0, -7.0, 0), height=3.4, width=2.0)])

chotki = bead_chain("chotki", pivot=(0, 7.4, 0.7), rotation=(1.4, 0, 0), count=16, spacing=0.85, size=0.7)


def arm(label, side, rotation, fore_pitch, drape_pitch, hand_curl, held):
    hand = skeletal_hand(label + "_hand", side, curl=hand_curl)
    hand.pivot = (0, 8.0, 0)
    fore = Part(label + "_forearm", pivot=(0, 7.4, 0), rotation=(fore_pitch, 0, 0),
                boxes=[Box((-0.6, 0, -0.6), (1.2, 8.2, 1.2), bone())],
                children=sleeve(label, drape_pitch) + [hand] + held)
    return Part(label, pivot=(side * 5.4, -11.2, -1.0), rotation=rotation, boxes=[
        Box((-1.9, -1.4, -1.9), (3.8, 3.0, 3.8), robe),
        Box((-1.7, 0, -1.7), (3.4, 7.8, 3.4), robe),
    ], children=[fore])


def hood():
    shell = hood_cloth
    tip4 = Part("hood_tip4", pivot=(0, -2.1, 0.1), rotation=(-0.3, 0, 0), boxes=[Box((-0.55, -2.0, -0.55), (1.1, 2.2, 1.1), shell)])
    tip3 = Part("hood_tip3", pivot=(0, -2.3, 0.1), rotation=(-0.22, 0, 0), boxes=[Box((-1.25, -2.3, -1.15), (2.5, 2.5, 2.3), shell)], children=[tip4])
    tip2 = Part("hood_tip2", pivot=(0, -2.5, 0.2), rotation=(-0.2, 0, 0), boxes=[Box((-2.05, -2.5, -1.95), (4.1, 2.7, 3.9), shell)], children=[tip3])
    tip = Part("hood_tip", pivot=(0, -8.2, 0.7), rotation=(-0.32, 0, 0), boxes=[Box((-2.9, -2.7, -2.7), (5.8, 2.9, 5.5), shell)], children=[tip2])
    flaps = [Part("hood_flap_%s" % n, pivot=(s * 3.0, 1.0, -0.6), rotation=(0, 0, -s * 0.28),
                  boxes=[Box((-0.5, 0, -2.8), (1.0, 4.6, 6.4), ragged(robe, 0.5, 6))]) for s, n in ((-1, "r"), (1, "l"))]
    return Part("head", pivot=(0, -12.6, -0.3), boxes=[
        Box((-2.3, -6.6, -2.4), (4.6, 6.8, 4.6), void_face, flat=True),
        Box((-3.3, -7.6, -3.0), (1.0, 9.2, 6.6), shell),
        Box((2.3, -7.6, -3.0), (1.0, 9.2, 6.6), shell),
        Box((-3.3, -7.6, 2.4), (6.6, 9.6, 1.0), shell),
        Box((-3.3, -8.5, -3.0), (6.6, 1.0, 6.6), shell),
        Box((-3.5, -8.4, -3.9), (7, 2, 1.2), brow_band),
    ], children=[tip] + flaps)


lappets = []
for s, n in ((-1, "r"), (1, "l")):
    low = Part("lappet_%s_low" % n, pivot=(0, 10, 0), rotation=(0.05, 0, 0),
               boxes=[Box((-1.4, 0, -0.2), (3, 10, 0.4), pointed(lappet, 0.55))])
    lappets.append(Part("lappet_%s" % n, pivot=(s * 1.7, -12.0, 5.2), rotation=(0.16, 0, s * 0.03),
                        boxes=[Box((-1.4, 0, -0.2), (3, 10, 0.4), lappet)], children=[low]))

parts = [
    Part("figure", pivot=(0, 24, 0), children=[
        skirt("ryasa", pivot=(0, -17.4, 0), top_radius=3.9, bottom_radius=7.4, height=16.9, panels=16,
              style=ragged(drapery(ROBE, highlight=ROBE_HL), 0.6, 2), sway=0.05),
        Part("analav_low", pivot=(0, -17.2, -4.5), rotation=(-0.195, 0, 0), boxes=[Box((-3, 0, -0.2), (6, 16, 0.4), analav_low)]),
        Part("body", pivot=(0, -17, 0), boxes=[
            Box((-4.2, -12, -2.6), (8.4, 6.6, 5.2), robe),
            Box((-3.7, -6, -2.4), (7.4, 6.6, 4.8), robe),
            Box((-5.2, -12.6, -2.3), (10.4, 2.4, 4.6), robe),
            Box((-4.0, -0.8, -2.7), (8, 1.4, 5.4), solid((44, 30, 24), k=0.08)),
        ], children=[
            Part("analav", pivot=(0, -11.4, -2.95), rotation=(-0.14, 0, 0), boxes=[Box((-3, 0, -0.2), (6, 9, 0.4), analav_top)]),
            ring("mantle", pivot=(0, -11.8, 1.4), top=(5.8, 3.4), bottom=(10.0, 9.0), height=28.0, panels=17, style=mantle,
                 arc=(1.25, 2 * math.pi - 1.25), sway=0.05, fold=0.55, seed=7, length_var=0.6),
            ring("cape", pivot=(0, -12.6, 0.4), top=(3.4, 3.2), bottom=(6.6, 6.0), height=9.5, panels=14,
                 style=ragged(kukol_cape, 0.4, 5), arc=(0.75, 2 * math.pi - 0.75), sway=0.04, fold=0.3, seed=2, length_var=0.4),
            hood(),
            arm("right_arm", -1, (0.05, 0, 0.14), -0.5, 0.42, 0.7, [staff]),
            arm("left_arm", 1, (-0.25, 0, -0.12), -1.15, 1.1, 0.8, [candle, chotki]),
        ] + lappets),
    ]),
]

if __name__ == "__main__":
    build("Schemnik", "black_schemnik", parts, size=256, seed=11, density=4)
