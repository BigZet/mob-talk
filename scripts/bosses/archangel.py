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
    r = min(f.w, f.h) * 0.36
    for v in range(f.h):
        for u in range(f.w):
            if r - d * 0.6 < math.hypot(u - cx, v - cy) < r + d * 0.4:
                p.put(f, u, v, GOLD_DARK)
    draw_cross(p, f, cx, int(cy - r * 0.8), int(r * 1.6), CINNABAR)
    draw_cross(p, f, cx + 1, int(cy - r * 0.8), int(r * 1.6), CINNABAR)


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


wings = []
for side, label in ((-1, "right"), (1, "left")):
    inner = wing(label + "_wing", pivot=(side * 2.4, -13, 3.2), rotation=(0.18, -side * 0.35, -side * 0.85), span=19, rows=3, feathers=10,
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
                Box((-2.5, -6.2, -2.9), (5, 6.2, 5.6), icon_face(SKIN, HAIR, eye=(52, 36, 26), stern=True)),
            ], children=[
                curls("curls", pivot=(0, -4.4, 0.4), count=11, radius=2.9, length=4.2, color=HAIR, seed=7),
                Part("diadem", pivot=(0, -5.6, -2.95), boxes=[Box((-2.6, -0.6, 0), (5.2, 1.0, 0.4), gold_leaf(ornament=False))]),
                halo("halo", pivot=(0, -3.2, 3.6), radius=6.2),
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
