"""Ангел-губитель (Exodus 12; 1 Chronicles 21:16): the dread angel of the night. A tall hooded figure with no face at all, only
one cold light deep in the hood; a long shadow robe that does not end in a hem but frays into strips of smoke with ragged,
see-through edges; a mantle over gaunt shoulders; ashen, bony hands; vast dark wings of single black feathers with ashen tips;
a drawn sword of cold pale fire; blood-red sash and baldric, the blood on the lintel he passes over. Built about 44 units tall
and drawn at scale 2.1 (about six blocks, wings about ten across), at 4 pixels per unit."""
from boxtex import *

SHADOW = (16, 14, 20)
SHADOW_2 = (26, 23, 32)
SHADOW_LIGHT = (62, 58, 74)
ASH = (150, 144, 138)
ASH_DARK = (92, 88, 90)
SMOKE = (48, 46, 54)
FEATHER = (20, 18, 24)
FEATHER_SHAFT = (58, 54, 64)
BONE_HAND = (150, 146, 138)
VOID = (3, 2, 6)
COLD = (214, 236, 255)
COLD_MID = (150, 196, 240)
COLD_DIM = (70, 110, 170)
IRON = (40, 40, 46)
BLOOD_RED = (128, 14, 20)
BLOOD_LIGHT = (196, 40, 40)

robe_cloth = drapery(SHADOW, highlight=SHADOW_LIGHT)
hood_cloth = drapery(SHADOW_2, highlight=SHADOW_LIGHT)
sash_cloth = drapery(BLOOD_RED, highlight=BLOOD_LIGHT)


def smoke_strip(base=SHADOW, fray=0.45):
    """A strip of the robe that turns to smoke: icon folds above; below, every column ends at its own length, greying into
    ash-coloured wisps with transparent gaps, so the hem dissolves instead of ending."""
    def paint(p, f, opts):
        d = opts.get("density", 1)
        sides = f.side in ("front", "back", "left", "right")
        cut = [1 - fray * p.rand.uniform(0.15, 1.0) for _ in range(f.w)]
        for u in range(1, f.w):
            cut[u] = 0.55 * cut[u] + 0.45 * cut[u - 1]
        period = max(3, 3 * d)
        for v in range(f.h):
            t = v / max(1, f.h - 1)
            for u in range(f.w):
                ph = ((u + 0.2 * v / max(1, d)) % period) / period
                c = shade(base, (0.75 + 0.4 * math.sin(ph * math.pi)) * p.noise(0.05))
                if sides and 0.47 < ph < 0.53 and p.rand.random() < 0.7:
                    c = mix(c, SHADOW_LIGHT, 0.45)
                if sides:
                    end = cut[u]
                    if t > end:
                        p.put(f, u, v, None)
                        continue
                    smoke = max(0.0, (t - (end - fray * 0.6)) / (fray * 0.6))
                    if smoke > 0:
                        c = mix(c, SMOKE, smoke * 0.8)
                        if p.rand.random() < smoke * 0.55:
                            p.put(f, u, v, None)
                            continue
                p.put(f, u, v, c)
    return paint


def ragged_sleeve(p, f, opts):
    smoke_strip(SHADOW_2, fray=0.5)(p, f, opts)


def ragged_sash(p, f, opts):
    smoke_strip(BLOOD_RED, fray=0.3)(p, f, opts)


def cold_flame(p, f, opts):
    """A blade of cold fire: a narrow white core, pale ice-blue flame around it, steel-blue licks at the edges that flicker
    out into transparency."""
    W, H = f.w, f.h
    for v in range(H):
        for u in range(W):
            across = abs(u + 0.5 - W / 2) / (W / 2) if W > 1 else 0
            lick = 0.3 * math.sin(v * 0.45 + u * 0.9) + 0.18 * math.sin(v * 1.3 + 2)
            t = min(1.0, across * (1 + lick))
            if t > 0.82 and p.rand.random() < 0.75:
                p.put(f, u, v, None)
                continue
            if t < 0.22:
                c = mix((246, 252, 255), (200, 232, 255), t / 0.22)
            elif t < 0.6:
                c = mix((176, 214, 250), COLD_MID, (t - 0.22) / 0.38)
            else:
                c = mix(COLD_MID, COLD_DIM, (t - 0.6) / 0.4)
            c = shade(c, 0.92 + 0.08 * math.sin(v * 0.33))
            p.put(f, u, v, c, c)


