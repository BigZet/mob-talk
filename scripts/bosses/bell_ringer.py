"""Звонарь погоста: a colossal living church bell of old bronze. Its body is a real bell profile turned from many rings of
cast panels: a domed shoulder, a band of pseudo-Slavonic lettering, a long waist with a cast mournful face whose eyes are
two burning candles, raised Orthodox crosses on the other three sides, a second band of lettering, a frieze on the sound
bow and a flared lip; green patina runs down from every cast rim. It hangs from crown loops on an iron-strapped oak beam,
and its heavy iron clapper on a chain is the foot it hops on. On the crown squats the ringer, a gaunt hunched thing in
rotten sackcloth whose long arms grip the two bell ropes that hang from the beam ends.

Built about 56 units tall and drawn at scale 1.75 (about six blocks), at 4 pixels per unit."""
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
            cx = u + s
            for v in range(5 * s):
                p.put(f, cx, top + v, BR_WORN)
            for du in range(-s, s + 1):
                p.put(f, cx + du, top + s, BR_WORN)
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
    cheeks, the folds from nose to mouth and a downturned mouth. The relief (brows, nose, moustache) is modelled."""
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
    sx, sy = math.ceil(width) / width, math.ceil(length) / length
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


def ringer_face(p, f, opts):
    """The face deep in the hood: sunken grey skin, hollow cheeks, a long nose and two embers for eyes."""
    W, H = f.w, f.h
    for v in range(H):
        for u in range(W):
            dx = (u - W / 2) / (W / 2)
            c = shade(FLESH, 1.0 - 0.45 * dx * dx - 0.3 * v / H)
            if 0.55 * H < v < 0.85 * H and 0.3 < abs(dx) < 0.75:
                c = shade(c, 0.6)
            p.put(f, u, v, shade(c, p.noise(0.05)))
    if f.side != "front":
        return
    for side in (-1, 1):
        ex, ey = W / 2 + side * W * 0.24, H * 0.36
        for v in range(H):
            for u in range(W):
                q = ((u - ex) / (W * 0.17)) ** 2 + ((v - ey) / (H * 0.13)) ** 2
                if q <= 1:
                    p.put(f, u, v, (12, 8, 8))
                if q <= 0.3:
                    p.put(f, u, v, EMBER_EYE, EMBER_EYE)
    for v in range(int(H * 0.36), int(H * 0.7)):
        p.put(f, W // 2, v, shade(FLESH, 1.15))
        p.put(f, W // 2 - 1, v, shade(FLESH, 0.7))
    for u in range(int(W * 0.3), int(W * 0.7)):
        p.put(f, u, int(H * 0.82), (30, 20, 18))


def hood_style(p, f, opts):
    sackcloth(patch=False)(p, f, opts)
    if f.side == "front":
        for v in range(f.h):
            for u in range(f.w):
                q = ((u - f.w / 2) / (f.w * 0.42)) ** 2 + ((v - f.h * 0.62) / (f.h * 0.52)) ** 2
                if q <= 1:
                    p.put(f, u, v, (10, 8, 8))


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
    Part("moustache_r", pivot=(-0.6, 7.6, -0.4), rotation=(0, 0, -0.55), boxes=[Box((-4.2, -0.5, -0.8), (4.2, 1.0, 0.8), relief_bronze)]),
    Part("moustache_l", pivot=(0.6, 7.6, -0.4), rotation=(0, 0, 0.55), boxes=[Box((0, -0.5, -0.8), (4.2, 1.0, 0.8), relief_bronze)]),
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
for idx, a in enumerate((math.pi / 2, math.pi, 3 * math.pi / 2)):
    crosses.append(relief("cross_%d" % idx, a, 12, 9.3, [
        Box((-0.45, 1.4, -0.7), (0.9, 7.8, 0.7), relief_bronze),
        Box((-1.0, 2.3, -0.7), (2.0, 0.6, 0.7), relief_bronze),
        Box((-2.3, 3.5, -0.7), (4.6, 0.8, 0.7), relief_bronze),
        Box((-1.8, 8.6, -0.6), (3.6, 0.8, 0.6), relief_bronze),
    ], children=[Part("cross_%d_foot" % idx, pivot=(0, 6.6, 0), rotation=(0, 0, -0.35), boxes=[Box((-1.5, -0.35, -0.7), (3.0, 0.7, 0.7), relief_bronze)])]))

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

# The clapper hangs from inside the crown; its ball sits in the mouth and its flight is the foot the bell hops on.
clapper = Part("clapper", pivot=(0, 7.4, 0), boxes=[
    Box((-0.8, 13.5, -0.8), (1.6, 8.5, 1.6), iron),
    Box((-1.4, 21, -1.4), (2.8, 1.4, 2.8), iron_style(True)),
    Box((-0.9, 27.6, -0.9), (1.8, 3.6, 1.8), iron),
    Box((-2.2, 30.8, -2.2), (4.4, 0.8, 4.4), iron_style(True)),
], children=chain_links("clink", 0, 14) + octagon("cball_a", 21.6, 2.2, 1.2, iron_style(True))
            + octagon("cball_b", 22.8, 3.1, 3.6, iron_style(True)) + octagon("cball_c", 26.4, 2.2, 1.2, iron))

ropes = []
for side, label in ((-1, "right"), (1, "left")):
    ropes.append(Part(label + "_rope", pivot=(side * 16.6, 1.0, 0), boxes=[
        Box((-0.6, 0, -0.6), (1.2, 6, 1.2), rope_style()),
        Box((-0.75, 6, -0.75), (1.5, 9, 1.5), rope_style(sally=True)),
        Box((-0.6, 15, -0.6), (1.2, 21, 1.2), rope_style()),
        Box((-1.0, 35.2, -1.0), (2.0, 1.4, 2.0), rope_style()),
    ]))


# ---- The ringer: squatting on the crown loop, hunched forward, arms reaching down to the ropes. ----

def arm_angles(shoulder, hand, upper, lower):
    """Angles from straight down for a two-bone arm reaching from shoulder to hand in the XY plane, the elbow out."""
    dx, dy = abs(hand[0] - shoulder[0]), hand[1] - shoulder[1]
    dist = math.hypot(dx, dy)
    alpha = math.acos(max(-1, min(1, (upper ** 2 + dist ** 2 - lower ** 2) / (2 * upper * dist))))
    phi1 = math.atan2(dx, dy) + alpha
    ex, ey = upper * math.sin(phi1), upper * math.cos(phi1)
    return phi1, math.atan2(dx - ex, dy - ey)


RINGER_Y = -4.8
SHOULDER = (3.9, RINGER_Y - 9.4, -4.6)
HAND = (16.6, 9.5)
UPPER, LOWER = 13.0, 13.4
PHI1, PHI2 = arm_angles(SHOULDER, HAND, UPPER, LOWER)
ARM_PITCH = math.atan2(-SHOULDER[2] - 0.3, HAND[1] - SHOULDER[1])


def ringer_arm(label, side):
    claw = [Part("%s_finger_%d" % (label, k), pivot=(0, LOWER - 0.8, -1.0 + k * 0.62), rotation=(0, 0, side * 1.2),
                 boxes=[Box((-0.3, 0, -0.28), (0.6, 2.6, 0.56), flesh)]) for k in range(4)]
    forearm = Part(label + "_forearm", pivot=(0, UPPER, 0), rotation=(0, 0, side * (PHI1 - PHI2)), boxes=[
        Box((-0.85, 0, -0.85), (1.7, LOWER - 2.2, 1.7), flesh),
        Box((-1.2, LOWER - 2.4, -1.3), (2.4, 2.4, 2.6), flesh),
        Box((-1.15, -0.9, -1.15), (2.3, 1.8, 2.3), flesh),
    ], children=claw)
    return Part(label + "_arm", pivot=(side * SHOULDER[0], SHOULDER[1] - RINGER_Y, SHOULDER[2]), rotation=(ARM_PITCH, 0, -side * PHI1), boxes=[
        Box((-1.6, -1.2, -1.6), (3.2, 4.8, 3.2), sackcloth(ragged=True, patch=False)),
        Box((-0.95, 0, -0.95), (1.9, UPPER, 1.9), flesh),
    ], children=[forearm])


def leg(label, side):
    return Part(label + "_leg", pivot=(side * 2.1, -1.2, -0.8), rotation=(-1.35, side * -0.25, 0), boxes=[
        Box((-1.1, 0, -1.1), (2.2, 6.2, 2.2), sackcloth(patch=False)),
    ], children=[
        Part(label + "_shin", pivot=(0, 6.0, 0), rotation=(1.45, 0, 0), boxes=[
            Box((-0.9, 0, -0.9), (1.8, 6.4, 1.8), flesh),
            Box((-1.1, 6.0, -2.6), (2.2, 0.9, 3.4), flesh),
        ]),
    ])


robe_panels = []
for i in range(9):
    a = math.pi * 2 * i / 9
    robe_panels.append(Part("robe_%d" % i, pivot=(math.sin(a) * 2.8, -1.8, -math.cos(a) * 2.6), rotation=(-0.45, -a, 0),
                            boxes=[Box((-1.6, 0, -0.3), (3.2, 4.8 + 1.2 * math.sin(i * 2.1), 0.6), sackcloth(ragged=True, patch=False))]))

hood_lappets = [Part("lappet_%d" % (s + 1), pivot=(s * 2.4, -1.5, -1.4), rotation=(0.2, 0, s * 0.25),
                     boxes=[Box((-0.9, 0, -1.5), (1.8, 5.5, 3.0), sackcloth(ragged=True, patch=False))]) for s in (-1, 1)]

ringer = Part("ringer", pivot=(0, RINGER_Y, 0.3), children=[
    Part("hips", boxes=[Box((-3.2, -2.8, -2.4), (6.4, 2.8, 4.8), sackcloth())]),
    Part("robe", children=robe_panels),
    leg("right", -1), leg("left", 1),
    Part("torso", pivot=(0, -2.4, 0), rotation=(0.62, 0, 0), boxes=[
        Box((-3.4, -9.2, -2.2), (6.8, 9.4, 4.4), sackcloth()),
        Box((-3.0, -9.6, 0.4), (6.0, 3.6, 3.0), sackcloth()),
        Box((-3.6, -1.6, -2.45), (7.2, 1.0, 4.9), rope_style()),
    ] + [Box((-0.5, -8.5 + k * 1.8, 2.1), (1.0, 1.0, 0.7), flesh) for k in range(4)], children=[
        Part("head", pivot=(0, -9.2, -1.2), rotation=(-0.75, 0, 0), boxes=[
            Box((-2.7, -5.8, -3.1), (5.4, 6.0, 5.6), hood_style),
            Box((-1.9, -5.0, -2.6), (3.8, 4.4, 0.6), ringer_face),
            Box((-1.3, -7.0, -0.2), (2.6, 1.6, 2.6), sackcloth(patch=False)),
        ], children=hood_lappets + [
            Part("hood_peak", pivot=(0, -6.8, 1.0), rotation=(-0.6, 0, 0), boxes=[Box((-0.8, -2.6, -0.8), (1.6, 2.6, 1.6), sackcloth(patch=False))]),
        ]),
    ]),
    ringer_arm("right", -1),
    ringer_arm("left", 1),
])

bell = Part("bell", pivot=(0, -39, 0), children=rings + rims + cap + loops + [face] + crosses + [beam, clapper, ringer] + ropes)

parts = [Part("figure", pivot=(0, 24, 0), children=[bell])]

if __name__ == "__main__":
    build("BellRinger", "bell_ringer", parts, size=256, seed=57, density=4)
