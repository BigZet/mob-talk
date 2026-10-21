"""Архангел гнева, after the icons of the Archangel Michael: a warrior angel in gold scale armour over a crimson tunic, a red
cloak (корзно), a noble face with chestnut curls and a gold nimbus, a flaming sword raised in his right hand, a spear with a
cross in his left, the trumpet of the seventh angel at his belt (Revelation 8), and two great wings of layered feathers.
Built about 46 units tall and drawn at scale 2.5 (about seven blocks), at 4 pixels per unit so every feather and fold is painted."""
from boxtex import *

SKIN = (214, 170, 128)
HAIR = (112, 66, 30)
CRIMSON = (150, 26, 30)
CRIMSON_DARK = (96, 14, 20)
WING = (252, 246, 230)
OCHRE = (196, 128, 52)
WOOD = (84, 56, 32)


def breastplate(p, f, opts):
    """Gold plate over the chest with a red-enamelled cross in a ring, like the clasp of an icon's armour."""
    gold_leaf()(p, f, opts)
    if f.side != "front":
        return
    d = opts.get("density", 1)
    cx, cy = f.w // 2, f.h // 2
    r = min(f.w, f.h) * 0.48
    for v in range(f.h):
        for u in range(f.w):
            dist = math.hypot(u - cx, v - cy)
            if dist < r - d * 0.7:
                p.put(f, u, v, shade(CRIMSON_DARK, p.noise(0.05)))
            elif dist < r + d * 0.3:
                p.put(f, u, v, GOLD_DARK)
    orthodox_cross(p, f, cx, cy, int(r * 1.75), GOLD_LIGHT, thickness=max(1, d // 2))


def leather_strip(p, f, opts):
    """A pteryx: a strip of crimson leather with a gold edge and a gold fringe at the end."""
    d = opts.get("density", 1)
    for v in range(f.h):
        for u in range(f.w):
            c = shade(CRIMSON, p.noise(0.06) * (0.9 + 0.1 * math.sin(v * 0.6)))
            if u < d or u >= f.w - d:
                c = GOLD
            if v >= f.h - 2 * d:
                c = GOLD_LIGHT if (u // max(1, d // 2)) % 2 == 0 else GOLD_DARK
            p.put(f, u, v, c)


def one_eye_face(p, f, opts):
    """Ochre flesh softly modelled, with no nose or mouth: the single eye is its own plane in front."""
    if f.side != "front":
        col = HAIR if f.side in ("back", "top") else SKIN
        for v in range(f.h):
            for u in range(f.w):
                p.put(f, u, v, shade(col, p.noise(0.05)))
        return
    for v in range(f.h):
        for u in range(f.w):
            dx = (u - f.w / 2) / (f.w / 2)
            c = shade(SKIN, 1.08 - 0.3 * dx * dx)
            if v < f.h * 0.16:
                c = shade(HAIR, p.noise(0.08))
            p.put(f, u, v, shade(c, p.noise(0.03)))


def great_eye(p, f, opts):
    """One great almond eye: dark lids and lashes, a white, a burning gold iris with a black pupil and a highlight."""
    W, H = f.w, f.h
    cx, cy = W / 2, H / 2
    for v in range(H):
        for u in range(W):
            if f.side != "front":
                p.put(f, u, v, SKIN)
                continue
            nx, ny = (u + 0.5 - cx) / (W / 2), (v + 0.5 - cy) / (H / 2)
            lid = 1 - nx * nx
            if abs(ny) > lid:
                p.put(f, u, v, None if abs(ny) > lid + 0.25 else shade(SKIN, 0.8))
                continue
            if abs(ny) > lid - 0.18:
                p.put(f, u, v, (40, 24, 18))
                continue
            r = math.hypot(nx * W / H, ny)
            if r < 0.28:
                c = (10, 6, 4)
            elif r < 0.78:
                c = mix(GOLD_LIGHT, FLAME, (r - 0.28) / 0.5)
            else:
                c = (236, 230, 214)
            glow = c if r < 0.78 and r >= 0.28 else None
            if 0.1 < nx * W / H + 0.3 < 0.22 and -0.45 < ny < -0.3:
                c, glow = (255, 255, 240), (255, 255, 240)
            p.put(f, u, v, c, glow)


def toroki(p, f, opts):
    """A thin ribbon of light gold with a darker edge; it narrows toward its fluttering end."""
    for v in range(f.h):
        for u in range(f.w):
            t = v / max(1, f.h - 1)
            if f.w > 2 and abs(u + 0.5 - f.w / 2) > (f.w / 2) * (1 - 0.5 * t):
                p.put(f, u, v, None)
                continue
            edge = u in (0, f.w - 1)
            p.put(f, u, v, GOLD_DARK if edge else mix(GOLD_LIGHT, (255, 250, 230), 0.4 * (1 - t)))


wings = []
for side, label in ((-1, "right"), (1, "left")):
    inner = wing(label + "_wing", pivot=(side * 3.4, -11.5, 3.6), rotation=(0.18, -side * 0.35, -side * 0.85), span=19, rows=3, feathers=10,
                 base=WING, tip=OCHRE, side=side)
    tip = wing(label + "_wing_tip", pivot=(side * 19, 0, 0), rotation=(0, 0, side * 0.6), span=17, rows=3, feathers=10,
               base=WING, tip=GOLD, side=side)
    inner.children.append(tip)
    wings.append(inner)

cloak_panels = []
for i in range(7):
    cloak_panels.append(Part("cloak_%d" % i, pivot=(-5.4 + i * 1.8, 0, 0.12 * abs(i - 3)), rotation=(0.08 + 0.02 * math.sin(i * 1.9), 0.06 * (i - 3), 0.015 * (i - 3)),
                             boxes=[Box((-1.5, 0, 0), (3.0, 26 + 1.5 * math.sin(i * 2.7), 0.6),
                                        drapery(CRIMSON, highlight=(214, 80, 70), hem=GOLD, hem_width=1))]))


def arm(label, side, hand_children):
    pauldrons = [Part("%s_pauldron_%d" % (label, k), pivot=(side * 0.6, -1.2 + k * 1.6, 0), rotation=(0, 0, side * (0.35 + 0.12 * k)),
                      boxes=[Box((-2.2 + side * 0.4, 0, -2.3), (4.4, 2, 4.6), scale_mail((206, 160, 70), GOLD_DARK))]) for k in range(3)]
    return Part(label, pivot=(side * 5.6, -14.2, 0), boxes=[
        Box((-1.5, -0.5, -1.5), (3, 7, 3), drapery(CRIMSON, highlight=(214, 80, 70))),
        Box((-1.7, 6, -1.7), (3.4, 6, 3.4), gold_leaf()),
        Box((-1.3, 12, -1.3), (2.6, 2.6, 2.6), solid(SKIN, k=0.05)),
    ], children=pauldrons + hand_children)


sword = Part("sword", pivot=(0, 13.2, -0.4), rotation=(0.7, 0, -0.3), boxes=[
    Box((-0.55, -1.4, -0.55), (1.1, 4.2, 1.1), solid((70, 40, 24))),
    Box((-3.2, -2.4, -0.7), (6.4, 1.1, 1.4), gold_leaf(ornament=False)),
    Box((-0.9, 2.8, -0.9), (1.8, 1.4, 1.8), gold_leaf(ornament=False)),
    Box((-1.5, -29, -0.2), (3, 26.6, 0.4), flame_blade(), flat=True),
    Box((-0.5, -27, -0.45), (1, 24, 0.9), flame_blade(), flat=True),
], children=[
    Part("sword_tip", pivot=(0, -29, 0), rotation=(0, 0, 0.785), boxes=[Box((-0.9, -0.9, -0.15), (1.8, 1.8, 0.3), solid((255, 246, 214)), flat=True)]),
])
spear = Part("spear", pivot=(0, 13.2, 0), rotation=(0.05, 0, 0.3), boxes=[
    Box((-0.45, -30, -0.45), (0.9, 46, 0.9), solid(WOOD, k=0.08)),
    Box((-0.6, -35, -0.6), (1.2, 5, 1.2), gold_leaf(ornament=False)),
    Box((-2.4, -33.5, -0.5), (4.8, 1, 1), gold_leaf(ornament=False)),
    Box((-1.6, -35.6, -0.5), (3.2, 0.8, 1), gold_leaf(ornament=False)),
    Box((-1, -36.4, -0.6), (2, 1.2, 1.2), solid(CANDLE), flat=True),
])
trumpet = Part("trumpet", pivot=(4.6, -3, -0.5), rotation=(0.2, 0, 0.5), boxes=[
    Box((-0.4, -5, -0.4), (0.8, 5, 0.8), gold_leaf(ornament=False)),
    Box((-0.7, -7, -0.7), (1.4, 2, 1.4), gold_leaf(ornament=False)),
    Box((-1.2, -8.4, -1.2), (2.4, 1.4, 2.4), gold_leaf(ornament=False)),
])


def leg(label, x):
    return Part(label, pivot=(x, -21, 0), boxes=[
        Box((-1.7, 0, -1.7), (3.4, 11, 3.4), drapery(CRIMSON_DARK)),
        Box((-1.95, 10, -1.95), (3.9, 9, 3.9), gold_leaf()),
        Box((-2, 19, -2.8), (4, 2, 4.8), solid(BRONZE, k=0.08)),
    ])


parts = [
    Part("figure", pivot=(0, 24, 0), children=[
        leg("right_leg", -2.1),
        leg("left_leg", 2.1),
        skirt("tunic", pivot=(0, -22.5, 0), top_radius=4.2, bottom_radius=6.6, height=13, panels=14,
              style=drapery(CRIMSON, highlight=(226, 96, 80), hem=GOLD, hem_width=1)),
        skirt("pteruges", pivot=(0, -22.8, 0), top_radius=4.6, bottom_radius=6.0, height=8.5, panels=12, style=leather_strip),
        Part("body", pivot=(0, -21, 0), boxes=[
            Box((-3.6, -7, -2.6), (7.2, 7, 5.2), scale_mail((190, 146, 62), GOLD_DARK)),
            Box((-4.6, -15, -3.1), (9.2, 8.2, 6.2), scale_mail((190, 146, 62), GOLD_DARK)),
            Box((-3.8, -14.6, -3.6), (7.6, 6.4, 0.7), breastplate),
            Box((-4.2, -8, -3.0), (8.4, 1.4, 5.8), gold_leaf(ornament=False)),
            Box((-3.2, -15.8, -2.4), (6.4, 1.2, 4.8), scale_mail((190, 146, 62), GOLD_DARK)),
            Box((-3.9, -1.2, -2.9), (7.8, 1.6, 5.8), gold_leaf()),
            Box((-1.3, -16.8, -1.3), (2.6, 2.2, 2.6), solid(SKIN, k=0.05)),
            Box((-5.2, -14.8, -1.8), (2.2, 1.6, 2.2), gold_leaf(ornament=False)),
        ], children=[
            Part("head", pivot=(0, -16.8, 0), boxes=[
                Box((-2.5, -6.2, -2.9), (5, 6.2, 5.6), one_eye_face),
            ], children=[
                Part("eye", pivot=(0, -3.0, -2.9), boxes=[Box((-2.3, -1.6, -0.45), (4.6, 3.2, 0.45), great_eye, flat=True)]),
                Part("hair", pivot=(0, 0, 0), boxes=[
                    Box((-2.8, -6.6, -2.4), (5.6, 1.4, 5.4), hair(HAIR)),
                    Box((-2.8, -5.4, 1.2), (5.6, 5.6, 1.9), hair(HAIR)),
                ]),
                Part("lock_r", pivot=(-2.4, -3.2, 1.6), rotation=(0.18, 0, 0.06), boxes=[Box((-0.7, 0, -0.9), (1.4, 5.6, 1.8), hair(HAIR))]),
                Part("lock_l", pivot=(2.4, -3.2, 1.6), rotation=(0.18, 0, -0.06), boxes=[Box((-0.7, 0, -0.9), (1.4, 5.6, 1.8), hair(HAIR))]),
                Part("toroki_r", pivot=(-2.7, -4.6, 2.2), rotation=(0.9, 0.25, 0.1), boxes=[Box((-0.35, 0, -0.05), (0.7, 7, 0.1), toroki, flat=True)]),
                Part("toroki_l", pivot=(2.7, -4.6, 2.2), rotation=(0.9, -0.25, -0.1), boxes=[Box((-0.35, 0, -0.05), (0.7, 7, 0.1), toroki, flat=True)]),
                Part("diadem", pivot=(0, -5.6, -2.95), boxes=[Box((-2.6, -0.6, 0), (5.2, 1.0, 0.4), gold_leaf(ornament=False))]),
                halo("halo", pivot=(0, -4.4, 3.4), radius=7.5),
            ]),
            Part("cloak", pivot=(0, -15, 3.0), children=cloak_panels),
            Part("chest_r", pivot=(-4.4, -11, -0.2), rotation=(0, 0.35, 0), boxes=[Box((-0.8, -3.8, -3), (1.6, 7.6, 6), scale_mail((190, 146, 62), GOLD_DARK))]),
            Part("chest_l", pivot=(4.4, -11, -0.2), rotation=(0, -0.35, 0), boxes=[Box((-0.8, -3.8, -3), (1.6, 7.6, 6), scale_mail((190, 146, 62), GOLD_DARK))]),
            Part("waist_r", pivot=(-3.4, -3.5, 0), rotation=(0, 0, 0.12), boxes=[Box((-0.6, -3.5, -2.5), (1.2, 7, 5), scale_mail((190, 146, 62), GOLD_DARK))]),
            Part("waist_l", pivot=(3.4, -3.5, 0), rotation=(0, 0, -0.12), boxes=[Box((-0.6, -3.5, -2.5), (1.2, 7, 5), scale_mail((190, 146, 62), GOLD_DARK))]),
            arm("right_arm", -1, [sword]),
            arm("left_arm", 1, [spear]),
            trumpet,
        ] + wings),
    ]),
]

if __name__ == "__main__":
    build("Archangel", "archangel", parts, size=256, seed=31, density=4)