def glimmer(p, f, opts):
    """The faint cold glow around the single light: a few dim pixels fading out, transparent outside."""
    R = min(f.w, f.h) / 2
    for v in range(f.h):
        for u in range(f.w):
            r = math.hypot(u + 0.5 - f.w / 2, v + 0.5 - f.h / 2) / R
            if r > 1 or p.rand.random() < r * 0.9:
                p.put(f, u, v, None)
                continue
            c = mix((90, 130, 190), (8, 12, 26), r)
            p.put(f, u, v, c, c)


def void_arch(p, f, opts):
    """The opening of the hood: utter black under a pointed arch; the corners outside the arch are the hood's cloth."""
    d = opts.get("density", 1)
    for v in range(f.h):
        for u in range(f.w):
            dx = abs(u + 0.5 - f.w / 2) / (f.w / 2)
            top = f.h * 0.42 * (1 - (1 - dx) ** 0.6) if dx < 1 else f.h
            if v < top or dx > 0.92:
                p.put(f, u, v, shade(SHADOW_2, p.noise(0.06) * (0.8 + 0.2 * math.sin(u / max(1, d)))))
            else:
                p.put(f, u, v, shade(VOID, p.noise(0.02)))


def the_light(p, f, opts):
    for v in range(f.h):
        for u in range(f.w):
            r = math.hypot(u + 0.5 - f.w / 2, v + 0.5 - f.h / 2) / (min(f.w, f.h) / 2)
            c = mix((255, 255, 255), COLD, min(1.0, r))
            p.put(f, u, v, c, c)


def bony(p, f, opts):
    """Ashen skin over bone: grey, knuckle lines, darker in the hollows."""
    d = opts.get("density", 1)
    for v in range(f.h):
        for u in range(f.w):
            c = shade(BONE_HAND, 0.9 + 0.12 * math.sin(v / max(1, d) * 2.2))
            if v % (2 * d) == 0:
                c = shade(c, 0.7)
            p.put(f, u, v, shade(c, p.noise(0.05)))


iron = metal(IRON, (16, 16, 20), (90, 92, 104))
void = solid(VOID, k=0.02)


def dark_wing(name, pivot, rotation, span, rows=3, feathers=11, side=1):
    """A wing of single black feathers with ashen tips, layered from long primaries to coverts over the bone (like `wing`, but
    with dark shafts and longer, more ragged primaries)."""
    prim = feather(FEATHER, ASH, shaft=FEATHER_SHAFT)
    sec = feather(shade(FEATHER, 1.15), ASH_DARK, shaft=FEATHER_SHAFT)
    cov = feather(SHADOW_2, shade(ASH_DARK, 0.8), shaft=FEATHER_SHAFT)
    x0 = 0 if side > 0 else -span
    arm = Part(name, pivot=pivot, rotation=rotation, boxes=[Box((x0, -1.6, -1.6), (span, 3.2, 3.2), cov)])
    for row in range(rows):
        length = span * (0.95 - 0.27 * row)
        width = 3.2 - 0.6 * row
        style = prim if row == 0 else sec if row == 1 else cov
        for i in range(feathers):
            t = i / max(1, feathers - 1)
            x = side * span * (0.04 + 0.94 * t)
            fan = side * (t ** 1.5) * 1.15
            l = length * (0.55 + 0.6 * t) if row == 0 else length * (0.6 + 0.35 * t)
            arm.children.append(Part("%s_f%d_%d" % (name, row, i), pivot=(x, 0.6 + row * 0.8, 0.35 - row * 0.4),
                                     rotation=(0.05 * row + 0.03 * math.sin(i * 1.7), 0, -fan),
                                     boxes=[Box((-width / 2, 0, 0), (width, l, 0.2), style)]))
    return arm


def smoke_robe(name, pivot, top_radius, bottom_radius, height, strips, base, fray, seed, back_only=False):
    """A robe of separate strips around a ring, each tilted out like a bell and fraying into smoke at its own length."""
    rnd = random.Random(seed)
    part = Part(name, pivot=pivot)
    tilt = math.atan2(bottom_radius - top_radius, height)
    width = 2 * math.pi * max(top_radius, bottom_radius) / strips * 1.25
    for i in range(strips):
        a = 2 * math.pi * i / strips if not back_only else math.pi * (0.55 + 0.9 * i / max(1, strips - 1))
        h = height * rnd.uniform(0.85, 1.12)
        part.children.append(Part("%s_%d" % (name, i), pivot=(math.sin(a) * top_radius, 0, -math.cos(a) * top_radius),
                                  rotation=(-tilt + rnd.uniform(-0.05, 0.05), -a, rnd.uniform(-0.04, 0.04)),
                                  boxes=[Box((-width / 2, 0, -0.3), (width, h, 0.6), smoke_strip(base, fray))]))
    return part


