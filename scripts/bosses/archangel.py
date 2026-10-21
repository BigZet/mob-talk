"""Архангел гнева: a tall archangel in gold-and-crimson armour, a crested helmet with a burning gaze, a halo of light, two great
white-gold wings, a flaming sword in his right hand and the trumpet of the seventh angel at his belt (Revelation 8)."""
from boxtex import *

CRIMSON = (150, 24, 28)
CRIMSON_DARK = (84, 12, 16)
FEATHER = (236, 230, 214)
FEATHER_SHADE = (196, 184, 160)


def armour(base=GOLD, band=CRIMSON):
    """Old gold plates with crimson enamel bands and dark rivets."""
    def paint(p, f, opts):
        for v in range(f.h):
            for u in range(f.w):
                t = v / max(1, f.h - 1)
                c = shade(mix(GOLD_LIGHT, base, 0.4 + 0.5 * t), p.noise(0.08))
                if f.side in ("front", "back", "left", "right") and f.h >= 6 and (v % 6 == 3):
                    c = shade(band, p.noise(0.1))
                if u in (0, f.w - 1):
                    c = GOLD_DARK
                p.put(f, u, v, c)
    return paint


def cuirass(p, f, opts):
    """The breastplate: gold with a crimson field and a gold cross on the front."""
    armour()(p, f, opts)
    if f.side != "front":
        return
    for v in range(2, f.h - 2):
        for u in range(2, f.w - 2):
            p.put(f, u, v, shade(CRIMSON, p.noise(0.08)))
    draw_cross(p, f, f.w // 2, 3, max(8, f.h - 8), GOLD_LIGHT)


def helmet(p, f, opts):
    armour(GOLD, CRIMSON_DARK)(p, f, opts)
    if f.side == "front":
        # The visor slit with a burning gaze.
        ey = f.h // 2 - 1
        for u in range(1, f.w - 1):
            p.put(f, u, ey, (12, 8, 6))
            p.put(f, u, ey + 1, (12, 8, 6))
        for ex in (f.w // 2 - 2, f.w // 2 + 1):
            p.put(f, ex, ey, CANDLE, CANDLE)
            p.put(f, ex + 1, ey, CANDLE, CANDLE)
            p.put(f, ex, ey + 1, FLAME, FLAME)
            p.put(f, ex + 1, ey + 1, FLAME, FLAME)
        for v in range(ey + 2, f.h):
            p.put(f, f.w // 2, v, GOLD_DARK)


def plume(p, f, opts):
    cloth(CRIMSON, folds=True)(p, f, opts)


def feathers(p, f, opts):
    """White-gold feathers in overlapping rows, gilded at the tips."""
    for v in range(f.h):
        for u in range(f.w):
            row = v % 5
            c = mix(FEATHER, FEATHER_SHADE, row / 5)
            if (u + (v // 5) * 2) % 4 == 0:
                c = shade(c, 0.85)
            if row == 4:
                c = mix(c, GOLD_LIGHT, 0.5)
            p.put(f, u, v, shade(c, p.noise(0.05)))
    if f.side in ("front", "back"):
        # Gold-edged long primaries at the bottom of the wing.
        for u in range(0, f.w, 3):
            p.put(f, u, f.h - 1, GOLD)


def wing_bone(p, f, opts):
    for v in range(f.h):
        for u in range(f.w):
            p.put(f, u, v, shade(mix(FEATHER, GOLD_LIGHT, 0.35), p.noise(0.06)))


def blade(p, f, opts):
    """The flaming sword: a white-hot core along the middle, fire at the edges."""
    for v in range(f.h):
        for u in range(f.w):
            t = abs(u - (f.w - 1) / 2) / max(1, f.w / 2)
            c = mix(CANDLE, FLAME, t) if f.side not in ("top", "bottom") else CANDLE
            if p.rand.random() < 0.12:
                c = mix(c, EMBER, 0.5)
            p.put(f, u, v, c, c)


def halo(p, f, opts):
    for v in range(f.h):
        for u in range(f.w):
            c = shade(mix(GOLD_LIGHT, CANDLE, p.rand.random()), p.noise(0.05))
            p.put(f, u, v, c, c)


def trumpet(p, f, opts):
    gold(trim=False)(p, f, opts)
    if f.side == "front" and f.w >= 3:
        for v in range(1, f.h - 1):
            for u in range(1, f.w - 1):
                p.put(f, u, v, GOLD_DARK)


skin = solid((214, 180, 140), k=0.06)
leather = solid((70, 40, 28), k=0.12)

wing_parts = lambda side: Part(side + "_wing", pivot=(3.5 if side == "left" else -3.5, -17, 3.5),
                               rotation=(0.25, -1.1 if side == "left" else 1.1, 0), boxes=[
    Box((0, -2, -1), (20, 3, 2), wing_bone) if side == "left" else Box((-20, -2, -1), (20, 3, 2), wing_bone),
    Box((0, 1, -0.5), (20, 18, 1), feathers) if side == "left" else Box((-20, 1, -0.5), (20, 18, 1), feathers),
], children=[
    Part(side + "_wing_tip", pivot=(20 if side == "left" else -20, 0, 0), boxes=[
        Box((0, -1, -1), (12, 2, 2), wing_bone) if side == "left" else Box((-12, -1, -1), (12, 2, 2), wing_bone),
        Box((0, 1, -0.5), (12, 26, 1), feathers) if side == "left" else Box((-12, 1, -0.5), (12, 26, 1), feathers),
    ]),
])

parts = [
    Part("body", pivot=(0, 4, 0), boxes=[
        Box((-6, -20, -3.5), (12, 20, 7), cuirass),
        Box((-6.5, -2, -4), (13, 2, 8), gold()),
        Box((-7, 0, -4), (14, 8, 8), armour(CRIMSON, GOLD_DARK)),
    ], children=[
        Part("head", pivot=(0, -20, 0), boxes=[
            Box((-4.5, -10, -4.5), (9, 10, 9), helmet),
            Box((-1, -14, -3), (2, 4, 9), plume),
            Box((-3, -3.5, -4.9), (6, 3, 0.4), skin),
        ], children=[
            Part("halo", pivot=(0, -7, 6), boxes=[
                Box((-7, -7, 0), (14, 1.5, 1), halo),
                Box((-7, 5.5, 0), (14, 1.5, 1), halo),
                Box((-7, -5.5, 0), (1.5, 11, 1), halo),
                Box((5.5, -5.5, 0), (1.5, 11, 1), halo),
            ]),
        ]),
        Part("right_arm", pivot=(-8, -18, 0), boxes=[
            Box((-3, -2, -3.5), (6, 5, 7), armour()),
            Box((-2.5, 3, -2.5), (5, 15, 5), armour(GOLD, CRIMSON_DARK)),
        ], children=[
            Part("sword", pivot=(0, 17, -1), rotation=(1.57, 0, 0), boxes=[
                Box((-0.5, -4, -0.5), (1, 6, 1), leather),
                Box((-3.5, -5, -1), (7, 1.5, 2), gold(trim=False)),
                Box((-1, -32, -0.5), (2, 27, 1), blade),
            ]),
        ]),
        Part("left_arm", pivot=(8, -18, 0), boxes=[
            Box((-3, -2, -3.5), (6, 5, 7), armour()),
            Box((-2.5, 3, -2.5), (5, 15, 5), armour(GOLD, CRIMSON_DARK)),
        ]),
        Part("trumpet", pivot=(7.5, 2, -1), rotation=(0, 0, -0.35), boxes=[
            Box((-0.75, -3, -0.75), (1.5, 12, 1.5), trumpet),
            Box((-2, 9, -2), (4, 3, 4), trumpet),
        ]),
        wing_parts("left"),
        wing_parts("right"),
    ]),
    Part("right_leg", pivot=(-3.5, 4, 0), boxes=[
        Box((-3, 0, -3), (6, 17, 6), armour(GOLD, CRIMSON_DARK)),
        Box((-3.5, 17, -4.5), (7, 3, 8), gold()),
    ]),
    Part("left_leg", pivot=(3.5, 4, 0), boxes=[
        Box((-3, 0, -3), (6, 17, 6), armour(GOLD, CRIMSON_DARK)),
        Box((-3.5, 17, -4.5), (7, 3, 8), gold()),
    ]),
]

if __name__ == "__main__":
    build("Archangel", "archangel", parts, size=128, seed=20)
