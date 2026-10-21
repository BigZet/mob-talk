"""Звонарь погоста: a colossal living church bell of old bronze. Its body is a real bell profile turned from many rings of
cast panels: a domed shoulder, a band of pseudo-Slavonic lettering, a long waist with a cast mournful face whose eyes are
two burning candles, raised Orthodox crosses on the other three sides, a second band of lettering, a frieze on the sound
bow and a flared lip; green patina runs down from every cast rim. It hangs from crown loops on an iron-strapped oak beam,
and its heavy iron clapper hangs inside on a chain. On the crown squats the ringer, a gaunt hunched thing in
rotten sackcloth whose long arms grip the two bell ropes that hang from the beam ends.

Built about 56 units tall and drawn at scale 1.65 (about six blocks), at 4 pixels per unit."""
from boxtex import *

BR_DARK = (70, 46, 26)
BR = (132, 88, 46)
BR_LIGHT = (200, 150, 86)
BR_WORN = (226, 182, 112)
PATINA_LIGHT = (112, 170, 140)
HOLLOW = (14, 10, 8)
WOOD = (74, 50, 30)
IRON = (58, 56, 56)
IRON_LIGHT = (104, 100, 96)
SACK = (104, 88, 62)
SACK_DARK = (58, 48, 34)
FLESH = (138, 132, 112)
FLESH_DARK = (82, 76, 62)
ROPE = (156, 126, 82)
SALLY_RED = (130, 24, 26)
EMBER_EYE = (255, 170, 80)

N = 20  # panels per ring

# Pseudo-Slavonic glyphs, 3x5, cast in relief on the bands.
GLYPHS = [
    ["111", "101", "111", "101", "101"], ["101", "101", "111", "010", "010"], ["111", "010", "111", "010", "111"],
    ["101", "111", "010", "111", "101"], ["010", "101", "111", "101", "101"], ["111", "100", "110", "100", "111"],
    ["101", "101", "111", "001", "001"], ["111", "101", "101", "101", "101"], ["110", "101", "110", "101", "110"],
    ["101", "101", "101", "101", "111"], ["011", "100", "010", "001", "110"], ["111", "001", "011", "001", "111"],
]
KIND_SEED = {"shoulder": 11, "letters": 23, "waist": 37, "frieze": 41, "sound": 53, "flare": 61, "lip": 71, "rim": 83}


# ---- Bronze painting. ----

def bronze_px(p, t, light=0.0):
    """Old bronze: lit near the top of a cast surface, darker below."""
    c = mix(BR_LIGHT, BR_DARK, max(0.0, min(1.0, 0.2 + t * 0.65 - light)))
    return shade(c, p.noise(0.07))


def patina_streaks(p, f, seed, amount=1.0):
    """Green runs dripping from the top of the face, as rain leaves them on an old bell."""
    rnd = random.Random(seed)
    for _ in range(int(f.w / 5 * amount) + 1):
        u0 = rnd.randrange(max(1, f.w))
        length = int(f.h * rnd.uniform(0.25, 1.0))
        width = rnd.choice((1, 1, 2, 3))
        for v in range(length):
            fade = 1 - v / max(1, length)
            wob = int(math.sin(v * 0.35 + u0) * 0.8)
            for du in range(width):
                u = u0 + du + wob
                if 0 <= u < f.w and rnd.random() < 0.55 + 0.4 * fade:
                    col = mix(PATINA, PATINA_LIGHT, rnd.random() * 0.5)
                    r, g, b, a = p.img.getpixel((f.x + u, f.y + v))
                    if a:
                        p.put(f, u, v, mix((r, g, b), col, 0.45 + 0.4 * fade))
    # Patina pools in a thin crust right under the rim above.
    for u in range(f.w):
        if rnd.random() < 0.6:
            p.put(f, u, 0, mix(PATINA, PATINA_LIGHT, rnd.random()))


def hollow_px(p, t):
    return shade(mix(HOLLOW, (40, 28, 18), t), p.noise(0.1))