def hand(name, side, grip, held=()):
    """A gaunt hand of ash and bone: a narrow palm and four long fingers, curled round a hilt or spread."""
    fingers = []
    for k in range(4):
        fx = side * (-0.75 + 0.5 * k)
        curl = 1.1 if grip else 0.25 + 0.1 * k
        fingers.append(Part("%s_finger_%d" % (name, k), pivot=(fx, 1.9, -0.5), rotation=(-curl * 0.6, 0, side * (0.08 * (k - 1.5))), boxes=[
            Box((-0.22, 0, -0.22), (0.44, 1.5, 0.44), bony),
        ], children=[Part("%s_finger_%d_tip" % (name, k), pivot=(0, 1.4, 0), rotation=(-curl * 0.7, 0, 0), boxes=[
            Box((-0.18, 0, -0.18), (0.36, 1.3, 0.36), bony),
        ])]))
    fingers.append(Part(name + "_thumb", pivot=(-side * 0.9, 0.9, -0.6), rotation=(-0.6, 0, -side * 0.5), boxes=[
        Box((-0.24, 0, -0.24), (0.48, 1.6, 0.48), bony)]))
    return Part(name, pivot=(0, 9.0, 0), boxes=[Box((-1.0, 0, -0.9), (2.0, 2.1, 1.8), bony)], children=fingers + list(held))


sword = Part("sword", pivot=(0, 2.2, -0.6), rotation=(0.95, 0, 0.18), boxes=[
    Box((-0.5, -1.8, -0.5), (1.0, 4.6, 1.0), solid((30, 20, 18), k=0.08)),
    Box((-3.2, -2.8, -0.65), (6.4, 1.0, 1.3), iron),
    Box((-0.8, 2.8, -0.8), (1.6, 1.4, 1.6), iron),
    Box((-0.45, -2.75, -0.8), (0.9, 0.9, 0.2), solid(BLOOD_LIGHT, glow=True), flat=True),
    Box((-1.6, -29.5, -0.2), (3.2, 26.8, 0.4), cold_flame, flat=True),
    Box((-0.2, -28.0, -1.1), (0.4, 25.0, 2.2), cold_flame, flat=True),
], children=[
    Part("sword_quillon_r", pivot=(-3.2, -2.3, 0), rotation=(0, 0, -0.5), boxes=[Box((-1.6, -0.4, -0.5), (1.6, 0.8, 1.0), iron)]),
    Part("sword_quillon_l", pivot=(3.2, -2.3, 0), rotation=(0, 0, 0.5), boxes=[Box((0, -0.4, -0.5), (1.6, 0.8, 1.0), iron)]),
])


def arm(label, side, hand_part):
    return Part(label, pivot=(side * 5.6, -12.6, 0), boxes=[
        Box((-1.7, -0.8, -1.7), (3.4, 9.0, 3.4), robe_cloth),
    ], children=[
        Part(label.replace("arm", "forearm"), pivot=(0, 7.5, 0), boxes=[
            Box((-1.9, -0.4, -1.9), (3.8, 6.0, 3.8), robe_cloth),
        ], children=[
            Part(label.replace("arm", "sleeve"), pivot=(0, 3.0, 0), boxes=[
                Box((-2.7, 0, -2.7), (5.4, 6.8, 5.4), ragged_sleeve),
            ]),
            hand_part,
        ]),
    ])


def hood():
    """The hood: a deep cowl with a pointed tail, an overhanging brim, nothing inside but darkness and one cold light."""
    return Part("head", pivot=(0, -14.6, 0), boxes=[
        Box((-3.5, -8.4, -0.8), (7.0, 8.8, 4.6), hood_cloth),
        Box((-3.7, -9.6, -4.4), (7.4, 1.6, 7.8), hood_cloth),
        Box((-4.4, -1.2, -3.4), (8.8, 2.6, 7.4), hood_cloth),
        Box((-2.8, -8.2, -3.7), (5.6, 8.2, 0.2), void_arch, flat=True),
        Box((-1.2, -5.7, -3.82), (2.4, 2.4, 0.05), glimmer, flat=True),
        Box((-0.4, -4.9, -3.95), (0.8, 0.8, 0.1), the_light, flat=True),
    ], children=[
        Part("hood_r", pivot=(-3.3, -4.6, -1.0), rotation=(0, -0.12, 0.04), boxes=[Box((-0.7, -5.0, -3.8), (1.4, 9.8, 5.0), hood_cloth)]),
        Part("hood_l", pivot=(3.3, -4.6, -1.0), rotation=(0, 0.12, -0.04), boxes=[Box((-0.7, -5.0, -3.8), (1.4, 9.8, 5.0), hood_cloth)]),
        Part("hood_brim", pivot=(0, -9.0, -4.3), rotation=(0.35, 0, 0), boxes=[Box((-3.8, 0, -1.4), (7.6, 1.5, 1.8), hood_cloth)]),
        Part("hood_tip", pivot=(0, -8.2, 2.6), rotation=(-1.15, 0, 0), boxes=[Box((-1.6, -4.2, -1.6), (3.2, 4.6, 3.2), hood_cloth)], children=[
            Part("hood_tip_2", pivot=(0, -4.0, 0), rotation=(-0.45, 0, 0), boxes=[Box((-0.9, -3.4, -0.9), (1.8, 3.6, 1.8), hood_cloth)]),
        ]),
    ])


