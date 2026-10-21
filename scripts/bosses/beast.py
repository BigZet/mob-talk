"""Зверь из бездны (Revelation 13:1-3): a leopard's spotted, heavily muscled body on a bear's feet with great claws and a heavy
tail; seven long serpentine necks of many segments, each ending in a lion's head with a mane of strands, an opening jaw, fangs and
a burning maw; ten horns with ten small gold crowns; names of blasphemy written in glowing red on the heads; one head wounded to
death (a smouldering gash that turns into a pale scar when it heals). Built about 34 units tall at the shoulder and 75 long and
drawn at scale 2.2 (about ten blocks long and seven tall with the heads raised), at 4 pixels per unit."""
from boxtex import *

TAWNY = (184, 132, 62)
TAWNY_DARK = (128, 84, 36)
CREAM = (226, 202, 150)
SPOT = (36, 24, 16)
BEAR = (86, 60, 40)
BEAR_DARK = (38, 26, 18)
BEAR_LIGHT = (112, 82, 56)
MANE = (118, 64, 24)
MANE_DARK = (58, 30, 14)
MANE_LIGHT = (196, 126, 54)
SIGIL = (236, 34, 26)
MAW = (255, 120, 30)
MAW_DEEP = (190, 30, 14)
HORN_DARK = (58, 46, 40)
CLAW = (226, 214, 186)
CLAW_TIP = (54, 44, 38)
NOSE = (40, 26, 22)

# Which heads carry two horns (3 x 2 + 4 x 1 = 10) and which one is wounded to death.
TWO_HORNS = {2, 3, 4}
WOUNDED = 5
SIDES = ("front", "back", "left", "right")