def panel_style(kind, index, extra=None, sx=1.0, sy=1.0):
    """A bell panel: bronze outside with patina, the dark hollow inside, worn edges; `kind` picks the cast decoration.
    `sx`, `sy` are painted pixels per unit over the density (box nets are rounded up), so overlays keep true proportions."""
    def paint(p, f, opts):
        d = opts.get("density", 1)
        if f.side == "back":
            for v in range(f.h):
                for u in range(f.w):
                    p.put(f, u, v, hollow_px(p, v / max(1, f.h - 1)))
            return
        if f.side in ("top", "bottom", "left", "right"):
            for v in range(f.h):
                for u in range(f.w):
                    p.put(f, u, v, shade(BR_WORN if kind == "lip" and f.side == "bottom" else BR, p.noise(0.08)))
            return
        for v in range(f.h):
            for u in range(f.w):
                p.put(f, u, v, bronze_px(p, v / max(1, f.h - 1), 0.15 if kind in ("rim", "lip") else 0.0))
        if kind == "rim":
            for u in range(f.w):
                for v in range(max(1, d // 2)):
                    p.put(f, u, v, shade(BR_WORN, p.noise(0.05)))
            return
        if kind == "letters":
            letters(p, f, d, index)
        elif kind == "frieze":
            frieze(p, f, d, index)
        elif kind == "lip":
            for u in range(f.w):
                for v in range(f.h - 2 * d, f.h):
                    p.put(f, u, v, shade(BR_WORN, p.noise(0.06) * (1.1 - 0.3 * (v - f.h + 2 * d) / (2 * d))))
        if kind not in ("lip", "letters"):
            patina_streaks(p, f, KIND_SEED.get(kind, 7) * 101 + index, 1.4 if kind in ("waist", "shoulder") else 0.8)
        if extra:
            extra(p, f, d * sx, d * sy)
    return paint


def letters(p, f, d, index):
    """A cast band: raised lettering between two raised rims, the recess darkened with verdigris."""
    rnd = random.Random(index * 31 + f.w + f.y)
    rim_h = max(1, d // 2)
    for u in range(f.w):
        for v in range(rim_h, f.h - rim_h):
            p.put(f, u, v, shade(mix(BR_DARK, PATINA, 0.3), p.noise(0.1)))
        for v in list(range(0, rim_h)) + list(range(f.h - rim_h, f.h)):
            p.put(f, u, v, shade(BR_WORN, p.noise(0.06)))
    gh = f.h - 2 * rim_h - 2
    s = max(1, gh // 5)
    top = rim_h + 1 + (gh - 5 * s) // 2
    u = rnd.randrange(3)
    while u + 3 * s <= f.w:
        if rnd.random() < 0.14:
            orthodox_cross(p, f, u + s, top + 5 * s / 2, 5 * s, BR_WORN, 1)
            u += 3 * s + 1
            continue
        glyph = rnd.choice(GLYPHS)
        for gv, row in enumerate(glyph):
            for gu, bit in enumerate(row):
                if bit == "1":
                    for a in range(s):
                        for b in range(s):
                            p.put(f, u + gu * s + a, top + gv * s + b, shade(BR_WORN, p.noise(0.06)))
        u += 4 * s


def frieze(p, f, d, index):
    """The sound bow's cast frieze: a running vine of trefoils."""
    mid = f.h // 2
    for u in range(f.w):
        gu = u + index * f.w
        v = int(mid + math.sin(gu / (2.2 * d)) * (f.h * 0.22))
        for k in range(max(1, d // 2)):
            p.put(f, u, v + k, shade(BR_WORN, p.noise(0.05)))
        if gu % (5 * d) == 0:
            r = d // 2 + 1
            for a in range(-r, r + 1):
                for b in range(-r, r + 1):
                    if a * a + b * b <= r * r:
                        p.put(f, u + a, v - d + b, BR_WORN)


# ---- The cast face across the five front panels of the waist. ----

FACE_PANELS = 5


def face_overlay(k):
    """Paints this panel's slice (k = -2..2, +X is the viewer's right) of the face: eye sockets lit by the candles, hollow
    cheeks, the folds from nose to mouth and a downturned mouth. The relief (brows, nose, lip) is modelled."""
    def extra(p, f, ux, uy):
        W = f.w * FACE_PANELS
        off = (k + FACE_PANELS // 2) * f.w
        cx = W / 2
        for v in range(f.h):
            for lu in range(f.w):
                gx = off + lu
                dx = (gx - cx) / ux
                y = v / uy
                for side in (-1, 1):
                    q = ((dx - side * 3.3) / 1.9) ** 2 + ((y - 4.9) / 1.5) ** 2
                    if q <= 1:
                        p.put(f, lu, v, mix((26, 16, 8), (110, 60, 22), 1 - q), shade((255, 150, 60), 0.55 * (1 - q) + 0.12))
                    elif q <= 1.7:
                        p.put(f, lu, v, shade(BR_DARK, 0.9), shade((255, 140, 50), 0.14 * (1.7 - q) / 0.7))
                if 6.6 < y < 10.5 and 2.2 < abs(dx) < 5.6:
                    q = abs(abs(dx) - 3.9) / 1.7
                    if q < 1:
                        p.put(f, lu, v, shade(BR, 0.68 + 0.32 * q))
                fold = 1.7 + (y - 6.4) * 0.4
                if 6.4 < y < 9.8 and abs(abs(dx) - fold) < 0.18:
                    p.put(f, lu, v, BR_DARK)
                my = 9.9 + dx * dx * 0.05
                if abs(dx) < 2.7 and abs(y - my) < 0.16:
                    p.put(f, lu, v, (26, 16, 10))
                if 0.5 < y < 1.6 and 0.5 < abs(dx) < 3 and abs(y - 0.8 - 0.4 * int(y > 1.1)) < 0.12:
                    p.put(f, lu, v, BR_DARK)
    return extra


def side_cross_overlay(p, f, ux, uy):
    """A raised ring framing the relief cross on the other sides of the waist."""
    cx, cy = f.w / 2, 5.2 * uy
    for v in range(f.h):
        for u in range(f.w):
            r = math.hypot((u - cx) / ux, (v - cy) / uy)
            if abs(r - 4.6) < 0.22:
                p.put(f, u, v, shade(BR_WORN, 0.9))


# ---- Builders. ----

def ring(name, y0, r0, y1, r1, thick, kind, extra_for=None, n=N):
    """One ring of the bell: `n` cast panels around the axis, each tilted out along the profile from (y0, r0) to (y1, r1)."""
    part = Part(name)
    tilt = math.atan2(r1 - r0, y1 - y0)
    length = math.hypot(y1 - y0, r1 - r0)
    width = 2 * max(r0, r1) * math.tan(math.pi / n) * 1.05
    sx = sy = 1.0  # box faces are painted at their exact size
    for i in range(n):
        a = 2 * math.pi * i / n
        rc = r0 - thick / 2
        extra = extra_for(i) if extra_for else None
        part.children.append(Part("%s_%d" % (name, i), pivot=(math.sin(a) * rc, y0, -math.cos(a) * rc), rotation=(-tilt, -a, 0),
                                  boxes=[Box((-width / 2, 0, -thick / 2), (width, length, thick), panel_style(kind, i, extra, sx, sy), flat=True)]))
    return part


def rim(name, y, r, height=0.9, thick=1.0):
    """A raised cast rim (astragal) around the bell."""
    return ring(name, y - height / 2, r + 0.45, y + height / 2, r + 0.45, thick, "rim")


def octagon(prefix, y, half, height, style, flat=False):
    """A regular octagonal slab of inradius `half`: four bars turned 45 degrees apart."""
    t = half * math.tan(math.pi / 8)
    return [Part("%s_%d" % (prefix, k), pivot=(0, y, 0), rotation=(0, k * math.pi / 4, 0),
                 boxes=[Box((-half, 0, -t), (2 * half, height, 2 * t), style, flat=flat)]) for k in range(4)]


def iron_style(light=False):
    return metal(IRON, (24, 22, 22), IRON_LIGHT if not light else (150, 144, 136), streaks=(96, 52, 30))


def wood(p, f, opts):
    """Old oak: long grain, dark knots."""
    d = opts.get("density", 1)
    along = f.w >= f.h
    for v in range(f.h):
        for u in range(f.w):
            a, b = (u, v) if along else (v, u)
            k = 0.82 + 0.18 * math.sin(b * 1.7 + math.sin(a * 0.05) * 3)
            c = shade(WOOD, k * p.noise(0.06))
            if (a * 7 + b * 13) % (37 * d) == 0:
                c = shade(WOOD, 0.5)
            p.put(f, u, v, c)


def chain_links(prefix, top, length, link=1.8, thick=0.5, width=1.1, style=None):
    """A chain hanging down from y = top: links alternating their plane."""
    style = style or iron_style()
    parts = []
    for i in range(int(length / (link * 0.8))):
        parts.append(Part("%s_%d" % (prefix, i), pivot=(0, top + i * link * 0.8, 0), rotation=(0, (math.pi / 2) * (i % 2), 0),
                          boxes=[Box((-width / 2, 0, -thick / 2), (width, link, thick), style)]))
    return parts


def rope_style(sally=False):
    def paint(p, f, opts):
        d = opts.get("density", 1)
        for v in range(f.h):
            for u in range(f.w):
                twist = ((u + v) // max(1, d // 2)) % 3
                c = shade(ROPE, (0.72, 0.95, 1.08)[twist] * p.noise(0.05))
                if sally:
                    band = (v // (2 * d)) % 3
                    c = shade((SALLY_RED, (196, 160, 80), (40, 30, 50))[band], (0.8, 1.0, 1.05)[twist] * p.noise(0.05))
                p.put(f, u, v, c)
    return paint


def sackcloth(ragged=False, patch=True):
    """Rotten sackcloth: a coarse weave, stains, a sewn-on patch; with `ragged` the hem is torn (transparent)."""
    def paint(p, f, opts):
        d = opts.get("density", 1)
        rnd = random.Random(f.x * 7 + f.y)
        for v in range(f.h):
            for u in range(f.w):
                weave = 0.9 if (u + (v // 2)) % 2 else 1.06
                c = shade(SACK, weave * p.noise(0.07) * (1.0 - 0.25 * v / max(1, f.h)))
                if math.sin(u * 0.3 + v * 0.17 + f.x) > 0.85:
                    c = shade(c, 0.75)
                p.put(f, u, v, c)
        if patch and f.side in ("front", "back") and f.w > 6 * d and f.h > 8 * d:
            pu, pv, pw, ph = rnd.randrange(f.w - 4 * d), rnd.randrange(f.h - 5 * d), 3 * d, 3 * d
            for v in range(ph):
                for u in range(pw):
                    edge = u in (0, pw - 1) or v in (0, ph - 1)
                    p.put(f, pu + u, pv + v, shade(SACK_DARK if not edge else (150, 130, 96), p.noise(0.05)))
        if ragged and f.side in ("front", "back", "left", "right"):
            for u in range(f.w):
                tear = int(abs(math.sin(u * 0.9 + f.x)) * 2.5 * d + rnd.random() * d)
                for v in range(f.h - tear, f.h):
                    p.put(f, u, v, None)
    return paint


def flesh(p, f, opts):
    """Grey dead skin over bone: knuckle lines, dark veins."""
    d = opts.get("density", 1)
    for v in range(f.h):
        for u in range(f.w):
            c = shade(FLESH, p.noise(0.08) * (0.9 + 0.1 * math.sin(v * 0.4)))
            if v % (3 * d) == 0 and p.rand.random() < 0.5:
                c = FLESH_DARK
            if abs(u - f.w / 2 - math.sin(v * 0.2) * 2) < 0.6 and p.rand.random() < 0.6:
                c = mix(c, (70, 80, 90), 0.5)
            p.put(f, u, v, c)


# ---- The bell. Bell-local space: the pivot is the beam's axis; the body runs from y = 6.2 down to the lip at y = 34. ----

PROFILE = [(6.2, 6.6), (7.4, 8.2), (9, 8.9), (12, 9.3), (22, 10.5), (25.5, 11.7), (28.5, 13.3), (31, 14.6), (32.6, 15.4), (34, 15.6)]
KINDS = ["shoulder", "shoulder", "letters", "waist", "letters", "frieze", "sound", "flare", "lip"]
THICK = [1.4, 1.4, 1.4, 1.4, 1.4, 1.6, 1.8, 2.0, 2.4]
WAIST_TILT = math.atan2(10.5 - 9.3, 10)


def waist_extra(i):
    k = i if i <= N // 2 else i - N
    if abs(k) <= FACE_PANELS // 2:
        return face_overlay(k)
    if i in (5, 10, 15):
        return side_cross_overlay
    return None


rings = []
for j in range(len(PROFILE) - 1):
    (y0, r0), (y1, r1) = PROFILE[j], PROFILE[j + 1]
    rings.append(ring("ring%d" % j, y0, r0, y1, r1, THICK[j], KINDS[j], waist_extra if KINDS[j] == "waist" else None))
rims = [rim("rim%d" % j, y, r) for j, (y, r) in enumerate([(9, 8.9), (12, 9.3), (22, 10.5), (25.5, 11.7), (31, 14.6)])]


def relief(name, a, y, r, boxes, children=()):
    """A cast relief on the waist at angle `a`: local x along the surface, y down the slope, -z out of the bronze."""
    return Part(name, pivot=(math.sin(a) * r, y, -math.cos(a) * r), rotation=(-WAIST_TILT, -a, 0), boxes=boxes, children=list(children))


relief_bronze = metal(BR_LIGHT, BR, BR_WORN)
face = relief("face_relief", 0, 12, 9.3, [
    Box((-0.8, 2.6, -1.5), (1.6, 4.6, 1.5), relief_bronze),     # the long nose
    Box((-1.4, 6.6, -1.2), (2.8, 0.9, 1.2), relief_bronze),     # its wings
    Box((-1.6, 10.1, -0.6), (3.2, 0.6, 0.6), relief_bronze),    # the lower lip
], children=[
    Part("brow_r", pivot=(-3.2, 2.5, 0), rotation=(0, 0, -0.26), boxes=[Box((-2.8, -0.6, -1.1), (5.6, 1.2, 1.1), relief_bronze)]),
    Part("brow_l", pivot=(3.2, 2.5, 0), rotation=(0, 0, 0.26), boxes=[Box((-2.8, -0.6, -1.1), (5.6, 1.2, 1.1), relief_bronze)]),
    Part("candle_r", pivot=(-3.3, 0, 0), boxes=[
        Box((-0.45, 5.0, -0.9), (0.9, 1.6, 0.8), candle()),
        Box((-0.35, 3.4, -0.75), (0.7, 1.6, 0.5), fire(), flat=True),
    ]),
    Part("candle_l", pivot=(3.3, 0, 0), boxes=[
        Box((-0.45, 5.0, -0.9), (0.9, 1.6, 0.8), candle()),
        Box((-0.35, 3.4, -0.75), (0.7, 1.6, 0.5), fire(), flat=True),
    ]),
])

crosses = []
cross_bronze = solid(BR_WORN, k=0.06)
for idx, a in enumerate((math.pi / 2, math.pi, 3 * math.pi / 2)):
    # Raised eight-pointed crosses: the upright, a distinct titulus, the crossbar, and one footrest through the upright
    # with its left end (as the viewer sees it) raised.
    crosses.append(relief("cross_%d" % idx, a, 12, 9.3, [
        Box((-0.5, 1.2, -1.0), (1.0, 8.0, 1.0), cross_bronze),
        Box((-1.1, 2.0, -1.0), (2.2, 0.7, 1.0), cross_bronze),
        Box((-2.6, 3.6, -1.0), (5.2, 0.9, 1.0), cross_bronze),
    ], children=[Part("cross_%d_foot" % idx, pivot=(0, 7.0, -0.5), rotation=(0, 0, 0.38), boxes=[Box((-1.7, -0.35, -0.5), (3.4, 0.7, 1.0), cross_bronze)])]))

crown_bronze = panel_style("rim", 0)
cap = (octagon("cap_a", 5.2, 6.1, 1.2, panel_style("shoulder", 3)) + octagon("cap_b", 4.0, 4.8, 1.3, panel_style("shoulder", 4))
       + octagon("cap_c", 3.0, 3.4, 1.1, crown_bronze) + octagon("hollow", 7.0, 7.2, 0.6, solid(HOLLOW)))


def loop(name, a, r, k):
    """A crown loop (canon) in the radial plane at angle `a`, leaning outward."""
    h, w = 5.5 * k, 2.4 * k
    return Part(name, pivot=(math.sin(a) * r, 3.0, -math.cos(a) * r), rotation=(0.25, -a, 0), boxes=[
        Box((-0.7, -h, -w - 0.7), (1.4, h, 1.4), crown_bronze),
        Box((-0.7, -h, w - 0.7), (1.4, h, 1.4), crown_bronze),
        Box((-0.75, -h - 1.2, -w - 0.7), (1.5, 1.4, 2 * w + 1.4), crown_bronze),
    ])


loops = [Part("canon_main", pivot=(0, 3.0, 0), boxes=[
    Box((-0.9, -6.4, -3.6), (1.8, 6.4, 1.6), crown_bronze),
    Box((-0.9, -6.4, 2.0), (1.8, 6.4, 1.6), crown_bronze),
    Box((-0.95, -7.8, -3.6), (1.9, 1.6, 7.2), crown_bronze),
])] + [loop("canon_%d" % i, math.pi / 4 + i * math.pi / 2, 2.6, 0.7) for i in range(4)]

iron = iron_style()
beam = Part("beam", boxes=[
    Box((-17.5, -1.7, -1.7), (35, 3.4, 3.4), wood),
] + [Box((x - 0.6, -1.95, -1.95), (1.2, 3.9, 3.9), iron) for x in (-15.5, -9, -4.2, 4.2, 9, 15.5)] + [
    Box((-18.6, -1.2, -1.2), (1.2, 2.4, 2.4), iron_style(True)),
    Box((17.4, -1.2, -1.2), (1.2, 2.4, 2.4), iron_style(True)),
])

# The clapper hangs from inside the crown on a chain and a rod; its iron ball sits just inside the mouth.
ball_iron = metal((86, 82, 80), (40, 38, 38), (150, 144, 136), streaks=(96, 52, 30))
clapper = Part("clapper", pivot=(0, 7.4, 0), boxes=[
    Box((-0.8, 13.5, -0.8), (1.6, 7.6, 1.6), iron),
    Box((-1.4, 19.8, -1.4), (2.8, 1.2, 2.8), iron_style(True)),
], children=chain_links("clink", 0, 14) + octagon("cball_a", 20.6, 2.2, 1.0, ball_iron)
            + octagon("cball_b", 21.6, 3.2, 3.2, ball_iron) + octagon("cball_c", 24.8, 2.2, 1.0, ball_iron))

ropes = []
for side, label in ((-1, "right"), (1, "left")):
    ropes.append(Part(label + "_rope", pivot=(side * 16.6, 1.0, 0), boxes=[
        Box((-0.6, 0, -0.6), (1.2, 6, 1.2), rope_style()),
        Box((-0.75, 6, -0.75), (1.5, 9, 1.5), rope_style(sally=True)),
        Box((-0.6, 15, -0.6), (1.2, 16, 1.2), rope_style()),
        Box((-1.0, 30.6, -1.0), (2.0, 1.4, 2.0), rope_style()),
    ]))


# ---- The ringer: squatting on the crown loop, knees up and splayed, a hunched spine, arms reaching down to the ropes. ----

BONE_SHADOW = (150, 142, 116)


def rotx(v, a):
    """Turns a (y, z) offset by pitch `a`, as a model part does."""
    y, z = v
    return (y * math.cos(a) - z * math.sin(a), y * math.sin(a) + z * math.cos(a))


def arm_angles(shoulder, hand, upper, lower):
    """Angles from straight down for a two-bone arm reaching from shoulder to hand in the XY plane, the elbow out."""
    dx, dy = abs(hand[0] - shoulder[0]), hand[1] - shoulder[1]
    dist = math.hypot(dx, dy)
    alpha = math.acos(max(-1, min(1, (upper ** 2 + dist ** 2 - lower ** 2) / (2 * upper * dist))))
    phi1 = math.atan2(dx, dy) + alpha
    ex, ey = upper * math.sin(phi1), upper * math.cos(phi1)
    return phi1, math.atan2(dx - ex, dy - ey)


RINGER_Y = -4.8
SPINE_LOW, SPINE_HIGH = 0.18, 0.75    # pitch of the lower back, and of the hunched upper back in total
_chest = rotx((-5.0, 0), SPINE_LOW)
_sh = rotx((-4.4, -0.3), SPINE_HIGH)
SHOULDER_LOCAL = (3.6, -2.4 + _chest[0] + _sh[0], _chest[1] + _sh[1])   # ringer-local
SHOULDER = (SHOULDER_LOCAL[0], RINGER_Y + SHOULDER_LOCAL[1], SHOULDER_LOCAL[2] + 0.3)   # bell-local
HAND = (16.6, 9.5)
UPPER, LOWER = 12.6, 15.2
PHI1, PHI2 = arm_angles(SHOULDER, HAND, UPPER, LOWER)
ARM_PITCH = math.atan2(-SHOULDER[2], HAND[1] - SHOULDER[1])


def bone(shadow=0.85):
    """Bone-grey skin stretched over bone, darker in the cowl's shadow."""
    def paint(p, f, opts):
        for v in range(f.h):
            for u in range(f.w):
                c = shade(BONE_SHADOW, shadow * p.noise(0.06) * (1.05 - 0.2 * v / max(1, f.h)))
                p.put(f, u, v, c)
    return paint


def jaw_style(p, f, opts):
    """The long jaw: bone with a dark slit of a mouth and a row of teeth."""
    d = opts.get("density", 1)
    bone(0.72)(p, f, opts)
    if f.side != "front":
        return
    my = int(f.h * 0.55)
    for u in range(1, f.w - 1):
        p.put(f, u, my, (20, 14, 12))
        if u % 2 == 0:
            p.put(f, u, my - 1, (196, 186, 150))
            p.put(f, u, my + 1, (180, 170, 136))


def eyes_style(p, f, opts):
    """Sunken sockets under the brow, each with a small ember deep inside."""
    for v in range(f.h):
        for u in range(f.w):
            p.put(f, u, v, (14, 10, 10))
    if f.side != "front":
        return
    for cx in (f.w * 0.25, f.w * 0.75):
        for v in range(f.h):
            for u in range(f.w):
                if abs(u + 0.5 - cx) <= 1 and abs(v + 0.5 - f.h / 2) <= 1:
                    p.put(f, u, v, EMBER_EYE, EMBER_EYE)


def chest_style(p, f, opts):
    """The torn sackcloth gapes over the chest: the front is shadowed grey skin, the rest is cloth."""
    if f.side == "front":
        for v in range(f.h):
            for u in range(f.w):
                p.put(f, u, v, shade(FLESH_DARK, p.noise(0.08) * (0.9 + 0.2 * abs(u / max(1, f.w) - 0.5))))
        return
    sackcloth(patch=False)(p, f, opts)


cloak = sackcloth(patch=False)
rags = sackcloth(ragged=True, patch=False)


def finger(name, side, z):
    """A long finger in two joints, curled round the rope."""
    return Part(name, pivot=(side * 0.55, LOWER - 0.5, z), rotation=(0, 0, side * 0.8), boxes=[Box((-0.22, 0, -0.22), (0.44, 1.5, 0.44), flesh)],
                children=[Part(name + "_tip", pivot=(0, 1.4, 0), rotation=(0, 0, side * 1.5), boxes=[Box((-0.2, 0, -0.2), (0.4, 1.3, 0.4), flesh)])])


def ringer_arm(label, side):
    fingers = [finger("%s_finger_%d" % (label, k), side, -0.8 + k * 0.53) for k in range(4)]
    fingers.append(Part(label + "_thumb", pivot=(-side * 0.5, LOWER - 1.2, -0.9), rotation=(-0.6, 0, -side * 0.5),
                        boxes=[Box((-0.22, 0, -0.22), (0.44, 1.6, 0.44), flesh)]))
    forearm = Part(label + "_forearm", pivot=(0, UPPER, 0), rotation=(0, 0, side * (PHI1 - PHI2)), boxes=[
        Box((-1.15, -1.0, -1.15), (2.3, 2.0, 2.3), bone(1.0)),               # the elbow knob
        Box((-0.85, 0.8, -0.85), (1.7, 5.6, 1.7), flesh),
        Box((-0.6, 6.2, -0.6), (1.2, LOWER - 8.4, 1.2), flesh),             # tapering to the wrist
        Box((-0.9, LOWER - 2.4, -1.1), (1.8, 2.0, 2.2), flesh),              # the palm
    ], children=fingers)
    return Part(label + "_arm", pivot=(side * SHOULDER_LOCAL[0], SHOULDER_LOCAL[1], SHOULDER_LOCAL[2]), rotation=(ARM_PITCH, 0, -side * PHI1), boxes=[
        Box((-1.6, -1.2, -1.6), (3.2, 4.6, 3.2), rags),
        Box((-0.95, 0, -0.95), (1.9, 6.4, 1.9), flesh),
        Box((-0.75, 6.2, -0.75), (1.5, UPPER - 6.2, 1.5), flesh),
    ], children=[forearm])


def leg(label, side):
    """Thigh about level and splayed, the shin angled down in front of the beam, a long bony foot."""
    return Part(label + "_leg", pivot=(side * 2.2, -1.4, -1.0), rotation=(-1.5, -side * 0.45, 0), boxes=[
        Box((-1.2, 0, -1.2), (2.4, 6.4, 2.4), cloak),
    ], children=[
        Part(label + "_shin", pivot=(0, 6.2, 0), rotation=(1.78, 0, 0), boxes=[
            Box((-1.0, -0.8, -1.0), (2.0, 1.6, 2.0), bone(1.0)),           # the knee
            Box((-0.75, 0.6, -0.75), (1.5, 5.6, 1.5), flesh),
            Box((-0.9, 6.0, -3.0), (1.8, 0.8, 3.6), flesh),
        ]),
    ])


# The sackcloth hangs from his hips all round the crown loop he squats on.
robe_panels = []
for i in range(10):
    a = math.pi * 2 * i / 10
    robe_panels.append(Part("robe_%d" % i, pivot=(math.sin(a) * 3.2, -2.2, -math.cos(a) * 3.0), rotation=(-0.22, -a, 0),
                            boxes=[Box((-1.6, 0, -0.3), (3.2, 7.4 + 1.0 * math.sin(i * 2.1), 0.6), rags)]))

# The skull: a narrow cranium, a jutting brow, high cheekbones and a long jaw; small embers deep in the sockets.
skull = [
    Box((-1.5, -5.4, -1.7), (3.0, 3.4, 3.5), bone(0.55)),
    Box((-1.75, -3.25, -2.25), (3.5, 0.7, 0.9), bone(0.95)),
    Box((-1.3, -2.6, -1.85), (2.6, 0.75, 0.2), eyes_style, flat=True),
    Box((-1.75, -1.95, -2.0), (0.9, 0.7, 1.0), bone(0.9)),
    Box((0.85, -1.95, -2.0), (0.9, 0.7, 1.0), bone(0.9)),
    Box((-1.0, -2.0, -2.0), (2.0, 3.0, 2.4), jaw_style),
]
# The cowl: open in front, deep enough to shadow the face, its peak tapering forward over the brow to a point.
cowl = [
    Box((-2.4, -6.3, -3.0), (4.8, 1.0, 5.8), cloak),
    Box((-2.4, -5.4, -2.8), (0.7, 5.4, 5.4), cloak),
    Box((1.7, -5.4, -2.8), (0.7, 5.4, 5.4), cloak),
    Box((-2.4, -5.4, 2.0), (4.8, 5.4, 0.7), cloak),
    Box((-2.4, -5.6, -3.6), (4.8, 1.3, 0.8), cloak),         # the brim, low over the brow: the face stays in shadow
    Box((-1.6, -6.4, -4.4), (3.2, 0.9, 1.4), cloak),         # the peak, tapering forward to a point
    Box((-0.9, -6.2, -5.4), (1.8, 0.7, 1.1), cloak),
    Box((-0.35, -6.0, -6.2), (0.7, 0.5, 0.9), cloak),
    Box((-0.12, -5.9, -6.8), (0.24, 0.3, 0.6), cloak),
]
hood_lappets = [Part("lappet_%d" % (s + 1), pivot=(s * 2.2, -0.6, -0.6), rotation=(0.15, 0, s * 0.3),
                     boxes=[Box((-0.9, 0, -1.6), (1.8, 4.6, 3.2), rags)]) for s in (-1, 1)]

ribs = [Box((-2.4, -4.3 + k * 1.0, -2.4), (4.8, 0.35, 0.35), bone(1.0)) for k in range(4)] + [Box((-0.25, -4.5, -2.45), (0.5, 3.8, 0.4), bone(1.0))]

ringer = Part("ringer", pivot=(0, RINGER_Y, 0.3), children=[
    Part("hips", boxes=[Box((-3.2, -2.8, -2.4), (6.4, 2.8, 4.8), cloak)]),
    Part("robe", children=robe_panels),
    leg("right", -1), leg("left", 1),
    Part("torso", pivot=(0, -2.4, 0), rotation=(SPINE_LOW, 0, 0), boxes=[
        Box((-3.0, -5.2, -2.0), (6.0, 5.4, 4.0), cloak),
        Box((-3.3, -1.4, -2.25), (6.6, 1.0, 4.5), rope_style()),
    ], children=[
        Part("chest", pivot=(0, -5.0, 0), rotation=(SPINE_HIGH - SPINE_LOW, 0, 0), boxes=[
            Box((-3.2, -5.0, -2.1), (6.4, 5.2, 4.2), chest_style),
            Box((-3.5, -5.1, -2.4), (1.3, 5.3, 0.6), rags),
            Box((2.2, -5.1, -2.4), (1.3, 5.3, 0.6), rags),
            Box((-2.9, -5.6, 0.6), (5.8, 3.2, 2.6), cloak),
        ] + ribs, children=[
            Part("head", pivot=(0, -4.8, -1.0), rotation=(-0.5, 0, 0), boxes=skull + cowl, children=hood_lappets),
        ]),
    ]),
    ringer_arm("right", -1),
    ringer_arm("left", 1),
])

bell = Part("bell", pivot=(0, -34, 0), children=rings + rims + cap + loops + [face] + crosses + [beam, clapper, ringer] + ropes)

parts = [Part("figure", pivot=(0, 24, 0), children=[bell])]

if __name__ == "__main__":
    build("BellRinger", "bell_ringer", parts, size=256, seed=57, density=4)