wings = []
for side, label in ((-1, "right"), (1, "left")):
    inner = dark_wing(label + "_wing", pivot=(side * 2.2, -11.4, 3.0), rotation=(0.12, -side * 0.28, -side * 0.5), span=18, side=side)
    tip = dark_wing(label + "_wing_tip", pivot=(side * 18, 0, 0), rotation=(0, -side * 0.08, side * 0.42), span=17, side=side)
    inner.children.append(tip)
    wings.append(inner)

parts = [
    Part("figure", pivot=(0, 24, 0), children=[
        Part("robe_core", pivot=(0, -20, 0), boxes=[
            Box((-3.4, 0, -2.6), (6.8, 13, 5.2), robe_cloth),
            Box((-2.6, 13, -2.0), (5.2, 4, 4.0), smoke_strip(SHADOW, 0.6)),
        ]),
        smoke_robe("robe_in", pivot=(0, -20.6, 0), top_radius=3.3, bottom_radius=5.2, height=17, strips=12, base=SHADOW, fray=0.35, seed=5),
        smoke_robe("robe", pivot=(0, -20.2, 0), top_radius=3.9, bottom_radius=7.2, height=16.5, strips=16, base=SHADOW_2, fray=0.55, seed=7),
        smoke_robe("trail", pivot=(0, -22, 1.2), top_radius=3.6, bottom_radius=6.4, height=20, strips=5, base=SHADOW, fray=0.6, seed=9, back_only=True),
        Part("body", pivot=(0, -20, 0), boxes=[
            Box((-3.4, -6.2, -2.4), (6.8, 6.4, 4.8), robe_cloth),
            Box((-4.3, -13.2, -2.7), (8.6, 7.4, 5.4), robe_cloth),
            Box((-5.4, -13.8, -2.5), (10.8, 2.4, 5.0), robe_cloth),
            Box((-1.3, -15.0, -1.3), (2.6, 1.8, 2.6), solid(SHADOW, k=0.05)),
            Box((-3.7, -1.7, -2.7), (7.4, 2.0, 5.4), sash_cloth),
            Box((1.9, -2.1, -3.2), (2.2, 2.6, 1.0), sash_cloth),
        ], children=[
            Part("sash_end_0", pivot=(2.6, 0.2, -2.9), rotation=(-0.05, 0, -0.06), boxes=[Box((-0.7, 0, -0.15), (1.4, 12, 0.3), ragged_sash)]),
            Part("sash_end_1", pivot=(3.6, 0.2, -2.6), rotation=(-0.02, 0, -0.14), boxes=[Box((-0.6, 0, -0.15), (1.2, 9.5, 0.3), ragged_sash)]),
            Part("baldric_front", pivot=(0, -8.6, -2.85), rotation=(0, 0, 0.62), boxes=[Box((-0.8, -6.6, -0.2), (1.6, 13.2, 0.4), sash_cloth)]),
            Part("baldric_back", pivot=(0, -8.6, 2.85), rotation=(0, 0, 0.62), boxes=[Box((-0.8, -6.6, -0.2), (1.6, 13.2, 0.4), sash_cloth)]),
            skirt("mantle", pivot=(0, -14.0, 0), top_radius=3.0, bottom_radius=6.4, height=7.5, panels=12, style=hood_cloth),
            hood(),
            arm("right_arm", -1, hand("right_hand", -1, True, [sword])),
            arm("left_arm", 1, hand("left_hand", 1, False)),
        ] + wings),
    ]),
]

if __name__ == "__main__":
    build("DestroyingAngel", "destroying_angel", parts, size=256, seed=23, density=4)