def rosettes(p, f, d, count_div=16, base=TAWNY):
    """Leopard rosettes: broken dark rings with a darker tawny heart."""
    rand = p.rand
    n = max(1, f.w * f.h // (d * d * count_div))
    for _ in range(n):
        cu, cv = rand.randrange(f.w), rand.randrange(f.h)
        r = d * rand.uniform(0.8, 1.4)
        gap = rand.uniform(0, 2 * math.pi)
        for dv in range(-int(r) - 2, int(r) + 3):
            for du in range(-int(r) - 2, int(r) + 3):
                dist = math.hypot(du, dv)
                u, v = cu + du, cv + dv
                if not (0 <= u < f.w and 0 <= v < f.h):
                    continue
                if dist < r - d * 0.55:
                    p.put(f, u, v, shade(base, 0.78 * p.noise(0.05)))
                elif dist < r + d * 0.35:
                    a = math.atan2(dv, du)
                    if math.cos(a - gap) < 0.82 and math.cos(a - gap - 2.6) < 0.9:
                        p.put(f, u, v, shade(SPOT, p.noise(0.1)))


def leopard(base=TAWNY, spots=True, belly_side=None):
    """Tawny hide with fine fur streaks, paler toward the belly, covered in rosettes; `belly_side` is painted as cream scutes."""
    def paint(p, f, opts):
        d = opts.get("density", 1)
        for v in range(f.h):
            for u in range(f.w):
                k = p.noise(0.05) * (0.93 + 0.07 * math.sin(u * 0.9 + v * 0.35))
                if f.side == "bottom":
                    c = CREAM
                elif f.side in SIDES:
                    t = v / max(1, f.h - 1)
                    c = mix(base, CREAM, max(0.0, (t - 0.62) / 0.38) * 0.75)
                else:
                    c = shade(base, 0.96)
                p.put(f, u, v, shade(c, k))
        if belly_side and f.side == belly_side:
            for v in range(f.h):
                for u in range(f.w):
                    c = mix(CREAM, TAWNY, 0.35 + 0.1 * math.sin(v / max(1, d) * 1.6))
                    if v % (3 * d) == 0:
                        c = shade(c, 0.86)
                    p.put(f, u, v, shade(c, p.noise(0.04)))
            return
        if spots and f.side != "bottom":
            rosettes(p, f, d, base=base)
    return paint


def bear_fur(base=BEAR):
    """Shaggy dark fur: short overlapping strokes."""
    def paint(p, f, opts):
        d = opts.get("density", 1)
        for v in range(f.h):
            for u in range(f.w):
                s = math.sin(u * 1.9 + (v // max(1, d)) * 2.7 + math.sin(v * 0.8) * 1.5)
                c = mix(BEAR_DARK, mix(base, BEAR_LIGHT, 0.35), 0.5 + 0.5 * s)
                if f.side == "bottom":
                    c = shade(c, 0.8)
                p.put(f, u, v, shade(c, p.noise(0.08)))
    return paint


def paw(p, f, opts):
    bear_fur()(p, f, opts)
    if f.side == "bottom":
        for v in range(f.h):
            for u in range(f.w):
                if ((u - f.w / 2) / (f.w * 0.42)) ** 2 + ((v - f.h * 0.6) / (f.h * 0.35)) ** 2 < 1:
                    p.put(f, u, v, shade((40, 32, 30), p.noise(0.08)))


def claw(tip=False):
    def paint(p, f, opts):
        for v in range(f.h):
            for u in range(f.w):
                c = CLAW_TIP if tip else mix(CLAW, (170, 156, 126), v / max(1, f.h - 1))
                if not tip and (u + v) % 7 == 0:
                    c = shade(c, 0.88)
                p.put(f, u, v, shade(c, p.noise(0.05)))
    return paint


def horn_style(t):
    """Ridged horn: dark at the root, bone toward the tip, a ring every unit."""
    def paint(p, f, opts):
        d = opts.get("density", 1)
        for v in range(f.h):
            for u in range(f.w):
                c = mix(HORN_DARK, BONE, t)
                if f.side in SIDES and v % (d + 1) == 0:
                    c = shade(c, 0.72)
                p.put(f, u, v, shade(c, p.noise(0.06)))
    return paint


def glyph(p, f, u0, v0, d, rand, glow=True):
    """One letter of a name of blasphemy: a stem with hooks and bars, burning red."""
    w, h = 2 * d, 3 * d
    strokes = []
    stem = rand.randrange(3)
    strokes.append(((stem * w) // 2, 0, (stem * w) // 2, h))
    for _ in range(rand.randrange(1, 3)):
        y = rand.randrange(h)
        strokes.append((0, y, w, y + rand.choice((-d, 0, d))))
    if rand.random() < 0.5:
        strokes.append((0, 0, w, h))
    for x0, y0, x1, y1 in strokes:
        n = max(abs(x1 - x0), abs(y1 - y0), 1)
        for i in range(n + 1):
            x = u0 + x0 + (x1 - x0) * i // n
            y = v0 + y0 + (y1 - y0) * i // n
            for dx in range(max(1, d // 3)):
                p.put(f, x + dx, y, SIGIL, SIGIL if glow else None)


def sigil_rows(p, f, d, rows):
    rand = p.rand
    for v in rows:
        u = d
        while u + 2 * d < f.w - d:
            glyph(p, f, u, v, d, rand)
            u += 3 * d


def skull(wounded=False, letters=True):
    """The lion's crown and brow: tawny fur darkening at the brow lines, names of blasphemy in red across the top and forehead."""
    def paint(p, f, opts):
        d = opts.get("density", 1)
        for v in range(f.h):
            for u in range(f.w):
                c = mix(TAWNY, MANE, 0.18)
                if f.side == "front":
                    dx = (u - f.w / 2) / (f.w / 2)
                    c = shade(c, 1.05 - 0.25 * dx * dx)
                    if abs(dx) < 0.12 and v > f.h * 0.35:
                        c = shade(c, 0.86)
                p.put(f, u, v, shade(c, p.noise(0.05)))
        if not letters:
            return
        if f.side == "top":
            sigil_rows(p, f, d, range(d, f.h - 3 * d, 4 * d))
        if f.side == "front":
            sigil_rows(p, f, d, [d])
        if f.side in ("left", "right") and not wounded:
            sigil_rows(p, f, d, [d])
    return paint


def muzzle(p, f, opts):
    """The snout: paler fur, whisker dots and a dark nose line on the front; underneath, the burning roof of the mouth."""
    d = opts.get("density", 1)
    for v in range(f.h):
        for u in range(f.w):
            c = mix(CREAM, TAWNY, 0.62)
            if f.side == "top":
                c = mix(TAWNY, MANE, 0.2)
            if f.side == "bottom":
                t = abs(u - f.w / 2) / (f.w / 2)
                c = mix(MAW, MAW_DEEP, t)
                p.put(f, u, v, c, c)
                continue
            p.put(f, u, v, shade(c, p.noise(0.05)))
    if f.side in ("left", "right", "front"):
        for i in range(6):
            p.put(f, (i * 3 * d // 2 + d) % max(1, f.w), f.h - 1 - d - (i % 2) * d, SPOT)
    if f.side == "front":
        for v in range(f.h // 2):
            p.put(f, f.w // 2, f.h // 2 + v, NOSE)
            p.put(f, f.w // 2 - 1, f.h // 2 + v, NOSE)


def jaw_style(p, f, opts):
    """The lower jaw: darker fur outside; its top is the burning tongue rimmed with teeth."""
    d = opts.get("density", 1)
    for v in range(f.h):
        for u in range(f.w):
            if f.side == "top":
                rim = u < d or u >= f.w - d
                c = BONE if rim else mix(MAW, MAW_DEEP, v / max(1, f.h - 1) * 0.6)
                p.put(f, u, v, c, None if rim else c)
                continue
            c = mix(CREAM, TAWNY_DARK, 0.5) if f.side != "bottom" else CREAM
            p.put(f, u, v, shade(c, p.noise(0.06)))
    if f.side == "front":
        for u in range(0, f.w, 2):
            p.put(f, u, 0, BONE)


def throat(p, f, opts):
    for v in range(f.h):
        for u in range(f.w):
            t = math.hypot(u - f.w / 2, v - f.h / 2) / max(1, f.w / 2)
            c = mix((255, 236, 160), MAW_DEEP, min(1.0, t))
            p.put(f, u, v, c, c)


def eye(p, f, opts):
    """A lion's eye burning amber, a black slit in the middle."""
    for v in range(f.h):
        for u in range(f.w):
            c = mix(CANDLE, FLAME, v / max(1, f.h - 1))
            if abs(u - (f.w - 1) / 2) < 0.8:
                p.put(f, u, v, (20, 8, 4))
                continue
            p.put(f, u, v, c, c)


def strand(p, f, opts):
    """One lock of the mane: dark at the root, lit toward the crest, with strand lines."""
    d = opts.get("density", 1)
    long_side = f.h >= f.w
    L, W = (f.h, f.w) if long_side else (f.w, f.h)
    for a in range(L):
        for b in range(W):
            u, v = (b, a) if long_side else (a, b)
            t = a / max(1, L - 1)
            c = mix(MANE_DARK, MANE, min(1.0, t * 1.6))
            if b % max(2, d // 2 + 1) == 0:
                c = shade(c, 0.75)
            if t > 0.75 and (b + a) % 3 == 0:
                c = mix(c, MANE_LIGHT, 0.5)
            if t > 0.88 and p.rand.random() < 0.35:
                p.put(f, u, v, None)
                continue
            p.put(f, u, v, shade(c, p.noise(0.07)))


mane_core = hair(MANE, MANE_LIGHT)


def wound(p, f, opts):
    """The deadly wound on a thin plane: a ragged gash, black at the edges and smouldering inside; transparent around it."""
    for v in range(f.h):
        for u in range(f.w):
            t = u / max(1, f.w - 1)
            centre = f.h * (0.25 + 0.5 * t)
            half = f.h * 0.16 * math.sin(math.pi * t) + 0.6 * math.sin(u * 1.7)
            dist = abs(v - centre)
            if dist > half + 1.5:
                p.put(f, u, v, None)
            elif dist > half - 1:
                p.put(f, u, v, (26, 6, 6))
            else:
                c = mix((255, 150, 60), EMBER, dist / max(1, half))
                p.put(f, u, v, c, c)


def scar(p, f, opts):
    """The healed wound: a pale, knotted scar along the same line."""
    for v in range(f.h):
        for u in range(f.w):
            t = u / max(1, f.w - 1)
            centre = f.h * (0.25 + 0.5 * t)
            half = max(1.0, f.h * 0.06 * math.sin(math.pi * t))
            if abs(v - centre) > half:
                p.put(f, u, v, None)
            else:
                p.put(f, u, v, shade((214, 150, 140), p.noise(0.08)) if u % 5 else (150, 80, 76))


crown_gold = gold_leaf(ornament=False)


def horn(name, pivot, rotation, segments=4, length=2.4, width=1.5):
    """A horn of rotated pieces, tapering and curving back, with a little gold crown around its root."""
    root = parent = None
    for k in range(segments):
        w = width * (1 - 0.2 * k)
        seg = Part("%s_%d" % (name, k), pivot=pivot if k == 0 else (0, -length + 0.2, 0),
                   rotation=rotation if k == 0 else (-0.32, 0, 0),
                   boxes=[Box((-w / 2, -length, -w / 2), (w, length, w), horn_style(k / max(1, segments - 1)))])
        if k == 0:
            # The crown: a gold band with four teeth and a red stone around the root of the horn.
            seg.children.append(Part(name + "_crown", pivot=(0, -0.6, 0), boxes=[
                Box((-1.4, -1.2, -1.4), (2.8, 1.2, 2.8), crown_gold),
                Box((-1.4, -2.0, -1.4), (0.6, 0.8, 0.6), crown_gold), Box((0.8, -2.0, -1.4), (0.6, 0.8, 0.6), crown_gold),
                Box((-1.4, -2.0, 0.8), (0.6, 0.8, 0.6), crown_gold), Box((0.8, -2.0, 0.8), (0.6, 0.8, 0.6), crown_gold),
                Box((-0.3, -1.0, -1.65), (0.6, 0.6, 0.3), solid(CINNABAR, glow=True), flat=True),
            ]))
            root = seg
        else:
            parent.children.append(seg)
        parent = seg
    return root


def mane_ring(i, ring, count, radius, length, z, spread, seed):
    """A ring of mane locks around the face, each swept back over the neck and flaring outward, like a lion's ruff."""
    rnd = random.Random(seed)
    out = []
    for k in range(count):
        a = 2 * math.pi * (k + 0.5 * ring) / count
        l = length * rnd.uniform(0.8, 1.2) * (0.75 if math.cos(a) < -0.5 else 1.0)
        tilt = spread * rnd.uniform(0.85, 1.15)
        out.append(Part("mane_%d_%d_%d" % (i, ring, k), pivot=(math.sin(a) * radius, -3.8 - math.cos(a) * radius * 0.95, z),
                        rotation=(tilt * math.cos(a), tilt * math.sin(a), rnd.uniform(-0.3, 0.3)),
                        boxes=[Box((-1.0, -0.8, 0), (2.0, 1.6, l), strand)]))
    return out


def lion_head(i, pitch, yaw, roll):
    wounded = i == WOUNDED
    horns = []
    if i in TWO_HORNS:
        horns.append(horn("horn_%d_r" % i, (-2.0, -6.8, -0.8), (-0.25, 0, -0.32)))
        horns.append(horn("horn_%d_l" % i, (2.0, -6.8, -0.8), (-0.25, 0, 0.32)))
    else:
        horns.append(horn("horn_%d" % i, (0, -6.9, -1.2), (-0.3, 0, 0), segments=4, length=2.6, width=1.7))
    extras = []
    if wounded:
        extras.append(Part("wound_%d" % i, pivot=(3.62, -6.8, -4.2), rotation=(0, -math.pi / 2, 0),
                           boxes=[Box((0, 0, 0), (6.0, 5.0, 0.1), wound, flat=True)]))
        extras.append(Part("scar_%d" % i, pivot=(3.62, -6.8, -4.2), rotation=(0, -math.pi / 2, 0),
                           boxes=[Box((0, 0, 0), (6.0, 5.0, 0.1), scar, flat=True)]))
    fangs = [
        Box((-1.75, -0.9, -9.0), (0.7, 2.0, 0.7), solid(BONE, k=0.05)),
        Box((1.05, -0.9, -9.0), (0.7, 2.0, 0.7), solid(BONE, k=0.05)),
        Box((-1.0, -0.9, -9.3), (2.0, 0.6, 0.5), solid(BONE, k=0.05)),
    ]
    return Part("head_%d" % i, pivot=(0, -0.5, 0), rotation=(pitch, yaw, roll), boxes=[
        Box((-3.6, -7.2, -4.4), (7.2, 6.4, 6.8), skull(wounded)),
        Box((-3.9, -6.4, -5.4), (7.8, 1.4, 1.6), skull(wounded)),
        Box((-3.3, -4.6, -6.6), (6.6, 3.4, 2.6), muzzle),
        Box((-2.3, -3.9, -9.8), (4.6, 3.0, 3.4), muzzle),
        Box((-1.1, -4.1, -10.1), (2.2, 1.3, 1.0), solid(NOSE, k=0.08)),
        Box((-1.9, -2.8, -5.0), (3.8, 2.2, 0.1), throat, flat=True),
        Box((-3.0, -5.0, -4.65), (1.8, 1.1, 0.3), eye, flat=True),
        Box((1.2, -5.0, -4.65), (1.8, 1.1, 0.3), eye, flat=True),
        Box((-4.4, -8.6, -1.6), (8.8, 9.6, 5.2), mane_core),
        Box((-3.4, -8.4, -0.6), (1.4, 1.8, 1.0), skull(wounded, letters=False)),
        Box((2.0, -8.4, -0.6), (1.4, 1.8, 1.0), skull(wounded, letters=False)),
    ] + fangs, children=[
        Part("brow_r_%d" % i, pivot=(-2.0, -5.6, -5.0), rotation=(0, 0.1, 0.28), boxes=[Box((-2.0, -0.7, -0.8), (4.0, 1.2, 1.4), skull(wounded, letters=False))]),
        Part("brow_l_%d" % i, pivot=(2.0, -5.6, -5.0), rotation=(0, -0.1, -0.28), boxes=[Box((-2.0, -0.7, -0.8), (4.0, 1.2, 1.4), skull(wounded, letters=False))]),
        Part("cheek_r_%d" % i, pivot=(-3.2, -2.8, -5.2), rotation=(0, 0.42, 0.1), boxes=[Box((-1.0, -1.9, -2.8), (2.0, 3.4, 3.6), muzzle)]),
        Part("cheek_l_%d" % i, pivot=(3.2, -2.8, -5.2), rotation=(0, -0.42, -0.1), boxes=[Box((-1.0, -1.9, -2.8), (2.0, 3.4, 3.6), muzzle)]),
        Part("jaw_%d" % i, pivot=(0, -0.9, -4.8), boxes=[
            Box((-2.1, 0, -4.8), (4.2, 1.7, 5.0), jaw_style),
            Box((-1.75, -1.3, -4.4), (0.6, 1.3, 0.6), solid(BONE, k=0.05)),
            Box((1.15, -1.3, -4.4), (0.6, 1.3, 0.6), solid(BONE, k=0.05)),
            Box((-1.2, 1.5, -3.6), (2.4, 1.4, 2.4), strand),
        ]),
    ] + mane_ring(i, 0, 13, 4.1, 5.0, -1.2, 0.55, 100 + i) + mane_ring(i, 1, 15, 4.6, 6.4, 0.6, 0.85, 200 + i)
       + mane_ring(i, 2, 9, 3.0, 6.0, 1.8, 0.3, 300 + i) + horns + extras)


def neck(i):
    """A serpentine neck of segments leaning forward and rising, spotted, with cream belly scutes and a mane down its back."""
    side = i - 3
    count = 7 if abs(side) <= 1 else 6
    seg_len = 3.6 if abs(side) <= 1 else 3.4
    base_pitch = 0.62 + 0.16 * abs(side)
    bends = [0.1, 0.02, -0.1, -0.18, -0.2, -0.16, -0.12][:count]
    total = base_pitch + sum(bends[1:])
    first = parent = None
    for k in range(count):
        w = 4.8 - 1.4 * k / (count - 1)
        name = "neck_%d" % i if k == 0 else "neck_%d_%d" % (i, k)
        if k == 0:
            pivot, rotation = (side * 2.6, -7.5 + abs(side) * 0.9, -17.5 + abs(side) * 0.6), (base_pitch, -side * 0.24, side * 0.3)
        else:
            pivot, rotation = (0, -seg_len + 0.3, 0), (bends[k], 0, 0)
        seg = Part(name, pivot=pivot, rotation=rotation, boxes=[
            Box((-w / 2, -seg_len - 0.3, -w / 2), (w, seg_len + 0.6, w), leopard(belly_side="front")),
            Box((-w / 2 - 0.25, -seg_len * 0.6, -w / 2 - 0.25), (w + 0.5, 1.0, w * 0.55), leopard(belly_side="front")),
        ])
        if k >= count - 3:
            for m in range(3):
                seg.children.append(Part("neck_mane_%d_%d_%d" % (i, k, m), pivot=((m - 1) * 1.3, -seg_len * 0.5, w / 2 - 0.4),
                                         rotation=(-1.2 - 0.15 * m, 0, (m - 1) * 0.3),
                                         boxes=[Box((-0.8, -4.2, -0.4), (1.6, 4.2, 0.8), strand)]))
        if k == 0:
            first = seg
        else:
            parent.children.append(seg)
        parent = seg
    parent.children.append(lion_head(i, -total + 0.12, side * 0.16, -side * 0.15))
    return first


def leg(name, x, z, front):
    """A bear's leg: a heavy shaggy thigh, a thick forearm, a broad paw with four great curved claws."""
    claws = []
    for k, cx in enumerate((-2.2, -0.75, 0.75, 2.2)):
        claws.append(Part("%s_claw_%d" % (name, k), pivot=(cx, 1.6, -4.2), rotation=(0.35, cx * -0.06, 0), boxes=[
            Box((-0.5, -0.5, -2.8), (1.0, 1.1, 2.9), claw()),
        ], children=[
            Part("%s_claw_%d_tip" % (name, k), pivot=(0, 0, -2.6), rotation=(0.55, 0, 0), boxes=[
                Box((-0.35, -0.35, -2.2), (0.7, 0.8, 2.3), claw()),
            ], children=[Part("%s_claw_%d_end" % (name, k), pivot=(0, 0, -2.0), rotation=(0.5, 0, 0), boxes=[
                Box((-0.2, -0.2, -1.3), (0.4, 0.5, 1.4), claw(tip=True)),
            ])]),
        ]))
    thigh = ((-3.6, -4, -4.2), (7.2, 13, 8.4)) if front else ((-3.8, -5, -4.6), (7.6, 13.5, 9.2))
    return Part(name, pivot=(x, 8, z), boxes=[
        Box(thigh[0], thigh[1], bear_fur()),
        Box((-3.0, 5, -3.6), (6.0, 4, 7.0), bear_fur()),
    ], children=[
        Part(name + "_muscle", pivot=(0, -1, 0), rotation=(0.12 if front else -0.12, 0, 0), boxes=[Box((-4.0, -3, -3.4), (8.0, 8, 6.8), bear_fur())]),
        Part(name + "_lower", pivot=(0, 8, 0.2 if front else 1.0), boxes=[
            Box((-2.7, 0, -2.7), (5.4, 5.6, 5.4), bear_fur()),
            Box((-3.0, 0.4, 1.6), (6.0, 3.0, 1.6), bear_fur()),
        ], children=[
            Part(name + "_fringe", pivot=(0, -0.5, 2.6), rotation=(-0.25, 0, 0), boxes=[Box((-2.8, 0, -0.6), (5.6, 5.0, 1.4), bear_fur())]),
            Part(name + "_paw", pivot=(0, 5.0, 0), boxes=[
                Box((-3.4, 0, -4.6), (6.8, 3.0, 8.0), paw),
            ], children=claws),
        ]),
    ])


def tail():
    """A heavy tail of tapering segments: it falls from the hips and curls up at the end, the tip ringed in black."""
    count = 9
    first = parent = None
    bends = [-0.75, 0.05, 0.1, 0.12, 0.14, 0.16, 0.18, 0.2, 0.22]
    for k in range(count):
        w = 5.4 - 3.4 * k / (count - 1)
        style = leopard() if k < count - 2 else solid(SPOT, k=0.12)
        name = "tail" if k == 0 else "tail_%d" % k
        seg = Part(name, pivot=(0, -4.5, 16) if k == 0 else (0, 0, 3.6), rotation=(bends[k], 0, 0), boxes=[
            Box((-w / 2, -w / 2, -0.3), (w, w, 4.2), style),
        ])
        if first is None:
            first = seg
        else:
            parent.children.append(seg)
        parent = seg
    return first


body_style = leopard()
parts = [
    Part("body", pivot=(0, 4, 0), boxes=[
        Box((-7.6, -9.5, -17.5), (15.2, 15.5, 12.5), body_style),
        Box((-7.0, -8.5, -6.0), (14.0, 14.0, 13.0), body_style),
        Box((-7.6, -9.0, 6.0), (15.2, 14.5, 11.0), body_style),
        Box((-5.6, 3.6, -13), (11.2, 3.6, 22), body_style),
        Box((-6.6, -4.5, -19.2), (13.2, 12, 2.4), body_style),
        Box((-9.4, -12.2, -21.0), (18.8, 8.6, 9.0), body_style),
        Box((-2.2, -10.6, -8), (4.4, 2.0, 16), body_style),
    ], children=[
        Part("shoulder_r", pivot=(-6.4, -7.5, -11), rotation=(0, 0, -0.32), boxes=[Box((-3.2, -3, -5.8), (6.4, 9, 11.6), body_style)]),
        Part("shoulder_l", pivot=(6.4, -7.5, -11), rotation=(0, 0, 0.32), boxes=[Box((-3.2, -3, -5.8), (6.4, 9, 11.6), body_style)]),
        Part("haunch_r", pivot=(-6.6, -5, 11.5), rotation=(0, 0, -0.14), boxes=[Box((-2.8, -4, -5.8), (5.6, 12, 11.6), body_style)]),
        Part("haunch_l", pivot=(6.6, -5, 11.5), rotation=(0, 0, 0.14), boxes=[Box((-2.8, -4, -5.8), (5.6, 12, 11.6), body_style)]),
        Part("ribs_r", pivot=(-7.2, -1, 0.5), rotation=(0, 0, 0.08), boxes=[Box((-0.8, -6, -6), (1.6, 11, 12), body_style)]),
        Part("ribs_l", pivot=(7.2, -1, 0.5), rotation=(0, 0, -0.08), boxes=[Box((-0.8, -6, -6), (1.6, 11, 12), body_style)]),
        tail(),
    ] + [neck(i) for i in range(7)]),
    leg("front_right_leg", -6.4, -11.5, True),
    leg("front_left_leg", 6.4, -11.5, True),
    leg("back_right_leg", -6.6, 11.5, False),
    leg("back_left_leg", 6.6, 11.5, False),
]

if __name__ == "__main__":
    build("Beast", "beast", parts, size=512, seed=13, density=4)
